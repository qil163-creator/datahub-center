package cn.iocoder.yudao.framework.dbswitch.dbwriter.kingbase;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.KingbaseCursorMetadata;
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
 * KingbaseES 的 Oracle 模式普通堆表原生 INSERT ... ON CONFLICT，仅允许已验证的
 * V008R006C009B0014、database_mode=oracle、enable_ci=on 配置，仲裁键须为精确且
 * 已验证的不可延迟唯一约束，确认凭据来自 JDBC 提交返回。
 */
public final class KingbaseUpsertWriter extends AbstractNativeUpsertWriter {

    private KingbaseCursorMetadata.Shape shape;

    private CursorKeyContract contract;

    /**
     * 初始化 {@code KingbaseUpsertWriter}及其父类状态。
     */
    public KingbaseUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "KingbaseES";
    }

    /**
     * 按Kingbase方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return KingbaseCursorMetadata.quote(name);
    }

    /**
     * 根据列属性确定参数绑定使用的类型。
     */
    @Override
    protected int bindingType(int jdbcType, String typeName) {
        // Kingbase8 驱动将 date 列报告为 Types.TIMESTAMP，按 DATE 绑定以避免使用时间戳载体。
        if ("date".equals(typeName)) {
            return java.sql.Types.DATE;
        }
        return "timestamptz".equals(typeName) ? java.sql.Types.TIMESTAMP_WITH_TIMEZONE : jdbcType;
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection c) throws SQLException {
        if (!"KingbaseES".equals(c.getMetaData().getDatabaseProductName())) {
            throw new SQLException("Native Kingbase UPSERT requires the KingbaseES V008R006C009B0014 server");
        }
        try (var s = c.createStatement(); var r = s.executeQuery(
                "SELECT pg_catalog.version(),pg_catalog.current_setting('database_mode'),pg_catalog.current_setting('enable_ci')")) {
            if (!r.next() || !r.getString(1)
                    .equals("KingbaseES V008R006C009B0014 on x86_64-pc-linux-gnu, compiled by gcc (GCC) 4.8.5 20150623 (Red Hat 4.8.5-28), 64-bit")
                    || !"oracle".equals(r.getString(2)) || !"on".equals(r.getString(3)) || r.next()) {
                throw new SQLException("Native Kingbase UPSERT requires the verified oracle/ci profile");
            }
        }
    }

    /**
     * 为当前操作获取目标对象的锁。
     */
    @Override
    protected void lockTarget(Connection c, boolean writing) throws SQLException {
        if (writing) {
            KingbaseCursorMetadata.lock(c, schemaName, tableName, true, runtimeOptions.queryTimeoutSeconds());
        }
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection c) throws SQLException {
        return KingbaseCursorMetadata.identity(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection c) throws SQLException {
        shape = KingbaseCursorMetadata.inspect(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
        contract = KingbaseCursorMetadata.contract(shape, uniqueKeys);
        for (String field : fields) {
            if (!shape.columns().containsKey(field)) {
                throw new SQLException("Kingbase target column is missing");
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
                return KingbaseCursorMetadata.exact(value, shape.columns().get(fields.get(index)));
            } catch (SQLException failure) {
                throw new BatchRejectedException("Kingbase UPSERT input cannot be preserved exactly");
            }
        });
        JdbcBatchExecutor.execute(dataSource, context -> {
            int[] types = inspect(context.connection(), true);
            if (contract != null && !contract.nativeFingerprint().equals(
                    KingbaseCursorMetadata.contract(shape, uniqueKeys).nativeFingerprint())) {
                throw new SQLException("Kingbase cursor target key comparison changed before write");
            }
            JdbcBatchExecutor.executeBatch(context, upsertSql, exact, types, runtimeOptions.queryTimeoutSeconds());
            return null;
        });
        return new BatchWriteResult(rows.size(), null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }
}
