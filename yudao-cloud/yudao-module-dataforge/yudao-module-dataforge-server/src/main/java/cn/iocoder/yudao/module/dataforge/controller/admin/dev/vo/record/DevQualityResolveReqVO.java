package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class DevQualityResolveReqVO {

    @NotNull
    private Long id;

    @NotBlank
    @Pattern(regexp = "UNKNOWN")
    private String expectedStatus;

    @NotNull
    @AssertTrue
    private Boolean confirmedTerminated;

    @NotBlank
    @Size(max = 1000)
    private String reason;
}
