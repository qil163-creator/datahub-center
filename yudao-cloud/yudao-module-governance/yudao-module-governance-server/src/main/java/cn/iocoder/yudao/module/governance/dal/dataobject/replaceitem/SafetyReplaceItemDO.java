package cn.iocoder.yudao.module.governance.dal.dataobject.replaceitem;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 脱敏替换明细 DO；替换方式的匹配-替换码表，随版本固化
 *
 * @author Li
 */
@TableName("dh_governance_safety_replace_item")
@KeySequence("dh_safety_replace_item_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyReplaceItemDO extends BaseDO {

    /**
     * 替换明细编号
     */
    @TableId
    private Long id;

    /**
     * 所属规则版本编号（关联 dh_governance_safety_rule_ver.id）
     */
    private Long ruleVerId;

    /**
     * 匹配顺序，从1开始，命中即停
     */
    private Integer sequenceNo;

    /**
     * 需要匹配的字段内容
     */
    private String matchValue;

    /**
     * 匹配成功后的替换内容
     */
    private String replacementValue;

}
