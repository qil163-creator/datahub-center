package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class EtlVersionRestoreReqVO {

    @NotNull
    private Long versionId;

    @NotNull
    @Min(0)
    private Integer expectedRevision;

    @NotBlank
    @Size(max = 500)
    private String reason;
}
