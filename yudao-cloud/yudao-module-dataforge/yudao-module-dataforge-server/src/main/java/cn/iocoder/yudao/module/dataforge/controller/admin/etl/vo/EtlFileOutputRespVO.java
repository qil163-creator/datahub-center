package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "管理后台 - 本人文件输出回执，不暴露本地路径、锁或文件系统身份")
public class EtlFileOutputRespVO {

    private Long id;

    private Long recordId;

    private String nodeNo;

    private String storageKey;

    private String fileName;

    private String state;

    private String recoveryStatus;

    private Integer attempts;

    private Long revision;

    private Long payloadBytes;

    private String payloadHash;

    private String lastErrorCode;

    private LocalDateTime nextAttemptAt;

    private LocalDateTime createTime;

    private String retentionStatus;

    private LocalDateTime retireAfter;

    private Long retirementRequestedBy;

    private String retirementReason;

    private String purgePhase;
}
