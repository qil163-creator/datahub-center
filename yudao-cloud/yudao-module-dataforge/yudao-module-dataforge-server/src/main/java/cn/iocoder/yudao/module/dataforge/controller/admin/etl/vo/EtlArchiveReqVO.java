package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class EtlArchiveReqVO {

    @NotNull
    @Positive
    private Long recordId;

    @NotBlank
    @Size(max = 1000)
    private String reason;

    @NotNull
    @Min(30)
    @Max(3650)
    private Integer retentionDays;
}
