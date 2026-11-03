package cn.iocoder.yudao.module.governance.dal.dataobject.quality;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

/**
 * 数据治理-数据质量-质量任务 DO
 *
 * 检核任务 + 调度配置（融合 qData 调度运维 + I-BASE 检核任务）
 *
 * @author Li
 */
@TableName("dh_governance_quality_task")
@KeySequence("dh_governance_quality_task_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QualityTaskDO extends BaseDO {

    /** 任务状态 - 上线 */
    public static final Integer STATUS_ONLINE = 0;

    /** 任务状态 - 下线 */
    public static final Integer STATUS_OFFLINE = 1;

    /** 调度类型 - 手动 */
    public static final String SCHEDULE_MANUAL = "MANUAL";

    /** 调度类型 - 定时 */
    public static final String SCHEDULE_TIMER = "TIMER";

    /** 执行策略 - 串行 */
    public static final String STRATEGY_SERIAL = "SERIAL";

    /** 执行策略 - 并行 */
    public static final String STRATEGY_PARALLEL = "PARALLEL";

    @TableId
    private Long id;

    /** 任务名称 */
    private String taskName;

    /** 任务编码 */
    private String taskCode;

    /** 任务分类（数据质量类目） */
    private String taskCategory;

    /** 执行策略：SERIAL串行/PARALLEL并行 */
    private String executeStrategy;

    /** 调度类型：MANUAL手动/TIMER定时 */
    private String scheduleType;

    /** cron表达式 */
    private String cronExpression;

    /** 时间窗（如 01:00-02:00） */
    private String timeWindow;

    /** 任务状态：0-上线 1-下线 */
    private Integer taskStatus;

    /** 任务优先级：HIGH/MID/LOW */
    private String priority;

    /** 失败重试次数 */
    private Integer retryCount;

    /** 重试间隔（分钟） */
    private Integer retryInterval;

    /** 延迟执行时间（分钟） */
    private Integer delayExecTime;

    /** Worker分组 */
    private String workerGroup;

    /** 关联规则集ID */
    private Long ruleSetId;

    /** 机构标识字段（如 org_code） */
    private String orgField;

    /** 通用分组字段（如 dept_code） */
    private String groupField;

    /** 负责人用户ID */
    private Long ownerUserId;

    /** 描述 */
    private String description;

    /** 备注 */
    private String remark;

}
