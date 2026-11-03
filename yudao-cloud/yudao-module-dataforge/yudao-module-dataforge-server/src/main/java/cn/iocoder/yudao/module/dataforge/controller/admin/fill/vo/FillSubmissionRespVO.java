package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线填报-提交记录 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 提交记录 Response VO")
@Data
public class FillSubmissionRespVO {

    @Schema(description = "提交快照编号", example = "1")
    private Long id;

    @Schema(description = "工作项编号", example = "1")
    private Long workItemId;

    @Schema(description = "任务编号", example = "1")
    private Long taskId;

    @Schema(description = "提交次数", example = "1")
    private Integer attemptNo;

    @Schema(description = "类型：1-正常 2-零上报 3-更正", example = "1")
    private Integer submissionType;

    @Schema(description = "模板版本编号", example = "1")
    private Long tableVersionId;

    @Schema(description = "快照行数", example = "120")
    private Integer rowCount;

    @Schema(description = "快照内容哈希", example = "a1b2c3...")
    private String snapshotHash;

    @Schema(description = "实际提交人编号", example = "1001")
    private Long submitterUserId;

    @Schema(description = "提交时间")
    private LocalDateTime submitTime;

    @Schema(description = "BPM 流程实例编号")
    private String processInstanceId;

    @Schema(description = "审批状态：0-待启动 1-审批中 2-通过 3-驳回 4-已撤回 5-已终止", example = "1")
    private Integer approvalStatus;

    @Schema(description = "审批结束时间")
    private LocalDateTime approvalEndTime;

    @Schema(description = "审批结果意见")
    private String approvalReason;

    @Schema(description = "零上报说明（submissionType=2 时填写）", example = "本期无新增项目")
    private String emptyReason;

}
