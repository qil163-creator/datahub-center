package cn.iocoder.yudao.framework.dbswitch.common.util;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Calendar;

/**
 * 通用且不依赖具体驱动的 JDBC 值转换。只有 SQL NULL 才转换为 Java null。
 * 转换会读取并释放 JDBC LOB、XML 和数组定位器，调用方不得再次使用。
 * 失败信息只包含类型信息，绝不包含源值或驱动异常链。
 */
public final class JdbcValueConversions {

    private static final java.util.regex.Pattern TIME_DURATION = java.util.regex.Pattern.compile(
            "-?[0-9]{2,3}:[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,6})?");

    /**
     * 初始化 {@code JdbcValueConversions}。
     */
    private JdbcValueConversions() {
    }

    /**
     * 将值整理为当前协议使用的规范形式。
     */
    public static Object normalize(Object value) {
        return normalize(value, BatchLimits.defaults().maxCellBytes());
    }

    /**
     * 将值整理为当前协议使用的规范形式。
     */
    public static Object normalize(Object value, long maxCellBytes) {
        if (maxCellBytes < 1) {
            throw new IllegalArgumentException("Invalid JDBC value limit");
        }
        if (value == null) {
            return null;
        }
        if (value instanceof Clob clob) {
            return clobToString(clob, maxCellBytes);
        }
        if (value instanceof Blob blob) {
            return blobToBytes(blob, maxCellBytes);
        }
        if (value instanceof SQLXML xml) {
            return xmlToString(xml, maxCellBytes);
        }
        if (value instanceof java.sql.Array array) {
            value = detachArray(array, maxCellBytes);
        }
        if (value instanceof Struct || value instanceof java.sql.Ref) {
            throw unsupported(value, "JDBC scalar");
        }
        if (JdbcTypesUtils.getObjectSize(Types.OTHER, value) > maxCellBytes) {
            throw unsupported(value, "bounded JDBC value");
        }
        // 保留 BigInteger 和 BigDecimal，不进行缩窄转换。
        return value;
    }

    /**
     * 读取 JDBC 字符大对象并转换为字符串。
     */
    public static String clobToString(Clob clob) {
        return clobToString(clob, BatchLimits.defaults().maxCellBytes());
    }

    /**
     * 读取 JDBC 字符大对象并转换为字符串。
     */
    private static String clobToString(Clob clob, long maxBytes) {
        if (clob == null) {
            return null;
        }
        try {
            try (Reader reader = clob.getCharacterStream()) {
                if (reader == null) {
                    throw unsupported(clob, "text");
                }
                return boundedText(reader, maxBytes);
            } finally {
                clob.free();
            }
        } catch (Exception failure) {
            throw unsupported(clob, "text");
        }
    }

    /**
     * 读取 JDBC 二进制大对象并转换为字节数组。
     */
    public static byte[] blobToBytes(Blob blob) {
        return blobToBytes(blob, BatchLimits.defaults().maxCellBytes());
    }

