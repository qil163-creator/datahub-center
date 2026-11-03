package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 资源评价回复")
@Data
public class MarketCommentReplyReqVO {

    @NotNull
    private Long id;

    @NotBlank
    @Size(max = 500)
    private String replyStr;

}
