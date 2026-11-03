package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Collections;
import java.util.List;

public class DatabaseInfluxdbImpl extends AbstractDatabase implements IDatabaseInterface {

    /** Flight SQL 原生信息模式基本表目录，超限失败，不把 VIEW 当作采样表。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String schema, int maxTables,
            int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.INFLUXDB3) {
            return super.queryCatalogTableList(connection, schema, maxTables, timeoutSeconds);
        }
        if (schema == null || schema.isBlank() || maxTables < 1 || maxTables > 10000
                || timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException("目录读取必须指定 schema、数量及超时上限");
        }
        String sql = "SELECT table_schema,table_name FROM information_schema.tables "
                + "WHERE table_schema=? AND table_type='BASE TABLE' ORDER BY table_name LIMIT " + (maxTables + 1);
        var tables = new java.util.TreeMap<String, TableDescription>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema); statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(maxTables + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (!schema.equals(rows.getString(1)) || rows.getString(2) == null || rows.getString(2).isBlank()) {
                        throw new SQLException("原生目录返回非精确或无效物理名称");
                    }
                    var table = new TableDescription();
                    table.setSchemaName(rows.getString(1)); table.setTableName(rows.getString(2)); table.setTableType("TABLE");
                    if (tables.putIfAbsent(table.getTableName(), table) != null || tables.size() > maxTables) {
                        throw new SQLException("目录不唯一或超过数量上限，拒绝截断结果", "54000");
                    }
                }
            }
        }
        return List.copyOf(tables.values());
    }

    /** Flight SQL 不支持 USE；在已认证数据库会话精确读取目标 schema 目录。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (namespace == null || namespace.isBlank() || timeoutSeconds < 1) {
            throw new IllegalArgumentException("目标 schema 和有效超时不能为空");
        }
        try (var statement = connection.prepareStatement(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name = ?")) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setString(1, namespace);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !namespace.equals(rows.getString(1))) {
                    throw new SQLException("InfluxDB 目标 schema 不存在或不可见", "3F000");
                }
            }
        }
    }

    /** Flight SQL 的目录权限不能替代 Token 管理协议的授权范围证据。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        throw new SQLFeatureNotSupportedException(
                "InfluxDB3 Flight SQL 没有已验证的 Token 授权目录；需要受控管理端点、版本能力及独立授权传递，不能用 schema 可见性冒充账号权限", "0A000");
    }

    /** 不用空注释冒充不具备的关系表列 COMMENT 能力。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        throw new cn.iocoder.yudao.framework.dbswitch.core.model.DiagnosticNotApplicableException(
                "InfluxDB3 未提供同等原生表列 COMMENT 目录；此属性不适用，不返回注释目录读取成功");
    }

    /**
     * 初始化 {@code DatabaseInfluxdbImpl}及其父类状态。
     */
    public DatabaseInfluxdbImpl() {
        super(ProductTypeEnum.INFLUXDB3.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.INFLUXDB3;
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String alias, String column) {
        String field = projectedField(alias, column);
        // 保持载荷为数值类型：Arrow 的 VARCHAR getLong 遇到 SQL NULL 会抛出异常。
        // 下面的原生同一行类型证明仍先于消费方的每次读取。
        return new IntegerProjection("CASE WHEN arrow_typeof(" + field + ") = 'Int64' THEN CAST("
                + field + " AS BIGINT) ELSE NULL END", integerProjection(alias, column, 21).typeSql());
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String alias, String column, int maxBytes) {
        if (maxBytes < 1 || maxBytes > 256) {
            throw new IllegalArgumentException("Invalid InfluxDB integer byte budget");
        }
        return integerProjection(alias, column, maxBytes);
    }

    /**
     * 生成整数值的查询投影。
     */
    private static IntegerProjection integerProjection(String alias, String column, int maxBytes) {
        String field = projectedField(alias, column);
        String text = "CAST(" + field + " AS VARCHAR)";
        return new IntegerProjection(
                "CASE WHEN LENGTH(" + text + ") <= " + maxBytes + " THEN " + text + " ELSE NULL END",
                "CASE WHEN arrow_typeof(" + field + ") = 'Int64' THEN -5 ELSE 0 END");
    }

    /**
     * 生成带必要别名和标识符引用的字段投影。
     */
    private static String projectedField(String alias, String column) {
        return (alias == null ? "" : RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.INFLUXDB3,
                alias) + ".")
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.INFLUXDB3, column);
    }

    private record IntegerProjection(String valueSql,
                                     String typeSql) implements BoundedIntegerKeyProjection, BoundedKeyComponentProjection {

        /**
         * 整数的十进制表示也是精确且有界的元组组件。
         */
        @Override
        public String groupSql() {
            return valueSql;
        }

        /**
         * 说明当前查询路径是否要求显式添加外层数量限制。
         */
        @Override
        public boolean requiresExplicitOuterLimit() {
            return true;
        }

        /**
         * 判断当前类型处理是否要求数据行类型证据。
         */
        @Override
        public boolean requiresRowTypeWitness() {
            return true;
        }

        /**
         * 确定当前列或值对应的 JDBC 类型。
         */
        @Override
        public int jdbcType(java.sql.ResultSetMetaData metadata, int column) {
            return 0;
        }

        /**
         * 当前行提供的有界原生验证信息，不持有游标或连接。
         */
        @Override
        public int jdbcType(java.sql.ResultSet rows, int column) throws SQLException {
            // Arrow JDBC 18.1 即使对 Int64 也报告 signed=false。需要原生同一行见证。
            if (rows.getMetaData().getColumnType(column) != java.sql.Types.BIGINT) {
                return 0;
            }
            long type = rows.getLong(column);
            return !rows.wasNull() && type == java.sql.Types.BIGINT ? java.sql.Types.BIGINT : 0;
        }

    }

    /**
     * 单个物理列转为保留 NULL 的文本表达式；不接受任意 SQL，不代表可作为无损身份编码。
     */
    @Override
    public String textColumnExpression(String column) {
        return "CAST(" + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.INFLUXDB3,
                column) + " AS VARCHAR)";
    }

    /**
     * 获取指定模式Schema内的所有表列表。
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        List<TableDescription> result = new ArrayList<>();
        var seen = new HashSet<String>();
        // Flight SQL 将 Influx 物理关系报告为 BASE TABLE，而非 JDBC TABLE。
        try (var rows = connection.getMetaData().getTables(this.catalogName, schemaName, "%",
                new String[]{"BASE TABLE", "TABLE", "VIEW"})) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME");
                if (!seen.add(name)) {
                    continue;
                }
                var table = new TableDescription();
                table.setSchemaName(schemaName);
                table.setTableName(name);
                String type = rows.getString("TABLE_TYPE").toUpperCase(Locale.ROOT);
                table.setTableType("BASE TABLE".equals(type) ? "TABLE" : type);
                table.setRemarks(rows.getString("REMARKS"));
                result.add(table);
            }
            return result;
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
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
     * 获取指定模式表的元信息。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schema, String table) {
        cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxNativeCatalog.schema(schema);
        String sql = "SELECT CAST(key AS VARCHAR) AS native_key,CAST(data_type AS VARCHAR) AS native_type FROM system.influxdb_schema WHERE measurement="
                + cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxNativeSession.literal(
                table) + " ORDER BY native_key LIMIT 501";
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            var result = new ArrayList<ColumnDescription>();
            var seen = new HashSet<String>();
            try (var rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    String name = rows.getString(1), nativeType = rows.getString(2);
                    if (!seen.add(name) || result.size() >= 500) {
                        throw new SQLException("Influx native column evidence is inconsistent");
                    }
                    var column = new ColumnDescription();
                    column.setFieldName(name);
                    column.setLabelName(name);
                    column.setDbType(ProductTypeEnum.INFLUXDB3);
                    column.setAutoIncrement(false);
                    column.setNullable(!"time".equals(nativeType));
                    column.setPk("time".equals(nativeType) || "tag".equals(nativeType));
                    column.setSigned("integer".equals(nativeType));
                    column.setFieldType(switch (nativeType) {
                        case "time" -> java.sql.Types.TIMESTAMP;
                        case "tag", "string" -> java.sql.Types.VARCHAR;
                        case "integer", "uinteger" -> java.sql.Types.BIGINT;
                        case "float" -> java.sql.Types.DOUBLE;
                        case "boolean" -> java.sql.Types.BOOLEAN;
                        default -> throw new SQLException("Influx native column type is unknown");
                    });
                    column.setFieldTypeName(nativeType);
                    column.setFiledTypeClassName(switch (nativeType) {
                        case "time" -> java.sql.Timestamp.class.getName();
                        case "tag", "string" -> String.class.getName();
                        case "integer" -> Long.class.getName();
                        case "uinteger" -> java.math.BigInteger.class.getName();
                        case "float" -> Double.class.getName();
                        default -> Boolean.class.getName();
                    });
                    if ("uinteger".equals(nativeType)) {
                        column.setPrecisionSize(20);
                    } else if ("integer".equals(nativeType)) {
                        column.setPrecisionSize(19);
                    }
                    result.add(column);
                }
            }
            if (result.isEmpty()) {
                throw new SQLException("Influx native table was not observed");
            }
            return result;
        } catch (SQLException invalid) {
            throw new IllegalStateException("Influx native column metadata could not be read");
        }
    }

    /**
     * 读取表结构中的列定义。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMetaOnly(Connection connection, String schema, String table) {
        return queryTableColumnMeta(connection, schema, table);
    }

    /**
     * 获取指定模式表的字段列表。
     */
    @Override
    public List<String> queryTableColumnName(Connection connection, String schema, String table) {
        return queryTableColumnMeta(connection, schema, table).stream().map(ColumnDescription::getFieldName).toList();
    }

    /**
     * 为字段描述列表补充索引信息。
     */
    @Override
    public void setColumnIndexInfo(Connection connection, String schema, String table,
            List<ColumnDescription> columns) {
        // Core 暴露标签、时间组成的点身份，而非 JDBC 次级索引。
    }

    /**
     * 读取表的索引定义。
     */
    @Override
    public List<cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription> queryTableIndexes(
            Connection connection,
            String schema, String table) {
        return List.of();
    }

    /**
     * 获取指定视图表的DDL语句。
     */
    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        return null;
    }

    /**
     * 获取查询字段默认值/注释的 SQL（数据库方言相关）。
     */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        return null;
    }

    /**
     * 获取指定查询SQL的元信息。
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(java.sql.Connection connection, String sql) {
        String querySql = "SELECT * FROM (" + sql + ") __dbswitch_meta LIMIT 0";
        return this.getSelectSqlColumnMeta(connection, querySql);
    }

    /**
     * 生成用于探测表字段的查询 SQL。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxNativeCatalog.schema(schemaName);
        return "SELECT * FROM \"iox\"." + cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxNativeSession.quote(
                tableName) + " WHERE 1=2";
    }

    /**
     * 把用户 SQL 包装为低成本的合法性探测语句（如 explain、外层包 WHERE 1=2 等），由子类按方言实现。
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        return "SELECT * FROM (" + sql + ") __dbswitch_meta LIMIT 0";
    }

    /**
     * 获取指定模式表的主键字段列表。
     */
    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schemaName, String tableName) {
        cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxNativeCatalog.schema(schemaName);
        String sql = "SELECT CAST(key AS VARCHAR) AS native_key FROM system.influxdb_schema WHERE measurement="
                + cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxNativeSession.literal(tableName)
                + " AND data_type='tag' ORDER BY native_key LIMIT 501";
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            var keys = new ArrayList<String>();
            try (var rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    if (keys.size() >= 499) {
                        throw new SQLException("Influx key exceeds its column budget");
                    }
                    keys.add(rows.getString(1));
                }
            }
            keys.add("time");
            return List.copyOf(keys);
        } catch (SQLException invalid) {
            throw new IllegalStateException("Influx native primary-key roles could not be read");
        }
    }

    /**
     * 获取字段列的结构定义。
     */
    @Override
    public String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc, boolean addCr,
            boolean withRemarks) {
        String fieldname = v.getName();
        int length = v.getLength();
        int precision = v.getPrecision();
        int type = v.getType();
        boolean nullable = v.isNullable();

        StringBuilder sb = new StringBuilder();
        sb.append(" \"").append(fieldname).append("\" ");

        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
                sb.append("TIMESTAMP");
                break;
            case ColumnMetaData.TYPE_TIME:
                sb.append("TIME");
                break;
            case ColumnMetaData.TYPE_DATE:
                sb.append("DATE");
                break;
            case ColumnMetaData.TYPE_BOOLEAN:
                sb.append("BOOLEAN");
                break;
            case ColumnMetaData.TYPE_INTEGER:
            case ColumnMetaData.TYPE_BIGNUMBER:
                sb.append("BIGINT");
                break;
            case ColumnMetaData.TYPE_NUMBER:
                sb.append("DOUBLE");
                break;
            case ColumnMetaData.TYPE_BINARY:
                sb.append("BYTEA");
                break;
            case ColumnMetaData.TYPE_STRING:
            default:
                int varcharLen = length > 0 ? length : 256;
                sb.append("VARCHAR(").append(varcharLen).append(")");
                break;
        }

        if (!nullable) {
            sb.append(" NOT NULL");
        }

        if (pks != null && !pks.isEmpty() && pks.contains(fieldname) && useAutoInc) {
            sb.append(" ");
        }

        if (withRemarks && StringUtils.isNotBlank(v.getRemarks())) {
            sb.append(" ");
        }

        if (addCr) {
            sb.append(Const.CR);
        }
        return sb.toString();
    }

    /**
     * 获取表和字段的注释定义。
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td, List<ColumnDescription> cds) {
        return Collections.emptyList();
    }

    /** InfluxDB 3 ALTER 尚未验证，因此不将模式或表名拼入 SQL。 */
    @Override
    @SneakyThrows
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        throw new SQLFeatureNotSupportedException("InfluxDB 3 add column is unverified", "0A000");
    }
}
