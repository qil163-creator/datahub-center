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
 * 数据开发-开发任务 DO
 *
 * @author Li
 */
@TableName(value = "dh_dataforge_dev_task", autoResultMap = true)
@KeySequence("dh_dataforge_dev_task_seq")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevTaskDO extends BaseDO {

    /**
     * 任务定义乐观锁修订号
     */
    private Integer revision;

    /**
     * 当前可执行保存版本编号；兼容读取历史快照中的旧字段。
     */
    @com.fasterxml.jackson.annotation.JsonAlias("publishedVersionId")
    private Long currentVersionId;

    /**
     * 经授权的执行身份
     */
    private Long executionUserId;

    /**
     * 成果质量任务编号
     */
    private Long qualityTaskId;

    /**
     * 上游任务编号列表（JSON 数组）
     */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String dependencyTaskIdsJson;


    /** 任务类型 - SQL 脚本，执行委托调度器 SQL 节点。 */
    public static final String TYPE_SQL = "SQL";

    /** 任务类型 - 存储过程（任务运行走调度器 SQL 节点） */
    public static final String TYPE_PROCEDURE = "PROCEDURE";

    /** 任务类型 - Shell 脚本（由调度器 Worker 执行） */
    public static final String TYPE_SHELL = "SHELL";

    /** Spark SQL；未配置程序参数的历史任务继续使用调度器 SQL 通道。 */
    public static final String TYPE_SPARK_SQL = "SPARK_SQL";

    /** Flink SQL；未配置程序参数的历史任务继续使用调度器 SQL 通道。 */
    public static final String TYPE_FLINK_SQL = "FLINK_SQL";

    public static final String TYPE_FLINK_JAR = "FLINK_JAR";

    public static final String TYPE_PYFLINK = "PYFLINK";

    public static final String TYPE_SPARK_JAR = "SPARK_JAR";

    public static final String TYPE_PYSPARK = "PYSPARK";

    /** 是否引擎任务；仅分类，不表示原生执行能力已经可用。 */
    public static boolean isClusterType(String taskType) {
        var type = cn.iocoder.yudao.module.dataforge.enums.dev.DevTaskTypeEnum.fromType(taskType);
        return type != null && type.isEngineTask();
    }

    /** 任务状态 - 下线 */
    public static final Integer STATUS_OFFLINE = 0;

    /** 任务状态 - 上线 */
    public static final Integer STATUS_ONLINE = 1;

    /** 调度状态 - 关闭 */
    public static final Integer SCHEDULE_OFF = 0;

    /** 调度状态 - 开启 */
    public static final Integer SCHEDULE_ON = 1;

    /**
     * 主键
     */
    @TableId
    private Long id;

    /**
     * 目录编号
     */
    private Long dirId;

    /**
     * 任务名称
     */
    private String name;

    private Long ownerId;

    private String ownerName;

    private String contactNumber;

    /** 项目 XXL-Job 控制面调度，运行仍委托 DolphinScheduler。 */
    private String scheduler;

    /** 原 XXL-Job 周期备份，不按 DS Quartz 语义执行。 */
    private String legacyCronExpression;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String executionConfigJson;

    /**
     * 任务类型：SQL（预留 SHELL/SPARK_SQL/FLINK_SQL）
     */
    private String taskType;

    /** 版本化的引擎程序定义，空值保留历史执行语义。 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String programConfigJson;

    /**
     * 数据源编号
     */
    private Long databaseId;

    /**
     * 数据源名称（冗余）
     */
    private String datasourceName;

    /**
     * 数据库名（冗余）
     */
    private String databaseName;

    /**
     * 数据库产品类型（冗余，决定 SQL 方言）
     */
    private String dbType;

    /**
     * 任务脚本内容
     */
    private String content;

    /**
     * 执行超时（秒）
     */
    private Integer timeoutSeconds;

    /**
     * 查询结果最大返回行数
     */
    private Integer maxRows;

    /**
     * DolphinScheduler 侧数据源编号（集群任务用）
     */
    private Integer dsDatasourceId;

    /**
     * DolphinScheduler 侧数据源类型（SPARK/HIVE/MYSQL/POSTGRESQL 等）
     */
    private String dsDatasourceType;

    /**
     * DolphinScheduler 工作流定义 code（首次提交时创建）
     */
    private Long dsWorkflowCode;

    /**
     * 任务描述
     */
    private String description;

    /**
     * 任务状态：0-下线 1-上线
     */
    private Integer status;

    /**
     * 调度状态：0-关闭 1-开启
     */
    private Integer scheduleStatus;

    /**
     * 调度 Cron 表达式
     */
    private String cronExpression;

    /**
     * 下次调度执行时间
     */
    private LocalDateTime nextRunTime;

    /**
     * 最新版本号
     */
    private Integer latestVersion;

}
