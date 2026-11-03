package cn.iocoder.yudao.module.dataforge.service.fill;

import lombok.Data;

/**
 * 在线填报-采集周期配置（fill_plan.period_config JSON 的结构化模型）
 *
 * 所有字段均可缺省，缺省值见各字段注释；前端以业务化选项生成本配置，不要求用户写 Cron
 *
 * @author Li
 */
@Data
public class FillPeriodConfig {

    /**
     * 数据所属期相对生成时点的偏移（单位 = 一个周期）。
     * 默认 -1，即"本次生成的任务报上一期数据"（日报报昨天、月报报上月，政府报送常态）；
     * 配 0 表示报当期
     */
    private Integer periodOffset;

    /**
     * 周期任务的生成时刻 HH:mm，默认 08:00
     */
    private String generateTime;

    /**
     * 每周任务的生成日（1=周一 ... 7=周日），默认 1
     */
    private Integer weekGenerateDay;

    /**
     * 每月任务的生成日（1~28），默认 1
     */
    private Integer monthGenerateDay;

    /**
     * 每季度任务的生成日（季度首月的 1~28），默认 1
     */
    private Integer quarterGenerateDay;

    /**
     * 截止时间 = 生成日 + 本偏移天数（默认 3；日报建议 0 即当日截止）
     */
    private Integer deadlineOffsetDays;

    /**
     * 截止时刻 HH:mm，默认 17:30
     */
    private String deadlineTime;

    /**
     * 一次性任务：开放时间（ISO-8601，如 2026-09-01T09:00:00），缺省为发布时刻
     */
    private String onceOpenTime;

    /**
     * 一次性任务：截止时间（ISO-8601），缺省为发布后 7 天的 17:30
     */
    private String onceDeadlineTime;

}
