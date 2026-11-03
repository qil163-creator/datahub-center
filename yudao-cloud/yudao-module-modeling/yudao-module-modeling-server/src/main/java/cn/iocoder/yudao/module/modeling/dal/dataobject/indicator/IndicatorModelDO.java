package cn.iocoder.yudao.module.modeling.dal.dataobject.indicator;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/** 指标定义；来源绑定当前已发布模型与稳定字段UID，本表不承担指标计算任务。 */
@TableName("dh_modeling_indicator_model")
@KeySequence("dh_modeling_indicator_model_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class IndicatorModelDO extends BaseDO {

    public static final String ATOMIC = "ATOMIC";

    public static final String DERIVED = "DERIVED";

    public static final String DRAFT = "DRAFT";

    public static final String ACTIVE = "ACTIVE";

    @TableId
    private Long id;

    private String code;

    private String name;

    private String indicatorType;

    private Long dataDomainId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long subjectDomainId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long entityId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long ownerUserId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String definition;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String unit;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String dataFreshness;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String resultDataType;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long sourceModelId;


    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String valueFieldUid;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String timeFieldUid;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String aggregation;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long baseIndicatorId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String timePeriod;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String businessCondition;

    private String status;

    private Long revision;
}