    /**
     * 读取 JDBC 二进制大对象并转换为字节数组。
     */
    private static byte[] blobToBytes(Blob blob, long maxBytes) {
        if (blob == null) {
            return null;
        }
        try {
            try (InputStream stream = blob.getBinaryStream()) {
                long length = blob.length();
                if (stream == null || length < 0 || length > Math.min(maxBytes, Integer.MAX_VALUE - 8L)) {
                    throw unsupported(blob, "bounded bytes");
                }
                // 不要根据不可信长度分配内存；须显式校验流是否过短或过长。
                ByteArrayOutputStream result = new ByteArrayOutputStream((int) Math.min(length, 8192));
                byte[] buffer = new byte[8192];
                long remaining = length;
                while (remaining > 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw unsupported(blob, "uninterrupted bytes");
                    }
                    int count = stream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) {
                        throw unsupported(blob, "bytes");
                    }
                    if (count == 0) {
                        int next = stream.read();
                        if (next < 0) {
                            throw unsupported(blob, "bytes");
                        }
                        result.write(next);
                        remaining--;
                    } else {
                        result.write(buffer, 0, count);
                        remaining -= count;
                    }
                }
                if (stream.read() != -1) {
                    throw unsupported(blob, "bytes");
                }
                return result.toByteArray();
            } finally {
                blob.free();
            }
        } catch (Exception failure) {
            throw unsupported(blob, "bytes");
        }
    }

    /**
     * 读取 JDBC SQLXML 并转换为字符串。
     */
    private static String xmlToString(SQLXML xml, long maxBytes) {
        try {
            try (Reader reader = xml.getCharacterStream()) {
                if (reader == null) {
                    throw unsupported(xml, "text");
                }
                return boundedText(reader, maxBytes);
            } finally {
                xml.free();
            }
        } catch (Exception failure) {
            throw unsupported(xml, "text");
        }
    }

    /**
     * 读取并校验长度受限的文本值。
     */
    private static String boundedText(Reader reader, long maxBytes) throws java.io.IOException {
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalArgumentException("JDBC text read interrupted");
            }
            if (count == 0) {
                int next = reader.read();
                if (next == -1) {
                    break;
                }
                if (2L * (result.length() + 1L) > maxBytes) {
                    throw new IllegalArgumentException("JDBC text exceeds value limit");
                }
                result.append((char) next);
            } else {
                if (2L * (result.length() + (long) count) > maxBytes) {
                    throw new IllegalArgumentException("JDBC text exceeds value limit");
                }
                result.append(buffer, 0, count);
            }
        }
        return result.toString();
    }

    /**
     * JDBC getArray/getResultSet 会规范化索引。先校验 PG 传输格式中的维度，避免。
     */
    private static JdbcArrayValue detachArray(java.sql.Array array, long maxBytes) {
        try {
            try {
                // JDBC getArray/getResultSet 会规范化索引。先校验 PG 传输格式中的维度，避免
                // 目标端的 createArrayOf 静默改变非默认下界。
                verifyPostgresArrayShape(array, maxBytes);
                String baseName = array.getBaseTypeName();
                int baseType = array.getBaseType();
                new JdbcArrayValue(baseName, baseType, java.util.List.of()); // 读取元素前先校验。
                java.util.List<Object> elements = new java.util.ArrayList<>();
                long bytes = 32L + 2L * baseName.length();
                try (var result = array.getResultSet()) {
                    if (result == null) {
                        throw unsupported(array, "detached array");
                    }
                    while (result.next()) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw unsupported(array, "uninterrupted array");
                        }
                        if (result.getLong(1) != elements.size() + 1L) {
                            throw unsupported(array, "one-based array");
                        }
                        Object element = result.getObject(2);
                        // 任何基于 toString 的估算之前，都须确认值属于受支持的不可变标量类型。
                        var singleton = new JdbcArrayValue(baseName, baseType,
                                java.util.Collections.singletonList(element));
                        bytes = Math.addExact(bytes, singleton.estimatedBytes() - 32L - 2L * baseName.length());
                        if (bytes > maxBytes) {
                            throw unsupported(array, "bounded array");
                        }
                        elements.add(element);
                    }
                }
                var detached = new JdbcArrayValue(baseName, baseType, elements);
                if (detached.estimatedBytes() > maxBytes) {
                    throw unsupported(array, "bounded array");
                }
                return detached;
            } finally {
                array.free();
            }
        } catch (Exception failure) {
            throw unsupported(array, "bounded one-dimensional array");
        }
    }

    /**
     * 检查 PostgreSQL 数组的维度及元素结构满足受支持协议。
     */
    private static void verifyPostgresArrayShape(java.sql.Array array, long maxBytes) throws Exception {
        Class<?> pgArray = array.getClass();
        while (pgArray != null && !pgArray.getName().equals("org.postgresql.jdbc.PgArray")) {
            pgArray = pgArray.getSuperclass();
        }
        if (pgArray == null) {
            throw unsupported(array, "verified array driver");
        }
        if ((boolean) pgArray.getMethod("isBinary").invoke(array)) {
            byte[] wire = (byte[]) pgArray.getMethod("toBytes").invoke(array);
            if (wire == null || wire.length < 12 || wire.length > maxBytes) {
                throw unsupported(array, "bounded array");
            }
            var buffer = java.nio.ByteBuffer.wrap(wire);
            int dimensions = buffer.getInt();
            if (dimensions == 0) {
                if (wire.length != 12) {
                    throw unsupported(array, "empty array");
                }
            } else if (dimensions != 1 || wire.length < 20 || buffer.getInt(12) < 0 || buffer.getInt(16) != 1) {
                throw unsupported(array, "one-dimensional one-based array");
            }
        } else {
            // PG 的文本形式读取方法返回服务器已有的表示。该形式仅用于
            // 限制解析范围并拒绝不支持的维度，绝不作为绑定到语句的值。
            String wire = array.toString();
            if (wire == null || 2L * wire.length() > maxBytes || !wire.startsWith("{")
                    || !wire.endsWith("}") || wire.startsWith("{{")) {
                throw unsupported(array, "bounded one-dimensional one-based array");
            }
        }
    }

    /**
     * 转换为文本。
     */
    public static String toText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Array array) {
            normalize(array); // 即使标量目标拒绝数组，也要释放源定位器。
            throw unsupported(array, "scalar text");
        }
        if (value instanceof Clob || value instanceof SQLXML) {
            return (String) normalize(value);
        }
        if (value instanceof byte[] bytes) {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException invalid) {
                throw unsupported(value, "UTF-8 text");
            }
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof Number
                || value instanceof Boolean || value instanceof java.util.Date || value instanceof java.sql.RowId
                || value instanceof java.time.temporal.TemporalAccessor || value instanceof java.util.UUID) {
            return meaningfulString(value);
        }
        // 识别 JDBC 厂商专有值时，不将厂商驱动引入 common 模块。
        String name = value.getClass().getName();
        if (name.startsWith("oracle.sql.INTERVAL") || name.equals("oracle.sql.TIMESTAMPLTZ")
                || name.equals("oracle.sql.TIMESTAMPTZ") || name.equals("microsoft.sql.DateTimeOffset")
                || isClass(value, "org.postgresql.util.PGobject")) {
            return meaningfulString(value);
        }
        throw unsupported(value, "text");
    }

    /**
     * 将输入值转换为字节数组。
     */
    public static byte[] toBytes(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof Blob blob) {
            return blobToBytes(blob);
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof Clob
                || value instanceof SQLXML || value instanceof java.util.Date) {
            return toText(value).getBytes(StandardCharsets.UTF_8);
        }
        // 任意对象的 Java 序列化结果不是可跨数据库使用的二进制值。
        throw unsupported(value, "bytes");
    }

    /**
     * 将输入值转换为精确小数。
     */
    public static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            if (value instanceof BigInteger integer) {
                return new BigDecimal(integer);
            }
            if (value instanceof Number number) {
                return new BigDecimal(number.toString());
            }
            if (value instanceof Boolean bool) {
                return bool ? BigDecimal.ONE : BigDecimal.ZERO;
            }
            if (value instanceof java.util.Date date) {
                return BigDecimal.valueOf(date.getTime());
            }
            if (value instanceof Calendar calendar) {
                return BigDecimal.valueOf(calendar.getTimeInMillis());
            }
            if (value instanceof LocalDateTime date) {
                return BigDecimal.valueOf(java.sql.Timestamp.valueOf(date).getTime());
            }
            if (value instanceof java.time.OffsetDateTime date) {
                return BigDecimal.valueOf(date.toInstant().toEpochMilli());
            }
            if (value instanceof CharSequence || value instanceof Character || value instanceof Clob) {
                String text = toText(value).trim();
                if ("true".equalsIgnoreCase(text)) {
                    return BigDecimal.ONE;
                }
                if ("false".equalsIgnoreCase(text)) {
                    return BigDecimal.ZERO;
                }
                return new BigDecimal(text);
            }
        } catch (RuntimeException failure) {
            throw unsupported(value, "decimal");
        }
        throw unsupported(value, "decimal");
    }

    /**
     * 将输入数值转换为字节整数。
     */
    public static Byte toByte(Object value) {
        try {
            return value == null ? null : toDecimal(value).byteValueExact();
        } catch (RuntimeException failure) {
            throw unsupported(value, "byte");
        }
    }

    /**
     * 将输入数值转换为短整数。
     */
    public static Short toShort(Object value) {
        try {
            return value == null ? null : toDecimal(value).shortValueExact();
        } catch (RuntimeException failure) {
            throw unsupported(value, "short");
        }
    }

    /**
     * 将输入数值转换为整数。
     */
    public static Integer toInteger(Object value) {
        try {
            return value == null ? null : toDecimal(value).intValueExact();
        } catch (RuntimeException failure) {
            throw unsupported(value, "integer");
        }
    }

    /**
     * 将输入数值转换为长整数。
     */
    public static Long toLong(Object value) {
        try {
            return value == null ? null : toDecimal(value).longValueExact();
        } catch (RuntimeException failure) {
            throw unsupported(value, "long");
        }
    }

    /**
     * 将输入数值转换为 Float 单精度浮点数。
     */
    public static Float toFloat(Object value) {
        if (value == null) {
            return null;
        }
        // 十进制转换会丢失 IEEE 带符号的零，还可能改变二进制舍入和扩宽结果。
        // 保留实际的 JDBC 浮点值；其他输入类型仍遵循十进制契约。
        float result = value instanceof Float number ? number
                : value instanceof Double number ? number.floatValue() : toDecimal(value).floatValue();
        if (!Float.isFinite(result)) {
            throw unsupported(value, "float");
        }
        return result;
    }

    /**
     * 将输入数值转换为 Double 双精度浮点数。
     */
    public static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        double result = value instanceof Double number ? number
                : value instanceof Float number ? number.doubleValue() : toDecimal(value).doubleValue();
        if (!Double.isFinite(result)) {
            throw unsupported(value, "double");
        }
        return result;
    }

    /**
     * 将输入值转换为布尔值。
     */
    public static Boolean toBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number) {
            return toDecimal(value).compareTo(BigDecimal.ZERO) != 0;
        }
        if (value instanceof CharSequence || value instanceof Character || value instanceof Clob) {
            String text = toText(value).trim();
            if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
                return false;
            }
        }
        throw unsupported(value, "boolean");
    }

    /**
     * 转换为日期。
     */
    public static LocalDate toDate(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof LocalDate date) {
                return date;
            }
            if (value instanceof java.sql.Date date) {
                return date.toLocalDate();
            }
            if (value instanceof CharSequence || value instanceof Character || value instanceof Clob) {
                return LocalDate.parse(toText(value));
            }
            return toDateTime(value).toLocalDate();
        } catch (RuntimeException failure) {
            throw unsupported(value, "date");
        }
    }

    /**
     * 转换为时间。
     */
    public static LocalTime toTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof LocalTime time) {
                return time;
            }
            if (value instanceof java.sql.Time time) {
                return time.toLocalTime().withNano((int) Math.floorMod(time.getTime(), 1000) * 1_000_000);
            }
            if (value instanceof CharSequence || value instanceof Character || value instanceof Clob) {
                return LocalTime.parse(toText(value));
            }
            return toDateTime(value).toLocalTime();
        } catch (RuntimeException failure) {
            throw unsupported(value, "time");
        }
    }

    /** 精确的原生 TIME 时长，与民用时钟时间区分，不使用时区，也不按 24 小时回绕。 */
    public static long timeDurationMicros(String value) {
        if (value == null || value.length() > 17 || !TIME_DURATION.matcher(value).matches()) {
            throw new IllegalArgumentException("Unsupported native TIME duration representation");
        }
        boolean negative = value.charAt(0) == '-';
        String[] components = (negative ? value.substring(1) : value).split(":");
        long seconds = Long.parseLong(components[0]) * 3600L + Long.parseLong(components[1]) * 60L;
        int dot = components[2].indexOf('.');
        seconds += Long.parseLong(dot < 0 ? components[2] : components[2].substring(0, dot));
        long fraction = dot < 0 ? 0 : Long.parseLong((components[2].substring(dot + 1) + "000000").substring(0, 6));
        long micros = seconds * 1_000_000L + fraction;
        return negative ? -micros : micros;
    }

    /**
     * 转换为日期时间。
     */
    public static LocalDateTime toDateTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof LocalDateTime date) {
                return date;
            }
            if (value instanceof java.sql.Timestamp timestamp) {
                return timestamp.toLocalDateTime();
            }
            if (value instanceof java.sql.Date date) {
                return date.toLocalDate().atStartOfDay();
            }
            if (value instanceof LocalDate date) {
                return date.atStartOfDay();
            }
            if (value instanceof java.time.OffsetDateTime date) {
                return date.toLocalDateTime();
            }
            if (value instanceof java.util.Date date) {
                return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(date.getTime()), ZoneId.systemDefault());
            }
            if (value instanceof Calendar calendar) {
                return LocalDateTime.ofInstant(calendar.toInstant(), calendar.getTimeZone().toZoneId());
            }
            if (value instanceof Number) {
                return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(toLong(value)), ZoneId.systemDefault());
            }
            if (value instanceof CharSequence || value instanceof Character || value instanceof Clob) {
                return LocalDateTime.parse(toText(value).replace(' ', 'T'));
            }
            String method = value.getClass().getName().equals("oracle.sql.TIMESTAMP") ? "timestampValue"
                    : value.getClass().getName().equals("microsoft.sql.DateTimeOffset") ? "getTimestamp" : null;
            if (method != null) {
                Object timestamp = value.getClass().getMethod(method).invoke(value);
                if (timestamp instanceof java.sql.Timestamp ts) {
                    return ts.toLocalDateTime();
                }
            }
        } catch (Exception failure) {
            throw unsupported(value, "timestamp");
        }
        throw unsupported(value, "timestamp");
    }

    /** 打开传输协议前，先完成 COPY 值的转换。 */
    public static Object forJdbcType(Object value, int type) {
        return switch (type) {
            case Types.CHAR, Types.NCHAR, Types.VARCHAR, Types.NVARCHAR, Types.LONGVARCHAR,
                 Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB, Types.NULL, Types.OTHER -> copyText(value);
            case Types.TINYINT -> toByte(value);
            case Types.SMALLINT -> toShort(value);
            case Types.INTEGER -> toInteger(value);
            case Types.BIGINT -> toLong(value);
            case Types.NUMERIC, Types.DECIMAL -> toDecimal(value);
            case Types.FLOAT, Types.REAL -> toFloat(value);
            case Types.DOUBLE -> toDouble(value);
            case Types.BOOLEAN, Types.BIT -> toBoolean(value);
            case Types.DATE -> toDate(value);
            case Types.TIME -> toTime(value);
            case Types.TIMESTAMP -> toDateTime(value);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> toBytes(value);
            case Types.ARRAY -> throw new IllegalArgumentException("COPY ARRAY is unsupported; use JDBC INSERT");
            default -> throw unsupported(value, "JDBC type " + type);
        };
    }

    /**
     * 复制文本。
     */
    private static String copyText(Object value) {
        String text = toText(value);
        // PostgreSQL 文本无法表示 NUL；静默删除它会改变源值。
        if (text != null && text.indexOf('\0') >= 0) {
            throw unsupported(value, "COPY text without NUL");
        }
        // String.getBytes 会将未配对的 UTF-16 代理项替换为 '?'。须在整批转换时
        // 拒绝该值，并且应早于借用连接或打开 COPY 协议。
        if (text != null) {
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (Character.isHighSurrogate(character)) {
                    if (++index == text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                        throw unsupported(value, "COPY well-formed Unicode text");
                    }
                } else if (Character.isLowSurrogate(character)) {
                    throw unsupported(value, "COPY well-formed Unicode text");
                }
            }
        }
        return text;
    }

    /**
     * 检查文本是否包含非空白的有效内容。
     */
    public static String meaningfulString(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String result = value.toString();
            String defaultValue = value.getClass().getName() + "@" + Integer.toHexString(value.hashCode());
            if (result == null || result.equals(defaultValue)) {
                throw unsupported(value, "text");
            }
            return result;
        } catch (RuntimeException failure) {
            throw unsupported(value, "text");
        }
    }

    /**
     * 判断类。
     */
    private static boolean isClass(Object value, String name) {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException unsupported(Object value, String target) {
        return new IllegalArgumentException("Unsupported or invalid JDBC conversion to " + target
                + " (source type: " + (value == null ? "null" : value.getClass().getName()) + ")");
    }
}
