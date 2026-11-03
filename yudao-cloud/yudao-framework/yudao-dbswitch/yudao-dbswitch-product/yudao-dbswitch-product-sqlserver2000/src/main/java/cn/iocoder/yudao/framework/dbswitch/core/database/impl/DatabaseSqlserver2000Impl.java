package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver2000.Sqlserver2000AddColumn;
import lombok.SneakyThrows;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 支持SQLServer2000数据库的元信息实现
 *
 * @author jrl
 */
public class DatabaseSqlserver2000Impl extends DatabaseSqlserverImpl implements IDatabaseInterface {

    /** 旧版本仅开放自身的 # 会话临时表语法，不继承现代 MERGE 或重命名计划。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan temporaryTableDiagnosticPlan(
            String randomName) throws SQLException {
        if (randomName==null || !randomName.matches("dbswitch_diag_[a-f0-9]{32}")) throw new IllegalArgumentException("组件生成的诊断临时表名不合法");
        String name="[#"+randomName+"]";
        return new cn.iocoder.yudao.framework.dbswitch.core.model.TemporaryTableDiagnosticPlan(
                "CREATE TABLE "+name+" (probe_id INTEGER PRIMARY KEY,probe_value VARCHAR(40))",name,
                "ALTER TABLE "+name+" ADD probe_extra INTEGER",
                "IF OBJECT_ID('tempdb..#"+randomName+"') IS NOT NULL DROP TABLE "+name,
                null,null,Integer.MAX_VALUE,"TRUNCATE TABLE "+name,null,null,null);
    }

    /** SQL Server 2000 没有 sys.extended_properties，按所有者和 sysproperties.smallid 读取。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        if (connection==null || namespace==null || namespace.isBlank() || table==null || table.isBlank()
                || namespace.indexOf('\0')>=0 || table.indexOf('\0')>=0 || timeoutSeconds<1 || timeoutSeconds>60)
            throw new IllegalArgumentException("SQL Server 2000 诊断参数必须完整且有界");
        String sql="SELECT MAX(CASE WHEN tp.value IS NULL THEN 0 ELSE 1 END),COUNT(c.colid),"
                + "SUM(CASE WHEN cp.value IS NULL THEN 0 ELSE 1 END) FROM sysobjects t JOIN sysusers u ON u.uid=t.uid "
                + "LEFT JOIN syscolumns c ON c.id=t.id "
                + "LEFT JOIN sysproperties tp ON tp.id=t.id AND tp.smallid=0 AND tp.name=N'MS_Description' "
                + "LEFT JOIN sysproperties cp ON cp.id=t.id AND cp.smallid=c.colid AND cp.name=N'MS_Description' "
                + "WHERE u.name=? AND t.name=? AND t.xtype='U' GROUP BY t.id";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setString(1,namespace); statement.setString(2,table); statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("SQL Server 2000 目标表不存在或不可见","42S02");
                int tableComments=rows.getInt(1); int columns=rows.getInt(2); int comments=rows.getInt(3);
                if (rows.wasNull() || columns<1 || comments<0 || comments>columns || tableComments<0 || tableComments>1 || rows.next()) throw new SQLException("SQL Server 2000 注释目录证据不完整或不唯一");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(columns,tableComments==1,comments);
            }
        }
    }


    /** SQL Server 2000 的 schema 是对象所有者；使用旧版 PERMISSIONS 位检查，不调用 HAS_PERMS_BY_NAME。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        String sql = "SELECT CASE WHEN u.uid=USER_ID() OR IS_MEMBER('db_owner')=1 OR EXISTS (SELECT 1 FROM sysobjects o WHERE o.uid=u.uid AND o.xtype='U' AND (PERMISSIONS(o.id)&1)=1) THEN 1 ELSE 0 END FROM sysusers u WHERE u.name=?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace); statement.setMaxRows(2); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("SQL Server 2000 对象所有者不存在", "3F000");
                if (!rows.getBoolean(1) || rows.wasNull()) throw new SQLException("SQL Server 2000 所有者对象范围不可访问", "42501");
            }
        }
    }

    /** 原生旧版数据库用户和授权目录，只作观测，不冒充完整角色递归授权计算。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        probeNamespace(connection, namespace, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT USER_NAME(),COUNT(*) FROM sysprotects WHERE uid IN (USER_ID(),0)")) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) throw new SQLException("SQL Server 2000 账号授权目录证据缺失");
            }
        }
    }

    /** 旧版 sysobjects/sysusers 目录；TOP 使用经过边界校验的整数，不依赖新版本参数语法。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String namespace, int maxTables, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, maxTables, timeoutSeconds);
        var result = new ArrayList<TableDescription>();
        String sql = "SELECT TOP " + (maxTables + 1) + " o.name FROM sysobjects o JOIN sysusers u ON u.uid=o.uid WHERE u.name=? AND o.xtype='U' AND OBJECTPROPERTY(o.id,'IsMSShipped')=0 ORDER BY o.name";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace); statement.setMaxRows(maxTables + 1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == maxTables) throw new SQLException("SQL Server 2000 目录超过上限，拒绝截断", "54000");
                    var table = new TableDescription(); table.setSchemaName(namespace); table.setTableName(rows.getString(1)); table.setTableType("TABLE"); result.add(table);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void diagnosticBounds(Connection connection, String namespace, int maxTables, int timeoutSeconds) {
        if (connection == null || namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || maxTables < 1 || maxTables > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("SQL Server 2000 诊断参数必须完整且有界");
    }

    /**
     * 初始化 {@code DatabaseSqlserver2000Impl}及其父类状态。
     */
    public DatabaseSqlserver2000Impl() {
        super(ProductTypeEnum.SQLSERVER2000.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.SQLSERVER2000;
    }

    /** 有界的旧版类型子集；显式 NULL 不依赖 ANSI_NULL_DFLT 会话设置。 */
    @Override
    @SneakyThrows
    public String getAddColumnSql(String schema, String table, ColumnDescription column) {
        if (getDatabaseType() != ProductTypeEnum.SQLSERVER2000) {
            throw unsupported("SQL Server 2000 add column cannot be inherited by another product");
        }
        return Sqlserver2000AddColumn.sql(schema, table, column,
                meta -> getFieldDefinition(meta, null, false, false, true));
    }

    /**
     * 获取指定模式Schema内的所有表列表。
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        List<TableDescription> ret = new ArrayList<>();
        Set<String> uniqueSet = new HashSet<>();
        String[] types = new String[]{"TABLE", "VIEW"};
        try (ResultSet tables = connection.getMetaData()
                .getTables(this.catalogName, schemaName, "%", types);) {
            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                if (uniqueSet.contains(tableName)) {
                    continue;
                } else {
                    uniqueSet.add(tableName);
                }

                TableDescription td = new TableDescription();
                td.setSchemaName(schemaName);
                td.setTableName(tableName);
                td.setRemarks(tables.getString("REMARKS"));
                if (tables.getString("TABLE_TYPE").equalsIgnoreCase("VIEW")) {
                    td.setTableType("VIEW");
                } else {
                    td.setTableType("TABLE");
                }
                ret.add(td);
            }
            return ret;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** 默认值通过参数化旧版目录路径读取，不使用现代父类的 SQL。 */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        return null;
    }

