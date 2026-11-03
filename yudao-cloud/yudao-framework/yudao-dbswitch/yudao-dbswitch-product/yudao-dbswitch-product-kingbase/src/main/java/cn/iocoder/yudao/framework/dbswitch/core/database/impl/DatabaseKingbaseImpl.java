package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.database.constant.PostgresqlConst;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.KingbaseRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.product.kingbase.KingbaseAddColumn;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 支持Kingbase数据库的元信息实现
 *
 * @author jrl
 */
public class DatabaseKingbaseImpl extends AbstractDatabase implements IDatabaseInterface {

    /** Kingbase 指定构建及 Oracle 模式独立检查复制目录；不从 PG 兼容协议推定支持。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic probeCdcReadiness(
            Connection connection,int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection,"CDC",1,timeoutSeconds);
        String sql="SELECT pg_catalog.version(),pg_catalog.current_setting('database_mode'),pg_catalog.current_setting('wal_level'),"
                + "pg_catalog.current_setting('max_replication_slots')::int,pg_catalog.current_setting('max_wal_senders')::int,rolsuper,rolreplication,pg_catalog.current_setting('enable_ci') "
                + "FROM pg_catalog.pg_roles WHERE rolname=current_user";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Kingbase CDC 配置及角色目录无响应");
                String version=rows.getString(1); String mode=rows.getString(2); String caseMode=rows.getString(8);
                if (!VERIFIED_VERSION.equals(version) || !"oracle".equals(mode) || !"on".equals(caseMode))
                    throw new java.sql.SQLFeatureNotSupportedException("Kingbase CDC 仅声明 V008R006C009B0014 Oracle/enable_ci=on 目录契约","0A000");
                String wal=rows.getString(3); int slots=rows.getInt(4); boolean missing=rows.wasNull(); int senders=rows.getInt(5); missing|=rows.wasNull();
                boolean administrator=rows.getBoolean(6); missing|=rows.wasNull(); boolean replication=rows.getBoolean(7);
                if (missing || rows.wasNull() || wal==null || slots<0 || senders<0 || rows.next()) throw new SQLException("Kingbase CDC 配置及角色证据不完整");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.CdcReadinessDiagnostic("logical".equals(wal)&&slots>0&&senders>0,administrator||replication);
            }
        }
    }

    /** Kingbase 原生 pg_catalog 注释机制；不据此开启其他兼容模式的写能力。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection,namespace,1,timeoutSeconds); diagnosticBounds(connection,table,1,timeoutSeconds);
        String sql="SELECT CASE WHEN pg_catalog.obj_description(c.oid,'pg_class') IS NULL THEN 0 ELSE 1 END,"
                + "COUNT(a.attnum),COUNT(pg_catalog.col_description(c.oid,a.attnum)) "
                + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace "
                + "LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped "
                + "WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('r','p') GROUP BY c.oid";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setString(1,namespace); statement.setString(2,table); statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Kingbase 目标表不存在或不可见","42S02");
                int tableComments=rows.getInt(1); int columns=rows.getInt(2); int comments=rows.getInt(3);
                if (rows.wasNull() || columns<1 || comments<0 || comments>columns || tableComments<0 || tableComments>1 || rows.next()) throw new SQLException("Kingbase 注释目录证据不完整或不唯一");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(columns,tableComments==1,comments);
            }
        }
    }


    /** Kingbase 独立只读入口；不要求开启 DDL，也不继承 PostgreSQL 诊断实现。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT pg_catalog.has_schema_privilege(current_user,n.oid,'USAGE') FROM pg_catalog.pg_namespace n WHERE n.nspname=?")) {
            statement.setString(1, namespace); statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Kingbase 命名空间不存在", "3F000");
                if (!rows.getBoolean(1) || rows.wasNull()) throw new SQLException("Kingbase schema USAGE 被拒绝", "42501");
            }
        }
    }

    /** 使用本产品公开角色目录，不读密码目录、不推断业务表权限。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        probeNamespace(connection, namespace, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT rolname FROM pg_catalog.pg_roles WHERE rolname=current_user")) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) throw new SQLException("Kingbase 当前账号目录证据缺失");
            }
        }
    }

    /** 仅基本表，固定原生 SQL，目录规模超过上限时不返回被截断的成功结果。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String namespace, int maxTables, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, maxTables, timeoutSeconds);
        var result = new ArrayList<TableDescription>();
        try (var statement = connection.prepareStatement("SELECT c.relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relkind IN ('r','p') ORDER BY c.relname LIMIT ?")) {
            statement.setString(1, namespace); statement.setInt(2, maxTables + 1); statement.setMaxRows(maxTables + 1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == maxTables) throw new SQLException("Kingbase 目录超过上限，拒绝截断", "54000");
                    var table = new TableDescription(); table.setSchemaName(namespace); table.setTableName(rows.getString(1)); table.setTableType("TABLE"); result.add(table);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void diagnosticBounds(Connection connection, String namespace, int maxTables, int timeoutSeconds) {
        if (connection == null || namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || maxTables < 1 || maxTables > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("Kingbase 诊断参数必须完整且有界");
    }

    private static final String SHOW_CREATE_VIEW_SQL =
            "SELECT pg_get_viewdef((select pg_class.oid from pg_catalog.pg_class \n"
                    + "join pg_catalog.pg_namespace on pg_class.relnamespace = pg_namespace.oid \n"
                    + "where pg_namespace.nspname='%s' and pg_class.relname ='%s'),true) ";

    /**
     * Kingbase 的 Oracle 模式对非分区表调用 pg_get_partkeydef 返回 NULL，但其
     * Oracle 风格拼接在右操作数为 NULL 时仍保留左操作数，因此通用的
     * coalesce 写法仍会生成 "PARTITION BY ;"；显式 CASE 保持 PG16 语义。
     */
    private static final String CREATE_TABLE_SQL_TPL = PostgresqlConst.CREATE_TABLE_SQL_TPL.replace(
            "coalesce(E'\\nPARTITION BY '||pg_get_partkeydef(tabobj.oid), '')",
            "case when pg_get_partkeydef(tabobj.oid) is null then '' else E'\\nPARTITION BY '||pg_get_partkeydef(tabobj.oid) end");

