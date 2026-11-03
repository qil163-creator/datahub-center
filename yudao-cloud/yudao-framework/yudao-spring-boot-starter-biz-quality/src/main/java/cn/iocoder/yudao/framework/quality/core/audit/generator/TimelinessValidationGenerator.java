package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;

/**
 * 及时性校验：时间字段值须落在当前时间回溯的窗口内（数据到达是否及时）
 *
 * <p>config: {"window": 30, "unit": "DAY", "timeField": "create_time", "format": "yyyy-MM-dd HH:mm:ss"}
 *
 * @author Li
 */
public class TimelinessValidationGenerator extends AuditSqlGenerator {

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return "TIMELINESS_VALIDATION";
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "及时性校验";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"window\": 30, \"unit\": \"DAY\", \"timeField\": \"create_time\"}";
    }

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    protected String buildValidFrag(AuditRuleContext context) {
        Integer window = MapUtil.getInt(context.getConfig(), "window");
        String unit = MapUtil.getStr(context.getConfig(), "unit", "DAY");
        String timeField = MapUtil.getStr(context.getConfig(), "timeField");
        if (window == null || window <= 0) {
            throw new IllegalArgumentException("及时性校验需要配置 window（回溯窗口）");
        }
        if (StrUtil.isBlank(timeField)) {
            timeField = context.getColumn();
        }
        String interval = unit.toUpperCase() + " '" + window + "'";
        // 时间字段 >= CURRENT_TIMESTAMP - interval
        return timeField + " >= (CURRENT_TIMESTAMP - INTERVAL " + interval + ")";
    }

}
