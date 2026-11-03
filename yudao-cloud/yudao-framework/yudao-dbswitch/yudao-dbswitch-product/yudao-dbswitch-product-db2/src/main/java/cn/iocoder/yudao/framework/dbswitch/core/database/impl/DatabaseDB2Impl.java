package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.product.db2.Db2AddColumn;
import org.apache.commons.lang3.StringUtils;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 支持DB2数据库的元信息实现
 *
 * @author jrl
 */
public class DatabaseDB2Impl extends AbstractDatabase implements IDatabaseInterface {

    /** DB2 LUW 12 的活动归档配置与有效日志读取管理权限；不修改 DB CFG 或表 DATA CAPTURE。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection,int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection,"CDC",1,timeoutSeconds);
        var metadata=connection.getMetaData();
        if (!java.util.Set.of("DB2/LINUXX8664","DB2/NT64","DB2/AIX64").contains(metadata.getDatabaseProductName()) || metadata.getDatabaseMajorVersion()!=12)
            throw new java.sql.SQLFeatureNotSupportedException("DB2 CDC 仅声明 LUW 12 目录契约，不包含 z/OS 或 IBM i","0A000");
        boolean logging=false; var names=new java.util.HashSet<String>();
        try (var statement=connection.prepareStatement("SELECT NAME,VALUE FROM SYSIBMADM.DBCFG WHERE NAME IN ('logarchmeth1','logarchmeth2')")) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(3);
            try (var rows=statement.executeQuery()) {
                while (rows.next()) {
                    String name=rows.getString(1); String value=rows.getString(2);
                    if (name==null || !java.util.Set.of("logarchmeth1","logarchmeth2").contains(name) || !names.add(name) || value==null || value.isBlank()) throw new SQLException("DB2 LUW 归档配置证据不完整或不唯一");
                    logging|=!"OFF".equalsIgnoreCase(value.trim());
                }
            }
        }
        if (!names.contains("logarchmeth1")) throw new SQLException("DB2 LUW 活动归档配置证据缺失");
        String sql="SELECT COUNT(*) FROM TABLE(SYSPROC.AUTH_LIST_AUTHORITIES_FOR_AUTHID(CURRENT USER,'U')) AS a "
                + "WHERE AUTHORITY IN ('SYSADM','DBADM') AND (D_USER='Y' OR D_GROUP='Y' OR D_PUBLIC='Y' OR ROLE_USER='Y' OR ROLE_GROUP='Y' OR ROLE_PUBLIC='Y')";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("DB2 LUW 有效日志读取权限目录无响应");
                int authority=rows.getInt(1);
                if (rows.wasNull() || authority<0 || authority>2 || rows.next()) throw new SQLException("DB2 LUW 有效权限证据不完整");
                // 仅为保守前置条件；具体表的 DATA CAPTURE CHANGES 与外部捕获程序仍需独立检查。
                return new cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic(logging,authority>0);
            }
        }
    }

    /** DB2 LUW 的 REMARKS 目录与其他 DB2 产品分离，不读取注释正文。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds); diagnosticBounds(connection, table, 1, timeoutSeconds);
        String sql="SELECT CASE WHEN t.REMARKS IS NULL OR t.REMARKS='' THEN 0 ELSE 1 END,COUNT(c.COLNAME),"
                + "COALESCE(SUM(CASE WHEN c.REMARKS IS NOT NULL AND c.REMARKS<>'' THEN 1 ELSE 0 END),0) "
                + "FROM SYSCAT.TABLES t LEFT JOIN SYSCAT.COLUMNS c ON c.TABSCHEMA=t.TABSCHEMA AND c.TABNAME=t.TABNAME "
                + "WHERE t.TABSCHEMA=? AND t.TABNAME=? AND t.TYPE='T' GROUP BY t.TABSCHEMA,t.TABNAME,t.REMARKS";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setString(1,namespace); statement.setString(2,table); statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("DB2 LUW 目标表不存在或不可见","42S02");
                int tableComments=rows.getInt(1); int columns=rows.getInt(2); int comments=rows.getInt(3);
                if (rows.wasNull() || columns<1 || comments<0 || comments>columns || tableComments<0 || tableComments>1 || rows.next()) throw new SQLException("DB2 LUW 注释目录证据不完整或不唯一");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(columns,tableComments==1,comments);
            }
        }
    }


    /** DB2 LUW 的 CURRENT SCHEMA 不授予权限；实际零行 SELECT 才是对象进入证据。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        String table;
        try (var statement = connection.prepareStatement("SELECT TABNAME FROM SYSCAT.TABLES WHERE TABSCHEMA=? AND TYPE='T' ORDER BY TABNAME FETCH FIRST 1 ROW ONLY")) {
            statement.setString(1, namespace); statement.setMaxRows(1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new java.sql.SQLFeatureNotSupportedException("DB2 LUW schema 无基本表；无法只读证明对象访问权限", "0A000");
                table = rows.getString(1);
            }
        }
        String relation = diagnosticIdentifier(namespace) + "." + diagnosticIdentifier(table);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(1);
            try (var ignored = statement.executeQuery("SELECT * FROM " + relation + " WHERE 1=0")) {
                // 不读取业务行，不修改 CURRENT SCHEMA 或调用方事务。
            }
        } catch (SQLException failure) {
            if (java.util.Set.of("42501", "3F000", "42P01", "42S02", "42704").contains(java.util.Objects.toString(failure.getSQLState(), ""))) {
                throw new java.sql.SQLFeatureNotSupportedException(
                        "DB2 LUW 单个样本不可读或已变化，不能据此判定整个 schema 无权访问；scope 未确认", "0A000", failure);
            }
            throw failure;
        }
    }

    /** 原生数据库授权目录；组和角色授权不在此处自行推断或冒充已验证。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        probeNamespace(connection, namespace, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT CURRENT USER,(SELECT COUNT(*) FROM SYSCAT.DBAUTH WHERE GRANTEE IN (CURRENT USER,'PUBLIC')) FROM SYSIBM.SYSDUMMY1")) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) throw new SQLException("DB2 LUW 账号授权目录证据缺失");
            }
        }
    }

    /** 仅 DB2 LUW 的实体表目录；JDBC 最大行数是有界执行入口，额外一行检测溢出。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String namespace, int maxTables, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, maxTables, timeoutSeconds);
        var result = new ArrayList<TableDescription>();
        try (var statement = connection.prepareStatement("SELECT TABNAME FROM SYSCAT.TABLES WHERE TABSCHEMA=? AND TYPE='T' ORDER BY TABNAME FETCH FIRST " + (maxTables + 1) + " ROWS ONLY")) {
            statement.setString(1, namespace); statement.setMaxRows(maxTables + 1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == maxTables) throw new SQLException("DB2 LUW 目录超过上限，拒绝截断", "54000");
                    var table = new TableDescription(); table.setSchemaName(namespace); table.setTableName(rows.getString(1)); table.setTableType("TABLE"); result.add(table);
                }
            }
        }
        return List.copyOf(result);
    }

    private String diagnosticIdentifier(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0) throw new IllegalArgumentException("DB2 LUW 物理标识符不能为空");
        return quoteIdentifier(name);
    }

    private static void diagnosticBounds(Connection connection, String namespace, int maxTables, int timeoutSeconds) {
        if (connection == null || namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || maxTables < 1 || maxTables > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("DB2 LUW 诊断参数必须完整且有界");
    }

    private static final String CALL_DB2LK_GEN =
            "CALL SYSPROC.DB2LK_GENERATE_DDL(?,?)";

    private static final String CALL_DB2LK_CLEAN =
            "CALL SYSPROC.DB2LK_CLEAN_TABLE(?)";

    private static final String SEL_DB2LK =
            "SELECT SQL_STMT FROM SYSTOOLS.DB2LOOK_INFO WHERE OP_TOKEN = '%d' ORDER BY OP_SEQUENCE WITH UR";

    private static final String DB2LK_COMMAND =
            "-e -x -xd -td %s -t %s";

    private static final String SHOW_CREATE_VIEW_SQL =
            "SELECT TEXT FROM SYSCAT.VIEWS WHERE VIEWSCHEMA ='%s' AND VIEWNAME ='%s'";

    /**
     * 初始化 {@code DatabaseDB2Impl}及其父类状态。
     */
    public DatabaseDB2Impl() {
        super(ProductTypeEnum.DB2.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.DB2;
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        String fullName = getQuotedSchemaTableCombination(schemaName, tableName);
        final String command = String.format(DB2LK_COMMAND, "\n", fullName);
        List<String> result = new ArrayList<>();
        try (CallableStatement stmt = connection.prepareCall(CALL_DB2LK_GEN)) {
            stmt.registerOutParameter(2, java.sql.Types.INTEGER);
            stmt.setString(1, command);
            stmt.execute();
            int token = stmt.getInt(2);
            String sql = String.format(SEL_DB2LK, token);
            try (Statement st = connection.createStatement()) {
                if (st.execute(sql)) {
                    try (ResultSet rs = st.getResultSet()) {
                        if (rs != null) {
                            while (rs.next()) {
                                String value = rs.getString(1);
                                Optional.ofNullable(value).ifPresent(result::add);
                            }
                        }
                    }
                }
            } finally {
                try (CallableStatement st = connection.prepareCall(CALL_DB2LK_CLEAN)) {
                    st.setInt(1, token);
                    st.execute();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return String.join(";", result);
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
     * 获取指定查询SQL的元信息。
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        String querySQL = String.format(" %s fetch first 1 rows only ", withoutTerminator(sql));
        return this.getSelectSqlColumnMeta(connection, querySQL);
    }

    /**
     * 生成用于探测表字段的查询 SQL。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        return "SELECT * FROM " + getQuotedSchemaTableCombination(schemaName, tableName) + " WHERE 1=2";
    }

    /**
     * 获取数据库的表全名。
     */
    @Override
    public String getQuotedSchemaTableCombination(String schemaName, String tableName) {
        return quoteIdentifier(schemaName) + "." + quoteIdentifier(tableName);
    }

    /**
     * 把用户 SQL 包装为低成本的合法性探测语句（如 explain、外层包 WHERE 1=2 等），由子类按方言实现。
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        return String.format("SELECT * FROM ( %s ) t WHERE 1=2 ", withoutTerminator(sql));
    }

    /** 仅移除末尾语句终止符；带引号的名称及值可能包含分号。 */
    private static String withoutTerminator(String sql) {
        String query = sql.strip();
        return query.endsWith(";") ? query.substring(0, query.length() - 1) : query;
    }

    /**
     * 获取查询字段默认值/注释的 SQL（数据库方言相关）。
     */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        return null;
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

        String retval = " " + quoteIdentifier(fieldname) + "   ";
        boolean canHaveDefaultValue = true;
        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
                if (length > 12) {
                    throw new IllegalArgumentException("DB2 timestamp precision exceeds 12 digits");
                }
                retval += length >= 0 ? "TIMESTAMP(" + length + ")" : "TIMESTAMP";
                break;
            case ColumnMetaData.TYPE_TIME:
                retval += "TIME";
                break;
            case ColumnMetaData.TYPE_DATE:
                retval += "DATE";
                break;
            case ColumnMetaData.TYPE_BOOLEAN:
                retval += "BOOLEAN";
                break;
            case ColumnMetaData.TYPE_NUMBER:
            case ColumnMetaData.TYPE_BIGNUMBER:
                if (null != pks && pks.contains(fieldname) && useAutoInc) {
                    retval += "BIGINT NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 0, INCREMENT BY 1, NOCACHE)";
                } else {
                    if (length > 0) {
                        retval += "DECIMAL(" + length;
                        if (precision > 0) {
                            retval += ", " + precision;
                        }
                        retval += ")";
                    } else {
                        retval += "FLOAT";
                    }
                    if (null != pks && pks.contains(fieldname)) {
                        retval += " NOT NULL";
                    }
                }
                break;
            case ColumnMetaData.TYPE_INTEGER:
                String integerType = v.getSqlType() == java.sql.Types.BIGINT || length > 9 ? "BIGINT"
                        : v.getSqlType() == java.sql.Types.SMALLINT || v.getSqlType() == java.sql.Types.TINYINT ? "SMALLINT" : "INTEGER";
                if (null != pks && pks.contains(fieldname)) {
                    if (useAutoInc) {
                        retval += integerType + " NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 0, INCREMENT BY 1, NOCACHE)";
                    } else {
                        retval += integerType + " NOT NULL";
                    }
                } else {
                    retval += integerType;
                }
                break;
            case ColumnMetaData.TYPE_STRING:
                if (length * 3 > 32672) {
                    retval += "CLOB";
                    canHaveDefaultValue = false;
                } else {
                    retval += "VARCHAR";
                    if (length > 0) {
                        retval += "(" + length * 3;
                    } else {
                        retval += "(";
                    }

                    retval += ")";
                }

                if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                    retval += " NOT NULL";
                }

                break;
            case ColumnMetaData.TYPE_BINARY:
                if (length > 32672) {
                    retval += "BLOB(" + length + ")";
                    canHaveDefaultValue = false;
                } else {
                    if (length > 0) {
                        retval += v.getSqlType() == java.sql.Types.VARBINARY ? "VARBINARY(" + length + ")"
                                : "CHAR(" + length + ") FOR BIT DATA";
                    } else {
                        retval += "BLOB";
                        canHaveDefaultValue = false;
                    }
                }
                break;
            default:
                retval += "CLOB";
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
            } /*else {
				retval += " DEFAULT DEFAULT SYSDATE";
			}*/
        }

        if (!v.isNullable()) {
            retval += " NOT NULL";
        }

        if (addCr) {
            retval += Const.CR;
        }

        return retval;
    }

