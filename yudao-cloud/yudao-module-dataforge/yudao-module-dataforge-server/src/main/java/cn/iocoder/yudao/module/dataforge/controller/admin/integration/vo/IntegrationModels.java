package cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 数据集成工作台的请求和响应契约，业务对象在平台内按接口权限共享。 */
public final class IntegrationModels {

    /**
     * 限制集成模型工具的实例化，统一通过静态能力使用。
     */
    private IntegrationModels() {
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Schema(description = "数据集成任务分页")
    public static class PageReq extends PageParam {

        @NotBlank
        @Pattern(regexp = "OFFLINE|REALTIME")
        private String mode;

        @Size(max = 200)
        private String name;

        private Long categoryId;

        @Min(0)
        @Max(1)
        private Integer status;

        @Pattern(regexp = "RUNNING|SUCCESS|FAILURE|IDLE")
        private String runStatus;

        private Long ownerId;

        @Pattern(regexp = "id")
        private String sortField;

        @Pattern(regexp = "asc|desc")
        private String sortOrder;

        private Boolean executedToday;

        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        private java.time.LocalDate runDateStart;

        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        private java.time.LocalDate runDateEnd;

        /**
         * 判断是否满足运行日期范围有效条件。
         *
         * @return 判断结果
         */
        @AssertTrue(message = "执行日期的开始日期不能晚于结束日期")
        public boolean isRunDateRangeValid() {
            return runDateStart == null || runDateEnd == null || !runDateStart.isAfter(runDateEnd);
        }
    }

    @Data
    @Schema(description = "数据集成引擎资源配置")
    public static class EngineConfig {

        @NotNull
        @Min(64)
        @Max(1048576)
        private Integer jvmInitialMemory = 1024;

        @NotNull
        @Min(64)
        @Max(1048576)
        private Integer jvmMaximumMemory = 2048;

        @NotNull
        @Min(1)
        @Max(1024)
        private Integer channel = 1;

        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        private Long byteSpeed = 0L;

        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        private Long recordSpeed = 0L;

        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        private Long errorLimit = 0L;

        @NotNull
        @Min(1)
        @Max(1024)
        private Integer driverCores = 1;

        @Pattern(regexp = "[1-9][0-9]{0,6}[mMgG]")
        private String driverMemory = "512m";

        @NotNull
        @Min(1)
        @Max(1024)
        private Integer executorCount = 1;

        @Pattern(regexp = "[1-9][0-9]{0,6}[mMgG]")
        private String executorMemory = "512m";

        @NotNull
        @Min(1)
        @Max(1024)
        private Integer executorCores = 1;

        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9_.-]*")
        private String yarnQueue = "";

        @Pattern(regexp = "[1-9][0-9]{0,6}[mMgG]")
        private String jobManagerMemory = "1G";

        @Pattern(regexp = "[1-9][0-9]{0,6}[mMgG]")
        private String taskManagerMemory = "2G";

        @NotNull
        @Min(1)
        @Max(1024)
        private Integer slots = 2;

        @NotNull
        @Min(1)
        @Max(1024)
        private Integer taskManagerCount = 2;

        @NotNull
        @Min(1)
        @Max(65536)
        private Integer parallelism = 2;
    }

    @Data
    @Schema(description = "保存数据集成基础信息，不包含流程编辑和运行时凭据")
    public static class SaveReq {

        private Long id;

        @Min(0)
        private Integer revision;

        @NotBlank
        @Pattern(regexp = "OFFLINE|REALTIME")
        private String mode;

        @NotNull
        @Positive
        private Long categoryId;

        @NotBlank
        @Size(max = 200)
        private String name;

        @NotNull
        @Positive
        private Long ownerId;

        @Min(0)
        @Max(1)
        private Integer status = 0;

        @NotBlank
        @Pattern(regexp = "DATAX|SPARK|FLINK")
        private String engine;

        @NotBlank
        @Pattern(regexp = "DOLPHIN_SCHEDULER")
        private String scheduler = "DOLPHIN_SCHEDULER";

        @Size(max = 100)
        private String cronExpression = "0 0 0 * * ?";

        @NotBlank
        @Pattern(regexp = "HIGHEST|HIGH|MEDIUM|LOW|LOWEST")
        private String priority = "HIGH";

        @NotNull
        @Min(0)
        @Max(100)
        private Integer retryCount = 0;

        @NotNull
        @Min(0)
        @Max(1440)
        private Integer retryInterval = 0;

        @NotNull
        @Min(0)
        @Max(1440)
        private Integer delayMinutes = 0;

