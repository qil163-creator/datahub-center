package cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 数据治理-数据质量-规则集新增/修改 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 规则集新增/修改 Request VO")
@Data
public class QualityRuleSetSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "规则集名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "数据采集质量规则集")
    @NotBlank(message = "规则集名称不能为空")
    @Size(max = 128, message = "规则集名称不能超过 128 个字符")
    private String setName;

    @Schema(description = "规则集编码", example = "COLLECT_QUALITY")
    @Size(max = 128, message = "规则集编码不能超过 128 个字符")
    private String setCode;

    @Schema(description = "分类", example = "数据采集质量")
    @Size(max = 64, message = "分类不能超过 64 个字符")
    private String category;

    @Schema(description = "评估维度", example = "机构")
    @Size(max = 64, message = "评估维度不能超过 64 个字符")
    private String assessDim;

    @Schema(description = "评估周期", example = "WEEK")
    private String cycle;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

    @Schema(description = "描述", example = "描述")
    @Size(max = 1024, message = "描述不能超过 1024 个字符")
    private String description;

}
