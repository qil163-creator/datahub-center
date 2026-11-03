package cn.iocoder.yudao.module.supply.controller.admin.dataapi.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - API 发布快照")
@Data
public class DataApiReleaseRespVO {

    private Long id;

    private Long apiId;

    private Integer revision;

    private String apiVersion;

    private String configHash;

    private String contractHash;

    private String publisher;

    private LocalDateTime publishTime;

    private Boolean active;
}
