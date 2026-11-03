package cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "ETL 人工核查命令；不得用停止受理代替实际退出证据")
public class EtlResolveCommandReqVO {

    @NotNull
    private Long recordId;

    @NotBlank
    @Size(max = 128)
    private String requestId;

    @NotNull
    @PositiveOrZero
    private Long expectedVersion;

    @NotNull
    @AssertTrue
    private Boolean confirmedStopped;

    @NotNull
    @AssertTrue
    private Boolean outputReviewed;

    @NotBlank
    @Pattern(regexp = "SUCCESS|FAILED|STOPPED")
    private String outcome;

    @NotBlank
    @Size(max = 500)
    private String reason;

    @NotBlank
    @Size(max = 500)
    private String evidenceRef;
}
