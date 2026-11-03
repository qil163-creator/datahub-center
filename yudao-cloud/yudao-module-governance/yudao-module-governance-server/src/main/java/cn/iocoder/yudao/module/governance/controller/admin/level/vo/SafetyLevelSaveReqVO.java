package cn.iocoder.yudao.module.governance.controller.admin.level.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import lombok.Data;

@Schema(description = "管理后台 - 数据分级新增/修改 Request VO")
@Data
public class SafetyLevelSaveReqVO {

    @Schema(description = "主键id", requiredMode = Schema.RequiredMode.REQUIRED, example = "14852")
    private Long id;

    @Schema(description = "安全等级", requiredMode = Schema.RequiredMode.REQUIRED, example = "L1")
    @NotEmpty(message = "安全等级不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{2,10}$", message = "安全等级须为 2 至 10 位字母、数字或下划线")
    private String safetyLevel;

    @Schema(description = "显示名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "芋艿")
    @NotEmpty(message = "显示名称不能为空")
    @Size(max = 50)
    private String safetyName;

    @Min(1)
    @Max(100)
    private Integer sensitiveLevel;

    @Schema(description = "标志颜色（hex，如 #52c41a，可为空，为空时前端按等级使用默认配色）", example = "#52c41a")
    @Pattern(regexp = "^$|^#[0-9a-fA-F]{6}$", message = "标志颜色格式必须为 6 位 hex 颜色值，如 #52c41a")
    private String color;

    @Schema(description = "状态", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "状态不能为空")
    @Min(0)
    @Max(1)
    private Short status;

    @Schema(description = "显示顺序", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer sort;

    @Schema(description = "规则描述", example = "随便")
    @Size(max = 256)
    private String description;

}
