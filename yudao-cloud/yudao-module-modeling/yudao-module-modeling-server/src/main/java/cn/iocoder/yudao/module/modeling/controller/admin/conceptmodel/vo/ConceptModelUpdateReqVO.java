package cn.iocoder.yudao.module.modeling.controller.admin.conceptmodel.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "管理后台 - 修改概念模型请求")
public class ConceptModelUpdateReqVO extends ConceptModelSaveReqVO {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @PositiveOrZero
    private Long expectedRevision;
}
