package cn.iocoder.yudao.module.governance.api.lineage.dto;

import cn.iocoder.yudao.module.governance.enums.lineage.LineageContractConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 血缘解析问题（不支持的语法、无法解析的对象、歧义等）。
 */
@Schema(description = "RPC 服务 - 血缘解析问题")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LineageIssueDTO {

    @Schema(description = "问题编码", requiredMode = Schema.RequiredMode.REQUIRED, example = "UNSUPPORTED_STATEMENT")
    @NotBlank(message = "问题编码不能为空")
    @Size(max = LineageContractConstants.MAX_ISSUE_CODE_LENGTH, message = "问题编码不能超过 64 个字符")
    private String code;

    @Schema(description = "问题描述（不得包含凭据或数据样本）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "问题描述不能为空")
    @Size(max = LineageContractConstants.MAX_ISSUE_MESSAGE_LENGTH, message = "问题描述不能超过 500 个字符")
    private String message;

    @Schema(description = "所在语句序号（从 0 开始），与具体语句无关时为空")
    @Min(value = 0, message = "语句序号不能小于 0")
    private Integer statementIndex;

}
