package cn.iocoder.yudao.module.governance.controller.admin.rule.vo;

import cn.iocoder.yudao.module.governance.controller.admin.category.vo.SafetyCategoryMaskReqVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SafetyRuleWorkbenchRespVO {

    private Long id;

    private String sourceRecordId;

    private String ruleName;

    private String description;

    private Long categoryId;

    private Long categoryRevision;

    private String categoryName;

    private String categorySourceRecordId;

    private List<String> applicationScene;

    private String maskType;

    private String replaceRule;

    private String replaceContent;

    private List<SafetyCategoryMaskReqVO.Interval> intervalList;

    private Short status;

    private Long ruleVersionId;

    private String ruleRevisionToken;

    private String creatorName;

    private LocalDateTime createTime;

    private String updaterName;

    private LocalDateTime updateTime;
}
