package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 确认需求匹配")
@Data
public class MarketDemandMatchConfirmReqVO {

    @NotNull
    private Long id;

    @NotNull
    @Schema(description = "1-接受 2-拒绝")
    private Integer status;

    @Size(max = 500)
    private String confirmNote;

    @Schema(description = "接受后是否自动生成共享申请")
    private Boolean createApply;

}
