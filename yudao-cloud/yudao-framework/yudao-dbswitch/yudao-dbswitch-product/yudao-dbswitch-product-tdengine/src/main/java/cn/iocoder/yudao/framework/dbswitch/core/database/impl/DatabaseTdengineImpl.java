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
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.TdengineNativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.TdengineRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineAddColumn;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DatabaseTdengineImpl extends AbstractDatabase implements IDatabaseInterface {

    /** 沿用 TDengine 3.x ins_tables 原生目录，SQL LIMIT 限制返回规模，不依赖 3.9.2 驱动的空 setMaxRows。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String schema, int maxTables,
            int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.TDENGINE) {
            return super.queryCatalogTableList(connection, schema, maxTables, timeoutSeconds);
        }
        if (schema == null || schema.isBlank() || maxTables < 1 || maxTables > 10000
                || timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException("目录读取必须指定 schema、数量及超时上限");
        }
        var tables = new java.util.TreeMap<String, TableDescription>();
        String sql = "SELECT table_name FROM information_schema.ins_tables WHERE db_name=? ORDER BY table_name LIMIT "
                + (maxTables + 1);
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    if (name == null || name.isBlank()) { throw new SQLException("表目录返回无效名称"); }
                    var table = new TableDescription();
                    table.setSchemaName(schema); table.setTableName(name); table.setTableType("TABLE");
                    if (tables.putIfAbsent(name, table) != null || tables.size() > maxTables) {
                        throw new SQLException("目录不唯一或超过数量上限，拒绝截断结果", "54000");
                    }
                }
            }
        }
        return List.copyOf(tables.values());
    }

    /** 在独占诊断连接上验证进入目标数据库；不将目录可见性当作访问授权。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (namespace == null || !namespace.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || timeoutSeconds < 1) {
            throw new IllegalArgumentException("TDengine 诊断需要普通数据库名和有效超时");
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.execute("USE `" + namespace + "`");
        }
    }

    /** 读取当前账号的原生授权目录；只观察，不把目录可见性推断为任意表可读写。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        if (namespace == null || !namespace.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
                || timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException("TDengine 诊断需要普通数据库名和有效超时");
        }
        probeNamespace(connection, namespace, timeoutSeconds);
        String principal;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            try (var rows = statement.executeQuery("SELECT current_user()")) {
                if (!rows.next() || (principal = rows.getString(1)) == null || rows.next()) {
                    throw new SQLException("TDengine 未返回唯一会话主体", "HY000");
                }
            }
        }
        int at = principal.indexOf('@');
        if (at < 1 || at != principal.lastIndexOf('@') || at == principal.length() - 1
                || !principal.substring(0, at).matches("[A-Za-z_][A-Za-z0-9_]{0,23}")) {
            throw new SQLException("TDengine 会话主体身份无法精确解析", "HY000");
        }
        String sql = "SELECT privilege,db_name,table_name FROM information_schema.ins_user_privileges "
                + "WHERE user_name=? AND (db_name='all' OR db_name=?) LIMIT 513";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setString(1, principal.substring(0, at)); statement.setString(2, namespace);
            try (var rows = statement.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 512) { throw new SQLException("账号授权目录超过接收上限", "54000"); }
                    if (rows.getString(1) == null || rows.getString(2) == null) {
                        throw new SQLException("账号授权目录缺少权限或数据库身份", "HY000");
                    }
                }
            }
        }
    }

    /** TDengine 表级 COMMENT 可用，但当前公共结果无法表达列级属性不适用，不能假装完整成功。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        throw new java.sql.SQLFeatureNotSupportedException(
                "TDengine 有 table_comment，但无同等列级 COMMENT；公共结果需支持按属性适用性及部分证据，不能返回全列注释假成功", "0A000");
    }

    private static final String SHOW_DATABASES_SQL = "SHOW DATABASES";

    private static final String SHOW_TABLES_SQL = "SHOW %s.TABLES";

    private static final String SHOW_CREATE_TABLE_SQL = "SHOW CREATE TABLE %s.%s";

    /** 与其他目录调用方采用相同上限。使用 DESCRIBE，非 JDBC getColumns。 */
    private static final int COLUMN_CATALOG_TIMEOUT_SECONDS = 30;

    /**
     * 初始化 {@code DatabaseTdengineImpl}及其父类状态。
     */
    public DatabaseTdengineImpl() {
        super(ProductTypeEnum.TDENGINE.getDriveClassName());
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.TDENGINE;
    }

    /** 来自 DESCRIBE 的普通列。标签和 JSON 标签不是列。不使用 JDBC getColumns 或 ins_columns。 */
    @Override
    protected ResultSet openColumnMetadata(Connection connection, String schemaName,
            String tableName) throws SQLException {
        List<String> names = TdengineNativeCatalog.read(connection, schemaName, tableName,
                        COLUMN_CATALOG_TIMEOUT_SECONDS)
                .columns().stream().map(ColumnDescription::getFieldName).toList();
        return ordinaryColumnNames(names);
    }

    /**
     * 获取指定模式表的元信息。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName, String tableName) {
        return describedColumns(connection, schemaName, tableName);
    }

    /**
     * 读取表结构中的列定义。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMetaOnly(Connection connection, String schemaName,
            String tableName) {
        return describedColumns(connection, schemaName, tableName);
    }

    /**
     * 获取指定模式表的字段列表。
     */
    @Override
    public List<String> queryTableColumnName(Connection connection, String schemaName, String tableName) {
        return describedColumns(connection, schemaName, tableName).stream().map(ColumnDescription::getFieldName)
                .toList();
    }

    /**
     * 将目录或驱动返回的列信息转换为列描述集合。
     */
    private List<ColumnDescription> describedColumns(Connection connection, String schemaName, String tableName) {
        try {
            return TdengineNativeCatalog.read(connection, schemaName, tableName, COLUMN_CATALOG_TIMEOUT_SECONDS)
                    .columns();
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
    }

    /** 供仍读取 JDBC 结果的调用方使用的 COLUMN_NAME 行。名称已经排除标签。 */
    private static ResultSet ordinaryColumnNames(List<String> names) {
        int[] cursor = {-1};
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == (args == null ? null : args[0]);
                            default -> "TDengine ordinary columns";
                        };
                    }
                    return switch (method.getName()) {
                        case "next" -> ++cursor[0] < names.size();
                        case "getString" -> ordinaryColumnName(names, cursor[0], args);
                        case "close" -> null;
                        case "wasNull", "isClosed" -> false;
                        default -> jdbcDefault(method.getReturnType());
                    };
                });
    }

    /**
     * 检查列名是否为当前路径支持的普通物理标识符。
     */
    private static String ordinaryColumnName(List<String> names, int cursor, Object[] args) {
        if (cursor < 0 || cursor >= names.size()) {
            return null;
        }
        if (args != null && args.length == 1 && args[0] instanceof String label && !"COLUMN_NAME".equalsIgnoreCase(
                label)) {
            return null;
        }
        return names.get(cursor);
    }

    /**
     * 规范化 JDBC 返回的列默认值信息。
     */
    private static Object jdbcDefault(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        return TdengineRelationIdentity.fingerprint(connection, reference);
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String alias, String column) {
        return integerProjection(alias, column, 21);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String alias, String column, int maxBytes) {
        if (maxBytes < 1 || maxBytes > 4096) {
            throw new IllegalArgumentException("Invalid key byte bound");
        }
        return integerProjection(alias, column, Math.min(maxBytes, 21));
    }

    /**
     * 生成整数值的查询投影。
     */
    private static IntegerProjection integerProjection(String alias, String column, int maxBytes) {
        String field = (alias == null ? "" : quote(alias) + ".") + quote(column);
        // 21 字节足以容纳任何受支持的原生有符号整数。读取此有界载荷前，
        // 必须验证未经转换的 NULLIF 元数据；字符串、无符号数和浮点数不是此处的整数。
        String text = "CAST(" + field + " AS VARCHAR(21))";
        return new IntegerProjection("CASE WHEN LENGTH(" + text + ") <= " + maxBytes
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
        public int jdbcType(ResultSetMetaData metadata, int column) throws SQLException {
            String nativeType = metadata.getColumnTypeName(column);
            int jdbc = metadata.getColumnType(column);
            if (nativeType == null || !metadata.isSigned(column)) {
                return 0;
            }
            return switch (nativeType) {
                case "SMALLINT" -> jdbc == Types.SMALLINT ? jdbc : 0;
                case "INT" -> jdbc == Types.INTEGER ? jdbc : 0;
                case "BIGINT" -> jdbc == Types.BIGINT ? jdbc : 0;
                default -> 0;
            };
        }
    }

    /**
     * 单个物理列转为保留 NULL 的文本表达式；不接受任意 SQL，不代表可作为无损身份编码。
     */
    @Override
    public String textColumnExpression(String column) {
        return "CAST(" + quote(column) + " AS VARCHAR(65517))";
    }

    /**
     * 按TDengine方言转义并引用标识符。
     */
    private static String quote(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.TDENGINE, name);
    }

    /**
     * 获取查询字段默认值/注释的 SQL（数据库方言相关）。
     */
    @Override
    protected String getDefaultValueSql(String schemaName, String tableName) {
        return null;
    }

    /**
     * 获取数据库的模式schema列表。
     */
    @Override
    public List<String> querySchemaList(Connection connection) {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(SHOW_DATABASES_SQL)) {
            List<String> schemas = new ArrayList<>();
            while (rs.next()) {
                String schema = rs.getString(1);
                if (StringUtils.isNotBlank(schema)) {
                    schemas.add(schema);
                }
            }
            return schemas;
        } catch (SQLException ex) {
            return super.querySchemaList(connection);
        }
    }

    /**
     * 获取指定模式Schema内的所有表列表。
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        String sql = String.format(SHOW_TABLES_SQL, quote(schemaName));
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<TableDescription> tables = new ArrayList<>();
            while (rs.next()) {
                String tableName = rs.getString(1);
                if (StringUtils.isBlank(tableName)) {
                    continue;
                }
                TableDescription td = new TableDescription();
                td.setSchemaName(schemaName);
                td.setTableName(tableName);
                td.setRemarks(null);
                td.setTableType("TABLE");
                tables.add(td);
            }
            try (Statement stableStatement = connection.createStatement()) {
                stableStatement.setQueryTimeout(30);
                try (ResultSet stableRows = stableStatement.executeQuery("SHOW " + quote(schemaName) + ".STABLES")) {
                    while (stableRows.next()) {
                        TableDescription stable = new TableDescription();
                        stable.setSchemaName(schemaName);
                        stable.setTableName(stableRows.getString(1));
                        stable.setTableType("TABLE");
                        tables.add(stable);
                    }
                }
            }
            return tables;
        } catch (SQLException ex) {
            return super.queryTableList(connection, schemaName);
        }
    }

    /**
     * 获取指定模式表的主键字段列表。
     */
    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schemaName, String tableName) {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.setMaxRows(4097);
            try (ResultSet rows = statement.executeQuery(
                    "DESCRIBE " + getQuotedSchemaTableCombination(schemaName, tableName))) {
                List<String> keys = new ArrayList<>();
                int count = 0;
                while (rows.next()) {
                    if (++count > 4096) {
                        return Collections.emptyList();
                    }
                    if (count == 1) {
                        if (!"TIMESTAMP".equals(rows.getString(2))) {
                            return Collections.emptyList();
                        }
                        keys.add(rows.getString(1));
                    } else if ("COMPOSITE KEY".equals(rows.getString(4))) {
                        keys.add(rows.getString(1));
                    }
                }
                return keys;
            }
        } catch (SQLException ex) {
            // 不完整的键比没有键更糟：绝不默默回退到仅使用时间戳。
            return Collections.emptyList();
        }
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        String sql = String.format(SHOW_CREATE_TABLE_SQL, quote(schemaName), quote(tableName));
        try (Statement st = connection.createStatement()) {
            st.setQueryTimeout(5);
            st.setMaxRows(2);
            try (ResultSet rs = st.executeQuery(sql)) {
                if (!rs.next()) {
                    return null;
                }
                // 原生结果：第 1 列为表名，第 2 列为实际 DDL。
                String ddl = rs.getString(2);
                return rs.next() ? null : ddl;
            }
        } catch (SQLException ex) {
            return null;
        }
    }

    /**
     * 获取指定视图表的DDL语句。
     */
    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        return null;
    }

    /**
     * 获取指定查询SQL的元信息。
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        String querySql = String.format("%s LIMIT 0", sql.replace(";", ""));
        return this.getSelectSqlColumnMeta(connection, querySql);
    }

    /**
     * 生成用于探测表字段的查询 SQL。
     */
    @Override
    protected String getTableFieldsQuerySQL(String schemaName, String tableName) {
        return "SELECT * FROM " + getQuotedSchemaTableCombination(schemaName, tableName) + " WHERE 1=2";
    }

    /**
     * 把用户 SQL 包装为低成本的合法性探测语句（如 explain、外层包 WHERE 1=2 等），由子类按方言实现。
     */
    @Override
    protected String getTestQuerySQL(String sql) {
        return String.format("%s LIMIT 0", sql.replace(";", ""));
    }

    /**
     * 获取数据库的表全名。
     */
    @Override
    public String getQuotedSchemaTableCombination(String schemaName, String tableName) {
        return quote(schemaName) + "." + quote(tableName);
    }

    /**
     * 在建表语句中追加主键定义片段 ", PRIMARY KEY (...)"；主键列表为空时不追加。
     */
    @Override
    public void appendPrimaryKeyForCreateTableSql(StringBuilder builder, List<String> primaryKeys) {
    }

    /** 时间戳主键与 TAG 由原生超级表结构表达，不是 JDBC 二级索引。 */
    @Override
    public List<cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription> queryTableIndexes(
            Connection connection,
            String schemaName, String tableName) {
        return List.of();
    }

    /**
     * 建表语句后置钩子：在字段定义之后追加方言相关内容（表注释、存储引擎/属性、分布键等）， 默认无操作，由需要的子类覆盖。
     */
    @Override
    public void postAppendCreateTableSql(StringBuilder builder, String tblComment, List<String> primaryKeys,
            java.util.Map<String, String> tblProperties, String hashFiled) {
        if (StringUtils.isNotBlank(tblComment)) {
            builder.append(" COMMENT ")
                    .append(cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineNativeTables.commentLiteral(
                            tblComment)).append(" ");
        }
    }

    /**
     * 获取字段列的结构定义。
     */
    @Override
    public String getFieldDefinition(ColumnMetaData v, List<String> pks, boolean useAutoInc, boolean addCr,
            boolean withRemarks) {
        if (useAutoInc) {
            throw new IllegalArgumentException("TDengine does not have generated identity columns");
        }
        String fieldname = v.getName();
        int length = v.getLength();
        int precision = v.getPrecision();
        int type = v.getType();

        StringBuilder sb = new StringBuilder();
        sb.append(" ").append(quote(fieldname)).append(" ");

        switch (type) {
            case ColumnMetaData.TYPE_TIMESTAMP:
                sb.append("TIMESTAMP");
                break;
            case ColumnMetaData.TYPE_BOOLEAN:
                sb.append("BOOL");
                break;
            case ColumnMetaData.TYPE_INTEGER:
                if (length > 0 && length <= 9) {
                    sb.append("INT");
                } else {
                    sb.append("BIGINT");
                }
                break;
            case ColumnMetaData.TYPE_BIGNUMBER:
                if (length < 1 || length > 38 || precision < 0 || precision > length) {
                    throw new IllegalArgumentException("TDengine requires an exact decimal precision up to 38");
                }
                sb.append("DECIMAL(").append(length).append(",").append(precision).append(")");
                break;
            case ColumnMetaData.TYPE_NUMBER:
                if (v.getSqlType() == Types.DECIMAL || v.getSqlType() == Types.NUMERIC) {
                    if (length < 1 || length > 38 || precision < 0 || precision > length) {
                        throw new IllegalArgumentException("TDengine requires an exact decimal precision up to 38");
                    }
                    sb.append("DECIMAL(").append(length).append(",").append(precision).append(")");
                } else {
                    sb.append("DOUBLE");
                }
                break;
            case ColumnMetaData.TYPE_BINARY:
                int binaryLen = length > 0 ? length : 256;
                sb.append("VARBINARY(").append(binaryLen).append(")");
                break;
            case ColumnMetaData.TYPE_STRING:
                int ncharLen = length > 0 ? length : 256;
                sb.append("NCHAR(").append(ncharLen).append(")");
                break;
            default:
                throw new IllegalArgumentException("TDengine cannot preserve this logical column type");
        }

        if (pks != null && !pks.isEmpty() && pks.contains(fieldname) && pks.size() > 1) {
            String firstPk = pks.get(0);
            if (!StringUtils.equals(firstPk, fieldname)) {
                sb.append(" COMPOSITE KEY");
            }
        }

        if (addCr) {
            sb.append(Const.CR);
        }
        return sb.toString();
    }

    /** 普通可空列。使用与 {@link #getFieldDefinition} 相同的类型写法。不查询超级表。 */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        return TdengineAddColumn.statement(this, targetSchemaName, targetTableName, targetColumn);
    }

    /**
     * 获取表和字段的注释定义。
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td, List<ColumnDescription> cds) {
        return Collections.emptyList();
    }
}
