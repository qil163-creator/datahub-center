package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresTransactionRoute;
import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt;

import java.sql.Connection;
import java.sql.SQLException;

/** 包内 provisional 状态，不能作为已确认回执发布；不持久化原始账号、库名、地址或 SQL。 */
record PostgresCommitWitness(TransactionCommitReceipt receipt, int backend, String relationReference) {

    private static final String ROUTE = ", " + PostgresTransactionRoute.COLUMNS
            + ", pg_catalog.pg_backend_pid()" + PostgresTransactionRoute.FROM;

    /**
     * 未原生验收的版本仍可走原有写入，但不伪造原生见证；不借用兼容产品的枚举。
     */
    static PostgresCommitWitness begin(Connection connection, String schema, String table) throws SQLException {
        var metadata = connection.getMetaData();
        if (!"PostgreSQL".equals(metadata.getDatabaseProductName())) {
            throw new SQLException("Unexpected write product");
        }
        // 未原生验收的版本仍可走原有写入，但不伪造原生见证；不借用兼容产品的枚举。
        if (metadata.getDatabaseMajorVersion() != 16) {
            return null;
        }
        var dialect = new cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl();
        return sample(connection, dialect.quoteIdentifier(schema) + "." + dialect.quoteIdentifier(table));
    }

    /**
     * 返回当前样本。
     */
    private static PostgresCommitWitness sample(Connection connection, String reference) throws SQLException {
        if (connection.getAutoCommit() || connection.isReadOnly() || Thread.currentThread().isInterrupted()) {
            throw new SQLException("Write witness transaction is unavailable");
        }
        String relation = PostgresRelationIdentity.storageFingerprint(connection, reference, false);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("SELECT pg_catalog.pg_current_xact_id()::text" + ROUTE)) {
                if (!rows.next()) {
                    throw new SQLException("Write witness is unavailable");
                }
                var witness = new PostgresCommitWitness(new TransactionCommitReceipt(1, "POSTGRESQL_XID8_V1",
                        rows.getString(1), PostgresTransactionRoute.fingerprint(rows, 2), relation), rows.getInt(8),
                        reference);
                if (witness.backend() <= 0 || rows.next()) {
                    throw new SQLException("Write witness is ambiguous");
                }
                return witness;
            }
        }
    }

    /**
     * 校验事务。
     */
    void verifyTransaction(Connection connection) throws SQLException {
        if (!equals(sample(connection, relationReference))) {
            throw new SQLException("Write transaction or relation changed");
        }
    }

    /**
     * 校验已提交。
     */
    void verifyCommitted(Connection connection) throws SQLException {
        if (!connection.getAutoCommit() || Thread.currentThread().isInterrupted()) {
            throw new SQLException("Committed witness connection is unavailable");
        }
        if (!java.util.Objects.equals(receipt.relationFingerprint(),
                PostgresRelationIdentity.storageFingerprint(connection, relationReference, false))) {
            throw new SQLException("Committed relation changed");
        }
        try (var statement = connection.prepareStatement("SELECT pg_catalog.pg_xact_status(?::xid8)" + ROUTE)) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            statement.setString(1, receipt.transactionId());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !"committed".equals(rows.getString(1))
                        || !receipt.routeFingerprint().equals(PostgresTransactionRoute.fingerprint(rows, 2))
                        || backend != rows.getInt(8) || rows.next()) {
                    throw new SQLException("Transaction commit could not be verified");
                }
            }
        }
    }

}
