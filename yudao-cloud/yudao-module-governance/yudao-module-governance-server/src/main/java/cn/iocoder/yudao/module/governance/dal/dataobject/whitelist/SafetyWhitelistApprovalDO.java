package cn.iocoder.yudao.module.governance.dal.dataobject.whitelist;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 脱敏白名单审批记录 DO
 *
 * @author Li
 */
@TableName("dh_governance_safety_whitelist_approval")
@KeySequence("dh_safety_whitelist_approval_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyWhitelistApprovalDO extends BaseDO {

    /** 审批动作：提交 */
    public static final String ACTION_SUBMIT = "SUBMIT";

    /** 审批动作：通过 */
    public static final String ACTION_APPROVE = "APPROVE";

    /** 审批动作：驳回 */
    public static final String ACTION_REJECT = "REJECT";

    /**
     * 记录编号
     */
    @TableId
    private Long id;

    /**
     * 白名单编号
     */
    private Long whitelistId;

    /**
     * 审批动作：SUBMIT / APPROVE / REJECT
     */
    private String action;

    /**
     * 审批意见
     */
    private String opinion;

}
