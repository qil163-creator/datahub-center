package cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_modeling_business_entity")
@KeySequence("dh_modeling_business_entity_seq")
public class BusinessEntityDO extends BaseDO {

    private Long id;

    private Long dataDomainId;

    private Long subjectDomainId;

    private String name;

    private String code;

    private String entityType;

    private String entitySubtype;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long leaderUserId;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String leaderPhone;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String description;

    private Integer status;

    private Long revision;
}
