package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.iocoder.yudao.framework.quality.core.clean.*;

import java.util.*;

/** 显式转换数值、日期和布尔类型，转换失败按配置保留、置空或报错。 */
public final class TypeConversionCleanFunction extends ConfiguredCleanFunction {

    public enum Kind {NUMBER, DATE, BOOLEAN}

    private final Kind kind;

    /**
     * 初始化 {@code TypeConversionCleanFunction}及其父类状态。
     */
    public TypeConversionCleanFunction(Kind kind) {
        super(switch (kind) {
                    case NUMBER -> "STRING_TO_NUMBER";
                    case DATE -> "STRING_TO_DATE";
                    case BOOLEAN -> "TO_BOOLEAN";
                },
                switch (kind) {
                    case NUMBER -> "字符串转数值";
                    case DATE -> "字符串转日期";
                    case BOOLEAN -> "任意类型转布尔值";
                },
                switch (kind) {
                    case NUMBER -> "{\"columns\":[\"value\"],\"targetType\":\"DECIMAL\",\"onInvalid\":\"KEEP\"}";
                    case DATE ->
                            "{\"columns\":[\"value\"],\"targetType\":\"DATE\",\"inputFormats\":[\"yyyy-MM-dd\"],\"onInvalid\":\"KEEP\"}";
                    case BOOLEAN ->
                            "{\"columns\":[\"value\"],\"trueValues\":[\"true\",\"1\",\"是\"],\"falseValues\":[\"false\",\"0\",\"否\"],\"ignoreCase\":true,\"onInvalid\":\"KEEP\"}";
                });
        this.kind = kind;
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = switch (kind) {
            case NUMBER -> checked(config, "targetType", "onInvalid");
            case DATE -> checked(config, "targetType", "inputFormats", "onInvalid");
            case BOOLEAN -> checked(config, "trueValues", "falseValues", "ignoreCase", "onInvalid");
        };
        result.put("onInvalid", option(result, "onInvalid", "KEEP", "KEEP", "NULL", "ERROR"));
        switch (kind) {
            case NUMBER -> result.put("targetType", option(result, "targetType", "DECIMAL", "DECIMAL", "INTEGER"));
            case DATE -> {
                result.put("targetType", option(result, "targetType", "DATE", "DATE", "DATETIME"));
                result.put("inputFormats", formats(result));
            }
            case BOOLEAN -> booleanConfig(result);
        }
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> input) {
        var config = normalizeConfig(input);
        String column = CleanValueSupport.firstColumn(config);
        return CleanValueSupport.mapColumn(rows, column, value -> {
            if (value == null) {
                return null;
            }
            Object converted = switch (kind) {
                case NUMBER -> {
                    var number = number(value);
                    if (number == null) {
                        yield null;
                    }
                    if ("INTEGER".equals(config.get("targetType"))) {
                        try {
                            yield number.toBigIntegerExact();
                        } catch (ArithmeticException invalid) {
                            yield null;
                        }
                    }
                    yield number;
                }
                case DATE -> {
                    var date = CleanDateSupport.parse(value, formats(config));
                    yield date == null ? null : "DATE".equals(
                            config.get("targetType")) ? date.toLocalDate() : date.toLocalDateTime();
                }
                case BOOLEAN -> booleanValue(value, config);
            };
            return converted == null ? invalid(value, (String) config.get("onInvalid")) : converted;
        });
    }
}
