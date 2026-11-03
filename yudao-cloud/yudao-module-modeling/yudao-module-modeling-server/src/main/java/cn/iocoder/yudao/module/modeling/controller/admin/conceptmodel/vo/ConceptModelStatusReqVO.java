package cn.iocoder.yudao.module.modeling.controller.admin.conceptmodel.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 启用或停用概念模型请求")
public class ConceptModelStatusReqVO {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @PositiveOrZero
    private Long expectedRevision;

    @NotBlank
    @Pattern(regexp = "DRAFT|ACTIVE")
    private String status;
}
