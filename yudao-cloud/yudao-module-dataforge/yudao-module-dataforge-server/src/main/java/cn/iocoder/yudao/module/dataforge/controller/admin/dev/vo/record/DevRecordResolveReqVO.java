package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class DevRecordResolveReqVO {

    @NotNull
    private Long id;

    @NotNull
    @AssertTrue
    private Boolean confirmedTerminated;

    @NotBlank
    @Pattern(regexp = "UNKNOWN")
    private String expectedStatus;

    @NotBlank
    @Pattern(regexp = "SUCCESS|FAILED|STOPPED")
    private String status;

    @NotBlank
    @Size(max = 1000)
    private String reason;
}
