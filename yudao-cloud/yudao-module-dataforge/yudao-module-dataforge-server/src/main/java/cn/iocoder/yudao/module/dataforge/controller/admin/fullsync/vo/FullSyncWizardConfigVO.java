package cn.iocoder.yudao.module.dataforge.controller.admin.fullsync.vo;

import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessSaveReqVO;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * 整库同步四步向导配置。
 *
 * <p>继承离线同步保存契约，避免向导和实际 DB Switch 配置出现两套字段语义。</p>
 */
@Schema(description = "管理后台 - 整库同步向导配置")
@Data
@EqualsAndHashCode(callSuper = true)
public class FullSyncWizardConfigVO extends DataAccessSaveReqVO {

    @Schema(description = "责任人编号，仅保存配置")
    @Positive
    private Long responsibleUserId;

    @Size(max = 100)
    private String responsibleName;

    @Size(max = 50)
    private String responsiblePhone;

    @Schema(description = "配置任务状态：0 未上线，1 已上线；不触发执行或发布")
    @Min(0)
    @Max(1)
    @NotNull
    private Integer taskStatus = 0;

    @Schema(description = "配置调度开关；不创建实际调度")
    @NotNull
    private Boolean scheduleEnabled = false;

    @Pattern(regexp = "MANUAL|CRON")
    @NotNull
    private String executionMethod = "MANUAL";

    @Schema(description = "错误后继续运行的配置；不改变既有执行器行为")
    @NotNull
    private Boolean errorContinue = true;

    @Pattern(regexp = "IGNORE|STOP|CONTINUE")
    @NotNull
    private String dirtyDataStrategy = "IGNORE";

    @Pattern(regexp = "SPARK|FLINK|DATAX")
    @NotNull
    private String engine = "SPARK";

    @Size(max = 64)
    @NotBlank
    private String executionStrategy = "PARALLEL";

    @Pattern(regexp = "HIGHEST|HIGH|MEDIUM|LOW|LOWEST")
    @NotNull
    private String taskPriority = "MEDIUM";

    @Size(max = 128)
    private String workerGroup = "default";

    @Min(0)
    @Max(1000)
    @NotNull
    private Integer retryTimes = 0;

    @Min(0)
    @Max(10080)
    @NotNull
    private Integer retryInterval = 1;

    @Min(0)
    @Max(10080)
    @NotNull
    private Integer delayTime = 0;

    @Positive
    @NotNull
    private Integer driverCores = 1;

    @Size(max = 32)
    private String driverMemory = "1G";

    @Positive
    @NotNull
    private Integer executorCount = 2;

    @Size(max = 32)
    private String executorMemory = "2G";

    @Positive
    @NotNull
    private Integer executorCores = 2;

    @Size(max = 128)
    private String yarnQueue = "default";

    @Size(max = 2000)
    private String partitionRule;

    @Size(max = 2000)
    private String bucketRule;

    @Min(1)
    @Max(100)
    @NotNull
    private Integer replicaCount = 1;

    @Valid
    @Size(max = 100)
    @NotNull
    private List<@NotNull AppendColumn> appendColumns = List.of();

    @Schema(description = "按来源表保存的界面属性，不进入执行配置")
    @Size(max = 10000)
    @NotNull
    private Map<String, Object> tableProperties = Map.of();

    @Data
    public static class AppendColumn {
        @Size(max = 128)
        private String name;
        @Size(max = 128)
        private String type;
        @Size(max = 2000)
        private String value;
        @Min(0)
        private Integer length;
        @Min(0)
        private Integer scale;
        @Size(max = 2000)
        private String defaultValue;
        @Size(max = 1000)
        private String description;
    }

    /** 执行模式：DBSWITCH 或 DOLPHIN_DATAX。 */
    @Schema(description = "执行模式：DBSWITCH 或 DOLPHIN_DATAX", example = "DBSWITCH")
    private String executionMode = "DBSWITCH";

    /** DolphinScheduler 项目编码；仅 Dolphin DataX 模式使用。 */
    @Schema(description = "DolphinScheduler 项目编码", example = "100")
    private Long dsProjectCode;

    /** DolphinScheduler Worker 分组；仅 Dolphin DataX 模式使用。 */
    @Schema(description = "DolphinScheduler Worker 分组", example = "default")
    private String dsWorkerGroup;

    /** DolphinScheduler 执行租户；按外部调度器协议处理。 */
    @Schema(description = "DolphinScheduler 执行租户编码", example = "tenant_default")
    private String dsTenantCode;

    /** 外部 DolphinScheduler 数据源编号（来源端），只记录引用编号，不保存连接凭据。 */
    @Schema(description = "DolphinScheduler 来源数据源编号", example = "11")
    private Long dsSourceDatasourceCode;

    /** 外部 DolphinScheduler 数据源编号（目标端），只记录引用编号，不保存连接凭据。 */
    @Schema(description = "DolphinScheduler 目标数据源编号", example = "12")
    private Long dsTargetDatasourceCode;

    /** DS 3.4 DATAX taskParams.rawScript 的完整 DataX 作业 JSON。 */
    @Schema(description = "DS 3.4 DATAX taskParams.rawScript 的 DataX 作业 JSON")
    @lombok.ToString.Exclude
    private String dataxJobJson;

    /** DS 节点执行优先级。 */
    @Schema(description = "DolphinScheduler 节点优先级", example = "MEDIUM")
    private String dsTaskPriority = "MEDIUM";

    /** DS 工作流执行策略。 */
    @Schema(description = "DolphinScheduler 工作流执行策略", example = "SERIAL_WAIT")
    private String dsExecutionType = "SERIAL_WAIT";
}