    /**
     * 已验证的 KingbaseES V008R006C009B0014 配置：{@code database_mode=oracle}、{@code enable_ci=on}。
     * pg、mysql 以及其他 Oracle 模式版本均不开放。
     */
    private static final String VERIFIED_VERSION = "KingbaseES V008R006C009B0014 on x86_64-pc-linux-gnu, "
            + "compiled by gcc (GCC) 4.8.5 20150623 (Red Hat 4.8.5-28), 64-bit";

    private static final String MODE_SQL = "SELECT pg_catalog.version(), pg_catalog.current_setting('database_mode'), "
            + "pg_catalog.current_setting('enable_ci')";

    private static final String CREATE_ASSEMBLY = "cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils";

    private volatile Boolean verifiedOracle;

    /**
     * 初始化 {@code DatabaseKingbaseImpl}及其父类状态。
     */
    public DatabaseKingbaseImpl() {
        super(ProductTypeEnum.KINGBASE.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.KINGBASE;
    }

    /** DDL 文本取决于模式；没有连接的调用方无法证明使用 Oracle 模式。 */
    @Override
    public boolean requiresDialectConnection() {
        return true;
    }

    /**
     * 核对实际连接产品和兼容模式，准备后续方言操作。
     */
    @Override
    public synchronized void prepareDialect(Connection connection) throws SQLException {
        boolean oracle = verifiedOracleConnection(connection);
        if (verifiedOracle != null && verifiedOracle != oracle) {
            throw new IllegalStateException("Kingbase compatibility mode changed for this metadata instance");
        }
        if (!oracle) {
            throw new SQLException("Kingbase DDL requires the verified oracle mode");
        }
        verifiedOracle = Boolean.TRUE;
    }

    /**
     * 获取添加字段的sql。
     */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        return KingbaseAddColumn.sql(this, targetSchemaName, targetTableName, targetColumn);
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.KINGBASE) {
            return super.queryRelationFingerprint(connection, reference);
        }
        return KingbaseRelationIdentity.fingerprint(connection, reference);
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String alias, String column) {
        if (getDatabaseType() != ProductTypeEnum.KINGBASE) {
            return super.integerKeyProjection(alias, column);
        }
        return integerProjection(alias, column, 21);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.KINGBASE) {
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
        String field = (alias == null ? "" : RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.KINGBASE,
                alias) + ".")
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.KINGBASE, column);
        String text = "CAST(" + field + " AS pg_catalog.text)";
        return new IntegerProjection("CASE WHEN pg_catalog.octet_length(" + text + ") <= " + maxBytes
                + " THEN " + text + " ELSE NULL END", "NULLIF(" + field + ", " + field + ")");
    }

    /**
     * 此版本的 NULLIF 保留 int2、int4、int8；tinyint 和 numeric 仍保留各自类型，
     * 所以两者都不能作为整数类型的证明。
     */
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

    /**
     * 获取数据库的表全名。
     */
    @Override
    public String getQuotedSchemaTableCombination(String schemaName, String tableName) {
        if (assemblingCreateTable()) {
            requirePreparedOracleMode();
        }
        return super.getQuotedSchemaTableCombination(schemaName, tableName);
    }

    /**
     * 在建表语句中追加主键定义片段 ", PRIMARY KEY (...)"；主键列表为空时不追加。
     */
    @Override
    public void appendPrimaryKeyForCreateTableSql(StringBuilder builder, List<String> primaryKeys) {
        requirePreparedOracleMode();
        super.appendPrimaryKeyForCreateTableSql(builder, primaryKeys);
    }

    /**
     * 建表语句后置钩子：在字段定义之后追加方言相关内容（表注释、存储引擎/属性、分布键等）， 默认无操作，由需要的子类覆盖。
     */
    @Override
    public void postAppendCreateTableSql(StringBuilder builder, String tblComment, List<String> primaryKeys,
            Map<String, String> tblProperties, String hashFiled) {
        requirePreparedOracleMode();
        super.postAppendCreateTableSql(builder, tblComment, primaryKeys, tblProperties, hashFiled);
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        try {
            requireVerifiedOracleConnection(connection);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        String sql = CREATE_TABLE_SQL_TPL
                .replace(PostgresqlConst.TPL_KEY_SCHEMA, schemaName)
                .replace(PostgresqlConst.TPL_KEY_TABLE, tableName);
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
     * 获取指定视图表的DDL语句。
     */
    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        try {
            requireVerifiedOracleConnection(connection);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
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
        String querySQL = String.format(" %s LIMIT 0 ", sql.replace(";", ""));
        return this.getSelectSqlColumnMeta(connection, querySQL);
    }

    /**
     * 生成用于探测表字段的查询 SQL。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        return String.format("SELECT * FROM \"%s\".\"%s\" WHERE 1=2", schemaName, tableName);
    }

    /**
     * 把用户 SQL 包装为低成本的合法性探测语句（如 explain、外层包 WHERE 1=2 等），由子类按方言实现。
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        return String.format("explain %s", sql.replace(";", ""));
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
        // 可空 ADD COLUMN 复用此文本且不感知 database_mode；组装 CREATE 时则必须确认模式。
        if (assemblingCreateTable()) {
            requirePreparedOracleMode();
        }
        String fieldname = v.getName();
        int length = v.getLength();
        int precision = v.getPrecision();
        int type = v.getType();

        String retval = " \"" + fieldname + "\"   ";
        boolean canHaveDefaultValue = true;
        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
                retval += "TIMESTAMP";
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
            case ColumnMetaData.TYPE_INTEGER:
            case ColumnMetaData.TYPE_BIGNUMBER:
                if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                    if (useAutoInc) {
                        retval += "BIGSERIAL";
                    } else {
                        retval += "BIGINT";
                    }
                } else {
                    if (length > 0) {
                        if (precision > 0 || length > 18) {
                            if ((length + precision) > 0 && precision > 0) {
                                // Numeric(Precision, Scale)：Precision 为总位数，Scale 为小数位数
                                retval += "NUMERIC(" + (length + precision) + ", " + precision + ")";
                            } else {
                                retval += "DOUBLE PRECISION";
                            }
                        } else {
                            if (length > 9) {
                                retval += "BIGINT";
                            } else {
                                if (length < 5) {
                                    retval += "SMALLINT";
                                } else {
                                    retval += "INTEGER";
                                }
                            }
                        }

                    } else {
                        retval += "DOUBLE PRECISION";
                    }
                }
                break;
            case ColumnMetaData.TYPE_STRING:
                if (length < 1 || length >= AbstractDatabase.CLOB_LENGTH) {
                    retval += "TEXT";
                    canHaveDefaultValue = false;
                } else if (length <= 2000) {
                    retval += "VARCHAR(" + length + ")";
                } else {
                    if (null != pks && pks.contains(fieldname)) {
                        retval += "VARCHAR(" + length + ")";
                    } else {
                        retval += "TEXT";
                        canHaveDefaultValue = false;
                    }
                }
                break;
            case ColumnMetaData.TYPE_BINARY:
                retval += "BYTEA";
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
                retval += " DEFAULT CURRENT_TIMESTAMP";
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
     * 获取表和字段的注释定义。
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td,
            List<ColumnDescription> cds) {
        requirePreparedOracleMode();
        List<String> results = new ArrayList<>();
        String relation = PostgreSqlUtils.getFullyQualifiedTableName(td.getSchemaName(), td.getTableName(), true);
        if (StringUtils.isNotBlank(td.getRemarks())) {
            results.add("COMMENT ON TABLE " + relation + " IS " + PostgreSqlUtils.quoteLiteral(td.getRemarks()) + " ");
        }

        for (ColumnDescription cd : cds) {
            if (StringUtils.isNotBlank(cd.getRemarks())) {
                results.add("COMMENT ON COLUMN " + relation + "." + PostgreSqlUtils.quoteIdentifier(cd.getFieldName())
                        + " IS " + PostgreSqlUtils.quoteLiteral(cd.getRemarks()) + " ");
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
                if (length < 1 || length >= AbstractDatabase.CLOB_LENGTH) {
                    canCreateIndex = false;
                } else if (length > 2000) {
                    canCreateIndex = false;
                }
                break;
            default:
                canCreateIndex = false;
                break;
        }

        return canCreateIndex;
    }

    /**
     * 拒绝当前实现未支持的 {@code requirePreparedOracleMode} 操作。
     */
    private void requirePreparedOracleMode() {
        if (!Boolean.TRUE.equals(verifiedOracle)) {
            throw new UnsupportedOperationException("Kingbase DDL requires the verified oracle mode");
        }
    }

    /**
     * 校验 Oracle 连接与当前已验证的驱动及产品条件一致。
     */
    private static void requireVerifiedOracleConnection(Connection connection) throws SQLException {
        if (!verifiedOracleConnection(connection)) {
            throw new SQLException("Kingbase DDL requires the verified oracle mode");
        }
    }

    /** 仅对固定的 V008R006C009B0014、Oracle 模式且 enable_ci=on 的服务器返回 true，不允许 pg 或 mysql 模式。 */
    private static boolean verifiedOracleConnection(Connection connection) throws SQLException {
        if (connection == null) {
            return false;
        }
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(2);
            try (ResultSet rows = statement.executeQuery(MODE_SQL)) {
                if (!rows.next()) {
                    return false;
                }
                String version = rows.getString(1);
                String mode = rows.getString(2);
                String ci = rows.getString(3);
                return !rows.next() && VERIFIED_VERSION.equals(version) && "oracle".equals(mode) && "on".equals(ci);
            }
        }
    }

    /**
     * 按表属性及列定义组装完整建表 SQL。
     */
    private static boolean assemblingCreateTable() {
        return StackWalker.getInstance().walk(frames -> frames.limit(24)
                .anyMatch(frame -> CREATE_ASSEMBLY.equals(frame.getClassName())));
    }

}
