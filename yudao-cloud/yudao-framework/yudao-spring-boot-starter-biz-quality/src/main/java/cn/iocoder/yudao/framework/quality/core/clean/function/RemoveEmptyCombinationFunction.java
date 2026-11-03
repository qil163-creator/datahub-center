package cn.iocoder.yudao.framework.quality.core.clean.function;

import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanValueSupport;
import cn.iocoder.yudao.framework.quality.core.clean.CleanExecutionControl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 组合字段为空删除：配置的关键字段全部为空（null / 空串 / 空白）时删除整行
 *
 * @author Li
 */
public class RemoveEmptyCombinationFunction implements CleanFunction {

    /**
     * 返回当前规则使用的目标列绑定信息。
     */
    @Override
    public ColumnBinding getColumnBinding() {
        return ColumnBinding.MULTIPLE;
    }

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "REMOVE_EMPTY_COMBINATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "组合字段为空删除";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"columns\": [\"user_id\", \"order_no\"]}";
    }

    /**
     * 执行清洗。
     */
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
        List<String> columns = CleanValueSupport.requiredColumns(config);
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            CleanExecutionControl.checkpoint();
            boolean allEmpty = true;
            for (String column : columns) {
                Object value = row.get(column);
                boolean nan = value instanceof Double doubleValue && doubleValue.isNaN()
                        || value instanceof Float floatValue && floatValue.isNaN();
                if (!CleanValueSupport.isBlankValue(value) && !nan) {
                    allEmpty = false;
                    break;
                }
            }
            if (!allEmpty) {
                CleanExecutionControl.retainOutput(row);
                result.add(row);
            }
        }
        return result;
    }

}
