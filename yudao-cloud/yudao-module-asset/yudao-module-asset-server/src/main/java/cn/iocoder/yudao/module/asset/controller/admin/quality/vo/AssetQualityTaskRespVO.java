package cn.iocoder.yudao.module.asset.controller.admin.quality.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import cn.iocoder.yudao.module.governance.api.quality.dto.QualityAssetEvaluationDTO;

@Schema(description = "管理后台 - 资产质量任务 Response VO")
@Data
public class AssetQualityTaskRespVO {

    private Long id;

    private Long assetId;

    private Long qualityTaskId;

    private String taskName;

    private Integer scheduleType;

    private String cronExpression;

    private Integer enabled;

    private Integer runStatus;

    private LocalDateTime lastRunTime;

    private LocalDateTime lastFinishTime;

    private Long totalCount;

    private Long issueCount;

    private BigDecimal qualityScore;

    private Integer deletePending;

    private String remoteError;

    private Boolean pendingSync;

    private Boolean executionUnknown;

    @Schema(description = "最近一次质量结果是否可用；不可用时不能把分数当成 0")
    private Boolean resultAvailable;

    @Schema(description = "最近结果状态：NONE/UNAVAILABLE/RUNNING/SUCCESS/FAILED")
    private String resultStatus;

    private String remark;

    private LocalDateTime createTime;

    private List<Rule> rules;

    private List<QualityAssetEvaluationDTO> evaluations;

    @Data
    public static class Rule {

        private Long id;

        private Long taskId;

        private Long auditRuleId;

        private Long qualityRuleId;

        private String ruleName;

        private String dimensionName;

        private Long metaColumnId;

        private String columnName;

        private Long qualityEngineTaskId;

        private String remark;

    }

}
