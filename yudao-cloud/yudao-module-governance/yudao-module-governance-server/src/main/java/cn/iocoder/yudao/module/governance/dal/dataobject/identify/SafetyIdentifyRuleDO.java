package cn.iocoder.yudao.module.governance.dal.dataobject.identify;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;

/**
 * 敏感数据识别规则 DO
 *
 * @author Li
 */
@TableName("dh_governance_safety_identify_rule")
@KeySequence("dh_safety_identify_rule_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyIdentifyRuleDO extends BaseDO {

    /** 匹配方式：字段名正则 */
    public static final String MATCH_TYPE_COLUMN_NAME = "COLUMN_NAME";

    /** 匹配方式：注释关键词（逗号分隔） */
    public static final String MATCH_TYPE_COLUMN_COMMENT = "COLUMN_COMMENT";

    /** 匹配方式：数据内容正则（采样） */
    public static final String MATCH_TYPE_DATA_REGEX = "DATA_REGEX";

    /** 状态：启用 */
    public static final Integer STATUS_ENABLE = 0;

    /** 状态：停用 */
    public static final Integer STATUS_DISABLE = 1;

    /**
     * 规则编号
     */
    @TableId
    private Long id;

    /**
     * 规则名称
     */
    private String ruleName;

    /**
     * 匹配方式：COLUMN_NAME / COLUMN_COMMENT / DATA_REGEX
     */
    private String matchType;

    /**
     * 匹配模式：正则表达式或关键词列表（逗号分隔）
     */
    private String pattern;

    /**
     * 命中后归属的数据分类编号
     */
    private Long categoryId;

    /**
     * 命中后建议的数据分级编号（为空取分类上配置的分级）
     */
    private Long levelId;

    /**
     * DATA_REGEX 方式的采样命中率阈值（百分比）
     */
    private BigDecimal minHitRatio;

    /**
     * DATA_REGEX 方式的采样行数（上限 100）
     */
    private Integer sampleRows;

    /**
     * 是否内置规则：0-自定义 1-内置
     */
    private Integer builtinFlag;

    /**
     * 状态：0-启用 1-停用
     */
    private Integer status;

    /**
     * 显示顺序
     */
    private Integer sort;

    /**
     * 描述
     */
    private String description;

}
