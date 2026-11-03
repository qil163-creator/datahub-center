package cn.iocoder.yudao.module.dataforge.dal.dataobject.integration;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 源系统迁入的执行实例快照；本平台新执行实例读取 ETL 记录。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dh_dataforge_integration_record")
@KeySequence("dh_dataforge_integration_record_seq")
public class IntegrationRecordDO extends BaseDO {

    @TableId
    private Long id;

    private Long taskId;

    private String status;

    private String triggerType;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long duration;

    private String executorName;

    private String message;

    private String currentNode;

    private String instanceName;

    private String commandType;

    private Long dsProjectCode;
    private Integer dsInstanceId;
}
