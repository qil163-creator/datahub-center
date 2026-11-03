package cn.iocoder.yudao.framework.dbswitch.core.database.impl;


import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.product.sybase.SybaseAddColumn;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 支持Sybase数据库的元信息实现
 *
 * @author tang
 */
public class DatabaseSybaseImpl extends AbstractDatabase implements IDatabaseInterface {

    /** ASE 没有现代 schema USAGE；对原生基本表执行零行 SELECT 验证对象范围访问。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        String table;
        try (var statement = connection.prepareStatement("SELECT TOP 1 o.name FROM sysobjects o JOIN sysusers u ON u.uid=o.uid WHERE u.name=? AND o.type='U' ORDER BY o.name")) {
            statement.setString(1, namespace); statement.setMaxRows(1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new java.sql.SQLFeatureNotSupportedException("Sybase ASE 所有者无可见基本表；不能只读证明对象访问权限", "0A000");
                table = rows.getString(1);
            }
        }
        // ASE 标识符模式随会话变化；没有可靠引用模式证据时仅接受严格常规名称。
        if (!namespace.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw new java.sql.SQLFeatureNotSupportedException("Sybase ASE 复杂标识符引用模式尚未验证", "0A000");
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(1);
            try (var ignored = statement.executeQuery("SELECT * FROM " + namespace + "." + table + " WHERE 1=0")) {
                // 不设置 quoted_identifier，不修改调用方会话，也不读取业务行。
            }
        } catch (SQLException failure) {
            if (java.util.Set.of("42501", "42000", "3F000", "42P01", "42S02").contains(java.util.Objects.toString(failure.getSQLState(), ""))) {
                throw new java.sql.SQLFeatureNotSupportedException(
                        "Sybase ASE 单个样本不可读或已变化，不能据此判定整个所有者范围无权访问；scope 未确认", "0A000", failure);
            }
            throw failure;
        }
    }

    /** ASE 原生用户授权目录，不能用 SQL Server 新版目录代替。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        probeNamespace(connection, namespace, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT user_name(),COUNT(*) FROM sysprotects WHERE uid IN (user_id(),0)")) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) throw new SQLException("Sybase ASE 账号授权目录证据缺失");
            }
        }
    }

    /** 原生基本表目录，固定 TOP 有界查询，不调用无界 getTables 后截断。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String namespace, int maxTables, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, maxTables, timeoutSeconds);
        var result = new java.util.ArrayList<TableDescription>();
        String sql = "SELECT TOP " + (maxTables + 1) + " o.name FROM sysobjects o JOIN sysusers u ON u.uid=o.uid WHERE u.name=? AND o.type='U' ORDER BY o.name";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace); statement.setMaxRows(maxTables + 1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == maxTables) throw new SQLException("Sybase ASE 目录超过上限，拒绝截断", "54000");
                    var table = new TableDescription(); table.setSchemaName(namespace); table.setTableName(rows.getString(1)); table.setTableType("TABLE"); result.add(table);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void diagnosticBounds(Connection connection, String namespace, int maxTables, int timeoutSeconds) {
        if (connection == null || namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || maxTables < 1 || maxTables > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("Sybase ASE 诊断参数必须完整且有界");
    }

    private static final String SHOW_CREATE_VIEW_SQL = "SELECT sc.text FROM sysobjects so, syscomments sc WHERE user_name(so.uid)=? AND so.name=? and sc.id = so.id ORDER BY sc.colid";

    private static Set<String> excludesSchemaNames;

    static {
        excludesSchemaNames = new HashSet<>();
        excludesSchemaNames.add("keycustodian_role");
        excludesSchemaNames.add("ha_role");
        excludesSchemaNames.add("replication_role");
        excludesSchemaNames.add("sa_role");
        excludesSchemaNames.add("usedb_user");
        excludesSchemaNames.add("replication_maint_role_gp");
        excludesSchemaNames.add("sybase_ts_role");
        excludesSchemaNames.add("dtm_tm_role");
        excludesSchemaNames.add("sso_role");
        excludesSchemaNames.add("navigator_role");
        excludesSchemaNames.add("sa_serverprivs_role");
        excludesSchemaNames.add("probe");
        excludesSchemaNames.add("mon_role");
        excludesSchemaNames.add("webservices_role");
        excludesSchemaNames.add("js_admin_role");
        excludesSchemaNames.add("js_user_role");
        excludesSchemaNames.add("messaging_role");
        excludesSchemaNames.add("js_client_role");
        excludesSchemaNames.add("oper_role");
        excludesSchemaNames.add("hadr_admin_role_gp");
    }

    /**
     * 初始化 {@code DatabaseSybaseImpl}及其父类状态。
     */
    public DatabaseSybaseImpl() {
        super(ProductTypeEnum.SYBASE.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.SYBASE;
    }

    /**
     * 设置系统目录名称。
     */
    private void setCatalogName(Connection connection) {
        try {
            this.catalogName = connection.getCatalog();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取数据库的模式schema列表。
     */
    @Override
    public List<String> querySchemaList(Connection connection) {
        setCatalogName(connection);
        List<String> schemas = super.querySchemaList(connection);
        return schemas.stream().filter(s -> !excludesSchemaNames.contains(s)).collect(Collectors.toList());
    }

    /**
     * 获取指定模式Schema内的所有表列表。
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        setCatalogName(connection);
        return super.queryTableList(connection, schemaName);
    }

    /**
     * 获取指定模式表的字段列表。
     */
    @Override
    public List<String> queryTableColumnName(Connection connection, String schemaName,
            String tableName) {
        setCatalogName(connection);
        return super.queryTableColumnName(connection, schemaName, tableName);
    }

    /**
     * 获取查询字段默认值/注释的 SQL（数据库方言相关）。
     */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        return null;
    }

    /**
     * 获取指定模式表的主键字段列表。
     */
    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schemaName,
            String tableName) {
        setCatalogName(connection);
        return super.queryTablePrimaryKeys(connection, schemaName, tableName);
    }

    /**
     * 获取指定模式表的元信息。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName,
            String tableName) {
        setCatalogName(connection);
        return super.queryTableColumnMeta(connection, schemaName, tableName);
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        List<ColumnDescription> columnDescriptions = queryTableColumnMeta(connection, schemaName, tableName);
        List<String> pks = queryTablePrimaryKeys(connection, schemaName, tableName);
        return GenerateSqlUtils.getDDLCreateTableSQL(this,
                columnDescriptions, pks, schemaName, tableName, false);
    }

    /**
     * 获取指定视图表的DDL语句。
     */
    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        try (PreparedStatement ps = connection.prepareStatement(SHOW_CREATE_VIEW_SQL)) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder sql = new StringBuilder();
                while (rs.next()) {
                    sql.append(rs.getString(1));
                }
                return sql.toString();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取指定查询SQL的元信息。
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        setCatalogName(connection);
        String querySQL = String.format("SELECT TOP 1 * from (%s) tmp ", sql.replace(";", ""));
        return this.getSelectSqlColumnMeta(connection, querySQL);
    }

