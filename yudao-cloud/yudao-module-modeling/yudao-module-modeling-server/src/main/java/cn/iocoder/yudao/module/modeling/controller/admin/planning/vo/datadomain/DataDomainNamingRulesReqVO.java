package cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.datadomain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
public class DataDomainNamingRulesReqVO {

    @NotNull
    @Positive
    private Long id;

    @NotNull
    @Size(min = 6, max = 6)
    private List<@NotNull @Valid DataDomainNamingRuleVO> namingRules;
}
