package cn.iocoder.yudao.framework.dbswitch.product.dm;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** DM 游标键值域，其绑定比较已在真实 DM8 服务端与 ORDER BY 核对。 */
public final class DmCursorMetadata {

    // DM 8.1.5.60 将 WHERE、MERGE ON 或派生源中的 LocalDateTime、Timestamp 及文本参数
    // 舍入为微秒；显式九位格式是唯一精确的 TIMESTAMP 比较参数。
    private static final String TIMESTAMP_FORMAT = "'YYYY-MM-DD HH24:MI:SS.FF9'";

    private static final DateTimeFormatter TIMESTAMP_TEXT = DateTimeFormatter.ofPattern(
            "uuuu-MM-dd HH:mm:ss.SSSSSSSSS");

    private static final Set<String> INTEGERS = Set.of("TINYINT", "SMALLINT", "INT", "INTEGER",
            "BIGINT"), DECIMALS = Set.of("DECIMAL", "NUMERIC", "NUMBER"),
            VARIABLE_TEXT = Set.of("VARCHAR", "VARCHAR2"), FIXED_TEXT = Set.of("CHAR"),
    // DM 将 DATETIME 与 TIMESTAMP 分别登记；两者属于同一本地日期时间值域，使用同一个绑定器。
    TIMESTAMPS = Set.of("TIMESTAMP", "DATETIME");

    /**
     * 初始化 {@code DmCursorMetadata}。
     */
    private DmCursorMetadata() {
    }

    /** 读取器锁：ROW SHARE 阻止快照期间的 TRUNCATE 及结构 DDL，同时允许并发 DML。 */
    public static void lock(Connection c, String schema, String table, int timeout) throws SQLException {
        if (c.getAutoCommit()) {
            throw new SQLException("DM relation lock requires an owned transaction");
        }
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.execute("LOCK TABLE " + DmSqlNames.relation(schema, table) + " IN ROW SHARE MODE");
        }
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    public static Map<String, DmNativeTables.ReadColumn> columns(DmNativeTables.ReadShape shape) {
        var columns = new LinkedHashMap<String, DmNativeTables.ReadColumn>();
        shape.columns().forEach(c -> columns.put(c.name(), c));
        return Collections.unmodifiableMap(columns);
    }

    /**
     * 返回游标读写使用的结构化键契约。
     */
    public static CursorKeyContract keyContract(Connection connection, DmNativeTables.ReadShape shape,
            List<String> keys, int timeout) throws SQLException {
        if (!shape.primaryKeys().equals(keys)) {
            throw new SQLException("DM replay requires the exact ordered non-null primary key");
        }
        var columns = columns(shape);
        var domains = new ArrayList<CursorKeyContract.Domain>();
        var material = new StringBuilder();
        boolean text = false;
        for (String name : keys) {
            var c = columns.get(name);
            if (c == null || c.nullable()) {
                throw new SQLException("DM cursor key metadata is incomplete");
            }
            var numeric = ordered(c);
            domains.add(numeric == null ? CursorKeyContract.Domain.nativeOnly() : numeric);
            material.append('\0').append(keySqlType(c));
            text |= textual(c);
        }
        if (domains.stream().allMatch(d -> d.numeric() != null)) {
            return new CursorKeyContract(ProductTypeEnum.DM, DigestUtil.sha256Hex("DM_NUMERIC_KEYS_V1\0" + domains),
                    domains);
        }
        // 文本及时间键相等语义仅在共享此比较配置的 DM 会话间得到证明。
        return new CursorKeyContract(ProductTypeEnum.DM, DigestUtil.sha256Hex(
                "DM_CURSOR_KEYS_V2\0" + (text ? requireTextOrdering(connection, timeout) : "") + material), domains);
    }

