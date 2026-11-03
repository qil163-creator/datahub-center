package cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class BusinessEntityUpdateReqVO extends BusinessEntitySaveReqVO {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @PositiveOrZero
    private Long expectedRevision;
}
