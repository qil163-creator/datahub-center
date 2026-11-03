package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Schema(description = "管理后台 - 当前用户可见的质量工单统计 Response VO")
@Data
public class QualityWorkOrderStatisticsRespVO {

    private String scope = "authorized";

    private Integer window;

    private LocalDateTime asOf;

    private String sampleStatus;

    private String countUnit = "WORK_ORDER";

    @Schema(description = "窗口内创建、截至统计时点可见的工单数，所有汇总使用同一集合")
    private Integer totalCount;

    private List<Trend> trend;

    private Map<String, Integer> statusSummary;

    private List<OwnerSummary> ownerSummary;

    private Integer currentOverdue;

    private Integer closeVerifiedPass;

    private Integer closeExplained;

    private Integer closeException;

    private Integer closeLegacy;

    private String coverage = "按窗口内创建的可见工单统计；责任人按当前归属，未认责单独分组；不等于规则通过率";

    public record Trend(String date, int createdCount, int processedCount) {

    }

    @Schema(description = "按责任人 ID 聚合，不合并同名人员；未认责组 ID 为空")
    public record OwnerSummary(Long ownerUserId, String ownerUserName, int count) {

    }
}