    /** 精确数值值域；已验证的文本或时间排序值域则返回 null。 */
    public static CursorKeyContract.Domain ordered(DmNativeTables.ReadColumn c) throws SQLException {
        if (c.nullable()) {
            throw new SQLException("DM cursor ordering cannot contain nulls");
        }
        if (INTEGERS.contains(c.kind()) || DECIMALS.contains(c.kind())) {
            return numeric(c);
        }
        if (textual(c)) {
            if (c.length() < 1 || c.length() > ("C".equals(c.semantics()) ? 4096 : 8192) || !Set.of("B", "C")
                    .contains(c.semantics())) {
                throw new SQLException("DM text cursor key exceeds its persisted domain");
            }
            return null;
        }
        if (TIMESTAMPS.contains(c.kind()) && scale(c) <= 9 || "TIME".equals(c.kind()) && scale(c) <= 6 || "DATE".equals(
                c.kind())) {
            return null;
        }
        // 无符号字节序与 DM 一致，但共享强类型游标值尚无二进制编解码器。
        if (Set.of("BINARY", "VARBINARY").contains(c.kind())) {
            throw new SQLException("DM binary cursor keys require a typed binary checkpoint codec");
        }
        throw new SQLException("DM cursor ordering requires a verified exact numeric, text or temporal column");
    }

    /**
     * 返回当前文本。
     */
    public static boolean textual(DmNativeTables.ReadColumn c) {
        return VARIABLE_TEXT.contains(c.kind()) || FIXED_TEXT.contains(c.kind());
    }

    /**
     * 按当前值域约定解析或转换数值。
     */
    public static CursorKeyContract.Domain numeric(DmNativeTables.ReadColumn c) throws SQLException {
        if (c.kind() == null) {
            throw new SQLException("DM cursor ordering requires a verified exact numeric column");
        }
        return switch (c.kind()) {
            case "TINYINT" -> CursorKeyContract.Domain.integer(8, false);
            case "SMALLINT" -> CursorKeyContract.Domain.integer(16, false);
            case "INT", "INTEGER" -> CursorKeyContract.Domain.integer(32, false);
            case "BIGINT" -> CursorKeyContract.Domain.integer(64, false);
            case "DECIMAL", "NUMERIC", "NUMBER" -> {
                if (c.precision() == null || c.scale() == null || c.precision() < 1 || c.precision() > 38 || c.scale() < 0 || c.scale() > c.precision()) {
                    throw new SQLException("DM exact numeric domain is unavailable");
                }
                yield CursorKeyContract.Domain.decimal(c.precision(), c.scale(), false);
            }
            default -> throw new SQLException("DM cursor ordering requires a verified exact numeric column");
        };
    }

    /**
     * 仅验证 UTF-8、区分大小写、BLANK_PAD_MODE=0、SPACE_COMPARE_MODE=0、NLS_SORT_TYPE=0 及原生模式：
     * 相等比较忽略尾部 U+0020，排序按剩余文本的码点顺序；ORDER BY
     * 与绑定比较采用相同规则。DM 8.1.5.60 接受列 COLLATE 子句，但不记录或应用它。
     */
    public static String requireTextOrdering(Connection c, int timeout) throws SQLException {
        try (var p = DmNativeIndexes.prepare(c,
                "SELECT SF_GET_UNICODE_FLAG(),SF_GET_CASE_SENSITIVE_FLAG(),(SELECT PARA_VALUE FROM V$DM_INI WHERE PARA_NAME='BLANK_PAD_MODE'),"
                        + "(SELECT PARA_VALUE FROM V$DM_INI WHERE PARA_NAME='SPACE_COMPARE_MODE'),SF_GET_SESSION_PARA_VALUE('NLS_SORT_TYPE'),(SELECT VALUE FROM V$PARAMETER WHERE NAME='COMPATIBLE_MODE') FROM DUAL",
                timeout); var r = p.executeQuery()) {
            if (!r.next() || !"1".equals(r.getString(1)) || !"1".equals(r.getString(2)) || !"0".equals(
                    r.getString(3)) || !"0".equals(r.getString(4)) || !"0".equals(r.getString(5)) || !"0".equals(
                    r.getString(6)) || r.next()) {
                throw new SQLException("DM text cursor ordering requires the verified binary comparison profile");
            }
        }
        return "DM_TEXT_ORDER_V2:UTF8:CASE_SENSITIVE:BLANK_PAD_0:SPACE_COMPARE_0:NLS_SORT_0:NATIVE";
    }

