package cn.iocoder.yudao.module.governance.controller.admin.identify.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "管理后台 - 敏感数据识别规则新增/修改 Request VO")
@Data
public class SafetyIdentifyRuleSaveReqVO {

    @Schema(description = "规则编号", example = "1")
    private Long id;

    @Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "手机号-字段名")
    @NotEmpty(message = "规则名称不能为空")
    private String ruleName;

    @Schema(description = "匹配方式", requiredMode = Schema.RequiredMode.REQUIRED, example = "COLUMN_NAME")
    @NotEmpty(message = "匹配方式不能为空")
    private String matchType;

    @Schema(description = "匹配模式（正则或关键词列表）", requiredMode = Schema.RequiredMode.REQUIRED, example = "(?i)(mobile|phone)")
    @NotEmpty(message = "匹配模式不能为空")
    private String pattern;

    @Schema(description = "数据分类编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "数据分类不能为空")
    private Long categoryId;

    @Schema(description = "数据分级编号（为空取分类上配置的分级）", example = "2")
    private Long levelId;

    @Schema(description = "采样命中率阈值（百分比）", example = "80")
    private BigDecimal minHitRatio;

    @Schema(description = "采样行数", example = "50")
    @Min(value = 1, message = "采样行数不能小于 1")
    @Max(value = 100, message = "采样行数不能大于 100")
    private Integer sampleRows;

    @Schema(description = "状态：0-启用 1-停用", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "状态不能为空")
    private Integer status;

    @Schema(description = "显示顺序", example = "0")
    private Integer sort;

    @Schema(description = "描述")
    private String description;

}
