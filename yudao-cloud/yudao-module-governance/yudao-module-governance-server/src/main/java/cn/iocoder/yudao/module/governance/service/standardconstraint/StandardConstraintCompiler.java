package cn.iocoder.yudao.module.governance.service.standardconstraint;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeitem.StandardCodeItemDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardcodeset.StandardCodeSetDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 将标准元素携带的声明式约束编译为确定性的
 * 可执行表示。本类不依赖持久化；调用方必须在调用之前
 * 解析代码集快照及有效业务时间。保持编译器为纯逻辑，
 * 可使同一个精确约束快照复用于合规运行、发布
 * 门禁及质量规则物化。
 */
@Component
public class StandardConstraintCompiler {

    public static final String NON_NULL = "NON_NULL";

    public static final String LENGTH = "LENGTH";

    public static final String PRECISION_SCALE = "PRECISION_SCALE";

    public static final String ENUM = "ENUM";

    public static final String NUMERIC_RANGE = "NUMERIC_RANGE";

    public static final String REGEX = "REGEX";

    public static final String DATE_FORMAT = "DATE_FORMAT";

    public static final String NOT_NULL_STRATEGY = "NOT_NULL_VALIDATION";

    public static final String LENGTH_STRATEGY = "LENGTH_VALIDATION";

    public static final String PRECISION_SCALE_STRATEGY = "DECIMAL_PRECISION_VALIDATION";

    public static final String ENUM_STRATEGY = "ENUM_VALIDATION";

    public static final String RANGE_STRATEGY = "NUMERIC_RANGE_VALIDATION";

    public static final String REGEX_STRATEGY = "REGEX_VALIDATION";

    public static final String DATE_FORMAT_STRATEGY = "DATE_FORMAT_VALIDATION";

    private static final Pattern RANGE_PATTERN = Pattern.compile(
            "^\\s*([\\[(])\\s*([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))?\\s*,\\s*" +
                    "([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))?\\s*([\\])])\\s*$");

    private static final Map<String, String> DATE_REGEXES = dateRegexes();

    private static final java.util.Set<String> NUMERIC_TYPES = java.util.Set.of(
            "TINYINT", "SMALLINT", "INT", "INTEGER", "BIGINT", "SERIAL", "BIGSERIAL",
            "DECIMAL", "NUMERIC", "NUMBER", "FLOAT", "DOUBLE", "DOUBLE PRECISION", "REAL", "MONEY");

    private static final java.util.Set<String> EXACT_NUMERIC_TYPES = java.util.Set.of(
            "DECIMAL", "NUMERIC", "NUMBER", "MONEY");

