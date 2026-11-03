package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;

import java.util.List;
import java.util.Map;

/**
 * 手机号格式统一：去除国家码、空格、短横线等，统一为国内 11 位纯数字
 *
 * @author Li
 */
public class PhoneFormatStdFunction implements CleanFunction {

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
        return "PHONE_FORMAT_STD";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "手机号格式统一";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"phone\"]}";
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        String column = CleanValueSupport.firstColumn(config);
        return CleanValueSupport.mapColumn(rows, column, value -> {
            if (CleanValueSupport.isBlankValue(value)) {
                return value;
            }
            String digits = String.valueOf(value).replaceAll("[^0-9]", "");
            if (digits.startsWith("0086")) {
                digits = digits.substring(4);
            } else if (digits.startsWith("86") && digits.length() == 13) {
                digits = digits.substring(2);
            }
            return digits;
        });
    }

}
