package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - ACCESS 记录级停止命令；受理不等于资源已退出")
public class AccessStopCommandReqVO {

    @NotNull
    @Positive
    @Schema(description = "运行记录编号（不是任务编号）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long recordId;

    @NotBlank
    @Size(max = 64)
    @Schema(description = "客户端稳定请求编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;

    @Size(max = 500)
    @Schema(description = "停止原因；不得包含凭据")
    private String reason;
}
