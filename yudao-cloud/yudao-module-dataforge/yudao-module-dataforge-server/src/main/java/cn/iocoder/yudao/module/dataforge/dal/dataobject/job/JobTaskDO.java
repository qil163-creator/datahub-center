package cn.iocoder.yudao.module.dataforge.dal.dataobject.job;
import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
/** 作业管理持久状态，不保存连接凭据。 */
@Data @EqualsAndHashCode(callSuper=true)
@TableName(value="dh_dataforge_job",autoResultMap=true)
@KeySequence("dh_dataforge_job_seq")
public class JobTaskDO extends BaseDO { @TableId private Long id; private Integer revision; private Long categoryId; private String name; private Long ownerId; private String ownerName; private String ownerPhone; private Integer status; private Integer scheduleStatus; private String strategy; private String cronExpression; private String description; @TableField(typeHandler=JsonbStringTypeHandler.class) private String flowJson; private Long dsProjectCode; private Long dsDefinitionCode; private Integer dsScheduleId; private String operationState; private Long scheduleGeneration; private Long scheduleUserId; private LocalDateTime nextRunTime; @TableField(typeHandler=JsonbStringTypeHandler.class) private String scheduleSnapshotJson; }
