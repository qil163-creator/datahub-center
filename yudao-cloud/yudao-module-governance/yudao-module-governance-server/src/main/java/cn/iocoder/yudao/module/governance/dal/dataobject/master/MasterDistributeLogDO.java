package cn.iocoder.yudao.module.governance.dal.dataobject.master;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 主数据派发执行记录 DO
 *
 * @author Li
 */
@TableName("dh_governance_master_distribute_log")
@KeySequence("dh_governance_master_distribute_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasterDistributeLogDO extends BaseDO {

    private Long totalCount;

    private Long successCount;

    private Long failureCount;

    private Long unknownCount;

    private String pushScope;

    private String pushMode;

    /** 停止命令持久回执，不证明远端请求或异步消费已经终止。 */
    private Long stopRequestedBy;

    private LocalDateTime stopRequestedAt;

    private String stopReason;

    /** 人工核查凭证不可覆盖；不等于派发成功。 */
    private String resolutionRequestId;

    private String resolutionOutcome;

    private String resolutionReason;

    private String stoppedEvidenceRef;

    private String outputEvidenceRef;

    private Long resolvedBy;

    private LocalDateTime resolvedAt;

    /** 零为历史记录，一为当前持久运行协议。 */
    private Integer runProtocol;

    /** 领取令牌和数据库时钟租约，用于隔离过期工作者。 */
    private String leaseToken;

    private LocalDateTime leaseUntil;

    /** 发送前领取次数；异常后的重新领取不能跨越发送边界。 */
    private Integer attempts;

    private LocalDateTime availableAt;

    private Long scheduleGeneration;

    private LocalDateTime scheduledFor;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 派发任务编号
     */
    private Long distributeId;

    /**
     * 主数据模型编号
     */
    private Long masterModelId;

    /**
     * 触发方式：1-自动调度 2-手动执行
     *
     * 枚举 {@link cn.iocoder.yudao.module.governance.enums.master.MasterRunTypeEnum}
     */
    private Integer runType;

    /**
     * 运行状态：1-等待派发 2-运行中 3-成功 4-失败 5-结果未知待核查 6-已人工处置（非成功）
     *
     * 枚举 {@link cn.iocoder.yudao.module.governance.enums.master.MasterRunStatusEnum}
     */
    private Integer runStatus;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 错误信息（异常堆栈摘要）
     */
    private String errorInfo;

    /**
     * 派发记录数
     */
    private Long dataCount;

    /**
     * 派发数据量（可读格式，如 12KB）
     */
    private String byteCount;

}
