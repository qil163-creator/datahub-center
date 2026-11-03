package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.iocoder.yudao.framework.quality.core.clean.*;

import java.math.BigDecimal;
import java.util.*;

/** 非法数值、枚举、时间、布尔值替换，与空值填充和类型转换分别建模。 */
public final class InvalidValueCleanFunction extends ConfiguredCleanFunction {

    public enum Kind {NUMBER, ENUM, DATE, BOOLEAN}

    private final Kind kind;

    /**
     * 初始化 {@code InvalidValueCleanFunction}及其父类状态。
     */
    public InvalidValueCleanFunction(Kind kind) {
        super(switch (kind) {
                    case NUMBER -> "INVALID_NUMBER_REPLACE";
                    case ENUM -> "INVALID_ENUM_REPLACE";
                    case DATE -> "INVALID_DATE_REPLACE";
                    case BOOLEAN -> "INVALID_BOOLEAN_REPLACE";
                },
                switch (kind) {
                    case NUMBER -> "替换无效数值";
                    case ENUM -> "替换非法枚举值";
                    case DATE -> "替换无效时间格式";
                    case BOOLEAN -> "替换非法布尔值";
                },
                switch (kind) {
                    case NUMBER ->
                            "{\"columns\":[\"value\"],\"min\":\"0\",\"max\":\"100\",\"invalidValues\":[\"-999\"],\"replacement\":\"0\"}";
                    case ENUM ->
                            "{\"columns\":[\"value\"],\"validValues\":[\"启用\",\"停用\"],\"ignoreCase\":false,\"replacement\":\"未知\"}";
                    case DATE -> "{\"columns\":[\"value\"],\"inputFormats\":[\"yyyy-MM-dd\"],\"replacement\":null}";
                    case BOOLEAN ->
                            "{\"columns\":[\"value\"],\"trueValues\":[\"true\",\"1\"],\"falseValues\":[\"false\",\"0\"],\"ignoreCase\":true,\"replacement\":false}";
                });
        this.kind = kind;
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = switch (kind) {
            case NUMBER -> checked(config, "min", "max", "invalidValues", "replacement");
            case ENUM -> checked(config, "validValues", "ignoreCase", "replacement");
            case DATE -> checked(config, "inputFormats", "replacement");
            case BOOLEAN -> checked(config, "trueValues", "falseValues", "ignoreCase", "replacement");
        };
        switch (kind) {
            case NUMBER -> {
                String min = decimal(result, "min", null, false), max = decimal(result, "max", null, false);
                if (min != null && max != null && new BigDecimal(min).compareTo(new BigDecimal(max)) > 0) {
                    throw new IllegalArgumentException("最小值不能大于最大值");
                }
                if (min != null) {
                    result.put("min", min);
                }
                if (max != null) {
                    result.put("max", max);
                }
                result.put("invalidValues", strings(result, "invalidValues", List.of(), false, 128, 128));
                result.put("replacement", decimal(result, "replacement", null, false));
            }
            case ENUM -> {
                result.put("validValues", strings(result, "validValues", List.of(), true, 1000, 128));
                result.put("ignoreCase", bool(result, "ignoreCase", false));
                result.put("replacement", text(result, "replacement", null, false, 1024, true));
            }
            case DATE -> {
                result.put("inputFormats", formats(result));
                String replacement = text(result, "replacement", null, false, 128, false);
                if (replacement != null && CleanDateSupport.parse(replacement, List.of()) == null) {
                    throw new IllegalArgumentException("替换日期必须是有效日期时间");
                }
                result.put("replacement", replacement);
            }
            case BOOLEAN -> {
                booleanConfig(result);
                Object replacement = result.containsKey("replacement") ? result.get("replacement") : Boolean.FALSE;
                if (replacement != null && !(replacement instanceof Boolean)) {
                    throw new IllegalArgumentException("替换布尔值必须为 boolean 或 null");
                }
                result.put("replacement", replacement);
            }
        }
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> input) {
        var config = normalizeConfig(input);
        Object replacement = config.get("replacement");
        if (kind == Kind.NUMBER && replacement != null) {
            replacement = new BigDecimal((String) replacement);
        }
        Object fixed = replacement;
        return CleanValueSupport.mapColumn(rows, CleanValueSupport.firstColumn(config), value -> {
            if (value == null) {
                return null;
            }
            boolean valid = switch (kind) {
                case NUMBER -> {
                    var number = number(value);
                    String min = (String) config.get("min"), max = (String) config.get("max");
                    yield number != null && !((List<?>) config.get("invalidValues")).contains(boundedText(value))
                            && (min == null || number.compareTo(new BigDecimal(min)) >= 0)
                            && (max == null || number.compareTo(new BigDecimal(max)) <= 0);
                }
                case ENUM -> {
                    String text = boundedText(value);
                    boolean ignoreCase = Boolean.TRUE.equals(config.get("ignoreCase"));
                    yield ((List<?>) config.get("validValues")).stream().map(String.class::cast)
                            .anyMatch(allowed -> ignoreCase ? allowed.equalsIgnoreCase(text) : allowed.equals(text));
                }
                case DATE -> CleanDateSupport.parse(value, formats(config)) != null;
                case BOOLEAN -> booleanValue(value, config) != null;
            };
            return valid ? value : fixed;
        });
    }
}
