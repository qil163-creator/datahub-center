package cn.iocoder.yudao.module.supply.controller.admin.app.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - ETL 授权审计游标查询")
public class SupplyEtlGrantAuditPageReqVO {

    @NotNull
    @Positive
    private Long appId;

    @Positive
    private Long grantId;

    @Positive
    private Long beforeId;

    @NotNull
    @Min(1)
    @Max(50)
    private Integer limit = 20;
}
