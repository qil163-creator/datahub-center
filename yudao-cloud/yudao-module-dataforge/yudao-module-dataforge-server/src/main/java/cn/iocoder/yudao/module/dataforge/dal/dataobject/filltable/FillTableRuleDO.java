package cn.iocoder.yudao.module.dataforge.dal.dataobject.filltable;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据填报-填报表校验规则 DO（行级、表级、跨期表达式规则；发布时固化进模板版本）
 *
 * @author Li
 */
@TableName("dh_dataforge_fill_table_rule")
@KeySequence("dh_dataforge_fill_table_rule_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FillTableRuleDO extends BaseDO {

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 填报表id
     */
    private Long tableId;

    /**
     * 规则名称
     */
    private String name;

    /**
     * 范围：ROW-行级 TABLE-表级（汇总） PERIOD-跨期（与上期比较），见 FillExpression.Scope
     */
    private String scope;

    /**
     * 规则表达式（结果为假即违反）
     */
    private String expression;

    /**
     * 违反时的提示语
     */
    private String message;

    /**
     * 级别：1-错误（阻断提交） 2-警告
     */
    private Integer severity;

    /**
     * 问题定位字段码（行级规则高亮该单元格；为空时定位到整行或整单）
     */
    private String fieldCode;

    /**
     * 排序
     */
    private Integer sort;

    /**
     * 是否启用：0-否 1-是
     */
    private Integer enabled;

}
