package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - ACCESS 人工核查命令")
public class AccessResolveCommandReqVO {

    @NotNull
    @Positive
    @Schema(description = "运行记录编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long recordId;

    @NotBlank
    @Size(max = 64)
    @Schema(description = "客户端稳定请求编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;

    @NotNull
    @PositiveOrZero
    @Schema(description = "查询时的行版本", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long expectedVersion;

    @NotBlank
    @Pattern(regexp = "SUCCESS|FAILED|STOPPED")
    @Schema(description = "经证据确认的最终状态", requiredMode = Schema.RequiredMode.REQUIRED)
    private String outcome;

    @NotNull
    @AssertTrue
    @Schema(description = "已核实工作线程、远端作业及物理资源停止", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean confirmedStopped;

    @NotNull
    @AssertTrue
    @Schema(description = "已核实或处置部分输出", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean outputReviewed;

    @NotBlank
    @Size(max = 500)
    @Schema(description = "核查原因；不得包含凭据", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;

    @NotBlank
    @Size(max = 1000)
    @Schema(description = "证据引用，不传凭据或原始数据", requiredMode = Schema.RequiredMode.REQUIRED)
    private String evidenceRef;
}
