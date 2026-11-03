package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.HighgoRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.product.highgo.HighgoAddColumn;
import lombok.SneakyThrows;

import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * 瀚高元信息。关系身份和有界整数键只证明本构建的永久 heap，不调用 PostgreSQL 指纹。
 * 可空加列只生成带引号的 ALTER TABLE ... ADD COLUMN，不查询目录。
 */
public class DatabaseHighgoImpl extends DatabasePostgresImpl implements IDatabaseInterface {

    /** 瀚高 6.0.4.2 的独立目录前置检查，不代表可建立复制连接或已有空闲复制槽。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection,int timeoutSeconds) throws SQLException {
        if (getDatabaseType()!=ProductTypeEnum.HIGHGO) return super.probeCdcReadiness(connection,timeoutSeconds);
        diagnosticBounds(connection,"CDC",1,timeoutSeconds);
        String sql="SELECT pg_catalog.version(),pg_catalog.current_setting('server_version'),pg_catalog.current_setting('wal_level'),"
                + "pg_catalog.current_setting('max_replication_slots')::int,pg_catalog.current_setting('max_wal_senders')::int,rolsuper,rolreplication "
                + "FROM pg_catalog.pg_roles WHERE rolname=current_user";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("HighGo CDC 配置及角色目录无响应");
                String version=rows.getString(1); String server=rows.getString(2);
                if (version==null || !version.matches("HighGo Database 6 Release 6[.]0[.]4[.]2(?:[- ].*)?") || !"12.7".equals(server))
                    throw new java.sql.SQLFeatureNotSupportedException("HighGo CDC 仅声明 6.0.4.2 原生目录契约","0A000");
                String wal=rows.getString(3); int slots=rows.getInt(4); boolean missing=rows.wasNull(); int senders=rows.getInt(5); missing|=rows.wasNull();
                boolean administrator=rows.getBoolean(6); missing|=rows.wasNull(); boolean replication=rows.getBoolean(7);
                if (missing || rows.wasNull() || wal==null || slots<0 || senders<0 || rows.next()) throw new SQLException("HighGo CDC 配置及角色证据不完整");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic("logical".equals(wal)&&slots>0&&senders>0,administrator||replication);
            }
        }
    }

    /** 瀚高独立声明已使用的原生注释目录，不透传 PostgreSQL 的 CDC 或临时写计划。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        if (getDatabaseType()!=ProductTypeEnum.HIGHGO) return super.probeExtendedProperties(connection,namespace,table,timeoutSeconds);
        diagnosticBounds(connection,namespace,1,timeoutSeconds); diagnosticBounds(connection,table,1,timeoutSeconds);
        String sql="SELECT CASE WHEN pg_catalog.obj_description(c.oid,'pg_class') IS NULL THEN 0 ELSE 1 END,"
                + "COUNT(a.attnum),COUNT(pg_catalog.col_description(c.oid,a.attnum)) "
                + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped "
                + "WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('r','p') GROUP BY c.oid";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setString(1,namespace); statement.setString(2,table); statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("HighGo 目标表不存在或不可见","42S02");
                int tableComments=rows.getInt(1); int columns=rows.getInt(2); int comments=rows.getInt(3);
                if (rows.wasNull() || columns<1 || comments<0 || comments>columns || tableComments<0 || tableComments>1 || rows.next()) throw new SQLException("HighGo 注释目录证据不完整或不唯一");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(columns,tableComments==1,comments);
            }
        }
    }


    /** HighGo 显式声明原生 schema USAGE 检查，不自动复用 PostgreSQL 诊断实现。 */
    @Override
    public void probeNamespace(java.sql.Connection connection, String namespace, int timeoutSeconds) throws java.sql.SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT pg_catalog.has_schema_privilege(current_user,n.oid,'USAGE') FROM pg_catalog.pg_namespace n WHERE n.nspname=?")) {
            statement.setString(1, namespace); statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new java.sql.SQLException("HighGo 命名空间不存在", "3F000");
                if (!rows.getBoolean(1) || rows.wasNull()) throw new java.sql.SQLException("HighGo schema USAGE 被拒绝", "42501");
            }
        }
    }

    /** 读取当前原生角色目录，不推断任意对象的写权限。 */
    @Override
    public void probeAccountPrivileges(java.sql.Connection connection, String namespace, int timeoutSeconds) throws java.sql.SQLException {
        probeNamespace(connection, namespace, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT rolname,rolsuper FROM pg_catalog.pg_roles WHERE rolname=current_user")) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) throw new java.sql.SQLException("HighGo 当前账号目录证据缺失");
            }
        }
    }

    /** HighGo 独立原生目录，只包含基本表，不含可执行用户函数的视图。 */
    @Override
    public java.util.List<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription> queryCatalogTableList(java.sql.Connection connection, String namespace, int maxTables, int timeoutSeconds) throws java.sql.SQLException {
        diagnosticBounds(connection, namespace, maxTables, timeoutSeconds);
        var result = new java.util.ArrayList<cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription>();
        try (var statement = connection.prepareStatement("SELECT c.relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relkind IN ('r','p') ORDER BY c.relname LIMIT ?")) {
            statement.setString(1, namespace); statement.setInt(2, maxTables + 1); statement.setMaxRows(maxTables + 1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == maxTables) throw new java.sql.SQLException("HighGo 目录超过上限，拒绝截断", "54000");
                    var table = new cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription();
                    table.setSchemaName(namespace); table.setTableName(rows.getString(1)); table.setTableType("TABLE"); result.add(table);
                }
            }
        }
        return java.util.List.copyOf(result);
    }

    private static void diagnosticBounds(java.sql.Connection connection, String namespace, int maxTables, int timeoutSeconds) {
        if (connection == null || namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || maxTables < 1 || maxTables > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("HighGo 诊断参数必须完整且有界");
    }

    /**
     * 初始化 {@code DatabaseHighgoImpl}及其父类状态。
     */
    public DatabaseHighgoImpl() {
        super(ProductTypeEnum.HIGHGO.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.HIGHGO;
    }

    /**
     * 获取添加字段的sql。
     */
    @Override
    @SneakyThrows
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        if (getDatabaseType() != ProductTypeEnum.HIGHGO) {
            return super.getAddColumnSql(targetSchemaName, targetTableName, targetColumn);
        }
        return HighgoAddColumn.sql(this, targetSchemaName, targetTableName, targetColumn);
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.HIGHGO) {
            return super.queryRelationFingerprint(connection, reference);
        }
        return HighgoRelationIdentity.fingerprint(connection, reference);
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String alias, String column) {
        if (getDatabaseType() != ProductTypeEnum.HIGHGO) {
            return super.integerKeyProjection(alias, column);
        }
        return integerProjection(alias, column, 21);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.HIGHGO) {
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
        String field = (alias == null ? "" : RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.HIGHGO,
                alias) + ".")
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.HIGHGO, column);
        String text = "CAST(" + field + " AS pg_catalog.text)";
        return new IntegerProjection("CASE WHEN pg_catalog.octet_length(" + text + ") <= " + maxBytes
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
