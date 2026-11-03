package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据治理-数据质量-告警记录 DO
 *
 * 告警等级分级（危险/严重/一般）+ 处理
 *
 * @author Li
 */
@TableName("dh_governance_quality_alert")
@KeySequence("dh_governance_quality_alert_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityAlertDO extends BaseDO {

    /** 告警等级 - 危险 */
    public static final String LEVEL_DANGER = "DANGER";

    /** 告警等级 - 严重 */
    public static final String LEVEL_SERIOUS = "SERIOUS";

    /** 告警等级 - 一般 */
    public static final String LEVEL_GENERAL = "GENERAL";

    /** 告警状态 - 未处理 */
    public static final String STATUS_UNPROCESSED = "UNPROCESSED";

    /** 告警状态 - 已处理 */
    public static final String STATUS_PROCESSED = "PROCESSED";

    @TableId
    private Long id;

    /** 告警标题 */
    private String alertTitle;

    /** 任务类型 */
    private String taskType;

    /** 任务ID */
    private Long taskId;

    /** 任务名称 */
    private String taskName;

    /** 告警等级：DANGER危险/SERIOUS严重/GENERAL一般 */
    private String alertLevel;

    /** 问题记录数 */
    private Long issueCount;

    /** 规则名称 */
    private String ruleName;

    /** 告警状态：UNPROCESSED未处理/PROCESSED已处理 */
    private String alertStatus;

    /** 告警时间 */
    private LocalDateTime alertTime;

}
