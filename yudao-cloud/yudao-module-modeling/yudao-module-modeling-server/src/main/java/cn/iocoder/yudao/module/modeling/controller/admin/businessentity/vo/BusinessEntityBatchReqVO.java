package cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class BusinessEntityBatchReqVO {

    @NotEmpty
    @Size(max = 100)
    private List<@NotNull @Valid Target> targets;

    @Min(0)
    @Max(1)
    private Integer status;

    @Data
    public static class Target {

        @NotNull
        @Positive
        private Long id;

        @NotNull
        @PositiveOrZero
        private Long expectedRevision;
    }
}
