package cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 已验证 3.31.1 和 3.49.1 引擎的 SQLite 文件目录。不存在 {@code information_schema}。
 * {@code DatabaseMetaData.getColumns} 不是
 * 原生目录：它将 BLOB 报告为 VARCHAR，将 NUMERIC 报告为 FLOAT。
 *
 * <p>原生类型为 {@code pragma_table_xinfo.type} 文本。此版本的 {@code pragma_table_info} 会省略生成
 * 列，所以不能作为列列表。长度、precision 和 scale 从该
 * 文本解析。不带长度的类型将三者均保留为 ABSENT。{@code VARCHAR(0)} 保留字符长度 0；它
 * 不是字符值域。{@code DECIMAL} 保留空白以及大于 precision 的 scale。
 */
public final class SqliteNativeCatalog {

    private static final int COLUMN_LIMIT = 2000;

    private static final Pattern VARCHAR = Pattern.compile("(?i)varchar\\((\\d+)\\)");

    private static final Pattern CHAR = Pattern.compile("(?i)char\\((\\d+)\\)");

    private static final Pattern DECIMAL = Pattern.compile("(?i)decimal\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");

    /**
     * 初始化 {@code SqliteNativeCatalog}。
     */
    private SqliteNativeCatalog() {
    }

    /** main 数据库文件已分配的页（含空闲页），不包括 WAL、临时库或 ATTACH 的文件。 */
    public static long allocatedDatabaseBytes(Connection connection, int timeoutSeconds) throws SQLException {
        if (connection == null || timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("SQLite connection and bounded metadata timeout are required");
        }
        return preservingBusyTimeout(connection, () -> {
            long pages;
            long pageSize;
            try (var statement = statement(connection, timeoutSeconds)) {
                try (var rows = statement.executeQuery("PRAGMA main.page_count")) {
                    if (!rows.next() || (pages = rows.getLong(1)) < 0 || rows.wasNull()) {
                        throw new SQLException("SQLite page count is invalid");
                    }
                }
                try (var rows = statement.executeQuery("PRAGMA main.page_size")) {
                    if (!rows.next() || (pageSize = rows.getLong(1)) < 1 || rows.wasNull()) {
                        throw new SQLException("SQLite page size is invalid");
                    }
                }
            }
            try {
                return Math.multiplyExact(pages, pageSize);
            } catch (ArithmeticException overflow) {
                throw new SQLException("SQLite allocated storage exceeds int64");
            }
        });
    }

    /**
     * 读取单张普通表。不提交，也不保留连接。
     * Xerial 3.31 的 {@code setQueryTimeout} 会覆盖 {@code PRAGMA busy_timeout}；之后恢复原值。
     */
    public static TableSchemaMetadata read(Connection connection, String schema, String table, int timeoutSeconds)
            throws SQLException {
        if (timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("Invalid metadata timeout");
        }
        if (connection == null) {
            throw new IllegalArgumentException("SQLite connection is required");
        }
        if (schema == null || schema.isBlank() || table == null || table.isBlank()) {
            throw new IllegalArgumentException("SQLite schema and table are required");
        }
        return preservingBusyTimeout(connection, () -> observe(connection, schema, table, timeoutSeconds));
    }

    /** 生成 DDL 或写入数据前校验新的或已有目标文件，不声称已验证表事实。 */
    public static MetadataProvenance verifyConnection(Connection connection, int timeoutSeconds) throws SQLException {
        if (connection == null || timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("SQLite connection and bounded metadata timeout are required");
        }
        return preservingBusyTimeout(connection, () -> observeConnection(connection, timeoutSeconds));
    }

    /**
     * 临时调整 SQLite 忙等待超时并保留其原始设置。
     */
    private static <T> T preservingBusyTimeout(Connection connection, Observation<T> observation) throws SQLException {
        long busy = busyTimeout(connection);
        SQLException failure = null;
        try {
            return observation.read();
        } catch (SQLException ex) {
            failure = ex;
            throw ex;
        } finally {
            try {
                busyTimeout(connection, busy);
            } catch (SQLException restore) {
                if (failure != null) {
                    failure.addSuppressed(restore);
                } else {
                    throw restore;
                }
            }
        }
    }

