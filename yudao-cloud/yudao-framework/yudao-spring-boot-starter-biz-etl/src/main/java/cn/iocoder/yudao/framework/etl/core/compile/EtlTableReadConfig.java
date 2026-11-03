package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Map;
import java.util.Set;

/** 表输入的草稿契约与执行能力约束；游标和分片参数不得被执行器静默忽略。 */
public final class EtlTableReadConfig {
    private static final Set<String> READ_MODES = Set.of("FULL", "ID_INCREMENT", "TIME_INCREMENT");
    private static final Set<String> TIME_FORMATS = Set.of("yyyy-MM-dd", "yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    private EtlTableReadConfig() { }

    /** 草稿允许暂未填写必填项，但已经填写的值必须符合无损、结构化配置契约。 */
    public static void validateDraft(JSONObject config) {
        if (config.getInt("ifSql", 0) == 1) return;
        optionalEnum(config, "readMode", READ_MODES);
        String mode = config.getStr("readMode", "FULL");
        if ("ID_INCREMENT".equals(mode)) {
            optionalText(config, "incrementColumn", 256);
            decimal(config, "startValue", false);
        }
        if ("TIME_INCREMENT".equals(mode)) validateTimeConditions(config);
        Object batchValue = config.get("batchRead");
        if (!present(batchValue)) return;
        var batch = object(batchValue, "分批读取");
        if (present(batch.get("enabled")) && !(batch.get("enabled") instanceof Boolean)) throw invalid("分批读取开关必须为布尔值");
        if (!"FULL".equals(mode) || !batch.getBool("enabled", false)) return;
        optionalText(batch, "field", 256);
        optionalEnum(batch, "mode", Set.of("ID", "TIME"));
        optionalEnum(batch, "timeUnit", Set.of("HOUR", "MINUTE", "SECOND"));
        boundedInteger(batch, "maxRetries", 0, 100);
        boundedInteger(batch, "retryIntervalSeconds", 1, 86400);
        if ("TIME".equals(batch.getStr("mode"))) {
            boundedInteger(batch, "size", 1, Integer.MAX_VALUE);
            batchDate(batch, "start"); batchDate(batch, "end");
            if (filled(batch, "start") && filled(batch, "end") && batch.getStr("start").compareTo(batch.getStr("end")) >= 0)
                throw invalid("分批结束时间必须晚于起始时间");
        } else {
            BigDecimal start = decimal(batch, "start", false), end = decimal(batch, "end", false);
            decimal(batch, "size", true);
            if (start != null && end != null && start.compareTo(end) >= 0) throw invalid("分批结束ID必须大于起始ID");
        }
    }

    private static void validateTimeConditions(JSONObject config) {
        optionalEnum(config, "timeFormat", TIME_FORMATS);
        String format = config.getStr("timeFormat", "yyyy-MM-dd");
        Object conditions = config.get("timeConditions");
        if (present(conditions)) {
            if (!(conditions instanceof JSONArray array) || array.size() > 100) throw invalid("时间增量条件必须为不超过100项的数组");
            for (Object value : array) {
                var condition = object(value, "时间增量条件");
                optionalText(condition, "key", 256);
                optionalText(condition, "field", 256);
                optionalEnum(condition, "operator", Set.of(">", ">=", "<", "<="));
                optionalEnum(condition, "baselineType", Set.of("FIXED", "TIME_RANGE", "SQL"));
                if ("SQL".equals(condition.getStr("baselineType"))) sqlText(condition, "baselineValue");
                else optionalText(condition, "baselineValue", 32);
                optionalText(condition, "cursorTime", 32);
                if ("FIXED".equals(condition.getStr("baselineType"))) date(condition, "baselineValue", format);
                if ("TIME_RANGE".equals(condition.getStr("baselineType"))) date(condition, "cursorTime", format);
            }
        }
    }

    /** 三条执行路径共用同一能力边界；草稿可编辑不表示运行时已经具备可靠游标。 */
    public static void requireSupportedExecution(JSONObject config) {
        validateDraft(config);
        if (config.getInt("ifSql", 0) == 1) return;
        if (!"FULL".equals(config.getStr("readMode", "FULL")))
            throw invalid("表输入增量配置可保存；当前执行引擎尚未接入持久游标与成功提交后的游标推进，不能执行ID或时间范围增量读取");
        Object batchValue = config.get("batchRead");
        if (present(batchValue) && object(batchValue, "分批读取").getBool("enabled", false))
            throw invalid("表输入分批配置可保存；当前执行引擎尚未接入ID/时间分片检查点与批次重试，不能执行分批读取");
    }

    private static JSONObject object(Object value, String label) {
        if (!(value instanceof JSONObject) && !(value instanceof Map<?, ?>)) throw invalid(label + "必须为结构化对象");
        return value instanceof JSONObject json ? json : JSONUtil.parseObj(value);
    }

    private static void optionalEnum(JSONObject config, String key, Set<String> values) {
        Object value = config.get(key);
        if (present(value) && (!(value instanceof String text) || !values.contains(text))) throw invalid(key + "选项无效");
    }

    private static void optionalText(JSONObject config, String key, int maximum) {
        Object value = config.get(key);
        if (present(value) && (!(value instanceof String text) || text.length() > maximum || text.codePoints().anyMatch(Character::isISOControl)))
            throw invalid(key + "必须为长度不超过" + maximum + "的普通文本");
    }

    private static void sqlText(JSONObject config, String key) {
        Object value = config.get(key);
        if (present(value) && (!(value instanceof String text) || text.length() > 8000
                || text.codePoints().anyMatch(code -> Character.isISOControl(code) && code != '\n' && code != '\r' && code != '\t')))
            throw invalid(key + "必须为长度不超过8000的SQL表达式文本");
    }

    private static BigDecimal decimal(JSONObject config, String key, boolean positive) {
        Object value = config.get(key);
        if (!present(value) || value instanceof String text && text.isBlank()) return null;
        if (!(value instanceof String text) || text.length() > 256 || !text.trim().matches("[+-]?[0-9]+(?:[.][0-9]+)?"))
            throw invalid(key + "必须使用十进制字符串，避免ID或数值精度丢失");
        var decimal = new BigDecimal(text.trim());
        if (positive && decimal.signum() <= 0) throw invalid(key + "必须大于零");
        return decimal;
    }

    private static void boundedInteger(JSONObject config, String key, int minimum, int maximum) {
        Object value = config.get(key);
        if (!present(value) || value instanceof String text && text.isBlank()) return;
        try {
            int number = new BigDecimal(String.valueOf(value)).intValueExact();
            if (number < minimum || number > maximum) throw new ArithmeticException();
        } catch (RuntimeException failure) { throw invalid(key + "必须为" + minimum + "至" + maximum + "的整数"); }
    }

    private static boolean present(Object value) { return value != null && !JSONUtil.isNull(value); }
    private static boolean filled(JSONObject config, String key) { return present(config.get(key)) && !config.getStr(key).isBlank(); }

    private static void batchDate(JSONObject config, String key) {
        optionalText(config, key, 32);
        if (filled(config, key)) date(config, key, config.getStr(key).length() == 10 ? "yyyy-MM-dd" : "yyyy-MM-dd HH:mm:ss");
    }

    private static void date(JSONObject config, String key, String format) {
        if (!filled(config, key)) return;
        try {
            if ("yyyy-MM-dd".equals(format)) LocalDate.parse(config.getStr(key), DATE);
            else LocalDateTime.parse(config.getStr(key), DATE_TIME);
        } catch (RuntimeException failure) { throw invalid(key + "必须为有效的" + format + "时间"); }
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
