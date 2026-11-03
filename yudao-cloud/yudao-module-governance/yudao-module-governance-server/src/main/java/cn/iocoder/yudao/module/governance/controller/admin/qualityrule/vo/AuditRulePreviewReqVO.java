package cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 稽查规则 SQL 预览 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 稽查规则 SQL 预览 Request VO")
@Data
public class AuditRulePreviewReqVO {

    @Schema(description = "规则编号（与 strategyKey 二选一）", example = "2")
    private Long ruleId;

    @Schema(description = "策略标识（与 ruleId 二选一）", example = "LENGTH_VALIDATION")
    private String strategyKey;

    @Schema(description = "数据库产品类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "MYSQL")
    @NotBlank(message = "数据库产品类型不能为空")
    private String productType;

    @Schema(description = "表名", requiredMode = Schema.RequiredMode.REQUIRED, example = "t_user")
    @NotBlank(message = "表名不能为空")
    private String tableName;

    @Schema(description = "稽查字段（单字段规则）", example = "phone")
    private String column;

    @Schema(description = "稽查字段列表（多字段规则，逗号分隔）", example = "store_id,sku_id,biz_date")
    private String columns;

    @Schema(description = "附加过滤条件", example = "deleted = 0")
    private String whereClause;

    @Schema(description = "规则配置 JSON", example = "{\"minLength\": 11, \"maxLength\": 11}")
    private String configJson;

}
