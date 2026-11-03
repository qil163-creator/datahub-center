package cn.iocoder.yudao.module.dataforge.controller.admin.job.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo.OpsInstanceModels.InstanceResp;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.List;

/** 作业编排为平台共享业务对象；读写和执行分别受接口权限约束。 */
public final class JobModels {
    private JobModels() { }
    @Data @EqualsAndHashCode(callSuper = true)
    public static class PageReq extends PageParam {
        @Size(max=200) private String name;
        private Long categoryId;
        private Long ownerId;
        @Min(0) @Max(1) private Integer status;
        @Pattern(regexp="WAITING|RUNNING|SUCCESS|FAILURE|STOPPED|UNKNOWN|IDLE") private String runStatus;
    }
    @Data public static class SaveReq {
        private Long id;
        @Min(0) private Integer revision;
        @NotNull @Positive private Long categoryId;
        @NotBlank @Size(max=200) private String name;
        @Positive private Long ownerId;
        @Size(max=50) private String ownerPhone;
        @NotBlank @Pattern(regexp="PARALLEL|SERIAL_WAIT|SERIAL_DISCARD|SERIAL_PRIORITY") private String strategy="PARALLEL";
        @Size(max=128) private String cronExpression;
        @Size(max=2000) private String description;
        @Size(max=1000000) private String flowJson;
    }
    @Data @EqualsAndHashCode(callSuper=true) public static class TaskResp extends SaveReq { private Long activeRunCount; private Long queuedRunCount; private Boolean hasActiveRuns; private Long scheduleGeneration; @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private java.time.LocalDateTime nextRunTime;
        private String categoryName;
        private String ownerName;
        private Integer status;
        private Integer scheduleStatus;
        private String configurationStatus;
        private String lastRunStatus;
        @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private LocalDateTime lastRunTime;
        private Long lastRunDuration;
        @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private LocalDateTime createTime;
        @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss") private LocalDateTime updateTime;
        private InstanceResp latestRecord;
    }
    @Data public static class StatusReq {
        @NotNull @Positive private Long id;
        @NotNull @Min(0) private Integer revision;
        @NotNull @Min(0) @Max(1) private Integer status;
    }
    @Data public static class ScheduleReq {
        @NotNull @Positive private Long id;
        @NotNull @Min(0) private Integer revision;
        @Size(max=128) private String cronExpression;
        @NotNull @Min(0) @Max(1) private Integer scheduleStatus;
    }
    @Data public static class RunReq {
        @NotNull @Positive private Long id;
        @NotNull @Min(0) private Integer revision;
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,64}") private String requestId;
    }
    @Data public static class FlowReq { @NotBlank @Size(max=1000000) private String flowJson; }
    @Data public static class CheckResp { private boolean valid; private boolean executable; private List<String> errors; }
    @Data public static class CategoryReq {
        private Long id;
        @NotNull @Min(0) private Long parentId=0L;
        @NotBlank @Size(max=100) private String name;
        @NotNull @Min(0) private Integer sort=0;
    }
    @Data @EqualsAndHashCode(callSuper=true) public static class CategoryResp extends CategoryReq { }
    @Data public static class TaskOption {
        private Long id;
        private String kind;
        private String name;
        private Long categoryId;
        private String categoryName;
        private String taskType;
        private String dbType;
        private Integer status;
        private String description;
        private boolean executable;
    }
    @Data @EqualsAndHashCode(callSuper=true) public static class RecordPageReq extends PageParam {
        @NotNull @Positive private Long taskId;
    }
}
