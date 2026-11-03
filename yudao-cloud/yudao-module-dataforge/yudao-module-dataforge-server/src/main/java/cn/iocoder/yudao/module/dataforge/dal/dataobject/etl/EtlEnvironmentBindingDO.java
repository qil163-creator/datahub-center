package cn.iocoder.yudao.module.dataforge.dal.dataobject.etl;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/** 环境中的逻辑连接键与平台数据源、模式绑定。 */
@TableName("dh_dataforge_etl_environment_binding")
@KeySequence("dh_dataforge_etl_environment_binding_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class EtlEnvironmentBindingDO extends BaseDO {

    @TableId
    private Long id;

    private Long environmentId;

    private String logicalKey;

    private Long databaseId;

    private String schemaName;

    private Integer revision;
}
