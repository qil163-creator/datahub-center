package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "管理后台 - ETL 环境响应")
public class EtlEnvironmentRespVO {

    private Long id;

    private String code;

    private String name;

    private Boolean enabled;

    private Integer revision;
}
