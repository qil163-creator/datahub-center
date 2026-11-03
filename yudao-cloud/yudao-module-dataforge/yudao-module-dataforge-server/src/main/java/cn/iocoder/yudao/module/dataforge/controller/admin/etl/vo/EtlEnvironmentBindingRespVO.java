package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "管理后台 - ETL 环境连接绑定响应，不含凭据")
public class EtlEnvironmentBindingRespVO {

    private Long id;

    private Long environmentId;

    private String logicalKey;

    private Long databaseId;

    private String schemaName;

    private Integer revision;
}
