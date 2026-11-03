package cn.iocoder.yudao.framework.dbswitch.dbsynch.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.IDatabaseSynchronize;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchBulkExecutor;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchMapping;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** 原生 _id 同步；比较及排序由共享变更计算器负责。 */
public final class ElasticsearchDatabaseSyncImpl implements IDatabaseSynchronize {

    private final DataSource source;

    private DbSwitchOptions options = DbSwitchOptions.defaults();

    private BatchLimits limits = BatchLimits.defaults();

    private List<String> fields;

    private Map<String, ElasticsearchMapping.Field> mapping;

    private String index;

    /**
     * 校验构造参数并初始化 {@code ElasticsearchDatabaseSyncImpl}。
     */
    public ElasticsearchDatabaseSyncImpl(DataSource source) {
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
    public void prepare(String schema, String table, List<String> names, List<String> keys) {
        fields = null;
        mapping = null;
        index = null;
        String target = ElasticsearchNativeSession.index(table);
        if (!List.of("_id").equals(keys) || names == null || !names.contains("_id")
                || new HashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException(
                    "Elasticsearch DIFF requires exactly the document _id key and distinct fields");
        }
        try (Connection connection = source.getConnection()) {
            var evidence = ElasticsearchMapping.read(
                    ElasticsearchNativeSession.open(connection, options.maxCacheBytes(), options.queryTimeoutSeconds()),
                    target);
            if (!evidence.keySet().containsAll(names)) {
                throw new IllegalArgumentException("DIFF fields are absent from the Elasticsearch target mapping");
            }
            mapping = evidence;
            fields = List.copyOf(names);
            index = target;
        } catch (SQLException failure) {
            throw new IllegalStateException("Elasticsearch DIFF target could not be prepared");
        }
    }

    /**
     * 执行参数化数据插入操作。
     */
    @Override
    public long executeInsert(List<Object[]> rows) {
        return execute(ElasticsearchBulkExecutor.Operation.CREATE, rows);
    }

    /**
     * 批量数据Update。
     */
    @Override
    public long executeUpdate(List<Object[]> rows) {
        return execute(ElasticsearchBulkExecutor.Operation.UPDATE, rows);
    }

    /**
     * 批量数据Delete。
     */
    @Override
    public long executeDelete(List<Object[]> rows) {
        return execute(ElasticsearchBulkExecutor.Operation.DELETE, rows);
    }

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    private long execute(ElasticsearchBulkExecutor.Operation operation, List<Object[]> rows) {
        if (fields == null) {
            throw new IllegalStateException("Elasticsearch DIFF has not been prepared");
        }
        return ElasticsearchBulkExecutor.execute(source, index, fields, mapping, rows, operation, limits, options);
    }
}
