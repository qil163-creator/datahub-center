package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.database.constant.SQLServerConst;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.DDLFormatterUtils;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver.SqlServerAddColumn;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 支持SQLServer数据库的元信息实现
 *
 * @author jrl
 */
public class DatabaseSqlserverImpl extends AbstractDatabase implements IDatabaseInterface {

    /** SQL Server 没有 schema USAGE，验证 schema SELECT 或至少一个基本表的有效 SELECT 权限。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.SQLSERVER) { super.probeNamespace(connection, namespace, timeoutSeconds); return; }
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        String sql = "SELECT CASE WHEN HAS_PERMS_BY_NAME(s.name,'SCHEMA','SELECT')=1 OR EXISTS (SELECT 1 FROM sys.tables t WHERE t.schema_id=s.schema_id AND HAS_PERMS_BY_NAME(QUOTENAME(s.name)+'.'+QUOTENAME(t.name),'OBJECT','SELECT')=1) THEN 1 ELSE 0 END FROM sys.schemas s WHERE s.name=?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace); statement.setMaxRows(2); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("SQL Server schema 不存在或不可见", "3F000");
                if (!rows.getBoolean(1) || rows.wasNull()) throw new SQLException("SQL Server schema 或基本表 SELECT 被拒绝", "42501");
            }
        }
    }

    /** 观测当前数据库用户及其显式授权目录，不把目录中的授权行等同于全部有效权限。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.SQLSERVER) { super.probeAccountPrivileges(connection, namespace, timeoutSeconds); return; }
        probeNamespace(connection, namespace, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT USER_NAME(),COUNT(*) FROM sys.database_permissions WHERE grantee_principal_id=USER_ID()")) {
            statement.setMaxRows(2); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) throw new SQLException("SQL Server 当前账号授权目录证据缺失");
            }
        }
    }

    /** 排除视图、系统表和外部对象；使用 TOP 与 JDBC maxRows 双重限制。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String namespace, int maxTables, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.SQLSERVER) return super.queryCatalogTableList(connection, namespace, maxTables, timeoutSeconds);
        diagnosticBounds(connection, namespace, maxTables, timeoutSeconds);
        var result = new java.util.ArrayList<TableDescription>();
        String sql = "SELECT TOP (" + (maxTables + 1) + ") t.name FROM sys.tables t JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=? AND t.is_ms_shipped=0 ORDER BY t.name";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace); statement.setMaxRows(maxTables + 1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == maxTables) throw new SQLException("SQL Server 目录超过上限，拒绝截断", "54000");
                    var table = new TableDescription(); table.setSchemaName(namespace); table.setTableName(rows.getString(1)); table.setTableType("TABLE"); result.add(table);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void diagnosticBounds(Connection connection, String namespace, int maxTables, int timeoutSeconds) {
        if (connection == null || namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || maxTables < 1 || maxTables > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("SQL Server 诊断参数必须完整且有界");
    }

    /** 通过对象和列 ID 精确读取 MS_Description；不解码 SQL_VARIANT 或返回正文。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        if (getDatabaseType()!=ProductTypeEnum.SQLSERVER) return super.probeExtendedProperties(connection,namespace,table,timeoutSeconds);
        diagnosticBounds(connection,namespace,1,timeoutSeconds); diagnosticBounds(connection,table,1,timeoutSeconds);
        String sql="SELECT MAX(CASE WHEN tp.value IS NULL THEN 0 ELSE 1 END),COUNT(c.column_id),"
                + "SUM(CASE WHEN cp.value IS NULL THEN 0 ELSE 1 END) "
                + "FROM sys.tables t JOIN sys.schemas s ON s.schema_id=t.schema_id "
                + "LEFT JOIN sys.columns c ON c.object_id=t.object_id "
                + "LEFT JOIN sys.extended_properties tp ON tp.class=1 AND tp.major_id=t.object_id AND tp.minor_id=0 AND tp.name=N'MS_Description' "
                + "LEFT JOIN sys.extended_properties cp ON cp.class=1 AND cp.major_id=t.object_id AND cp.minor_id=c.column_id AND cp.name=N'MS_Description' "
                + "WHERE s.name=? AND t.name=? AND t.is_ms_shipped=0 GROUP BY t.object_id";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setString(1,namespace); statement.setString(2,table); statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("SQL Server 目标表不存在或不可见","42S02");
                int tableComments=rows.getInt(1); int columns=rows.getInt(2); int comments=rows.getInt(3);
                if (rows.wasNull() || columns<1 || comments<0 || comments>columns || tableComments<0 || tableComments>1 || rows.next()) throw new SQLException("SQL Server 注释目录证据不完整或不唯一");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(columns,tableComments==1,comments);
            }
        }
    }

    /** 读取实际 CDC 开关、捕获实例和有效门控角色；不调用启用 CDC 的存储过程。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection, int timeoutSeconds) throws SQLException {
        if (getDatabaseType()!=ProductTypeEnum.SQLSERVER) return super.probeCdcReadiness(connection,timeoutSeconds);
        diagnosticBounds(connection,"CDC",1,timeoutSeconds);
        var metadata=connection.getMetaData();
        if (!"Microsoft SQL Server".equals(metadata.getDatabaseProductName()) || !Set.of(13,14,15,16).contains(metadata.getDatabaseMajorVersion()))
            throw new SQLFeatureNotSupportedException("SQL Server CDC 仅声明原生版本 13/14/15/16 的目录契约","0A000");
        boolean enabled; boolean administrator;
        try (var statement=connection.prepareStatement("SELECT is_cdc_enabled,IS_SRVROLEMEMBER('sysadmin'),IS_MEMBER('db_owner') FROM sys.databases WHERE database_id=DB_ID()")) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("SQL Server CDC 配置目录无响应");
                int flag=rows.getInt(1); boolean missing=rows.wasNull(); int sysadmin=rows.getInt(2); missing|=rows.wasNull(); int owner=rows.getInt(3);
                if (missing || rows.wasNull() || flag<0 || flag>1 || sysadmin<0 || sysadmin>1 || owner<0 || owner>1 || rows.next()) throw new SQLException("SQL Server CDC 配置或角色证据不完整");
                enabled=flag==1; administrator=sysadmin==1 || owner==1;
            }
        }
        if (!enabled) return new cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic(false,administrator);
        String sql="SELECT COUNT(*),COALESCE(SUM(CASE WHEN HAS_PERMS_BY_NAME(QUOTENAME(s.name)+'.'+QUOTENAME(t.name),'OBJECT','SELECT')=1 "
                + "AND (ct.role_name IS NULL OR IS_MEMBER(ct.role_name)=1) THEN 1 ELSE 0 END),0) "
                + "FROM cdc.change_tables ct JOIN sys.tables t ON t.object_id=ct.source_object_id "
                + "JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE t.is_tracked_by_cdc=1";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("SQL Server CDC 捕获目录无响应");
                int captures=rows.getInt(1); boolean missing=rows.wasNull(); int permitted=rows.getInt(2);
                if (missing || rows.wasNull() || captures<0 || permitted<0 || permitted>captures || rows.next()) throw new SQLException("SQL Server CDC 捕获权限证据不完整");
                // 未创建任何捕获实例时，不把数据库总开关当作已就绪的数据捕获链路。
                return new cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic(captures>0,
                        administrator || captures>0 && permitted==captures);
            }
        }
    }

    /** # 局部临时表的定义及数据均会话隔离；绝不创建普通表或 ## 全局临时表。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan temporaryTableDiagnosticPlan(
            String randomName) throws SQLException {
        if (getDatabaseType()!=ProductTypeEnum.SQLSERVER) return super.temporaryTableDiagnosticPlan(randomName);
        if (randomName==null || !randomName.matches("dbswitch_diag_[a-f0-9]{32}")) throw new IllegalArgumentException("组件生成的诊断临时表名不合法");
        String name="[#"+randomName+"]";
        return new cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan(
                "CREATE TABLE "+name+" (probe_id INTEGER PRIMARY KEY,probe_value VARCHAR(40))", name,
                "ALTER TABLE "+name+" ADD probe_extra INTEGER",
                "IF OBJECT_ID('tempdb..#"+randomName+"') IS NOT NULL DROP TABLE "+name,
                null,
                "MERGE INTO "+name+" AS target USING (VALUES (5,'merged'),(6,'inserted')) AS source(probe_id,probe_value) "
                        + "ON target.probe_id=source.probe_id WHEN MATCHED THEN UPDATE SET probe_value=source.probe_value "
                        + "WHEN NOT MATCHED THEN INSERT (probe_id,probe_value) VALUES(source.probe_id,source.probe_value);",
                10,"TRUNCATE TABLE "+name,null,null,null);
    }

    private static final String SHOW_CREATE_VIEW_SQL =
            "SELECT VIEW_DEFINITION from INFORMATION_SCHEMA.VIEWS where TABLE_SCHEMA ='%s' and TABLE_NAME ='%s'";

    private static Set<String> excludesSchemaNames;

    static {
        excludesSchemaNames = new HashSet<>();
        excludesSchemaNames.add("db_denydatawriter");
        excludesSchemaNames.add("db_datawriter");
        excludesSchemaNames.add("db_accessadmin");
        excludesSchemaNames.add("db_ddladmin");
        excludesSchemaNames.add("db_securityadmin");
        excludesSchemaNames.add("db_denydatareader");
        excludesSchemaNames.add("db_backupoperator");
        excludesSchemaNames.add("db_datareader");
        excludesSchemaNames.add("db_owner");
    }

    /**
     * 初始化 {@code DatabaseSqlserverImpl}及其父类状态。
     */
    public DatabaseSqlserverImpl() {
        super(ProductTypeEnum.SQLSERVER.getDriveClassName());
    }

