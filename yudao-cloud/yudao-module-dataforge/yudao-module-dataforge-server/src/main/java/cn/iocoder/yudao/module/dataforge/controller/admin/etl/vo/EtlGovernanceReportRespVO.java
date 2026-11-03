package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class EtlGovernanceReportRespVO {

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

    private LocalDateTime createTime;
}
