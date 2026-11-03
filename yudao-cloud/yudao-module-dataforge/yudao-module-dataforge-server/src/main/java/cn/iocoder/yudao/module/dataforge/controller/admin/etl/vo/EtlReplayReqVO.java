package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class EtlReplayReqVO {

    @NotNull
    @Positive
    private Long workOrderId;

    @NotNull
    @Positive
    private Long submissionId;

    @NotBlank
    @Size(max = 64)
    private String requestId;

    @NotBlank
    @Pattern(regexp = "ORIGINAL|LATEST_PUBLISHED")
    private String snapshotMode;

    @NotBlank
    @Size(max = 1000)
    private String reason;

    @NotNull
    @AssertTrue
    private Boolean confirmedTargetReplacement;
}
