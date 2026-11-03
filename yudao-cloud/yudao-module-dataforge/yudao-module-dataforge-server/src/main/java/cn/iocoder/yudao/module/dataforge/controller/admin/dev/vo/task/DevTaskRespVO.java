package cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 开发任务 Response VO
 *
 * @author Li
 */
@Schema(description = "管理后台 - 开发任务 Response VO")
@Data
public class DevTaskRespVO {

    @Schema(description = "成果质量任务编号", example = "1")
    private Long qualityTaskId;

    @Schema(description = "上游任务编号列表")
    private java.util.List<Long> dependencyTaskIds;

    @Schema(description = "任务定义乐观锁修订号", example = "3")
    private Integer revision;

    @Schema(description = "当前可执行保存版本编号", example = "100")
    private Long currentVersionId;

    @Schema(description = "经授权的执行身份", example = "1")
    private Long executionUserId;


    @Schema(description = "主键", example = "1")
    private Long id;

    @Schema(description = "目录编号", example = "1")
    private Long dirId;

    @Schema(description = "任务名称", example = "ods_水位日汇总")
    private String name;

    private Long ownerId;

    private String ownerName;

    private String contactNumber;

    private String scheduler;

    private String legacyCronExpression;

    private String scheduleSyncStatus;

    private String scheduleSyncReason;

    private cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig executionConfig;

    @Schema(description = "同一任务串行取得执行槽位")
    private String executionType = "SERIAL";

    private cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordRespVO latestRecord;

    @Schema(description = "任务类型", example = "SQL")
    private String taskType;

    @Schema(description = "运行模式；未配置引擎模式的历史任务返回 BATCH")
    private String workloadMode;

    @Schema(description = "引擎：FLINK/SPARK，历史通用任务为空")
    private String engine;

    @Schema(description = "程序形态：SQL/JAR/PYTHON，历史通用任务为空")
    private String programType;

    private cn.iocoder.yudao.module.dataforge.model.dev.DevProgramConfig programConfig;

    @Schema(description = "执行能力：STANDARD、LEGACY_SQL（兼容 SQL 通道）、NATIVE（受管原生运行）、NOT_READY（配置未就绪）")
    private String executionCapability;

    private String executionCapabilityReason;

    @Schema(description = "数据源编号", example = "1")
    private Long databaseId;

    @Schema(description = "数据源名称", example = "业务库")
    private String datasourceName;

    @Schema(description = "数据库名", example = "biz_db")
    private String databaseName;

    @Schema(description = "数据库产品类型", example = "MYSQL")
    private String dbType;

    @Schema(description = "任务脚本内容")
    private String content;

    @Schema(description = "执行超时（秒）", example = "300")
    private Integer timeoutSeconds;

    @Schema(description = "查询结果最大返回行数", example = "1000")
    private Integer maxRows;

    @Schema(description = "DolphinScheduler 数据源编号（集群任务）", example = "1")
    private Integer dsDatasourceId;

    @Schema(description = "DolphinScheduler 数据源类型（集群任务）", example = "SPARK")
    private String dsDatasourceType;

    @Schema(description = "DolphinScheduler 工作流定义 code", example = "1234567890")
    private Long dsWorkflowCode;

    @Schema(description = "任务描述")
    private String description;

    @Schema(description = "任务状态：0-下线 1-上线", example = "1")
    private Integer status;

    @Schema(description = "调度状态：0-关闭 1-开启", example = "0")
    private Integer scheduleStatus;

    @Schema(description = "调度 Cron 表达式", example = "0 0 2 * * ?")
    private String cronExpression;

    @Schema(description = "下次调度执行时间")
    private LocalDateTime nextRunTime;

    @Schema(description = "最新版本号", example = "3")
    private Integer latestVersion;

    @Schema(description = "创建人", example = "1")
    private String creator;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

}
