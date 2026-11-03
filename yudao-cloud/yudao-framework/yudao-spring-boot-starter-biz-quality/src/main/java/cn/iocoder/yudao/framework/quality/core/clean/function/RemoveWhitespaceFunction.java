package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.hutool.core.map.MapUtil;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;

import java.util.List;
import java.util.Map;

/**
 * 去除字段空格：handleType 1-去首尾空格；2-去全部空白字符
 *
 * @author Li
 */
public class RemoveWhitespaceFunction implements CleanFunction {

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
        return "REMOVE_WHITESPACE";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "去除字段空格";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"name\"], \"handleType\": 1}";
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.checked(config,
                java.util.Set.of("columns", "handleType"));
        result.put("handleType",
                cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.integer(result, "handleType",
                        1, 1, 2));
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        String column = CleanValueSupport.firstColumn(config);
        int handleType = MapUtil.getInt(config, "handleType", 1);
        return CleanValueSupport.mapColumn(rows, column, value -> {
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value);
            return handleType == 2 ? text.replaceAll("\\s+", "") : text.trim();
        });
    }

}
