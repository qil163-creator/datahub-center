package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.UxdbRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.product.uxdb.UxdbAddColumn;
import lombok.SneakyThrows;

import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * 优炫元信息。关系身份和有界整数键只证明 UXsinoDB 2.1.0.3 的永久 heap，不调用 PostgreSQL 指纹。
 * 其余元数据方法仍是既有继承行为，不因本次身份实现改写。
 * 可空加列只生成带引号的 ALTER TABLE ... ADD COLUMN，不查询目录。
 */
public class DatabaseUxdbImpl extends DatabasePostgresImpl implements IDatabaseInterface {

    /** UXsinoDB 2.1.0.3 的 ux_catalog 复制角色和日志配置，不调用 PG 目录或创建复制槽。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection,int timeoutSeconds) throws SQLException {
        if (getDatabaseType()!=ProductTypeEnum.UXDB) return super.probeCdcReadiness(connection,timeoutSeconds);
        diagnosticBounds(connection,"CDC",1,timeoutSeconds);
        String sql="SELECT ux_catalog.version(),ux_catalog.current_setting('server_version'),ux_catalog.current_setting('wal_level'),"
                + "ux_catalog.current_setting('max_replication_slots')::int,ux_catalog.current_setting('max_wal_senders')::int,rolsuper,rolreplication "
                + "FROM ux_catalog.ux_roles WHERE rolname=current_user";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("UXDB CDC 配置及角色目录无响应");
                String version=rows.getString(1); String server=rows.getString(2);
                if (version==null || !version.matches("UXsinoDB 2[.]1[.]0[.]3(?: on .*)?") || !"10.0".equals(server))
                    throw new java.sql.SQLFeatureNotSupportedException("UXDB CDC 仅声明 UXsinoDB 2.1.0.3 原生目录契约","0A000");
                String wal=rows.getString(3); int slots=rows.getInt(4); boolean missing=rows.wasNull(); int senders=rows.getInt(5); missing|=rows.wasNull();
                boolean administrator=rows.getBoolean(6); missing|=rows.wasNull(); boolean replication=rows.getBoolean(7);
                if (missing || rows.wasNull() || wal==null || slots<0 || senders<0 || rows.next()) throw new SQLException("UXDB CDC 配置及角色证据不完整");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic("logical".equals(wal)&&slots>0&&senders>0,administrator||replication);
            }
        }
    }

    /** UXDB 使用 ux_catalog 和 ux_description，不把 PostgreSQL 目录硬套到优炫。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        if (getDatabaseType()!=ProductTypeEnum.UXDB) return super.probeExtendedProperties(connection,namespace,table,timeoutSeconds);
        diagnosticBounds(connection,namespace,1,timeoutSeconds); diagnosticBounds(connection,table,1,timeoutSeconds);
        String sql="SELECT CASE WHEN t.description IS NULL THEN 0 ELSE 1 END,COUNT(a.attnum),COUNT(d.description) "
                + "FROM ux_catalog.ux_class c JOIN ux_catalog.ux_namespace n ON n.oid=c.relnamespace "
                + "LEFT JOIN ux_catalog.ux_description t ON t.objoid=c.oid AND t.objsubid=0 AND t.classoid=CAST('ux_catalog.ux_class' AS ux_catalog.regclass) "
                + "LEFT JOIN ux_catalog.ux_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped "
                + "LEFT JOIN ux_catalog.ux_description d ON d.objoid=c.oid AND d.objsubid=a.attnum AND d.classoid=CAST('ux_catalog.ux_class' AS ux_catalog.regclass) "
                + "WHERE n.nspname=? AND c.relname=? AND c.relkind='r' GROUP BY c.oid,t.description";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setString(1,namespace); statement.setString(2,table); statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("UXDB 目标表不存在或不可见","42S02");
                int tableComments=rows.getInt(1); int columns=rows.getInt(2); int comments=rows.getInt(3);
                if (rows.wasNull() || columns<1 || comments<0 || comments>columns || tableComments<0 || tableComments>1 || rows.next()) throw new SQLException("UXDB 注释目录证据不完整或不唯一");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(columns,tableComments==1,comments);
            }
        }
    }


    /** UXDB 目录和权限函数显式限定 ux_catalog，不使用 pg_catalog。 */
    @Override
    public void probeNamespace(java.sql.Connection connection, String namespace, int timeoutSeconds) throws java.sql.SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT ux_catalog.has_schema_privilege(current_user,n.oid,'USAGE') FROM ux_catalog.ux_namespace n WHERE n.nspname=?")) {
            statement.setString(1, namespace); statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new java.sql.SQLException("UXDB 命名空间不存在", "3F000");
                if (!rows.getBoolean(1) || rows.wasNull()) throw new java.sql.SQLException("UXDB schema USAGE 被拒绝", "42501");
            }
        }
    }

    /** 读取本产品账号目录，不读取密码、不推断普通表写权限。 */
    @Override
    public void probeAccountPrivileges(java.sql.Connection connection, String namespace, int timeoutSeconds) throws java.sql.SQLException {
        probeNamespace(connection, namespace, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT rolname FROM ux_catalog.ux_roles WHERE rolname=current_user")) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) throw new java.sql.SQLException("UXDB 当前账号目录证据缺失");
            }
        }
    }

    /** 使用 UXDB 原生基本表目录并拒绝截断，不继承 PostgreSQL 表目录能力。 */
    @Override
    public java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription> queryCatalogTableList(java.sql.Connection connection, String namespace, int maxTables, int timeoutSeconds) throws java.sql.SQLException {
        diagnosticBounds(connection, namespace, maxTables, timeoutSeconds);
        var result = new java.util.ArrayList<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription>();
        try (var statement = connection.prepareStatement("SELECT c.relname FROM ux_catalog.ux_class c JOIN ux_catalog.ux_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relkind='r' ORDER BY c.relname LIMIT ?")) {
            statement.setString(1, namespace); statement.setInt(2, maxTables + 1); statement.setMaxRows(maxTables + 1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == maxTables) throw new java.sql.SQLException("UXDB 目录超过上限，拒绝截断", "54000");
                    var table = new cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription();
                    table.setSchemaName(namespace); table.setTableName(rows.getString(1)); table.setTableType("TABLE"); result.add(table);
                }
            }
        }
        return java.util.List.copyOf(result);
    }

    private static void diagnosticBounds(java.sql.Connection connection, String namespace, int maxTables, int timeoutSeconds) {
        if (connection == null || namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || maxTables < 1 || maxTables > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("UXDB 诊断参数必须完整且有界");
    }

    /**
     * 初始化 {@code DatabaseUxdbImpl}及其父类状态。
     */
    public DatabaseUxdbImpl() {
        super(ProductTypeEnum.UXDB.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.UXDB;
    }

    /** UXDB 将 timestamp 和 timestamptz 报告为 JDBC 93，time 和 timetz 报告为 92，须保留原生区别。 */
    @Override
    @SneakyThrows
    public String getFieldDefinition(ColumnMetaData column, List<String> keys, boolean autoIncrement, boolean addCr,
            boolean remarks) {
        if (getDatabaseType() != ProductTypeEnum.UXDB || column.getNativeTypeName() == null) {
            return super.getFieldDefinition(column, keys, autoIncrement, addCr, remarks);
        }
        String nativeType = column.getNativeTypeName().toLowerCase(java.util.Locale.ROOT);
        if (column.getSqlType() == Types.ARRAY || nativeType.startsWith("_")) {
            throw new java.sql.SQLFeatureNotSupportedException(
                    "UXDB legacy DDL cannot reconstruct native array columns", "0A000");
        }
        String type = switch (nativeType) {
            case "int2" -> "SMALLINT";
            case "int4" -> "INTEGER";
            case "int8" -> "BIGINT";
            case "float4" -> "REAL";
            case "float8" -> "DOUBLE PRECISION";
            case "timestamp", "timestamptz", "time", "timetz" -> {
                int scale = column.getPrecision() >= 0 ? column.getPrecision() : nativeType.startsWith(
                        "timestamp") && column.getLength() >= 0 ? column.getLength() : 6;
                if (scale > 6) {
                    throw new java.sql.SQLFeatureNotSupportedException("UXDB temporal precision is not verified",
                            "0A000");
                }
                String base = nativeType.startsWith("timestamp") ? "TIMESTAMP" : "TIME";
                yield base + '(' + Integer.toString(scale) + ')' + (nativeType.endsWith("tz") ? " WITH TIME ZONE" : "");
            }
            default -> null;
        };
        if (type == null || autoIncrement && keys != null && keys.contains(column.getName())) {
            return super.getFieldDefinition(column, keys, autoIncrement, addCr, remarks);
        }
        String result = " " + quoteIdentifier(column.getName()) + "   " + type;
        if (column.getDefaultValue() != null && !"null".equalsIgnoreCase(column.getDefaultValue())) {
            result += " DEFAULT " + renderDefault(column);
        }
        if (!column.isNullable()) {
            result += " NOT NULL";
        }
        return result + (addCr ? "\n" : "");
    }

    /**
     * 获取添加字段的sql。
     */
    @Override
    @SneakyThrows
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        if (getDatabaseType() != ProductTypeEnum.UXDB) {
            return super.getAddColumnSql(targetSchemaName, targetTableName, targetColumn);
        }
        return UxdbAddColumn.sql(this, targetSchemaName, targetTableName, targetColumn);
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.UXDB) {
            return super.queryRelationFingerprint(connection, reference);
        }
        return UxdbRelationIdentity.fingerprint(connection, reference);
    }

    /**
     * 为字段描述列表补充默认值与注释信息。
     */
    @Override
    public void setColumnDefaultValue(Connection connection, String schema, String table,
            List<ColumnDescription> columns) {
        if (getDatabaseType() != ProductTypeEnum.UXDB) {
            super.setColumnDefaultValue(connection, schema, table, columns);
            return;
        }
        String sql = "SELECT a.attname, ux_catalog.ux_get_expr(d.adbin,d.adrelid), x.description "
                + "FROM ux_catalog.ux_class c JOIN ux_catalog.ux_namespace n ON n.oid=c.relnamespace "
                + "JOIN ux_catalog.ux_attribute a ON a.attrelid=c.oid "
                + "LEFT JOIN ux_catalog.ux_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum "
                + "LEFT JOIN ux_catalog.ux_description x ON x.objoid=c.oid AND x.objsubid=a.attnum "
                + "AND x.classoid=CAST('ux_catalog.ux_class' AS ux_catalog.regclass) "
                + "WHERE n.nspname=? AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(30);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    for (ColumnDescription column : columns) {
                        if (column.getFieldName().equals(rows.getString(1))) {
                            column.setDefaultValue(rows.getString(2));
                            column.setRemarks(rows.getString(3));
                            break;
                        }
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("UXDB column defaults and comments could not be read");
        }
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String alias, String column) {
        if (getDatabaseType() != ProductTypeEnum.UXDB) {
            return super.integerKeyProjection(alias, column);
        }
        return integerProjection(alias, column, 21);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.UXDB) {
            return super.keyComponentProjection(alias, column, maxBytes);
        }
        if (maxBytes < 1 || maxBytes > 4096) {
            throw new IllegalArgumentException("Invalid key byte bound");
        }
        return integerProjection(alias, column, Math.min(maxBytes, 21));
    }

    /**
     * 生成整数值的查询投影。
     */
    private static IntegerProjection integerProjection(String alias, String column, int maxBytes) {
        String field = (alias == null ? "" : RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.UXDB,
                alias) + ".")
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.UXDB, column);
        String text = "CAST(" + field + " AS ux_catalog.text)";
        return new IntegerProjection("CASE WHEN ux_catalog.octet_length(" + text + ") <= " + maxBytes
                + " THEN " + text + " ELSE NULL END", "NULLIF(" + field + ", " + field + ")");
    }

    private record IntegerProjection(String valueSql, String typeSql)
            implements BoundedIntegerKeyProjection, BoundedKeyComponentProjection {

        /**
         * 整数的十进制表示也是精确且有界的元组组件。
         */
        @Override
        public String groupSql() {
            return valueSql;
        }

        /**
         * 确定当前列或值对应的 JDBC 类型。
         */
        @Override
        public int jdbcType(ResultSetMetaData metadata, int column) throws SQLException {
            if (!metadata.isSigned(column)) {
                return 0;
            }
            String nativeType = metadata.getColumnTypeName(column);
            int jdbc = metadata.getColumnType(column);
            if (nativeType == null) {
                return 0;
            }
            return switch (nativeType) {
                case "int2" -> jdbc == Types.SMALLINT ? jdbc : 0;
                case "int4" -> jdbc == Types.INTEGER ? jdbc : 0;
                case "int8" -> jdbc == Types.BIGINT ? jdbc : 0;
                default -> 0;
            };
        }
    }
}
