package cn.iocoder.yudao.framework.dbswitch.product.oceanbase;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.util.OceanbaseCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.util.OceanbaseCursorMetadata.Column;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.*;
import java.util.Locale;

/**
 * OceanBase MySQL 模式的已验证值域，用于类型化游标读取及 UPSERT 绑定。
 * Connector/J 2.4.12 在 getString/getTimestamp/getObject(LocalDateTime) 中偏移夏令时跳跃期间的 DATETIME 值，
 * FLOAT 文本丢失位数，严格模式仍会舍入 DECIMAL scale、整数小数部分及 DATE 时间部分。
 * 因此时间值仅作为服务器文本传输（CAST AS CHAR / CAST(? AS ...)），每个目标
 * 值在首个 DML 前均证明可表示。一天内的 TIME 读取为 LocalTime，其他情况为有符号
 * Duration；写入绝不接受仅含毫秒的 java.sql.Time。未列出的类型（FLOAT、DOUBLE、
 * BIT、YEAR、JSON、ENUM、SET、空间类型）在任何副作用前拒绝。
 */
public final class OceanbaseExactValues {

    public enum Kind {INTEGER, DECIMAL, TEXT, BINARY, DATE, DATETIME, TIMESTAMP, TIME}

    private static final long[] POWERS = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000, 1_000_000_000};

    private static final long MICROS_PER_DAY = 86_400_000_000L;

    /**
     * 初始化 {@code OceanbaseExactValues}。
     */
    private OceanbaseExactValues() {
    }

    /**
     * 根据当前输入判定类型或操作种类。
     */
    public static Kind kind(Column column) throws SQLException {
        Kind kind = switch (column.type()) {
            case "tinyint", "smallint", "mediumint", "int", "bigint" -> Kind.INTEGER;
            case "decimal" -> Kind.DECIMAL;
            case "char", "varchar", "tinytext", "text", "mediumtext", "longtext" -> Kind.TEXT;
            case "binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob" -> Kind.BINARY;
            case "date" -> Kind.DATE;
            case "datetime" -> Kind.DATETIME;
            case "timestamp" -> Kind.TIMESTAMP;
            case "time" -> Kind.TIME;
            default -> throw OceanbaseCursorMetadata.unsupported(
                    "OceanBase column type is not verified for typed cursor/UPSERT");
        };
        if (kind == Kind.DECIMAL && (column.precision() == null || column.scale() == null || column.scale() < 0 || column.scale() > column.precision())
                || (kind == Kind.DATETIME || kind == Kind.TIMESTAMP || kind == Kind.TIME) && (column.temporalScale() == null
                || column.temporalScale() < 0 || column.temporalScale() > 6)) {
            throw OceanbaseCursorMetadata.unsupported("OceanBase numeric or temporal precision is unavailable");
        }
        return kind;
    }

    /** UPSERT 目标还要求 UTF-8 文本存储，以及普通可写列。 */
    public static Kind writable(Column column) throws SQLException {
        Kind kind = kind(column);
        if (column.isGenerated() || kind == Kind.TEXT && !"utf8mb4".equals(column.charset())) {
            throw OceanbaseCursorMetadata.unsupported(
                    "OceanBase target column is generated or does not store UTF-8 text");
        }
        return kind;
    }

    // ---- 读取 ---------------------------------------------------------------------------------------

    /** 时间列投影为服务器文本；TIMESTAMP 使用 UTC 会话时区。 */
    public static String projection(Column column) throws SQLException {
        Kind kind = kind(column);
        String quoted = OceanbaseCursorMetadata.quote(column.name());
        return kind == Kind.DATE || kind == Kind.DATETIME || kind == Kind.TIMESTAMP || kind == Kind.TIME ? "CAST(" + quoted + " AS CHAR)" : quoted;
    }

    /**
     * 从 JDBC 结果集读取当前字段或数据行。
     */
    public static Object read(ResultSet rows, int index, Column column) throws SQLException {
        return switch (kind(column)) {
            case INTEGER -> {
                if (column.type().equals("bigint") && column.unsigned()) {
                    yield rows.getBigDecimal(index);
                }
                long value = rows.getLong(index);
                if (rows.wasNull()) {
                    yield null;
                }
                yield column.type().equals("bigint") || column.type()
                        .equals("int") && column.unsigned() ? (Object) value : (Object) Math.toIntExact(value);
            }
            case DECIMAL -> rows.getBigDecimal(index);
            case TEXT -> rows.getString(index);
            case BINARY -> rows.getBytes(index);
            case DATE -> {
                String text = rows.getString(index);
                yield text == null ? null : date(text);
            }
            case DATETIME -> {
                String text = rows.getString(index);
                yield text == null ? null : dateTime(text, column.temporalScale());
            }
            case TIMESTAMP -> {
                String text = rows.getString(index);
                yield text == null ? null : dateTime(text, column.temporalScale()).atOffset(ZoneOffset.UTC);
            }
            case TIME -> {
                String text = rows.getString(index);
                if (text == null) {
                    yield null;
                }
                if (fraction(text) != column.temporalScale()) {
                    throw new SQLException("OceanBase TIME text does not match its precision");
                }
                // 带类型且精确到微秒的载体；其他驱动会通过毫秒 java.sql.Time 重新解析文本。
                long micros;
                try {
                    micros = JdbcValueConversions.timeDurationMicros(text);
                } catch (RuntimeException invalid) {
                    throw new SQLException("OceanBase TIME is not representable");
                }
                yield micros >= 0 && micros < MICROS_PER_DAY ? (Object) LocalTime.ofNanoOfDay(
                        micros * 1000) : (Object) Duration.of(micros, java.time.temporal.ChronoUnit.MICROS);
            }
        };
    }

    // ---- 游标键 -----------------------------------------------------------------------------------

    /**
     * 根据键列精确值域选择 JDBC 类型。
     */
    public static int keyJdbcType(Column column) throws SQLException {
        OceanbaseCursorMetadata.domain(column);
        return switch (column.type()) {
            case "tinyint" -> Types.TINYINT;
            case "smallint" -> column.unsigned() ? Types.INTEGER : Types.SMALLINT;
            case "mediumint" -> Types.INTEGER;
            case "int" -> column.unsigned() ? Types.BIGINT : Types.INTEGER;
            case "bigint" -> column.unsigned() ? Types.NUMERIC : Types.BIGINT;
            case "decimal" -> Types.DECIMAL;
            case "date" -> Types.DATE;
            case "datetime" -> Types.TIMESTAMP;
            case "timestamp" -> Types.TIMESTAMP_WITH_TIMEZONE;
            default -> Types.VARCHAR;
        };
    }

    /**
     * 根据键列类型生成参数占位符及必要的类型转换。
     */
    public static String keyParameter(Column column) throws SQLException {
        return switch (kind(column)) {
            case DATE -> "CAST(? AS DATE)";
            case DATETIME, TIMESTAMP -> "CAST(? AS DATETIME(" + column.temporalScale() + "))";
            default -> "?";
        };
    }

    /**
     * 返回键。
     */
    public static TypedCursorValue key(Column column, Object value) throws SQLException {
        if (value == null) {
            throw new SQLException("OceanBase cursor key is NULL");
        }
        return TypedCursorValue.of(keyJdbcType(column), column.columnType(), value);
    }

    /** 持久化元组必须仍表示当前原生键列中的精确值。 */
    public static void validateKey(TypedCursorValue value, Column column) throws SQLException {
        if (value.jdbcType() != keyJdbcType(column) || !value.typeName().equals(column.columnType())) {
            throw new SQLException("OceanBase cursor key type changed");
        }
        try {
            switch (kind(column)) {
                case INTEGER, DECIMAL -> exactNumber(column, new BigDecimal(value.value()));
                case DATE -> dateText((LocalDate) value.jdbcValue());
                case DATETIME -> dateTimeText((LocalDateTime) value.jdbcValue(), column.temporalScale());
                case TIMESTAMP -> dateTimeText(
                        ((OffsetDateTime) value.jdbcValue()).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                        column.temporalScale());
                case TEXT -> {
                    if (!column.type().equals("char") && !column.type().equals("varchar")) {
                        throw new IllegalArgumentException();
                    }
                }
                default -> throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new SQLException("OceanBase cursor key value is not exact for its column");
        }
    }

    /**
     * 将游标键值绑定到查询或写入语句参数。
     */
    public static void bindKey(PreparedStatement statement, int index, TypedCursorValue value,
            Column column) throws SQLException {
        switch (kind(column)) {
            case INTEGER, DECIMAL -> statement.setBigDecimal(index, new BigDecimal(value.value()));
            case DATE -> statement.setString(index, dateText((LocalDate) value.jdbcValue()));
            case DATETIME ->
                    statement.setString(index, dateTimeText((LocalDateTime) value.jdbcValue(), column.temporalScale()));
            case TIMESTAMP -> statement.setString(index, dateTimeText(((OffsetDateTime) value.jdbcValue())
                    .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(), column.temporalScale()));
            case TEXT -> statement.setString(index, value.value());
            default -> throw new SQLException("OceanBase cursor key type is not verified");
        }
    }

    // ---- UPSERT 绑定 --------------------------------------------------------------------------------

    /**
     * 绑定类型。
     */
    public static int bindType(Column column) throws SQLException {
        return switch (writable(column)) {
            case INTEGER, DECIMAL -> Types.DECIMAL;
            case BINARY -> Types.VARBINARY;
            default -> Types.VARCHAR;
        };
    }

    /** 将单个输入转换为精确绑定值或拒绝；TIMESTAMP 文本要求 UTC 写入会话。 */
    public static Object write(Column column, Object value) {
        try {
            Kind kind = writable(column);
            if (value == null) {
                if (!column.nullable()) {
                    throw new IllegalArgumentException("NULL cannot be written to a NOT NULL OceanBase column");
                }
                return null;
            }
            return switch (kind) {
                case INTEGER, DECIMAL -> exactNumber(column, number(value));
                case TEXT -> text(column, value);
                case BINARY -> binary(column, value);
                case DATE -> {
                    if (value instanceof java.sql.Date date) {
                        yield dateText(date.toLocalDate());
                    }
                    if (value instanceof LocalDate date) {
                        yield dateText(date);
                    }
                    throw new IllegalArgumentException("Unsupported DATE value type");
                }
                case DATETIME -> {
                    if (value instanceof LocalDateTime time) {
                        yield dateTimeText(time, column.temporalScale());
                    }
                    if (value instanceof java.sql.Timestamp time) {
                        yield dateTimeText(time.toLocalDateTime(), column.temporalScale());
                    }
                    throw new IllegalArgumentException("Civil DATETIME requires a civil date-time value");
                }
                case TIMESTAMP -> {
                    if (value instanceof OffsetDateTime time) {
                        yield dateTimeText(time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
                                column.temporalScale());
                    }
                    throw new IllegalArgumentException("Instant TIMESTAMP requires an offset date-time value");
                }
                case TIME -> {
                    // java.sql.Time 仅携带毫秒：上游微秒值可能已经截断。
                    if (value instanceof java.sql.Time) {
                        throw new IllegalArgumentException("Millisecond java.sql.Time cannot prove an exact TIME");
                    }
                    yield new OceanbaseTemporalColumns.Column("time", column.temporalScale()).exactValue(
                            value instanceof Duration duration ? durationText(duration) : value);
                }
            };
        } catch (SQLException unsupported) {
            throw new IllegalArgumentException("OceanBase target column is not verified");
        }
    }

    /**
     * 按当前值域约定解析或转换数值。
     */
    private static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        throw new IllegalArgumentException("Unsupported exact numeric value type");
    }

    /**
     * 核对元数据数值与预期精确数值一致。
     */
    private static BigDecimal exactNumber(Column column, BigDecimal value) {
        if (column.type().equals("decimal")) {
            BigDecimal exact = value.setScale(column.scale(), RoundingMode.UNNECESSARY);
            BigDecimal limit = BigDecimal.TEN.pow(column.precision() - column.scale());
            if (exact.abs().compareTo(limit) >= 0 || column.unsigned() && exact.signum() < 0) {
                throw new ArithmeticException("Decimal out of range");
            }
            return exact;
        }
        BigInteger integer = value.setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
        int bits = switch (column.type()) {
            case "tinyint" -> 8;
            case "smallint" -> 16;
            case "mediumint" -> 24;
            case "int" -> 32;
            default -> 64;
        };
        BigInteger minimum = column.unsigned() ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(bits - 1).negate();
        BigInteger maximum = column.unsigned() ? BigInteger.ONE.shiftLeft(bits)
                .subtract(BigInteger.ONE) : BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE);
        if (integer.compareTo(minimum) < 0 || integer.compareTo(maximum) > 0) {
            throw new ArithmeticException("Integer out of range");
        }
        return new BigDecimal(integer);
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static String text(Column column, Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Unsupported text value type");
        }
        int bytes;
        try {
            bytes = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text)).remaining();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Text is not valid Unicode");
        }
        if (column.maxChars() != null && text.codePointCount(0, text.length()) > column.maxChars()
                || column.maxBytes() != null && bytes > column.maxBytes()
                // CHAR 使用 PAD 语义：存储后无法取回尾部空格。
                || column.type().equals("char") && text.endsWith(" ")) {
            throw new IllegalArgumentException("Text cannot be stored exactly");
        }
        return text;
    }

    /**
     * 按当前值域约定解析或转换二进制。
     */
    private static byte[] binary(Column column, Object value) {
        if (!(value instanceof byte[] bytes)) {
            throw new IllegalArgumentException("Unsupported binary value type");
        }
        if (column.maxBytes() != null && bytes.length > column.maxBytes()
                || column.type().equals("binary") && (column.maxBytes() == null || bytes.length != column.maxBytes())) {
            throw new IllegalArgumentException("Binary value cannot be stored exactly");
        }
        return bytes;
    }

    // ---- 服务器文本 -----------------------------------------------------------------------------------

    /**
     * 按当前值域约定解析或转换日期。
     */
    static LocalDate date(String text) throws SQLException {
        try {
            LocalDate value = LocalDate.parse(text);
            dateText(value);
            return value;
        } catch (RuntimeException invalid) {
            throw new SQLException("OceanBase DATE is not representable");
        }
    }

    /**
     * 按当前产品的精度和时区约定处理日期时间值。
     */
    static LocalDateTime dateTime(String text, int scale) throws SQLException {
        try {
            if (fraction(text) != scale) {
                throw new IllegalArgumentException();
            }
            LocalDateTime value = LocalDateTime.parse(text.replace(' ', 'T'));
            dateTimeText(value, scale);
            return value;
        } catch (RuntimeException invalid) {
            throw new SQLException("OceanBase DATETIME/TIMESTAMP is not representable");
        }
    }

    /**
     * 返回当前小数部分。
     */
    private static int fraction(String text) {
        int dot = text.lastIndexOf('.');
        return dot < 0 ? 0 : text.length() - dot - 1;
    }

    /** 有符号 [-]H:MM:SS.ffffff；随后由共享 TIME 校验验证范围及列精度。 */
    static String durationText(Duration value) {
        if (value.getNano() % 1000 != 0) {
            throw new IllegalArgumentException("TIME supports microsecond precision");
        }
        long micros = value.dividedBy(Duration.of(1, java.time.temporal.ChronoUnit.MICROS)), magnitude = Math.abs(
                micros);
        return String.format(Locale.ROOT, "%s%02d:%02d:%02d.%06d", micros < 0 ? "-" : "", magnitude / 3_600_000_000L,
                magnitude / 60_000_000L % 60, magnitude / 1_000_000L % 60, magnitude % 1_000_000L);
    }

    /**
     * 校验日期处于产品支持范围后生成日期文本。
     */
    static String dateText(LocalDate value) {
        if (value.getYear() < 1 || value.getYear() > 9999) {
            throw new IllegalArgumentException("Date is outside the OceanBase range");
        }
        return String.format(Locale.ROOT, "%04d-%02d-%02d", value.getYear(), value.getMonthValue(),
                value.getDayOfMonth());
    }

    /**
     * 校验小数秒精度后生成数据库可精确还原的时间文本。
     */
    static String dateTimeText(LocalDateTime value, int scale) {
        if (value.getNano() % POWERS[9 - scale] != 0) {
            throw new IllegalArgumentException("Fractional seconds exceed the OceanBase column precision");
        }
        return dateText(value.toLocalDate()) + String.format(Locale.ROOT, " %02d:%02d:%02d.%06d", value.getHour(),
                value.getMinute(), value.getSecond(), value.getNano() / 1000);
    }
}
