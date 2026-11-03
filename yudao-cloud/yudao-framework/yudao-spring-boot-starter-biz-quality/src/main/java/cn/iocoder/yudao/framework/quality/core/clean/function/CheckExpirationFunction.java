package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;
import cn.iocoder.yudao.framework.quality.core.clean.CleanDateSupport;
import cn.iocoder.yudao.framework.quality.core.clean.CleanExecutionControl;

import java.time.LocalDate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 清理过期记录：时间字段早于阈值时（相对天/月/年 或 固定日期），标记或删除该记录
 *
 * <p>dataRange：0-相对当前日期（dataRangeType 1-天 2-月 3-年）；1-固定日期（dataRangeType 1-早于，2/3-晚于）
 * <br>handleType：0-标记（handleColumns 置为 handleValue）；1-删除记录
 *
 * @author Li
 */
public class CheckExpirationFunction implements CleanFunction {

    private final Clock clock;

    /**
     * 使用给定参数初始化 {@code CheckExpirationFunction}。
     */
    public CheckExpirationFunction() {
        this(Clock.systemDefaultZone());
    }

    /** 同一批次使用同一日期边界，调用方可在重放时固定时钟。 */
    public CheckExpirationFunction(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    /**
     * 目标字段之外还必须存在的排序、优先级或更新字段。
     */
    @Override
    public List<String> referencedColumns(Map<String, Object> config) {
        return Integer.valueOf(0).equals(config.get("handleType")) ? List.of(
                (String) config.get("handleColumns")) : List.of();
    }

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "CHECK_EXPIRATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "清理过期记录";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"last_login_time\"], \"dataRange\": 0, \"dataRangeType\": 1, \"dataRangeValue\": 30, "
                + "\"handleType\": 0, \"handleColumns\": \"status\", \"handleValue\": \"过期\"}";
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.checked(config,
                java.util.Set.of("columns", "dataRange", "dataRangeType", "dataRangeValue", "handleType",
                        "handleColumns", "handleValue"));
        int dataRange = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.integer(result,
                "dataRange", 0, 0, 1);
        int rangeType = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.integer(result,
                "dataRangeType", 1, 1, 3);
        int handleType = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.integer(result,
                "handleType", 0, 0, 1);
        String rangeValue;
        if (dataRange == 0) {
            int offset = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.integer(
                    result, "dataRangeValue", 30, 0, 100_000);
            rangeValue = Integer.toString(offset);
        } else {
            rangeValue = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.string(
                    result, "dataRangeValue", null, true, 32, false);
            try {
                LocalDate.parse(rangeValue);
            } catch (java.time.DateTimeException invalid) {
                throw new IllegalArgumentException("固定日期须为有效的 yyyy-MM-dd 日期");
            }
        }
        result.put("dataRange", dataRange);
        result.put("dataRangeType", rangeType);
        result.put("dataRangeValue", rangeValue);
        result.put("handleType", handleType);
        if (handleType == 0 || result.containsKey("handleColumns")) {
            result.put("handleColumns",
                    cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.string(result,
                            "handleColumns", null, handleType == 0, 128, false));
        }
        if (result.containsKey("handleValue")) {
            result.put("handleValue",
                    cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.string(result,
                            "handleValue", "", false, 256, true));
        }
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        String column = CleanValueSupport.firstColumn(config);
        int dataRange = MapUtil.getInt(config, "dataRange", 0);
        int dataRangeType = MapUtil.getInt(config, "dataRangeType", 1);
        String dataRangeValue = MapUtil.getStr(config, "dataRangeValue");
        int handleType = MapUtil.getInt(config, "handleType", 0);
        String handleColumns = MapUtil.getStr(config, "handleColumns");
        String handleValue = MapUtil.getStr(config, "handleValue");

        LocalDate threshold;
        if (dataRange == 0) {
            int offset = Integer.parseInt(StrUtil.blankToDefault(dataRangeValue, "30"));
            LocalDate now = LocalDate.now(clock);
            switch (dataRangeType) {
                case 2:
                    threshold = now.minusMonths(offset);
                    break;
                case 3:
                    threshold = now.minusYears(offset);
                    break;
                default:
                    threshold = now.minusDays(offset);
            }
        } else {
            threshold = LocalDate.parse(dataRangeValue);
        }

        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            CleanExecutionControl.checkpoint();
            boolean expired = isExpired(row.get(column), threshold, dataRange == 0 || dataRangeType == 1);
            if (!expired) {
                CleanExecutionControl.retainOutput(row);
                result.add(row);
                continue;
            }
            if (handleType == 1) {
                continue; // 删除过期记录
            }
            Map<String, Object> copy = new LinkedHashMap<>(row);
            if (StrUtil.isNotBlank(handleColumns)) {
                copy.put(handleColumns, handleValue);
            }
            CleanExecutionControl.retainOutput(copy);
            result.add(copy);
        }
        return result;
    }

    /**
     * 判断过期。
     */
    private boolean isExpired(Object value, LocalDate threshold, boolean before) {
        if (CleanValueSupport.isBlankValue(value)) {
            return false;
        }
        var parsed = CleanDateSupport.parse(value, List.of());
        if (parsed == null) {
            return false;
        }
        LocalDate date = parsed.toLocalDate();
        return before ? date.isBefore(threshold) : date.isAfter(threshold);
    }

}
