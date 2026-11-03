package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 在线填报-组织填报实例（工作项）Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 组织填报实例 Response VO")
@Data
public class FillWorkItemRespVO {

    @Schema(description = "工作项编号", example = "1")
    private Long id;

    @Schema(description = "任务编号", example = "1")
    private Long taskId;

    @Schema(description = "任务名称", example = "民生项目月报（2026-08）")
    private String taskName;

    @Schema(description = "计划编号", example = "1")
    private Long planId;

    @Schema(description = "模板版本编号", example = "1")
    private Long tableVersionId;

    @Schema(description = "数据所属期", example = "2026-08")
    private String periodKey;

    @Schema(description = "报送组织编号", example = "101")
    private Long reportingOrgId;

    @Schema(description = "组织名称", example = "市住建局")
    private String orgName;

    @Schema(description = "主状态：0-待填报 1-填报中 2-提交校验中 3-待启动审批 4-审批中 5-整改中 6-待合并 7-合并中 8-合并失败 9-已合并 10-已取消", example = "1")
    private Integer status;

    @Schema(description = "逾期标识：0-未逾期 1-已逾期", example = "0")
    private Integer overdue;

    @Schema(description = "报送类型：1-正常 2-零上报", example = "1")
    private Integer submissionType;

    @Schema(description = "提交次数", example = "0")
    private Integer attemptCount;

    @Schema(description = "当前提交快照编号")
    private Long currentSubmissionId;

    @Schema(description = "草稿行数", example = "0")
    private Integer draftRowCount;

    @Schema(description = "截止时间")
    private LocalDateTime deadlineTime;

    @Schema(description = "最后修改人编号")
    private Long lastModifyUserId;

    @Schema(description = "最后修改时间")
    private LocalDateTime lastModifyTime;

    @Schema(description = "当前有效填报人")
    private List<FillAssigneeRespVO> assignees;

}
