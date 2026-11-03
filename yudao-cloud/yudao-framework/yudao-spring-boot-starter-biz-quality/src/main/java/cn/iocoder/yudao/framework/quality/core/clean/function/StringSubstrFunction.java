package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.hutool.core.map.MapUtil;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;

import java.util.List;
import java.util.Map;

/**
 * 超长字段截断：超过 maxLength 时按方向截取（direction 1-从头保留；2-从尾保留）
 *
 * @author Li
 */
public class StringSubstrFunction implements CleanFunction {

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
        return "STRING_SUBSTR";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "超长字段截断";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"description\"], \"maxLength\": 100, \"direction\": 1}";
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.checked(config,
                java.util.Set.of("columns", "maxLength", "direction"));
        if (!result.containsKey("maxLength")) {
            throw new IllegalArgumentException("缺少清洗参数 maxLength");
        }
        result.put("maxLength",
                cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.integer(result, "maxLength",
                        -1, 1, 10_000));
        result.put("direction",
                cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.integer(result, "direction", 1,
                        1, 2));
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        String column = CleanValueSupport.firstColumn(config);
        Integer maxLength = MapUtil.getInt(config, "maxLength");
        if (maxLength == null || maxLength <= 0) {
            throw new IllegalArgumentException("超长截断需要配置 maxLength（最大长度）");
        }
        int direction = MapUtil.getInt(config, "direction", 1);
        return CleanValueSupport.mapColumn(rows, column, value -> {
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value);
            int length = text.codePointCount(0, text.length());
            if (length <= maxLength) {
                return value;
            }
            return direction == 2 ? text.substring(text.offsetByCodePoints(0, length - maxLength))
                    : text.substring(0, text.offsetByCodePoints(0, maxLength));
        });
    }

}
