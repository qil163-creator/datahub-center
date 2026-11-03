package cn.iocoder.yudao.module.governance.api.etl.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class InlineQualityReportDTO {

    private Long reportId;

    private Long workOrderId;

    private String runId;

    private String nodeId;

    private String batchId;

    private String sourceType;

    private String planHash;

    private Long totalCount;

    private Long passCount;

    private Long warnCount;

    private Long rejectCount;

    private List<String> issueReferences;

    private List<String> identityFields;

    private String identityHash;

    private LocalDateTime createTime;
}
