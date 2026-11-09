package cn.iocoder.yudao.module.dataforge.dal.dataobject.dev;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import com.baomidou.mybatisplus.annotation.TableField;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;

import java.time.LocalDateTime;

/**
 * 数据开发-任务执行实例 DO
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_dev_record", autoResultMap = true)
@KeySequence("dh_dataforge_dev_record_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevRecordDO extends BaseDO {

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String taskType;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean nativeProgram;

    public static final String STATUS_WAITING = "WAITING";

    public static final String STATUS_SUBMITTING = "SUBMITTING";

    public static final String STATUS_UNKNOWN = "UNKNOWN";

    public static final String STATUS_CANCEL_REQUESTED = "CANCEL_REQUESTED";

    private java.time.LocalDate businessDate;

    private String runKey;

    private Long versionId;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String snapshotJson;

    private Long executionUserId;

    private Long dsProjectCode;

    private Long dsWorkflowCode;

    private Long dsTriggerCode;

    private String submissionStage;

    private String leaseOwner;

    private LocalDateTime leaseUntil;

    private LocalDateTime heartbeatTime;

    private Boolean cancelRequested;

    private String lastError;

    private String qualityStatus;

    private Long qualityInstanceId;

    private Long resolvedBy;

    private java.time.LocalDateTime resolvedAt;

    private String resolutionReason;


    /** 执行状态 */
    public static final String STATUS_RUNNING = "RUNNING";

    public static final String STATUS_SUCCESS = "SUCCESS";

    public static final String STATUS_FAILED = "FAILED";

    public static final String STATUS_STOPPED = "STOPPED";

    public static final java.util.Set<String> ACTIVE_STATUSES = java.util.Set.of(
            STATUS_WAITING, STATUS_SUBMITTING, STATUS_RUNNING, STATUS_UNKNOWN, STATUS_CANCEL_REQUESTED);


    /** 触发方式 */
    public static final String TRIGGER_MANUAL = "MANUAL";

    public static final String TRIGGER_SCHEDULE = "SCHEDULE";

    /** 补数据批次按业务日期逐日登记的运行 */
    public static final String TRIGGER_BACKFILL = "BACKFILL";

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 任务编号
     */
    private Long taskId;

    /**
     * 任务名称（冗余）
     */
    private String taskName;

    /**
     * 数据源编号
     */
    private Long databaseId;

    /**
     * 数据源名称（冗余）
     */
    private String datasourceName;

    /**
     * 触发方式：MANUAL-手动 SCHEDULE-调度
     */
    private String triggerType;

    /**
     * 执行状态：RUNNING/SUCCESS/FAILED/STOPPED
     */
    private String status;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 执行耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 语句总数
     */
    private Integer statementTotal;

    /**
     * 成功语句数
     */
    private Integer statementSuccess;

    /**
     * 累计影响行数
     */
    private Long affectedRows;

    /**
     * DolphinScheduler 工作流实例编号（集群任务用）
     */
    private Integer dsInstanceId;

    /**
     * 执行日志
     */
    private String log;

}
