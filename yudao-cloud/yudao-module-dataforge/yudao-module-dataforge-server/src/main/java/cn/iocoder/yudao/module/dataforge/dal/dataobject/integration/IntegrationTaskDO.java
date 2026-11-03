package cn.iocoder.yudao.module.dataforge.dal.dataobject.integration;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 数据集成任务控制面，执行仅委托已绑定的受管 ETL 流程。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "dh_dataforge_integration_task", autoResultMap = true)
@KeySequence("dh_dataforge_integration_task_seq")
public class IntegrationTaskDO extends BaseDO {

    @TableId
    private Long id;

    private Integer revision;

    private String mode;

    private Long categoryId;

    private String name;

    private Long ownerId;

    private String ownerName;

    private String contactNumber;

    private Integer status;

    private Integer scheduleStatus;

    private String engine;

    private String scheduler;

    private String cronExpression;

    private String priority;

    private Integer retryCount;

    private Integer retryInterval;

    private Integer delayMinutes;

    private String executionStrategy;

    private String workerGroup;

    private String description;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String engineConfigJson;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String flowJson;

    private String configurationStatus;

    /** 仅受信任的配置流程可写入，不从基础信息请求接受流程绑定。 */
    private Long pipelineId;

    private Long dsProjectCode;
    private Long dsDefinitionCode;
    private Integer dsScheduleId;
    private String dsBindingHash;

    private Boolean importedSnapshot;

    private String lastRunStatus;

    private LocalDateTime lastRunTime;

    private Long lastRunDuration;

    private String creatorName;
}
