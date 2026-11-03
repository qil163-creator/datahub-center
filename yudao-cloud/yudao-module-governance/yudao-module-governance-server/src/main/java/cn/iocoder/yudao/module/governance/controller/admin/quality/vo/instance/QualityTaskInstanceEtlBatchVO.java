package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 任务实例的 ETL 批次来源信息
 *
 * 仅暴露用于监控与跳转的标识和计数；冻结范围清单、业务键摘要、批次键等
 * 范围与业务键信息不随实例监控返回。
 *
 * @author Li
 */
@Schema(description = "管理后台 - 任务实例 ETL 批次来源 VO")
@Data
public class QualityTaskInstanceEtlBatchVO {

    @Schema(description = "本次 ETL 运行编号，用于跳转到 ETL 运行记录")
    private Long recordId;

    @Schema(description = "DataForge 目标批次编号")
    private Long targetBatchId;

    @Schema(description = "目标输出节点编号")
    private String sinkNodeId;

    @Schema(description = "内联质量节点编号")
    private String qualityNodeId;

    @Schema(description = "内联质量批次编号")
    private String qualityBatchId;

    @Schema(description = "对应的内联质量报告编号")
    private Long inlineReportId;

    @Schema(description = "该批次实际落库行数")
    private Long rowCount;

    @Schema(description = "该批次允许参与消费检测的准入行数")
    private Long admittedRowCount;

    @Schema(description = "是否允许警告行进入准入范围")
    private Boolean allowWarnings;

    @Schema(description = "批次检测受理状态，例如 QUEUED")
    private String state;

    @Schema(description = "本次受控替换的直接父批次编号，非回放时为空")
    private Long replayOfBatchId;

    @Schema(description = "直接父批次所属运行编号，非回放时为空")
    private Long replayOfRecordId;

    @Schema(description = "本次回放对应的治理整改提交编号，非回放时为空")
    private Long workOrderSubmissionId;

    @Schema(description = "来源运行稳定字符串标识")
    private String runId;

}
