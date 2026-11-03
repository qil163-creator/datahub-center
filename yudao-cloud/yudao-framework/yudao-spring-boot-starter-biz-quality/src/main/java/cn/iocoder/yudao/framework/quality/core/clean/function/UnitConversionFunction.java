package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.iocoder.yudao.framework.quality.core.clean.*;

import java.math.*;
import java.util.*;

/** 使用精确十进制执行仿射单位换算，输入无法识别为数值时保留原值。 */
public final class UnitConversionFunction extends ConfiguredCleanFunction {

    /**
     * 初始化 {@code UnitConversionFunction}及其父类状态。
     */
    public UnitConversionFunction() {
        super("UNIT_CONVERSION", "单位标准化类",
                "{\"columns\":[\"value\"],\"factor\":\"0.001\",\"offset\":\"0\",\"scale\":3}");
    }

    /**
     * 规范化并校验已持久化的函数配置；实现必须拒绝未知键。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        var result = checked(config, "factor", "offset", "scale");
        String factor = decimal(result, "factor", null, true);
        if (new BigDecimal(factor).signum() == 0) {
            throw new IllegalArgumentException("单位换算系数不能为零");
        }
        result.put("factor", factor);
        result.put("offset", decimal(result, "offset", "0", false));
        result.put("scale", CleanFunctionConfigSupport.integer(result, "scale", 2, 0, 12));
        return result;
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> input) {
        var config = normalizeConfig(input);
        BigDecimal factor = new BigDecimal((String) config.get("factor")), offset = new BigDecimal(
                (String) config.get("offset"));
        return CleanValueSupport.mapColumn(rows, CleanValueSupport.firstColumn(config), value -> {
            var number = number(value);
            return number == null ? value : number.multiply(factor).add(offset)
                    .setScale((Integer) config.get("scale"), RoundingMode.HALF_UP);
        });
    }
}
