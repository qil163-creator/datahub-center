package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EtlVersionRespVO {

    private Long id;

    private Long pipelineId;

    private Integer revision;

    private Long releaseGeneration;

    private String eventType;

    private Long sourceVersionId;

    private Long actorId;

    private String reason;

    private String contentHash;

    /** 分页不返回快照；详情保留发布时规则和映射计划。 */
    private String configJson;

    private LocalDateTime createTime;
}
