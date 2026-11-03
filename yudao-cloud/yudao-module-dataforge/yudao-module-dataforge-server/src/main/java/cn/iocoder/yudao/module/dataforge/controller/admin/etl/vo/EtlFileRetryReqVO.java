package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 文件回执重新对账，仅重试核验，不能授权覆盖或盲删")
public class EtlFileRetryReqVO {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @PositiveOrZero
    private Long expectedRevision;

    @NotBlank
    @Size(max = 500)
    private String reason;
}
