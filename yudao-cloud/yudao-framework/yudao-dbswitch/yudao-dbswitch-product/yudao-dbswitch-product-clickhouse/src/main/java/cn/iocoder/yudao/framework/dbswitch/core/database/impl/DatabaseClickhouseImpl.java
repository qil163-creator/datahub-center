package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;
import cn.iocoder.yudao.framework.dbswitch.common.constant.Const;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.ClickHouseNativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.core.util.ClickhouseRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedTextKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickHouseAddColumn;

import java.sql.ResultSetMetaData;
import java.sql.Types;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 支持DB2数据库的元信息实现
 *
 * @author jrl
 */
@Slf4j
public class DatabaseClickhouseImpl extends AbstractDatabase implements IDatabaseInterface {

    /** 仅列出原生非临时、非视图基本表；数量超限时拒绝截断清单。 */
    @Override
    public List<TableDescription> queryCatalogTableList(Connection connection, String schema, int maxTables,
            int timeoutSeconds) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.CLICKHOUSE) {
            return super.queryCatalogTableList(connection, schema, maxTables, timeoutSeconds);
        }
        if (schema == null || schema.isBlank() || maxTables < 1 || maxTables > 10000
                || timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException("目录读取必须指定 schema、数量及超时上限");
        }
        String sql = "SELECT database,name FROM system.tables WHERE database=? AND is_temporary=0 "
                + "AND engine NOT IN ('View','MaterializedView','LiveView','WindowView') ORDER BY name LIMIT " + (maxTables + 1);
        var tables = new java.util.TreeMap<String, TableDescription>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(maxTables + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (!schema.equals(rows.getString(1)) || rows.getString(2) == null || rows.getString(2).isBlank()) {
                        throw new SQLException("原生目录返回非精确或无效物理名称");
                    }
                    var table = new TableDescription();
                    table.setSchemaName(rows.getString(1)); table.setTableName(rows.getString(2)); table.setTableType("TABLE");
                    if (tables.putIfAbsent(table.getTableName(), table) != null || tables.size() > maxTables) {
                        throw new SQLException("目录不唯一或超过数量上限，拒绝截断结果", "54000");
                    }
                }
            }
        }
        return List.copyOf(tables.values());
    }

    /** 进入目标数据库只验证 USE 权限，不访问业务表。 */
    @Override
    public void probeNamespace(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.execute("USE " + quoteIdentifier(namespace));
        }
    }

    /** 授权目录证据不等同于任意业务对象的写授权。 */
    @Override
    public void probeAccountPrivileges(Connection connection, String namespace, int timeoutSeconds) throws SQLException {
        probeNamespace(connection, namespace, timeoutSeconds);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            try (var result = statement.executeQuery("SHOW GRANTS")) {
                if (!result.next()) {
                    throw new SQLException("账号授权目录未返回记录");
                }
            }
        }
    }

    /** 精确读取系统目录中的注释，仅返回计数。 */
    @Override
    public cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic probeExtendedProperties(
            Connection connection, String namespace, String table, int timeoutSeconds) throws SQLException {
        if (namespace == null || namespace.isBlank() || table == null || table.isBlank()
                || timeoutSeconds < 1 || timeoutSeconds > 60) {
            throw new IllegalArgumentException("扩展属性读取需要物理对象及有效超时");
        }
        String sql = "SELECT t.comment,c.comment FROM system.tables t INNER JOIN system.columns c "
                + "ON t.database=c.database AND t.name=c.table WHERE t.database=? AND t.name=? ORDER BY c.position LIMIT 4097";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setString(1, namespace);
            statement.setString(2, table);
            int columns = 0;
            int comments = 0;
            boolean tableComment = false;
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    if (++columns > 4096) { throw new SQLException("注释列目录超过诊断上限，拒绝截断证据", "54000"); }
                    String value = result.getString(1);
                    tableComment |= value != null && !value.isBlank();
                    value = result.getString(2);
                    if (value != null && !value.isBlank()) { comments++; }
                }
            }
            if (columns == 0) { throw new SQLException("目标关系不存在或不可见", "42S02"); }
            return new cn.iocoder.yudao.framework.dbswitch.core.model.ExtendedPropertyDiagnostic(columns, tableComment, comments);
        }
    }

    private static final String SHOW_CREATE_TABLE_SQL = "SHOW CREATE TABLE `%s`.`%s` ";

    private static final String SHOW_CREATE_VIEW_SQL = "SHOW CREATE VIEW `%s`.`%s` ";

    private static final String QUERY_SCHEMA_LIST_SQL =
            "SELECT name from `system`.`databases` where engine !='Memory'";

    private static final String QUERY_TABLE_LIST_SQL =
            "SELECT database ,name, %s, engine from `system`.`tables` where is_temporary =0 and database = ? ";

    private static final String QUERY_TABLE_META_SQL =
            "SELECT database ,name, %s, engine from `system`.`tables` where is_temporary =0 and database = ? and name = ?";

    private static final String QUERY_PRIMARY_KEY_SQL =
            "SELECT name from `system`.`columns` where `database` = ? and `table` = ? and is_in_primary_key =1 order by `position` ";

    /** 与原生目录读取器一样固定为 25.8。其他版本不适用此列目录。 */
    private static final String CLICKHOUSE_CATALOG_VERSION = "25\\.8\\.[0-9]+(?:\\.[0-9]+)?";

    /**
     * 初始化 {@code DatabaseClickhouseImpl}及其父类状态。
     */
    public DatabaseClickhouseImpl() {
        super(ProductTypeEnum.CLICKHOUSE.getDriveClassName());
    }

    /**
     * 引用单个标识符：引号字符来自 {@link ProductTypeEnum#getQuote()}，并转义嵌入的引号。 引号为空（如 OceanBase 枚举默认值）时回退为反引号，子类可覆盖。
     */
    @Override
    public String quoteIdentifier(String name) {
        return cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickhouseSql.quote(name);
    }

    /**
     * 获取数据库的表全名。
     */
    @Override
    public String getQuotedSchemaTableCombination(String schema, String table) {
        return quoteIdentifier(schema) + "." + quoteIdentifier(table);
    }

    /**
     * 读取当前关系的结构指纹，供后续一致性比较。
     */
    @Override
    public String queryRelationFingerprint(Connection connection, String reference) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.CLICKHOUSE) {
            return super.queryRelationFingerprint(connection, reference);
        }
        return ClickhouseRelationIdentity.fingerprint(connection, reference);
    }

    /**
     * 单个物理列转为保留 NULL 的文本表达式；不接受任意 SQL，不代表可作为无损身份编码。
     */
    @Override
    public String textColumnExpression(String column) {
        if (getDatabaseType() != ProductTypeEnum.CLICKHOUSE) {
            return super.textColumnExpression(column);
        }
        return "toString(" + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.CLICKHOUSE, column) + ")";
    }

    /**
     * 有界键投影的方言扩展点；两个参数都是物理标识符，不接受 SQL 表达式。
     */
    @Override
    public BoundedIntegerKeyProjection integerKeyProjection(String alias, String column) {
        if (getDatabaseType() != ProductTypeEnum.CLICKHOUSE) {
            return super.integerKeyProjection(alias, column);
        }
        return integerProjection(alias, column, 21);
    }

    /**
     * 复合身份分量；原生类型及编码范围由产品实现证明，不从文本转换结果猜类型。
     */
    @Override
    public BoundedKeyComponentProjection keyComponentProjection(String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.CLICKHOUSE) {
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
        String field = (alias == null ? "" : RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.CLICKHOUSE,
                alias) + ".")
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.CLICKHOUSE, column);
        String text = "toString(" + field + ")";
        String value = "if(length(" + text + ") <= " + maxBytes + ", substring(" + text + ", 1, " + maxBytes + "), NULL)";
        return new IntegerProjection(value, "nullIf(" + field + ", " + field + ")");
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
         * 确定当前列或值对应的 JDBC 类型。
         */
        @Override
        public int jdbcType(ResultSetMetaData metadata, int column) throws SQLException {
            return signedIntegerType(metadata, column);
        }
    }

    /**
     * 根据位宽构建有符号整数类型定义。
     */
    private static int signedIntegerType(ResultSetMetaData metadata, int column) throws SQLException {
        if (!metadata.isSigned(column)) {
            return 0;
        }
        String type = metadata.getColumnTypeName(column);
        if (type == null) {
            return 0;
        }
        if (type.startsWith("Nullable(") && type.endsWith(")")) {
            type = type.substring(9, type.length() - 1);
        }
        int jdbc = metadata.getColumnType(column);
        return switch (type) {
            case "Int16" -> jdbc == Types.SMALLINT ? jdbc : 0;
            case "Int32" -> jdbc == Types.INTEGER ? jdbc : 0;
            case "Int64" -> jdbc == Types.BIGINT ? jdbc : 0;
            default -> 0;
        };
    }

    /**
     * 精确文本键投影；alias 可为 null（未限定列），其余输入均须为物理标识符。
     */
    @Override
    public BoundedTextKeyProjection textKeyProjection(String alias, String column, int maxBytes) {
        if (getDatabaseType() != ProductTypeEnum.CLICKHOUSE) {
            return super.textKeyProjection(alias, column, maxBytes);
        }
        if (maxBytes < 1 || maxBytes > 4096) {
            throw new IllegalArgumentException("Invalid text key byte limit");
        }
        String field = (alias == null ? "" : RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.CLICKHOUSE,
                alias) + ".")
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.CLICKHOUSE, column);
        String text = "toString(" + field + ")";
        // String 可能包含任意字节。在 JDBC 驱动替换字节前拒绝无效 UTF-8。
        String value = "if(length(" + text + ") <= " + maxBytes + " AND isValidUTF8(" + text
                + "), substring(" + text + ", 1, " + maxBytes + "), NULL)";
        return new TextProjection("hex(" + text + ")", "hex(" + value + ")", value,
                "nullIf(" + field + ", " + field + ")", maxBytes);
    }

    /**
     * 生成文本或整数键分量的查询投影。
     */
    @Override
    public BoundedKeyComponentProjection textAndIntegerKeyComponentProjection(Connection connection,
            String alias, String column, int maxBytes) throws SQLException {
        if (getDatabaseType() != ProductTypeEnum.CLICKHOUSE) {
            return super.textAndIntegerKeyComponentProjection(connection, alias, column, maxBytes);
        }
        return new MixedProjection(textKeyProjection(alias, column, maxBytes));
    }

    private record TextProjection(String identitySql, String groupSql, String valueSql, String typeSql, int maxBytes)
            implements BoundedTextKeyProjection {

        /**
         * 0 表示类型未经验证；不可由转成文本的结果反推源字段类型。
         */
        @Override
        public int jdbcType(ResultSetMetaData metadata, int column) throws SQLException {
            String nativeType = metadata.getColumnTypeName(column);
            return metadata.getColumnType(column) == Types.VARCHAR && nativeType != null
                    && java.util.Set.of("String", "Nullable(String)", "LowCardinality(String)",
                    "LowCardinality(Nullable(String))").contains(nativeType) ? Types.VARCHAR : 0;
        }

        /**
         * 有界的精确值过滤表达式，返回方言安全常量，不依赖会话反斜杠转义或 collation。
         */
        @Override
        public String equalitySql(String value) {
            if (value == null) {
                throw new IllegalArgumentException("A non-null text key is required");
            }
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.length > maxBytes || !value.equals(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("Invalid or oversized UTF-8 text key");
            }
            return identitySql + " = '" + java.util.HexFormat.of().withUpperCase().formatHex(bytes) + "'";
        }
    }

    private record MixedProjection(BoundedTextKeyProjection text) implements BoundedKeyComponentProjection {

        /**
         * 按目标类型和数据库方言生成值表达式。
         */
        @Override
        public String valueSql() {
            return text.valueSql();
        }

        /**
         * 按当前列属性生成数据库类型 SQL。
         */
        @Override
        public String typeSql() {
            return text.typeSql();
        }

        /**
         * 生成按当前键列分组的 SQL 片段。
         */
        @Override
        public String groupSql() {
            return text.groupSql();
        }

        /**
         * 确定当前列或值对应的 JDBC 类型。
         */
        @Override
        public int jdbcType(ResultSetMetaData metadata, int column) throws SQLException {
            int integer = signedIntegerType(metadata, column);
            return integer != 0 ? integer : text.jdbcType(metadata, column);
        }
    }

    /**
     * 获取数据库类型。
     */
    @Override
    public ProductTypeEnum getDatabaseType() {
        return ProductTypeEnum.CLICKHOUSE;
    }

    /**
     * 获取数据库的模式schema列表。
     */
    @Override
    public List<String> querySchemaList(Connection connection) {
        List<String> result = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            try (ResultSet rs = st.executeQuery(QUERY_SCHEMA_LIST_SQL)) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取指定模式Schema内的所有表列表。
     */
    @Override
    public List<TableDescription> queryTableList(Connection connection, String schemaName) {
        List<TableDescription> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(tableMetadataSql(connection, false))) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery();) {
                while (rs.next()) {
                    TableDescription td = new TableDescription();
                    td.setSchemaName(rs.getString(1));
                    td.setTableName(rs.getString(2));
                    td.setRemarks(rs.getString(3));
                    String tableType = rs.getString(4);
                    if (tableType.equalsIgnoreCase("VIEW")) {
                        td.setTableType("VIEW");
                    } else {
                        td.setTableType("TABLE");
                    }

                    result.add(td);
                }

                return result;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 精确获取表或视图的元数据。
     */
    @Override
    public TableDescription queryTableMeta(Connection connection, String schemaName, String tableName) {
        try (PreparedStatement ps = connection.prepareStatement(tableMetadataSql(connection, true))) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery();) {
                if (rs.next()) {
                    TableDescription td = new TableDescription();
                    td.setSchemaName(rs.getString(1));
                    td.setTableName(rs.getString(2));
                    td.setRemarks(rs.getString(3));
                    String tableType = rs.getString(4);
                    if (tableType.equalsIgnoreCase("VIEW")) {
                        td.setTableType("VIEW");
                    } else {
                        td.setTableType("TABLE");
                    }

                    return td;
                }

                return null;
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
        return null;
    }

    /** 在传入的同一个连接上探测结果元数据；授权或连接失败直接传播。 */
    private static String tableMetadataSql(Connection connection, boolean singleTable) throws SQLException {
        boolean comment = false;
        try (Statement probe = connection.createStatement()) {
            probe.setQueryTimeout(5);
            try (ResultSet rows = probe.executeQuery("SELECT * FROM `system`.`tables` WHERE 1=0")) {
                ResultSetMetaData metadata = rows.getMetaData();
                if (metadata.getColumnCount() < 1) {
                    throw new SQLException("ClickHouse system table metadata is unavailable");
                }
                for (int column = 1; column <= metadata.getColumnCount(); column++) {
                    if ("comment".equalsIgnoreCase(metadata.getColumnName(column))) {
                        comment = true;
                    }
                }
            }
        }
        return String.format(singleTable ? QUERY_TABLE_META_SQL : QUERY_TABLE_LIST_SQL,
                comment ? "comment" : "NULL AS comment");
    }

    /**
     * 由 {@link ClickHouseNativeCatalog} 覆盖的 JDBC 外壳。列注释取自
     * 该查询中的 {@code system.columns.comment}。授权失败直接传播。
     */
    @Override
    public List<ColumnDescription> queryTableColumnMeta(Connection connection, String schemaName,
            String tableName) {
        List<ColumnDescription> results = queryTableColumnMetaOnly(connection, schemaName, tableName);
        try {
            applyNativeColumns(connection, schemaName, tableName, results);
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
        for (ColumnDescription column : results) {
            if (StringUtils.equals(column.getFieldTypeName(), "Nullable(String)")
                    || StringUtils.equals(column.getFieldTypeName(), "String")) {
                column.setDisplaySize(9999999);
            }
        }
        return results;
    }

    /** 不调用 JDBC {@code getColumns}，也不读取 MySQL 列目录。 */
    private static void applyNativeColumns(Connection connection, String schemaName, String tableName,
            List<ColumnDescription> columns) throws SQLException {
        MetadataProvenance source = columnProvenance(connection);
        Map<String, ColumnDescription> pending = new LinkedHashMap<>();
        for (ColumnDescription column : columns) {
            String name = column.getFieldName();
            if (name == null || name.isBlank() || pending.put(name, column) != null) {
                throw new SQLException("ClickHouse JDBC column identity is ambiguous");
            }
        }
        int seen = 0;
        try (PreparedStatement statement = connection.prepareStatement(ClickHouseNativeCatalog.SQL)) {
            statement.setQueryTimeout(5);
            statement.setString(1, schemaName);
            statement.setString(2, tableName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    seen++;
                    String name = rows.getString("column_name");
                    ColumnDescription column = name == null ? null : pending.remove(name);
                    if (column == null) {
                        throw new SQLException("ClickHouse system.columns row is not an observed column");
                    }
                    ClickHouseNativeCatalog.apply(column, rows, source);
                }
            }
        }
        if (seen == 0 || !pending.isEmpty()) {
            throw new SQLException("ClickHouse system.columns did not match the observed columns");
        }
    }

    /**
     * 拒绝当前实现未支持的 {@code columnProvenance} 操作。
     */
    private static MetadataProvenance columnProvenance(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (ResultSet rows = statement.executeQuery("SELECT version()")) {
                if (!rows.next()) {
                    throw new SQLFeatureNotSupportedException("ClickHouse native version unavailable", "0A000");
                }
                String version = rows.getString(1);
                if (rows.next() || version == null || !version.matches(CLICKHOUSE_CATALOG_VERSION)) {
                    throw new SQLFeatureNotSupportedException("ClickHouse native version is unverified", "0A000");
                }
                return new MetadataProvenance(ProductTypeEnum.CLICKHOUSE, "ClickHouse", version,
                        "CLICKHOUSE_CATALOG_V1", java.time.Instant.now());
            }
        }
    }

    /**
     * 获取指定模式表的主键字段列表。
     */
    @Override
    public List<String> queryTablePrimaryKeys(Connection connection, String schemaName, String tableName) {
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(QUERY_PRIMARY_KEY_SQL)) {
            ps.setString(1, schemaName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery();) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取指定物理表的DDL语句。
     */
    @Override
    public String getTableDDL(Connection connection, String schemaName, String tableName) {
        List<String> result = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            if (st.execute("SHOW CREATE TABLE " + getQuotedSchemaTableCombination(schemaName, tableName))) {
                try (ResultSet rs = st.getResultSet()) {
                    if (rs != null) {
                        while (rs.next()) {
                            String value = rs.getString(1);
                            Optional.ofNullable(value).ifPresent(result::add);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result.stream().findAny().orElse(null);
    }

    /**
     * 获取指定视图表的DDL语句。
     */
    @Override
    public String getViewDDL(Connection connection, String schemaName, String tableName) {
        List<String> result = new ArrayList<>();
        try (Statement st = connection.createStatement()) {
            if (st.execute("SHOW CREATE VIEW " + getQuotedSchemaTableCombination(schemaName, tableName))) {
                try (ResultSet rs = st.getResultSet()) {
                    if (rs != null) {
                        while (rs.next()) {
                            String value = rs.getString(1);
                            Optional.ofNullable(value).ifPresent(result::add);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return result.stream().findAny().orElse(null);
    }

    /**
     * 获取指定查询SQL的元信息。
     */
    @Override
    public List<ColumnDescription> querySelectSqlColumnMeta(Connection connection, String sql) {
        String querySQL = String.format(" %s LIMIT 0,1", sql.replace(";", ""));
        return this.getSelectSqlColumnMeta(connection, querySQL);
    }

    /**
     * 测试查询SQL语句的有效性。
     */
    @Override
    public void testQuerySQL(Connection connection, String sql) {
        String testQuerySql = String.format("explain %s", sql.replace(";", ""));
        if (log.isDebugEnabled()) {
            log.debug("Execute sql :{}", testQuerySql);
        }
        try (Statement st = connection.createStatement()) {
            st.execute(testQuerySql);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 获取字段列的结构定义。
     */
    @Override
    public String getFieldDefinition(ColumnMetaData column, List<String> pks, boolean useAutoInc,
            boolean addCr, boolean withRemarks) {
        if (useAutoInc) {
            throw new UnsupportedOperationException("ClickHouse does not implement auto-increment columns");
        }
        String type = switch (column.getSqlType()) {
            case Types.TINYINT -> "Int16";
            case Types.SMALLINT -> "Int32";
            case Types.INTEGER -> "Int64";
            case Types.BIGINT -> column.getType() == ColumnMetaData.TYPE_BIGNUMBER ? "Decimal(20,0)" : "Int64";
            case Types.DECIMAL, Types.NUMERIC -> {
                if (column.getLength() < 1 || column.getLength() > 76 || column.getPrecision() < 0
                        || column.getPrecision() > column.getLength()) {
                    throw new UnsupportedOperationException("ClickHouse decimal requires explicit precision and scale");
                }
                yield "Decimal(" + column.getLength() + "," + column.getPrecision() + ")";
            }
            case Types.REAL -> "Float32";
            case Types.FLOAT, Types.DOUBLE -> "Float64";
            case Types.BOOLEAN, Types.BIT -> "Bool";
            case Types.DATE -> "Date32";
            case Types.TIMESTAMP -> "DateTime64(9)";
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                 Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB, Types.BINARY, Types.VARBINARY,
                 Types.LONGVARBINARY, Types.BLOB -> "String";
            default -> throw new UnsupportedOperationException(
                    "ClickHouse column requires an explicit lossless type mapping");
        };
        if (column.isNullable() && (pks == null || !pks.contains(column.getName()))) {
            type = "Nullable(" + type + ")";
        }
        String definition = " " + quoteIdentifier(column.getName()) + " " + type;
        if (withRemarks && StringUtils.isNotBlank(column.getRemarks())) {
            definition += " COMMENT '" + column.getRemarks().replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        return definition + (addCr ? "\r" : "");
    }

    /** 普通可空列，无默认。类型文本自带 Nullable，标识符用反引号。不打开连接。 */
    @Override
    public String getAddColumnSql(String targetSchemaName, String targetTableName, ColumnDescription targetColumn) {
        try {
            return ClickHouseAddColumn.sql(targetSchemaName, targetTableName, targetColumn,
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

    /**
     * 获取表和字段的注释定义。
     */
    @Override
    public List<String> getTableColumnCommentDefinition(TableDescription td, List<ColumnDescription> cds) {
        return Collections.emptyList();
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
        return String.format("explain %s", sql.replace(";", ""));
    }

    /**
     * 建表语句后置钩子：在字段定义之后追加方言相关内容（表注释、存储引擎/属性、分布键等）， 默认无操作，由需要的子类覆盖。
     */
    @Override
    public void postAppendCreateTableSql(StringBuilder builder, String tblComment, List<String> primaryKeys,
            Map<String, String> tblProperties, String hashFiled) {
        builder.append("ENGINE=MergeTree");
        builder.append("\r\n");
        if (CollectionUtils.isEmpty(primaryKeys)) {
            builder.append("ORDER BY tuple()");
        } else {
            builder.append("ORDER BY (").append(getPrimaryKeyAsString(primaryKeys)).append(")");
        }
        if (StringUtils.isNotBlank(tblComment)) {
            builder.append("\r\n");
            builder.append(String.format("COMMENT '%s' ", tblComment.replace("\\", "\\\\").replace("'", "\\'")));
        }
    }

    /**
     * 主键列转换为逗号分隔的字符串。
     */
    @Override
    public String getPrimaryKeyAsString(List<String> pks) {
        if (null != pks && !pks.isEmpty()) {
            return pks.stream().map(this::quoteIdentifier).collect(java.util.stream.Collectors.joining(","));
        }

        return "";
    }

    /** ORDER BY/PRIMARY KEY 与数据跳数索引属于 MergeTree 定义，没有可按普通索引重建的唯一/B-tree 索引。 */
    @Override
    public List<cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription> queryTableIndexes(
            Connection connection,
            String schemaName, String tableName) {
        return List.of();
    }
}
