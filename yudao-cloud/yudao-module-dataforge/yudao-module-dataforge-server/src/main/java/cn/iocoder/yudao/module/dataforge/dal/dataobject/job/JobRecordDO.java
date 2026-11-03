package cn.iocoder.yudao.module.dataforge.dal.dataobject.job;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
/** 作业管理持久状态，不保存连接凭据。 */
@Data @EqualsAndHashCode(callSuper=true)
@TableName(value="dh_dataforge_job_record",autoResultMap=true)
@KeySequence("dh_dataforge_job_record_seq")
public class JobRecordDO extends BaseDO { @TableId private Long id; private Long jobId; private String origin; private String requestId; private String instanceName; private String status; private String executionType; private Long dsProjectCode; private Long dsDefinitionCode; private Long executionUserId; private String submissionStage; @TableField(typeHandler=JsonbStringTypeHandler.class) private String runtimeTargetJson; private Integer dsInstanceId; private LocalDateTime startTime; private LocalDateTime endTime; private Long duration; private String logContent; @TableField(typeHandler=JsonbStringTypeHandler.class) private String flowJson; @TableField(typeHandler=JsonbStringTypeHandler.class) private String childrenJson; @TableField(typeHandler=JsonbStringTypeHandler.class) private String runSnapshotJson; private String queueState; private String queueReason; private LocalDateTime queuedTime; private LocalDateTime scheduledTime; private Long scheduleGeneration; private Boolean serialSlot; private Boolean stopRequested; private String leaseOwner; private LocalDateTime leaseUntil; }
