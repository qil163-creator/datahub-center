package cn.iocoder.yudao.module.governance.dal.dataobject.standardelement;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

@TableName(value = "dh_governance_standard_element_rule", autoResultMap = true)
@KeySequence("dh_governance_standard_element_rule_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardElementRuleDO extends BaseDO {

    @TableId
    private Long id;

    private Integer elementId;

    private String kind;

    private String name;

    private Long templateId;

    private Integer status;

    private String description;

    private String whereCondition;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String configJson;

    private String severity;

    private String errorExample;

    private String repairSuggestion;

    private Long revision;
}
