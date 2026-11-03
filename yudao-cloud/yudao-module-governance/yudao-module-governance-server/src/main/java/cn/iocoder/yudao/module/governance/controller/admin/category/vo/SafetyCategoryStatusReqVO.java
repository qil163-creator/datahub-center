package cn.iocoder.yudao.module.governance.controller.admin.category.vo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SafetyCategoryStatusReqVO extends SafetyCategoryVersionReqVO {

    @NotNull
    @Min(0)
    @Max(1)
    private Short status;
}