    /** 可空、无默认值的普通列。本类 DDL 是双引号标识符，加列用 ADD，不用 MySQL 的 ADD COLUMN。 */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        return Db2AddColumn.sql(targetSchemaName, targetTableName, targetColumn,
                meta -> getFieldDefinition(meta, null, false, false, false));
    }

    /**
     * 获取表和字段的注释定义。
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td,
            List<ColumnDescription> cds) {
        List<String> results = new ArrayList<>();
        if (StringUtils.isNotBlank(td.getRemarks())) {
            results.add(
                    "COMMENT ON TABLE " + quoteIdentifier(td.getSchemaName()) + "." + quoteIdentifier(td.getTableName())
                            + " IS '" + td.getRemarks().replace("'", "''") + "'");
        }

        for (ColumnDescription cd : cds) {
            if (StringUtils.isNotBlank(cd.getRemarks())) {
                results.add("COMMENT ON COLUMN " + quoteIdentifier(td.getSchemaName()) + "." + quoteIdentifier(
                        td.getTableName())
                        + "." + quoteIdentifier(cd.getFieldName()) + " IS '" + cd.getRemarks()
                        .replace("'", "''") + "'");
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
            case ColumnMetaData.TYPE_BIGNUMBER:
            case ColumnMetaData.TYPE_INTEGER:
                break;
            case ColumnMetaData.TYPE_STRING:
                if (length * 3 > 32672) {
                    canCreateIndex = false;
                }
                break;
            case ColumnMetaData.TYPE_BINARY:
                if (length > 32672) {
                    canCreateIndex = false;
                } else {
                    if (length <= 0) {
                        canCreateIndex = false;
                    }
                }
                break;
            default:
                canCreateIndex = false;
                break;
        }

        return canCreateIndex;
    }
}