    /**
     * 初始化 {@code DatabaseSqlserverImpl}及其父类状态。
     */
    public DatabaseSqlserverImpl(String driverName) {
        super(driverName);
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.SQLSERVER;
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection integerKeyProjection(String alias,
            String column) {
        if (getDatabaseType() != ProductTypeEnum.SQLSERVER) {
            return super.integerKeyProjection(alias, column);
        }
        return integerProjection(alias, column, 21, false);
    }

    /**
     * 多兼容模式产品在实际连接上验证方言；默认委托旧 SPI，保留已有产品/扩展的行为。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection integerKeyProjection(
            Connection connection, String alias, String column) throws SQLException {
        verifyKeyConnection(connection);
        return integerKeyProjection(alias, column);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection keyComponentProjection(
            String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.SQLSERVER) {
            return super.keyComponentProjection(alias, column, maxBytes);
        }
        if (maxBytes < 1 || maxBytes > 256) {
            throw new IllegalArgumentException("Invalid SQL Server integer byte budget");
        }
        return integerProjection(alias, column, Math.min(maxBytes, 21), true);
    }

    /**
     * 连接感知的复合键投影；不得为了检测模式改变连接状态。
     */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection keyComponentProjection(
            Connection connection, String alias, String column, int maxBytes) throws SQLException {
        verifyKeyConnection(connection);
        return keyComponentProjection(alias, column, maxBytes);
    }

    /**
     * 校验键连接。
     */
    private void verifyKeyConnection(Connection connection) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.SQLSERVER || connection == null
                || !"Microsoft SQL Server".equals(connection.getMetaData().getDatabaseProductName())
                || connection.getMetaData().getDatabaseMajorVersion() != 16) {
            throw new java.sql.SQLFeatureNotSupportedException("SQL Server key projection requires verified version 16",
                    "0A000");
        }
    }

    /**
     * 生成整数值的查询投影。
     */
    private static cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection integerProjection(
            String alias, String column, int maxBytes, boolean binary) {
        if (column == null || !column.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                || alias != null && !alias.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Ordinary SQL Server key identifiers required");
        }
        String field = (alias == null ? "" : quoteKey(alias) + ".") + quoteKey(column);
        String text = "CONVERT(varchar(32), " + field + ")";
        String bounded = "CASE WHEN DATALENGTH(" + text + ") <= " + maxBytes + " THEN " + text + " ELSE NULL END";
        return new IntegerProjection(binary ? "CONVERT(varbinary(21), " + bounded + ")" : bounded,
                "NULLIF(" + field + ", " + field + ")", bounded);
    }

    private record IntegerProjection(String valueSql, String typeSql, String groupSql)
            implements cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection {

        /**
         * 说明当前查询路径是否要求显式添加外层数量限制。
         */
        @Override
        public boolean requiresExplicitOuterLimit() {
            return true;
        }

        /**
         * 确定当前列或值对应的 JDBC 类型。
         */
        @Override
        public int jdbcType(java.sql.ResultSetMetaData metadata, int column) throws SQLException {
            String type = metadata.getColumnTypeName(column);
            int jdbc = metadata.getColumnType(column);
            if (type == null || !metadata.isSigned(column)) {
                return 0;
            }
            return switch (type.toLowerCase(java.util.Locale.ROOT)) {
                case "smallint" -> jdbc == java.sql.Types.SMALLINT ? jdbc : 0;
                case "int" -> jdbc == java.sql.Types.INTEGER ? jdbc : 0;
                case "bigint" -> jdbc == java.sql.Types.BIGINT ? jdbc : 0;
                default -> 0;
            };
        }
    }

    /**
     * 单个物理列转为保留 NULL 的文本表达式；不接受任意 SQL，不代表可作为无损身份编码。
     */
    @Override
    public String textColumnExpression(String column) {
        if (getDatabaseType() != ProductTypeEnum.SQLSERVER) {
            return super.textColumnExpression(column);
        }
        return "CONVERT(nvarchar(max), " + quoteKey(column) + ")";
    }

    /**
     * 引用键。
     */
    private static String quoteKey(String column) {
        return cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.quotePhysicalIdentifier(
                ProductTypeEnum.SQLSERVER, column);
    }

    /**
     * 返回当前连接报告的数据库主版本。
     */
    private int getDatabaseMajorVersion(Connection connection) {
        try {
            return connection.getMetaData().getDatabaseMajorVersion();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取数据库的模式schema列表。
     */
    @Override
    public List<String> querySchemaList(Connection connection) {
        Set<String> ret = new HashSet<>();
        try (ResultSet schemas = connection.getMetaData().getSchemas();) {
            while (schemas.next()) {
                String name = schemas.getString("TABLE_SCHEM");
                if (!excludesSchemaNames.contains(name)) {
                    ret.add(name);
                }
            }
            return new ArrayList<>(ret);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取指定模式Schema内的所有表列表。
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        int majorVersion = getDatabaseMajorVersion(connection);
        if (majorVersion <= 8) {
            return super.queryTableList(connection, schemaName);
        }

        List<TableDescription> ret = new ArrayList<>();
        String sql = "SELECT s.name TABLE_SCHEMA,o.name TABLE_NAME,CASE o.type WHEN 'V' THEN 'VIEW' ELSE 'BASE TABLE' END TABLE_TYPE,"
                + "CONVERT(nvarchar(max),g.value) COMMENTS FROM sys.objects o JOIN sys.schemas s ON s.schema_id=o.schema_id "
                + "LEFT JOIN sys.extended_properties g ON g.class=1 AND g.major_id=o.object_id AND g.minor_id=0 AND g.name=N'MS_Description' "
                + "WHERE s.name=? AND o.type IN ('U','V') AND o.is_ms_shipped=0 ORDER BY o.name";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            ps.setQueryTimeout(30);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableDescription td = new TableDescription();
                    td.setSchemaName(rs.getString("TABLE_SCHEMA"));
                    td.setTableName(rs.getString("TABLE_NAME"));
                    td.setRemarks(rs.getString("COMMENTS"));
                    String tableType = rs.getString("TABLE_TYPE").trim();
                    if (tableType.equalsIgnoreCase("VIEW")) {
                        td.setTableType("VIEW");
                    } else {
                        td.setTableType("TABLE");
                    }

                    ret.add(td);
                }

                return ret;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取查询字段默认值/注释的 SQL（数据库方言相关）。
     */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        return "select columns.name AS column_name, columns.column_id AS COLPOSITION, columns.max_length AS DATALENGTH, columns.precision AS DATAPRECISION, columns.scale AS DATASCALE, " +
                "columns.is_nullable AS NULLABLE, types.name AS DATATYPE, CAST(ep.value  AS NVARCHAR(128)) AS column_comment, e.text AS column_default, " +
                "(select top 1 ind.is_primary_key from sys.index_columns ic left join sys.indexes ind on ic.object_id = ind.object_id and ic.index_id = ind.index_id and ind.name like 'PK_%' where ic.object_id=columns.object_id and ic.column_id=columns.column_id) AS COLKEY " +
                "from sys.columns columns LEFT JOIN sys.types types ON columns.system_type_id = types.system_type_id " +
                "LEFT JOIN syscomments e ON columns.default_object_id= e.id " +
                "LEFT JOIN sys.extended_properties ep ON ep.major_id = columns.object_id AND ep.minor_id = columns.column_id AND ep.name = 'MS_Description' " +
                "where columns.object_id = object_id('" + tableName + "') order by columns.column_id ";
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        String sql = String.format(SQLServerConst.CREATE_TABLE_SQL_TPL, schemaName, tableName);
        try (Statement st = connection.createStatement()) {
            if (st.execute(sql)) {
                try (ResultSet rs = st.getResultSet()) {
                    if (rs != null && rs.next()) {
                        return DDLFormatterUtils.format(rs.getString(1));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return null;
    }

    /**
     * 获取指定视图表的DDL语句。
     */
    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        String sql = String.format(SHOW_CREATE_VIEW_SQL, schemaName, tableName);
        try (Statement st = connection.createStatement()) {
            if (st.execute(sql)) {
                try (ResultSet rs = st.getResultSet()) {
                    if (rs != null && rs.next()) {
                        return rs.getString(1);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return null;
    }

    /**
     * 获取指定模式表的元信息。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName,
            String tableName) {
        int majorVersion = getDatabaseMajorVersion(connection);
        if (majorVersion <= 8) {
            return super.queryTableColumnMeta(connection, schemaName, tableName);
        }

        List<ColumnDescription> ret = this.getSelectSqlColumnMeta(connection,
                "SELECT TOP (0) * FROM " + getQuotedSchemaTableCombination(schemaName, tableName), 30);
        enrichColumnProperties(connection, schemaName, tableName, ret);
        return ret;
    }

    /**
     * 为字段描述列表补充默认值与注释信息。
     */
    @Override
    public void setColumnDefaultValue(Connection connection, String schema, String table,
            List<ColumnDescription> columns) {
        if (getDatabaseType() != ProductTypeEnum.SQLSERVER || getDatabaseMajorVersion(connection) <= 8) {
            super.setColumnDefaultValue(connection, schema, table, columns);
            return;
        }
        enrichColumnProperties(connection, schema, table, columns);
    }

    /**
     * 使用当前产品目录事实补充列属性。
     */
    private void enrichColumnProperties(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> ret) {
        String querySql = "SELECT c.name COLUMN_NAME,CONVERT(nvarchar(max),g.value) REMARKS,d.definition COLUMN_DEFAULT "
                + "FROM sys.columns c JOIN sys.objects o ON o.object_id=c.object_id JOIN sys.schemas s ON s.schema_id=o.schema_id "
                + "LEFT JOIN sys.extended_properties g ON g.class=1 AND g.major_id=c.object_id AND g.minor_id=c.column_id AND g.name=N'MS_Description' "
                + "LEFT JOIN sys.default_constraints d ON d.object_id=c.default_object_id WHERE s.name=? AND o.name=? ORDER BY c.column_id";
        try (PreparedStatement ps = connection.prepareStatement(querySql)) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            ps.setQueryTimeout(30);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    for (ColumnDescription cd : ret) {
                        if (columnName.equals(cd.getFieldName())) {
                            cd.setRemarks(rs.getString("REMARKS"));
                            cd.setDefaultValue(rs.getString("COLUMN_DEFAULT"));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
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
     * 获取指定模式表的主键字段列表。
     */
    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schema, String table) {
        if (getDatabaseType() != ProductTypeEnum.SQLSERVER || getDatabaseMajorVersion(connection) <= 8) {
            return super.queryTablePrimaryKeys(connection, schema, table);
        }
        String query = "SELECT c.name FROM sys.indexes i JOIN sys.index_columns ic ON ic.object_id=i.object_id AND ic.index_id=i.index_id "
                + "JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id "
                + "JOIN sys.objects o ON o.object_id=i.object_id JOIN sys.schemas s ON s.schema_id=o.schema_id "
                + "WHERE i.is_primary_key=1 AND s.name=? AND o.name=? AND ic.key_ordinal>0 ORDER BY ic.key_ordinal";
        try (var statement = connection.prepareStatement(query)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setQueryTimeout(30);
            try (var rows = statement.executeQuery()) {
                List<String> keys = new ArrayList<>();
                while (rows.next()) {
                    keys.add(rows.getString(1));
                }
                return keys;
            }
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
    }

    /**
     * 获取指定查询SQL的元信息。
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        String querySQL = String.format("SELECT TOP 1 * from (%s) tmp ", sql.replace(";", ""));
        return this.getSelectSqlColumnMeta(connection, querySQL);
    }

    /**
     * 生成用于探测表字段的查询 SQL。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        // 保留旧版 SQLSERVER2000 查询语法；现代 SQL Server 表元数据直接使用 TOP (0)。
        return "SELECT TOP 1 * FROM " + getQuotedSchemaTableCombination(schemaName, tableName);
    }

    /**
     * 把用户 SQL 包装为低成本的合法性探测语句（如 explain、外层包 WHERE 1=2 等），由子类按方言实现。
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        return String.format("SELECT top 1 * from ( %s ) tmp", sql.replace(";", ""));
    }

    /**
     * 获取数据库的表全名。
     */
    @Override
    public String getQuotedSchemaTableCombination(String schemaName, String tableName) {
        return quoteKey(schemaName) + "." + quoteKey(tableName);
    }

    /**
     * 获取字段列的结构定义。
     */
    @Override
    public String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc,
            boolean addCr, boolean withRemarks) {
        String fieldname = v.getName();
        int length = v.getLength();
        int precision = v.getPrecision();
        int type = v.getType();

        String retval = " " + quoteKey(fieldname) + "  ";
        boolean canHaveDefaultValue = true;
        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
                // 现代 SQL Server DATETIME 舍入为 1/300 秒；DATETIME2 保留
                // 全部七位受支持小数，SQL Server 2000 使用独立的旧版路径。
                retval += getDatabaseType() == ProductTypeEnum.SQLSERVER ? "DATETIME2(7)" : "DATETIME";
                break;
            case ColumnMetaData.TYPE_TIME:
                retval += "TIME";
                break;
            case ColumnMetaData.TYPE_DATE:
                retval += "DATE";
                break;
            case ColumnMetaData.TYPE_BOOLEAN:
                retval += "BIT";
                break;
            case ColumnMetaData.TYPE_NUMBER:
            case ColumnMetaData.TYPE_INTEGER:
            case ColumnMetaData.TYPE_BIGNUMBER:
                if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                    if (useAutoInc) {
                        retval += "BIGINT IDENTITY(0,1)";
                    } else {
                        retval += "BIGINT";
                    }
                } else {
                    if (precision == 0) {
                        if (length > 18) {
                            retval += "DECIMAL(" + length + ",0)";
                        } else {
                            if (length > 9) {
                                retval += "BIGINT";
                            } else {
                                retval += "INT";
                            }
                        }
                    } else {
                        if (precision > 0 && length > 0) {
                            retval += "DECIMAL(" + length + "," + precision + ")";
                        } else {
                            retval += "FLOAT(53)";
                        }
                    }
                }
                break;
            case ColumnMetaData.TYPE_STRING:
                if (length > 0 && length <= 4000) {
                    retval += "NVARCHAR(" + length + ")";
                } else if (length > 4000) {
                    retval += "NVARCHAR(MAX)";
                    canHaveDefaultValue = false;
                } else {
                    retval += "NVARCHAR(100)";
                }
                break;
            case ColumnMetaData.TYPE_BINARY:
                retval += "VARBINARY(MAX)";
                canHaveDefaultValue = false;
                break;
            default:
                retval += "TEXT";
                canHaveDefaultValue = false;
                break;
        }

        if (canHaveDefaultValue && v.getDefaultValue() != null && !"null".equals(v.getDefaultValue()) && !"NULL".equals(
                v.getDefaultValue())) {
            if (type != ColumnMetaData.TYPE_TIMESTAMP && type != ColumnMetaData.TYPE_TIME && type != ColumnMetaData.TYPE_DATE) {
                if (v.getDefaultValue().startsWith("'")) {
                    retval += " DEFAULT " + v.getDefaultValue();
                } else {
                    retval += " DEFAULT '" + v.getDefaultValue() + "'";
                }
            } else {
                retval += " DEFAULT DEFAULT (getdate())";
            }
        }

        if (!v.isNullable()) {
            retval += " NOT NULL";
        }

        if (addCr) {
            retval += Const.CR;
        }

        return retval;
    }

    /**
     * 主键列转换为逗号分隔的字符串。
     */
    @Override
    public String getPrimaryKeyAsString(List<String> pks) {
        if (null != pks && !pks.isEmpty()) {
            return pks.stream().map(DatabaseSqlserverImpl::quoteKey).collect(java.util.stream.Collectors.joining(", "));
        }

        return "";
    }

    /**
     * 获取表和字段的注释定义。
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td,
            List<ColumnDescription> cds) {
        List<String> results = new ArrayList<>();
        if (StringUtils.isNotBlank(td.getRemarks())) {
            results.add(String
                    .format(
                            "EXEC [sys].sp_addextendedproperty 'MS_Description', N'%s', 'schema', N'%s', 'table', N'%s' ",
                            td.getRemarks().replace("'", "''"), td.getSchemaName().replace("'", "''"),
                            td.getTableName().replace("'", "''")));
        }

        for (ColumnDescription cd : cds) {
            if (StringUtils.isNotBlank(cd.getRemarks())) {
                results.add(String
                        .format(
                                "EXEC [sys].sp_addextendedproperty 'MS_Description', N'%s', 'schema', N'%s', 'table', N'%s', 'column', N'%s' ",
                                cd.getRemarks().replace("'", "''"), td.getSchemaName().replace("'", "''"),
                                td.getTableName().replace("'", "''"),
                                cd.getFieldName().replace("'", "''")));
            }
        }

        return results;
    }

    /**
     * 获取字段类型。
     */
    @Override
    public boolean canCreateIndex(ColumnMetaData v) {
        int length = v.getLength();
        int type = v.getType();
        boolean canCreateIndex = true;
        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
            case ColumnMetaData.TYPE_TIME:
            case ColumnMetaData.TYPE_DATE:
            case ColumnMetaData.TYPE_BOOLEAN:
            case ColumnMetaData.TYPE_NUMBER:
            case ColumnMetaData.TYPE_INTEGER:
            case ColumnMetaData.TYPE_BIGNUMBER:
                break;
            case ColumnMetaData.TYPE_STRING:
                if (length >= 4000) {
                    canCreateIndex = false;
                }
                break;
            case ColumnMetaData.TYPE_BINARY:
                canCreateIndex = false;
                break;
            default:
                canCreateIndex = false;
                break;
        }

        return canCreateIndex;
    }

    /**
     * SQL Server 2022：可空、无默认值的单列，{@code ALTER TABLE [schema].[table] ADD [column] type}。
     * 不是 {@code ADD COLUMN}。SQL Server 2000 还没有实库加列，不能把未引用的库名和表名拼进语句。
     */
    @Override
    @SneakyThrows
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        if (getDatabaseType() != ProductTypeEnum.SQLSERVER) {
            throw new SQLFeatureNotSupportedException("SQL Server 2000 nullable add is unverified", "0A000");
        }
        return SqlServerAddColumn.sql(targetSchemaName, targetTableName, targetColumn,
                meta -> getFieldDefinition(meta, null, false, false, true));
    }

    /**
     * 生成“按指定字段分组后出现次数大于 1”的查询 SQL（用于检测按这些字段判重时的重复数据）。
     */
    @Override
    public String getCountMoreThanOneSql(String schemaName, String tableName, List<String> columns) {
        String columnStr = "[" + String.join("],[", columns) + "]";
        return String.format("SELECT %s FROM [%s].[%s] GROUP BY %s HAVING count(*)>1", columnStr, schemaName, tableName,
                columnStr);
    }

    /**
     * 生成判断分组数量为一的查询 SQL。
     */
    @Override
    public String getCountOneSql(String schemaName, String tableName, List<String> columns) {
        String columnStr = "[" + String.join("],[", columns) + "]";
        return String.format("SELECT %s FROM [%s].[%s] GROUP BY %s HAVING count(*)=1", columnStr, schemaName, tableName,
                columnStr);
    }

}
