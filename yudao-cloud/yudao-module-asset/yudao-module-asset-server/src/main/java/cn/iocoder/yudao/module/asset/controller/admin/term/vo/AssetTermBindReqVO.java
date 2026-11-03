package cn.iocoder.yudao.module.asset.controller.admin.term.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 资产字段绑定业务术语 Request VO")
@Data
public class AssetTermBindReqVO {

    @NotNull(message = "资产编号不能为空")
    @Positive
    private Long assetId;

    @NotNull(message = "字段元数据编号不能为空")
    @Positive
    private Long metaColumnId;

    @Schema(description = "治理业务术语编号；为空表示解除绑定")
    @Positive
    private Long termId;

    @Size(max = 500)
    private String remark;

}