    /**
     * 原生 8.00.760 和 Microsoft JDBC 3.0，直接 TOP 0 保留自增标识元数据。
     * 继承的派生表 TOP 1 查询会丢失自增标识信息，并可能读取实际行。
     * 借用连接时不改变事务或会话设置。
     */
    @Override
    @SneakyThrows
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName, String tableName) {
        verifyLegacyServer(connection);
        String relation = RestrictedSelectGuard.qualifiedTableName(getDatabaseType(), schemaName, tableName);
        List<ColumnDescription> columns = getSelectSqlColumnMeta(connection, "SELECT TOP 0 * FROM " + relation, 30);
        if (columns.isEmpty() || columns.size() > 1024) {
            throw unsupported("Invalid SQL Server 2000 column count");
        }
        enrichLegacyColumns(connection, schemaName, tableName, columns);
        return columns;
    }

    /**
     * 为字段描述列表补充默认值与注释信息。
     */
    @Override
    @SneakyThrows
    public void setColumnDefaultValue(Connection connection, String schema, String table,
            List<ColumnDescription> columns) {
        verifyLegacyServer(connection);
        RestrictedSelectGuard.qualifiedTableName(getDatabaseType(), schema, table);
        enrichLegacyColumns(connection, schema, table, columns);
    }

    /**
     * 校验旧版服务端。
     */
    private void verifyLegacyServer(Connection connection) throws SQLException {
        if (connection == null || !"Microsoft SQL Server".equals(connection.getMetaData().getDatabaseProductName())
                || !"8.00.760".equals(connection.getMetaData().getDatabaseProductVersion())) {
            throw unsupported("SQL Server 2000 column metadata requires verified server 8.00.760");
        }
    }

    /**
     * 旧驱动不能解码 SQL_VARIANT，需在服务器端转换，并拒绝超大注释。
     */
    private void enrichLegacyColumns(Connection connection, String schema, String table,
            List<ColumnDescription> columns) throws SQLException {
        Map<String, ColumnDescription> byName = new LinkedHashMap<>();
        for (var column : columns) {
            if (column == null || byName.putIfAbsent(column.getFieldName(), column.copy()) != null) {
                throw unsupported("Invalid SQL Server 2000 column snapshot");
            }
        }
        if (byName.isEmpty() || byName.size() > 1024) {
            throw unsupported("Invalid SQL Server 2000 column count");
        }
        Set<String> observed = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(LEGACY_COLUMNS)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setQueryTimeout(30);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString("COLUMN_NAME");
                    ColumnDescription column = byName.get(name);
                    if (column == null || !observed.add(name)) {
                        throw unsupported("SQL Server 2000 column snapshot changed");
                    }
                    if (rows.getBoolean("OPAQUE_DEFAULT")) {
                        throw unsupported("SQL Server 2000 default definition is incomplete");
                    }
                    column.setDefaultValue(rows.getString("COLUMN_DEFAULT"));
                    column.setNullable(rows.getBoolean("NULLABLE"));
                    column.setAutoIncrement(rows.getBoolean("IS_IDENTITY"));
                }
            }
        }
        if (!observed.equals(byName.keySet())) {
            throw unsupported("Incomplete SQL Server 2000 column snapshot");
        }
        // 旧驱动不能解码 SQL_VARIANT，需在服务器端转换，并拒绝超大注释。
        try (PreparedStatement statement = connection.prepareStatement(LEGACY_COMMENTS)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setQueryTimeout(30);
            try (ResultSet rows = statement.executeQuery()) {
                Set<String> comments = new HashSet<>();
                while (rows.next()) {
                    String name = rows.getString("objname");
                    if (!byName.containsKey(name) || !comments.add(name)) {
                        throw unsupported("SQL Server 2000 comment snapshot changed");
                    }
                    if (rows.getInt("COMMENT_BYTES") > 4000) {
                        throw unsupported("SQL Server 2000 column comment exceeds the read budget");
                    }
                    byName.get(name).setRemarks(rows.getString("COLUMN_COMMENT"));
                }
            }
        }
        // 全部有界目录观测成功后才发布补充信息。
        for (var column : columns) {
            var observedColumn = byName.get(column.getFieldName());
            column.setDefaultValue(observedColumn.getDefaultValue());
            column.setNullable(observedColumn.isNullable());
            column.setAutoIncrement(observedColumn.isAutoIncrement());
            column.setRemarks(observedColumn.getRemarks());
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String message) {
        return new SQLFeatureNotSupportedException(message, "0A000");
    }

    private static final String LEGACY_COLUMNS = "SELECT c.name AS COLUMN_NAME,d.text AS COLUMN_DEFAULT,c.isnullable AS NULLABLE,"
            + "COLUMNPROPERTY(c.id,c.name,'IsIdentity') AS IS_IDENTITY,"
            + "CASE WHEN c.cdefault<>0 AND (d.text IS NULL OR EXISTS(SELECT 1 FROM syscomments x WHERE x.id=c.cdefault AND (x.number<>0 OR x.colid<>1))) THEN 1 ELSE 0 END AS OPAQUE_DEFAULT "
            + "FROM syscolumns c JOIN sysobjects o ON o.id=c.id JOIN sysusers u ON u.uid=o.uid "
            + "LEFT JOIN syscomments d ON d.id=c.cdefault AND d.number=0 AND d.colid=1 "
            + "WHERE u.name=? AND o.name=? AND o.xtype IN ('U','V') ORDER BY c.colid";

    private static final String LEGACY_COMMENTS = "SELECT objname,CAST(value AS NVARCHAR(4000)) AS COLUMN_COMMENT,DATALENGTH(value) AS COMMENT_BYTES "
            + "FROM ::fn_listextendedproperty('MS_Description','user',?,'table',?,'column',DEFAULT)";

    /** SQL Server 2000 使用所有者和用户层级，以及不带限定名称的系统存储过程。 */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription table, List<ColumnDescription> columns) {
        RestrictedSelectGuard.qualifiedTableName(getDatabaseType(), table.getSchemaName(), table.getTableName());
        String object = ", 'user', " + commentLiteral(table.getSchemaName())
                + ", 'table', " + commentLiteral(table.getTableName());
        List<String> result = new ArrayList<>();
        if (table.getRemarks() != null && !table.getRemarks().isBlank()) {
            result.add("EXEC sp_addextendedproperty 'MS_Description', " + commentLiteral(table.getRemarks()) + object);
        }
        for (var column : columns) {
            if (column.getRemarks() != null && !column.getRemarks().isBlank()) {
                RestrictedSelectGuard.quotePhysicalIdentifier(getDatabaseType(), column.getFieldName());
                result.add("EXEC sp_addextendedproperty 'MS_Description', " + commentLiteral(column.getRemarks())
                        + object + ", 'column', " + commentLiteral(column.getFieldName()));
            }
        }
        return result;
    }

    /**
     * 按当前方言转义并生成注释文本字面量。
     */
    private static String commentLiteral(String value) {
        if (value == null || value.length() > 2000 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("SQL Server 2000 comment exceeds the bounded Unicode read contract");
        }
        return "N'" + value.replace("'", "''") + "'";
    }

}
