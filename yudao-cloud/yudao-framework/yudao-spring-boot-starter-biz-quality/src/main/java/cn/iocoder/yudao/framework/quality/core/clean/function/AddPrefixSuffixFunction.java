package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;

import java.util.List;
import java.util.Map;

/**
 * 字段前缀/后缀统一：handleType 1-加前缀；2-加后缀；3-去前缀；4-去后缀（已存在则不重复添加）
 *
 * @author Li
 */
public class AddPrefixSuffixFunction implements CleanFunction {

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
        return "ADD_PREFIX_SUFFIX";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "字段前缀/后缀统一";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"code\"], \"handleType\": 3, \"stringValue\": \"编号\"}";
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.checked(config,
                java.util.Set.of("columns", "handleType", "stringValue"));
        result.put("handleType",
                cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.integer(result, "handleType",
                        1, 1, 4));
        result.put("stringValue",
                cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionConfigSupport.string(result, "stringValue",
                        null, true, 128, false));
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        String column = CleanValueSupport.firstColumn(config);
        String token = MapUtil.getStr(config, "stringValue");
        if (StrUtil.isEmpty(token)) {
            throw new IllegalArgumentException("前缀/后缀处理需要配置 stringValue（前缀或后缀内容）");
        }
        int handleType = MapUtil.getInt(config, "handleType", 1);
        return CleanValueSupport.mapColumn(rows, column, value -> {
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value);
            switch (handleType) {
                case 1:
                    return text.startsWith(token) ? text : token + text;
                case 2:
                    return text.endsWith(token) ? text : text + token;
                case 3:
                    return text.startsWith(token) ? text.substring(token.length()) : text;
                case 4:
                    return text.endsWith(token) ? text.substring(0, text.length() - token.length()) : text;
                default:
                    throw new IllegalArgumentException("前缀/后缀处理不支持的 handleType：" + handleType);
            }
        });
    }

}
