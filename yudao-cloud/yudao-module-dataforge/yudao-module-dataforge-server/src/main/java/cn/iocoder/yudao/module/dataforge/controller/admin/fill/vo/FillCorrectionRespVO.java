package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线填报-数据更正 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 数据更正 Response VO")
@Data
public class FillCorrectionRespVO {

    @Schema(description = "更正实例编号", example = "1")
    private Long id;

    @Schema(description = "原提交快照编号", example = "10")
    private Long originSubmissionId;

    @Schema(description = "工作项编号", example = "1")
    private Long workItemId;

    @Schema(description = "组织名称", example = "市住建局")
    private String orgName;

    @Schema(description = "任务编号", example = "1")
    private Long taskId;

    @Schema(description = "任务名称", example = "民生项目月报（2026-08）")
    private String taskName;

    @Schema(description = "更正原因")
    private String reason;

    @Schema(description = "更正生成的新提交快照编号", example = "11")
    private Long correctionSubmissionId;

    @Schema(description = "状态：0-草拟 1-审批中 2-已合并 3-已取消", example = "0")
    private Integer status;

    @Schema(description = "发起人", example = "admin")
    private String creator;

    @Schema(description = "发起时间")
    private LocalDateTime createTime;

}
