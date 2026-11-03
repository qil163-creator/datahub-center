package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据治理-数据质量-任务实例 DO
 *
 * 一次任务运行的实例记录
 *
 * @author Li
 */
@TableName("dh_governance_quality_task_instance")
@KeySequence("dh_governance_quality_task_instance_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityTaskInstanceDO extends BaseDO {

    /** 运行类型 - 手动 */
    public static final String RUN_TYPE_MANUAL = "MANUAL";

    /** 运行类型 - 调度 */
    public static final String RUN_TYPE_SCHEDULE = "SCHEDULE";

    /** 实例状态 - 排队 */
    public static final String STATUS_QUEUED = "QUEUED";

    /** 实例状态 - 运行中 */
    public static final String STATUS_RUNNING = "RUNNING";

    /** 实例状态 - 成功（流程完成，不等于规则全部通过） */
    public static final String STATUS_SUCCESS = "SUCCESS";

    /** 实例状态 - 失败 */
    public static final String STATUS_FAILED = "FAILED";

    /** 实例状态 - 结果未知 */
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    @TableId
    private Long id;

    /** 任务ID */
    private Long taskId;

    /** 研发运行幂等键（全局唯一） */
    private String requestKey;

    /** 已授权的执行身份 */
    private Long executionUserId;

    /** 检查对象及规则的冻结快照，不含连接凭据 */
    private String definitionSnapshot;

    /** 运行心跳租约 */
    private LocalDateTime leaseUntil;

    /** 实例名称 */
    private String instanceName;

    /** 运行类型：MANUAL/SCHEDULE */
    private String runType;

    /** 实例状态：RUNNING/SUCCESS/FAILED */
    private String instanceStatus;

    /** 调度时间 */
    private LocalDateTime scheduleTime;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 运行耗时（毫秒） */
    private Long durationMs;

    /** 运行次数 */
    private Integer runCount;

    /** 错误标识 */
    private String errorFlag;

    /** 执行用户 */
    private String execUser;

    /** 主机 */
    private String host;

    /** 检测总条数 */
    private Long totalCount;

    /** 通过条数 */
    private Long passCount;

    /** 失败条数 */
    private Long failCount;

    /** 通过率 */
    private BigDecimal passRate;

    /** 质量评分 */
    private BigDecimal score;

}
