package cn.iocoder.yudao.module.modeling.dal.dataobject.businesscondition;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/** 业务限定是建模定义；表达式不用于动态 SQL 执行。 */
@TableName("dh_modeling_business_condition")
@KeySequence("dh_modeling_business_condition_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class BusinessConditionDO extends BaseDO {

    public static final String DRAFT = "DRAFT";

    public static final String ACTIVE = "ACTIVE";

    @TableId
    private Long id;

    private String code;

    private String name;

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
    private String businessEntity;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String dataFreshness;

    private Long sourceModelId;


    private String configType;

    private String logicMode;

    private String expression;

    private String status;

    private Long revision;
}
