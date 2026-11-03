package cn.iocoder.yudao.framework.etl.core.schema;

import cn.hutool.json.JSONNull;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可执行子流程字段契约。十进制精度限制系数的十进制位数；
 * 若声明了小数位数，必须先确认该值能以此小数位数精确表示。
 * 校验既不舍入，也不转换行值。旧定义省略 nullable 属性时允许 null，
 * 但设计元数据仍将其视为未知。必须同时具备源证据和运行时时区语义，
 * 才接受时区声明。
 */
public record EtlFieldContract(String name, String type, Boolean nullable, Integer precision,
                               Integer scale, String timezone) {

    private static final Set<String> TYPES = Set.of("String", "Integer", "Long", "Double",
            "Decimal", "Boolean", "Date", "Object");

    /**
     * 初始化 {@code EtlFieldContract}。
     */
    public EtlFieldContract {
        if (name == null || name.isBlank() || name.length() > 128) {
            fail("边界字段名称无效");
        }
        if (type == null || !TYPES.contains(type)) {
            fail("边界字段类型无效");
        }
        if (precision != null && (precision < 1 || precision > 1000)) {
            fail("边界精度须为 1–1000 的整数");
        }
        if (scale != null && (scale < -1000 || scale > 1000)) {
            fail("边界 scale 须为 -1000–1000 的整数");
        }
        if ((precision != null || scale != null) && !"Decimal".equals(type)) {
            fail("只有 Decimal 字段可以声明精度或 scale");
        }
        if (timezone != null) {
            fail("边界时区契约暂不支持声明");
        }
    }

    /**
     * 返回当前解析。
     */
    public static EtlFieldContract parse(Object item) {
        if (!(item instanceof Map<?, ?> field)) {
            throw new IllegalArgumentException("边界字段契约须为对象");
        }
        Object name = field.get("name"), type = optional(field.get("type"));
        if (!(name instanceof String)) {
            throw new IllegalArgumentException("边界字段名称须为字符串");
        }
        if (type != null && !(type instanceof String)) {
            throw new IllegalArgumentException("边界字段类型须为字符串");
        }
        Object nullable = optional(field.get("nullable"));
        if (nullable != null && !(nullable instanceof Boolean)) {
            throw new IllegalArgumentException("边界 nullable 须为布尔值");
        }
        Object timezone = optional(field.get("timezone"));
        if (timezone != null) {
            throw new IllegalArgumentException("边界时区契约暂不支持声明");
        }
        return new EtlFieldContract((String) name, type == null ? "Object" : (String) type,
                (Boolean) nullable, integer(field.get("precision"), "precision"),
                integer(field.get("scale"), "scale"), null);
    }

    /**
     * 解析字段集合。
     */
    public static List<EtlFieldContract> parseFields(Iterable<?> fields) {
        if (fields == null) {
            throw new IllegalArgumentException("边界需要 1–256 个字段契约");
        }
        List<EtlFieldContract> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object item : fields) {
            var contract = parse(item);
            if (!names.add(contract.name())) {
                throw new IllegalArgumentException("边界字段名称重复");
            }
            result.add(contract);
            if (result.size() > 256) {
                throw new IllegalArgumentException("边界需要 1–256 个字段契约");
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("边界需要 1–256 个字段契约");
        }
        return List.copyOf(result);
    }

    /**
     * 返回元数据。
     */
    public EtlFieldMeta metadata() {
        return new EtlFieldMeta(name, type, nullable, precision, scale, timezone);
    }

    /**
     * 返回属性配置。
     */
    public Map<String, Object> properties() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("type", type);
        if (nullable != null) {
            result.put("nullable", nullable);
        }
        if (precision != null) {
            result.put("precision", precision);
        }
        if (scale != null) {
            result.put("scale", scale);
        }
        return result;
    }

    /**
     * 检查行中字段存在，并校验可空性和声明类型。
     */
    public void validate(Map<String, Object> row) {
        Object value = row.get(name);
        if (!row.containsKey(name) || value == null && Boolean.FALSE.equals(nullable)) {
            throw new IllegalStateException("子流程边界字段缺失或为空：" + name);
        }
        if (value == null) {
            return;
        }
        boolean compatible = switch (type) {
            case "String" -> value instanceof CharSequence;
            case "Boolean" -> value instanceof Boolean;
            case "Integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer;
            case "Long" ->
                    value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
            case "Double", "Decimal" -> value instanceof Number;
            case "Date" -> value instanceof java.util.Date || value instanceof java.time.temporal.TemporalAccessor;
            default -> true;
        };
        if (!compatible) {
            throw new IllegalStateException("子流程边界字段类型不匹配：" + name);
        }
        if (precision == null && scale == null) {
            return;
        }
        BigDecimal decimal;
        if (value instanceof BigDecimal exact) {
            decimal = exact;
        } else if (value instanceof BigInteger exact) {
            decimal = new BigDecimal(exact);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            decimal = BigDecimal.valueOf(((Number) value).longValue());
        } else {
            throw new IllegalStateException("子流程 Decimal 精度契约需要精确数值：" + name);
        }
        try {
            // 在 setScale 前限制算术运算范围，包括恶意的 BigDecimal 指数。
            if (decimal.scale() < -1000 || decimal.scale() > 1000 || decimal.precision() > 3000) {
                throw new ArithmeticException("Decimal representation exceeds contract bounds");
            }
            if (scale != null) {
                decimal = decimal.setScale(scale, RoundingMode.UNNECESSARY);
            }
            if (precision != null && decimal.precision() > precision) {
                throw new ArithmeticException("precision");
            }
        } catch (ArithmeticException ex) {
            throw new IllegalStateException("子流程边界字段精度或 scale 不匹配：" + name);
        }
    }

    /**
     * 返回当前可选。
     */
    private static Object optional(Object value) {
        return value == JSONNull.NULL ? null : value;
    }

    /**
     * 按当前值域约定解析或转换整数。
     */
    private static Integer integer(Object raw, String property) {
        Object value = optional(raw);
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof BigInteger integer) {
                return integer.intValueExact();
            }
            if (value instanceof BigDecimal decimal) {
                return decimal.intValueExact();
            }
            if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
                return ((Number) value).intValue();
            }
            if (value instanceof Long integer) {
                return Math.toIntExact(integer);
            }
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("边界 " + property + " 须为范围内整数");
        }
        throw new IllegalArgumentException("边界 " + property + " 须为整数");
    }

    /**
     * 抛出表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }
}
