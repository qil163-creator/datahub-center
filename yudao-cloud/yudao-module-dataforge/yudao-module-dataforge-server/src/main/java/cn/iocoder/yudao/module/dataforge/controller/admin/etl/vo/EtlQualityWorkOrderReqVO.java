package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EtlQualityWorkOrderReqVO {

    @NotNull
    private Long batchId;

    @NotBlank
    @Size(max = 256)
    private String orderName;

    @Size(max = 1024)
    private String description;

    private LocalDateTime deadline;
}
