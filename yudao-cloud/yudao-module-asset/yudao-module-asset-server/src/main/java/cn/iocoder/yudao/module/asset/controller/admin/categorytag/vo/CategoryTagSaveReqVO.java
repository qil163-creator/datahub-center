package cn.iocoder.yudao.module.asset.controller.admin.categorytag.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 标签类目新增/修改 Request VO")
@Data
public class CategoryTagSaveReqVO {

    private Long id;

    @Schema(description = "父级编号，顶级为 0", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "父级编号不能为空")
    private Long parentId;

    @NotBlank(message = "类目名称不能为空")
    @Size(max = 128, message = "类目名称不能超过 128 个字符")
    private String categoryName;

    @NotBlank(message = "类目编码不能为空")
    @Size(max = 128, message = "类目编码不能超过 128 个字符")
    private String categoryCode;

    @NotNull(message = "排序不能为空")
    private Integer sort;

    @NotNull(message = "状态不能为空")
    private Integer status;

    private String remark;

}