    /**
     * 生成用于探测表字段的查询 SQL。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        return String.format("select top 1 * from [%s].[%s] ", schemaName, tableName);
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
        return String.format("  [%s].[%s] ", schemaName, tableName);
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

        String retval = " [" + fieldname + "]  ";

        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
            case ColumnMetaData.TYPE_TIME:
            case ColumnMetaData.TYPE_DATE:
                retval += "DATETIME";
                if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                    retval += " NOT NULL";
                }
                break;
            case ColumnMetaData.TYPE_BOOLEAN:
                retval += "TINYINT";
                if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                    retval += " NOT NULL";
                }
                break;
            case ColumnMetaData.TYPE_NUMBER:
            case ColumnMetaData.TYPE_INTEGER:
            case ColumnMetaData.TYPE_BIGNUMBER:
                if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                    if (useAutoInc) {
                        retval += "INTEGER IDENTITY NOT NULL";
                    } else {
                        retval += "INTEGER NOT NULL";
                    }
                } else {
                    if (precision != 0 || (precision == 0 && length > 9)) {
                        if (precision > 0 && length > 0) {
                            retval += "DECIMAL(" + length + ", " + precision + ") NULL";
                        } else {
                            retval += "DOUBLE PRECISION NULL";
                        }
                    } else {
                        if (length < 3) {
                            retval += "TINYINT NULL";
                        } else if (length < 5) {
                            retval += "SMALLINT NULL";
                        } else {
                            retval += "INTEGER NULL";
                        }
                    }
                }
                break;
            case ColumnMetaData.TYPE_STRING:
                if (length >= 2048) {
                    retval += "TEXT NULL";
                } else {
                    retval += "VARCHAR";
                    if (length > 0) {
                        retval += "(" + length + ")";
                    }
                    if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                        retval += " NOT NULL";
                    } else {
                        retval += " NULL";
                    }
                }
                break;
            case ColumnMetaData.TYPE_BINARY:
                retval += "VARBINARY";
                break;
            default:
                retval += "TEXT NULL";
                break;
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
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            sb.append(StringUtils.join(pks, "] , ["));
            sb.append("]");
            return sb.toString();
        }

        return "";
    }

    /**
     * 获取表和字段的注释定义。
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td,
            List<ColumnDescription> cds) {
        return Collections.emptyList();
    }

    /**
     * 可空、无默认加列。类型沿用 {@link #getFieldDefinition}。
     * 名字用 {@code quotePhysicalIdentifier(SYBASE)} 的方括号，语句是 ADD 而不是 ADD COLUMN。
     */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        return SybaseAddColumn.sql(this, targetSchemaName, targetTableName, targetColumn);
    }

    /**
     * 获取字段类型。
     */
    @Override
    public boolean canCreateIndex(ColumnMetaData v) {
        //TODO
        return false;
    }

}
