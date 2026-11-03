package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据治理-数据质量-规则集 DO
 *
 * @author Li
 */
@TableName("dh_governance_quality_rule_set")
@KeySequence("dh_governance_quality_rule_set_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityRuleSetDO extends BaseDO {

    /** 状态 - 启用 */
    public static final Integer STATUS_ENABLE = 0;

    /** 状态 - 停用 */
    public static final Integer STATUS_DISABLE = 1;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 规则集名称
     */
    private String setName;

    /**
     * 规则集编码
     */
    private String setCode;

    /**
     * 分类（如数据采集质量）
     */
    private String category;

    /**
     * 评估维度（如按机构）
     */
    private String assessDim;

    /**
     * 评估周期：DAY/WEEK/MONTH
     */
    private String cycle;

    /**
     * 状态：0-启用 1-停用
     */
    private Integer status;

    /**
     * 描述
     */
    private String description;

}
