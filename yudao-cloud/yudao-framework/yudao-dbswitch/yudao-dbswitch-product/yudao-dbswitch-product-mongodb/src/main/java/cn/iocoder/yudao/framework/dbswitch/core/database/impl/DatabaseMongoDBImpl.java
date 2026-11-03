package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import com.google.common.collect.Sets;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.constant.Constants;
import cn.iocoder.yudao.framework.dbswitch.core.util.MongoNativeMetadata;

import lombok.SneakyThrows;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * MongoDB 原生集合元数据（不是通用 ANSI SQL 数据库）。
 *
 * @author jrl
 */
public class DatabaseMongoDBImpl extends AbstractDatabase implements IDatabaseInterface {

    /** 当前库已分配的文档与索引存储，包含其内部空闲空间；不返回宿主机磁盘大小。 */
    @Override
    public long queryDatabaseUsedBytes(Connection connection, int timeoutSeconds) throws SQLException {
        String catalog = connection.getCatalog();
        if (catalog == null || catalog.isBlank()) {
            throw new SQLException("A selected database is required for capacity collection");
        }
        var session = cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession.open(connection, 64 * 1024,
                timeoutSeconds);
        var result = cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies.success(session,
                session.command(catalog, session.encode("{\"dbStats\":1,\"scale\":1,\"freeStorage\":0,\"maxTimeMS\":"
                        + (1000L * timeoutSeconds) + "}")));
        long bytes = cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies.integer(result.path("totalSize"));
        if (bytes < 0) {
            throw new SQLException("MongoDB capacity result is invalid");
        }
        return bytes;
    }

    /**
     * 探测数据库连接并返回连接诊断结果。
     */
    @Override
    public void probeConnection(Connection connection, int timeoutSeconds) throws SQLException {
        cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoConnectionProbe.probe(connection, timeoutSeconds);
    }

