package cn.iocoder.yudao.module.governance.dal.dataobject.metadata;

import cn.iocoder.yudao.framework.mybatis.core.dataobject.BaseDO;
import cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 数据治理-元数据采集任务 DO
 *
 * 采集引擎统一为「全量扫描 + 差异写入」，不设全量/增量模式开关；
 * 任务上下线（enabled）与调度启停（schedule_enabled）为两个独立开关
 *
 * @author Li
 */
@TableName(value = "dh_governance_meta_collect_task", autoResultMap = true)
@KeySequence("dh_governance_meta_collect_task_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaCollectTaskDO extends BaseDO {

    /** 可信离线导入的展示快照，不接受浏览器维护，不参与授权或连接执行。 */
    @com.baomidou.mybatisplus.annotation.TableField(typeHandler = cn.iocoder.yudao.module.governance.framework.mybatis.core.type.JsonbTypeHandler.class,
            updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String displaySnapshot;

    private Integer collectType;

    private String executeStrategy;

    private String filePath;

    private String datasourceName;

    private String ownerName;


    /** 采集范围：整个数据源 */
    public static final int SCOPE_ALL = 1;

    /** 采集范围：自定义 */
    public static final int SCOPE_CUSTOM = 2;

    /** 开关：关闭 */
    public static final int SWITCH_OFF = 0;

    /** 开关：开启 */
    public static final int SWITCH_ON = 1;

    /** 调度器：XXL-Job（默认） */
    public static final String SCHEDULER_XXL_JOB = "XXL_JOB";

    /** 调度器：DolphinScheduler（预留，P1 不启用） */
    public static final String SCHEDULER_DS = "DOLPHINSCHEDULER";

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 任务名称
     */
    private String name;

    /**
     * dataforge 数据源编号
     */
    private Long datasourceId;

    /**
     * 来源系统编号（dataforge 源系统管理）
     */
    private Long sourceSystemId;

    /**
     * 来源系统名称（展示快照）
     */
    private String sourceSystemName;

    /**
     * 数据库类型（展示冗余）
     */
    private String dbType;

    /**
     * 采集范围：1-整个数据源 2-自定义
     */
    private Integer scopeType;

    /**
     * 自定义范围 JSON：[{"db":"dwd","tables":["t1"]}]，tables 为空表示整库
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String scopeConfig;

    /**
     * 黑名单规则 JSON：["tmp_*","bak_*","dwd.t_debug"]
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String blacklist;

    /**
     * 调度周期 cron 表达式
     */
    private String cron;

    /**
     * 调度器：XXL_JOB（默认）；DOLPHINSCHEDULER 预留
     */
    private String scheduler;

    /**
     * DolphinScheduler 工作流编码（预留，P1 不启用）
     */
    private Long dsWorkflowCode;

    /**
     * DolphinScheduler 调度编号（预留，P1 不启用）
     */
    private Long dsScheduleId;

    /**
     * 任务上下线：0-下线 1-上线（发布/取消发布任务）
     */
    private Integer enabled;

    /**
     * 调度启停：0-停止 1-启动
     */
    private Integer scheduleEnabled;

    /**
     * 责任人用户编号
     */
    private Long ownerUserId;

    /**
     * 责任人电话
     */
    private String ownerPhone;

    /**
     * 责任部门编号
     */
    private Long ownerDeptId;

    /**
     * 最近运行时间
     */
    private LocalDateTime lastRunTime;

    /**
     * 下次运行时间（全局扫描 + CAS 推进）
     */
    private LocalDateTime nextRunTime;

    /**
     * 最近实例编号
     */
    private Long lastInstanceId;

    /**
     * 描述
     */
    private String description;

    /**
     * 备注
     */
    private String remark;

}
