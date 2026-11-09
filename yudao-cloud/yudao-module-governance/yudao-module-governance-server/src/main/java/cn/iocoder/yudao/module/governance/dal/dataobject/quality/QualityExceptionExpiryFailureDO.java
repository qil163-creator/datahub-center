package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 例外到期处理失败账本：按提交记录持久记录逐单失败、退避、耗尽与人工授权重试。
 *
 * <p>账本只描述“自动处理是否需要退避/人工介入”，不代表到期动作是否已生效；动作是否生效始终以
 * 工单上的 EXCEPTION_EXPIRED 动作为准（该动作同时保证重试不会重复创建复核单）。</p>
 */
@TableName("dh_governance_quality_expiry_failure")
@KeySequence("dh_governance_quality_expiry_failure_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityExceptionExpiryFailureDO extends BaseDO {

    /** 按退避等待下次自动处理 */
    public static final String STATUS_RETRYING = "RETRYING";
    /** 自动处理次数耗尽，等待人工核查后授权重试 */
    public static final String STATUS_EXHAUSTED = "EXHAUSTED";
    /** 后续自动处理已成功（或已不再满足到期条件），账本关闭 */
    public static final String STATUS_RESOLVED = "RESOLVED";

    /** 自动处理最大次数 */
    public static final int MAX_ATTEMPTS = 5;

    @TableId
    private Long id;

    private Long submissionId;

    private Long workOrderId;

    private String status;

    private Integer attemptCount;

    /** 安全摘要：不含驱动或厂商原文 */
    private String lastError;

    private LocalDateTime firstFailedAt;

    private LocalDateTime lastFailedAt;

    private LocalDateTime nextAttemptAt;

    /** SUCCEEDED 自动成功 / RETRY_AUTHORIZED 人工授权重试 */
    private String resolutionAction;

    private String resolutionReason;

    private Long resolvedBy;

    private LocalDateTime resolvedAt;

    /** CAS 版本，防止旧页面覆盖新状态 */
    private Integer version;
}
