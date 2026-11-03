package cn.iocoder.yudao.module.governance.api.identify.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "RPC - 已启用的敏感识别规则")
@Data
public class SafetyIdentifyRuleRespDTO {

    @Schema(description = "规则编号")
    private Long id;

    @Schema(description = "规则名称")
    private String ruleName;

    @Schema(description = "匹配方式：COLUMN_NAME / COLUMN_COMMENT / DATA_REGEX")
    private String matchType;

    @Schema(description = "匹配模式")
    private String pattern;

    @Schema(description = "DATA_REGEX 采样命中率阈值（百分比）")
    private BigDecimal minHitRatio;

    @Schema(description = "DATA_REGEX 采样行数（上限 100）")
    private Integer sampleRows;

}
