package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "ETL 记录级幂等停止命令")
public class EtlStopCommandReqVO {

    @NotNull
    private Long recordId;

    @NotBlank
    @Size(max = 128)
    private String requestId;
}
