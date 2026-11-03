package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.hutool.core.map.MapUtil;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 数值边界调整：将超出预设范围的数值调整为边界值
 *
 * <p>handleType：1-小于取 min、大于取 max（夹取）；2-越界统一取 min；3-越界统一取 max
 *
 * @author Li
 */
public class WithinBoundaryFunction implements CleanFunction {

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
        return "WITHIN_BOUNDARY";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "数值边界调整";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"age\"], \"min\": \"0\", \"max\": \"150\", \"handleType\": 1}";
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.checked(
                config, java.util.Set.of("columns", "min", "max", "handleType"));
        String min = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.decimal(result, "min",
                "0", false);
        String max = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.decimal(result, "max",
                "0", false);
        if (new BigDecimal(min).compareTo(new BigDecimal(max)) > 0) {
            throw new IllegalArgumentException("最小值不能大于最大值");
        }
        result.put("min", min);
        result.put("max", max);
        result.put("handleType",
                cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.integer(result, "handleType",
                        1, 1, 3));
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        String column = CleanValueSupport.firstColumn(config);
        BigDecimal min = new BigDecimal(String.valueOf(MapUtil.getStr(config, "min", "0")));
        BigDecimal max = new BigDecimal(String.valueOf(MapUtil.getStr(config, "max", "0")));
        int handleType = MapUtil.getInt(config, "handleType", 1);
        return CleanValueSupport.mapColumn(rows, column, value -> {
            if (CleanValueSupport.isBlankValue(value)) {
                return value;
            }
            BigDecimal number;
            try {
                number = new BigDecimal(String.valueOf(value).trim());
            } catch (NumberFormatException ex) {
                return value;
            }
            boolean belowMin = number.compareTo(min) < 0;
            boolean aboveMax = number.compareTo(max) > 0;
            if (!belowMin && !aboveMax) {
                return value;
            }
            switch (handleType) {
                case 2:
                    return min;
                case 3:
                    return max;
                default:
                    return belowMin ? min : max;
            }
        });
    }

}
