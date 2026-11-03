package cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理后台 - 项目类目状态请求")
public class ProjectCategoryStatusReqVO extends ProjectCategoryKindReqVO {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer status;
}
