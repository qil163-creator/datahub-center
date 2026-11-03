package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import java.math.*;
import java.nio.charset.*;
import java.nio.CharBuffer;
import java.time.*;
import java.util.*;

/** 无损原生数据点编码。UINT64 需要 SDK Point API 未提供的无符号后缀。 */
public final class InfluxLineProtocol {

    private static final BigInteger UINT_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    /**
     * 初始化 {@code InfluxLineProtocol}。
     */
    private InfluxLineProtocol() {
    }

    /**
     * 返回字段集合。
     */
    public static void fields(InfluxNativeCatalog.Table table, List<String> fields) {
        if (fields == null || fields.isEmpty() || new HashSet<>(fields).size() != fields.size() || !fields.containsAll(
                table.keys())) {
            throw new IllegalArgumentException("Influx writes require explicit time and every native tag column");
        }
        fields.forEach(table::column);
    }

    /**
     * 返回当前批次。
     */
    public static byte[] batch(InfluxNativeCatalog.Table table, List<String> fields, List<Object[]> rows, long limit) {
        fields(table, fields);
        var output = new java.io.ByteArrayOutputStream();
        for (Object[] row : rows) {
            if (row == null || row.length != fields.size()) {
                throw new IllegalArgumentException("Influx row width disagrees with its native schema");
            }
            StringBuilder tags = new StringBuilder(), values = new StringBuilder();
            Long time = null;
            for (int i = 0; i < fields.size(); i++) {
                var column = table.column(fields.get(i));
                Object value = row[i];
                if (column.kind() == InfluxNativeCatalog.Kind.TIME) {
                    time = nanos(value);
                    continue;
                }
                if (value == null) {
                    continue;
                }
                if (column.kind() == InfluxNativeCatalog.Kind.TAG) {
                    if (!(value instanceof String text) || text.isEmpty()) {
                        throw invalid();
                    }
                    tags.append(',').append(name(column.name(), true)).append('=').append(name(text, true));
                    continue;
                }
                if (!values.isEmpty()) {
                    values.append(',');
                }
                values.append(name(column.name(), true)).append('=');
                switch (column.kind()) {
                    case INT64 -> values.append(signed(value)).append('i');
                    case UINT64 -> values.append(unsigned(value)).append('u');
                    case FLOAT64 -> {
                        if (!(value instanceof Double || value instanceof Float) || !Double.isFinite(
                                ((Number) value).doubleValue())) {
                            throw invalid();
                        }
                        values.append(Double.toString(((Number) value).doubleValue()));
                    }
                    case BOOLEAN -> {
                        if (!(value instanceof Boolean)) {
                            throw invalid();
                        }
                        values.append(value);
                    }
                    case STRING -> {
                        if (!(value instanceof String text)) {
                            throw invalid();
                        }
                        validText(text);
                        values.append('"').append(text.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                    }
                    default -> throw invalid();
                }
            }
            if (time == null || values.isEmpty()) {
                throw new IllegalArgumentException(
                        "Influx points require explicit time and at least one non-null field");
            }
            byte[] line = utf8(name(table.name(), false) + tags + " " + values + " " + time + "\n");
            if (line.length > limit - output.size()) {
                throw new IllegalArgumentException("Influx encoded batch exceeds its byte budget");
            }
            output.writeBytes(line);
        }
        return output.toByteArray();
    }

    /**
     * 返回当前有符号。
     */
    private static long signed(Object value) {
        if (value instanceof BigInteger integer) {
            return integer.longValueExact();
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw invalid();
    }

    /**
     * 按当前值域约定解析或转换无符号数值。
     */
    private static BigInteger unsigned(Object value) {
        BigInteger integer;
        if (value instanceof BigInteger n) {
            integer = n;
        } else if (value instanceof BigDecimal n) {
            try {
                integer = n.toBigIntegerExact();
            } catch (ArithmeticException fractional) {
                throw invalid();
            }
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            integer = BigInteger.valueOf(((Number) value).longValue());
        } else {
            throw invalid();
        }
        if (integer.signum() < 0 || integer.compareTo(UINT_MAX) > 0) {
            throw invalid();
        }
        return integer;
    }

    /**
     * 返回当前纳秒数。
     */
    public static long nanos(Object value) {
        Instant instant;
        if (value instanceof Instant i) {
            instant = i;
        } else if (value instanceof OffsetDateTime i) {
            instant = i.toInstant();
        } else if (value instanceof ZonedDateTime i) {
            instant = i.toInstant();
        } else if (value instanceof java.util.Date i) {
            instant = i.toInstant();
        } else if (value instanceof BigInteger i) {
            instant = Instant.ofEpochMilli(i.longValueExact());
        } else if (value instanceof BigDecimal i) {
            instant = Instant.ofEpochMilli(i.longValueExact());
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            instant = Instant.ofEpochMilli(((Number) value).longValue());
        } else {
            throw new IllegalArgumentException(
                    "Influx requires an absolute timestamp or exact integral epoch milliseconds");
        }
        return BigInteger.valueOf(instant.getEpochSecond()).multiply(BigInteger.valueOf(1_000_000_000L))
                .add(BigInteger.valueOf(instant.getNano())).longValueExact();
    }

    /**
     * 返回名称。
     */
    private static String name(String text, boolean equals) {
        validText(text);
        String result = text.replace("\\", "\\\\").replace(" ", "\\ ").replace(",", "\\,");
        return equals ? result.replace("=", "\\=") : result;
    }

    /**
     * 校验文本是否满足当前协议的字符与长度限制。
     */
    private static void validText(String text) {
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0) {
            throw invalid();
        }
        utf8(text);
    }

    /**
     * 按 UTF-8 编码或解码当前文本。
     */
    private static byte[] utf8(String text) {
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException invalid) {
            throw invalid();
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Influx value cannot be represented in its declared native column type");
    }
}
