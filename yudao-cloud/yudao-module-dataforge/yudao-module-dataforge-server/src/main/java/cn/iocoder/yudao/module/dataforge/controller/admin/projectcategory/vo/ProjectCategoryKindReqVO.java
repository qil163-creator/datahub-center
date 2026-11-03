package cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 项目类目种类请求")
public class ProjectCategoryKindReqVO {

    @NotNull
    @Pattern(regexp = "integration|development")
    @Schema(description = "类目种类", requiredMode = Schema.RequiredMode.REQUIRED)
    private String kind;
}
