package cn.iocoder.yudao.module.dataforge.model.dev;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/** 发布版本拥有的执行属性；凭据和任意 Worker 授权不属于任务配置。 */
@Data
@Schema(description = "数据开发任务执行属性")
public class DevExecutionConfig {

    @Pattern(regexp = "BATCH|STREAMING")
    @Schema(description = "任务页面分类；空值沿用 programConfig 的模式，不改变 JDBC 或原生引擎执行语义")
    private String workloadMode;

    @NotNull
    @Pattern(regexp = "HIGHEST|HIGH|MEDIUM|LOW|LOWEST")
    private String priority = "MEDIUM";

    @NotBlank
    @Size(max = 100)
    private String workerGroup = "default";

    @NotNull
    @Min(0)
    @Max(10)
    private Integer retryTimes = 0;

    @NotNull
    @Min(1)
    @Max(1440)
    private Integer retryIntervalMinutes = 1;

    @NotNull
    @Min(0)
    @Max(1440)
    private Integer delayMinutes = 0;

    @NotNull
    @Pattern(regexp = "AUTO|QUERY|NON_QUERY|PROCEDURE")
    private String sqlType = "AUTO";

    @NotNull
    @Pattern(regexp = ";|/")
    private String statementSeparator = ";";

    @Valid
    @NotNull
    @Size(max = 100)
    private List<@NotNull LocalParam> localParams = List.of();

    @Valid
    @Schema(description = "人工维护的任务表血缘，仅描述关系，不授予外部数据读写权限")
    private TableLineage tableLineage;

    @Data
    public static class TableLineage {

        @Valid
        @NotNull
        @Size(max = 100)
        private List<@NotNull TableReference> upstream = List.of();

        @Valid
        @NotNull
        @Size(max = 100)
        private List<@NotNull TableReference> downstream = List.of();
    }

    @Data
    public static class TableReference {

        @NotNull
        @Positive
        private Long databaseId;

        @Size(max = 128)
        @Pattern(regexp = "[^\\p{Cntrl}]*")
        private String schemaName;

        @NotBlank
        @Size(max = 255)
        @Pattern(regexp = "[^\\p{Cntrl}]+")
        private String tableName;
    }

    @Data
    public static class LocalParam {

        @NotBlank
        @Pattern(regexp = "[a-zA-Z_][a-zA-Z0-9_]{0,63}")
        private String prop;

        @NotNull
        @Pattern(regexp = "VARCHAR|INTEGER|LONG|DOUBLE|BOOLEAN|DATE|TIME|TIMESTAMP")
        private String type = "VARCHAR";

        @NotNull
        @Size(max = 2048)
        @Pattern(regexp = "(?s)^(?!.*\\$(?:\\{|\\[)).*$", message = "参数值必须是固定值，不能包含调度器参数或时间模板")
        private String value = "";
    }
}
