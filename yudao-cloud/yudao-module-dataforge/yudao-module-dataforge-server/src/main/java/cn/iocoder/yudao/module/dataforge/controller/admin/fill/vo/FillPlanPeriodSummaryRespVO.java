package cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 在线填报-计划期次汇总 Response VO：期次 × 组织的报送状态矩阵
 *
 * @author Li
 */
@Schema(description = "管理后台 - 计划期次汇总 Response VO")
@Data
public class FillPlanPeriodSummaryRespVO {

    @Schema(description = "期次（按所属期倒序）")
    private List<Period> periods;

    @Schema(description = "组织行")
    private List<OrgRow> orgs;

    @Schema(description = "管理后台 - 期次")
    @Data
    public static class Period {

        @Schema(description = "任务编号", example = "1")
        private Long taskId;

        @Schema(description = "所属期", example = "2026-10")
        private String periodKey;

        @Schema(description = "任务状态", example = "1")
        private Integer status;

        @Schema(description = "已合并组织数", example = "8")
        private Integer mergedCount;

        @Schema(description = "应填组织数", example = "10")
        private Integer totalCount;
    }

    @Schema(description = "管理后台 - 组织行")
    @Data
    public static class OrgRow {

        @Schema(description = "报送组织编号", example = "100")
        private Long reportingOrgId;

        @Schema(description = "组织名称", example = "甲单位")
        private String orgName;

        @Schema(description = "各期状态：taskId → 工作项状态；不在该期名单内时无此项")
        private Map<Long, Integer> statuses;

        @Schema(description = "各期是否逾期：taskId → 0/1")
        private Map<Long, Integer> overdue;
    }

}
