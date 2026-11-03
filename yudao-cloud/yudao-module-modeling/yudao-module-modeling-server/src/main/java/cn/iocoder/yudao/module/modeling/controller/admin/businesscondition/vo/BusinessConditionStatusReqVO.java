package cn.iocoder.yudao.module.modeling.controller.admin.businesscondition.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class BusinessConditionStatusReqVO {

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