    private static final java.util.Set<String> CHARACTER_TYPES = java.util.Set.of(
            "CHAR", "CHARACTER", "VARCHAR", "CHARACTER VARYING", "VARCHAR2", "NCHAR", "NVARCHAR",
            "NVARCHAR2", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB", "NCLOB", "STRING");

    /** 使用已解析的代码集快照编译当前元素定义。 */
    public Compilation compile(StandardElementDO element, StandardCodeSetDO codeSet,
            List<StandardCodeItemDO> codeItems, LocalDateTime effectiveAt) {
        return compileCurrent(element, codeSet, codeItems,
                effectiveAt != null ? effectiveAt : LocalDateTime.now());
    }

    /**
     * 按标准数据元约束配置生成结构化约束定义，拒绝不支持或无效的配置。
     */
    private Compilation compileCurrent(StandardElementDO element,
            StandardCodeSetDO codeSet, List<StandardCodeItemDO> codeItems,
            LocalDateTime effectiveAt) {
        if (element == null) {
            return new Compilation(List.of(),
                    List.of(error("DEFINITION", "ELEMENT_MISSING", "标准数据元不能为空", null)));
        }
        List<CompiledConstraint> constraints = new ArrayList<>();
        List<CompileError> errors = new ArrayList<>();

        if (StrUtil.isBlank(element.getColumnType())) {
            errors.add(error("DEFINITION", "COLUMN_TYPE_MISSING", "字段类型不能为空", element.getColumnType()));
        }
        if (element.getNullableFlag() == null) {
            errors.add(error(NON_NULL, "NULLABLE_FLAG_MISSING", "是否可空必须明确声明", null));
        } else if (!Objects.equals(element.getNullableFlag(), 0) && !Objects.equals(element.getNullableFlag(), 1)) {
            errors.add(error(NON_NULL, "INVALID_NULLABLE_FLAG", "是否可空只能为 0 或 1",
                    element.getNullableFlag()));
        } else if (Objects.equals(element.getNullableFlag(), 0)) {
            constraints.add(constraint(NON_NULL, NOT_NULL_STRATEGY,
                    // 空字符串语义仅适用于字符数据；数值零绝不能
                    // 与 '' 比较，MySQL 会将两者强制转为零并误报错误。
                    linkedMap("allowBlank", !isCharacterType(element.getColumnType()))));
        }

        if (element.getDataLength() != null) {
            if (element.getDataLength() <= 0) {
                errors.add(error(LENGTH, "INVALID_LENGTH", "数据长度必须大于 0", element.getDataLength()));
            } else {
                constraints.add(constraint(LENGTH, LENGTH_STRATEGY,
                        linkedMap("minLength", 0, "maxLength", element.getDataLength(),
                                "ignoreNullValue", true)));
            }
        }

        compilePrecisionScale(element, constraints, errors);
        compileEnum(element, codeSet, codeItems, effectiveAt, constraints, errors);
        compileRange(element, constraints, errors);
        compileFormat(element, constraints, errors);

        return new Compilation(List.copyOf(constraints), List.copyOf(errors));
    }

    /**
     * 编译标准约束编译器的精度小数位。
     */
    private static void compilePrecisionScale(StandardElementDO element,
            List<CompiledConstraint> constraints,
            List<CompileError> errors) {
        Integer precision = element.getDataPrecision();
        Integer scale = element.getDataScale();
        if (precision == null && scale == null) {
            return;
        }
        if (!isExactNumericType(element.getColumnType())) {
            errors.add(error(PRECISION_SCALE, "TYPE_INCOMPATIBLE",
                    "精度/小数位约束只能用于数值字段", element.getColumnType()));
            return;
        }
        if (precision == null || scale == null || precision <= 0 || scale < 0 || scale > precision) {
            errors.add(error(PRECISION_SCALE, "INVALID_PRECISION_SCALE",
                    "精度和小数位必须同时声明，且满足 precision > 0、0 <= scale <= precision",
                    linkedMap("precision", precision, "scale", scale)));
            return;
        }
        constraints.add(constraint(PRECISION_SCALE, PRECISION_SCALE_STRATEGY,
                linkedMap("precision", precision, "scale", scale, "skipInteger", true,
                        "ignoreNullValue", true)));
    }

    /**
     * 编译标准约束编译器的枚举。
     */
    private static void compileEnum(StandardElementDO element, StandardCodeSetDO codeSet,
            List<StandardCodeItemDO> codeItems, LocalDateTime effectiveAt,
            List<CompiledConstraint> constraints,
            List<CompileError> errors) {
        if (element.getCodeSetId() == null) {
            return;
        }
        if (codeSet == null || !Objects.equals(element.getCodeSetId(), codeSet.getId())) {
            errors.add(error(ENUM, "CODE_SET_NOT_FOUND", "关联码表不存在", element.getCodeSetId()));
            return;
        }
        if (!Objects.equals(codeSet.getStatus(), (short) 0)) {
            errors.add(error(ENUM, "CODE_SET_DISABLED", "关联码表未启用", element.getCodeSetId()));
            return;
        }
        TreeSet<String> values = new TreeSet<>();
        if (codeItems != null) {
            for (StandardCodeItemDO item : codeItems) {
                if (item == null || !Objects.equals(item.getCodeSetId(), element.getCodeSetId())
                        || !Objects.equals(item.getStatus(), (short) 0)
                        || (item.getValidFrom() != null && item.getValidFrom().isAfter(effectiveAt))
                        || (item.getValidTo() != null && !item.getValidTo().isAfter(effectiveAt))) {
                    continue;
                }
                String value = canonicalText(item.getValue());
                if (StrUtil.isNotBlank(value)) {
                    values.add(value);
                }
            }
        }
        if (values.isEmpty()) {
            errors.add(error(ENUM, "EMPTY_CODE_SET", "关联码表在当前生效时间没有可用码值",
                    element.getCodeSetId()));
            return;
        }
        constraints.add(constraint(ENUM, ENUM_STRATEGY,
                linkedMap("validValues", List.copyOf(values), "ignoreCase", false,
                        "ignoreNullValue", true)));
    }

    /**
     * 编译标准约束编译器的范围。
     */
    private static void compileRange(StandardElementDO element, List<CompiledConstraint> constraints,
            List<CompileError> errors) {
        String range = canonicalText(element.getValueRange());
        if (StrUtil.isBlank(range)) {
            return;
        }
        if (!isNumericType(element.getColumnType())) {
            errors.add(error(NUMERIC_RANGE, "TYPE_INCOMPATIBLE",
                    "数值范围约束只能用于数值字段", element.getColumnType()));
            return;
        }
        Matcher matcher = RANGE_PATTERN.matcher(range);
        if (!matcher.matches() || StrUtil.isBlank(matcher.group(2)) && StrUtil.isBlank(matcher.group(3))) {
            errors.add(error(NUMERIC_RANGE, "INVALID_NUMERIC_RANGE", "数值范围格式必须类似 [0,100]",
                    element.getValueRange()));
            return;
        }
        try {
            BigDecimal min = StrUtil.isBlank(matcher.group(2)) ? null : new BigDecimal(matcher.group(2));
            BigDecimal max = StrUtil.isBlank(matcher.group(3)) ? null : new BigDecimal(matcher.group(3));
            boolean includeMin = "[".equals(matcher.group(1));
            boolean includeMax = "]".equals(matcher.group(4));
            if (min != null && max != null) {
                int comparison = min.compareTo(max);
                if (comparison > 0 || comparison == 0 && !(includeMin && includeMax)) {
                    throw new IllegalArgumentException("empty range");
                }
            }
            constraints.add(constraint(NUMERIC_RANGE, RANGE_STRATEGY,
                    linkedMap("minValue", decimalText(min), "maxValue", decimalText(max),
                            "includeMin", includeMin, "includeMax", includeMax,
                            "ignoreNullValue", true)));
        } catch (RuntimeException ex) {
            errors.add(error(NUMERIC_RANGE, "INVALID_NUMERIC_RANGE", "数值范围无效",
                    element.getValueRange()));
        }
    }

    /**
     * 编译标准约束编译器的格式。
     */
    private static void compileFormat(StandardElementDO element, List<CompiledConstraint> constraints,
            List<CompileError> errors) {
        String format = canonicalText(element.getDataFormat());
        if (StrUtil.isBlank(format)) {
            return;
        }
        boolean dateFormat = isTemporalType(element.getColumnType()) || looksLikeDatePattern(format);
        if (dateFormat) {
            String regex = DATE_REGEXES.get(format);
            if (regex == null) {
                errors.add(error(DATE_FORMAT, "UNSUPPORTED_DATE_FORMAT",
                        "日期时间格式不在安全白名单中", element.getDataFormat()));
                return;
            }
            constraints.add(constraint(DATE_FORMAT, DATE_FORMAT_STRATEGY,
                    linkedMap("pattern", format, "regex", regex, "ignoreNullValue", true)));
            return;
        }
        try {
            Pattern.compile(format);
            constraints.add(constraint(REGEX, REGEX_STRATEGY,
                    linkedMap("regex", format, "ignoreNullValue", true)));
        } catch (PatternSyntaxException ex) {
            errors.add(error(REGEX, "INVALID_REGEX", "数据格式不是合法正则表达式",
                    element.getDataFormat()));
        }
    }

    /**
     * 创建结构化标准约束定义。
     */
    private static CompiledConstraint constraint(String type, String strategyKey, Map<String, Object> config) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("constraintType", type);
        canonical.put("config", config);
        String canonicalJson = JsonUtils.toJsonString(canonical);
        return new CompiledConstraint(type, strategyKey,
                Collections.unmodifiableMap(new LinkedHashMap<>(config)), canonicalJson,
                DigestUtil.sha256Hex(canonicalJson));
    }

