package cn.iocoder.yudao.module.governance.controller.admin.rule.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SafetyRuleWorkbenchPageReqVO extends PageParam {

    @Size(max = 64)
    private String ruleName;

    @Positive
    private Long categoryId;

    @Pattern(regexp = "1|2|3")
    private String applicationScene;

    @Pattern(regexp = "1|2")
    private String maskType;

    @Min(0)
    @Max(1)
    private Short status;

    @Pattern(regexp = "id|createTime")
    private String sortField;

    @Pattern(regexp = "asc|desc")
    private String sortOrder;
}
