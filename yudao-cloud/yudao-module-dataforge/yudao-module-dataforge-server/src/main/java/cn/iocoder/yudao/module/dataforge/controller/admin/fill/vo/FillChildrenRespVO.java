package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 在线填报-下级单位与汇总状态 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 下级单位与汇总状态 Response VO")
@Data
public class FillChildrenRespVO {

    @Schema(description = "直接下级实例")
    private List<Child> children;

    @Schema(description = "汇总状态")
    private RollupState rollup;

    @Schema(description = "管理后台 - 下级实例")
    @Data
    public static class Child {

        @Schema(description = "实例编号", example = "21")
        private Long workItemId;

        @Schema(description = "报送组织编号", example = "103")
        private Long reportingOrgId;

        @Schema(description = "组织名称", example = "下属一科")
        private String orgName;

        @Schema(description = "层级", example = "1")
        private Integer level;

        @Schema(description = "实例状态", example = "4")
        private Integer status;

        @Schema(description = "是否逾期：0-否 1-是", example = "0")
        private Integer overdue;

        @Schema(description = "截止时间")
        private LocalDateTime deadlineTime;

        @Schema(description = "草稿行数", example = "12")
        private Integer draftRowCount;

        @Schema(description = "当前提交编号", example = "71")
        private Long currentSubmissionId;

        @Schema(description = "当前提交的审批状态", example = "1")
        private Integer approvalStatus;

        @Schema(description = "当前提交的提交时间")
        private LocalDateTime submitTime;

        @Schema(description = "当前提交是否等待本单位审核", example = "true")
        private Boolean awaitingReview;

        @Schema(description = "当前审核通过的数据是否已纳入本单位最近一次汇总", example = "true")
        private Boolean included;

        @Schema(description = "该下级再往下下发的实例数", example = "0")
        private Integer childCount;

        @Schema(description = "当前有效填报人")
        private List<FillAssigneeRespVO> assignees;
    }

    @Schema(description = "管理后台 - 汇总状态")
    @Data
    public static class RollupState {

        @Schema(description = "汇总方式：DETAIL 明细汇集 / GROUP 分组合计", example = "DETAIL")
        private String mode;

        @Schema(description = "未取消的下级数", example = "5")
        private Integer totalCount;

        @Schema(description = "已审核通过（已汇入上级）的下级数", example = "3")
        private Integer rolledUpCount;

        @Schema(description = "等待本单位审核的下级数", example = "1")
        private Integer reviewingCount;

        @Schema(description = "尚未审核通过的下级数（含等待审核）", example = "2")
        private Integer unfinishedCount;

        @Schema(description = "汇总是否已过期：下级数据变化后尚未重新汇总", example = "false")
        private Boolean stale;

        @Schema(description = "最近一次汇总时间")
        private LocalDateTime lastRolledAt;

        @Schema(description = "现在能否上报；不能时 blockReason 说明原因", example = "false")
        private Boolean readyToSubmit;

        @Schema(description = "不能上报的原因")
        private String blockReason;
    }

}
