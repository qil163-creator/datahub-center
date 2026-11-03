package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.hutool.core.map.MapUtil;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 小数位统一：数值统一保留指定小数位（四舍五入），0 位时输出整数
 *
 * @author Li
 */
public class FixToPrecisionFunction implements CleanFunction {

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
        return "FIX_TO_PRECISION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "小数位统一";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"amount\"], \"scale\": 2}";
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.checked(config,
                java.util.Set.of("columns", "scale", "stringValue"));
        Object rawScale = result.containsKey("scale") ? result.get("scale") : result.get("stringValue");
        int scale = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.integer(
                java.util.Map.of("scale", rawScale == null ? 2 : rawScale), "scale", 2, 0, 12);
        result.remove("stringValue");
        result.put("scale", scale);
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        String column = CleanValueSupport.firstColumn(config);
        // 兼容 qData 的 stringValue 命名
        Integer scale = MapUtil.getInt(config, "scale");
        if (scale == null) {
            scale = MapUtil.getInt(config, "stringValue", 2);
        }
        int finalScale = Math.max(0, scale);
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
            BigDecimal rounded = number.setScale(finalScale, RoundingMode.HALF_UP);
            return finalScale == 0 ? rounded.toBigInteger() : rounded.toPlainString();
        });
    }

}
