package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;
import cn.iocoder.yudao.framework.quality.core.clean.CleanDateSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.format.DateTimeFormatter;

/**
 * 日期格式统一：按 inputFormats 依次尝试解析（支持 "timestamp" 秒/毫秒时间戳），输出为 targetFormat
 *
 * @author Li
 */
public class DateFormatStdFunction implements CleanFunction {

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
        return "DATE_FORMAT_STD";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "日期格式统一";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"biz_date\"], \"targetFormat\": \"yyyy-MM-dd\", "
                + "\"inputFormats\": [\"yyyyMMdd\", \"yyyy/MM/dd\", \"yyyy-MM-dd\", \"timestamp\"]}";
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.checked(config,
                java.util.Set.of("columns", "targetFormat", "inputFormats"));
        String target = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.string(result,
                "targetFormat", "yyyy-MM-dd", false, 64, false);
        DateTimeFormatter.ofPattern(target);
        List<String> inputs = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.stringList(
                result, "inputFormats", List.of(), false, 16, 64);
        for (String format : inputs) {
            if (!"timestamp".equalsIgnoreCase(format)) {
                DateTimeFormatter.ofPattern(format);
            }
        }
        result.put("targetFormat", target);
        result.put("inputFormats", inputs);
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        String column = CleanValueSupport.firstColumn(config);
        String targetFormat = StrUtil.blankToDefault(MapUtil.getStr(config, "targetFormat"), "yyyy-MM-dd");
        List<String> inputFormats = new ArrayList<>();
        Object raw = config.get("inputFormats");
        if (raw instanceof List) {
            for (Object item : (List<Object>) raw) {
                if (item != null && StrUtil.isNotBlank(String.valueOf(item))) {
                    inputFormats.add(String.valueOf(item).trim());
                }
            }
        }
        return CleanValueSupport.mapColumn(rows, column, value -> {
            if (CleanValueSupport.isBlankValue(value)) {
                return value;
            }
            var parsed = CleanDateSupport.parse(value, inputFormats);
            return parsed == null ? value : DateTimeFormatter.ofPattern(targetFormat, java.util.Locale.ROOT)
                    .format(parsed);
        });
    }
}
