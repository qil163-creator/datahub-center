package cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class BusinessEntityStatusReqVO {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @PositiveOrZero
    private Long expectedRevision;

    @NotNull
    @Min(0)
    @Max(1)
    private Integer status;
}
