package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("dh_governance_quality_work_order_notify_review")
@KeySequence("dh_governance_quality_work_order_notify_review_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityWorkOrderNotifyReviewDO extends BaseDO {

    public static final String ACTION_MARK_DELIVERED = "MARK_DELIVERED";

    public static final String ACTION_MARK_NOT_DELIVERED = "MARK_NOT_DELIVERED";

    public static final String ACTION_AUTHORIZE_RETRY = "AUTHORIZE_RETRY";

    @TableId
    private Long id;

    private Long workOrderId;

    private Long notifyId;

    private String requestId;

    private String actionType;

    private String payloadHash;

    private String expectedResolutionStatus;

    private Integer expectedResolutionVersion;

    private Long decisionId;

    private String outcome;

    private String reason;

    private Long operatorMessageId;

    private Long actorUserId;

    private Integer resultingResolutionVersion;
}
