package cn.iocoder.yudao.module.modeling.dal.dataobject.model;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.modeling.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 部署领域持久尝试；冻结无密任务输入与目标证据，未知结果持续占用物理目标。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "dh_modeling_deployment_attempt", autoResultMap = true)
@KeySequence("dh_modeling_deployment_attempt_seq")
public class ModelDeploymentAttemptDO extends BaseDO {

    @TableId
    private Long id;

    private Long materializedId;

    /** 本次部署准备时的设计修订标记，未知历史记录为空。 */
    private Long designRevision;
    private Long modelId;

    private Long datasourceId;

    private String targetKey;

    private String schemaName;

    private String tableName;

    private String configurationRevision;

    private String publishMode;

    private String status;

    private String phase;

    private String leaseOwner;

    private LocalDateTime leaseUntil;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String beforeObservationJson;

    private String ddlSql;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String fieldsJson;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String modelSnapshotJson;

    private String message;

    private LocalDateTime finishedTime;
}
