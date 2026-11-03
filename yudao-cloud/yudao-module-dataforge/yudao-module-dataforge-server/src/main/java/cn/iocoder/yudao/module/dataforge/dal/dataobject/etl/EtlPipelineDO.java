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
 * 可视化 ETL 生产流程 DO
 *
 * 参考 srt-cloud 的 data_prod_pipeline：画布节点与连线整体以 JSON 保存（LogicFlow 结构），
 * 执行时由 starter 转换为 DAG
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_etl_pipeline", autoResultMap = true)
@KeySequence("dh_dataforge_etl_pipeline_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EtlPipelineDO extends BaseDO {

    /** 可选连接环境，空值沿用节点的物理数据源配置。 */
    private Long environmentId;

    /** 成果质量任务编号（选填）。 */
    private Long qualityTaskId;

    /** 配置乐观锁版本。 */
    private Integer revision;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String publishedConfigJson;

    private Integer publishedRevision;

    private Long publishedExecutionUserId;

    /** 调度身份代次，修改发布或调度状态时递增。 */
    private Long scheduleGeneration;

    /** 最近领取的运行记录；旧执行不得覆盖较新记录的摘要。 */
    private Long latestRunId;


    public static final int STATUS_UNRELEASED = 0;

    public static final int STATUS_RELEASED = 1;

    /** 调度关闭 */
    public static final int SCHEDULE_OFF = 0;

    /** 调度开启 */
    public static final int SCHEDULE_ON = 1;

    /** 一次性任务 */
    public static final int TASK_TYPE_ONCE = 1;

    /** 周期任务 */
    public static final int TASK_TYPE_PERIODIC = 2;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 流程名称
     */
    private String name;

    /**
     * 调度类型：1-一次性 2-周期性
     */
    private Integer taskType;

    /**
     * Cron 表达式（周期任务）
     */
    private String cron;

    /**
     * 下次执行时间（周期任务调度扫描用）
     */
    private LocalDateTime nextRunTime;

    /**
     * 任务状态：0-未上线 1-已上线（上线后锁定配置，一次性任务上线即执行一次）
     */
    private Integer status;

    /**
     * 调度状态：0-关闭 1-开启（仅周期任务；开启后按 Cron 排期，需先上线任务）
     */
    private Integer scheduleStatus;

    /**
     * 最近运行状态：1-等待 2-运行中 3-成功 4-失败 5-停止
     */
    private Integer runStatus;

    /**
     * 最近运行开始时间
     */
    private LocalDateTime startTime;

    /**
     * 最近运行结束时间
     */
    private LocalDateTime endTime;

    /**
     * 发布时间
     */
    private LocalDateTime releaseTime;

    /**
     * 发布人编号
     */
    private Long releaseUserId;

    /**
     * 描述
     */
    private String description;

    /**
     * 画布节点 JSON 数组（LogicFlow 节点）
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String nodesJson;

    /**
     * 画布连线 JSON 数组（LogicFlow 连线）
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String edgesJson;

    /**
     * 流程参数定义 JSON 数组：[{name,type,defaultValue,description}]
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String paramsJson;

    /**
     * 所属机构编号
     */
    private Long orgId;

}
