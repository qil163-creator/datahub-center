package cn.iocoder.yudao.module.governance.controller.admin.master.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class MasterPushCategoryStatusReqVO {
    @NotNull @Positive
    private Long id;
    @NotNull @Min(0) @Max(1)
    private Integer status;
}
