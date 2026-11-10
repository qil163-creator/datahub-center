package cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 运维管理只读查询契约，所有操作均使用平台共享范围并受接口权限约束。 */
public final class OpsInstanceModels {

    /**
     * 限制运维实例模型工具的实例化，统一通过静态能力使用。
     */
    private OpsInstanceModels() {
    }

    @Data
    public static class KindReq {

        @Schema(description = "实例页面类型：INTEGRATION 集成任务、DEVELOPMENT 数据开发、DATA_ACCESS 数据同步、FULL_SYNC 整库同步")
        @NotBlank
        @Pattern(regexp = "INTEGRATION|DEVELOPMENT|DATA_ACCESS|FULL_SYNC|JOB")
        private String kind;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class IdentityReq extends KindReq {

        @NotNull
        @Positive
        private Long id;

        @NotBlank
        @Pattern(regexp = "SOURCE|PLATFORM|WIZARD|MANAGED")
        private String origin = "SOURCE";
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class StatisticsReq extends KindReq {

        @Pattern(regexp = "(?:source|platform):[1-9][0-9]{0,18}")
        private String categoryId;

        @Pattern(regexp = "REALTIME|OFFLINE|API")
        private String taskType;

        /**
         * 判断是否满足分类编号有效条件。
         *
         * @return 判断结果
         */
        @AssertTrue(message = "类目编号超出有效范围")
        public boolean isCategoryIdValid() {
            return categoryIdValid(categoryId);
        }
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class PageReq extends PageParam {

        @NotBlank
        @Pattern(regexp = "INTEGRATION|DEVELOPMENT|DATA_ACCESS|FULL_SYNC|JOB")
        private String kind;

        @Size(max = 200)
        private String name;

        @Positive
        private Long taskId;

        @Pattern(regexp = "(?:source|platform):[1-9][0-9]{0,18}")
        private String categoryId;

        @Pattern(regexp = "REALTIME|OFFLINE|API")
        private String taskType;

        @Pattern(regexp = "WAITING|RUNNING|SUCCESS|FAILURE|UNKNOWN|IDLE|STOPPED")
        private String status;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate startDate;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate endDate;

        @Pattern(regexp = "id|startTime|endTime|createTime")
        private String sortField;

        @Pattern(regexp = "asc|desc")
        private String sortOrder;

        /**
         * 判断是否满足日期范围有效条件。
         *
         * @return 判断结果
         */
        @AssertTrue(message = "执行日期的开始日期不能晚于结束日期")
        public boolean isDateRangeValid() {
            return startDate == null || endDate == null || !startDate.isAfter(endDate);
        }

        /**
         * 判断是否满足分类编号有效条件。
         *
         * @return 判断结果
         */
        @AssertTrue(message = "类目编号超出有效范围")
        public boolean isCategoryIdValid() {
            return categoryIdValid(categoryId);
        }
    }

    /**
     * 判断分类编号有效条件是否成立。
     *
     * @param id 记录编号
     * @return 处理是否成功
     */
    private static boolean categoryIdValid(String id) {
        if (id == null) {
            return true;
        }
        try {
            return Long.parseLong(id.substring(id.indexOf(':') + 1)) > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Data
    @Schema(description = "运维实例；SOURCE 为源系统迁入快照，PLATFORM 为本平台真实执行记录")
    public static class InstanceResp {

        private Long id;

        private String origin;

        private String kind;

        private Long taskId;

        private String categoryId;

        private String taskName;

        private String instanceName;

        private String taskType;

        private String executionType;

        private String status;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @JsonDeserialize(using = OpsInstanceTimeDeserializer.class)
        private LocalDateTime startTime;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @JsonDeserialize(using = OpsInstanceTimeDeserializer.class)
        private LocalDateTime endTime;

        @Schema(description = "运行时长，单位秒；源运行中快照保持采集时的耗时")
        private Long duration;

        private Integer runCount;

        private Long dsProjectCode;
        private Long dsDefinitionCode;
        private Integer dsInstanceId;
        private String submissionStage;
        private String queueState;
        private String queueReason;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @JsonDeserialize(using = OpsInstanceTimeDeserializer.class)
        private LocalDateTime queuedTime;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @JsonDeserialize(using = OpsInstanceTimeDeserializer.class)
        private LocalDateTime scheduledTime;


        private String ownerName;

        private String creatorName;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @JsonDeserialize(using = OpsInstanceTimeDeserializer.class)
        private LocalDateTime createTime;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @JsonDeserialize(using = OpsInstanceTimeDeserializer.class)
        private LocalDateTime updateTime;

        private String description;

        private String flowJson;

        private java.util.List<InstanceResp> children;
    }

    /** 向导在受管调度器中的只读身份，不包含执行参数。 */
    @Data
    public static class WizardTarget {
        private Long projectCode;
        private Integer instanceId;
        private Long workflowCode;
    }

    @Data
    public static class CategoryResp {

        private String id;

        private String parentId;

        private String name;

        private Integer sort;
    }

    @Data
    public static class StatisticsResp {

        private Long running;

        private Long todayFailed;

        private Long todayExecuted;

        private Integer todaySuccessRate;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        @JsonDeserialize(using = OpsInstanceTimeDeserializer.class)
        private LocalDateTime statisticTime;

        private Boolean importedSnapshot;
    }

    @Data
    public static class LogResp {

        private String message;

        private Boolean available;

        private Boolean importedSnapshot;
    }
}
