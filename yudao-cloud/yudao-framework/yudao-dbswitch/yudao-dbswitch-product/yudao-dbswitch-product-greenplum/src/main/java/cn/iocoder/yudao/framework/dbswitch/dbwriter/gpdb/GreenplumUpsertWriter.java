package cn.iocoder.yudao.framework.dbswitch.dbwriter.gpdb;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.core.util.GreenplumCursorMetadata;
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
 * Greenplum 堆表原生 INSERT ... ON CONFLICT，仅允许已验证的 7.1.0 配置：
 * 堆表、哈希分布，不允许追加优化、复制或随机存储；数值主键顺序必须精确，
 * 且 GP 自身要求该主键包含全部分布键。追加优化（AO）表会在引擎内部拒绝
 * 此语句，因此此处在第一条数据语句前即予以拒绝。
 */
public final class GreenplumUpsertWriter extends AbstractNativeUpsertWriter {

    private GreenplumCursorMetadata.Shape shape;

    private CursorKeyContract contract;

    /**
     * 初始化 {@code GreenplumUpsertWriter}及其父类状态。
     */
    public GreenplumUpsertWriter(DataSource source) {
        super(source);
    }

    /**
     * 返回写入器对应的数据库产品名（用于日志输出）。
     */
    @Override
    protected String getDatabaseProductName() {
        return "Greenplum";
    }

    /**
     * 按Greenplum方言转义并引用标识符。
     */
    @Override
    protected String quote(String name) {
        return GreenplumCursorMetadata.quote(name);
    }

    /**
     * 根据列属性确定参数绑定使用的类型。
     */
    @Override
    protected int bindingType(int jdbcType, String typeName) {
        return "timestamptz".equals(typeName) ? java.sql.Types.TIMESTAMP_WITH_TIMEZONE : jdbcType;
    }

    /**
     * 核对连接实际产品与当前实现的产品要求。
     */
    @Override
    protected void verifyProduct(Connection c) throws SQLException {
        if (!"PostgreSQL".equals(c.getMetaData().getDatabaseProductName())) {
            throw new SQLException("Native Greenplum UPSERT requires the Greenplum 7.1.0 coordinator");
        }
    }

    /**
     * 为当前操作获取目标对象的锁。
     */
    @Override
    protected void lockTarget(Connection c, boolean writing) throws SQLException {
        if (writing) {
            GreenplumCursorMetadata.lock(c, schemaName, tableName, true, runtimeOptions.queryTimeoutSeconds());
        }
    }

    /**
     * 补充当前产品特有的模式身份信息。
     */
    @Override
    protected String additionalSchemaIdentity(Connection c) throws SQLException {
        return GreenplumCursorMetadata.identity(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 检查指定键列是否满足当前写入路径要求的唯一键条件。
     */
    @Override
    protected void verifyUniqueKey(Connection c) throws SQLException {
        shape = GreenplumCursorMetadata.inspect(c, schemaName, tableName, runtimeOptions.queryTimeoutSeconds());
        contract = GreenplumCursorMetadata.contract(shape, uniqueKeys);
        for (String field : fields) {
            if (!shape.columns().containsKey(field)) {
                throw new SQLException("Greenplum target column is missing");
            }
        }
        // GP 7.1.0 在创建索引时强制校验此规则，但仍须对实时分布策略重新验证仲裁键。
        if (!shape.distribution().get(1).isBlank()) {
            var numbers = java.util.Arrays.stream(shape.distribution().get(1).split(" ")).map(Integer::parseInt)
                    .toList();
            for (int number : numbers) {
                var column = shape.columns().values().stream().filter(x -> x.number() == number).findFirst()
                        .orElseThrow();
                if (!uniqueKeys.contains(column.name())) {
                    throw new SQLException("Greenplum ON CONFLICT arbiter must contain the distribution key");
                }
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
                return GreenplumCursorMetadata.exact(value, shape.columns().get(fields.get(index)));
            } catch (SQLException failure) {
                throw new BatchRejectedException("Greenplum UPSERT input cannot be preserved exactly");
            }
        });
        JdbcBatchExecutor.execute(dataSource, context -> {
            int[] types = inspect(context.connection(), true);
            if (contract != null && !contract.nativeFingerprint().equals(
                    GreenplumCursorMetadata.contract(shape, uniqueKeys).nativeFingerprint())) {
                throw new SQLException("Greenplum cursor target key comparison changed before write");
            }
            JdbcBatchExecutor.executeBatch(context, upsertSql, exact, types, runtimeOptions.queryTimeoutSeconds());
            return null;
        });
        return new BatchWriteResult(rows.size(), null, CommitAcknowledgement.JDBC_COMMIT_RETURNED);
    }
}
