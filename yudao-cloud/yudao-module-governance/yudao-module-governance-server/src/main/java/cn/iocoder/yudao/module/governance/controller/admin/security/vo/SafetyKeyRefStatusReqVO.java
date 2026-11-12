package cn.iocoder.yudao.module.governance.controller.admin.security.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 管理后台 - 启停密钥引用 Request VO。停用后引用它的加密规则失败关闭（返回不可逆掩码）。
 */
@Schema(description = "管理后台 - 启停密钥引用 Request VO")
@Data
public class SafetyKeyRefStatusReqVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "编号不能为空")
    @Positive(message = "编号必须为正数")
    private Long id;

    @Schema(description = "状态：0 启用、1 停用", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态不合法")
    @Max(value = 1, message = "状态不合法")
    private Integer status;
}
