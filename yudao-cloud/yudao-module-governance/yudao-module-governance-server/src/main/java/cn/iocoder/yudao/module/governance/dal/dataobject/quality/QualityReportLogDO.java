package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据治理-数据质量-报告生成记录 DO
 *
 * @author Li
 */
@TableName("dh_governance_quality_report_log")
@KeySequence("dh_governance_quality_report_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityReportLogDO extends BaseDO {

    /** 生成状态 - 生成中 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 生成状态 - 已生成 */
    public static final String STATUS_SUCCESS = "SUCCESS";

    /** 生成状态 - 生成失败 */
    public static final String STATUS_FAILED = "FAILED";

    @TableId
    private Long id;

    /** 报告周期开始 */
    private String reportPeriodStart;

    /** 报告周期结束 */
    private String reportPeriodEnd;

    /** 生成状态：RUNNING/SUCCESS/FAILED */
    private String generateStatus;

    /** 生成进度（%） */
    private Integer generateProgress;

    /** 生成信息 */
    private String generateMsg;

    /** 生成人员 */
    private String generator;

    /** 生成时间 */
    private LocalDateTime generateTime;

}
