package cn.iocoder.yudao.module.governance.controller.admin.identify.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 敏感数据识别规则 Response VO")
@Data
public class SafetyIdentifyRuleRespVO {

    @Schema(description = "规则编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String ruleName;

    @Schema(description = "匹配方式", requiredMode = Schema.RequiredMode.REQUIRED, example = "COLUMN_NAME")
    private String matchType;

    @Schema(description = "匹配模式", requiredMode = Schema.RequiredMode.REQUIRED)
    private String pattern;

    @Schema(description = "数据分类编号", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long categoryId;

    @Schema(description = "数据分类名称")
    private String categoryName;

    @Schema(description = "数据分级编号")
    private Long levelId;

    @Schema(description = "数据分级名称")
    private String levelName;

    @Schema(description = "采样命中率阈值（百分比）")
    private BigDecimal minHitRatio;

    @Schema(description = "采样行数")
    private Integer sampleRows;

    @Schema(description = "是否内置规则：0-自定义 1-内置")
    private Integer builtinFlag;

    @Schema(description = "状态：0-启用 1-停用", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer status;

    @Schema(description = "显示顺序")
    private Integer sort;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime createTime;

}
