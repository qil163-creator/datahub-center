package cn.iocoder.yudao.module.dataforge.dal.dataobject.fill;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 在线填报-提交记录 DO（快照头，内容不可变；BPM businessKey = 本表 id；审批与合并只认快照）
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_submission")
@KeySequence("dh_dataforge_fill_submission_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillSubmissionDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 工作项编号
     */
    private Long workItemId;

    /**
     * 周期任务编号（冗余便查）
     */
    private Long taskId;

    /**
     * 提交次数（驳回整改后重新提交 +1，对应新的 BPM 实例）
     */
    private Integer attemptNo;

    /**
     * 类型：1-正常 2-零上报 3-更正，见 FillSubmissionTypeEnum
     */
    private Integer submissionType;

    /**
     * 冻结的模板版本编号
     */
    private Long tableVersionId;

    /**
     * 快照行数
     */
    private Integer rowCount;

    /**
     * 快照内容哈希（SHA-256，防篡改校验）
     */
    private String snapshotHash;

    /**
     * 零上报说明（submissionType=2 时填写）
     */
    private String emptyReason;

    /**
     * 冻结草稿时的工作项 serverVersion
     */
    private Long serverVersionAtSubmit;

    /**
     * 实际提交人编号（同时是 BPM 流程发起人）
     */
    private Long submitterUserId;

    /**
     * 提交时间
     */
    private LocalDateTime submitTime;

    /**
     * BPM 流程实例编号
     */
    private String processInstanceId;

    /**
     * 审批状态镜像：0-待启动 1-审批中 2-通过 3-驳回 4-已撤回 5-已终止，见 FillApprovalStatusEnum
     */
    private Integer approvalStatus;

    /**
     * 审批结束时间
     */
    private LocalDateTime approvalEndTime;

    /**
     * 审批结果意见（详细意见在 BPM 流程历史）
     */
    private String approvalReason;

}
