package cn.iocoder.yudao.framework.dbswitch.product.clickhouse;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用于类型化游标和基于变更的更新插入的 ClickHouse 25.8 关系结构：物理代次（服务器 UUID、
 * Atomic 数据库 UUID、表 UUID、引擎）、精确 ORDER BY（排序键）列列表、有界列
 * 目录及 JDBC 编解码器，以及活动片段证据。排序键按普通列列表读取；任何
 * 表达式、不可验证标识符、ReplicatedMergeTree 或临时关系均拒绝。键仅接受
 * 精确的有符号/无符号整数、Decimal、Date/Date32、DateTime/DateTime64 和 String；文本键按 UTF-8
 * 字节序比较，且仅在相同原生契约下重放。时间戳为本地日期时间，绝非纪元计数。
 * 仅负责有界目录读取。
 */
public final class ClickhouseCursorMetadata {

    public static final String VERIFIED_SERVER = "25.8.";

    private static final Pattern INTEGER = Pattern.compile("(U?)Int(8|16|32|64)");

    private static final Pattern DECIMAL = Pattern.compile("Decimal\\(([1-9][0-9]?)\\s*,\\s*([0-9]{1,2})\\)");

    private static final Pattern TEMPORAL = Pattern.compile("Date32?|DateTime(?:64\\([1-9]\\))?");

    private static final Pattern TEXT = Pattern.compile("String|LowCardinality\\(String\\)");

    private static final Pattern PART = Pattern.compile("all_[0-9]+_[0-9]+_[0-9]+(_[0-9]+)?");

    /**
     * 初始化 {@code ClickhouseCursorMetadata}。
     */
    private ClickhouseCursorMetadata() {
    }

    public record Column(String name, String type, int jdbcType, int precision, int scale, boolean nullable,
                         boolean immutable) {

        /**
         * 校验构造参数并初始化 {@code Column}。
         */
        public Column {
            ClickhouseSql.quote(name);
            if (type == null || type.isBlank() || type.length() > 128) {
                throw new IllegalArgumentException("ClickHouse column type is incomplete");
            }
        }
    }

    public record Shape(String schema, String table, String server, String databaseUuid, String tableUuid,
                        String engine,
                        String version, String sortingKeySql, List<String> sortingKey, List<Column> columns, long parts,
                        String minPart) {

        /**
         * 返回列。
         */
        public Column column(String name) {
            for (var column : columns) {
                if (column.name().equals(name)) {
                    return column;
                }
            }
            throw new IllegalArgumentException("ClickHouse column is not part of the verified relation");
        }

        /** 不受数据变化影响的物理身份：INSERT/TRUNCATE 后保持，重建或结构修改后改变。 */
        public String relationEvidence() {
            var text = new StringBuilder("CLICKHOUSE_CURSOR_RELATION_V1\0").append(version).append('\0')
                    .append(server).append('\0').append(databaseUuid).append('\0').append(tableUuid).append('\0')
                    .append(engine).append('\0').append(sortingKeySql);
            for (var column : columns) {
                text.append('\0').append(column.name()).append(':').append(normalize(column.type()))
                        .append(':').append(column.jdbcType()).append(':').append(column.precision()).append(':')
                        .append(column.scale());
            }
            return text.toString();
        }

        /** 每次运行捕获的、随数据变化的片段证据：TRUNCATE/重载会移除最旧活动片段。 */
        public String partsEvidence() {
            return "CLICKHOUSE_PARTS_V1\0" + parts + '\0' + minPart;
        }

        /** 有序键相等值域。不包含列名，使显式重命名仍然可行。 */
        public String keyEvidence() {
            var text = new StringBuilder("CLICKHOUSE_CURSOR_KEY_V1\0").append(version);
            for (String key : sortingKey) {
                text.append('\0').append(normalize(column(key).type()));
            }
            return DigestUtil.sha256Hex(text.toString());
        }

        /**
         * 返回当前源端读取契约。
         */
        public CursorKeyContract sourceContract() {
            var domains = new ArrayList<CursorKeyContract.Domain>();
            for (String key : sortingKey) {
                domains.add(domain(column(key)));
            }
            return new CursorKeyContract(ProductTypeEnum.CLICKHOUSE, keyEvidence(), domains);
        }

        /** 经过验证的唯一键列所对应的更新插入目标契约。 */
        public CursorKeyContract targetContract(List<String> keys) {
            var text = new StringBuilder("CLICKHOUSE_CURSOR_KEY_V1\0").append(version);
            var domains = new ArrayList<CursorKeyContract.Domain>();
            for (String key : keys) {
                var column = column(key);
                domains.add(domain(column));
                text.append('\0').append(normalize(column.type()));
            }
            return new CursorKeyContract(ProductTypeEnum.CLICKHOUSE, DigestUtil.sha256Hex(text.toString()), domains);
        }
    }

