package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EtlReplayStatusRespVO {

    private Long recordId;

    private Long sourceRecordId;

    private Long workOrderId;

    private Long submissionId;

    private String snapshotMode;

    private String attachStatus;

    private Integer attemptCount;

    private LocalDateTime nextAttemptAt;

    private String lastError;
}