    @FunctionalInterface
    private interface Observation<T> {

        /**
         * 读取SQLite原生目录并构建表结构元数据。
         */
        T read() throws SQLException;
    }

    /**
     * 拒绝当前实现未支持的 {@code observeConnection} 操作。
     */
    private static MetadataProvenance observeConnection(Connection connection, int timeout) throws SQLException {
        var jdbc = connection.getMetaData();
        String version = jdbc.getDatabaseProductVersion();
        var source = new MetadataProvenance(ProductTypeEnum.SQLITE3, jdbc.getDatabaseProductName(), version,
                "SQLITE_CATALOG_V1", Instant.now());
        if (!source.nativeProduct(ProductTypeEnum.SQLITE3)) {
            throw new SQLFeatureNotSupportedException("SQLite native version is unverified", "0A000");
        }
        try (var statement = statement(connection, timeout);
             var rows = statement.executeQuery("SELECT sqlite_version()")) {
            if (!rows.next() || !version.equals(rows.getString(1)) || rows.next()) {
                throw new SQLFeatureNotSupportedException("SQLite native version is unverified", "0A000");
            }
        }
        try (var statement = statement(connection, timeout);
             var rows = statement.executeQuery("PRAGMA encoding")) {
            if (!rows.next() || !"UTF-8".equals(rows.getString(1)) || rows.next()) {
                throw new SQLFeatureNotSupportedException("SQLite encoding is unverified", "0A000");
            }
        }
        return source;
    }

    /**
     * 读取当前目标对象或运行状态的最新事实。
     */
    private static TableSchemaMetadata observe(Connection connection, String schema, String table, int timeout)
            throws SQLException {
        var source = observeConnection(connection, timeout);
        String sql = tableSql(connection, schema, table, timeout);
        var words = words(sql);
        // pragma_table_info 没有排序规则。表 SQL 任意位置存在 COLLATE 关键字就不能判定 ABSENT。
        MetadataValue<String> collation = words != null && words.stream().anyMatch(token -> token.word("COLLATE"))
                ? MetadataValue.unknown() : words == null ? MetadataValue.unknown() : MetadataValue.absent();
        List<PragmaColumn> pragma = pragma(connection, schema, table, timeout);
        List<DriverColumn> driver = driver(connection, schema, table, timeout);
        if (pragma.size() != driver.size()) {
            throw new SQLException("Schema changed during metadata observation");
        }
        var columns = new ArrayList<ColumnDescription>(pragma.size());
        var keys = new ArrayList<Key>();
        for (int i = 0; i < pragma.size(); i++) {
            PragmaColumn fact = pragma.get(i);
            DriverColumn shape = driver.get(i);
            if (!fact.name.equals(shape.name)) {
                throw new SQLException("Schema changed during metadata observation");
            }
            // pk>0 仅表示属于主键。复合主键尚未验证为严格的 int64 值域。
            if (fact.pk < 0) {
                throw new SQLException("SQLite primary key position is unverified");
            }
            if (fact.pk > 0) {
                keys.add(new Key(fact.pk, fact.name));
            }
            columns.add(column(source, fact, shape, collation));
        }
        keys.sort(Comparator.comparingInt(Key::position));
        for (int i = 1; i < keys.size(); i++) {
            if (keys.get(i).position == keys.get(i - 1).position) {
                throw new SQLException("SQLite primary key position is ambiguous");
            }
        }
        List<String> primaryKeys = keys.stream().map(Key::name).toList();
        for (var column : columns) {
            column.setPk(primaryKeys.contains(column.getFieldName()));
        }
        return new TableSchemaMetadata(columns, primaryKeys, source, MetadataValue.value(true),
                additional(connection, schema, table, timeout, words));
    }

