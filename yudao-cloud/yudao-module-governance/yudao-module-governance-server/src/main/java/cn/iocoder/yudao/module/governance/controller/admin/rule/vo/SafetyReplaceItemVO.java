package cn.iocoder.yudao.module.governance.controller.admin.rule.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Schema(description = "管理后台 - 脱敏替换明细 VO（内容替换方式的匹配-替换码表）")
@Data
public class SafetyReplaceItemVO {

    @Schema(description = "匹配顺序，从1开始，命中即停", example = "1")
    private Integer sequenceNo;

    @Schema(description = "需要匹配的字段内容", requiredMode = Schema.RequiredMode.REQUIRED, example = "abc")
    @NotEmpty(message = "匹配值不能为空")
    private String matchValue;

    @Schema(description = "匹配成功后的替换内容", requiredMode = Schema.RequiredMode.REQUIRED, example = "*")
    private String replacementValue;

}
