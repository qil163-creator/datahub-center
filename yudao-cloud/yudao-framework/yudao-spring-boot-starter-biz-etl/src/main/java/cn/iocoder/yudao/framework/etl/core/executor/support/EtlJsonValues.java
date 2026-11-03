package cn.iocoder.yudao.framework.etl.core.executor.support;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ETL JSON 契约：精确小数和超出 JavaScript 安全范围的整数使用十进制字符串。 */
public final class EtlJsonValues {

    private static final BigInteger SAFE = BigInteger.valueOf(9_007_199_254_740_991L);

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();

    /**
     * 初始化 {@code EtlJsonValues}。
     */
    private EtlJsonValues() {
    }

    /**
     * 返回当前解析。
     */
    public static Object parse(String json) {
        try {
            return wireValue(MAPPER.readValue(json, Object.class));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid ETL JSON data");
        }
    }

    /**
     * 将数据序列化为 JSON，精确小数和超出安全范围的整数使用十进制字符串。
     */
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(wireValue(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Unsupported ETL JSON value");
        }
    }

    /** 复制结果行用于 JSON 传输，不改变执行流水线中的数值。 */
    public static Map<String, Object> wireRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(key, wireValue(value)));
        return result;
    }

    /**
     * 将 Java 值转换为保持精度的 JSON 传输表示。
     */
    private static Object wireValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            long textSize = Math.max(1L, (long) decimal.precision() - decimal.scale())
                    + Math.max(0L, decimal.scale()) + 2;
            if (textSize > 1024 * 1024) {
                throw new IllegalArgumentException("ETL decimal text exceeds scalar budget");
            }
            return decimal.toPlainString();
        }
        if (value instanceof BigInteger integer) {
            return integer.abs().compareTo(SAFE) > 0 ? integer.toString() : integer;
        }
        if (value instanceof Long integer) {
            return BigInteger.valueOf(integer).abs().compareTo(SAFE) > 0 ? integer.toString() : integer;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), wireValue(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(EtlJsonValues::wireValue).toList();
        }
        if (value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor) {
            return cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions.toText(value);
        }
        return value;
    }
}
