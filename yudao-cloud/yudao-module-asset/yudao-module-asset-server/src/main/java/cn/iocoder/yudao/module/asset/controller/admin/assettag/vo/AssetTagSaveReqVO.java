package cn.iocoder.yudao.module.asset.controller.admin.assettag.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Schema(description = "管理后台 - 资产标签新增/修改 Request VO")
@Data
public class AssetTagSaveReqVO {

    @Positive
    private Long id;

    @Min(0)
    private Integer revision;

    @Schema(description = "标签类目编号")
    @NotNull
    @Positive
    private Long categoryId;

    @NotBlank(message = "标签名称不能为空")
    @Size(max = 50, message = "标签名称不能超过 50 个字符")
    private String name;

    @Size(max = 256, message = "近义词不能超过 256 个字符")
    private String nearSynonyms;

    @Size(max = 256, message = "同义词不能超过 256 个字符")
    private String synonyms;

    @Size(max = 20, message = "标签颜色不能超过 20 个字符")
    private String color;

    @Min(0)
    @Max(1)
    private Integer status = 0;

    @Size(max = 256, message = "描述不能超过 256 个字符")
    private String description;

    @Size(max = 512, message = "备注不能超过 512 个字符")
    private String remark;

}
