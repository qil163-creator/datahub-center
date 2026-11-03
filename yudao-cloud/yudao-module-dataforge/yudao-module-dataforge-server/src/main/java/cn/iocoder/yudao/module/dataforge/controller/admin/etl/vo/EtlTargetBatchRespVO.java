package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EtlTargetBatchRespVO {

    private Long id;

    private Long recordId;

    private String nodeNo;

    private Long qualityBatchId;

    private Long qualityTaskId;

    private Long qualityInstanceId;

    private String batchKey;

    private Long replayOfBatchId;

    private Long replayOfRecordId;

    private Long workOrderId;

    private Long workOrderSubmissionId;

    private Long replacedByBatchId;

    private Long inputCount;

    private Long outputCount;

    private Long admittedCount;

    private Long rejectedCount;

    private String commitStatus;

    private String qualityStatus;

    private Integer attemptCount;

    private LocalDateTime nextAttemptAt;

    private String lastError;

    private LocalDateTime createTime;
}
