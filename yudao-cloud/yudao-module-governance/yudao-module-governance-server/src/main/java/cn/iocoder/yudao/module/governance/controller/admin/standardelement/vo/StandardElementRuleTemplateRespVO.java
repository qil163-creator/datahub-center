package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import lombok.Data;

import java.util.Map;

@Data
public class StandardElementRuleTemplateRespVO {

    private Long id;

    private String kind;

    private String code;

    private String name;

    private String strategyKey;

    private Long categoryId;

    private String categoryName;

    private String dimensionName;

    private String description;

    private String useCase;

    private String example;

    private boolean enabled;

    private boolean engineSupported;

    private String disabledReason;

    private String configExample;

    private Map<String, Object> config;
}
