package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;
import cn.iocoder.yudao.framework.quality.core.clean.CleanRegexSupport;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 字段值替换：不符合预设正则的值替换为指定默认值（或置空）
 *
 * @author Li
 */
public class ValueReplaceFunction implements CleanFunction {

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
        return "VALUE_REPLACE";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "字段值替换";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"status\"], \"regex\": \"^(启用|停用)$\", \"replacement\": \"未知\"}";
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.checked(config,
                Set.of("columns", "regex", "replacement"));
        String regex = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.string(result, "regex",
                null, true, 512, false);
        Pattern.compile(regex);
        result.put("regex", regex);
        if (result.containsKey("replacement")) {
            result.put("replacement",
                    cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.string(result,
                            "replacement", "", false, 1024, true));
        }
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        String column = CleanValueSupport.firstColumn(config);
        String regex = MapUtil.getStr(config, "regex");
        if (StrUtil.isBlank(regex)) {
            throw new IllegalArgumentException("字段值替换需要配置 regex（合法值正则）");
        }
        Pattern pattern = CleanRegexSupport.compile(regex);
        String replacement = MapUtil.getStr(config, "replacement");
        var budget = new CleanRegexSupport.OutputBudget();
        return CleanValueSupport.mapColumn(rows, column, value -> {
            if (value == null) {
                return null;
            }
            Object output = CleanRegexSupport.matches(pattern, String.valueOf(value)) ? value : replacement;
            budget.retain(output == null ? 0 : String.valueOf(output).length());
            return output;
        });
    }

}
