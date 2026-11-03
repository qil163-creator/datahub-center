package cn.iocoder.yudao.framework.dbswitch.dbsynch.mongodb;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoBulkExecutor;
import cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoPreparedTarget;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

/** 原生文档同步，使用共享变更计算器和识别 BSON 类型的匹配键。 */
public final class MongoDatabaseSyncImpl implements IDatabaseSynchronize {

    private final DataSource source;

    private MongoPreparedTarget prepared;

    private DbSwitchOptions options = DbSwitchOptions.defaults();

    private BatchLimits limits = BatchLimits.defaults();

    /**
     * 校验构造参数并初始化 {@code MongoDatabaseSyncImpl}。
     */
    public MongoDatabaseSyncImpl(DataSource source) {
        this.source = Objects.requireNonNull(source);
    }

    /**
     * 获取数据源对象。
     */
    @Override
    public DataSource getDataSource() {
        return source;
    }

    /**
     * 在 prepare 前配置新操作；自定义选项不得被静默忽略。
     */
    @Override
    public void configure(DbSwitchOptions options) {
        this.options = Objects.requireNonNull(options);
    }

    /**
     * 设置写入批次的行数、字节数及绑定参数限制。
     */
    @Override
    public void configureBatchLimits(BatchLimits limits) {
        this.limits = Objects.requireNonNull(limits);
    }

    /**
     * 校验目标表及列映射并准备批次写入资源。
     */
    @Override
    public void prepare(String schema, String table, List<String> fields, List<String> keys) {
        prepared = null;
        if (!List.of("_id").equals(keys) || fields == null || !fields.contains("_id")) {
            throw new IllegalArgumentException("MongoDB native DIFF requires only the _id key");
        }
        prepared = MongoPreparedTarget.prepare(source, schema, table, fields);
    }

    /**
     * 执行参数化数据插入操作。
     */
    @Override
    public long executeInsert(List<Object[]> rows) {
        return execute(rows, MongoBulkExecutor.Operation.INSERT);
    }

    /**
     * 批量数据Update。
     */
    @Override
    public long executeUpdate(List<Object[]> rows) {
        return execute(rows, MongoBulkExecutor.Operation.UPDATE);
    }

    /**
     * 批量数据Delete。
     */
    @Override
    public long executeDelete(List<Object[]> rows) {
        return execute(rows, MongoBulkExecutor.Operation.DELETE);
    }

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    private long execute(List<Object[]> rows, MongoBulkExecutor.Operation operation) {
        return MongoBulkExecutor.execute(source, prepared, rows, operation, limits, options);
    }
}
