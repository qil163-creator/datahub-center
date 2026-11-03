package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EtlArchiveRespVO {

    private Long recordId;

    private Long actorId;

    private String reason;

    private LocalDateTime archivedAt;

    private LocalDateTime evidenceAccessUntil;

    private LocalDateTime restoredAt;

    private Long restoredBy;
}
