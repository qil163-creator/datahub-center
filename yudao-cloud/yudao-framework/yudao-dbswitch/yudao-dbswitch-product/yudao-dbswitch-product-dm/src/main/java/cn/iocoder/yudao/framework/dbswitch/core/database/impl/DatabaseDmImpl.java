package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmDriverCompatibility;
import cn.iocoder.yudao.framework.dbswitch.product.dm.DmAddColumn;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 支持DM数据库的元信息实现
 *
 * @author jrl
 */
public class DatabaseDmImpl extends AbstractDatabase implements IDatabaseInterface {

    /** DM 的 ALL_* 注释目录只证明目录读取，不证明 schema 的业务对象访问权。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection,namespace,1,timeoutSeconds); diagnosticBounds(connection,table,1,timeoutSeconds);
        String sql="SELECT CASE WHEN t.COMMENTS IS NULL THEN 0 ELSE 1 END,COUNT(c.COLUMN_NAME),COUNT(m.COMMENTS) "
                + "FROM ALL_TAB_COMMENTS t JOIN ALL_TABLES b ON b.OWNER=t.OWNER AND b.TABLE_NAME=t.TABLE_NAME "
                + "LEFT JOIN ALL_TAB_COLUMNS c ON c.OWNER=t.OWNER AND c.TABLE_NAME=t.TABLE_NAME "
                + "LEFT JOIN ALL_COL_COMMENTS m ON m.OWNER=c.OWNER AND m.TABLE_NAME=c.TABLE_NAME AND m.COLUMN_NAME=c.COLUMN_NAME "
                + "WHERE t.OWNER=? AND t.TABLE_NAME=? GROUP BY t.OWNER,t.TABLE_NAME,t.COMMENTS";
        try (var statement=connection.prepareStatement(sql)) {
            statement.setString(1,namespace); statement.setString(2,table); statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows=statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("DM 目标表不存在或不可见","42S02");
                int tableComments=rows.getInt(1); int columns=rows.getInt(2); int comments=rows.getInt(3);
                if (rows.wasNull() || columns<1 || comments<0 || comments>columns || tableComments<0 || tableComments>1 || rows.next()) throw new SQLException("DM 注释目录证据不完整或不唯一");
                return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(columns,tableComments==1,comments);
            }
        }
    }


    /** DM8 不以改变 CURRENT_SCHEMA 伪装授权检查，实际验证可见基本表的零行 SELECT。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        String table;
        try (var statement = connection.prepareStatement("SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER=? AND ROWNUM<=1")) {
            statement.setString(1, namespace); statement.setMaxRows(1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLFeatureNotSupportedException("DM8 schema 无可见基本表，无法只读证明对象访问权限", "0A000");
                table = rows.getString(1);
            }
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(1);
            try (var ignored = statement.executeQuery("SELECT * FROM " + DmSqlNames.relation(namespace, table) + " WHERE 1=0")) {
                // 仅检查解析和 SELECT 权限，不读取业务数据，也不改变 schema 或事务。
            }
        } catch (SQLException failure) {
            if (java.util.Set.of("42501", "42000", "3F000", "42P01", "42S02").contains(java.util.Objects.toString(failure.getSQLState(), ""))) {
                throw new SQLFeatureNotSupportedException(
                        "DM8 单个样本不可读或已变化，不能据此判定整个 schema 无权访问；scope 未确认", "0A000", failure);
            }
            throw failure;
        }
    }

    /** DM8 当前会话权限目录的实际读取，不推断普通对象的写权限。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        probeNamespace(connection, namespace, timeoutSeconds);
        try (var statement = connection.prepareStatement("SELECT USER,(SELECT COUNT(*) FROM SESSION_PRIVS) FROM DUAL")) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(2);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) throw new SQLException("DM8 当前账号权限目录证据缺失");
            }
        }
    }

    /** DM8 原生目录，ROWNUM 和 JDBC 最大行数同时有界，拒绝溢出而非返回截断结果。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String namespace, int maxTables, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, maxTables, timeoutSeconds);
        var result = new ArrayList<TableDescription>();
        try (var statement = connection.prepareStatement("SELECT TABLE_NAME FROM (SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER=? ORDER BY TABLE_NAME) WHERE ROWNUM<=?")) {
            statement.setString(1, namespace); statement.setInt(2, maxTables + 1); statement.setMaxRows(maxTables + 1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == maxTables) throw new SQLException("DM8 目录超过上限，拒绝截断", "54000");
                    var table = new TableDescription(); table.setSchemaName(namespace); table.setTableName(rows.getString(1)); table.setTableType("TABLE"); result.add(table);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void diagnosticBounds(Connection connection, String namespace, int maxTables, int timeoutSeconds) {
        if (connection == null || namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || maxTables < 1 || maxTables > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("DM8 诊断参数必须完整且有界");
    }

    /**
     * 初始化 {@code DatabaseDmImpl}及其父类状态。
     */
    public DatabaseDmImpl() {
        super(ProductTypeEnum.DM.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.DM;
    }

    /**
     * 获取数据库的表全名。
     */
    @Override
    public String getQuotedSchemaTableCombination(String schemaName, String tableName) {
        return DmSqlNames.relation(schemaName, tableName);
    }

    /**
     * 主键列转换为逗号分隔的字符串。
     */
    @Override
    public String getPrimaryKeyAsString(List<String> keys) {
        return DmSqlNames.columns(keys);
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.DM) {
            throw new SQLFeatureNotSupportedException("Relation identity is not verified for " + getDatabaseType());
        }
        return DmRelationIdentity.fingerprint(connection, reference);
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String alias, String column) {
        if (getDatabaseType() != ProductTypeEnum.DM) {
            throw new UnsupportedOperationException("Bounded integer keys are not verified for " + getDatabaseType());
        }
        return integerProjection(alias, column, 21);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.DM) {
            throw new UnsupportedOperationException("Bounded integer keys are not verified for " + getDatabaseType());
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
        String field = (alias == null ? "" : RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DM,
                alias) + ".")
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DM, column);
        String text = "CAST(" + field + " AS VARCHAR(40))";
        return new IntegerProjection(
                "CASE WHEN LENGTH(" + text + ") <= " + maxBytes + " THEN " + text + " ELSE NULL END",
                "NULLIF(" + field + ", " + field + ")");
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
            // 该驱动中的 NULLIF 会将 TINYINT 和 SMALLINT 扩宽为 INTEGER；NUMBER/DECIMAL 仍属非整数。
            return switch (nativeType) {
                case "INTEGER" -> jdbc == Types.INTEGER ? jdbc : 0;
                case "BIGINT" -> jdbc == Types.BIGINT ? jdbc : 0;
                default -> 0;
            };
        }
    }

    /**
     * 获取指定模式Schema内的所有表列表。
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        List<TableDescription> ret = new ArrayList<>();
        String sql = "SELECT T.TABLE_NAME,U.COMMENTS AS TABLE_COMMENT FROM ALL_TABLES T "
                + "LEFT JOIN ALL_TAB_COMMENTS U ON T.OWNER=U.OWNER AND T.TABLE_NAME=U.TABLE_NAME "
                + "WHERE T.OWNER=? ORDER BY T.TABLE_NAME";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableDescription td = new TableDescription();
                    td.setSchemaName(schemaName);
                    td.setTableName(rs.getString("TABLE_NAME"));
                    td.setRemarks(rs.getString("TABLE_COMMENT"));
                    td.setTableType("TABLE");
                    ret.add(td);
                }
            }
            return ret;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        return objectDDL(connection, "TABLE", schemaName, tableName);
    }

    /**
     * 获取指定视图表的DDL语句。
     */
    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        return objectDDL(connection, "VIEW", schemaName, tableName);
    }

    /**
     * 通过原生元数据接口读取当前对象的 DDL。
     */
    private String objectDDL(Connection connection, String type, String schema, String name) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DBMS_METADATA.GET_DDL(?,?,?) FROM DUAL")) {
            statement.setString(1, type);
            statement.setString(2, name);
            statement.setString(3, schema);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
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
        try {
            DmDriverCompatibility.require(connection);
        } catch (SQLException failure) {
            throw new IllegalStateException("DM migration requires a validated driver bundle");
        }
        String querySQL = String.format(
                "SELECT * from (%s) tmp where ROWNUM<=1 ",
                withoutTerminator(sql));
        List<ColumnDescription> columns = this.getSelectSqlColumnMeta(connection, querySQL);
        for (ColumnDescription column : columns) {
            if (column.getFieldType() == Types.TIME) {
                // 历史通用 TIME 模型丢弃小数位数，此处保留 JDBC 事实。
                column.setValType(ColumnMetaData.TYPE_TIME);
                column.setLength(column.getScaleSize());
                column.setPrecision(0);
            }
        }
        return columns;
    }

    /**
     * 生成用于探测表字段的查询 SQL。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        return "SELECT * FROM " + DmSqlNames.relation(schemaName, tableName) + " WHERE 1=2";
    }

    /**
     * 把用户 SQL 包装为低成本的合法性探测语句（如 explain、外层包 WHERE 1=2 等），由子类按方言实现。
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        return "explain " + withoutTerminator(sql);
    }

    /**
     * 移除 SQL 末尾的单个语句终止符。
     */
    private static String withoutTerminator(String sql) {
        String query = java.util.Objects.requireNonNull(sql, "sql").strip();
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

        StringBuilder retval = new StringBuilder(128);
        retval.append(' ').append(DmSqlNames.quote(fieldname)).append("    ");
        boolean canHaveDefaultValue = true;
        int type = v.getType();
        // 即使通用值模型使用 Long，JDBC DECIMAL(p,0) 仍保持小数值域。
        if (v.getSqlType() == Types.DECIMAL || v.getSqlType() == Types.NUMERIC) {
            type = ColumnMetaData.TYPE_BIGNUMBER;
        }
        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
                if (v.getSqlType() == Types.TIMESTAMP_WITH_TIMEZONE) {
                    throw new UnsupportedOperationException("DM timezone timestamp mapping needs an explicit strategy");
                }
                if (length < 0 || length > 9) {
                    throw new IllegalArgumentException("DM timestamp scale must be 0..9");
                }
                retval.append("TIMESTAMP(").append(length).append(')');
                break;
            case ColumnMetaData.TYPE_TIME:
                if (v.getSqlType() == Types.TIME_WITH_TIMEZONE || length < 0 || length > 6) {
                    throw new UnsupportedOperationException(
                            "DM time mapping requires known fractional scale and no timezone");
                }
                retval.append("TIME(").append(length).append(')');
                break;
            case ColumnMetaData.TYPE_DATE:
                retval.append("DATE");
                break;
            case ColumnMetaData.TYPE_BOOLEAN:
                retval.append("BIT");
                break;
            case ColumnMetaData.TYPE_NUMBER:
            case ColumnMetaData.TYPE_BIGNUMBER:
                if (v.getSqlType() == Types.DOUBLE || v.getSqlType() == Types.FLOAT || v.getSqlType() == Types.REAL) {
                    retval.append(v.getSqlType() == Types.REAL ? "REAL" : "DOUBLE PRECISION");
                    break;
                }
                if (length < 1 || length > 38 || precision < 0 || precision > length) {
                    throw new IllegalArgumentException(
                            "DM_NUMERIC_PRECISION_UNVERIFIED: explicit precision 1..38 and scale 0..precision are required; narrowing is forbidden");
                }
                // 小数主键仍保持小数类型；BIGINT 会丢弃其小数部分。
                retval.append(v.getSqlType() == Types.DECIMAL ? "DECIMAL(" : "NUMERIC(")
                        .append(length).append(", ").append(precision).append(')');
                break;
            case ColumnMetaData.TYPE_INTEGER:
                retval.append("BIGINT");
                break;
            case ColumnMetaData.TYPE_STRING:
                if (length <= 0) {
                    throw new IllegalArgumentException(
                            "DM_STRING_LENGTH_UNKNOWN: a positive character length or an explicit LOB mapping is required");
                }
                if (null != pks && pks.contains(fieldname)) {
                    if (length >= 2048) {
                        throw new IllegalArgumentException(
                                "DM_KEY_LENGTH_UNVERIFIED: key byte size depends on character set, page size and index limits; automatic shortening is forbidden");
                    }
                    retval.append("VARCHAR(").append(length).append(")");
                } else if (length < 2048) {
                    retval.append("VARCHAR(").append(length).append(")");
                } else {
                    retval.append("TEXT");
                }
                break;
            case ColumnMetaData.TYPE_BINARY:
                retval.append("BLOB");
                canHaveDefaultValue = false;
                break;
            default:
                retval.append("CLOB");
                canHaveDefaultValue = false;
                break;
        }

        if (canHaveDefaultValue && v.getDefaultValue() != null && !"null".equals(v.getDefaultValue()) && !"NULL".equals(
                v.getDefaultValue())) {
            if (type != ColumnMetaData.TYPE_TIMESTAMP && type != ColumnMetaData.TYPE_TIME && type != ColumnMetaData.TYPE_DATE) {
                if (v.getDefaultValue().startsWith("'")) {
                    retval.append(" DEFAULT ").append(v.getDefaultValue());
                } else {
                    retval.append(" DEFAULT '").append(v.getDefaultValue()).append("'");
                }
            } /*else {
				retval += " DEFAULT DEFAULT SYSDATE";
			}*/
        }

        if (!v.isNullable()) {
            retval.append(" NOT NULL");
        }

        if (addCr) {
            retval.append(Const.CR);
        }

        return retval.toString();
    }

    /**
     * 获取表和字段的注释定义。
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td,
            List<ColumnDescription> cds) {
        List<String> results = new ArrayList<>();
        if (StringUtils.isNotBlank(td.getRemarks())) {
            results.add("COMMENT ON TABLE " + DmSqlNames.relation(td.getSchemaName(), td.getTableName())
                    + " IS '" + td.getRemarks().replace("'", "''") + "'");
        }

        for (ColumnDescription cd : cds) {
            if (StringUtils.isNotBlank(cd.getRemarks())) {
                results.add("COMMENT ON COLUMN " + DmSqlNames.relation(td.getSchemaName(), td.getTableName())
                        + '.' + DmSqlNames.quote(cd.getFieldName()) + " IS '" + cd.getRemarks()
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
            case ColumnMetaData.TYPE_INTEGER:
                break;
            case ColumnMetaData.TYPE_NUMBER:
            case ColumnMetaData.TYPE_BIGNUMBER:
                canCreateIndex = length > 0 && length <= 38 && v.getPrecision() >= 0 && v.getPrecision() <= length;
                break;
            case ColumnMetaData.TYPE_STRING:
                if (length <= 0 || length >= 2048) {
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

    /** 仅支持普通可空列；现有 DM DDL 使用 ADD，不使用 ADD COLUMN。 */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        try {
            return DmAddColumn.sql(targetSchemaName, targetTableName, targetColumn,
                    meta -> getFieldDefinition(meta, null, false, false, false));
        } catch (SQLFeatureNotSupportedException unsupported) {
            sneakyThrow(unsupported);
            throw new IllegalStateException(unsupported);
        }
    }

    /**
     * 按原始异常类型重新抛出当前异常。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable failure) throws E {
        throw (E) failure;
    }

}