    /**
     * 返回列。
     */
    private static ColumnDescription column(MetadataProvenance source, PragmaColumn fact, DriverColumn shape,
            MetadataValue<String> collation) throws SQLException {
        var column = new ColumnDescription();
        column.setFieldName(fact.name);
        column.setDbType(ProductTypeEnum.SQLITE3);
        column.setFieldType(shape.type);
        column.setFieldTypeName(shape.typeName);
        column.setFiledTypeClassName(shape.className);
        column.setDisplaySize(shape.displaySize);
        column.setPrecisionSize(shape.precision);
        column.setScaleSize(shape.scale);
        column.setSigned(shape.signed);
        column.setAutoIncrement(shape.autoIncrement);
        // 驱动空值约束与 pragma notnull 在 NOT NULL 和主键上存在差异，不要合并二者。
        column.setNullable(shape.nullable);
        MetadataValue<Boolean> nullable = fact.notNull == 0 ? MetadataValue.value(true)
                : fact.notNull == 1 ? MetadataValue.value(false) : MetadataValue.unknown();
        Lengths lengths = lengths(fact.type);
        MetadataValue<String> generated = switch (fact.hidden) {
            case 0 -> MetadataValue.absent();
            case 2, 3 -> MetadataValue.unknown();
            default -> throw new SQLException("SQLite hidden column is unverified");
        };
        column.setDefaultValue(fact.defaultSql);
        column.setMetadataEvidence(new ColumnMetadataEvidence(source, MetadataValue.value(fact.type),
                fact.defaultSql == null ? MetadataValue.absent() : MetadataValue.value(fact.defaultSql),
                nullable, lengths.precision, lengths.scale, lengths.length,
                MetadataValue.absent(), generated, MetadataValue.absent(), collation, MetadataValue.absent()));
        return column;
    }

