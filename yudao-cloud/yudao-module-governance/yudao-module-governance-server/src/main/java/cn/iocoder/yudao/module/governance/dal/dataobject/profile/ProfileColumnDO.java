package cn.iocoder.yudao.module.governance.dal.dataobject.profile;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * 数据探查-字段指标：每次探查每个字段一行，统计基于抽样。
 *
 * @author Li
 */
@TableName("dh_governance_profile_column")
@KeySequence("dh_governance_profile_column_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ProfileColumnDO extends BaseDO {

    @TableId
    private Long id;

    /** 探查记录编号 */
    private Long runId;

    /** 元数据字段编号 */
    private Long metaColumnId;

    /** 字段名 */
    private String columnName;

    /** 探查时的字段类型 */
    private String dataType;

    /** 统计类别：TEXT / NUMERIC / TEMPORAL / BOOLEAN / OTHER */
    private String columnKind;

    /** 字段序号 */
    private Integer ordinalPosition;

    /** 非空数 */
    private Long nonNullCount;

    /** 空值数 */
    private Long nullCount;

    /** 空值率（0~1） */
    private BigDecimal nullRate;

    /** 唯一值数 */
    private Long distinctCount;

    /** 唯一率（唯一值数/非空数） */
    private BigDecimal uniqueRate;

    /** 最小值文本；敏感字段为空 */
    private String minValue;

    /** 最大值文本；敏感字段为空 */
    private String maxValue;

    /** 取值已脱敏：0 否 1 是 */
    private Integer valueMasked;

    /** 脱敏原因：RULE / UNCLASSIFIED / UNTRACEABLE / UNVERIFIED / METADATA / MASK_FAILED */
    private String maskReason;

    /** 文本平均长度 */
    private BigDecimal avgLength;

    /** 文本最小长度 */
    private Long minLength;

    /** 文本最大长度 */
    private Long maxLength;

    /** 数值平均值 */
    private BigDecimal avgValue;

}
