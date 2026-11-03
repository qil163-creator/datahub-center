package cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 清洗规则新增/修改 Request VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 清洗规则新增/修改 Request VO")
@Data
public class CleanRuleSaveReqVO {

    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "去除字段空格")
    @NotBlank(message = "规则名称不能为空")
    @Size(max = 100, message = "规则名称不能超过 100 个字符")
    private String name;

    @Schema(description = "清洗规则类目编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "5")
    @NotNull(message = "清洗规则类目不能为空")
    private Long categoryId;

    @Schema(description = "规则级别：1-字段级 2-表级", example = "1")
    private Integer ruleLevel;

    @Schema(description = "规则编码", example = "009")
    @Size(max = 32, message = "规则编码不能超过 32 个字符")
    private String code;

    @Schema(description = "策略标识", example = "REMOVE_WHITESPACE")
    @Size(max = 64, message = "策略标识不能超过 64 个字符")
    private String strategyKey;

    @Schema(description = "函数参数 JSON 对象；字段绑定由 ETL 节点提供", example = "{\"handleType\":1}")
    private Map<String, Object> config;

    @Schema(description = "规则描述", example = "去除字段中的前后空格")
    @Size(max = 500, message = "规则描述不能超过 500 个字符")
    private String description;

    @Schema(description = "使用场景", example = "去除字段中的前后空格")
    @Size(max = 500, message = "使用场景不能超过 500 个字符")
    private String useCase;

    @Schema(description = "示例", example = "“ 山西省 ” → “山西省”")
    @Size(max = 2000, message = "示例不能超过 2000 个字符")
    private String example;

    @Schema(description = "状态：0-启用 1-停用", example = "0")
    private Integer status;

}
