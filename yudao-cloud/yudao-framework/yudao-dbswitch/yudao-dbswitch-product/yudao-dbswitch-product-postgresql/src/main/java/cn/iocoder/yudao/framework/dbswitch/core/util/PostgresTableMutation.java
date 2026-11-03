package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.hutool.crypto.digest.DigestUtil;

import java.sql.*;
import java.util.Objects;

/**
 * PostgreSQL 普通持久表的固定身份事务性处置。调用者提供独占的新连接；本类拥有事务。
 * 只承载技术机制，不决定业务审批、租户或引用授权。锁、完整副本验证、DDL 和回执一起提交。
 * 回执存储在执行角色拥有的独立 schema；不得清理仍可能重放的 operation key。
 */
public final class PostgresTableMutation {

    private static final String RECEIPTS = "\"_yudao_control\".\"table_mutation_receipt_v1\"";

    public record Identity(String schema, String table, String fingerprint, String structure) {

    }

    public record Request(String operationKey, String digest, String operation, Identity source,
                          String newTable, Identity archive) {

    }

    public record Receipt(String operationKey, String digest, String operation, long rows, boolean replayed) {

    }

    /**
     * 初始化 {@code PostgresTableMutation}。
     */
    private PostgresTableMutation() {
    }

    /**
     * 拒绝当前实现未支持的 {@code inspect} 操作。
     */
    public static Identity inspect(Connection connection, String schema, String table) throws SQLException {
        requirePostgres(connection);
        String reference = reference(schema, table);
        String storage = PostgresRelationIdentity.storageFingerprint(connection, reference, false);
        if (storage == null) {
            throw new SQLFeatureNotSupportedException(
                    "Only persistent non-partitioned tables without RLS or inheritance are supported");
        }
        String relation = PostgresRelationIdentity.fingerprint(connection, reference);
        StringBuilder columns = new StringBuilder();
        try (var query = connection.prepareStatement(
                "SELECT attnum, attname, atttypid::text, atttypmod FROM pg_catalog.pg_attribute WHERE attrelid=pg_catalog.to_regclass(?) AND attnum>0 AND NOT attisdropped ORDER BY attnum")) {
            query.setString(1, reference);
            query.setQueryTimeout(5);
            try (var result = query.executeQuery()) {
                while (result.next()) {
                    for (int i = 1; i <= 4; i++) {
                        String value = result.getString(i);
                        columns.append(value == null ? -1 : value.length()).append(':').append(value).append('|');
                    }
                }
            }
        }
        if (columns.isEmpty()) {
            throw new SQLFeatureNotSupportedException("An ordinary table with columns is required");
        }
        return new Identity(schema, table, DigestUtil.sha256Hex(relation + ":" + storage),
                DigestUtil.sha256Hex(columns.toString()));
    }

