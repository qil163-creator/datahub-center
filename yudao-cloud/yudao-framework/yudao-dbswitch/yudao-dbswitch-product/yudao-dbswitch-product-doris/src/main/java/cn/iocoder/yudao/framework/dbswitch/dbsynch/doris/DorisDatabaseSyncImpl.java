package cn.iocoder.yudao.framework.dbswitch.dbsynch.doris;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisJdbcSupport;

import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * Doris数据库DML同步实现类。
 *
 * @author jrl
 */
public class DorisDatabaseSyncImpl implements IDatabaseSynchronize {

    private final DataSource dataSource;

    private DbSwitchOptions options = DbSwitchOptions.defaults();

    private BatchLimits limits = BatchLimits.defaults();

    private DorisJdbcSupport.Evidence captured;

    private String schema, table;

    private List<String> fields;

    /**
     * 校验构造参数并初始化 {@code DorisDatabaseSyncImpl}。
     */
    public DorisDatabaseSyncImpl(DataSource ds) {
        dataSource = Objects.requireNonNull(ds);
    }

    /**
     * 获取数据源对象。
     */
    @Override
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * 在 prepare 前配置新操作；自定义选项不得被静默忽略。
     */
    @Override
    public void configure(DbSwitchOptions value) {
        options = Objects.requireNonNull(value);
    }

    /**
     * 设置写入批次的行数、字节数及绑定参数限制。
     */
    @Override
    public void configureBatchLimits(BatchLimits value) {
        limits = Objects.requireNonNull(value);
    }

    /**
     * 校验目标表及列映射并准备批次写入资源。
     */
    @Override
    public void prepare(String schema, String table, List<String> fields, List<String> keys) {
        captured = null;
        this.fields = null;
        try (var c = dataSource.getConnection()) {
            var observed = DorisJdbcSupport.inspect(c, schema, table, options.queryTimeoutSeconds());
            if (!observed.table().unique() || !observed.table().keys().equals(keys) || !fields.containsAll(
                    keys) || keys.stream().anyMatch(k -> observed.table().column(k).nullable())) {
                throw new IllegalArgumentException("Doris DIFF requires the exact nonnull native unique key");
            }
            if (!"true".equals(
                    observed.table().properties().get("enable_unique_key_merge_on_write")) || observed.table()
                    .properties().containsKey("function_column.sequence_col")) {
                throw new IllegalArgumentException("Doris DIFF precedence requires a separate native plan");
            }
            fields.forEach(observed.table()::column);
            captured = observed;
        } catch (Exception failure) {
            throw new IllegalStateException("Doris difference native preflight failed");
        }
        this.schema = schema;
        this.table = table;
        this.fields = List.copyOf(fields);
    }

    /**
     * 校验已准备状态后按同步操作类型写入或变更目标数据。
     */
    private long execute(List<Object[]> records, DorisJdbcSupport.Operation operation) {
        if (captured == null || fields == null) {
            throw new IllegalStateException("Doris synchronizer is not prepared");
        }
        return DorisJdbcSupport.write(dataSource, schema, table, captured, fields, records, operation,
                options.queryTimeoutSeconds(), limits);
    }

    /**
     * 执行参数化数据插入操作。
     */
    @Override
    public long executeInsert(List<Object[]> rows) {
        return execute(rows, DorisJdbcSupport.Operation.DIFF_INSERT);
    }

    /**
     * 批量数据Update。
     */
    @Override
    public long executeUpdate(List<Object[]> rows) {
        return execute(rows, DorisJdbcSupport.Operation.UPDATE);
    }

    /**
     * 批量数据Delete。
     */
    @Override
    public long executeDelete(List<Object[]> rows) {
        return execute(rows, DorisJdbcSupport.Operation.DELETE);
    }
}
