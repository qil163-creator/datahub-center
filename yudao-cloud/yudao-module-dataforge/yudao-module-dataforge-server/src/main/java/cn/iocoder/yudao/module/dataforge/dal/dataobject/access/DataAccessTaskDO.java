package cn.iocoder.yudao.module.dataforge.dal.dataobject.access;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据同步任务-执行记录 DO
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_access_task", autoResultMap = true)
@KeySequence("dh_dataforge_access_task_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataAccessTaskDO extends BaseDO {

    /** 运行协议版本；历史记录保持 0，新的可靠执行显式写 1。 */
    private Integer protocolVersion;

    /** 平台内稳定运行标识，与每次抢占的令牌分离。 */
    private String runKey;

    /** 首次受理命令摘要；重复请求不重读当前草稿。 */
    private String requestHash;

    /** 原计划触发时刻，补发从该时刻推进。 */
    private LocalDateTime scheduledTime;

    /** 发布或调度变更代次。 */
    private Long scheduleGeneration;

    /** 执行阶段 PREPARE/EXECUTION_STARTED/SUBMIT_SENT。 */
    private String executionStage;

    /** 本次领取的节点实例标识。 */
    private String leaseOwner;

    /** 独立执行占位；排队不占位，未知结果不得自动释放。 */
    private Boolean occupiesSlot;

    /** 冻结快照引用的平台数据源编号；NULL 表示旧运行的未知资源范围，不能按空集合处理。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String resourceIds;

    /** 实际执行时的数据源修订与表级结果；历史 NULL 不回填为可信同步证据。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String syncEvidence;

    /** 与实际执行绑定一起提交；空值不具备提交后顺序证明。 */
    private Long syncStartPosition;

    /** 首次接受的停止命令标识。 */
    private String stopRequestId;

    /** 停止命令摘要。 */
    private String stopRequestHash;

    /** 停止操作人。 */
    private Long stopActorId;

    /** 停止受理时间，不代表已终止。 */
    private LocalDateTime stopRequestedAt;

    /** 外部效果 NONE/COMPLETE/PARTIAL/UNKNOWN。 */
    private String effectState;

    /** 外部效果或退出证据仍需核查。 */
    private Boolean reviewRequired;

    /** 状态迁移版本，用于人工核查与异步对账的并发隔离。 */
    private Long rowVersion;

    /** 执行领取的累计尝试数。 */
    private Integer attemptCount;

    /** 等待任务最早可重试时间。 */
    private LocalDateTime nextAttemptAt;

    /** 独立对账令牌。 */
    private String reconcileToken;

    /** 对账租约截止时间。 */
    private LocalDateTime reconcileLeaseUntil;

    /** 连续对账失败次数。 */
    private Integer reconcileAttempts;

    /** 下次允许对账时间。 */
    private LocalDateTime nextReconcileAt;

    /** 人工核查的脱敏依据与结果。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String resolutionJson;

    private String executionToken;

    private LocalDateTime leaseUntil;

    private Boolean stopRequested;

    /** 受理时的不可变配置，仅保存数据源引用，不保存连接密码。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String configJson;

    private Long executionUserId;

    private Integer releaseRevision;

    /**
     * 主键id
     */
    @TableId
    private Long id;

    /**
     * 数据同步任务id
     */
    private Long dataAccessId;

    /**
     * 任务名称快照（便于记录页直接展示）
     */
    private String taskName;

    /**
     * 运行状态：1-等待中 2-运行中 3-正常结束 4-异常结束 5-结果待核查 6-停止
     *
     * 枚举 {@link cn.iocoder.yudao.module.dataforge.enums.access.AccessRunStatusEnum}
     */
    private Integer runStatus;

    /**
     * 触发方式：1-自动调度 2-手动执行
     *
     * 枚举 {@link cn.iocoder.yudao.module.dataforge.enums.access.AccessRunTypeEnum}
     */
    private Integer runType;

    /**
     * 执行编号（手动执行时用于停止）
     */
    private String executeNo;

    /**
     * Seatunnel 作业编号（engine_type=2 时有值）
     */
    private String seatunnelJobId;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 实时日志
     */
    private String realTimeLog;

    /**
     * 错误信息
     */
    private String errorInfo;

    /**
     * 同步数据量（行数）
     */
    private Long dataCount;

    /**
     * 成功表数量
     */
    private Long tableSuccessCount;

    /**
     * 失败表数量
     */
    private Long tableFailCount;

    /**
     * 同步数据大小（人性化格式）
     */
    private String byteCount;

    /**
     * 单位id
     */
    private Long orgId;

}
