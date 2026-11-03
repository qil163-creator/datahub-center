package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 关闭/驳回需求")
@Data
public class MarketDemandCloseReqVO {

    @NotNull
    private Long id;

    @NotNull
    @Schema(description = "4-关闭 5-撤销 6-驳回")
    private Integer status;

    @Size(max = 500)
    private String closeReason;

}
