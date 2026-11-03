package cn.iocoder.yudao.module.modeling.service.model;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 在服务端已解析的同一个 JDBC 连接上，完成目标表的完整观测。 */
public final class ModelJdbcTableObserver {

    private static final String[] TABLE_TYPES = {"TABLE", "VIEW"};

    /**
     * 限制模型JDBC表观测器工具的实例化，统一通过静态能力使用。
     */
    private ModelJdbcTableObserver() {
    }

    /**
     * 读取模型JDBC表观测器当前观测状态。
     *
     * @param connection 数据库连接
     * @param configuredSchema 已配置结构
     * @param requestedTableName 已请求表名
     * @return 观测
     */
    public static Observation observe(Connection connection, String configuredSchema,
            String requestedTableName) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        String requested = ModelJdbcSchemaCanonical.requireSafeIdentifier(requestedTableName);
        DatabaseMetaData metadata = connection.getMetaData();
        Namespace namespace = resolveNamespace(connection, metadata, configuredSchema);
        ResolvedTable table = findTable(metadata, namespace, requested);
        if (table == null) {
            return new Observation(false, false, null, Instant.now(),
                    namespace.catalog(), namespace.schema(), null, List.of());
        }
        Set<String> primaryKeys = readPrimaryKeys(metadata, table);
        List<ModelJdbcSchemaCanonical.ColumnSnapshot> columns = readColumns(
                metadata, table, primaryKeys);
        String schemaHash = ModelJdbcSchemaCanonical.sha256(columns);
        boolean hasData = probeHasData(connection, metadata, namespace, table);
        return new Observation(true, hasData, schemaHash, Instant.now(),
                table.catalog(), table.schema(), table.tableName(), List.copyOf(columns));
    }

    /**
     * 解析命名空间。
     *
     * @param connection 数据库连接
     * @param metadata 元数据
     * @param configuredSchema 已配置结构
     * @return 命名空间
     */
    private static Namespace resolveNamespace(Connection connection, DatabaseMetaData metadata,
            String configuredSchema) throws SQLException {
        String catalog = safeIdentifierOrNull(connection.getCatalog());
        String connectionSchema;
        try {
            connectionSchema = safeIdentifierOrNull(connection.getSchema());
        } catch (SQLException | AbstractMethodError | UnsupportedOperationException ex) {
            connectionSchema = null;
        }
        String configured = safeIdentifierOrNull(configuredSchema);
        boolean catalogBased = metadata.supportsCatalogsInTableDefinitions()
                && !metadata.supportsSchemasInTableDefinitions();
        if (catalogBased) {
            if (configured != null && catalog != null && !configured.equalsIgnoreCase(catalog)) {
                throw new SQLException("configured database does not match the connection catalog");
            }
            return new Namespace(configured == null ? catalog : configured, null, true);
        }
        return new Namespace(catalog, configured == null ? connectionSchema : configured, false);
    }

    /**
     * 查找表。
     *
     * @param metadata 元数据
     * @param namespace 命名空间
     * @param requested 已请求
     * @return 已解析表
     */
    private static ResolvedTable findTable(DatabaseMetaData metadata, Namespace namespace,
            String requested) throws SQLException {
        Map<String, ResolvedTable> matches = new LinkedHashMap<>();
        LinkedHashSet<String> patterns = new LinkedHashSet<>(List.of(
                requested, requested.toUpperCase(Locale.ROOT), requested.toLowerCase(Locale.ROOT), "%"));
        for (String pattern : patterns) {
            try (ResultSet tables = metadata.getTables(namespace.catalog(), namespace.schema(),
                    pattern, TABLE_TYPES)) {
                while (tables.next()) {
                    String found = tables.getString("TABLE_NAME");
                    if (found == null || !requested.equalsIgnoreCase(found)) {
                        continue;
                    }
                    found = ModelJdbcSchemaCanonical.requireSafeIdentifier(found);
                    String tableCatalog = safeIdentifierOrNull(tables.getString("TABLE_CAT"));
                    String tableSchema = safeIdentifierOrNull(tables.getString("TABLE_SCHEM"));
                    if (!sameNamespace(namespace.catalog(), tableCatalog)
                            || !sameNamespace(namespace.schema(), tableSchema)) {
                        continue;
                    }
                    ResolvedTable resolved = new ResolvedTable(tableCatalog, tableSchema, found);
                    matches.putIfAbsent(key(resolved), resolved);
                }
            }
        }
        if (matches.size() > 1) {
            throw new SQLException("ambiguous case-insensitive table name");
        }
        return matches.values().stream().findFirst().orElse(null);
    }

    /**
     * 读取主键。
     *
     * @param metadata 元数据
     * @param table 表
     * @return 主键结果集合
     */
    private static Set<String> readPrimaryKeys(DatabaseMetaData metadata, ResolvedTable table)
            throws SQLException {
        Set<String> primaryKeys = new java.util.HashSet<>();
        try (ResultSet keys = metadata.getPrimaryKeys(
                table.catalog(), table.schema(), table.tableName())) {
            while (keys.next()) {
                String name = keys.getString("COLUMN_NAME");
                if (name != null) {
                    ModelJdbcSchemaCanonical.requireSafeIdentifier(name);
                    primaryKeys.add(canonicalName(name));
                }
            }
        }
        return primaryKeys;
    }

    /**
     * 读取模型JDBC表观测器的列集合。
     *
     * @param metadata 元数据
     * @param table 表
     * @param primaryKeys 主键
     * @return 模型JDBC表观测器的列集合结果集合
     */
    private static List<ModelJdbcSchemaCanonical.ColumnSnapshot> readColumns(
            DatabaseMetaData metadata, ResolvedTable table, Set<String> primaryKeys) throws SQLException {
        List<ModelJdbcSchemaCanonical.ColumnSnapshot> columns = new ArrayList<>();
        try (ResultSet result = metadata.getColumns(
                table.catalog(), table.schema(), table.tableName(), null)) {
            while (result.next()) {
                String name = result.getString("COLUMN_NAME");
                if (name == null || name.isBlank()) {
                    continue;
                }
                int jdbcType = result.getInt("DATA_TYPE");
                if (result.wasNull()) {
                    throw new SQLException("column DATA_TYPE is null");
                }
                String typeName = result.getString("TYPE_NAME");
                int rawSize = result.getInt("COLUMN_SIZE");
                Integer size = result.wasNull() ? null : rawSize;
                int rawDigits = result.getInt("DECIMAL_DIGITS");
                Integer digits = result.wasNull() ? null : rawDigits;
                int nullableCode = result.getInt("NULLABLE");
                boolean nullable = result.wasNull()
                        || nullableCode != DatabaseMetaData.columnNoNulls;
                columns.add(new ModelJdbcSchemaCanonical.ColumnSnapshot(
                        name, jdbcType, typeName, size, digits, nullable,
                        primaryKeys.contains(canonicalName(name))));
            }
        }
        if (columns.isEmpty()) {
            throw new SQLException("existing table returned no observable columns");
        }
        return columns;
    }

    /**
     * 探测包含数据。
     *
     * @param connection 数据库连接
     * @param metadata 元数据
     * @param namespace 命名空间
     * @param table 表
     * @return 处理是否成功
     */
    private static boolean probeHasData(Connection connection, DatabaseMetaData metadata,
            Namespace namespace, ResolvedTable table) throws SQLException {
        String qualifier = table.schema();
        if (qualifier == null && namespace.catalogBased()) {
            qualifier = table.catalog();
        }
        String qualified = qualifier == null ? quote(metadata, table.tableName())
                : quote(metadata, qualifier) + '.' + quote(metadata, table.tableName());
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(1);
            statement.setQueryTimeout(30);
            try (ResultSet result = statement.executeQuery("SELECT 1 FROM " + qualified)) {
                return result.next();
            }
        }
    }

    /**
     * 按目标数据库方言引用标识符。
     *
     * @param metadata 元数据
     * @param identifier 标识符
     * @return 按目标数据库方言引用标识符文本
     */
    private static String quote(DatabaseMetaData metadata, String identifier) throws SQLException {
        String safe = ModelJdbcSchemaCanonical.requireSafeIdentifier(identifier);
        String quote = metadata.getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            if (!safe.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
                throw new SQLException("database does not expose a safe identifier quote");
            }
            return safe;
        }
        return quote + safe.replace(quote, quote + quote) + quote;
    }

    /**
     * 获取模型JDBC表观测器的安全标识符或空值。
     *
     * @param value 待处理的值
     * @return 模型JDBC表观测器的安全标识符或空值文本
     */
    private static String safeIdentifierOrNull(String value) {
        return value == null || value.isBlank() ? null
                : ModelJdbcSchemaCanonical.requireSafeIdentifier(value);
    }

    /**
     * 获取模型JDBC表观测器的规范名称。
     *
     * @param value 待处理的值
     * @return 模型JDBC表观测器的规范名称文本
     */
    private static String canonicalName(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 判断是否一致命名空间条件。
     *
     * @param expected 预期值
     * @param actual 实际值
     * @return 判断结果
     */
    private static boolean sameNamespace(String expected, String actual) {
        return expected == null ? actual == null
                : actual != null && expected.equalsIgnoreCase(actual);
    }

    /**
     * 构造模型JDBC表观测器标识键。
     *
     * @param table 表
     * @return 构造模型JDBC表观测器标识键文本
     */
    private static String key(ResolvedTable table) {
        return String.valueOf(table.catalog()) + '\u0000' + table.schema() + '\u0000' + table.tableName();
    }

    private record Namespace(String catalog, String schema, boolean catalogBased) {

    }

    private record ResolvedTable(String catalog, String schema, String tableName) {

    }

    public record Observation(boolean tableExists, boolean hasData, String schemaHash, Instant observedAt,
                              String catalog, String schema, String actualTableName,
                              List<ModelJdbcSchemaCanonical.ColumnSnapshot> columns) {

    }
}
