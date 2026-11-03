package cn.iocoder.yudao.module.governance.api.etl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateInlineQualityWorkOrderReqDTO {

    @NotNull
    private Long reportId;

    @NotBlank
    @Size(max = 256)
    private String orderName;

    @Size(max = 1024)
    private String description;

    private LocalDateTime deadline;
}
