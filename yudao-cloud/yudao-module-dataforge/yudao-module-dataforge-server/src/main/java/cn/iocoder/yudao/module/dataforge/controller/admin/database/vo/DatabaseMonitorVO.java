package cn.iocoder.yudao.module.dataforge.controller.admin.database.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/** 数据库观测契约；行数和精确阈值使用十进制字符串，时间沿用全局毫秒时间戳。 */
public final class DatabaseMonitorVO {

    /**
     * 限制数据源监控工具的实例化，统一通过静态能力使用。
     */
    private DatabaseMonitorVO() {
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Schema(description = "管理后台 - 数据库观测分页请求")
    public static class PageReq extends PageParam {

        @NotNull
        @Positive
        private Long databaseId;
    }

    @Data
    @Schema(description = "管理后台 - 数据库监控概览")
    public static class Overview {

        private Integer connectionStatus;

        private LocalDateTime lastCollectedTime;

        private String collectionStatus;

        private String collectionMessage;

        private Long responseTimeMs;

        private String todayOutputRows;

        private String todayInputRows;

        private String totalTransferRows;

        private Long pendingAlarmCount;

        private Double averageResponseTimeMs;

        /** 已完成同步任务平均行吞吐，非数据库物理 IO。 */
        private Double readSpeed;

        private Double writeSpeed;

        private String throughputUnit = "rows/s";

        /** 已用字节；未知保持空值。数据库通常没有固定总容量。 */
        private String capacityUsed;

        private String capacityTotal;

        private List<String> supportedMetrics = List.of("CONNECTION_STATUS", "RESPONSE_TIME");

        private String logCoverage = "统计可确认关联的同步任务和 SQL 执行记录，不含 ETL 和数据库外部操作；同步行数不代表数据库物理 IO。";
    }

    @Data
    public static class Trend {

        private Integer days;

        private List<TrendPoint> points;

        private List<String> supportedMetrics = List.of("CONNECTION_STATUS", "RESPONSE_TIME");
    }

    @Data
    public static class TrendPoint {

        private String date;

        private Double responseTimeMs;

        private String outputRows;

        private String inputRows;

        private Double onlineRate;

        private Double readSpeed;

        private Double writeSpeed;
    }

    @Data
    @Schema(description = "管理后台 - 数据库读写日志；未知方向的 SQL 影响行数不冒充输入输出")
    public static class ReadWriteLog {

        private String id;

        private Long recordId;

        private String taskName;

        private String description;

        private String sourceModule;

        private String outputRows;

        private String inputRows;

        private String status;

        private LocalDateTime createTime;

        private LocalDateTime startTime;

        private LocalDateTime endTime;

        private Long durationMs;
    }

    @Data
    @Schema(description = "管理后台 - 数据库告警规则保存请求")
    public static class RuleSave {

        private Long id;

        @NotNull
        @Positive
        private Long databaseId;

        @NotBlank
        @Size(max = 100)
        private String name;

        @Size(max = 500)
        private String description;

        @NotNull
        @Pattern(regexp = "CONNECTION_STATUS|RESPONSE_TIME")
        private String metric;

        @NotNull
        @Pattern(regexp = "EQ|GT|GTE|LT|LTE")
        private String operator;

        @NotNull
        @Pattern(regexp = "[0-9]{1,12}(\\.[0-9]{1,6})?")
        private String threshold;

        @NotNull
        @Pattern(regexp = "GENERAL|SERIOUS|URGENT")
        private String level;

        @NotNull
        @Min(0)
        @Max(1)
        private Integer status;

        @NotBlank
        @Size(max = 100)
        private String owner;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @Schema(description = "管理后台 - 数据库告警规则响应")
    public static class Rule extends RuleSave {

        private String creator;

        private LocalDateTime createTime;

        private LocalDateTime updateTime;
    }

    @Data
    public static class RuleStatus {

        @NotNull
        @Positive
        private Long databaseId;

        @NotNull
        @Positive
        private Long id;

        @NotNull
        @Min(0)
        @Max(1)
        private Integer status;
    }

    @Data
    public static class Alarm {

        private Long id;

        private Long databaseId;

        private Long ruleId;

        private String ruleName;

        private String description;

        private String metric;

        private String operator;

        private String threshold;

        private String currentValue;

        private String level;

        private String status;

        private LocalDateTime firstTime;

        private LocalDateTime lastTime;

        private Integer occurrences;

        private String handledBy;

        private LocalDateTime handledTime;

        private String handleRemark;
    }

    @Data
    public static class AlarmHandle {

        @NotNull
        @Positive
        private Long databaseId;

        @NotNull
        @Positive
        private Long id;

        @NotNull
        @Pattern(regexp = "HANDLE|IGNORE")
        private String action;

        @NotBlank
        @Size(max = 500)
        private String remark;
    }
}
