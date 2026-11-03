package cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理后台 - 项目类目新增或修改请求")
public class ProjectCategorySaveReqVO extends ProjectCategoryKindReqVO {

    @Positive
    private Long id;

    @NotNull
    @Min(0)
    private Long parentId;

    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = ".*[A-Za-z0-9一-龥].*", message = "类目名称至少包含一个汉字、字母或数字")
    private String name;

    @Size(max = 256)
    private String description;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer status = 0;

    @NotNull
    @Min(0)
    @Max(100000)
    private Integer sort = 0;
}
