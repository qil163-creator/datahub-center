package cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/** 稽查规则独立维度；与数据质量业务类目无关联。 */
@TableName("dh_governance_audit_rule_dimension")
@KeySequence("dh_governance_audit_rule_dimension_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditRuleDimensionDO extends BaseDO {

    @TableId
    private Long id;

    private String name;

    private String code;

    /** 状态：0-启用，1-停用。 */
    private Short status;

    private Integer sort;

    private String remark;
}
