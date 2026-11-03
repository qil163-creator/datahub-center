package cn.iocoder.yudao.framework.dbswitch.core.database.impl;


import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.OscarRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.product.oscar.OscarSqlNames;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 支持神通数据库的元信息实现
 *
 * @author tang
 */
public class DatabaseOscarImpl extends AbstractDatabase implements IDatabaseInterface {

    /** 神通 INFO_SCHEM 原生目录只用于定位基本表，实际零行 SELECT 才能验证访问权限。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        String table;
        try (var statement = connection.prepareStatement("SELECT c.RELNAME FROM INFO_SCHEM.SYS_CLASS c JOIN INFO_SCHEM.SYS_NAMESPACE n ON n.OID=c.RELNAMESPACE WHERE n.NSPNAME=? AND c.RELKIND='r' ORDER BY c.RELNAME LIMIT 1")) {
            statement.setString(1, namespace); statement.setMaxRows(1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new java.sql.SQLFeatureNotSupportedException("神通 schema 无可见基本表，尚无独立 USAGE 检查契约", "0A000");
                table = rows.getString(1);
            }
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds); statement.setMaxRows(1);
            try (var ignored = statement.executeQuery("SELECT * FROM " + OscarSqlNames.relation(namespace, table) + " WHERE 1=0")) {
                // 不读取业务行，不改变命名空间，不调用 Oracle 或 PostgreSQL 权限函数。
            }
        }
    }

    /** 原生账号授权目录尚未形成可靠契约，不能从 USER 或 namespace 可见性伪造权限检查。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, 1, timeoutSeconds);
        throw new java.sql.SQLFeatureNotSupportedException("神通账号角色授权目录尚无可靠原生契约；不以 USER 函数或其他产品目录替代", "0A000");
    }

    /** 神通原生基本表目录，不使用 PostgreSQL 的 pg_class 或 Oracle 的 ALL_TABLES。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String namespace, int maxTables, int timeoutSeconds) throws SQLException {
        diagnosticBounds(connection, namespace, maxTables, timeoutSeconds);
        var result = new java.util.ArrayList<TableDescription>();
        try (var statement = connection.prepareStatement("SELECT c.RELNAME FROM INFO_SCHEM.SYS_CLASS c JOIN INFO_SCHEM.SYS_NAMESPACE n ON n.OID=c.RELNAMESPACE WHERE n.NSPNAME=? AND c.RELKIND='r' ORDER BY c.RELNAME LIMIT ?")) {
            statement.setString(1, namespace); statement.setInt(2, maxTables + 1); statement.setMaxRows(maxTables + 1); statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == maxTables) throw new SQLException("神通目录超过上限，拒绝截断", "54000");
                    var table = new TableDescription(); table.setSchemaName(namespace); table.setTableName(rows.getString(1)); table.setTableType("TABLE"); result.add(table);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void diagnosticBounds(Connection connection, String namespace, int maxTables, int timeoutSeconds) {
        if (connection == null || namespace == null || namespace.isBlank() || namespace.indexOf('\0') >= 0 || maxTables < 1 || maxTables > 10000 || timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("神通诊断参数必须完整且有界");
    }

    private static final String SHOW_CREATE_TABLE_SQL =
            "SELECT \"SYS_GET_TABLEDEF\" FROM \"V_SYS_TABLE\" WHERE \"SCHEMANAME\"= ? AND \"TABLENAME\"= ? ";

    private static final String SHOW_CREATE_VIEW_SQL =
            "SELECT \"DEFINITION\" FROM \"V_SYS_VIEWS\" WHERE \"SCHEMANAME\"= ? AND \"VIEWNAME\"= ?";

    /**
     * 初始化 {@code DatabaseOscarImpl}及其父类状态。
     */
    public DatabaseOscarImpl() {
        super(ProductTypeEnum.OSCAR.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.OSCAR;
    }

    /**
     * 引用单个标识符：引号字符来自 {@link ProductTypeEnum#getQuote()}，并转义嵌入的引号。 引号为空（如 OceanBase 枚举默认值）时回退为反引号，子类可覆盖。
     */
    @Override
    public String quoteIdentifier(String name) {
        return OscarSqlNames.quote(name);
    }

    /**
     * 获取数据库的表全名。
     */
    @Override
    public String getQuotedSchemaTableCombination(String schema, String table) {
        return OscarSqlNames.relation(schema, table);
    }

    /**
     * 主键列转换为逗号分隔的字符串。
     */
    @Override
    public String getPrimaryKeyAsString(List<String> keys) {
        return OscarSqlNames.fields(keys);
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.OSCAR) {
            return super.queryRelationFingerprint(connection, reference);
        }
        return OscarRelationIdentity.fingerprint(connection, reference);
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String alias, String column) {
        if (getDatabaseType() != ProductTypeEnum.OSCAR) {
            return super.integerKeyProjection(alias, column);
        }
        return integerProjection(alias, column, 21);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.OSCAR) {
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
        String field = (alias == null ? "" : RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OSCAR,
                alias) + ".")
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OSCAR, column);
        String text = "CAST(" + field + " AS VARCHAR(40))";
        return new IntegerProjection("CASE WHEN LENGTHB(" + text + ") <= " + maxBytes
                + " THEN " + text + " ELSE NULL END", "NULLIF(" + field + ", " + field + ")");
    }

    /**
     * NULLIF 保留 int2、int4 和 bigint。int1 和 numeric 保持自身类型，因此二者均不能作为整数验证信息。
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
                case "bigint" -> jdbc == Types.BIGINT ? jdbc : 0;
                default -> 0;
            };
        }
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        String sql = String.format(SHOW_CREATE_TABLE_SQL, tableName, schemaName);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs != null && rs.next()) {
                    return rs.getString(1);
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
        String sql = String.format(SHOW_CREATE_VIEW_SQL, tableName, schemaName);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs != null && rs.next()) {
                    return rs.getString(1);
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
        String querySQL = String.format(
                "SELECT * from (%s) tmp LIMIT 0 ",
                sql.replaceFirst(";\\s*$", ""));
        return this.getSelectSqlColumnMeta(connection, querySQL);
    }

    /**
     * 生成用于探测表字段的查询 SQL。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        return "SELECT * FROM " + OscarSqlNames.relation(schemaName, tableName);
    }

    /**
     * 把用户 SQL 包装为低成本的合法性探测语句（如 explain、外层包 WHERE 1=2 等），由子类按方言实现。
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        return String.format("explain %s", sql.replaceFirst(";\\s*$", ""));
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
        retval.append(" ").append(quoteIdentifier(fieldname)).append("    ");

        // JDBC precision 是总位数，包括 scale。规范化后的
        // BIGNUMBER 类别还包含精确十进制数；渲染为 BIGINT 会丢失数据。
        if (v.getSqlType() == Types.NUMERIC || v.getSqlType() == Types.DECIMAL) {
            if (length < 1 || length > 1000 || precision < 0 || precision > 38 || precision > length) {
                throw new UnsupportedOperationException(
                        "Oscar decimal precision/scale is outside the verified native domain");
            }
            if (length == 1000 && precision == 38) {
                retval.append("NUMBER");
            } else {
                retval.append("NUMERIC(").append(length).append(",").append(precision).append(")");
            }
            if (addCr) {
                retval.append(Const.CR);
            }
            return retval.toString();
        }

        int type = v.getType();
        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
            case ColumnMetaData.TYPE_TIME:
                retval.append("TIMESTAMP");
                break;
            case ColumnMetaData.TYPE_DATE:
                retval.append("DATE");
                break;
            case ColumnMetaData.TYPE_BOOLEAN:
                retval.append("BIT");
                break;
            case ColumnMetaData.TYPE_BIGNUMBER:
            case ColumnMetaData.TYPE_INTEGER:
                retval.append("BIGINT");
                break;
            case ColumnMetaData.TYPE_NUMBER:
                if (null != pks && !pks.isEmpty() && pks.contains(fieldname)) {
                    retval.append("BIGINT");
                } else {
                    if (length > 0) {
                        if (precision > 0 || length > 18) {
                            if ((length + precision) > 0 && precision > 0) {
                                // Numeric(Precision, Scale)：Precision = 总长度；Scale = 小数位数
                                retval.append("NUMERIC(").append(length + precision).append(", ").append(precision)
                                        .append(")");
                            } else {
                                retval.append("DOUBLE PRECISION");
                            }
                        } else {
                            if (length > 9) {
                                retval.append("BIGINT");
                            } else {
                                if (length < 5) {
                                    retval.append("SMALLINT");
                                } else {
                                    retval.append("INTEGER");
                                }
                            }
                        }

                    } else {
                        retval.append("DOUBLE PRECISION");
                    }
                }
                break;
            case ColumnMetaData.TYPE_STRING:
                if (2 * length >= AbstractDatabase.CLOB_LENGTH) {
                    retval.append("TEXT");
                } else {
                    if (length == 1) {
                        retval.append("VARCHAR(2)");
                    } else if (length > 0 && length < 2048) {
                        retval.append("VARCHAR(").append(2 * length).append(')');
                    } else {
                        retval.append("TEXT");
                    }
                }
                break;
            case ColumnMetaData.TYPE_BINARY:
                retval.append("BLOB");
                break;
            default:
                retval.append("TEXT");
                break;
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
        String table = commentIdentifier(td.getSchemaName()) + "." + commentIdentifier(td.getTableName());
        if (StringUtils.isNotBlank(td.getRemarks())) {
            results.add("COMMENT ON TABLE " + table + " IS " + commentLiteral(td.getRemarks()));
        }

        for (ColumnDescription cd : cds) {
            if (StringUtils.isNotBlank(cd.getRemarks())) {
                results.add("COMMENT ON COLUMN " + table + "."
                        + commentIdentifier(cd.getFieldName())
                        + " IS " + commentLiteral(cd.getRemarks()));
            }
        }

        return results;
    }

    /**
     * 按当前方言转义并生成注释文本字面量。
     */
    private static String commentLiteral(String value) {
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Oscar comment contains a null character");
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * 校验当前注释对象名称并按方言加引号。
     */
    private String commentIdentifier(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Oscar comment requires a physical identifier");
        }
        return quoteIdentifier(name);
    }

    /**
     * 可空、无默认值加列。神通 V8.0.251217 实库接受
     * {@code ALTER TABLE "schema"."table" ADD ("column" type)}，不是 ADD COLUMN。
     * 标识符走 {@link RestrictedSelectGuard}，未加引号的普通名先折叠为大写。
     */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        try {
            return nullableAddColumn(targetSchemaName, targetTableName, targetColumn);
        } catch (SQLFeatureNotSupportedException unsupported) {
            sneakyThrow(unsupported);
            throw new IllegalStateException(unsupported);
        }
    }

    /**
     * 生成当前产品受支持的可空增列定义。
     */
    private String nullableAddColumn(String schema, String table, ColumnDescription column)
            throws SQLFeatureNotSupportedException {
        if (schema == null || table == null || column == null || column.getFieldName() == null
                || column.getFieldName().isBlank()) {
            throw new IllegalArgumentException("Oscar add column requires a schema, table and physical column");
        }
        if (column.getDbType() != null && column.getDbType() != ProductTypeEnum.OSCAR) {
            throw unsupported("Oscar nullable add column does not accept another product column");
        }
        var evidence = column.getMetadataEvidence();
        if (!column.isNullable() || Boolean.FALSE.equals(evidence.nullable().value())) {
            throw unsupported("Oscar nullable add column does not accept NOT NULL");
        }
        if (column.isPk()) {
            throw unsupported("Oscar nullable add column does not accept a primary key");
        }
        String fallback = column.getDefaultValue();
        boolean jdbcDefault = fallback != null && !"null".equals(fallback) && !"NULL".equals(fallback);
        MetadataValue<String> declaredDefault = evidence.defaultExpression();
        if (jdbcDefault || declaredDefault.state() == MetadataValue.State.VALUE
                || declaredDefault.state() == MetadataValue.State.UNSUPPORTED) {
            throw unsupported("Oscar nullable add column does not accept a default");
        }
        MetadataValue<String> identity = evidence.identity();
        if (column.isAutoIncrement() || identity.state() == MetadataValue.State.VALUE
                || identity.state() == MetadataValue.State.UNSUPPORTED) {
            throw unsupported("Oscar nullable add column does not accept an identity column");
        }
        MetadataValue<String> generated = evidence.generatedExpression();
        if (generated.state() == MetadataValue.State.VALUE || generated.state() == MetadataValue.State.UNSUPPORTED) {
            throw unsupported("Oscar nullable add column does not accept a generated column");
        }
        String quotedSchema = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OSCAR, schema);
        String quotedTable = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OSCAR, table);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OSCAR,
                column.getFieldName());
        ColumnMetaData meta = column.getMetaData();
        if (meta.getType() == ColumnMetaData.TYPE_NONE) {
            throw unsupported("Oscar nullable ADD COLUMN requires a recognized field type");
        }
        String type = typeOnly(meta.getName(), getFieldDefinition(meta, null, false, false, false));
        return "ALTER TABLE " + quotedSchema + "." + quotedTable + " ADD (" + quotedColumn + " " + type + ")";
    }

    /** {@code getFieldDefinition} 以带引号名称开头；只能复用后面的类型部分。 */
    private static String typeOnly(String name, String definition) throws SQLFeatureNotSupportedException {
        String prefix = " \"" + name + "\"    ";
        if (definition == null || name == null || !definition.startsWith(prefix)) {
            throw new IllegalArgumentException("Oscar field definition is not the existing getFieldDefinition layout");
        }
        String type = definition.substring(prefix.length());
        String upper = type.toUpperCase(Locale.ROOT);
        if (upper.contains(" NOT NULL") || upper.contains(" DEFAULT") || upper.contains(" IDENTITY")
                || upper.contains(" GENERATED") || upper.contains(" PRIMARY")) {
            throw unsupported("Oscar nullable add column accepts a type only");
        }
        if (type.isBlank() || type.indexOf(';') >= 0 || type.indexOf('\n') >= 0 || type.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Oscar field definition is not a bare column type");
        }
        return type;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }

    /**
     * 按原始异常类型重新抛出当前异常。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable failure) throws E {
        throw (E) failure;
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
