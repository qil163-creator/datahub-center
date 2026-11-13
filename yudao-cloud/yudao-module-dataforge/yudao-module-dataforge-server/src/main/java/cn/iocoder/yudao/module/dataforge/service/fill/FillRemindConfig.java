package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 在线填报-提醒规则（fill_plan.remind_config JSON 的结构化模型）
 *
 * 所有字段均可缺省；缺省或超出界面允许范围时按默认值执行，保证历史计划与手写 JSON 不会让提醒失效。
 *
 * @author Li
 */
@Slf4j
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FillRemindConfig {

    /** 截止前提醒提前量默认值（小时） */
    public static final int DEFAULT_HOURS_BEFORE_DEADLINE = 24;

    /** 截止前提醒提前量上限（小时），与计划表单可选范围一致 */
    public static final int MAX_HOURS_BEFORE_DEADLINE = 168;

    /** 催办频控默认值（分钟） */
    public static final int DEFAULT_URGE_COOLDOWN_MINUTES = 60;

    /** 催办频控上限（分钟），与计划表单可选范围一致 */
    public static final int MAX_URGE_COOLDOWN_MINUTES = 1440;

    /**
     * 截止前多少小时发送临近截止提醒（1~168，默认 24）
     */
    private Integer hoursBeforeDeadline;

    /**
     * 同一组织两次催办的最小间隔（分钟，1~1440，默认 60）
     */
    private Integer urgeCooldownMinutes;

    /**
     * 解析提醒规则；JSON 为空或格式错误时返回全默认配置。
     *
     * @param remindConfigJson 计划上的 remindConfig
     * @return 提醒规则
     */
    public static FillRemindConfig parse(String remindConfigJson) {
        if (StrUtil.isBlank(remindConfigJson)) {
            return new FillRemindConfig();
        }
        try {
            FillRemindConfig config = JsonUtils.parseObject(remindConfigJson, FillRemindConfig.class);
            return config != null ? config : new FillRemindConfig();
        } catch (RuntimeException ex) {
            log.warn("[parse][提醒规则({}) 解析失败，按默认值执行]", remindConfigJson);
            return new FillRemindConfig();
        }
    }

    /**
     * @return 生效的截止前提醒提前量（小时）
     */
    public int resolveHoursBeforeDeadline() {
        return within(hoursBeforeDeadline, MAX_HOURS_BEFORE_DEADLINE, DEFAULT_HOURS_BEFORE_DEADLINE);
    }

    /**
     * @return 生效的催办频控间隔（分钟）
     */
    public int resolveUrgeCooldownMinutes() {
        return within(urgeCooldownMinutes, MAX_URGE_COOLDOWN_MINUTES, DEFAULT_URGE_COOLDOWN_MINUTES);
    }

    private static int within(Integer value, int max, int defaultValue) {
        return value != null && value >= 1 && value <= max ? value : defaultValue;
    }

}
