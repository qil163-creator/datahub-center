package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

/**
 * 在线填报-采集计划保存 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 采集计划保存 Request VO")
@Data
public class FillPlanSaveReqVO {

    @Schema(description = "计划编号（更新时必填）", example = "1")
    private Long id;

    @Schema(description = "计划名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "民生项目月报")
    @NotEmpty(message = "计划名称不能为空")
    private String name;

    @Schema(description = "计划分类编号，空值表示未分类", example = "1")
    @Positive(message = "计划分类编号必须为正整数")
    private Long categoryId;

    @Schema(description = "业务说明", example = "各区县每月报送民生项目进展")
    private String description;

    @Schema(description = "填报表编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "填报表编号不能为空")
    private Long tableId;

    @Schema(description = "模板版本编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "模板版本编号不能为空")
    private Long tableVersionId;

    @Schema(description = "周期类型：1-一次性 2-每日 3-每周 4-每月 5-每季度", requiredMode = Schema.RequiredMode.REQUIRED, example = "4")
    @NotNull(message = "周期类型不能为空")
    private Integer periodType;

    @Schema(description = "周期参数 JSON（FillPeriodConfig 结构）", example = "{\"monthGenerateDay\":1}")
    private String periodConfig;

    @Schema(description = "时区", example = "Asia/Shanghai")
    private String timeZone;

    @Schema(description = "提醒规则 JSON")
    private String remindConfig;

    @Schema(description = "逾期策略：1-软逾期 2-硬截止", example = "1")
    private Integer overduePolicy;

    @Schema(description = "BPM 流程定义 Key", requiredMode = Schema.RequiredMode.REQUIRED, example = "dataforge_fill_approval")
    @NotEmpty(message = "BPM 流程定义 Key 不能为空")
    private String processDefinitionKey;

    @Schema(description = "任务发布审批开关：0-关 1-开", example = "0")
    private Integer publishApprovalEnabled;

    @Schema(description = "上期数据带入：0-关 1-开", example = "0")
    private Integer prefillLastPeriod;

    @Schema(description = "发起单位编号", example = "100")
    private Long ownerOrgId;

    @Schema(description = "目标组织及各组织填报人")
    @Valid
    private List<OrgItem> orgs;

    @Schema(description = "管理后台 - 采集计划目标组织项")
    @Data
    public static class OrgItem {

        @Schema(description = "报送组织编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "101")
        @NotNull(message = "报送组织不能为空")
        private Long reportingOrgId;

        @Schema(description = "显示顺序", example = "0")
        private Integer sort;

        @Schema(description = "填报人用户编号列表", example = "[1001,1002]")
        private List<Long> assigneeUserIds;

    }

}
