package cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_modeling_business_entity_relation")
@KeySequence("dh_modeling_business_entity_relation_seq")
public class BusinessEntityRelationDO extends BaseDO {

    private Long id;

    private Long sourceEntityId;

    private Long targetEntityId;

    private String relationType;

    private Integer sortOrder;
}
