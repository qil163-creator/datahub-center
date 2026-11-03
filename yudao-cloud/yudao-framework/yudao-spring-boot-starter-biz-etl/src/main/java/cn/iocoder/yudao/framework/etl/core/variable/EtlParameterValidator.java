package cn.iocoder.yudao.framework.etl.core.variable;

import cn.hutool.core.util.StrUtil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 校验有界 ETL 参数定义，并解析字符串类型的运行参数。 */
public final class EtlParameterValidator {

    public static final int MAX_PARAMETERS = 100;

    public static final int MAX_VALUE_LENGTH = 4096;

    public static final int MAX_NUMERIC_LENGTH = 128;

    public static final int MAX_NUMERIC_PRECISION = 128;

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private static final Pattern INTEGER = Pattern.compile("[+-]?[0-9]+");

    private static final Pattern DECIMAL = Pattern.compile("[+-]?[0-9]+(\\.[0-9]+)?");

    public enum DefaultsPolicy {
        /** 输入缺失时使用经过验证的默认值；必填参数没有默认值时失败。 */
        APPLY_DEFAULTS,
        /** 忽略默认值；每个声明的子级参数都必须显式绑定。 */
        REQUIRE_ALL_EXPLICIT
    }

    /**
     * 初始化 {@code EtlParameterValidator}。
     */
    private EtlParameterValidator() {
    }

    /** 将 JSON 对象映射转换为框架定义，不携带业务层 DTO。 */
    public static List<EtlParameterDefinition> parseDefinitions(List<? extends Map<String, ?>> rawDefinitions) {
        if (rawDefinitions == null) {
            return List.of();
        }
        if (rawDefinitions.size() > MAX_PARAMETERS) {
            throw invalid("参数数量超出上限");
        }
        List<EtlParameterDefinition> definitions = new ArrayList<>(rawDefinitions.size());
        for (Map<String, ?> raw : rawDefinitions) {
            if (raw == null) {
                throw invalid("参数定义无效");
            }
            definitions.add(new EtlParameterDefinition(stringValue(raw.get("name")), stringValue(raw.get("type")),
                    booleanValue(raw.get("required")), stringValue(raw.get("defaultValue")),
                    stringValue(raw.get("min")), stringValue(raw.get("max")),
                    integerValue(raw.get("minLength")), integerValue(raw.get("maxLength"))));
        }
        validateDefinitions(definitions, false);
        return List.copyOf(definitions);
    }

    /**
     * 校验定义集合。
     */
    public static void validateDefinitions(List<EtlParameterDefinition> definitions,
            boolean requireDefaultsForRequired) {
        if (definitions == null) {
            return;
        }
        if (definitions.size() > MAX_PARAMETERS) {
            throw invalid("参数数量超出上限");
        }
        Set<String> names = new HashSet<>();
        for (EtlParameterDefinition definition : definitions) {
            if (definition == null || StrUtil.isBlank(definition.name())
                    || !NAME.matcher(definition.name()).matches() || !names.add(definition.name())) {
                throw invalid("参数名无效或重复");
            }
            String type = type(definition);
            boolean required = Boolean.TRUE.equals(definition.required());
            if (requireDefaultsForRequired && required && definition.defaultValue() == null) {
                throw invalid("必填参数必须配置默认值以供发布和调度使用");
            }
            if (definition.defaultValue() != null) {
                validateValue(definition, definition.defaultValue());
            }
            validateBounds(definition, type);
        }
    }

