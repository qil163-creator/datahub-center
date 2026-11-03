package cn.iocoder.yudao.module.asset.controller.admin.market.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 资源评价保存")
@Data
public class MarketCommentSaveReqVO {

    private Long id;

    @NotNull(message = "资产不能为空")
    private Long assetId;

    private Long applyId;

    @NotBlank(message = "评价内容不能为空")
    @Size(max = 500)
    private String commentStr;

    @Min(1)
    @Max(5)
    private Integer level;

    @Schema(description = "1普通交流 2结构化校核")
    private Integer commentKind;

    @Size(max = 64)
    private String problemType;

    @Size(max = 256)
    private String impactScope;

    private java.time.LocalDateTime foundTime;

    @Size(max = 64)
    private String idempotentKey;

}
