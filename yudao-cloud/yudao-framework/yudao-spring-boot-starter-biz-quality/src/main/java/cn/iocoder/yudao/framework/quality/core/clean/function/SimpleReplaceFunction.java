package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;
import cn.iocoder.yudao.framework.quality.core.clean.CleanRegexSupport;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 正则表达式替换：匹配到的内容批量替换为指定文本（支持 $1 分组引用）
 *
 * @author Li
 */
public class SimpleReplaceFunction implements CleanFunction {

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
        return "SIMPLE_REPLACE";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "正则表达式替换";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"biz_date\"], \"regex\": \"(\\\\d{4})/(\\\\d{2})/(\\\\d{2})\", \"replacement\": \"$1-$2-$3\"}";
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.checked(config,
                java.util.Set.of("columns", "regex", "replacement"));
        String regex = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.string(result, "regex",
                null, true, 512, false);
        Pattern.compile(regex);
        result.put("regex", regex);
        result.put("replacement",
                cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.string(result, "replacement",
                        "", false, 1024, true));
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        String column = CleanValueSupport.firstColumn(config);
        String regex = MapUtil.getStr(config, "regex");
        if (StrUtil.isEmpty(regex)) {
            throw new IllegalArgumentException("正则替换需要配置 regex");
        }
        Pattern pattern = CleanRegexSupport.compile(regex);
        String replacement = StrUtil.nullToEmpty(MapUtil.getStr(config, "replacement"));
        var budget = new CleanRegexSupport.OutputBudget();
        return CleanValueSupport.mapColumn(rows, column, value -> {
            if (value == null) {
                return null;
            }
            return CleanRegexSupport.replaceAll(pattern, String.valueOf(value), replacement, budget);
        });
    }

}
