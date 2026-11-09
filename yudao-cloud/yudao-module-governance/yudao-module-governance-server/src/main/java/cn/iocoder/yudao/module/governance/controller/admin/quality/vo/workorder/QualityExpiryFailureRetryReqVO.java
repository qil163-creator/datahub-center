package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "管理后台 - 例外到期处理失败授权重试 Request VO")
@Data
public class QualityExpiryFailureRetryReqVO {

    @Schema(description = "账本编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "账本编号不能为空")
    private Long id;

    @Schema(description = "读取时的账本版本", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
    @NotNull(message = "账本版本不能为空")
    private Integer expectedVersion;

    @Schema(description = "授权理由（排查结论）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "授权理由不能为空")
    @Size(min = 5, max = 500, message = "授权理由长度为 5-500 个字符")
    private String reason;
}