        @NotBlank
        @Pattern(regexp = "PARALLEL|SERIAL_WAIT|SERIAL_DISCARD|SERIAL_PRIORITY")
        private String executionStrategy = "SERIAL_DISCARD";

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9_.-]+")
        private String workerGroup = "default";

        @Size(max = 256)
        private String description;

        @NotNull
        @Valid
        private EngineConfig engineConfig = new EngineConfig();
    }

    @Data
    @Schema(description = "数据集成任务；源系统最近运行摘要属于迁入快照，不表示本平台正在执行")
    public static class TaskResp {

        private Long id;

        private Integer revision;

        private String mode;

        private Long categoryId;

        private String categoryName;

        private String name;

        private Long ownerId;

        private String ownerName;

        private String contactNumber;

        private Integer status;

        private Integer scheduleStatus;

        private String engine;

        private String scheduler;

        private String cronExpression;

        private String priority;

        private Integer retryCount;

        private Integer retryInterval;

        private Integer delayMinutes;

        private String executionStrategy;

        private String workerGroup;

        private String description;

        private EngineConfig engineConfig;

        private String flowJson;

        private String configurationStatus;

        private Boolean executable;

        private Boolean runtimeAvailable = true;

        private String runtimeMessage;

        private Boolean activeRunning;

        private Long dsProjectCode;
        private Long dsDefinitionCode;
        private Integer dsScheduleId;

        private Boolean importedSnapshot;

        private String lastRunStatus;

        private LocalDateTime lastRunTime;

        private Long lastRunDuration;

        private String creatorName;

        private LocalDateTime createTime;

        private LocalDateTime updateTime;
    }

    @Data
    public static class FlowReq {
        @NotNull @Positive private Long id;
        @NotNull @Min(0) private Integer revision;
        @NotBlank @Size(max = 1000000) private String flowJson;
        @NotNull private Boolean draft = false;
    }

    @Data
    public static class FlowCheckResp {
        private Boolean valid;
        private java.util.List<String> errors = java.util.List.of();
    }

    @Data
    public static class StatusReq {

        @NotNull
        @Positive
        private Long id;

        @NotNull
        @Min(0)
        private Integer revision;

        @NotNull
        @Min(0)
        @Max(1)
        private Integer status;
    }

    @Data
    public static class ScheduleReq {

        @NotNull
        @Positive
        private Long id;

        @NotNull
        @Min(0)
        private Integer revision;

        @NotNull
        @Min(0)
        @Max(1)
        private Integer scheduleStatus;

        @Size(max = 100)
        private String cronExpression;
    }

    @Data
    public static class CategoryResp {

        private Long id;

        private Long parentId;

        private String name;

        private Integer sort;

        private Integer sortOrder;

        private Boolean validFlag;

        private String description;
    }

    @Data
    public static class OwnerResp {

        private Long id;

        private String name;

        private String contactNumber;
    }

    @Data
    public static class CategorySaveReq {

        private Long id;

        @NotNull
        @Min(0)
        private Long parentId;

        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = ".*[A-Za-z0-9一-龥].*", message = "类目名称至少包含一个汉字、字母或数字")
        private String name;

        @com.fasterxml.jackson.annotation.JsonAlias("sortOrder")
        @Min(0)
        @Max(100000)
        private Integer sort = 0;

        @NotNull
        private Boolean validFlag = true;

        @Size(max = 256)
        private String description;
    }

    @Data
    public static class StatisticsResp {

        private Boolean runtimeAvailable = true;

        private String runtimeMessage;

        private Long running = 0L;

        private Long todayFailed = 0L;

        private Long todayExecuted = 0L;

        private Integer todaySuccessRate = 0;

        private LocalDateTime statisticTime;

        private Boolean importedSnapshot = false;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class RecordPageReq extends PageParam {

        @NotNull
        @Positive
        private Long taskId;

        @Pattern(regexp = "RUNNING|SUCCESS|FAILURE|IDLE")
        private String status;
    }

    @Data
    public static class RecordStatisticsResp {

        private Long running;

        private Long success;

        private Long failed;

        private LocalDateTime statisticTime;
    }

    @Data
    public static class RecordResp {

        private Long id;

        private Long taskId;

        private String status;

        private String triggerType;

        private LocalDateTime startTime;

        private LocalDateTime endTime;

        private Long duration;

        private String executorName;

        private String message;

        private String taskName;

        private String executionType;

        private String currentNode;

        private String instanceName;

        private String commandType;

        private Long dsProjectCode;
        private Integer dsInstanceId;
    }
}
