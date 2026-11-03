package cn.iocoder.yudao.module.dataforge.dal.dataobject.push;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据推送执行日志 DO
 *
 * @author Li
 */
@TableName("dh_dataforge_push_task_log")
@KeySequence("dh_dataforge_push_task_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataPushTaskLogDO extends BaseDO {

    @TableId
    private Long id;

    private Long pushTaskId;

    private Integer runStatus;

    private Integer runType;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long dataCount;

    private String realTimeLog;

    private String errorInfo;

    private String executeNo;

    private Long orgId;

    /**
     * 运行心跳；超时未刷新则判定中断
     */
    private LocalDateTime heartbeatTime;

    private Integer dispatchProtocol;

    private String dispatchToken;

    private LocalDateTime dispatchLeaseUntil;

    private Integer dispatchAttempts;

    private LocalDateTime nextDispatchAt;

    private LocalDateTime scheduledTime;

    /** 核查记录与原始运行凭证一起永久保留，不参与普通日志清理。 */
    private String resolutionRequestId;

    private String resolutionOutcome;

    private String resolutionReason;

    private String stoppedEvidenceRef;

    private String outputEvidenceRef;

    private Long resolvedBy;

    private LocalDateTime resolvedAt;

}
