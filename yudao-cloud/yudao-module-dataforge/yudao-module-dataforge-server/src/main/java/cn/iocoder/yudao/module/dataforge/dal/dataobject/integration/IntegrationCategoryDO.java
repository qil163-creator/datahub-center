package cn.iocoder.yudao.module.dataforge.dal.dataobject.integration;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 离线、实时工作台共享的数据集成类目。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_dataforge_integration_category")
@KeySequence("dh_dataforge_integration_category_seq")
public class IntegrationCategoryDO extends BaseDO {

    @TableId
    private Long id;

    private Long parentId;

    private String name;

    private Integer sort;

    private Boolean validFlag;

    private String description;
}