    /**
     * 确定当前列或值对应的 JDBC 类型。
     */
    public static int jdbcType(DmNativeTables.ReadColumn c) throws SQLException {
        if (c.kind() == null) {
            throw new SQLException("DM replay payload type has no verified exact binder");
        }
        return switch (c.kind()) {
            case "TINYINT" -> Types.TINYINT;
            case "SMALLINT" -> Types.SMALLINT;
            case "INT", "INTEGER" -> Types.INTEGER;
            case "BIGINT" -> Types.BIGINT;
            case "DECIMAL" -> Types.DECIMAL;
            case "NUMERIC", "NUMBER" -> Types.NUMERIC;
            case "CHAR" -> Types.CHAR;
            case "VARCHAR", "VARCHAR2" -> Types.VARCHAR;
            case "NCHAR" -> Types.NCHAR;
            case "NVARCHAR", "NVARCHAR2" -> Types.NVARCHAR;
            case "BINARY" -> Types.BINARY;
            case "VARBINARY" -> Types.VARBINARY;
            case "BLOB" -> Types.BLOB;
            case "CLOB" -> Types.CLOB;
            case "DATE" -> Types.DATE;
            case "TIME" -> Types.TIME;
            case "TIMESTAMP", "DATETIME" -> Types.TIMESTAMP;
            default -> throw new SQLException("DM replay payload type has no verified exact binder");
        };
    }

    /** 持久化游标承载类型：CHAR 共用文本承载类型，并保留其精确原生类型名。 */
    public static int cursorJdbcType(DmNativeTables.ReadColumn c) throws SQLException {
        ordered(c);
        return textual(c) ? Types.VARCHAR : jdbcType(c);
    }

    /**
     * 生成游标键分量所需的 SQL 类型声明。
     */
    public static String keySqlType(DmNativeTables.ReadColumn c) throws SQLException {
        ordered(c);
        if (DECIMALS.contains(c.kind())) {
            return c.kind() + "(" + c.precision() + "," + c.scale() + ")";
        }
        if (textual(c)) {
            return c.kind() + "(" + c.length() + ("C".equals(c.semantics()) ? " CHAR" : " BYTE") + ")";
        }
        return TIMESTAMPS.contains(c.kind()) || "TIME".equals(c.kind()) ? c.kind() + "(" + c.scale() + ")" : c.kind();
    }

    /** 比较占位符，其 DM 转换保留绑定游标值的所有数字位。 */
    public static String parameter(DmNativeTables.ReadColumn c) throws SQLException {
        ordered(c);
        return TIMESTAMPS.contains(c.kind()) ? "TO_TIMESTAMP(?," + TIMESTAMP_FORMAT + ")" : "?";
    }

    /** MERGE 派生源的键表达式；TIMESTAMP 形式可避免驱动按微秒舍入。 */
    public static String keySource(DmNativeTables.ReadColumn c) throws SQLException {
        return TIMESTAMPS.contains(c.kind()) ? parameter(c) : "CAST(? AS " + keySqlType(c) + ")";
    }

    /**
     * MERGE ON 条件。DM 键相等比较忽略尾部 U+0020，因此 VARCHAR 键还必须匹配已存字节长度；
     * 目标中已有的其他空格变体不会被认作同一条记录，而是触发主键冲突使批次失败。
     */
    public static String keyMatch(DmNativeTables.ReadColumn c, String target, String source) throws SQLException {
        ordered(c);
        return target + "=" + source + (VARIABLE_TEXT.contains(
                c.kind()) ? " AND LENGTHB(" + target + ")=LENGTHB(" + source + ")" : "");
    }

