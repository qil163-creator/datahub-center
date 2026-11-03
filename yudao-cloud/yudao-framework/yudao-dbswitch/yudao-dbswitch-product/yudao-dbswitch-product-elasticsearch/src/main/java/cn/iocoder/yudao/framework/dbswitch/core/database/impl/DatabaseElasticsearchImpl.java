package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import lombok.SneakyThrows;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ElasticSearch 元信息实现（JDBC 桥，不走标准 SQL DDL）。
 */
public class DatabaseElasticsearchImpl extends AbstractDatabase implements IDatabaseInterface {

    /** es 是组件逻辑命名空间，不是物理索引；仅验证服务器身份，不请求 /es。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (!"es".equals(namespace)) {
            throw new IllegalArgumentException("Elasticsearch 逻辑命名空间必须为 es，索引需使用产品专用接口");
        }
        probeConnection(connection, timeoutSeconds);
    }

    /** 逻辑命名空间没有统一索引权限；不能把名为 es 的索引授权当作账号证据。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (!"es".equals(namespace)) { throw new IllegalArgumentException("Elasticsearch 逻辑命名空间必须为 es"); }
        throw new SQLFeatureNotSupportedException("请对明确物理索引调用 probeIndexPrivileges，逻辑 es 不对应索引授权", "0A000");
    }

    /** 只读权限查询验证当前账号对精确索引的 read/view_index_metadata 授权，不推断写权限。 */
    public void probeIndexPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        String index = cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession.index(namespace);
        String body = "{\"index\":[{\"names\":[\"" + index + "\"],\"privileges\":[\"read\",\"view_index_metadata\"]}]}";
        var result = cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession
                .open(connection, 65536, timeoutSeconds)
                .request("POST", "/_security/user/_has_privileges", body).requireSuccess();
        if (!result.path("has_all_requested").isBoolean()) {
            throw new SQLException("Elasticsearch 未返回有效权限证据");
        }
        if (!result.path("has_all_requested").booleanValue()) {
            throw new SQLException("Elasticsearch 当前账号缺少目标索引读取或元数据权限", "42501");
        }
    }

    /** Elasticsearch 的诊断目标是单个实际索引；只读 mapping，不搜索业务文档。 */
    public void probeIndexMapping(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        String index = cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession.index(namespace);
        var result = cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession
                .open(connection, 65536, timeoutSeconds).request("GET", "/" + index + "/_mapping", null).requireSuccess();
        if (result.size() != 1 || !result.path(index).path("mappings").isObject()) {
            throw new SQLException("Elasticsearch 未返回精确目标索引的 mapping", "42S02");
        }
    }

    /** 当前账号可见索引的存储统计，包含副本；Elasticsearch 没有关系库式的当前数据库。 */
    @Override
    public long queryDatabaseUsedBytes(Connection connection, int timeoutSeconds) throws SQLException {
        var result = cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession
                .open(connection, 64 * 1024, timeoutSeconds)
                .request("GET", "/_stats/store?level=cluster", null).requireSuccess();
        var failed = result.path("_shards").path("failed");
        var bytes = result.path("_all").path("total").path("store").path("size_in_bytes");
        if (!failed.isIntegralNumber() || failed.longValue() != 0 || !bytes.isIntegralNumber()
                || !bytes.canConvertToLong() || bytes.longValue() < 0) {
            throw new SQLException("Elasticsearch store statistics are incomplete or invalid");
        }
        return bytes.longValue();
    }

    /**
     * 探测数据库连接并返回连接诊断结果。
     */
    @Override
    public void probeConnection(Connection connection, int timeoutSeconds) throws SQLException {
        cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchConnectionProbe.probe(connection,
                timeoutSeconds);
    }

    /**
     * 初始化 {@code DatabaseElasticsearchImpl}及其父类状态。
     */
    public DatabaseElasticsearchImpl() {
        super(ProductTypeEnum.ELASTICSEARCH.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.ELASTICSEARCH;
    }

    /**
     * jdbc-jest 把语句当 JSON 解析，不能执行 ALTER。
     * 继承 {@link AbstractDatabase#getAddColumnSql} 会把库名和表名拼进语句。
     */
    @Override
    @SneakyThrows
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        throw new SQLFeatureNotSupportedException("Elasticsearch jdbc-jest cannot add a column", "0A000");
    }

    /**
     * 获取数据库的模式schema列表。
     */
    @Override
    public List<String> querySchemaList(Connection connection) {
        try {
            cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession.open(connection,
                            16L * 1024 * 1024)
                    .request("GET", "/", null).requireSuccess();
            return List.of("es");
        } catch (SQLException failure) {
            throw new IllegalStateException("Elasticsearch metadata could not be read");
        }
    }

    /**
     * 获取指定模式Schema内的所有表列表。
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        try {
            var indexes = cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession.open(
                            connection, 16L * 1024 * 1024)
                    .request("GET", "/_cat/indices?format=json&h=index", null).requireSuccess();
            if (!indexes.isArray()) {
                throw new SQLException("Invalid Elasticsearch index listing");
            }
            List<TableDescription> result = new ArrayList<>();
            for (var index : indexes) {
                String name = index.path("index").asText();
                if (name.startsWith(".")) {
                    continue;
                }
                cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession.index(name);
                var table = new TableDescription();
                table.setSchemaName("es");
                table.setTableName(name);
                table.setTableType("TABLE");
                result.add(table);
            }
            return result;
        } catch (SQLException failure) {
            throw new IllegalStateException("Elasticsearch indexes could not be read");
        }
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        return null;
    }

    /**
     * 获取指定视图表的DDL语句。
     */
    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        return null;
    }

    /**
     * 获取指定查询SQL的元信息。
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        return queryTableColumnMeta(connection, "es", sql);
    }

    /**
     * 获取指定模式表的元信息。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName, String tableName) {
        try {
            var session = cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchNativeSession.open(
                    connection, 16L * 1024 * 1024);
            return cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchMapping.columns(
                    cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchMapping.read(session,
                            tableName));
        } catch (SQLException failure) {
            throw new IllegalStateException("Elasticsearch mapping could not be read");
        }
    }

    /**
     * 获取指定模式表的字段列表。
     */
    @Override
    public List<String> queryTableColumnName(Connection connection, String schemaName, String tableName) {
        return queryTableColumnMeta(connection, schemaName, tableName).stream().map(ColumnDescription::getFieldName)
                .toList();
    }

    /**
     * 读取表结构中的列定义。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMetaOnly(Connection connection, String schemaName,
            String tableName) {
        return queryTableColumnMeta(connection, schemaName, tableName);
    }

    /**
     * 测试查询SQL语句的有效性。
     */
    @Override
    public void testQuerySQL(Connection connection, String sql) {
        queryTableColumnMeta(connection, "es", sql);
    }

    /**
     * 获取指定模式表的主键字段列表。
     */
    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schemaName, String tableName) {
        queryTableColumnMeta(connection, schemaName, tableName);
        return List.of("_id");
    }

    /**
     * 获取数据库的表全名。
     */
    @Override
    public String getQuotedSchemaTableCombination(String schemaName, String tableName) {
        return String.format("%s.%s", schemaName, tableName);
    }

    /**
     * 获取字段列的结构定义。
     */
    @Override
    public String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc, boolean addCr,
            boolean withRemarks) {
        return null;
    }

    /**
     * 主键列转换为逗号分隔的字符串。
     */
    @Override
    public String getPrimaryKeyAsString(List<String> pks) {
        return null;
    }

    /**
     * 获取表和字段的注释定义。
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td, List<ColumnDescription> cds) {
        return Collections.emptyList();
    }

    /**
     * 获取字段类型。
     */
    @Override
    public boolean canCreateIndex(ColumnMetaData v) {
        return false;
    }

    /**
     * 为字段描述列表补充索引信息。
     */
    @Override
    public void setColumnIndexInfo(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> columnDescriptions) {
    }

    /**
     * 读取表的索引定义。
     */
    @Override
    public List<cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription> queryTableIndexes(
            Connection connection,
            String schemaName, String tableName) {
        return List.of();
    }

    /**
     * 获取查询字段默认值/注释的 SQL（数据库方言相关）。
     */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        return null;
    }

    /**
     * 返回表名。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        return tableName;
    }

    /**
     * 把用户 SQL 包装为低成本的合法性探测语句（如 explain、外层包 WHERE 1=2 等），由子类按方言实现。
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        return sql;
    }
}
