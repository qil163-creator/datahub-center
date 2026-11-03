package cn.iocoder.yudao.module.modeling.dal.dataobject.conceptmodel;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.modeling.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "dh_modeling_concept_model", autoResultMap = true)
@KeySequence("dh_modeling_concept_model_seq")
public class ConceptModelDO extends BaseDO {

    public static final String STATUS_DRAFT = "DRAFT";

    public static final String STATUS_ACTIVE = "ACTIVE";

    @TableId
    private Long id;

    private String name;

    private String code;

    private Long dataDomainId;

    private Long subjectDomainId;

    private String description;

    private String status;

    private Long revision;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String design;
}
