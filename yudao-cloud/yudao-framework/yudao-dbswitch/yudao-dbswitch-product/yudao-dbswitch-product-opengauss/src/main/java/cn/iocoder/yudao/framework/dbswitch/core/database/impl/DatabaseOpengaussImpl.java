package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils;

import java.sql.Connection;
import java.sql.SQLException;

import cn.iocoder.yudao.framework.dbswitch.core.util.OpengaussRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.product.opengauss.OpengaussAddColumn;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedTextKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.ResultSetMetaData;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 支持 openGauss 数据库的元信息实现，复用 PostgreSQL 方言并过滤系统 schema。
 * 可空加列只生成 sql_compatibility A 的带引号 ALTER TABLE ... ADD COLUMN，不查询目录，也不回落到父类。
 */
public class DatabaseOpengaussImpl extends DatabasePostgresImpl implements IDatabaseInterface {

    /** openGauss 7 RC3 A 模式独立读取日志配置和复制角色；不借用 PG 的能力开关。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection,int timeoutSeconds) throws SQLException {
        if (getDatabaseType()!=ProductTypeEnum.OPENGAUSS) return super.probeCdcReadiness(connection,timeoutSeconds);
        diagnosticBounds(connection,"CDC",1,timeoutSeconds);
        String sql="SELECT pg_catalog.version(),pg_catalog.current_setting('sql_compatibility'),pg_catalog.current_setting('wal_level'),"
                + "pg_catalog.current_setting('max_replication_slots')::int,pg_catalog.current_setting('max_wal_senders')::int,(rolsuper OR rolsystemadmin),rolreplication "
                + "FROM pg_catalog.pg_roles WHERE rolname=current_user";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("openGauss CDC 配置及角色目录无响应");
                String version=rows.getString(1); String mode=rows.getString(2);
                if (version==null || !version.startsWith("(openGauss 7.0.0-RC3 build ") || !"A".equals(mode))
                    throw new java.sql.SQLFeatureNotSupportedException("openGauss CDC 仅声明 7.0.0-RC3 A 模式目录契约","0A000");
                String wal=rows.getString(3); int slots=rows.getInt(4); boolean missing=rows.wasNull(); int senders=rows.getInt(5); missing|=rows.wasNull();
                boolean administrator=rows.getBoolean(6); missing|=rows.wasNull(); boolean replication=rows.getBoolean(7);
                if (missing || rows.wasNull() || wal==null || slots<0 || senders<0 || rows.next()) throw new SQLException("openGauss CDC 配置及角色证据不完整");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic("logical".equals(wal)&&slots>0&&senders>0,administrator||replication);
            }
        }
    }

    /** openGauss 仅复用本产品已采用的基本表及列注释目录，不使用 PG 新版本列属性。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        if (getDatabaseType()!=ProductTypeEnum.OPENGAUSS) return super.probeExtendedProperties(connection,namespace,table,timeoutSeconds);
        diagnosticBounds(connection,namespace,1,timeoutSeconds); diagnosticBounds(connection,table,1,timeoutSeconds);
        String sql="SELECT CASE WHEN pg_catalog.obj_description(c.oid,'pg_class') IS NULL THEN 0 ELSE 1 END,"
                + "COUNT(a.attnum),COUNT(pg_catalog.col_description(c.oid,a.attnum)) "
                + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped "
                + "WHERE n.nspname=? AND c.relname=? AND c.relkind='r' GROUP BY c.oid";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setString(1,namespace); statement.setString(2,table); statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("openGauss 目标表不存在或不可见","42S02");
                int tableComments=rows.getInt(1); int columns=rows.getInt(2); int comments=rows.getInt(3);
                if (rows.wasNull() || columns<1 || comments<0 || comments>columns || tableComments<0 || tableComments>1 || rows.next()) throw new SQLException("openGauss 注释目录证据不完整或不唯一");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(columns,tableComments==1,comments);
            }
        }
    }


    /** openGauss 独立原生权限入口，不继承 PostgreSQL 的 CDC、写入或角色字段假设。 */
    @Override
    public void probeNamespace(java.sql.Connection connection, String namespace, int timeoutSeconds) throws java.sql.SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT pg_catalog.has_schema_privilege(current_user,n.oid,'USAGE') FROM pg_catalog.pg_namespace n WHERE n.nspname=?")) {
            statement.setString(1, namespace); statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new java.sql.SQLException("openGauss 命名空间不存在", "3F000");
                if (!rows.getBoolean(1) || rows.wasNull()) throw new java.sql.SQLException("openGauss schema USAGE 被拒绝", "42501");
            }
        }
    }

    /** 只观测当前账号角色目录，不读取密码目录或推断业务表授权。 */
    @Override
    public void probeAccountPrivileges(java.sql.Connection connection, String namespace, int timeoutSeconds) throws java.sql.SQLException {
        probeNamespace(connection, namespace, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT rolname FROM pg_catalog.pg_roles WHERE rolname=current_user")) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) throw new java.sql.SQLException("openGauss 当前账号目录证据缺失");
            }
        }
    }

    /** openGauss 基本表原生目录；不把 PostgreSQL 分区目录格式强加给此产品。 */
    @Override
    public java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription> queryCatalogTableList(java.sql.Connection connection, String namespace, int maxTables, int timeoutSeconds) throws java.sql.SQLException {
        diagnosticBounds(connection, namespace, maxTables, timeoutSeconds);
        var result = new java.util.ArrayList<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription>();
        try (var statement = connection.prepareStatement("SELECT c.relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relkind='r' ORDER BY c.relname LIMIT ?")) {
            statement.setString(1, namespace); statement.setInt(2, maxTables + 1); statement.setMaxRows(maxTables + 1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == maxTables) throw new java.sql.SQLException("openGauss 目录超过上限，拒绝截断", "54000");
                    var table = new cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription();
                    table.setSchemaName(namespace); table.setTableName(rows.getString(1)); table.setTableType("TABLE"); result.add(table);
                }
            }
        }
        return java.util.List.copyOf(result);
    }

    private static void diagnosticBounds(java.sql.Connection connection, String namespace, int maxTables, int timeoutSeconds) {
        if (connection == null || namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || maxTables < 1 || maxTables > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("openGauss 诊断参数必须完整且有界");
    }

    private static final Set<String> SYSTEM_SCHEMAS = new HashSet<>();

    static {
        SYSTEM_SCHEMAS.add("blockchain");
        SYSTEM_SCHEMAS.add("cstore");
        SYSTEM_SCHEMAS.add("db4ai");
        SYSTEM_SCHEMAS.add("dbe_perf");
        SYSTEM_SCHEMAS.add("dbe_pldebugger");
        SYSTEM_SCHEMAS.add("dbe_pldeveloper");
        SYSTEM_SCHEMAS.add("dbe_sql_util");
        SYSTEM_SCHEMAS.add("information_schema");
        SYSTEM_SCHEMAS.add("pg_catalog");
        SYSTEM_SCHEMAS.add("pkg_service");
        SYSTEM_SCHEMAS.add("snapshot");
        SYSTEM_SCHEMAS.add("sqladvisor");
    }

    /**
     * 初始化 {@code DatabaseOpengaussImpl}及其父类状态。
     */
    public DatabaseOpengaussImpl() {
        super(ProductTypeEnum.OPENGAUSS.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.OPENGAUSS;
    }

    /**
     * 获取添加字段的sql。
     */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        return OpengaussAddColumn.sql(this, targetSchemaName, targetTableName, targetColumn);
    }

    /**
     * 获取指定查询SQL的元信息。
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        List<ColumnDescription> columns = super.querySelectSqlColumnMeta(connection, sql);
        for (ColumnDescription column : columns) {
            if (column.getFieldType() == java.sql.Types.TIME) {
                column.setValType(ColumnMetaData.TYPE_TIME);
                column.setLength(column.getScaleSize());
                column.setPrecision(0);
            }
        }
        return columns;
    }

    /**
     * 获取字段列的结构定义。
     */
    @Override
    public String getFieldDefinition(ColumnMetaData column, List<String> pks, boolean autoIncrement,
            boolean addCr, boolean withRemarks) {
        if (column.getType() != ColumnMetaData.TYPE_TIMESTAMP && column.getType() != ColumnMetaData.TYPE_TIME) {
            return super.getFieldDefinition(column, pks, autoIncrement, addCr, withRemarks);
        }
        if (column.getSqlType() == java.sql.Types.TIMESTAMP_WITH_TIMEZONE
                || column.getSqlType() == java.sql.Types.TIME_WITH_TIMEZONE) {
            throw new UnsupportedOperationException("openGauss timezone columns require a native definition");
        }
        if (column.getLength() < 0 || column.getLength() > 6) {
            throw new IllegalArgumentException("openGauss temporal precision must be between 0 and 6");
        }
        return " " + PostgreSqlUtils.quoteIdentifier(column.getName()) + " "
                + (column.getType() == ColumnMetaData.TYPE_TIMESTAMP ? "TIMESTAMP" : "TIME")
                + "(" + column.getLength() + ")"
                + (column.getDefaultValue() == null ? "" : " DEFAULT " + renderDefault(column))
                + (column.isNullable() ? "" : " NOT NULL")
                + (addCr ? cn.iocoder.yudao.framework.dbswitch.common.constant.Const.CR : "");
    }

    /**
     * 拒绝当前实现未支持的 {@code renderDefault} 操作。
     */
    @Override
    protected String renderDefault(ColumnMetaData column) {
        String value = column.getDefaultValue();
        // 目录字面值保留其类型转换和精确值，包含依赖的表达式需要原生计划。
        String scalar = "(?:[+-]?[0-9]+(?:\\.[0-9]*)?(?:[eE][+-]?[0-9]+)?|'(?:[^'\\\\]|'')*'|true|false|null)";
        String cast = "(?:::(?:numeric|decimal|smallint|integer|bigint|real|double precision|boolean|text|"
                + "character(?: varying)?|(?:timestamp|time)(?: (?:with|without) time zone)?|date)(?:\\([0-9]+(?:,[0-9]+)?\\))?)?";
        if (value == null || value.length() > 65_536 || !value.matches("(?is)" + scalar + cast)) {
            throw new UnsupportedOperationException("openGauss default expressions require a native dependency plan");
        }
        return value;
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.OPENGAUSS) {
            return super.queryRelationFingerprint(connection, reference);
        }
        return OpengaussRelationIdentity.fingerprint(connection, reference);
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String alias, String column) {
        if (getDatabaseType() != ProductTypeEnum.OPENGAUSS) {
            return super.integerKeyProjection(alias, column);
        }
        return integerProjection(alias, column, 21);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.OPENGAUSS) {
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
        String field = (alias == null ? "" : RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OPENGAUSS,
                alias) + ".")
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OPENGAUSS, column);
        String text = "CAST(" + field + " AS pg_catalog.text)";
        return new IntegerProjection("CASE WHEN pg_catalog.octet_length(" + text + ") <= " + maxBytes
                + " THEN " + text + " ELSE NULL END", "NULLIF(" + field + ", " + field + ")");
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
         * 确定当前列或值对应的 JDBC 类型。
         */
        @Override
        public int jdbcType(ResultSetMetaData metadata, int column) throws SQLException {
            return PgCatalogKeyProjections.signedIntegerType(metadata, column);
        }
    }

    /**
     * 精确文本键投影；alias 可为 null（未限定列），其余输入均须为物理标识符。
     */
    @Override
    public BoundedTextKeyProjection textKeyProjection(String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.OPENGAUSS) {
            return super.textKeyProjection(alias, column, maxBytes);
        }
        return PgCatalogKeyProjections.text(ProductTypeEnum.OPENGAUSS, alias, column, maxBytes);
    }

    /**
     * 生成文本或整数键分量的查询投影。
     */
    @Override
    public BoundedKeyComponentProjection textAndIntegerKeyComponentProjection(Connection connection,
            String alias, String column, int maxBytes) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.OPENGAUSS) {
            return super.textAndIntegerKeyComponentProjection(connection, alias, column, maxBytes);
        }
        return PgCatalogKeyProjections.mixed(ProductTypeEnum.OPENGAUSS, alias, column, maxBytes);
    }

    /**
     * 获取数据库的模式schema列表。
     */
    @Override
    public List<String> querySchemaList(Connection connection) {
        List<String> schemas = super.querySchemaList(connection);
        return schemas.stream()
                .filter(s -> !SYSTEM_SCHEMAS.contains(s))
                .collect(Collectors.toList());
    }
}
