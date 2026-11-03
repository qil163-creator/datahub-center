package cn.iocoder.yudao.framework.dbswitch.dbwriter.highgo;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.HighgoCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.AbstractNativeUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.BatchWriteResult;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * HighGo 6.0.4.2 普通堆表上的原生 INSERT ... ON CONFLICT；仲裁键须为精确且
 * 已验证的不可延迟唯一约束，确认凭据来自 JDBC 提交返回。
 */
public final class HighgoUpsertWriter extends AbstractNativeUpsertWriter {

    private HighgoCursorMetadata.Shape shape;

    private CursorKeyContract contract;

    /**
     * 初始化 {@code HighgoUpsertWriter}及其父类状态。
     */
    public HighgoUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "HighGo Database 6 Release 6.0.4.2";
    }

    /**
     * 按当前数据库方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return HighgoCursorMetadata.quote(name);
    }

    /**
     * 根据列属性确定参数绑定使用的类型。
     */
    @Override
    protected int bindingType(int jdbcType, String typeName) {
        // HighGo 驱动将 timestamptz 列报告为 Types.TIMESTAMP，应按 TIMESTAMP_WITH_TIMEZONE 绑定。
        return "timestamptz".equals(typeName) ? java.sql.Types.TIMESTAMP_WITH_TIMEZONE : jdbcType;
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection c) throws SQLException {
        if (!"PostgreSQL".equals(c.getMetaData().getDatabaseProductName())) {
            throw new SQLException("Native HighGo UPSERT requires the HighGo Database 6 Release 6.0.4.2 server");
        }
        try (var s = c.createStatement(); var r = s.executeQuery(
                "SELECT pg_catalog.version(),pg_catalog.current_setting('server_version')")) {
            if (!r.next() || !r.getString(1).startsWith("HighGo Database 6 Release 6.0.4.2")
                    || !"12.7".equals(r.getString(2)) || r.next()) {
                throw new SQLException("Native HighGo UPSERT requires the verified 6.0.4.2 profile");
            }
        }
    }

    /**
     * 为当前操作获取目标对象的锁。
     */
    @Override
    protected void lockTarget(Connection c, boolean writing) throws SQLException {
        if (writing) {
            HighgoCursorMetadata.lock(c, schemaName, tableName, true, runtimeOptions.queryTimeoutSeconds());
        }
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection c) throws SQLException {
        return HighgoCursorMetadata.identity(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection c) throws SQLException {
        shape = HighgoCursorMetadata.inspect(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
        contract = HighgoCursorMetadata.contract(shape, uniqueKeys);
        for (String field : fields) {
            if (!shape.columns().containsKey(field)) {
                throw new SQLException("HighGo target column is missing");
            }
        }
        batchLimits.requireStatementParameters(fields.size());
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
        String base = "INSERT INTO " + target() + " (" + quoted(fields) + ") VALUES (" + placeholders()
                + ") ON CONFLICT (" + quoted(uniqueKeys) + ") ";
        List<String> updates = updateFields();
        return base + (updates.isEmpty() ? "DO NOTHING" : "DO UPDATE SET "
                + String.join(",", updates.stream().map(name -> quote(name) + "=EXCLUDED." + quote(name)).toList()));
    }

    /**
     * 写入已完成预处理的数据行。
     */
    @Override
    protected BatchWriteResult writePreparedRows(List<Object[]> rows) {
        var exact = BatchValueConverter.convert(rows, fields.size(), batchLimits, (value, index) -> {
            try {
                return HighgoCursorMetadata.exact(value, shape.columns().get(fields.get(index)));
            } catch (SQLException failure) {
                throw new BatchRejectedException("HighGo UPSERT input cannot be preserved exactly");
            }
        });
        JdbcBatchExecutor.execute(dataSource, context -> {
            int[] types = inspect(context.connection(), true);
            if (contract != null && !contract.nativeFingerprint().equals(
                    HighgoCursorMetadata.contract(shape, uniqueKeys).nativeFingerprint())) {
                throw new SQLException("HighGo cursor target key comparison changed before write");
            }
            JdbcBatchExecutor.executeBatch(context, upsertSql, exact, types, runtimeOptions.queryTimeoutSeconds());
            return null;
        });
        return new BatchWriteResult(rows.size(), null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }
}
