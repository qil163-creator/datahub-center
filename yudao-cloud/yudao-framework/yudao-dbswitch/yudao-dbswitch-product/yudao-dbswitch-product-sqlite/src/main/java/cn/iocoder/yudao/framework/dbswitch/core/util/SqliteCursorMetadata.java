package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;

/** SQLite 比较证据使用声明的亲和性和实际存储类别，绝不使用 JDBC 采样类型。 */
public final class SqliteCursorMetadata {

    /**
     * 初始化 {@code SqliteCursorMetadata}。
     */
    private SqliteCursorMetadata() {
    }

    public record Column(String name, String declaredType, String affinity, boolean notNull, int primaryOrder,
                         int hidden) {

    }

    public record Shape(Map<String, Column> columns, List<String> primaryKeys, String nativeDdl, String identity) {

    }

    /**
     * 按SQLite方言转义并引用标识符。
     */
    public static String quote(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid SQLite identifier");
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /**
     * 生成当前操作引用的限定关系名。
     */
    public static String reference(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }

    /**
     * 返回按当前方言加引号后的标识符。
     */
    public static String quoted(List<String> names) {
        return String.join(",", names.stream().map(SqliteCursorMetadata::quote).toList());
    }

    /**
     * 根据当前输入构建并返回 {@code Shape}。
     */
    public static Shape inspect(Connection c, String schema, String table, int timeout) throws SQLException {
        if (!"SQLite".equals(c.getMetaData().getDatabaseProductName())) {
            throw new SQLException("SQLite connection required");
        }
        String ddl;
        long root;
        try (var s = c.prepareStatement(
                "SELECT sql,rootpage FROM " + quote(schema) + ".sqlite_master WHERE type='table' AND name=?")) {
            s.setQueryTimeout(timeout);
            s.setString(1, table);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    throw new SQLException("SQLite ordinary table absent");
                }
                ddl = r.getString(1);
                root = r.getLong(2);
                if (r.next() || ddl == null || root <= 0 || ddl.toUpperCase(Locale.ROOT).startsWith("CREATE VIRTUAL")) {
                    throw new SQLException("SQLite ordinary storage required");
                }
            }
        }
        var columns = new LinkedHashMap<String, Column>();
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            try (var r = s.executeQuery("PRAGMA " + quote(schema) + ".table_xinfo(" + quote(table) + ")")) {
                while (r.next()) {
                    if (columns.size() >= 4096) {
                        throw new SQLException("SQLite metadata bound exceeded");
                    }
                    String type = r.getString("type"), name = r.getString("name");
                    columns.put(name, new Column(name, type, affinity(type), r.getInt("notnull") != 0, r.getInt("pk"),
                            r.getInt("hidden")));
                }
            }
        }
        if (columns.isEmpty()) {
            throw new SQLException("SQLite columns absent");
        }
        var keys = columns.values().stream().filter(v -> v.primaryOrder() > 0)
                .sorted(Comparator.comparingInt(Column::primaryOrder)).map(Column::name).toList();
        return new Shape(Map.copyOf(columns), keys, ddl,
                digest("SQLITE_TABLE_V1\0" + fileIdentity(c, schema, timeout) + "\0" + root + "\0" + ddl));
    }

    /** 路径和时间不能作为代次证据；可用时，稳定的 fileKey 增强部署固定标识。 */
    public static String fileIdentity(Connection c, String schema, int timeout) throws SQLException {
        String file = null;
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            try (var r = s.executeQuery("PRAGMA database_list")) {
                while (r.next()) {
                    if (schema.equals(r.getString(2))) {
                        if (file != null) {
                            throw new SQLException("Ambiguous SQLite file");
                        }
                        file = r.getString(3);
                    }
                }
            }
        }
        if (file == null || file.isBlank()) {
            throw new SQLException("SQLite durable cursor requires an actual database file");
        }
        try {
            Path path = Path.of(file).toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw new SQLException("SQLite source is not a regular file");
            }
            return digest(
                    "SQLITE_FILE_V1\0" + path + "\0" + (attributes.fileKey() == null ? "EXTERNAL_GENERATION_REQUIRED" : attributes.fileKey()
                            .toString()));
        } catch (java.io.IOException invalid) {
            throw new SQLException("SQLite source file identity unavailable");
        }
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    public static CursorKeyContract keyContract(Connection c, String schema, String table, List<String> keys,
            Shape shape, int timeout, boolean primary) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            try (var r = s.executeQuery("PRAGMA " + quote(schema) + ".encoding")) {
                if (!r.next() || !"UTF-8".equalsIgnoreCase(r.getString(1))) {
                    throw new SQLException("SQLite binary cursor contract requires UTF-8 storage");
                }
            }
        }
        if (keys.isEmpty() || primary && !keys.equals(shape.primaryKeys())) {
            throw new SQLException("SQLite cursor requires the exact ordered primary key");
        }
        List<String> collations = null;
        boolean pkIndex = false;
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            try (var r = s.executeQuery("PRAGMA " + quote(schema) + ".index_list(" + quote(table) + ")")) {
                while (r.next()) {
                    pkIndex |= "pk".equals(r.getString("origin"));
                    if (r.getInt("unique") != 1 || r.getInt("partial") != 0 || primary && !"pk".equals(
                            r.getString("origin"))) {
                        continue;
                    }
                    var names = new ArrayList<String>();
                    var comparisons = new ArrayList<String>();
                    boolean valid = true;
                    try (var detail = c.createStatement()) {
                        detail.setQueryTimeout(timeout);
                        try (var x = detail.executeQuery(
                                "PRAGMA " + quote(schema) + ".index_xinfo(" + quote(r.getString("name")) + ")")) {
                            while (x.next()) {
                                if (x.getInt("key") == 1) {
                                    if (x.getInt("cid") < 0 || x.getInt("desc") != 0) {
                                        valid = false;
                                    }
                                    names.add(x.getString("name"));
                                    comparisons.add(x.getString("coll"));
                                }
                            }
                        }
                    }
                    if (valid && names.equals(keys)) {
                        collations = comparisons;
                    }
                }
            }
        }
        boolean rowidAlias = !pkIndex && keys.equals(shape.primaryKeys()) && keys.size() == 1 && shape.columns()
                .get(keys.getFirst()).declaredType().equalsIgnoreCase("INTEGER");
        if (collations == null && rowidAlias) {
            collations = List.of("BINARY");
        }
        if (collations == null || collations.stream().anyMatch(v -> !"BINARY".equalsIgnoreCase(v))) {
            throw new SQLException("SQLite complete ascending BINARY unique key required");
        }
        var domains = new ArrayList<CursorKeyContract.Domain>();
        var description = new StringBuilder("SQLITE_KEY_BINARY_V1");
        for (String key : keys) {
            var column = shape.columns().get(key);
            if (column == null || column.hidden() != 0 || !column.notNull() && !rowidAlias || !Set.of("INTEGER", "TEXT")
                    .contains(column.affinity())) {
                throw new SQLException("SQLite nonnull exact integer/text keys required");
            }
            domains.add(column.affinity().equals("INTEGER") ? CursorKeyContract.Domain.integer(64,
                    false) : CursorKeyContract.Domain.nativeOnly());
            description.append('\0').append(column.affinity()).append(":BINARY");
        }
        return new CursorKeyContract(ProductTypeEnum.SQLITE3, digest(description.toString()), domains);
    }

    /**
     * 校验并取得存储类型集合。
     */
    public static void requireStorageTypes(Connection c, String schema, String table, List<String> columns, Shape shape,
            int timeout) throws SQLException {
        var invalid = new ArrayList<String>();
        for (String name : columns) {
            var column = shape.columns().get(name);
            if (column == null || column.hidden() != 0 || !Set.of("INTEGER", "TEXT").contains(column.affinity())) {
                throw new SQLException("SQLite cursor type is unproven");
            }
            invalid.add(
                    "typeof(" + quote(name) + ")<>" + (column.affinity().equals("INTEGER") ? "'integer'" : "'text'"));
        }
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            try (var r = s.executeQuery("SELECT 1 FROM " + reference(schema, table) + " WHERE " + String.join(" OR ",
                    invalid) + " LIMIT 1")) {
                if (r.next()) {
                    throw new SQLException("SQLite cursor contains NULL or mixed storage classes");
                }
            }
        }
    }

    /**
     * 校验并取得重放安全。
     */
    public static void requireReplaySafe(Connection c, String schema, String table, int timeout) throws SQLException {
        try (var s = c.prepareStatement(
                "SELECT 1 FROM " + quote(schema) + ".sqlite_master WHERE type='trigger' AND tbl_name=? LIMIT 1")) {
            s.setQueryTimeout(timeout);
            s.setString(1, table);
            try (var r = s.executeQuery()) {
                if (r.next()) {
                    throw new SQLException("SQLite cursor replay with triggers is unsupported");
                }
            }
        }
        try (var s = c.prepareStatement(
                "SELECT 1 FROM temp.sqlite_master WHERE type='trigger' AND tbl_name=? LIMIT 1")) {
            s.setQueryTimeout(timeout);
            s.setString(1, table);
            try (var r = s.executeQuery()) {
                if (r.next()) {
                    throw new SQLException("SQLite replay with temporary triggers is unsupported");
                }
            }
        }
    }

    /**
     * 返回当前类型亲和性。
     */
    public static String affinity(String type) {
        String name = type.toUpperCase(Locale.ROOT);
        if (name.contains("INT")) {
            return "INTEGER";
        }
        if (name.contains("CHAR") || name.contains("CLOB") || name.contains("TEXT")) {
            return "TEXT";
        }
        if (name.contains("BLOB") || name.isEmpty()) {
            return "BLOB";
        }
        if (name.contains("REAL") || name.contains("FLOA") || name.contains("DOUB")) {
            return "REAL";
        }
        return "NUMERIC";
    }

    /**
     * 按当前产品的精确值域读取或转换字段值。
     */
    public static Object exactValue(Object value, Column column) {
        if (value == null) {
            return null;
        }
        return switch (column.affinity()) {
            case "INTEGER" -> JdbcValueConversions.toLong(value);
            case "TEXT" -> {
                String text = JdbcValueConversions.toText(value);
                try {
                    java.nio.charset.StandardCharsets.UTF_8.newEncoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .encode(java.nio.CharBuffer.wrap(text));
                } catch (java.nio.charset.CharacterCodingException invalid) {
                    throw new IllegalArgumentException("SQLite text contains an invalid Unicode sequence");
                }
                yield text;
            }
            case "BLOB" -> {
                if (!(value instanceof byte[])) {
                    throw new IllegalArgumentException("SQLite BLOB requires owned bytes");
                }
                yield ((byte[]) value).clone();
            }
            case "REAL" -> {
                if (!(value instanceof Float || value instanceof Double)) {
                    throw new IllegalArgumentException("SQLite REAL input requires explicit approximate numeric type");
                }
                yield JdbcValueConversions.toDouble(value);
            }
            default -> throw new IllegalArgumentException(
                    "SQLite NUMERIC affinity cannot guarantee arbitrary exact decimal preservation");
        };
    }

    /**
     * 根据当前内容计算摘要，供一致性比较使用。
     */
    public static String digest(String value) {
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(value);
    }
}
