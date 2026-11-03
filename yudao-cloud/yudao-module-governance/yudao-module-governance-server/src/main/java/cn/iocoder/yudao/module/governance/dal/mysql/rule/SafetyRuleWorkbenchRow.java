package cn.iocoder.yudao.module.governance.dal.mysql.rule;

import cn.iocoder.yudao.module.governance.dal.dataobject.rule.SafetyRuleDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SafetyRuleWorkbenchRow extends SafetyRuleDO {

    private Long categoryId;

    private Long categoryRevision;

    private String categoryName;

    private String categorySourceRecordId;

    private Integer currentVersionNo;

    private String configJson;

    private String configChecksum;
}
