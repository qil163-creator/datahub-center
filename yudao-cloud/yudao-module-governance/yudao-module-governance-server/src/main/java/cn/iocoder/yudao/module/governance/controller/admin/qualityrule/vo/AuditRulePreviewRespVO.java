package cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 稽查规则 SQL 预览 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 稽查规则 SQL 预览 Response VO")
@Data
public class AuditRulePreviewRespVO {

    @Schema(description = "策略标识", example = "LENGTH_VALIDATION")
    private String strategyKey;

    @Schema(description = "错误统计 SQL（totalCount / errorCount）")
    private String countSql;

    @Schema(description = "错误明细 SQL")
    private String errorDataSql;

}