    /** 账号与角色目录仅作为读取证据；仍独立验证目标数据库目录，不推断写权限。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        probeNamespace(connection, namespace, timeoutSeconds);
        var session = cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession.open(connection, 65536,
                timeoutSeconds);
        var result = cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies.success(session,
                session.command("admin", session.encode("{\"connectionStatus\":1,\"maxTimeMS\":"
                        + (1000L * timeoutSeconds) + "}")));
        var info = result.path("authInfo");
        if (!info.path("authenticatedUsers").isArray() || !info.path("authenticatedUserRoles").isArray()) {
            throw new SQLException("MongoDB 未返回当前账号和角色元数据");
        }
        if (info.path("authenticatedUsers").isEmpty()) {
            throw new SQLFeatureNotSupportedException("MongoDB 当前会话为匿名或未启用认证，不能证明账号角色权限", "0A000");
        }
    }

    /** 只观察复制拓扑和目标数据库的有效 changeStream/find 授权，不打开变更流。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection, int timeoutSeconds) throws SQLException {
        String database = connection.getCatalog();
        MongoNativeMetadata.identifier(database);
        var session = cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession.open(connection, 65536,
                timeoutSeconds);
        var topology = cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies.success(session,
                session.command("admin", session.encode("{\"hello\":1,\"maxTimeMS\":" + (1000L * timeoutSeconds) + "}")));
        var authorization = cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies.success(session,
                session.command("admin", session.encode("{\"connectionStatus\":1,\"showPrivileges\":true,\"maxTimeMS\":"
                        + (1000L * timeoutSeconds) + "}")));
        var privileges = authorization.path("authInfo").path("authenticatedUserPrivileges");
        if (!privileges.isArray()) {
            throw new SQLException("MongoDB 未返回当前账号有效权限目录");
        }
        boolean find = false;
        boolean changeStream = false;
        for (var privilege : privileges) {
            var resource = privilege.path("resource");
            boolean applicable = resource.path("anyResource").asBoolean(false)
                    || resource.path("collection").isTextual() && resource.path("collection").asText().isEmpty()
                    && resource.path("db").isTextual()
                    && (database.equals(resource.path("db").asText()) || resource.path("db").asText().isEmpty());
            if (applicable && privilege.path("actions").isArray()) {
                for (var action : privilege.path("actions")) {
                    find |= "find".equals(action.asText()) || "anyAction".equals(action.asText());
                    changeStream |= "changeStream".equals(action.asText()) || "anyAction".equals(action.asText());
                }
            }
        }
        boolean topologyReady = topology.path("setName").isTextual() && !topology.path("setName").asText().isBlank()
                || "isdbgrid".equals(topology.path("msg").asText());
        return new cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic(topologyReady, find && changeStream);
    }

    /** 只读检查已存在且可见的数据库，并读取一个有界、无业务文档的集合目录批次。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        MongoNativeMetadata.identifier(namespace);
        var session = cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession.open(connection, 65536,
                timeoutSeconds);
        String encodedName = "\"" + namespace + "\"";
        var databases = cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies.success(session,
                session.command("admin", session.encode("{\"listDatabases\":1,\"nameOnly\":true,\"authorizedDatabases\":true,"
                        + "\"filter\":{\"name\":" + encodedName + "},\"maxTimeMS\":" + (1000L * timeoutSeconds) + "}")));
        if (!databases.path("databases").isArray() || databases.path("databases").size() != 1
                || !namespace.equals(databases.path("databases").get(0).path("name").asText())) {
            throw new SQLException("MongoDB 目标数据库不存在或不可见", "3D000");
        }
        var collections = cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies.success(session,
                session.command(namespace, session.encode("{\"listCollections\":1,\"nameOnly\":true,\"authorizedCollections\":true,"
                        + "\"filter\":{\"name\":\"__dbswitch_diagnostic_no_collection__\"},\"cursor\":{\"batchSize\":1},\"maxTimeMS\":"
                        + (1000L * timeoutSeconds) + "}")));
        if (!collections.path("cursor").path("firstBatch").isArray()
                || cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoReplies.integer(
                        collections.path("cursor").path("id")) != 0) {
            throw new SQLException("MongoDB 集合目录响应无效");
        }
    }

    private static final Set<String> systemSchemas = Sets.newHashSet("admin", "config", "local");

    /**
     * 初始化 {@code DatabaseMongoDBImpl}及其父类状态。
     */
    public DatabaseMongoDBImpl() {
        super(ProductTypeEnum.MONGODB.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.MONGODB;
    }

    /**
     * 获取数据库的模式schema列表。
     */
    @Override
    public List<String> querySchemaList(Connection connection) {
        List<String> catalogs = new ArrayList<String>();
        try (ResultSet rs = connection.getMetaData().getCatalogs()) {
            while (rs.next()) {
                String name = rs.getString("TABLE_CAT");
                if (!systemSchemas.contains(name)) {
                    catalogs.add(name);
                }
            }
            return catalogs;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取指定模式Schema内的所有表列表。
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        List<TableDescription> ret = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getTables(schemaName, null, null, null)) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                TableDescription td = new TableDescription();
                td.setSchemaName(schemaName);
                td.setTableName(tableName);
                td.setRemarks(rs.getString("REMARKS"));
                td.setTableType(rs.getString("TABLE_TYPE").toUpperCase());
                ret.add(td);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return ret;
    }

    /**
     * 精确获取表或视图的元数据。
     */
    @Override
    public TableDescription queryTableMeta(Connection connection, String schemaName, String tableName) {
        try (ResultSet rs = connection.getMetaData()
                .getTables(schemaName, null, tableName, new String[]{"TABLE"})) {
            if (rs.next()) {
                TableDescription td = new TableDescription();
                td.setSchemaName(schemaName);
                td.setTableName(tableName);
                td.setRemarks(rs.getString("REMARKS"));
                td.setTableType(rs.getString("TABLE_TYPE").toUpperCase());
                return td;
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        try {
            var metadata = MongoNativeMetadata.ordinaryCollection(connection, schemaName, tableName);
            // 不能通过名义上的 DDL 导出静默丢弃原生校验器、排序规则及索引。
            if (!metadata.getJSONObject("options").isEmpty()) {
                throw new java.sql.SQLFeatureNotSupportedException(
                        "MongoDB collection options require native DDL export", "0A000");
            }
            MongoNativeMetadata.requireDefaultIdIndex(connection, schemaName, tableName);
            return MongoNativeMetadata.database(schemaName) + ".createCollection('" + tableName + "');";
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取指定视图表的DDL语句。
     */
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        return null;
    }

    /**
     * 获取指定模式表的字段列表。
     */
    public List<String> queryTableColumnName(Connection connection, String schemaName, String tableName) {
        return queryTableColumnMeta(connection, schemaName, tableName).stream().map(ColumnDescription::getFieldName)
                .toList();
    }

    /**
     * 获取查询字段默认值/注释的 SQL（数据库方言相关）。
     */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        return null;
    }

    /**
     * 获取指定模式表的元信息。
     */
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName,
            String tableName) {
        try {
            MongoNativeMetadata.ordinaryCollection(connection, schemaName, tableName);
            var session = cn.iocoder.yudao.framework.dbswitch.product.mongodb.MongoNativeSession.open(connection,
                    32L * 1024 * 1024, 5);
            try (var cursor = session.find(schemaName, tableName, null, List.of(), 1)) {
                byte[] document = cursor.next();
                var columns = new ArrayList<ColumnDescription>();
                if (document != null) {
                    session.cells(document)
                            .forEach((name, cell) -> columns.add(observedColumn(name, Byte.toUnsignedInt(cell[4]))));
                }
                if (columns.isEmpty()) {
                    columns.add(observedColumn("_id", 0));
                }
                return columns;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("MongoDB bounded native column observation failed");
        }
    }

    /**
     * 获取指定查询SQL的元信息。
     */
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        if (sql == null || sql.split("[.]", -1).length != 2) {
            throw new IllegalArgumentException("MongoDB metadata requires a database.collection handle");
        }
        String[] names = sql.split("[.]", -1);
        return queryTableColumnMeta(connection, names[0], names[1]);
    }

    /**
     * 根据当前目录事实构建已观测列定义。
     */
    private ColumnDescription observedColumn(String name, int bsonType) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setLabelName(name);
        column.setFieldType(Types.OTHER);
        column.setFieldTypeName("BSON_TYPE_" + bsonType);
        column.setFiledTypeClassName(cn.iocoder.yudao.framework.dbswitch.common.entity.NativeCellValue.class.getName());
        column.setNullable(!"_id".equals(name));
        column.setPk("_id".equals(name));
        column.setDbType(getDatabaseType());
        column.setRemarks("Bounded one-document observation; not a complete collection schema");
        return column;
    }


    /**
     * 获取指定模式表的主键字段列表。
     */
    public List<String> queryTablePrimaryKeys(Connection connection, String schemaName, String tableName) {
        try {
            MongoNativeMetadata.ordinaryCollection(connection, schemaName, tableName);
            return List.of("_id");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        return MongoNativeMetadata.fingerprint(connection, reference);
    }

    /**
     * 获取数据库的表全名。
     */
    public String getQuotedSchemaTableCombination(String schemaName, String tableName) {
        MongoNativeMetadata.identifier(schemaName);
        MongoNativeMetadata.identifier(tableName);
        return String.format("%s.%s", schemaName, tableName);
    }

    /**
     * 获取字段列的结构定义。
     */
    public String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc, boolean addCr,
            boolean withRemarks) {
        return null;
    }

    /** 产品驱动把语句当作 JavaScript，不能执行 ALTER TABLE，也不拼加列 SQL。 */
    @Override
    @SneakyThrows
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        throw new SQLFeatureNotSupportedException("MongoDB 不能执行 ALTER TABLE", "0A000");
    }

    /**
     * 主键列转换为逗号分隔的字符串。
     */
    public String getPrimaryKeyAsString(List<String> pks) {
        return null;
    }

    /**
     * 获取表和字段的注释定义。
     */
    public List<String> getTableColumnCommentDefinition(TableDescription td, List<ColumnDescription> cds) {
        return Collections.emptyList();
    }

    /**
     * 生成用于探测表字段的查询 SQL。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        return MongoNativeMetadata.collection(schemaName, tableName) + ".find().limit(1);";
    }

    /**
     * 把用户 SQL 包装为低成本的合法性探测语句（如 explain、外层包 WHERE 1=2 等），由子类按方言实现。
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        return ProductTypeEnum.MONGODB.getTestSql();
    }

    /**
     * 为字段描述列表补充索引信息。
     */
    @Override
    public void setColumnIndexInfo(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> columnDescriptions) {
    }

    /** 集合索引由原生结构迁移处理，不经 JDBC 桥的 getIndexInfo。 */
    @Override
    public List<cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription> queryTableIndexes(
            Connection connection,
            String schemaName, String tableName) {
        return List.of();
    }

}
