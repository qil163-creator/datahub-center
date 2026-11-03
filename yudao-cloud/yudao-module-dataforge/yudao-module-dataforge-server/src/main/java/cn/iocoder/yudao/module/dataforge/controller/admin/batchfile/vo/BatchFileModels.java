package cn.iocoder.yudao.module.dataforge.controller.admin.batchfile.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 批量文件入库的浏览器合同；不包含存储凭据和数据库连接信息。 */
public final class BatchFileModels {

    /**
     * 限制批次文件模型工具的实例化，统一通过静态能力使用。
     */
    private BatchFileModels() {
    }

    @Data
    public static class Field {

        @NotNull
        @Min(0)
        @Max(255)
        private Integer sourceIndex;

        @NotBlank
        @Size(max = 64)
        private String name;

        @NotBlank
        @Pattern(regexp = "string|bigint|int|decimal|double|date|datetime|boolean")
        private String type;
    }

    @Data
    public static class FileSelection {

        @NotNull
        private Long id;

        @NotEmpty
        @Size(max = 256)
        @Valid
        private List<Field> fields = new ArrayList<>();
    }

    @Data
    public static class Target {

        @NotBlank
        @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]{0,63}")
        private String name;

        @NotEmpty
        @Size(max = 20)
        private List<@NotNull Long> fileIds = new ArrayList<>();
    }

    @Data
    public static class Save {

        private Long id;

        private Long revision;

        @NotBlank
        @Size(max = 100)
        private String taskName;

        @NotNull
        private Long responsibleUserId;

        @Size(max = 256)
        private String description;

        @Size(max = 256)
        private String remark;

        @NotNull
        private Long targetDatabaseId;

        @NotEmpty
        @Size(max = 20)
        @Valid
        private List<FileSelection> files = new ArrayList<>();

        @NotEmpty
        @Size(max = 20)
        @Valid
        private List<Target> targets = new ArrayList<>();
    }

    @Data
    public static class File {

        private Long id;

        private String name;

        private String type;

        private String source;

        private Long connectionId;

        private String path;

        private Long size;

        private String sha256;

        private String parseStatus = "PARSED";

        private Integer rowCount;

        private List<Field> fields = new ArrayList<>();

        private String targetTable;

        private Integer writeOrder;

        private String lastStatus;

        private Long writtenRows;
    }

    @Data
    public static class Task {

        private Long id;

        private Long revision;

        private String taskName;

        private Long responsibleUserId;

        private String responsibleName;

        private String responsiblePhone;

        private String description;

        private String remark;

        private Long targetDatabaseId;

        private String targetDatabaseName;

        private Boolean online;

        private Integer fileCount;

        private Integer targetCount;

        private String lastStatus;

        private Long lastRunId;

        private LocalDateTime lastRunTime;

        private LocalDateTime lastRunResolvedTime;

        private LocalDateTime createTime;

        private LocalDateTime updateTime;

        private String creator;

        private Integer successFiles;

        private Integer failedFiles;

        private List<File> files = new ArrayList<>();

        private List<Target> targets = new ArrayList<>();
    }

    @Data
    public static class RunItem {

        private Long fileId;

        private String fileName;

        private String targetTable;

        private Integer writeOrder;

        private String status;

        private Long writtenRows = 0L;

        private String errorMessage;
    }

    @Data
    public static class Run {

        private Long id;

        private Long taskId;

        private String requestId;

        private String status;

        private String executionType = "MANUAL";

        private Long resolvedBy;

        private LocalDateTime resolvedTime;

        private String resolutionReason;

        private Long executionUserId;

        private LocalDateTime startTime;

        private LocalDateTime endTime;

        private Integer fileCount;

        private Integer successCount;

        private Integer failedCount;

        private Long writtenRows;

        private String errorMessage;

        private LocalDateTime createTime;

        private List<RunItem> items = new ArrayList<>();
    }

    @Data
    public static class Summary {

        private Long taskCount;

        private Long successCount;

        private Long failedCount;

        private Long runCount;

        private LocalDateTime statisticsTime;
    }

    @Data
    public static class DatabaseOption {

        private Long id;

        private String name;

        private String productId = "MYSQL";
    }

    @Data
    public static class UserOption {

        private Long id;

        private String nickname;

        private String mobile;
    }

    public record Limits(int maxFiles, long maxFileBytes, long maxTotalBytes, int maxRows, int maxColumns) {

    }

    public record Options(List<DatabaseOption> databases, List<UserOption> users, Limits limits,
                          List<String> fieldTypes) {

    }

    public record Receipt(Long id, String status, boolean replayed) {

    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Page extends PageParam {

        private String taskName;

        private String lastStatus;

        @Pattern(regexp = "id|fileCount|targetCount|updateTime")
        private String sortField;

        @Pattern(regexp = "asc|desc")
        private String sortOrder;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class RunPage extends PageParam {

        @NotNull
        private Long taskId;
    }

    @Data
    public static class SystemFile {

        @NotNull
        private Long connectionId;

        @NotBlank
        @Size(max = 2048)
        private String path;
    }

    @Data
    public static class Status {

        @NotNull
        private Long id;

        @NotNull
        private Long revision;

        @NotNull
        private Boolean online;
    }

    @Data
    public static class Execute {

        @NotNull
        private Long id;

        @NotBlank
        @Pattern(regexp = "[a-fA-F0-9-]{36}")
        private String requestId;
    }

    @Data
    public static class Resolve {

        @NotNull
        private Long id;

        @NotBlank
        @Size(max = 500)
        private String reason;

        @AssertTrue
        @NotNull
        private Boolean confirmedStopped;

        @AssertTrue
        @NotNull
        private Boolean outputReviewed;
    }
}