    /**
     * 记录标准约束编译器运行错误。
     */
    private static CompileError error(String type, String code, String message, Object actual) {
        return new CompileError(type, code, message, actual);
    }

    /**
     * 判断标准约束编译器是否时间型类型。
     */
    private static boolean isTemporalType(String columnType) {
        if (StrUtil.isBlank(columnType)) {
            return false;
        }
        String upper = columnType.trim().toUpperCase(java.util.Locale.ROOT);
        return upper.contains("DATE") || upper.contains("TIME");
    }

    /**
     * 判断标准约束编译器是否数值类型。
     */
    private static boolean isNumericType(String columnType) {
        return NUMERIC_TYPES.contains(baseType(columnType));
    }

    /**
     * 判断标准约束编译器是否精确数值类型。
     */
    private static boolean isExactNumericType(String columnType) {
        return EXACT_NUMERIC_TYPES.contains(baseType(columnType));
    }

    /**
     * 判断标准约束编译器是否字符型类型。
     */
    private static boolean isCharacterType(String columnType) {
        return CHARACTER_TYPES.contains(baseType(columnType));
    }

    /**
     * 从完整数据库字段类型中提取基础类型。
     */
    private static String baseType(String columnType) {
        if (StrUtil.isBlank(columnType)) {
            return "";
        }
        String upper = columnType.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        int paren = upper.indexOf('(');
        return paren < 0 ? upper : upper.substring(0, paren).trim();
    }

