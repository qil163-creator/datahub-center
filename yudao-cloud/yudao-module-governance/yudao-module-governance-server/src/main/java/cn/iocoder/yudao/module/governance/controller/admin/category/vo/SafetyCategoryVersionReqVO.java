package cn.iocoder.yudao.module.governance.controller.admin.category.vo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class SafetyCategoryVersionReqVO {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @Positive
    private Long revision;
}
