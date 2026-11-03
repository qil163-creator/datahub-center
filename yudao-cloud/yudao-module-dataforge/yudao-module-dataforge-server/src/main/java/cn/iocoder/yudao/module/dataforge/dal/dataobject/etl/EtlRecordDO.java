package cn.iocoder.yudao.module.dataforge.dal.dataobject.etl;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 可视化 ETL 流程执行记录 DO
 *
 * config_json 保存运行时的流程快照（节点/连线/参数），执行按快照进行，防止运行中修改流程
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_etl_record", autoResultMap = true)
@KeySequence("dh_dataforge_etl_record_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EtlRecordDO extends BaseDO {

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

    /** 本次绑定的质量任务（来自发布快照）。 */
    private Long qualityTaskId;

    public static final int RUN_STATUS_UNKNOWN = 6;

    private Integer pipelineRevision;

    private Long executionUserId;

    private String qualityStatus;

    private Long qualityInstanceId;

    private String executionToken;

    private LocalDateTime leaseUntil;

    private Boolean stopRequested;


    public static final int RUN_STATUS_WAITING = 1;

    public static final int RUN_STATUS_RUNNING = 2;

    public static final int RUN_STATUS_SUCCESS = 3;

    public static final int RUN_STATUS_FAILED = 4;

    public static final int RUN_STATUS_STOPPED = 5;

    /** 手动执行 */
    public static final int EXECUTE_TYPE_HAND = 1;

    /** 自动调度 */
    public static final int EXECUTE_TYPE_AUTO = 2;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 流程编号
     */
    private Long pipelineId;

    /**
     * 流程名称（冗余，日志页直接展示）
     */
    private String name;

    /**
     * 运行状态：1-等待 2-运行中 3-成功 4-失败 5-停止 6-结果待核查
     */
    private Integer runStatus;

    /**
     * 执行方式：1-手动 2-自动调度
     */
    private Integer executeType;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 流程级聚合日志
     */
    private String log;

    /**
     * 运行时流程快照（nodes/edges/parameters）
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String configJson;

    /**
     * 运行观测快照：节点实时读写行数、连线队列与等待时间、有界吞吐趋势，按采样间隔覆盖写入；
     * 历史运行为空表示未记录。默认查询不加载，仅日志接口单独读取。
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class, select = false)
    private String runtimeMetricsJson;

    /**
     * 所属机构编号
     */
    private Long orgId;

}
