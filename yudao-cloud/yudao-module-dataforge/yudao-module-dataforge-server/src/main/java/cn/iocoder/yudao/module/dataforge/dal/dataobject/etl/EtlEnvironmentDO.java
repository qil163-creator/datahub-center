package cn.iocoder.yudao.module.dataforge.dal.dataobject.etl;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.*;

/** 平台内 ETL 连接环境，不表示新的研发工作空间。 */
@TableName("dh_dataforge_etl_environment")
@KeySequence("dh_dataforge_etl_environment_seq")
@Data
@EqualsAndHashCode(callSuper = true)
public class EtlEnvironmentDO extends BaseDO {

    @TableId
    private Long id;

    private String code;

    private String name;

    private Boolean enabled;

    private Integer revision;
}
