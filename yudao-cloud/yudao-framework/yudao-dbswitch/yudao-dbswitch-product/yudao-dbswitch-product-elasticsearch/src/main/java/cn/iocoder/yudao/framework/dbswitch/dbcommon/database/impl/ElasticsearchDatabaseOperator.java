package cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.AbstractDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.StatementResultSet;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession;
import cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchScroll;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** 具体索引是查询句柄；绝不将任意 SQL/DSL 静默视为索引。 */
public class ElasticsearchDatabaseOperator extends AbstractDatabaseOperator {

    /**
     * 初始化 {@code ElasticsearchDatabaseOperator}及其父类状态。
     */
    public ElasticsearchDatabaseOperator(DataSource source) {
        super(source);
    }

    /**
     * 返回原生搬运路径使用的列定义。
     */
    @Override
    public List<cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription> nativeMigrationColumns(String schema,
            String table) {
        return cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeStructure.columns(
                describeNativeTable(schema, table));
    }

    /**
     * 原生索引及集合结构支持独立于关系型 SQL DDL。
     */
    @Override
    public boolean supportsNativeTableDefinition() {
        return true;
    }

    /**
     * 读取数据库原生表定义及其元数据证据。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition describeNativeTable(String schema,
            String table) {
        return cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeStructure.describe(
                dataSource, table, runtimeOptions.maxCacheBytes(), runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 为当前产品准备原生目标对象及写入计划。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan prepareNativeTarget(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition definition, String schema,
            String table,
            Map<String, String> fields, boolean replace) {
        return cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeStructure.prepare(
                dataSource, definition, table, fields, replace, runtimeOptions.maxCacheBytes(),
                runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 返回当前实现对应的数据库产品类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.ELASTICSEARCH;
    }

    /**
     * 校验目标原生定义并生成可复核的结构演进计划。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan prepareNativeSchemaEvolution(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition desired, String schema,
            String table) {
        return cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeStructure.evolve(
                dataSource, desired, table, runtimeOptions.maxCacheBytes(), runtimeOptions.queryTimeoutSeconds());
    }

    /**
     * 设置读取(fetch)数据的批次大小。
     */
    @Override
    public void setFetchSize(int size) {
        if (size < 1 || size > 10_000) {
            throw new IllegalArgumentException("Elasticsearch fetch size must be between 1 and 10000");
        }
        fetchSize = size;
    }

    /**
     * 生成查询指定字段的select查询SQL语句。
     */
    @Override
    public String getSelectTableSql(String schema, String table, List<String> fields) {
        return ElasticsearchNativeSession.index(table);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String index) {
        return queryTableData(index, List.of(), List.of());
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String index, List<String> fields) {
        return queryTableData(index, fields, List.of());
    }

    /**
     * 获取指定schema下表的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields) {
        return queryTableData(table, fields, List.of());
    }

    /**
     * 获取指定schema下表的按主键有序的结果集。
     */
    @Override
    public StatementResultSet queryTableData(String schema, String table, List<String> fields, List<String> orders) {
        return queryTableData(table, fields, orders);
    }

    /**
     * 按指定模式、表及查询范围读取表数据。
     */
    @Override
    public StatementResultSet queryTableData(String index, List<String> fields, List<String> orders) {
        ElasticsearchNativeSession.index(index);
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            return ElasticsearchScroll.open(connection,
                    ElasticsearchNativeSession.open(connection, runtimeOptions.maxCacheBytes(),
                            runtimeOptions.queryTimeoutSeconds()),
                    index, fields, orders, fetchSize, runtimeOptions.queryTimeoutSeconds());
        } catch (SQLException | RuntimeException failure) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
            }
            throw new IllegalStateException("Elasticsearch read could not be opened");
        }
    }

    /** 固定且无快照的单类型水位游标；交付边界参见 ElasticsearchCursorReader。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadSession openCursor(
            cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest request) {
        return ElasticsearchCursorReader.open(dataSource, request, runtimeOptions, fetchSize);
    }

    /**
     * 删除指定物理表。
     */
    @Override
    public void dropTable(String schema, String table) {
        mutate(table, true);
    }

    /**
     * 清除指定表的所有数据。
     */
    @Override
    public void truncateTableData(String schema, String table) {
        mutate(table, false);
    }

    /**
     * 按当前变更定义修改目标对象或数据。
     */
    private void mutate(String table, boolean drop) {
        String index = ElasticsearchNativeSession.index(table);
        boolean attempted = false;
        try (Connection connection = dataSource.getConnection()) {
            var session = ElasticsearchNativeSession.open(connection, runtimeOptions.maxCacheBytes(),
                    runtimeOptions.queryTimeoutSeconds());
            var mappings = session.request("GET", "/" + index + "/_mapping", null).requireSuccess();
            if (mappings.size() != 1 || !mappings.has(index)) {
                throw new SQLException("Mutation requires an exact physical index");
            }
            attempted = true;
            var response = drop ? session.request("DELETE", "/" + index, null)
                    : session.request("POST",
                    "/" + index + "/_delete_by_query?refresh=true&wait_for_completion=true&conflicts=abort&timeout="
                            + runtimeOptions.queryTimeoutSeconds() + "s", "{\"query\":{\"match_all\":{}}}");
            var json = response.requireSuccess();
            if (drop) {
                if (!json.path("acknowledged").asBoolean(false)) {
                    throw new SQLException("Index deletion was not acknowledged");
                }
            } else if (!json.path("timed_out").isBoolean() || json.path("timed_out").booleanValue()
                    || !json.path("failures").isArray() || !json.path("failures").isEmpty()
                    || !json.path("version_conflicts").isIntegralNumber() || json.path("version_conflicts")
                    .longValue() != 0
                    || !json.path("total").isIntegralNumber() || !json.path("deleted").isIntegralNumber()
                    || !json.get("total").bigIntegerValue().equals(json.get("deleted").bigIntegerValue())) {
                throw new SQLException("Index truncation was not fully acknowledged");
            }
        } catch (SQLException | RuntimeException failure) {
            if (attempted) {
                throw new UnconfirmedWriteException();
            }
            throw new IllegalStateException("Elasticsearch mutation was not submitted");
        }
    }

    /**
     * 返回当前字段是否属于主键。
     */
    @Override
    public Boolean getPkExist(String schema, String table, Map<String, Object> key) throws SQLException {
        if (key == null || key.size() != 1 || !(key.get("_id") instanceof String id) || id.isEmpty()) {
            throw new IllegalArgumentException("Elasticsearch identity lookup requires exactly one _id string");
        }
        String encoded = java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        try (var connection = dataSource.getConnection()) {
            var response = ElasticsearchNativeSession.open(connection, runtimeOptions.maxCacheBytes(),
                            runtimeOptions.queryTimeoutSeconds())
                    .request("GET", "/" + ElasticsearchNativeSession.index(table) + "/_doc/" + encoded, null);
            if (response.status() == 404 && response.json().path("found").isBoolean()) {
                return false;
            }
            return response.requireSuccess().path("found").asBoolean(false);
        }
    }
}