    /** 读取排序值时不使用 java.sql.Timestamp，其 JVM 时区转换会偏移夏令时空档中的本地时间。 */
    public static Object readKey(ResultSet rows, int index, DmNativeTables.ReadColumn c) throws SQLException {
        ordered(c);
        Object value = read(rows, index, c);
        if (value == null) {
            throw new SQLException("DM cursor ordering value is null");
        }
        // 无法再次绑定的位置，只有在其数据行应用后、下次恢复时才会失败。
        return key(value, c);
    }

    /** 任何语句使用之前，按列值域校验持久化或刚读取的游标值。 */
    public static Object cursorValue(TypedCursorValue value, DmNativeTables.ReadColumn c) throws SQLException {
        if (value.jdbcType() != cursorJdbcType(c) || !value.typeName().equals(keySqlType(c))) {
            throw new SQLException("DM cursor key type changed");
        }
        return key(value.jdbcValue(), c);
    }

    /**
     * 将当前值绑定到预编译语句参数。
     */
    public static void bind(PreparedStatement p, int index, TypedCursorValue value,
            DmNativeTables.ReadColumn c) throws SQLException {
        Object exact = keyParameter(cursorValue(value, c), c);
        if (exact instanceof BigDecimal n) {
            p.setBigDecimal(index, n);
        } else if (exact instanceof String text) {
            p.setString(index, text);
        } else {
            p.setObject(index, exact, jdbcType(c));
        }
    }

    /** 在目标列之外绑定的键值；TIMESTAMP 转为九位小数秒文本，供 TO_TIMESTAMP 使用。 */
    public static Object keyParameter(Object exact, DmNativeTables.ReadColumn c) throws SQLException {
        ordered(c);
        return exact instanceof LocalDateTime t ? TIMESTAMP_TEXT.format(t) : exact;
    }

    /**
     * 根据键列类型选择 JDBC 参数绑定类型。
     */
    public static int keyParameterType(DmNativeTables.ReadColumn c) throws SQLException {
        ordered(c);
        return TIMESTAMPS.contains(c.kind()) ? Types.VARCHAR : jdbcType(c);
    }

    /** 精确键输入；VARCHAR 键的空格变体由 {@link #keyMatch} 区分，不在此处拒绝。 */
    public static Object exactKey(Object value, DmNativeTables.ReadColumn c) throws SQLException {
        ordered(c);
        return key(value, c);
    }

    /**
     * 返回键。
     */
    private static Object key(Object value, DmNativeTables.ReadColumn c) throws SQLException {
        Object exact = exact(value, c);
        if (exact == null || exact instanceof String text && text.indexOf('\0') >= 0) {
            throw new SQLException("DM cursor key has no exact replay identity");
        }
        return exact;
    }

    /**
     * 按当前游标位置读取受限数量的数据行。
     */
    public static Object read(ResultSet rows, int index, DmNativeTables.ReadColumn c) throws SQLException {
        return switch (jdbcType(c)) {
            case Types.DECIMAL, Types.NUMERIC -> rows.getBigDecimal(index);
            case Types.TIMESTAMP -> rows.getObject(index, LocalDateTime.class);
            case Types.TIME -> rows.getObject(index, LocalTime.class);
            case Types.DATE -> rows.getObject(index, LocalDate.class);
            default -> rows.getObject(index);
        };
    }

