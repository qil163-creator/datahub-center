package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

/**
 * 数据治理-数据质量-规则集明细（规则 + 权重）DO
 *
 * @author Li
 */
@TableName("dh_governance_quality_rule_set_item")
@KeySequence("dh_governance_quality_rule_set_item_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityRuleSetItemDO extends BaseDO {

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 规则集ID
     */
    private Long ruleSetId;

    /**
     * 规则ID
     */
    private Long ruleId;

    /**
     * 权重
     */
    private BigDecimal weight;

}
