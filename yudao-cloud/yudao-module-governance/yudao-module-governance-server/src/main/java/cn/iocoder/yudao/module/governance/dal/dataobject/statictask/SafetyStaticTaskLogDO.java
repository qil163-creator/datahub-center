package cn.iocoder.yudao.module.governance.dal.dataobject.statictask;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 静态脱敏任务执行记录 DO
 *
 * @author Li
 */
@TableName("dh_governance_safety_static_task_log")
@KeySequence("dh_safety_static_task_log_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SafetyStaticTaskLogDO extends BaseDO {

    /** 触发方式：自动调度 */
    public static final Integer RUN_TYPE_SCHEDULE = 1;

    /** 触发方式：手动执行 */
    public static final Integer RUN_TYPE_MANUAL = 2;

    /** 运行状态：运行中 */
    public static final Integer RUN_STATUS_RUNNING = 2;

    /** 运行状态：成功 */
    public static final Integer RUN_STATUS_SUCCESS = 3;

    /** 运行状态：失败 */
    public static final Integer RUN_STATUS_FAILED = 4;

    /**
     * 记录编号
     */
    @TableId
    private Long id;

    /**
     * 任务编号（关联 dh_governance_safety_static_task.id）
     */
    private Long taskId;

    /**
     * 触发方式：1-自动调度 2-手动执行
     */
    private Integer runType;

    /**
     * 运行状态：2-运行中 3-成功 4-失败
     */
    private Integer runStatus;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 处理行数
     */
    private Long dataCount;

    /**
     * 脱敏字段数（本次任务配置的脱敏字段个数）
     */
    private Integer maskedColumnCount;

    /**
     * 错误信息（异常堆栈摘要）
     */
    private String errorInfo;

}
