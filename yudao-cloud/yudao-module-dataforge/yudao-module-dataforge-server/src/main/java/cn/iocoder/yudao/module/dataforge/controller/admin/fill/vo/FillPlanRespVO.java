package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 在线填报-采集计划 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 采集计划 Response VO")
@Data
public class FillPlanRespVO {

    @Schema(description = "计划编号", example = "1")
    private Long id;

    @Schema(description = "计划名称", example = "民生项目月报")
    private String name;

    @Schema(description = "计划分类编号，空值表示未分类", example = "1")
    private Long categoryId;

    @Schema(description = "计划分类名称，未分类时为空", example = "基础信息")
    private String categoryName;

    @Schema(description = "业务说明")
    private String description;

    @Schema(description = "填报表编号", example = "1")
    private Long tableId;

    @Schema(description = "填报表名称", example = "民生项目月报表")
    private String tableName;

    @Schema(description = "模板版本编号", example = "1")
    private Long tableVersionId;

    @Schema(description = "模板版本号", example = "1")
    private Integer tableVersionNo;

    @Schema(description = "周期类型：1-一次性 2-每日 3-每周 4-每月 5-每季度", example = "4")
    private Integer periodType;

    @Schema(description = "周期参数 JSON")
    private String periodConfig;

    @Schema(description = "时区", example = "Asia/Shanghai")
    private String timeZone;

    @Schema(description = "提醒规则 JSON")
    private String remindConfig;

    @Schema(description = "逾期策略：1-软逾期 2-硬截止", example = "1")
    private Integer overduePolicy;

    @Schema(description = "BPM 流程定义 Key", example = "dataforge_fill_approval")
    private String processDefinitionKey;

    @Schema(description = "任务发布审批开关", example = "0")
    private Integer publishApprovalEnabled;

    @Schema(description = "上期数据带入", example = "0")
    private Integer prefillLastPeriod;

    @Schema(description = "发起单位编号", example = "100")
    private Long ownerOrgId;

    @Schema(description = "状态：0-草稿 1-启用 2-停用", example = "1")
    private Integer status;

    @Schema(description = "最近生成任务的所属期", example = "2026-08")
    private String lastPeriodKey;

    @Schema(description = "下次生成时间")
    private LocalDateTime nextGenerateTime;

    @Schema(description = "创建人（任务发起者）")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "目标组织及填报人")
    private List<OrgRespItem> orgs;

    @Schema(description = "管理后台 - 采集计划目标组织项")
    @Data
    public static class OrgRespItem {

        @Schema(description = "报送组织编号", example = "101")
        private Long reportingOrgId;

        @Schema(description = "组织名称快照", example = "市住建局")
        private String orgName;

        @Schema(description = "显示顺序", example = "0")
        private Integer sort;

        @Schema(description = "填报人列表")
        private List<AssigneeItem> assignees;

    }

    @Schema(description = "管理后台 - 计划填报人项")
    @Data
    public static class AssigneeItem {

        @Schema(description = "用户编号", example = "1001")
        private Long userId;

        @Schema(description = "用户昵称快照", example = "李四")
        private String userName;

    }

}
