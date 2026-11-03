package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class DevTaskRunReqVO {

    @NotNull
    private Long id;

    @NotNull
    private java.time.LocalDate businessDate;

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_-]{8,100}")
    private String requestId;
}