    /**
     * 校验原值和目标条件后应用受保护的数据变更。
     */
    public static Receipt execute(Connection connection, Request request) throws SQLException {
        requirePostgres(connection);
        validate(request);
        if (!connection.getAutoCommit()) {
            throw new SQLException("Mutation requires a fresh connection with owned transaction");
        }
        connection.setAutoCommit(false);
        try {
            executeSql(connection, "SET LOCAL lock_timeout='5s'", 5);
            executeSql(connection, "SET LOCAL statement_timeout='120s'", 5);
            ensureReceiptStore(connection);
            // 稳定操作锁使工作线程串行执行，即使 DROP 已使原名称消失也生效。
            try (var lock = connection.prepareStatement(
                    "SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, 481516))")) {
                lock.setString(1, request.operationKey());
                lock.setQueryTimeout(10);
                lock.execute();
            }
            Receipt old = receipt(connection, request.operationKey());
            if (old != null) {
                if (!Objects.equals(old.digest(), request.digest())) {
                    throw new SQLException("Operation key conflicts with another immutable plan");
                }
                connection.commit();
                return new Receipt(old.operationKey(), old.digest(), old.operation(), old.rows(), true);
            }
            String source = reference(request.source().schema(), request.source().table());
            executeSql(connection, "LOCK TABLE ONLY " + source + " IN ACCESS EXCLUSIVE MODE", 10);
            if (!request.source().equals(inspect(connection, request.source().schema(), request.source().table()))) {
                throw new SQLException("Pinned source identity or structure changed");
            }
            long rows;
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(120);
                try (var result = statement.executeQuery("SELECT count(*) FROM ONLY " + source)) {
                    result.next();
                    rows = result.getLong(1);
                }
            }
            if ("DROP".equals(request.operation())) {
                if (request.archive() == null || request.source().fingerprint()
                        .equals(request.archive().fingerprint())) {
                    throw new SQLException("A distinct complete archive is required");
                }
                String archive = reference(request.archive().schema(), request.archive().table());
                executeSql(connection, "LOCK TABLE ONLY " + archive + " IN ACCESS EXCLUSIVE MODE", 10);
                if (!request.archive()
                        .equals(inspect(connection, request.archive().schema(), request.archive().table()))) {
                    throw new SQLException("Pinned archive identity or structure changed");
                }
                if (!request.source().structure().equals(request.archive().structure())) {
                    throw new SQLException("Archive structure does not match source");
                }
                // 二进制记录保留 JSON 源文本、数组下界及精确原生值。
                // EXCEPT ALL 保留重复项数量；不支持的二进制发送类型默认拒绝。
                // 上方已比较列类型身份，业务行不会离开此连接。
                String difference = "SELECT EXISTS((SELECT pg_catalog.record_send(s) FROM ONLY " + source + " s EXCEPT ALL SELECT pg_catalog.record_send(a) FROM ONLY " + archive + " a) UNION ALL (SELECT pg_catalog.record_send(a) FROM ONLY " + archive + " a EXCEPT ALL SELECT pg_catalog.record_send(s) FROM ONLY " + source + " s))";
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(120);
                    try (var result = statement.executeQuery(difference)) {
                        result.next();
                        if (result.getBoolean(1)) {
                            throw new SQLException("Archive does not completely cover the locked source");
                        }
                    }
                }
                executeSql(connection, "DROP TABLE " + source + " RESTRICT", 120);
            } else {
                executeSql(connection, "ALTER TABLE " + source + " RENAME TO " + quote(request.newTable()), 120);
                // 排队或已准备的写入可能在重命名后仍持有表关系 OID；持久化校验
                // 在提交后仍拒绝 DML 和 TRUNCATE，而不只在持有独占锁时生效。
                executeSql(connection,
                        "CREATE TRIGGER \"_yudao_retired_guard\" BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON "
                                + reference(request.source().schema(), request.newTable())
                                + " FOR EACH STATEMENT EXECUTE FUNCTION \"_yudao_control\".reject_retired_write_v1()",
                        10);
            }
            try (var insert = connection.prepareStatement(
                    "INSERT INTO " + RECEIPTS + " (operation_key, plan_digest, operation, affected_rows) VALUES (?,?,?,?)")) {
                insert.setString(1, request.operationKey());
                insert.setString(2, request.digest());
                insert.setString(3, request.operation());
                insert.setLong(4, rows);
                insert.executeUpdate();
            }
            connection.commit();
            return new Receipt(request.operationKey(), request.digest(), request.operation(), rows, false);
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /** 只读对账；对象不存在不能证明正在执行的事务已经结束。 */
    public static Receipt receipt(Connection connection, String key) throws SQLException {
        requirePostgres(connection);
        try (var exists = connection.prepareStatement("SELECT pg_catalog.to_regclass(?)")) {
            exists.setString(1, RECEIPTS);
            try (var result = exists.executeQuery()) {
                result.next();
                if (result.getString(1) == null) {
                    return null;
                }
            }
        }
        verifyStoreOwner(connection);
        try (var query = connection.prepareStatement(
                "SELECT operation_key,plan_digest,operation,affected_rows FROM " + RECEIPTS + " WHERE operation_key=?")) {
            query.setString(1, key);
            query.setQueryTimeout(5);
            try (var result = query.executeQuery()) {
                return result.next() ? new Receipt(result.getString(1), result.getString(2), result.getString(3),
                        result.getLong(4), true) : null;
            }
        }
    }

