package cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import lombok.*;

/**
 * 数据质量-清洗规则 DO
 *
 * @author Li
 */
@TableName(value = "dh_governance_clean_rule", autoResultMap = true)
@KeySequence("dh_governance_clean_rule_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CleanRuleDO extends BaseDO {

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
     * 清洗规则类目编号
     */
    private Long categoryId;

    /**
     * 规则级别：1-字段级 2-表级
     */
    private Integer ruleLevel;

    /**
     * 规则编码
     */
    private String code;

    /**
     * 策略标识（质量引擎清洗函数），为空表示暂未接入引擎
     */
    private String strategyKey;

    /** 已注册清洗函数的结构化参数 JSON 对象；旧记录可为空。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String configJson;

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
