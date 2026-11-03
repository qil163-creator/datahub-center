package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;

import java.util.List;
import java.util.Map;

/**
 * 字段值转小写
 *
 * @author Li
 */
public class ToLowercaseFunction implements CleanFunction {

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
        return "TO_LOWERCASE";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "字段值转小写";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"email\"]}";
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        String column = CleanValueSupport.firstColumn(config);
        return CleanValueSupport.mapColumn(rows, column,
                value -> value == null ? null : String.valueOf(value).toLowerCase(java.util.Locale.ROOT));
    }

}
