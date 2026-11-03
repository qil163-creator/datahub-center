package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 需求响应")
@Data
public class MarketDemandReplySaveReqVO {

    @NotNull
    private Long demandId;

    @NotNull
    private Integer replyType;

    @NotBlank
    @Size(max = 1000)
    private String content;

    private Long replyDeptId;

}
