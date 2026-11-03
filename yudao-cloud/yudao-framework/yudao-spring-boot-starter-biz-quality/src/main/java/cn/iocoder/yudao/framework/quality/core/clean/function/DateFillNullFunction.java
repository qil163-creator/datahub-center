package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.time.format.DateTimeFormatter;

/**
 * 日期空值填充：日期字段为空时填充为当前日期 / 昨天 / 固定值
 *
 * <p>fillType：CURRENT-当前时间；YESTERDAY-昨天；FIXED-固定值（value）
 *
 * @author Li
 */
public class DateFillNullFunction implements CleanFunction {

    private final Clock clock;

    /**
     * 使用给定参数初始化 {@code DateFillNullFunction}。
     */
    public DateFillNullFunction() {
        this(Clock.systemDefaultZone());
    }

    /**
     * 校验构造参数并初始化 {@code DateFillNullFunction}。
     */
    public DateFillNullFunction(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    /**
     * 未证明边界的扩展函数默认按集合级处理，不能自动进入逐行流水线。
     */
    @Override
    public ExecutionScope executionScope() {
        return ExecutionScope.ROW;
    }

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "DATE_FILL_NULL";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "日期空值填充";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"register_time\"], \"fillType\": \"CURRENT\", \"format\": \"yyyy-MM-dd HH:mm:ss\", \"value\": \"\"}";
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.checked(config,
                java.util.Set.of("columns", "fillType", "format", "value"));
        String fillType = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.string(
                result, "fillType", "CURRENT", false, 16, false).toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("CURRENT", "YESTERDAY", "FIXED").contains(fillType)) {
            throw new IllegalArgumentException("fillType 无效");
        }
        String format = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.string(
                result, "format", "yyyy-MM-dd HH:mm:ss", false, 64, false);
        DateTimeFormatter.ofPattern(format);
        result.put("fillType", fillType);
        result.put("format", format);
        if ("FIXED".equals(fillType)) {
            result.put("value", cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.string(
                    result, "value", null, true, 128, false));
        } else if (result.containsKey("value")) {
            result.put("value", cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.string(
                    result, "value", "", false, 128, true));
        }
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        String column = CleanValueSupport.firstColumn(config);
        String fillType = StrUtil.blankToDefault(MapUtil.getStr(config, "fillType"), "CURRENT")
                .toUpperCase(Locale.ROOT);
        String format = StrUtil.blankToDefault(MapUtil.getStr(config, "format"), "yyyy-MM-dd HH:mm:ss");
        String fixedValue = MapUtil.getStr(config, "value");
        ZonedDateTime now = ZonedDateTime.now(clock);
        String replacement = switch (fillType) {
            case "FIXED" -> fixedValue;
            case "YESTERDAY" -> DateTimeFormatter.ofPattern(format, Locale.ROOT).format(now.minusDays(1));
            default -> DateTimeFormatter.ofPattern(format, Locale.ROOT).format(now);
        };
        return CleanValueSupport.mapColumn(rows, column, value -> {
            if (!CleanValueSupport.isBlankValue(value)) {
                return value;
            }
            return replacement;
        });
    }

}
