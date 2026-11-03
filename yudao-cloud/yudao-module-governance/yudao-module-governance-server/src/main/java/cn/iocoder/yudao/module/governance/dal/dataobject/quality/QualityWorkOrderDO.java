package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据治理-数据质量-问题处理工单 DO
 */
@TableName("dh_governance_quality_work_order")
@KeySequence("dh_governance_quality_work_order_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityWorkOrderDO extends BaseDO {

    public static final String STATUS_PENDING = "PENDING";

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";

    public static final String STATUS_PENDING_RECHECK = "PENDING_RECHECK";

    public static final String STATUS_RECHECKING = "RECHECKING";

    public static final String STATUS_PENDING_CONFIRMATION = "PENDING_CONFIRMATION";

    public static final String STATUS_PROCESSED = "PROCESSED";

    /** 仅历史读取兼容，新转派不再写入 */
    public static final String STATUS_TRANSFERRED = "TRANSFERRED";

    public static final String RESP_UNASSIGNED = "UNASSIGNED";

    public static final String RESP_ASSIGNED = "ASSIGNED";

    public static final String CLOSE_VERIFIED_PASS = "VERIFIED_PASS";

    public static final String CLOSE_EXPLAINED = "EXPLAINED";

    public static final String CLOSE_EXCEPTION = "EXCEPTION_ACCEPTED";

    public static final String CLOSE_LEGACY = "LEGACY_PROCESSED";

    public static final String CONFIRM_RECHECK = "RECHECK_PASS";

    public static final String CONFIRM_EXPLAIN = "EXPLAIN";

    public static final String CONFIRM_EXCEPTION = "EXCEPTION";

    @TableId
    private Long id;

    private String orderName;

    private String orderCode;

    private Long rootOrderId;

    private String sourceType;

    private String topicRef;

    private Long taskId;

    private String taskName;

    private Long issueCount;

    private Long requestDeptId;

    private Long providerDeptId;

    private Long handlingDeptId;

    private Long ownerUserId;

    private Long creatorUserId;

    private Long confirmerUserId;

    private String confirmationPolicy;

    private String problemType;

    private String severity;

    private LocalDateTime deadline;

    private String deadlineBasis;

    private String orderStatus;

    private String responsibilityStatus;

    private Integer lockVersion;

    private Long currentSubmissionId;

    private String closeType;

    private String closeReason;

    private Long closedBy;

    private LocalDateTime closedAt;

    private Integer everOverdue;

    private Integer closedOverdue;

    private String confirmationPendingType;

    private String lastError;

    private String legacyState;

    private String evidenceCompleteness;

    private String auditOpinion;

    private String description;

}