    /**
     * 串行化初始化并检查数据库中的持久化写入回执表。
     */
    private static void ensureReceiptStore(Connection connection) throws SQLException {
        // 对不同首次操作键的模式初始化进行串行化。
        executeSql(connection, "SELECT pg_catalog.pg_advisory_xact_lock(481516,2342)", 10);
        executeSql(connection, "CREATE SCHEMA IF NOT EXISTS \"_yudao_control\"", 10);
        try (var statement = connection.createStatement(); var result = statement.executeQuery(
                "SELECT nspowner=current_user::regrole::oid FROM pg_catalog.pg_namespace WHERE nspname='_yudao_control'")) {
            if (!result.next() || !result.getBoolean(1)) {
                throw new SQLException("Mutation receipt schema must be owned by execution role");
            }
        }
        executeSql(connection,
                "CREATE TABLE IF NOT EXISTS " + RECEIPTS + " (operation_key varchar(200) PRIMARY KEY,plan_digest varchar(64) NOT NULL,operation varchar(10) NOT NULL,affected_rows bigint NOT NULL,completed_at timestamptz NOT NULL DEFAULT clock_timestamp())",
                10);
        executeSql(connection,
                "CREATE OR REPLACE FUNCTION \"_yudao_control\".reject_retired_write_v1() RETURNS trigger LANGUAGE plpgsql AS $body$ BEGIN RAISE EXCEPTION 'retired relation is write-frozen' USING ERRCODE='55000'; END $body$",
                10);
        verifyStoreOwner(connection);
    }

    /**
     * 检查回执存储表归当前数据库账号所有且结构符合协议。
     */
    private static void verifyStoreOwner(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement(
                "SELECT c.relowner=current_user::regrole::oid AND c.relkind='r' AND NOT c.relrowsecurity FROM pg_catalog.pg_class c WHERE c.oid=pg_catalog.to_regclass(?)")) {
            query.setString(1, RECEIPTS);
            try (var result = query.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new SQLException("Untrusted mutation receipt store");
                }
            }
        }
    }

    /**
     * 检查受保护行变更的目标、键列、原值及目标值符合当前操作协议。
     */
    private static void validate(Request request) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(request.source());
        if (request.operationKey() == null || !request.operationKey().matches("[A-Za-z0-9:_-]{8,200}")
                || request.digest() == null || !request.digest().matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Bounded operation key and plan digest required");
        }
        if (!"DROP".equals(request.operation()) && !"RENAME".equals(request.operation())) {
            throw new IllegalArgumentException("Unsupported mutation");
        }
        if ("RENAME".equals(request.operation())) {
            quote(request.newTable());
        }
    }

    /**
     * 拒绝当前实现未支持的 {@code requirePostgres} 操作。
     */
    private static void requirePostgres(Connection connection) throws SQLException {
        if (connection == null || !"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
            throw new SQLFeatureNotSupportedException("Transactional mutation currently proves PostgreSQL only");
        }
    }

    /**
     * 生成当前操作引用的限定关系名。
     */
    private static String reference(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }

    /**
     * 按PostgreSQL方言转义并引用标识符。
     */
    private static String quote(String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.indexOf('\0') >= 0 || identifier.getBytes(
                java.nio.charset.StandardCharsets.UTF_8).length > 63) {
            throw new IllegalArgumentException("Bounded PostgreSQL identifier required");
        }
        if ("_yudao_control".equals(identifier)) {
            throw new IllegalArgumentException("Control schema cannot be a mutation target");
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * 在当前数据库连接上执行给定 SQL。
     */
    private static void executeSql(Connection connection, String sql, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.execute(sql);
        }
    }
}
