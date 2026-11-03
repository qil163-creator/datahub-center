package cn.iocoder.yudao.module.governance.controller.admin.rule.vo;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SafetyRuleWorkbenchStatusReqVO extends SafetyRuleWorkbenchVersionReqVO {

    @NotNull
    @Min(0)
    @Max(1)
    private Short status;
}