    /**
     * 根据当前配置和输入类型解析匹配的实现或定义。
     */
    public static Map<String, String> resolve(List<EtlParameterDefinition> definitions,
            Map<String, String> supplied,
            DefaultsPolicy defaultsPolicy) {
        List<EtlParameterDefinition> defs = definitions == null ? List.of() : definitions;
        validateDefinitions(defs, false);
        Map<String, String> values = supplied == null ? Map.of() : supplied;
        if (values.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("参数值不能为 null");
        }
        Set<String> known = new HashSet<>();
        defs.forEach(definition -> known.add(definition.name()));
        for (String key : values.keySet()) {
            if (!known.contains(key)) {
                throw invalid("运行参数包含未定义名称");
            }
        }
        if (defaultsPolicy == DefaultsPolicy.REQUIRE_ALL_EXPLICIT && values.size() != defs.size()) {
            throw invalid("子流程必须显式绑定全部参数");
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (EtlParameterDefinition definition : defs) {
            String value = values.get(definition.name());
            if (value == null && defaultsPolicy == DefaultsPolicy.APPLY_DEFAULTS) {
                value = definition.defaultValue();
            }
            if (value == null) {
                if (Boolean.TRUE.equals(
                        definition.required()) || defaultsPolicy == DefaultsPolicy.REQUIRE_ALL_EXPLICIT) {
                    throw invalid("必填参数缺少值");
                }
                continue;
            }
            validateValue(definition, value);
            result.put(definition.name(), "BOOLEAN".equals(type(definition))
                    ? value.toLowerCase(Locale.ROOT) : value);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * 校验边界集合。
     */
    private static void validateBounds(EtlParameterDefinition definition, String type) {
        if (definition.min() != null || definition.max() != null) {
            if (!"INTEGER".equals(type) && !"DECIMAL".equals(type)) {
                throw invalid("min/max 仅支持数值参数");
            }
            if (definition.min() != null) {
                parseNumber(type, definition.min());
            }
            if (definition.max() != null) {
                parseNumber(type, definition.max());
            }
            if (definition.min() != null && definition.max() != null
                    && parseNumber(type, definition.min()).compareTo(parseNumber(type, definition.max())) > 0) {
                throw invalid("参数最小值不能大于最大值");
            }
        }
        if (definition.minLength() != null || definition.maxLength() != null) {
            if (!"STRING".equals(type)) {
                throw invalid("minLength/maxLength 仅支持字符串参数");
            }
            Integer min = definition.minLength();
            Integer max = definition.maxLength();
            if ((min != null && (min < 0 || min > MAX_VALUE_LENGTH))
                    || (max != null && (max < 0 || max > MAX_VALUE_LENGTH))
                    || (min != null && max != null && min > max)) {
                throw invalid("字符串长度约束无效");
            }
        }
    }

    /**
     * 校验值。
     */
    private static void validateValue(EtlParameterDefinition definition, String value) {
        if (value == null || value.length() > MAX_VALUE_LENGTH) {
            throw invalid("参数值超出长度限制");
        }
        if (value.contains("${")) {
            throw invalid("运行参数值不能包含流程变量占位符");
        }
        String type = type(definition);
        if (Boolean.TRUE.equals(definition.required()) && StrUtil.isBlank(value)) {
            throw invalid("必填参数不能为空");
        }
        switch (type) {
            case "STRING" -> {
                if (definition.minLength() != null && value.length() < definition.minLength()) {
                    throw invalid("参数值短于最小长度");
                }
                if (definition.maxLength() != null && value.length() > definition.maxLength()) {
                    throw invalid("参数值长于最大长度");
                }
            }
            case "INTEGER", "DECIMAL" -> {
                BigDecimal numeric = parseNumber(type, value);
                if (definition.min() != null && numeric.compareTo(parseNumber(type, definition.min())) < 0) {
                    throw invalid("参数值小于最小值");
                }
                if (definition.max() != null && numeric.compareTo(parseNumber(type, definition.max())) > 0) {
                    throw invalid("参数值大于最大值");
                }
            }
            case "BOOLEAN" -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw invalid("布尔参数值无效");
                }
            }
            default -> throw invalid("不支持的参数类型");
        }
    }

    /**
     * 解析数值。
     */
    private static BigDecimal parseNumber(String type, String value) {
        if (value == null || value.length() > MAX_NUMERIC_LENGTH
                || !("INTEGER".equals(type) ? INTEGER : DECIMAL).matcher(value).matches()) {
            throw invalid("数值参数格式无效");
        }
        if ("INTEGER".equals(type)) {
            return new BigDecimal(new BigInteger(value));
        }
        BigDecimal parsed = new BigDecimal(value);
        if (parsed.precision() > MAX_NUMERIC_PRECISION || Math.abs((long) parsed.scale()) > MAX_NUMERIC_PRECISION) {
            throw invalid("数值参数精度超出限制");
        }
        return parsed;
    }

    /**
     * 返回类型。
     */
    private static String type(EtlParameterDefinition definition) {
        String type = StrUtil.blankToDefault(definition.type(), "STRING");
        String canonical = type.toUpperCase(Locale.ROOT);
        if (!Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN").contains(canonical)) {
            throw invalid("参数类型无效");
        }
        return canonical;
    }

    /**
     * 检查并返回配置中的字符串值。
     */
    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        throw invalid("参数文本字段必须是字符串");
    }

    /**
     * 检查并返回配置中的布尔值。
     */
    private static Boolean booleanValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw invalid("参数 required 字段必须是布尔值");
    }

    /**
     * 检查并返回当前配置中的整数值。
     */
    private static Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Short shortValue) {
            return shortValue.intValue();
        }
        if (value instanceof Byte byteValue) {
            return byteValue.intValue();
        }
        if (value instanceof Long longValue && longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
            return longValue.intValue();
        }
        throw invalid("参数长度约束必须是整数");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
