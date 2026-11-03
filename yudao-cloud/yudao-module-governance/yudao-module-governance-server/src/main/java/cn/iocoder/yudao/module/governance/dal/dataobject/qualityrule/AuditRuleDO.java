package cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据质量-稽查规则 DO
 *
 * @author Li
 */
@TableName("dh_governance_audit_rule")
@KeySequence("dh_governance_audit_rule_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditRuleDO extends BaseDO {

    /** 规则级别 - 字段级 */
    public static final Integer LEVEL_FIELD = 1;

    /** 规则级别 - 表级 */
    public static final Integer LEVEL_TABLE = 2;

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
     * 规则名称
     */
    private String name;

    /**
     * 稽查规则独立维度编号
     */
    private Long dimensionId;

    /**
     * 规则级别：1-字段级 2-表级
     */
    private Integer ruleLevel;

    /**
     * 规则编码
     */
    private String code;

    /**
     * 策略标识（质量引擎 SQL 生成器），为空表示暂未接入引擎
     */
    private String strategyKey;

    /**
     * 规则描述
     */
    private String description;

    /**
     * 使用场景
     */
    private String useCase;

    /**
     * 示例
     */
    private String example;

    /**
     * 状态：0-启用 1-停用
     */
    private Integer status;

}
