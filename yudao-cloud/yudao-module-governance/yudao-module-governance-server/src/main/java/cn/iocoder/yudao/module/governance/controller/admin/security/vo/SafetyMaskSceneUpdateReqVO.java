package cn.iocoder.yudao.module.governance.controller.admin.security.vo;

import cn.iocoder.yudao.framework.common.validation.InEnum;
import cn.iocoder.yudao.module.governance.enums.security.SafetyMaskSceneEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台 - 动态脱敏场景开关更新 Request VO
 */
@Schema(description = "管理后台 - 动态脱敏场景开关更新 Request VO")
@Data
public class SafetyMaskSceneUpdateReqVO {

    @Schema(description = "场景编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "DATA_QUERY")
    @NotBlank(message = "场景编码不能为空")
    @InEnum(value = SafetyMaskSceneEnum.class, message = "场景编码必须是 {value}")
    private String sceneCode;

    @Schema(description = "是否启用", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
    @NotNull(message = "是否启用不能为空")
    private Boolean enabled;

    @Schema(description = "备注；关闭场景时必填，说明原因")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

    @Schema(description = "期望修订号（乐观锁）", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "修订号不能为空")
    @Min(value = 0, message = "修订号不能小于 0")
    private Integer expectedRevision;
}
