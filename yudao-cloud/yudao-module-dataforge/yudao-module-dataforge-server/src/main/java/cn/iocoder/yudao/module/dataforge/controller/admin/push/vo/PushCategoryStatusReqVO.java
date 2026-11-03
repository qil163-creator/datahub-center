package cn.iocoder.yudao.module.dataforge.controller.admin.push.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class PushCategoryStatusReqVO {
    @NotNull @Positive
    private Long id;
    @NotNull @Min(0) @Max(1)
    private Integer status;
}
