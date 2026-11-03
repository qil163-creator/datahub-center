package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.orgscore;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "管理后台 - 机构评分 Response VO")
@Data
public class QualityOrgScoreRespVO {

    @Schema(description = "编号")
    private Long id;

    @Schema(description = "规则集编号")
    private Long ruleSetId;

    @Schema(description = "规则集名称")
    private String ruleSetName;

    @Schema(description = "周期开始（YYYYMM）")
    private String periodStart;

    @Schema(description = "周期结束（YYYYMM）")
    private String periodEnd;

    @Schema(description = "机构编号")
    private Long orgId;

    @Schema(description = "机构名称")
    private String orgName;

    @Schema(description = "机构编码")
    private String orgCode;

    @Schema(description = "得分率（%）")
    private BigDecimal scoreRate;

    @Schema(description = "达标规则数")
    private Long passRuleCount;

    @Schema(description = "总规则数")
    private Long totalRuleCount;

    @Schema(description = "区域排名")
    private Integer regionRank;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

}
