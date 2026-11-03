package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据治理-数据质量-质量规则 DO
 *
 * 融合四系统：规则原子化（可复用）、六维质量维度、规则类型、严重程度、适用分层
 *
 * @author Li
 */
@TableName(value = "dh_governance_quality_rule", autoResultMap = true)
@KeySequence("dh_governance_quality_rule_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityRuleDO extends BaseDO {

    /** 状态 - 启用 */
    public static final Integer STATUS_ENABLE = 0;

    /** 状态 - 停用 */
    public static final Integer STATUS_DISABLE = 1;

    /** 校验级别 - 字段级 */
    public static final String CHECK_LEVEL_FIELD = "FIELD";

    /** 校验级别 - 表级 */
    public static final String CHECK_LEVEL_TABLE = "TABLE";

    /** 校验级别 - 跨表级 */
    public static final String CHECK_LEVEL_CROSS_TABLE = "CROSS_TABLE";

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 规则类目ID（质量维度树/分层树）
     */
    private Long categoryId;

    /**
     * 规则名称
     */
    private String ruleName;

    /**
     * 规则编码
     */
    private String ruleCode;

    /**
     * 质量维度：COMPLETENESS完整性/UNIQUENESS唯一性/NORMALIZATION规范性/ACCURACY准确性/TIMELINESS及时性/CONSISTENCY一致性
     */
    private String qualityDim;

    /**
     * 检查级别：FIELD字段级/TABLE表级/CROSS_TABLE跨表级
     */
    private String checkLevel;

    /**
     * 规则类型：NOT_NULL/UNIQUE/RANGE/REGEX/ENUM/LENGTH/PRECISION/TIME_ORDER/GROUP_COMPLETENESS/COMPOSITE_UNIQUE/CROSS_TABLE/FOREIGN_KEY/TIMELINESS/PLACEHOLDER/CUSTOM
     */
    private String ruleType;

    /**
     * 执行策略标识（质量引擎生成器）
     */
    private String strategyKey;

    /**
     * 参数模板JSON Schema
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String paramSchema;

    /**
     * 规则内容：SQL语句或正则表达式
     */
    private String ruleContent;

    /**
     * 严重程度：CRITICAL严重/HIGH高/MID中/LOW低
     */
    private String severity;

    /**
     * 适用分层：贴源/明细/汇总/应用（留空不限层）
     */
    private String applyLayer;

    /**
     * 采集问题明细：0-否 1-是（留存可回源定位记录，默认开启）
     */
    private Integer collectIssueDetail;

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

    /**
     * 是否内置：1-是（不可删）0-否
     */
    private Integer builtin;

    /**
     * 说明
     */
    private String description;

    /**
     * 备注
     */
    private String remark;

    /**
     * 来源标准数据元编号；空表示手工或内置规则
     */
    private Long standardElementId;

    /**
     * 约束内容 SHA-256
     */
    private String constraintHash;

    /** 本次执行的检查对象；仅进入实例快照，不写入规则模板。 */
    @TableField(exist = false)
    private Long executionObjectId;

    /** 任务专属评测编号，避免将任务配置写入全局模板。 */
    @TableField(exist = false)
    private Long executionEvaluationId;

    /** 本次执行的元数据字段；仅进入实例快照。 */
    @TableField(exist = false)
    private Long executionColumnId;

    /** 本次执行采用的已确认落标事实；仅进入实例快照。 */
    @TableField(exist = false)
    private Long standardMappingId;

    /** 冻结的源端关系位置，执行前按实际连接复核。 */
    @TableField(exist = false)
    private String executionSchemaName;

    @TableField(exist = false)
    private String executionTableName;

}
