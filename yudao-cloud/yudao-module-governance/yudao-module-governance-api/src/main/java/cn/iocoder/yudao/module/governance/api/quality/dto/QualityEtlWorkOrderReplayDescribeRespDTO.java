package cn.iocoder.yudao.module.governance.api.quality.dto;

import lombok.Data;

import java.util.List;

@Data
public class QualityEtlWorkOrderReplayDescribeRespDTO {

    private Long workOrderId;

    private Long submissionId;

    private Long sourceReportId;

    private String runId;

    private String qualityNodeId;

    private String qualityBatchId;

    private Long totalCount;

    private Long passCount;

    private Long warnCount;

    private Long rejectCount;

    private List<String> identityFields;

    private String identityHash;

    private List<OriginalTarget> originalTargets;

    @Data
    public static class OriginalTarget {

        /** DataForge 必须替换的当前已提交最新批次，不一定是原始批次。 */
        private Long targetBatchId;

        private Long recordId;

        /** 不可变的首次运行目标标识，用于证明多次重试均覆盖完整数据集合。 */
        private Long rootTargetBatchId;

        private Long rootRecordId;

        private String sinkNodeId;

        private Long qualityTaskId;
    }
}
