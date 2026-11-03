package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class EtlReplayRetryReqVO {

    @NotNull
    @Positive
    private Long recordId;

    @NotBlank
    @Size(max = 1000)
    private String reason;
}
