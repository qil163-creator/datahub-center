package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 在线填报-任务汇总 Response VO：各组织的报送状态与数值字段合计（只统计审批通过的数据）
 *
 * @author Li
 */
@Schema(description = "管理后台 - 任务汇总 Response VO")
@Data
public class FillTaskSummaryRespVO {

    @Schema(description = "任务编号", example = "1")
    private Long taskId;

    @Schema(description = "任务名称", example = "月报（2026-10）")
    private String taskName;

    @Schema(description = "所属期", example = "2026-10")
    private String periodKey;

    @Schema(description = "参与合计的数值字段")
    private List<Field> fields;

    @Schema(description = "各组织汇总")
    private List<OrgRow> orgs;

    @Schema(description = "全部审批通过的顶层组织合计：fieldCode → 十进制字符串（下级已含在上级中，不重复计算）")
    private Map<String, String> totals;

    @Schema(description = "已审批通过的顶层组织数", example = "8")
    private Integer approvedCount;

    @Schema(description = "未报组织数（待填报、填报中、整改中、已取消）", example = "2")
    private Integer unreportedCount;

    @Schema(description = "管理后台 - 汇总字段")
    @Data
    public static class Field {

        @Schema(description = "字段码", example = "amount")
        private String fieldCode;

        @Schema(description = "字段名称", example = "金额")
        private String name;
    }

    @Schema(description = "管理后台 - 组织汇总行")
    @Data
    public static class OrgRow {

        @Schema(description = "组织实例编号", example = "11")
        private Long workItemId;

        @Schema(description = "组织名称", example = "甲单位")
        private String orgName;

        @Schema(description = "工作项状态", example = "9")
        private Integer status;

        @Schema(description = "是否逾期：0-否 1-是", example = "0")
        private Integer overdue;

        @Schema(description = "是否已审批通过", example = "true")
        private Boolean approved;

        @Schema(description = "审批通过数据的行数", example = "12")
        private Integer rowCount;

        @Schema(description = "提交时间")
        private LocalDateTime submitTime;

        @Schema(description = "数值字段合计：fieldCode → 十进制字符串（中间单位含其下级汇入的数据）")
        private Map<String, String> totals;

        @Schema(description = "上级实例编号；空表示顶层", example = "11")
        private Long parentWorkItemId;

        @Schema(description = "层级：顶层为 0", example = "0")
        private Integer level;
    }

}