    /** 未声明大小的类型保持 ABSENT。JDBC precision 0 不是目录长度。 */
    private static Lengths lengths(String type) {
        Matcher decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            return new Lengths(number(decimal.group(1)), number(decimal.group(2)), MetadataValue.absent());
        }
        Matcher varchar = VARCHAR.matcher(type);
        if (varchar.matches()) {
            return new Lengths(MetadataValue.absent(), MetadataValue.absent(), characters(varchar.group(1)));
        }
        Matcher character = CHAR.matcher(type);
        if (character.matches()) {
            return new Lengths(MetadataValue.absent(), MetadataValue.absent(), characters(character.group(1)));
        }
        return new Lengths(MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent());
    }

    /**
     * 按当前值域约定解析或转换数值。
     */
    private static MetadataValue<Integer> number(String digits) {
        try {
            int value = Integer.parseInt(digits);
            return value < 0 ? MetadataValue.unknown() : MetadataValue.value(value);
        } catch (NumberFormatException ex) {
            return MetadataValue.unknown();
        }
    }

    /**
     * 返回当前字符集合。
     */
    private static MetadataValue<Long> characters(String digits) {
        try {
            long value = Long.parseLong(digits);
            return value < 0 ? MetadataValue.unknown() : MetadataValue.value(value);
        } catch (NumberFormatException ex) {
            return MetadataValue.unknown();
        }
    }

    /**
     * 根据表定义生成当前产品的表 SQL。
     */
    private static String tableSql(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        String query = "SELECT sql FROM " + quote(
                schema) + ".sqlite_master WHERE type='table' AND name=? COLLATE NOCASE";
        try (var statement = connection.prepareStatement(query)) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("SQLite table is absent");
                }
                String sql = rows.getString(1);
                if (rows.wasNull() || rows.next()) {
                    throw new SQLException("SQLite table identity is ambiguous");
                }
                return sql;
            }
        }
    }

    /**
     * "notnull" 是关键字，所以该列加引号并按位置读取。
     */
    private static List<PragmaColumn> pragma(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        // "notnull" 是关键字，所以该列加引号并按位置读取。
        // 使用 table_xinfo，而不是 table_info：table_info 缺少生成列。
        String query = "SELECT cid, name, type, \"notnull\", dflt_value, pk, hidden FROM pragma_table_xinfo(?, ?) ORDER BY cid";
        var columns = new ArrayList<PragmaColumn>();
        try (var statement = connection.prepareStatement(query)) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, table);
            statement.setString(2, schema);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (columns.size() >= COLUMN_LIMIT) {
                        throw new SQLException("Column metadata limit exceeded");
                    }
                    String name = rows.getString(2);
                    if (name == null || rows.wasNull()) {
                        throw new SQLException("SQLite column name is unverified");
                    }
                    String type = rows.getString(3);
                    // 空声明存储为 ""，不得重写为 NUMERIC。
                    if (rows.wasNull()) {
                        throw new SQLException("SQLite column type is unverified");
                    }
                    int notNull = rows.getInt(4);
                    if (rows.wasNull()) {
                        throw new SQLException("SQLite column nullability is unverified");
                    }
                    String dflt = rows.getString(5);
                    if (rows.wasNull()) {
                        dflt = null;
                    }
                    int pk = rows.getInt(6);
                    if (rows.wasNull()) {
                        throw new SQLException("SQLite primary key position is unverified");
                    }
                    int hidden = rows.getInt(7);
                    if (rows.wasNull()) {
                        throw new SQLException("SQLite hidden column is unverified");
                    }
                    if (columns.stream().anyMatch(column -> column.name.equals(name))) {
                        throw new SQLException("SQLite column name is ambiguous");
                    }
                    columns.add(new PragmaColumn(name, type, notNull, dflt, pk, hidden));
                }
            }
        }
        if (columns.isEmpty()) {
            throw new SQLException("SQLite table is absent");
        }
        return columns;
    }

    /**
     * 解析当前连接或产品使用的驱动配置。
     */
    private static List<DriverColumn> driver(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        var columns = new ArrayList<DriverColumn>();
        try (var statement = statement(connection, timeout);
             var rows = statement.executeQuery("SELECT * FROM " + quote(schema) + "." + quote(table) + " WHERE 1=0")) {
            ResultSetMetaData metadata = rows.getMetaData();
            if (metadata.getColumnCount() > COLUMN_LIMIT) {
                throw new SQLException("Column metadata limit exceeded");
            }
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                columns.add(new DriverColumn(metadata.getColumnName(i), metadata.getColumnType(i),
                        metadata.getColumnTypeName(i),
                        metadata.getColumnClassName(i), metadata.getPrecision(i), metadata.getScale(i),
                        metadata.isSigned(i),
                        metadata.isNullable(i) != ResultSetMetaData.columnNoNulls, metadata.isAutoIncrement(i),
                        metadata.getColumnDisplaySize(i)));
            }
        }
        return columns;
    }

    /**
     * 仅当索引查询和触发器查询均未返回行，且表 SQL 没有
     * CHECK、FOREIGN KEY、REFERENCES、STRICT 或 WITHOUT ROWID 子句时，才为 ABSENT。这些子句不属于两个查询的结果行。
     * 自动 {@code sqlite_autoindex} 仍是索引行；忽略它会隐藏 UNIQUE。
     */
    private static MetadataValue<List<String>> additional(Connection connection, String schema, String table,
            int timeout, List<Token> words) throws SQLException {
        List<String> objects = new ArrayList<>();
        collect(connection, timeout, objects, "SELECT name FROM " + quote(schema)
                + ".sqlite_master WHERE type='index' AND tbl_name=? COLLATE NOCASE ORDER BY name", table, "index:");
        collect(connection, timeout, objects, "SELECT name FROM " + quote(schema)
                + ".sqlite_master WHERE type='trigger' AND tbl_name=? COLLATE NOCASE ORDER BY name", table, "trigger:");
        if (words == null || unlisted(words)) {
            return MetadataValue.unknown();
        }
        return objects.isEmpty() ? MetadataValue.absent() : MetadataValue.value(objects);
    }

    /**
     * 收集当前输入中的结构或数据结果。
     */
    private static void collect(Connection connection, int timeout, List<String> objects, String query, String table,
            String prefix) throws SQLException {
        try (var statement = connection.prepareStatement(query)) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (objects.size() >= COLUMN_LIMIT) {
                        throw new SQLException("Additional schema object limit exceeded");
                    }
                    String name = rows.getString(1);
                    if (name == null) {
                        throw new SQLException("SQLite additional object is unverified");
                    }
                    objects.add(prefix + name);
                }
            }
        }
    }

    /**
     * 拒绝未列入当前支持或允许清单的对象。
     */
    private static boolean unlisted(List<Token> words) {
        for (int i = 0; i < words.size(); i++) {
            var token = words.get(i);
            if (token.word("CHECK") || token.word("REFERENCES") || token.word("FOREIGN") || token.word("STRICT")) {
                return true;
            }
            if (token.word("WITHOUT") && i + 1 < words.size() && words.get(i + 1).word("ROWID")) {
                return true;
            }
        }
        return false;
    }

    /** 带引号文本、注释和标识符不视为词，所以字符串字面量 COLLATE 不是关键字。 */
    private static List<Token> words(String sql) {
        try {
            return tokens(sql);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 将当前 SQL 或表达式拆解为语法词元。
     */
    private static List<Token> tokens(String sql) {
        if (sql == null || sql.length() > 1_048_576 || sql.indexOf('\0') >= 0) {
            throw invalid();
        }
        var out = new ArrayList<Token>();
        int i = 0;
        boolean ended = false;
        while (i < sql.length()) {
            char ch = sql.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    throw invalid();
                }
                i = end + 2;
                continue;
            }
            if (ended) {
                throw invalid();
            }
            int start = i;
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                char close = ch == '[' ? ']' : ch;
                i++;
                var value = new StringBuilder();
                boolean closed = false;
                while (i < sql.length()) {
                    char next = sql.charAt(i++);
                    if (next == close) {
                        if (close != ']' && i < sql.length() && sql.charAt(i) == close) {
                            i++;
                            value.append(close);
                            continue;
                        }
                        closed = true;
                        break;
                    }
                    value.append(next);
                }
                if (!closed) {
                    throw invalid();
                }
                out.add(new Token(value.toString(), ch));
            } else if (Character.isUnicodeIdentifierPart(ch) || ch == '$') {
                i++;
                while (i < sql.length() && (Character.isUnicodeIdentifierPart(sql.charAt(i)) || sql.charAt(i) == '$')) {
                    i++;
                }
                out.add(new Token(sql.substring(start, i), (char) 0));
            } else {
                i++;
                out.add(new Token(String.valueOf(ch), (char) 0));
                if (ch == ';') {
                    ended = true;
                }
            }
            if (out.size() > 100_000) {
                throw invalid();
            }
        }
        if (out.isEmpty()) {
            throw invalid();
        }
        return List.copyOf(out);
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("SQLite native DDL is outside the supported ordinary-table grammar");
    }

    /**
     * 返回语句。
     */
    private static Statement statement(Connection connection, int timeout) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            statement.setQueryTimeout(timeout);
            return statement;
        } catch (SQLException ex) {
            statement.close();
            throw ex;
        }
    }

    /**
     * 读取或设置 SQLite 忙等待超时。
     */
    private static long busyTimeout(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA busy_timeout")) {
            if (!rows.next()) {
                throw new SQLException("SQLite busy_timeout is unavailable");
            }
            long value = rows.getLong(1);
            if (rows.wasNull() || rows.next()) {
                throw new SQLException("SQLite busy_timeout is unavailable");
            }
            return value;
        }
    }

    /**
     * 读取或设置 SQLite 忙等待超时。
     */
    private static void busyTimeout(Connection connection, long value) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + value);
        }
    }

    /**
     * 按SQLite方言转义并引用标识符。
     */
    private static String quote(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0 || name.chars()
                .anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid SQLite identifier");
        }
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private record Token(String text, char quote) {

        /**
         * 按当前解析位置读取或匹配一个词元。
         */
        boolean word(String value) {
            return quote == 0 && text.equalsIgnoreCase(value);
        }
    }

    private record PragmaColumn(String name, String type, int notNull, String defaultSql, int pk, int hidden) {

    }

    private record DriverColumn(String name, int type, String typeName, String className, int precision, int scale,
                                boolean signed, boolean nullable, boolean autoIncrement, int displaySize) {

    }

    private record Lengths(MetadataValue<Integer> precision, MetadataValue<Integer> scale, MetadataValue<Long> length) {

    }

    private record Key(int position, String name) {

    }
}
