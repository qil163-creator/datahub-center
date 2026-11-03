package cn.iocoder.yudao.module.asset.controller.admin.assettag.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Schema(description = "管理后台 - 资产标签绑定 Request VO")
@Data
public class AssetTagBindReqVO {

    @NotNull(message = "资产编号不能为空")
    private Long assetId;

    @NotNull
    @Min(0)
    private Long catalogRevision;

    @Schema(description = "标签编号列表；空列表表示清空标签")
    @NotNull(message = "标签列表不能为 null")
    @Size(max = 200)
    private List<@NotNull @Positive Long> tagIds;

}