    /**
     * 判断格式配置是否符合日期时间模板的形态。
     */
    private static boolean looksLikeDatePattern(String format) {
        return format.contains("yyyy") || format.contains("MM") && format.contains("dd");
    }

    /**
     * 将标准约束文本转换为规范表示。
     */
    private static String canonicalText(String value) {
        return value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFC).trim();
    }

    /**
     * 将约束数值转换为规范化十进制字符串。
     */
    private static String decimalText(BigDecimal decimal) {
        return decimal == null ? null : decimal.stripTrailingZeros().toPlainString();
    }

    /**
     * 构造保持插入顺序的约束属性映射。
     */
    private static Map<String, Object> linkedMap(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put((String) entries[i], entries[i + 1]);
        }
        return result;
    }

    /**
     * 根据日期时间格式生成对应的正则表达式约束。
     */
    private static Map<String, String> dateRegexes() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("yyyy-MM-dd", "^(?:\\d{4})-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])$");
        values.put("yyyy/MM/dd", "^(?:\\d{4})/(?:0[1-9]|1[0-2])/(?:0[1-9]|[12]\\d|3[01])$");
        values.put("yyyyMMdd", "^(?:\\d{4})(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])$");
        values.put("HH:mm:ss", "^(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$");
        values.put("yyyy-MM-dd HH:mm:ss",
                "^(?:\\d{4})-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01]) " +
                        "(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$");
        values.put("yyyy/MM/dd HH:mm:ss",
                "^(?:\\d{4})/(?:0[1-9]|1[0-2])/(?:0[1-9]|[12]\\d|3[01]) " +
                        "(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$");
        values.put("yyyy-MM-dd'T'HH:mm:ss",
                "^(?:\\d{4})-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])T" +
                        "(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d$");
        return Collections.unmodifiableMap(values);
    }

    /** 不可变编译结果。 */
    public record Compilation(List<CompiledConstraint> constraints, List<CompileError> errors) {

        /**
         * 判断标准约束编译结果是否不含阻断错误。
         */
        public boolean compilable() {
            return errors == null || errors.isEmpty();
        }
    }

    /** 一条可执行、通过哈希定位的约束。 */
    public record CompiledConstraint(String constraintType, String strategyKey, Map<String, Object> config,
                                     String canonicalConfig, String constraintHash) {

    }

    /** 与声明约束或元素定义关联、按拒绝原则处理的错误。 */
    public record CompileError(String constraintType, String code, String message, Object actual) {

    }
}
