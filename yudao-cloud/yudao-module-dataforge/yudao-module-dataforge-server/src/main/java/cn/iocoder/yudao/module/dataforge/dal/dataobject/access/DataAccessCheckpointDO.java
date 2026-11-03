package cn.iocoder.yudao.module.dataforge.dal.dataobject.access;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 平台内任务表级的已应用游标；旧 increase_log 不作为此协议的可信来源。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "dh_dataforge_access_checkpoint", autoResultMap = true)
@KeySequence("dh_dataforge_access_checkpoint_seq")
public class DataAccessCheckpointDO extends BaseDO {

    @TableId
    private Long id;

    private Long dataAccessId;

    private String schemaName;

    private String tableName;

    private String watermarkColumn;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String checkpointJson;

    private Long revision;

    private Long ownerRunId;
}
