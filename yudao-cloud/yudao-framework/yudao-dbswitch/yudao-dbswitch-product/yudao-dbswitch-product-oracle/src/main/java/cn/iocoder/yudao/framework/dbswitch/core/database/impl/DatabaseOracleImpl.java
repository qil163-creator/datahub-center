package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

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
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleAddColumn;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 支持Oracle数据库的元信息实现
 * <p>
 * 职责：{@link AbstractDatabase} 的 Oracle 方言实现。主要差异点：
 * <ul>
 * <li>标识符使用双引号包裹，schema 即用户（OWNER）；</li>
 * <li>表清单查 all_tab_comments，主键查 all_constraints/all_cons_columns（只取状态为 ENABLED 的主键）；</li>
 * <li>建表/视图 DDL 通过 DBMS_METADATA.GET_DDL 获取；</li>
 * <li>SQL 合法性探测使用 explain plan for；字段元数据探测用 ROWNUM<=1 包装；</li>
 * <li>字段类型映射为 Oracle 类型（NUMBER/VARCHAR2/NVARCHAR2/CLOB/BLOB 等）；</li>
 * <li>注释通过独立的 COMMENT ON TABLE/COLUMN 语句生成；</li>
 * <li>建索引时索引名带 schema 前缀（{@link #qualifiesIndexName()}）。</li>
 * </ul>
 * <p>
 * 备注：
 * <p>
 * （1）Oracle12c安装教程：
 * <p>
 * 官方安装版：https://www.w3cschool.cn/oraclejc/oraclejc-vuqx2qqu.html
 * <p>
 * Docker版本：http://www.pianshen.com/article/4448142743/
 * <p>
 * https://www.cnblogs.com/Dev0ps/p/10676930.html
 * <p>
 * (2) Oracle的一个表里至多只能有一个字段为LONG类型
 *
 * @author jrl
 */
public class DatabaseOracleImpl extends AbstractDatabase implements IDatabaseInterface {

    /** Oracle 没有 schema USAGE；实际验证可见基本表的零行 SELECT，不以 CURRENT_SCHEMA 代替授权。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        String table;
        try (var statement = connection.prepareStatement("SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER=? AND ROWNUM<=1")) {
            statement.setString(1, namespace); statement.setMaxRows(1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new java.sql.SQLFeatureNotSupportedException("Oracle 命名空间无可见基本表；无法只读证明该 schema 的对象访问权限", "0A000");
                table = rows.getString(1);
            }
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(1);
            try (var ignored = statement.executeQuery("SELECT * FROM " + cn.iocoder.yudao.framework.dbswitch.core.util.OracleSqlNames.relation(namespace, table) + " WHERE 1=0")) {
                // 只验证解析和 SELECT 授权，不读取业务行，也不改变默认 schema。
            }
        } catch (SQLException failure) {
            if (java.util.Set.of("42501", "3F000", "42P01", "42S02").contains(java.util.Objects.toString(failure.getSQLState(), ""))
                    || "42000".equals(failure.getSQLState()) && (failure.getErrorCode() == 942 || failure.getErrorCode() == 1031)) {
                throw new java.sql.SQLFeatureNotSupportedException(
                        "Oracle 单个样本不可读或已变化，不能据此判定整个 schema 无权访问；scope 未确认", "0A000", failure);
            }
            throw failure;
        }
    }

    /** 当前会话实际权限目录，不把目录可见性推断为 CREATE/UPDATE 等授权。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        probeNamespace(connection, namespace, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT USER,(SELECT COUNT(*) FROM SESSION_PRIVS) FROM DUAL")) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) throw new SQLException("Oracle 会话账号权限目录证据缺失");
            }
        }
    }

    /** 原生基本表目录；JDBC maxRows 与 ROWNUM 双重限制，不先无界读取再截断。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String namespace, int maxTables, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, maxTables, timeoutSeconds);
        var result = new java.util.ArrayList<TableDescription>();
        try (var statement = connection.prepareStatement("SELECT TABLE_NAME FROM (SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER=? ORDER BY TABLE_NAME) WHERE ROWNUM<=?")) {
            statement.setString(1, namespace); statement.setInt(2, maxTables + 1); statement.setMaxRows(maxTables + 1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == maxTables) throw new SQLException("Oracle 目录超过上限，拒绝截断", "54000");
                    var table = new TableDescription(); table.setSchemaName(namespace); table.setTableName(rows.getString(1)); table.setTableType("TABLE"); result.add(table);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void diagnosticBounds(Connection connection, String namespace, int maxTables, int timeoutSeconds) {
        if (connection == null || namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || maxTables < 1 || maxTables > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("Oracle 诊断参数必须完整且有界");
    }

    /** 仅统计原生目录中的表列注释；无注释不是权限失败，不访问业务行。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.ORACLE) return super.probeExtendedProperties(connection, namespace, table, timeoutSeconds);
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        diagnosticBounds(connection, table, 1, timeoutSeconds);
        String sql = "SELECT CASE WHEN t.COMMENTS IS NULL THEN 0 ELSE 1 END,COUNT(c.COLUMN_NAME),COUNT(m.COMMENTS) "
                + "FROM ALL_TAB_COMMENTS t JOIN ALL_TABLES b ON b.OWNER=t.OWNER AND b.TABLE_NAME=t.TABLE_NAME "
                + "LEFT JOIN ALL_TAB_COLUMNS c ON c.OWNER=t.OWNER AND c.TABLE_NAME=t.TABLE_NAME "
                + "LEFT JOIN ALL_COL_COMMENTS m ON m.OWNER=c.OWNER AND m.TABLE_NAME=c.TABLE_NAME AND m.COLUMN_NAME=c.COLUMN_NAME "
                + "WHERE t.OWNER=? AND t.TABLE_NAME=? GROUP BY t.OWNER,t.TABLE_NAME,t.COMMENTS";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace); statement.setString(2, table);
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Oracle 目标表不存在或不可见", "42S02");
                int tableComments=rows.getInt(1); int columns=rows.getInt(2); int comments=rows.getInt(3);
                if (rows.wasNull() || columns<1 || comments<0 || comments>columns || tableComments<0 || tableComments>1 || rows.next())
                    throw new SQLException("Oracle 表列注释目录证据不完整或不唯一");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(columns, tableComments==1, comments);
            }
        }
    }

    /** LogMiner 的保守数据库级前置检查，不启用归档、补充日志或挖掘会话。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection,"CDC",1,timeoutSeconds);
        var metadata=connection.getMetaData();
        if (getDatabaseType()!=ProductTypeEnum.ORACLE || !"Oracle".equals(metadata.getDatabaseProductName())
                || !java.util.Set.of(19,21,23).contains(metadata.getDatabaseMajorVersion()))
            throw new java.sql.SQLFeatureNotSupportedException("Oracle CDC 仅声明原生 19/21/23 的 LogMiner 目录契约","0A000");
        boolean logging;
        try (var statement=connection.prepareStatement("SELECT LOG_MODE,SUPPLEMENTAL_LOG_DATA_MIN,SUPPLEMENTAL_LOG_DATA_ALL FROM V$DATABASE")) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Oracle 日志配置目录无响应");
                String archive=rows.getString(1); String minimum=rows.getString(2); String all=rows.getString(3);
                if (archive==null || minimum==null || all==null || rows.next()) throw new SQLException("Oracle 日志配置证据不完整或不唯一");
                // 不把最小日志当成全部列日志；表级单独补充日志需由后续对象检查另行证明。
                logging="ARCHIVELOG".equals(archive) && ("YES".equals(minimum)||"IMPLICIT".equals(minimum)) && "YES".equals(all);
            }
        }
        String sql="SELECT (SELECT COUNT(DISTINCT PRIVILEGE) FROM SESSION_PRIVS WHERE PRIVILEGE IN ('LOGMINING','SELECT ANY TRANSACTION','SELECT ANY DICTIONARY')),"
                + "CASE WHEN USER='SYS' OR EXISTS (SELECT 1 FROM ALL_TAB_PRIVS WHERE TABLE_SCHEMA='SYS' AND TABLE_NAME='DBMS_LOGMNR' AND PRIVILEGE='EXECUTE' "
                + "AND (GRANTEE=USER OR GRANTEE='PUBLIC' OR GRANTEE IN (SELECT ROLE FROM SESSION_ROLES))) THEN 1 ELSE 0 END FROM DUAL";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Oracle LogMiner 权限目录无响应");
                int privileges=rows.getInt(1); boolean missing=rows.wasNull(); int execute=rows.getInt(2);
                if (missing || rows.wasNull() || privileges<0 || privileges>3 || execute<0 || execute>1 || rows.next()) throw new SQLException("Oracle LogMiner 权限证据不完整");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic(logging,privileges==3 && execute==1);
            }
        }
    }

    private static final String COLUMN_PROPERTIES =
            "SELECT c.column_name, m.comments AS column_comment, c.data_default AS column_default FROM all_tab_columns c "
                    + "LEFT JOIN all_col_comments m ON m.owner=c.owner AND m.table_name=c.table_name AND m.column_name=c.column_name "
                    + "WHERE c.owner=%s AND c.table_name=%s ORDER BY c.column_id";

    /**
     * 初始化 {@code DatabaseOracleImpl}及其父类状态。
     */
    public DatabaseOracleImpl() {
        super(ProductTypeEnum.ORACLE.getDriveClassName());
    }

    /**
     * 初始化 {@code DatabaseOracleImpl}及其父类状态。
     */
    public DatabaseOracleImpl(String driverClassName) {
        super(driverClassName);
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.ORACLE;
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.ORACLE) {
            return super.queryRelationFingerprint(connection, reference);
        }
        return OracleRelationIdentity.fingerprint(connection, reference);
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String alias, String column) {
        return integerProjection(alias, column, 21, false);
    }

    /**
     * 多兼容模式产品在实际连接上验证方言；默认委托旧 SPI，保留已有产品/扩展的行为。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(Connection connection, String alias,
            String column) throws SQLException {
        verifyKeyConnection(connection);
        return integerKeyProjection(alias, column);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String alias, String column, int maxBytes) {
        if (maxBytes < 1 || maxBytes > 256) {
            throw new IllegalArgumentException("Invalid Oracle integer byte budget");
        }
        return integerProjection(alias, column, Math.min(maxBytes, 21), true);
    }

    /**
     * 连接感知的复合键投影；不得为了检测模式改变连接状态。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(Connection connection, String alias, String column,
            int maxBytes) throws SQLException {
        verifyKeyConnection(connection);
        return keyComponentProjection(alias, column, maxBytes);
    }

    /**
     * 校验键连接。
     */
    private void verifyKeyConnection(Connection connection) throws SQLException {
        if (connection == null || !"Oracle".equals(connection.getMetaData().getDatabaseProductName())
                || connection.getMetaData().getDatabaseMajorVersion() != 23) {
            throw new java.sql.SQLFeatureNotSupportedException(
                    "Oracle key projection requires verified native version 23", "0A000");
        }
    }

    /**
     * 生成整数值的查询投影。
     */
    private static BoundedIntegerKeyProjection integerProjection(String alias, String column, int maxBytes,
            boolean binary) {
        if (column == null || !column.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")
                || alias != null && !alias.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Ordinary Oracle key identifiers required");
        }
        // 别名是普通未加引号的 SQL 别名，Oracle 会折叠其大小写；列则是精确物理名称或结果名称。
        String field = (alias == null ? "" : alias + ".") + quoteName(column);
        String text = "TO_CHAR(" + field + ", 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''')";
        String bounded = "CASE WHEN " + field + " BETWEEN -9223372036854775808 AND 9223372036854775807"
                + " AND LENGTHB(" + text + ") <= " + maxBytes + " THEN " + text + " ELSE NULL END";
        // Oracle JDBC 中 NULLIF 与标量子查询会丢失精度及小数位数，此未强制转换的 CASE 同时保留两者。
        return new IntegerProjection(binary ? "UTL_RAW.CAST_TO_RAW(" + bounded + ")" : bounded,
                "CASE WHEN 1=0 THEN " + field + " END", bounded);
    }

    private record IntegerProjection(String valueSql, String typeSql,
                                     String groupSql) implements BoundedIntegerKeyProjection {

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
            return "NUMBER".equals(metadata.getColumnTypeName(column)) && metadata.getColumnType(
                    column) == java.sql.Types.NUMERIC
                    && metadata.getPrecision(column) >= 1 && metadata.getPrecision(column) <= 38
                    && metadata.getScale(column) == 0 && metadata.isSigned(column) ? java.sql.Types.BIGINT : 0;
        }
    }

    /**
     * 引用名称。
     */
    private static String quoteName(String name) {
        return cn.iocoder.yudao.framework.dbswitch.core.util.OracleSqlNames.quote(name);
    }

    /**
     * 获取数据库的表全名。
     */
    @Override
    public String getQuotedSchemaTableCombination(String schema, String table) {
        return quoteName(schema) + "." + quoteName(table);
    }

    /**
     * 获取指定模式Schema内的所有表列表。
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        String sql = "SELECT owner,table_name,table_type,comments FROM all_tab_comments WHERE owner=? ORDER BY table_name";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schemaName);
            statement.setQueryTimeout(30);
            try (var rows = statement.executeQuery()) {
                List<TableDescription> result = new ArrayList<>();
                while (rows.next()) {
                    var table = new TableDescription();
                    table.setSchemaName(rows.getString(1));
                    table.setTableName(rows.getString(2));
                    table.setTableType("VIEW".equals(rows.getString(3)) ? "VIEW" : "TABLE");
                    table.setRemarks(rows.getString(4));
                    result.add(table);
                }
                return result;
            }
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
    }

    /**
     * 获取查询字段默认值/注释的 SQL（数据库方言相关）。
     */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        return String.format(COLUMN_PROPERTIES, "'" + schemaName.replace("'", "''") + "'",
                "'" + tableName.replace("'", "''") + "'");
    }

    /**
     * 获取指定模式表的元信息。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schema, String table) {
        var columns = queryTableColumnMetaOnly(connection, schema, table);
        setColumnDefaultValue(connection, schema, table, columns);
        return columns;
    }

    /**
     * 读取表结构中的列定义。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMetaOnly(Connection connection, String schema, String table) {
        return getSelectSqlColumnMeta(connection, getTableFieldsQuerySQL(schema, table), 30);
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    @Override
    public String getTableDDL(Connection connection, String schema, String table) {
        return readObjectDdl(connection, "TABLE", schema, table);
    }

    /**
     * 获取指定视图表的DDL语句。
     */
    @Override
    public String getViewDDL(Connection connection, String schema, String table) {
        return readObjectDdl(connection, "VIEW", schema, table);
    }

    /**
     * 通过 DBMS_METADATA 读取指定对象的原生 DDL。
     */
    private String readObjectDdl(Connection connection, String type, String schema, String table) {
        try (var statement = connection.prepareStatement("SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM DUAL")) {
            statement.setString(1, type);
            statement.setString(2, table);
            statement.setString(3, schema);
            statement.setQueryTimeout(30);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
    }

    /**
     * 获取指定模式表的主键字段列表。
     */
    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schema, String table) {
        String sql = "SELECT c.column_name FROM all_cons_columns c JOIN all_constraints p ON p.owner=c.owner "
                + "AND p.constraint_name=c.constraint_name AND p.table_name=c.table_name "
                + "WHERE p.constraint_type='P' AND p.status='ENABLED' AND p.owner=? AND p.table_name=? ORDER BY c.position";
        try (var statement = connection.prepareStatement(sql)) {
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
     * 解析查询 SQL 的字段元数据：Oracle 没有 LIMIT，用子查询包一层 ROWNUM<=1 只取一行
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        String querySQL = String.format("SELECT * from (%s) tmp where ROWNUM<=1 ",
                sql.replace(";", ""));
        return this.getSelectSqlColumnMeta(connection, querySQL);
    }

    /**
     * 表字段查询 SQL：WHERE 1=2 不取数据只取元信息，标识符用双引号
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        return "SELECT * FROM " + getQuotedSchemaTableCombination(schemaName, tableName) + " WHERE 1=2";
    }

    /**
     * SQL 合法性探测语句：使用 explain plan for 只生成执行计划、不实际执行
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        return String.format("explain plan for %s", sql.replace(";", ""));
    }

    /**
     * 生成 Oracle 建表语句中的字段定义片段
     * <p>
     * 类型映射规则：
     * 时间戳/时间→TIMESTAMP、日期→DATE、布尔→NUMBER(1)；
     * 小数/大数→NUMBER(长度,精度)（长度上限 38）；整型→INTEGER；
     * 字符串按原 JDBC 类型细分为 NVARCHAR2/VARCHAR2/CHAR/NCHAR/CLOB/NCLOB，
     * 未识别时按长度选择 NVARCHAR2（上限 2000 字符）或 CLOB；
     * 二进制→BLOB；未识别类型兜底 CLOB。
     * 其他规则：CLOB/BLOB 不能带默认值；日期时间类默认值统一改为 SYSDATE；
     * 非空字段追加 NOT NULL（注意 Oracle 中 DEFAULT 必须写在 NOT NULL 之前）
     *
     * @param v           字段元数据
     * @param pks         主键字段名列表（Oracle 实现未特殊处理主键类型）
     * @param useAutoInc  是否自增（Oracle 实现未使用）
     * @param addCr       是否在结尾追加换行
     * @param withRemarks 是否携带注释（Oracle 注释走独立 COMMENT ON 语句，此参数未使用）
     * @return 字段定义 SQL 片段
     */
    @Override
    public String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc,
            boolean addCr, boolean withRemarks) {
        String fieldname = v.getName();
        int length = v.getLength();
        int precision = v.getPrecision();

        StringBuilder retval = new StringBuilder(128);
        retval.append(' ').append(quoteName(fieldname)).append("    ");

        int type = v.getType();
        int sqlType = v.getSqlType();
        boolean canHaveDefaultValue = true;
        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
            case ColumnMetaData.TYPE_TIME:
                // 通用跨产品元数据不能证明更小的精度；采用 Oracle 完整范围可避免舍入。
                retval.append("TIMESTAMP(9)");
                break;
            case ColumnMetaData.TYPE_DATE:
                retval.append("DATE");
                break;
            case ColumnMetaData.TYPE_BOOLEAN:
                retval.append("NUMBER(1)");
                break;
            case ColumnMetaData.TYPE_NUMBER:
            case ColumnMetaData.TYPE_BIGNUMBER:
                // 绝不将精确源数值值域静默截断到 Oracle 的 38 位上限。
                retval.append("NUMBER");
                if (length > 0) {
                    if (length > 38) {
                        throw new IllegalArgumentException("Exact numeric precision exceeds Oracle NUMBER capacity");
                    }

                    retval.append('(').append(length);
                    if (precision < -84 || precision > 127) {
                        throw new IllegalArgumentException("Invalid Oracle NUMBER scale");
                    }
                    if (precision != 0) {
                        retval.append(", ").append(precision);
                    }
                    retval.append(')');
                }
                break;
            case ColumnMetaData.TYPE_INTEGER:
                retval.append("INTEGER");
                break;
            case ColumnMetaData.TYPE_STRING:
                // 字符串：优先按源端原始 JDBC 类型选择对应的 Oracle 字符类型
                switch (sqlType) {
                    case java.sql.Types.NVARCHAR:
                        retval.append("NVARCHAR2(").append(length).append(')');
                        break;
                    case java.sql.Types.VARCHAR:
                        retval.append("VARCHAR2(").append(length).append(')');
                        break;
                    case java.sql.Types.CHAR:
                        retval.append("CHAR(").append(length).append(')');
                        break;
                    case java.sql.Types.NCHAR:
                        retval.append("NCHAR(").append(length).append(')');
                        break;
                    case java.sql.Types.CLOB:
                        retval.append("CLOB");
                        canHaveDefaultValue = false;
                        break;
                    case java.sql.Types.NCLOB:
                        retval.append("NCLOB");
                        canHaveDefaultValue = false;
                        break;
                    default:
                        if (length >= AbstractDatabase.CLOB_LENGTH) {
                            retval.append("CLOB");
                            canHaveDefaultValue = false;
                        } else {
                            if (length == 1) {
                                retval.append("NVARCHAR2(1)");
                            } else if (length > 0 && length <= 2000) {
                                // VARCHAR2(size)，size最大值为4000，单位是字节；而NVARCHAR2(size)，size最大值为2000，单位是字符
                                retval.append("NVARCHAR2(").append(length).append(')');
                            } else {
                                retval.append("CLOB");// 无法确定具体大小，因此使用最大值……
                                canHaveDefaultValue = false;
                            }
                        }
                        break;
                }
                break;
            case ColumnMetaData.TYPE_BINARY: // BLOB 可包含二进制数据。
                retval.append("BLOB");
                canHaveDefaultValue = false;
                break;
            default:
                retval.append("CLOB");
                canHaveDefaultValue = false;
                break;
        }

        // 追加默认值：普通类型原样带引号追加；日期时间类默认值统一替换为 SYSDATE
        if (canHaveDefaultValue && v.getDefaultValue() != null && !"null".equals(v.getDefaultValue()) && !"NULL".equals(
                v.getDefaultValue())) {
            if (type != ColumnMetaData.TYPE_TIMESTAMP && type != ColumnMetaData.TYPE_TIME && type != ColumnMetaData.TYPE_DATE) {
                if (v.getDefaultValue().startsWith("'")) {
                    retval.append(" DEFAULT").append(v.getDefaultValue());
                } else {
                    retval.append(" DEFAULT").append(" '").append(v.getDefaultValue()).append("'");
                }
            } else {
                retval.append(" DEFAULT SYSDATE");
            }
        }

        // Oracle 语法要求 NOT NULL 写在 DEFAULT 之后
        if (!v.isNullable()) {
            retval.append(" NOT NULL");
        }

        if (addCr) {
            retval.append(Const.CR);
        }

        return retval.toString();
    }

    /**
     * 可空加列：ALTER TABLE "schema"."table" ADD ("column" type)，不是 ADD COLUMN。
     * 类型沿用 {@link #getFieldDefinition}。NOT NULL、主键、默认值、identity、生成列不生成 SQL。
     */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        return OracleAddColumn.sql(targetSchemaName, targetTableName, targetColumn,
                meta -> getFieldDefinition(meta, null, false, false, true));
    }

    /**
     * 判断字段类型能否建索引：数值/日期/布尔类可以；
     * 字符串长度达到 2000（NVARCHAR2 上限）或映射为 CLOB 的不建；其余类型不建
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
                if (length >= AbstractDatabase.CLOB_LENGTH) {
                    canCreateIndex = false;
                } else {
                    if (length >= 2000) {
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

    /**
     * 生成表与字段的注释 DDL：Oracle 不支持建表语句内联注释，
     * 为表和每个有注释的字段分别生成 COMMENT ON TABLE / COMMENT ON COLUMN 语句
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td,
            List<ColumnDescription> cds) {
        List<String> results = new ArrayList<>();
        if (StringUtils.isNotBlank(td.getRemarks())) {
            results.add("COMMENT ON TABLE " + quoteName(td.getSchemaName()) + "." + quoteName(td.getTableName())
                    + " IS '" + td.getRemarks().replace("'", "''") + "'");
        }

        for (ColumnDescription cd : cds) {
            if (StringUtils.isNotBlank(cd.getRemarks())) {
                results.add("COMMENT ON COLUMN " + quoteName(td.getSchemaName()) + "." + quoteName(td.getTableName())
                        + "." + quoteName(cd.getFieldName()) + " IS '" + cd.getRemarks().replace("'", "''") + "'");
            }
        }

        return results;
    }

    /** Oracle 未加 schema 的索引名会落在当前用户下，而不是表所在的 schema。 */
    @Override
    public boolean qualifiesIndexName() {
        return true;
    }

    /** 最后读取 LONG 类型的 data_default，每行仅读一次；按精确所有者、表及列连接，绝不按行位置连接。 */
    @Override
    public void setColumnDefaultValue(Connection connection, String schema, String table,
            List<ColumnDescription> columns) {
        try (var statement = connection.prepareStatement(String.format(COLUMN_PROPERTIES, "?", "?"))) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setQueryTimeout(30);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1), comment = rows.getString(2), defaultValue = rows.getString(3);
                    for (var column : columns) {
                        if (name.equals(column.getFieldName())) {
                            column.setRemarks(comment);
                            column.setDefaultValue(defaultValue);
                            break;
                        }
                    }
                }
            }
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
    }

    /**
     * 按当前方言生成列默认值 SQL。
     */
    String dialectDefaultValueSql(String schemaName, String tableName) {
        return getDefaultValueSql(schemaName, tableName);
    }

    /**
     * 按当前方言生成读取表字段的查询 SQL。
     */
    String dialectTableFieldsQuerySQL(String schemaName, String tableName) {
        return getTableFieldsQuerySQL(schemaName, tableName);
    }

    /**
     * 返回当前方言使用的连接探测 SQL。
     */
    String dialectTestQuerySQL(String sql) {
        return getTestQuerySQL(sql);
    }

}
