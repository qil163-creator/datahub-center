package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

@TableName("dh_governance_quality_work_order_action")
@KeySequence("dh_governance_quality_work_order_action_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityWorkOrderActionDO extends BaseDO {

    @TableId
    private Long id;

    private Long workOrderId;

    private String requestId;

    private String actionType;

    private String fromStatus;

    private String toStatus;

    private Long fromOwnerUserId;

    private Long toOwnerUserId;

    private Long fromDeptId;

    private Long toDeptId;

    private String reason;

    private String payloadHash;

    private Long actorUserId;

    private Long actorDeptId;

    private Long submissionId;

    /** 到期事务的直接结果引用；历史空值不代表成功或失败，不按同根工单推断。 */
    private Long followUpWorkOrderId;

    private Integer expiryResultVersion;

    private String expiryIssueHash;

    private Long expiryIssueCount;

    /** 创建后续工单时的通知接收人，空值表示当时没有通知义务。 */
    private Long expiryReceiverUserId;

}
