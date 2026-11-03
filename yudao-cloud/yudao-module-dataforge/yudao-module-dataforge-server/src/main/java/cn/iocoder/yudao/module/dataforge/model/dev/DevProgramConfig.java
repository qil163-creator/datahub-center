package cn.iocoder.yudao.module.dataforge.model.dev;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/** 双引擎程序定义。仅保存受管资源引用与参数，不承载连接凭据或执行授权。 */
@Data
@Schema(description = "数据开发引擎程序配置；原生运行使用管理员配置的受管环境")
public class DevProgramConfig {

    @NotNull
    @Min(1)
    @Max(1)
    private Integer schemaVersion = 1;

    @NotNull
    @Pattern(regexp = "BATCH|STREAMING")
    private String workloadMode = "BATCH";

    @Size(max = 100)
    private String runtimeProfileCode;

    @Size(max = 100)
    private String engineVersion;

    @Size(max = 500)
    private String entryPoint;

    @Positive
    private Long artifactFileId;

    @Pattern(regexp = "[a-fA-F0-9]{64}")
    private String artifactSha256;

    @Size(max = 100)
    private List<@NotNull @Size(max = 2048) String> arguments;

    @Size(max = 100)
    private List<@NotNull @Positive Long> dependencyFileIds;

    @Size(max = 100)
    private String pythonEnvironment;

    @Min(1)
    @Max(65536)
    private Integer parallelism;

    @Min(1)
    @Max(1024)
    private Integer taskManagerSlots;

    @Min(1)
    @Max(10000)
    private Integer taskManagerCount;

    @Min(128)
    @Max(1048576)
    private Integer jobManagerMemoryMb;

    @Min(128)
    @Max(1048576)
    private Integer taskManagerMemoryMb;

    @Min(128)
    @Max(1048576)
    private Integer driverMemoryMb;

    @Min(128)
    @Max(1048576)
    private Integer executorMemoryMb;

    @Min(1)
    @Max(1024)
    private Integer driverCores;

    @Min(1)
    @Max(1024)
    private Integer executorCores;

    @Min(1)
    @Max(10000)
    private Integer numExecutors;

    @Size(max = 100)
    private String queue;

    @Min(100)
    @Max(86400000)
    private Integer checkpointIntervalMs;
}