    /** 精确键值域：整数位宽、十进制精度和小数位数；时间与文本仅接受原生类型。 */
    public static CursorKeyContract.Domain domain(Column column) {
        if (column.nullable()) {
            throw unsupportedArgument("ClickHouse nullable columns cannot be cursor keys");
        }
        String type = base(column.type());
        Matcher integer = INTEGER.matcher(type);
        if (integer.matches()) {
            boolean unsigned = !integer.group(1).isEmpty();
            int bits = Integer.parseInt(integer.group(2));
            return CursorKeyContract.Domain.integer(bits, unsigned);
        }
        Matcher decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            int precision = Integer.parseInt(decimal.group(1)), scale = Integer.parseInt(decimal.group(2));
            return CursorKeyContract.Domain.decimal(precision, scale, false);
        }
        if (TEMPORAL.matcher(type).matches() || TEXT.matcher(type).matches()) {
            return CursorKeyContract.Domain.nativeOnly();
        }
        throw unsupportedArgument("ClickHouse cursor key type is not verified");
    }

    /**
     * 判断当前类型是否处于已支持的游标键值域。
     */
    public static boolean supportedKeyType(Column column) {
        if (column.nullable()) {
            return false;
        }
        String type = base(column.type());
        return INTEGER.matcher(type).matches() || DECIMAL.matcher(type).matches()
                || TEMPORAL.matcher(type).matches() || TEXT.matcher(type).matches();
    }

    /** 值编解码器：精确数值、本地日期时间、文本、布尔值和有限浮点数；其他类型均拒绝。 */
    public static boolean supportedValueType(Column column) {
        String type = base(column.type());
        return INTEGER.matcher(type).matches() || DECIMAL.matcher(type).matches() || TEMPORAL.matcher(type).matches()
                || TEXT.matcher(type).matches() || type.equals("Bool") || type.equals("Float32") || type.equals(
                "Float64");
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Shape read(Connection connection, String schema, String table, int timeout) throws SQLException {
        ClickhouseSql.table(schema, table);
        if (connection == null || timeout < 1) {
            throw unsupported("ClickHouse cursor requires a bounded connection");
        }
        String version = one(connection, "SELECT version()", timeout);
        if (!version.startsWith(VERIFIED_SERVER)) {
            throw unsupported("ClickHouse server version is not verified for typed cursors");
        }
        String server = one(connection, "SELECT toString(serverUUID())", timeout);
        if (!uuid(server)) {
            throw unsupported("ClickHouse server identity is not verified");
        }
        String[] databaseRow = pair(connection,
                "SELECT toString(uuid), engine FROM system.databases WHERE name=? LIMIT 2", schema, timeout);
        if (!uuid(databaseRow[0]) || !"Atomic".equals(databaseRow[1])) {
            throw unsupported("Only ClickHouse Atomic databases are verified");
        }
        String[] tableRow = pair(connection,
                "SELECT engine, toString(uuid), sorting_key FROM system.tables WHERE database=? AND name=? AND is_temporary=0 LIMIT 2",
                schema + '\0' + table, timeout);
        if (!"MergeTree".equals(tableRow[0]) || !uuid(tableRow[1])) {
            throw unsupported("Only permanent ClickHouse MergeTree relations are verified");
        }
        List<String> sortingKey = sortingKey(tableRow[2]);
        var described = new ArrayList<String[]>();
        try (var statement = connection.prepareStatement(
                "SELECT name, type, is_in_sorting_key, is_in_partition_key, is_in_primary_key"
                        + " FROM system.columns WHERE database=? AND table=? ORDER BY position")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(4097);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (described.size() == 4096) {
                        throw unsupported("ClickHouse column count exceeds the cursor bound");
                    }
                    String name = rows.getString(1), type = rows.getString(2);
                    if (name == null || type == null || type.isBlank() || type.length() > 128) {
                        throw unsupported("ClickHouse column description is incomplete");
                    }
                    boolean immutable = rows.getBoolean(3) || rows.getBoolean(4) || rows.getBoolean(5);
                    described.add(new String[]{name, type, Boolean.toString(immutable)});
                }
            }
        }
        if (described.isEmpty() || sortingKey.isEmpty()
                || !new java.util.HashSet<>(described.stream().map(column -> column[0]).toList()).containsAll(
                sortingKey)) {
            throw unsupported("ClickHouse sorting key is not a plain column list of this relation");
        }
        var codecs = new HashMap<String, Column>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery(
                    "SELECT " + ClickhouseSql.columns(described.stream().map(column -> column[0]).toList())
                            + " FROM " + ClickhouseSql.table(schema, table) + " LIMIT 0")) {
                var metadata = rows.getMetaData();
                if (metadata.getColumnCount() != described.size()) {
                    throw unsupported("ClickHouse projection metadata is ambiguous");
                }
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    if (metadata.getColumnType(index) == Types.NULL) {
                        throw unsupported("ClickHouse driver cannot represent a physical column type");
                    }
                    String[] entry = described.get(index - 1);
                    var column = new Column(entry[0], entry[1], metadata.getColumnType(index),
                            metadata.getPrecision(index),
                            metadata.getScale(index),
                            metadata.isNullable(index) != java.sql.ResultSetMetaData.columnNoNulls,
                            Boolean.parseBoolean(entry[2]));
                    if (!normalize(entry[1]).equals(normalize(metadata.getColumnTypeName(index)))) {
                        throw unsupported("ClickHouse driver type mapping is not verified for column " + entry[0]);
                    }
                    codecs.put(entry[0], column);
                }
            }
        }
        var columns = new ArrayList<Column>();
        for (String[] entry : described) {
            var column = codecs.get(entry[0]);
            if (sortingKey.contains(entry[0]) && (column.nullable() || !supportedKeyType(column))) {
                throw unsupported("ClickHouse sorting key columns require verified non-nullable monotonic types");
            }
            columns.add(column);
        }
        long parts;
        String minPart;
        try (var statement = connection.prepareStatement(
                "SELECT count(), min(name) FROM system.parts WHERE database=? AND table=? AND active")) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("ClickHouse parts evidence is absent or not visible");
                }
                parts = rows.getLong(1);
                minPart = rows.getString(2);
                // 驱动将 NULL 聚合值渲染为空字符串；空 MergeTree 没有活动片段。
                if (minPart != null && minPart.isEmpty()) {
                    minPart = null;
                }
                if (parts < 0 || parts == 0 && minPart != null || parts > 0 && (minPart == null || !PART.matcher(
                        minPart).matches()) || rows.next()) {
                    throw unsupported("ClickHouse parts evidence is incomplete");
                }
            }
        }
        return new Shape(schema, table, server, databaseRow[0], tableRow[1], tableRow[0], version, tableRow[2],
                List.copyOf(sortingKey), List.copyOf(columns), parts, minPart);
    }

    /** 仅接受普通列列表：表达式、元组和不可验证标识符均拒绝。 */
    private static List<String> sortingKey(String rendered) throws SQLException {
        if (rendered == null || rendered.isBlank() || rendered.length() > 4096 || rendered.indexOf('(') >= 0) {
            throw unsupported("ClickHouse expression sorting keys are not verified");
        }
        var keys = new ArrayList<String>();
        for (String token : rendered.split(", ", -1)) {
            String name;
            if (token.startsWith("`") && token.endsWith("`") && token.length() >= 2) {
                name = token.substring(1, token.length() - 1).replace("\\`", "`").replace("\\\\", "\\");
            } else if (token.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
                name = token;
            } else {
                throw unsupported("ClickHouse sorting key cannot be verified as a plain column list");
            }
            ClickhouseSql.quote(name);
            if (keys.contains(name)) {
                throw unsupported("ClickHouse sorting key repeats a column");
            }
            keys.add(name);
        }
        return keys;
    }

    /** 投影列的精确源值。 */
    public static Object readValue(ResultSet rows, int index, Column column) throws SQLException {
        Object value = rows.getObject(index);
        if (value == null) {
            if (!column.nullable()) {
                throw unsupported("ClickHouse non-nullable column value is missing");
            }
            return null;
        }
        try {
            return canonical(value, column);
        } catch (IllegalArgumentException invalid) {
            throw unsupported("ClickHouse projected value is outside its verified codec");
        }
    }

    /** 与驱动编解码器一致的规范 Java 值：有符号整数保留装箱类型，无符号整数拓宽为 BigInteger。 */
    public static Object canonical(Object value, Column column) {
        String type = base(column.type());
        Matcher integer = INTEGER.matcher(type);
        if (integer.matches()) {
            if (!(value instanceof Number number)) {
                throw unsupportedArgument("ClickHouse integer value class differs");
            }
            BigInteger exact;
            try {
                exact = number instanceof BigDecimal decimal ? decimal.toBigIntegerExact() : new BigInteger(
                        number.toString());
            } catch (NumberFormatException | ArithmeticException invalid) {
                throw unsupportedArgument("ClickHouse integer value is not exact");
            }
            boolean unsigned = !integer.group(1).isEmpty();
            int bits = Integer.parseInt(integer.group(2));
            BigInteger boundary = BigInteger.ONE.shiftLeft(unsigned ? bits : bits - 1);
            if (exact.compareTo(unsigned ? BigInteger.ZERO : boundary.negate()) < 0 || exact.compareTo(boundary) >= 0) {
                throw unsupportedArgument("ClickHouse integer is outside its native range");
            }
            if (!unsigned) {
                return bits == 8 ? (Object) exact.byteValueExact() : bits == 16 ? (Object) exact.shortValueExact()
                        : bits == 32 ? (Object) exact.intValueExact() : exact.longValueExact();
            }
            return bits == 8 ? (Object) exact.shortValueExact() : bits == 16 ? (Object) exact.intValueExact()
                    : bits == 32 ? (Object) exact.longValueExact() : exact;
        }
        Matcher decimal = DECIMAL.matcher(type);
        if (decimal.matches()) {
            if (!(value instanceof BigDecimal big) || big.scale() != column.scale() || big.precision() > column.precision()) {
                throw unsupportedArgument("ClickHouse decimal value is not exact");
            }
            return big;
        }
        return switch (type) {
            case "Date", "Date32" -> {
                if (value instanceof LocalDate date) {
                    requireTemporalRange(column, value);
                    yield date;
                }
                throw unsupportedArgument("ClickHouse date value class differs");
            }
            case "DateTime", "DateTime64(1)", "DateTime64(2)", "DateTime64(3)", "DateTime64(4)", "DateTime64(5)",
                 "DateTime64(6)", "DateTime64(7)", "DateTime64(8)", "DateTime64(9)" -> {
                if (value instanceof LocalDateTime time) {
                    requireTemporalRange(column, value);
                    yield time;
                }
                throw unsupportedArgument("ClickHouse datetime value class differs");
            }
            case "String", "LowCardinality(String)" -> {
                if (value instanceof String text) {
                    yield text;
                }
                throw unsupportedArgument("ClickHouse text value class differs");
            }
            case "Bool" -> {
                if (value instanceof Boolean) {
                    yield value;
                }
                throw unsupportedArgument("ClickHouse boolean value class differs");
            }
            case "Float32" -> {
                if (value instanceof Float) {
                    yield value;
                }
                throw unsupportedArgument("ClickHouse float value class differs");
            }
            case "Float64" -> {
                if (value instanceof Double) {
                    yield value;
                }
                throw unsupportedArgument("ClickHouse double value class differs");
            }
            default -> throw unsupportedArgument("ClickHouse value type requires an explicit cursor codec");
        };
    }

    /** 单个有序键的类型化游标分量。 */
    public static TypedCursorValue cursorValue(Column column, Object value) {
        return TypedCursorValue.of(cursorType(column), cursorTypeName(column), canonical(value, column));
    }

    /** 校验持久化分量，并返回其规范比较值。 */
    public static Object cursorComponent(Column column, TypedCursorValue value) {
        if (value == null || value.jdbcType() != cursorType(column) || !value.typeName()
                .equals(cursorTypeName(column))) {
            throw new IllegalArgumentException("ClickHouse cursor tuple type changed");
        }
        return canonical(value.jdbcValue(), column);
    }

    /** Decimal 参数必须以精确类型化文本绑定；其他键以规范值绑定。 */
    public static String placeholder(Column column) {
        Matcher decimal = DECIMAL.matcher(base(column.type()));
        if (decimal.matches()) {
            return "CAST(? AS Decimal(" + Integer.parseInt(decimal.group(1)) + "," + Integer.parseInt(
                    decimal.group(2)) + "))";
        }
        return "?";
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    public static void bind(PreparedStatement statement, int index, Column column, Object value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
            return;
        }
        Matcher decimal = DECIMAL.matcher(base(column.type()));
        if (decimal.matches()) {
            statement.setString(index, ((BigDecimal) value).toPlainString());
            return;
        }
        if (value instanceof String) {
            statement.setString(index, (String) value);
            return;
        }
        // 变更表达式和谓词没有列类型上下文：将时间值绑定为精确文本，避免
        // 服务器隐式转换丢失亚秒位数（真实驱动已观察到 DateTime64 精度丢失）。
        if (value instanceof LocalDateTime || value instanceof LocalDate) {
            statement.setString(index, temporalText(column, value));
            return;
        }
        statement.setObject(index, value);
    }

    /** 使用列自身精度的精确本地日期时间文本；绝不使用纪元计数或驱动默认格式。 */
    public static String temporalText(Column column, Object value) {
        requireTemporalRange(column, value);
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof LocalDateTime time) {
            String type = base(column.type());
            if (type.startsWith("DateTime64(")) {
                int digits = Integer.parseInt(type.substring("DateTime64(".length(), type.length() - 1));
                return String.format("%04d-%02d-%02d %02d:%02d:%02d", time.getYear(), time.getMonthValue(),
                        time.getDayOfMonth(),
                        time.getHour(), time.getMinute(), time.getSecond())
                        + (digits == 0 ? "" : "." + String.format("%09d", time.getNano()).substring(0, digits));
            }
            if (type.equals("DateTime")) {
                return String.format("%04d-%02d-%02d %02d:%02d:%02d", time.getYear(), time.getMonthValue(),
                        time.getDayOfMonth(),
                        time.getHour(), time.getMinute(), time.getSecond());
            }
            throw unsupportedArgument("ClickHouse temporal value requires a temporal column");
        }
        throw unsupportedArgument("ClickHouse temporal value class differs");
    }

    /**
     * 服务器会默默钳制超范围本地日期时间，而不是拒绝；写入路径绝不能
     * 提交服务器会改变的值。25.8 已验证：DateTime64/Date32 保持 1900..2299 范围。
     */
    public static void requireTemporalRange(Column column, Object value) {
        String type = base(column.type());
        if (value instanceof LocalDate date) {
            if (type.equals("Date32")) {
                if (date.isBefore(LocalDate.of(1900, 1, 1)) || date.isAfter(LocalDate.of(2299, 12, 31))) {
                    throw unsupportedArgument("ClickHouse Date32 value is outside its native range");
                }
                return;
            }
            if (type.equals("Date")) {
                if (date.isBefore(LocalDate.of(1970, 1, 1)) || date.isAfter(LocalDate.of(2149, 6, 6))) {
                    throw unsupportedArgument("ClickHouse Date value is outside its native range");
                }
                return;
            }
        }
        if (value instanceof LocalDateTime time) {
            if (type.startsWith("DateTime64(")) {
                if (time.isBefore(LocalDateTime.of(1900, 1, 1, 0, 0)) || time.isAfter(
                        LocalDateTime.of(2299, 12, 31, 23, 59, 59, 999999999))) {
                    throw unsupportedArgument("ClickHouse DateTime64 value is outside its native range");
                }
                return;
            }
            if (type.equals("DateTime")) {
                if (time.isBefore(LocalDateTime.of(1970, 1, 1, 0, 0)) || time.isAfter(
                        LocalDateTime.of(2106, 2, 7, 6, 28, 15))) {
                    throw unsupportedArgument("ClickHouse DateTime value is outside its native range");
                }
                return;
            }
        }
        throw unsupportedArgument("ClickHouse temporal value class differs");
    }

    /**
     * 返回游标类型。
     */
    private static int cursorType(Column column) {
        String type = base(column.type());
        if (DECIMAL.matcher(type).matches()) {
            return Types.NUMERIC;
        }
        return switch (type) {
            case "Int8" -> Types.TINYINT;
            case "UInt8", "Int16" -> Types.SMALLINT;
            case "UInt16", "Int32" -> Types.INTEGER;
            case "UInt32", "Int64" -> Types.BIGINT;
            case "UInt64" -> Types.NUMERIC;
            case "Date", "Date32" -> Types.DATE;
            case "DateTime", "DateTime64(1)", "DateTime64(2)", "DateTime64(3)", "DateTime64(4)", "DateTime64(5)",
                 "DateTime64(6)", "DateTime64(7)", "DateTime64(8)", "DateTime64(9)" -> Types.TIMESTAMP;
            case "String", "LowCardinality(String)" -> Types.VARCHAR;
            default -> throw new IllegalArgumentException("ClickHouse cursor key type is unsupported");
        };
    }

    /**
     * 返回游标键在当前数据库中的原生类型名称。
     */
    private static String cursorTypeName(Column column) {
        return normalize(column.type());
    }

    /**
     * 将类型整理为当前协议使用的规范形式。
     */
    private static String normalize(String type) {
        return type.replace(" ", "");
    }

    /**
     * 返回当前基础。
     */
    private static String base(String type) {
        return type.startsWith("Nullable(") && type.endsWith(")") ? type.substring(9, type.length() - 1) : type;
    }

    /**
     * 返回当前成对元素。
     */
    private static String[] pair(Connection connection, String sql, String key, int timeout) throws SQLException {
        String[] parts = key.split("\0", -1);
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            statement.setString(1, parts[0]);
            if (parts.length > 1) {
                statement.setString(2, parts[1]);
            }
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported("ClickHouse catalog evidence is absent or not visible");
                }
                String[] values = new String[rows.getMetaData().getColumnCount()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = rows.getString(i + 1);
                    if (values[i] == null || values[i].isBlank() || values[i].length() > 1024) {
                        throw unsupported("ClickHouse catalog evidence is unavailable");
                    }
                }
                if (rows.next()) {
                    throw unsupported("ClickHouse catalog evidence is ambiguous");
                }
                return values;
            }
        }
    }

    /**
     * 读取查询结果中的单个标量或唯一记录。
     */
    private static String one(Connection connection, String sql, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery(sql)) {
                if (!rows.next()) {
                    throw unsupported("ClickHouse evidence is absent or not visible");
                }
                String value = rows.getString(1);
                if (value == null || value.isBlank() || value.length() > 1024 || rows.next()) {
                    throw unsupported("ClickHouse evidence is unavailable");
                }
                return value;
            }
        }
    }

    /**
     * 按当前值域约定解析或转换UUID 值。
     */
    private static boolean uuid(String value) {
        return value != null && !"00000000-0000-0000-0000-000000000000".equals(value)
                && value.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    static SQLFeatureNotSupportedException unsupported(String reason) {
        return new SQLFeatureNotSupportedException(reason, "0A000");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    static IllegalArgumentException unsupportedArgument(String reason) {
        return new IllegalArgumentException(reason);
    }
}
