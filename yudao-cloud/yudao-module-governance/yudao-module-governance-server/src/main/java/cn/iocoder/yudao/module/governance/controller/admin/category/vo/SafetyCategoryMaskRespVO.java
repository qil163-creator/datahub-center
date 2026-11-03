package cn.iocoder.yudao.module.governance.controller.admin.category.vo;

import lombok.Data;

import java.util.List;

@Data
public class SafetyCategoryMaskRespVO {

    private Long id;

    private Long revision;

    private Long ruleId;

    private Long ruleVersionId;

    private String ruleRevisionToken;

    private String ruleName;

    private List<String> applicationScene;

    private String maskType;

    private String replaceRule;

    private String replaceContent;

    private List<SafetyCategoryMaskReqVO.Interval> intervalList;

    private Short status;

    private String description;
}