    /** 任何 DML 之前先校验；setObject 绝不能静默缩窄带小数的整数或时间戳。 */
    public static Object exact(Object value, DmNativeTables.ReadColumn c) throws SQLException {
        if (value == null) {
            if (!c.nullable()) {
                throw new SQLException("DM required value is null");
            }
            return null;
        }
        int type = jdbcType(c);
        if (Set.of(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL)
                .contains(type)) {
            BigDecimal n;
            if (value instanceof BigDecimal v) {
                n = v;
            } else if (value instanceof BigInteger v) {
                n = new BigDecimal(v);
            } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                n = BigDecimal.valueOf(((Number) value).longValue());
            } else {
                throw new SQLException("DM exact numeric input required");
            }
            var domain = numeric(c).numeric();
            try {
                n = n.setScale(domain.scale(), RoundingMode.UNNECESSARY);
            } catch (ArithmeticException e) {
                throw new SQLException("DM numeric input loses scale");
            }
            if (n.compareTo(domain.minimum()) < 0 || n.compareTo(domain.maximum()) > 0) {
                throw new SQLException("DM numeric input exceeds target domain");
            }
            return n;
        }
        if (type == Types.TIMESTAMP) {
            // LocalDateTime 直接绑定目标列，不经过 JVM 时区往返转换。
            LocalDateTime t = value instanceof Timestamp v ? v.toLocalDateTime() : value instanceof LocalDateTime v ? v : null;
            if (t == null || c.scale() == null || t.getYear() < 1 || t.getYear() > 9999 || t.getNano() % pow10(
                    9 - c.scale()) != 0) {
                throw new SQLException("DM timestamp input loses precision");
            }
            return t;
        }
        if (type == Types.TIME) {
            LocalTime t = null;
            if (value instanceof LocalTime v) {
                t = v;
            } else if (value instanceof Time v) {
                // java.sql.Time 最多携带毫秒，而 Time.toLocalTime() 连毫秒也会丢弃。
                if (c.scale() == null || c.scale() > 3) {
                    throw new SQLException("DM time input carrier cannot prove sub-millisecond precision");
                }
                t = v.toLocalTime().withNano((int) Math.floorMod(v.getTime(), 1000L) * 1_000_000);
            }
            if (t == null || c.scale() == null || c.scale() > 6 || t.getNano() % pow10(9 - c.scale()) != 0) {
                throw new SQLException("DM time input loses precision");
            }
            return t;
        }
        if (type == Types.DATE) {
            LocalDate d = value instanceof java.sql.Date v ? v.toLocalDate() : value instanceof LocalDate v ? v : null;
            if (d == null || d.getYear() < 1 || d.getYear() > 9999) {
                throw new SQLException("DM date input is not exact");
            }
            return d;
        }
        if (Set.of(Types.VARCHAR, Types.NVARCHAR, Types.CHAR, Types.NCHAR, Types.CLOB).contains(type)) {
            if (!(value instanceof String text)) {
                throw new SQLException("DM text input is not materialized");
            }
            int bytes;
            try {
                bytes = java.nio.charset.StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text))
                        .remaining();
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new SQLException("DM text contains invalid Unicode");
            }
            if (type != Types.CLOB && (c.semantics() != null && c.semantics().equals("C") ? text.codePointCount(0,
                    text.length()) : bytes) > c.length()) {
                throw new SQLException("DM text input exceeds target length");
            }
            return text;
        }
        if (!(value instanceof byte[] bytes) || type != Types.BLOB && bytes.length > c.length()) {
            throw new SQLException("DM binary input exceeds target domain");
        }
        return bytes;
    }

    /**
     * 返回小数位数。
     */
    private static int scale(DmNativeTables.ReadColumn c) throws SQLException {
        if (c.scale() == null || c.scale() < 0) {
            throw new SQLException("DM temporal precision is unavailable");
        }
        return c.scale();
    }

    /**
     * 计算给定整数次幂的十进制基数。
     */
    private static int pow10(int power) throws SQLException {
        if (power < 0 || power > 9) {
            throw new SQLException("DM temporal precision is invalid");
        }
        int value = 1;
        for (int i = 0; i < power; i++) {
            value *= 10;
        }
        return value;
    }
}
