package cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo;

import lombok.Data;

@Data
public class StandardElementRuleRespVO {

    private Long id;

    private Integer elementId;

    private String kind;

    private String name;

    private Long templateId;

    private Integer status;

    private String description;

    private String whereCondition;

    private java.util.Map<String, Object> config;

    private String severity;

    private String errorExample;

    private String repairSuggestion;

    private Long revision;

    private String templateCode;

    private String templateName;

    private String strategyKey;

    private String dimensionName;

    private java.time.LocalDateTime createTime;

    private java.time.LocalDateTime updateTime;

    private String creator;

    private String creatorName;
}
