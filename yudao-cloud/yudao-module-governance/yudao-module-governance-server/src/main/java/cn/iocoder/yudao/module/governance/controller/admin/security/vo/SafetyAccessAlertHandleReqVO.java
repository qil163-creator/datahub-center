package cn.iocoder.yudao.module.governance.controller.admin.security.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - 处理异常访问告警 Request VO
 */
@Schema(description = "管理后台 - 处理异常访问告警 Request VO")
@Data
public class SafetyAccessAlertHandleReqVO {

    @Schema(description = "编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "编号不能为空")
    @Positive(message = "编号必须为正数")
    private Long id;

    @Schema(description = "处理结果：CONFIRMED 确认风险、IGNORED 忽略误报", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "处理结果不能为空")
    @Pattern(regexp = "CONFIRMED|IGNORED", message = "处理结果不合法")
    private String status;

    @Schema(description = "处理说明", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "处理说明不能为空")
    @Size(max = 500, message = "处理说明不能超过 500 个字符")
    private String remark;

    @Schema(description = "期望修订号", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "修订号不能为空")
    @Min(value = 0, message = "修订号不能小于 0")
    private Integer expectedRevision;
}
