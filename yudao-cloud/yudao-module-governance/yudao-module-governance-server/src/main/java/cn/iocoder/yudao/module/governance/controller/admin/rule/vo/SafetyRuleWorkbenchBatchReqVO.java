package cn.iocoder.yudao.module.governance.controller.admin.rule.vo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class SafetyRuleWorkbenchBatchReqVO {

    @Valid
    @NotEmpty
    @Size(max = 100)
    private List<@NotNull SafetyRuleWorkbenchVersionReqVO> items;
}
