package cn.iocoder.yudao.module.dataforge.controller.admin.access.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
@Schema(description = "管理后台 - ACCESS 持久运行命令")
public class AccessRunCommandReqVO {

    @NotNull
    @Positive
    @Schema(description = "任务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long taskId;

    @NotBlank
    @Size(max = 64)
    @Schema(description = "客户端稳定请求编号，重试须保持不变", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;
}
