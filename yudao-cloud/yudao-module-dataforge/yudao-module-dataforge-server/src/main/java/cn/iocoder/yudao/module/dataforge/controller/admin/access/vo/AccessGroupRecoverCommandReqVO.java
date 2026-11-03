package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - 恢复中断的数据组；上一执行器退出证据由服务端核验")
public class AccessGroupRecoverCommandReqVO {

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
    @Size(max = 500)
    @Schema(description = "恢复原因；不得包含凭据", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;

    @NotBlank
    @Size(max = 1000)
    @Schema(description = "核查证据引用，不传凭据或原始数据", requiredMode = Schema.RequiredMode.REQUIRED)
    private String evidenceRef;
}
