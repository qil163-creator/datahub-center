package cn.iocoder.yudao.framework.dbswitch.dbwriter.sybase;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.product.sybase.SybaseCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.sybase.SybaseWriteColumns;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * 在 SERIALIZABLE 目标事务内按顺序精确的主键执行参数化原生 MERGE。
 * ASE MERGE 更新所有匹配目标行，因此已验证的唯一主键必不可少；提交
 * 和 UNKNOWN 处理仍共享，自增标识键在显式 SET IDENTITY_INSERT 会话内重放。
 */
public final class SybaseUpsertWriter extends AbstractNativeUpsertWriter {

    private SybaseCursorMetadata.Shape shape;

    private Map<String, ColumnDescription> columns;

    private CursorKeyContract contract;

    private boolean identityValues;

    /**
     * 初始化 {@code SybaseUpsertWriter}及其父类状态。
     */
    public SybaseUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Adaptive Server Enterprise";
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    @Override
    protected String quote(String value) {
        return SybaseCursorMetadata.quote(value);
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection c) throws SQLException {
        SybaseCursorMetadata.product(c);
    }

    /**
     * 为当前操作获取目标对象的锁。
     */
    @Override
    protected void lockTarget(Connection c, boolean writing) throws SQLException {
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection c) throws SQLException {
        // 准备阶段使用空闲连接读取完整目录；写事务只能执行
        // 直接目录查询，因为 jconn4 的 getPrimaryKeys 会创建临时表。
        if (shape == null || c.getAutoCommit()) {
            shape = SybaseCursorMetadata.read(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
            columns = shape.columns();
        } else {
            SybaseCursorMetadata.verifyInTransaction(c, schemaName, tableName, shape.sortOrder(), shape.physical(),
                    columns, runtimeOptions.queryTimeoutSeconds());
        }
        return shape.physical() + '\0' + shape.fingerprint() + '\0' + shape.sortOrder();
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection c) throws SQLException {
        contract = SybaseCursorMetadata.keys(shape, uniqueKeys);
        identityValues = false;
        for (String name : fields) {
            var column = columns.get(name);
            if (column == null) {
                throw new SQLException("Sybase target column is missing");
            }
            if (column.getMetadataEvidence().identity().state() == MetadataValue.State.VALUE) {
                if (!uniqueKeys.contains(name)) {
                    throw new SQLException("Sybase replay identity must be an immutable explicit key");
                }
                identityValues = true;
            }
        }
        upsertSql = render();
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    @Override
    public CursorKeyContract keyContract() {
        targetSchemaFingerprint();
        return contract;
    }

    /**
     * 精确有序键的类型与排序规则契约，是无损类型化游标重放的必要条件。
     */
    @Override
    public String keyContractFingerprint() {
        return keyContract().nativeFingerprint();
    }

    /**
     * 按目标方言生成按键更新或插入的 SQL。
     */
    @Override
    protected String buildUpsertSql() {
        return "";
    }

    /**
     * 将当前结构定义渲染为目标 SQL 或表达式。
     */
    private String render() throws SQLException {
        var source = new StringBuilder("SELECT ");
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                source.append(", ");
            }
            source.append("? AS ").append(quote(fields.get(i)));
        }
        String on = String.join(" AND ", uniqueKeys.stream().map(n -> "t." + quote(n) + "=s." + quote(n)).toList());
        String update = updateFields().isEmpty() ? "" : " WHEN MATCHED THEN UPDATE SET " + String.join(",",
                updateFields().stream().map(n -> "t." + quote(n) + "=s." + quote(n)).toList());
        String sql = "MERGE INTO " + target() + " t USING(" + source + ") s ON " + on + update + " WHEN NOT MATCHED THEN INSERT(" + quoted(
                fields) + ")VALUES(" + String.join(",", fields.stream().map(n -> "s." + quote(n)).toList()) + ")";
        if (sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65_536) {
            throw new SQLException("Sybase replay SQL byte budget exceeded");
        }
        return sql;
    }

    /**
     * 写入已完成预处理的数据行。
     */
    @Override
    protected BatchWriteResult writePreparedRows(List<Object[]> rows) {
        List<Object[]> bound;
        int[] types = new int[fields.size()];
        try {
            var order = new ArrayList<SybaseWriteColumns.Column>();
            for (int i = 0; i < types.length; i++) {
                String name = fields.get(i);
                var column = columns.get(name);
                types[i] = SybaseCursorMetadata.jdbcType(column);
                order.add(SybaseWriteColumns.of(column));
            }
            bound = SybaseWriteColumns.exactRows(BatchValueConverter.convert(rows, fields.size(), batchLimits), order);
            types = SybaseWriteColumns.bindingTypes(types, order);
        } catch (SQLException | RuntimeException failure) {
            throw new BatchRejectedException("Sybase UPSERT input cannot be represented without loss");
        }
        int[] parameters = types;
        JdbcBatchExecutor.execute(dataSource, context -> {
            try {
                context.connection().setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                try (var statement = context.connection().createStatement()) {
                    statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                    statement.execute("SET ROWCOUNT 0");
                }
                // 先获取目标独占锁，使并发写入串行执行，避免元数据探测和 MERGE
                // 从共享锁升级为独占锁时发生死锁。
                String lockColumn = lockColumn();
                if (lockColumn != null) {
                    try (var statement = context.connection().createStatement()) {
                        statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                        statement.executeUpdate("UPDATE " + target() + " SET " + quote(lockColumn) + "=" + quote(
                                lockColumn) + " WHERE 1=0");
                    }
                }
                inspect(context.connection(), true);
                if (identityValues) {
                    withIdentityInsert(context, ctx -> {
                        JdbcBatchExecutor.executeBatch(ctx, upsertSql, bound, parameters,
                                runtimeOptions.queryTimeoutSeconds());
                        return null;
                    });
                } else {
                    JdbcBatchExecutor.executeBatch(context, upsertSql, bound, parameters,
                            runtimeOptions.queryTimeoutSeconds());
                }
                return null;
            } catch (SQLException | RuntimeException failure) {
                throw new SQLException("Sybase UPSERT transaction step failed before commit", failure);
            }
        });
        // 仅确认输入，绝不宣称每次 MERGE 恰好修改了一条存储行。
        return new BatchWriteResult(rows.size(), null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }

    /** ASE 即使对无实际修改的加锁探测也拒绝更新自增列，优先使用可写列。 */
    private String lockColumn() {
        for (String field : fields) {
            var column = columns.get(field);
            if (column != null && column.getMetadataEvidence().identity().state() != MetadataValue.State.VALUE) {
                return field;
            }
        }
        return null;
    }

    /**
     * 在允许写入标识列的会话设置下调用当前写入操作。
     */
    private <T> T withIdentityInsert(JdbcBatchExecutor.WriteContext context,
            JdbcBatchExecutor.WriteAction<T> action) throws Exception {
        context.markWriteAttempted();
        try (var statement = context.connection().createStatement()) {
            statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
            statement.execute("SET IDENTITY_INSERT " + target() + " ON");
        }
        try {
            return action.execute(context);
        } finally {
            try (var statement = context.connection().createStatement()) {
                statement.setQueryTimeout(runtimeOptions.queryTimeoutSeconds());
                statement.execute("SET IDENTITY_INSERT " + target() + " OFF");
            }
        }
    }
}
