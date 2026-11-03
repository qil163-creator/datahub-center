package cn.iocoder.yudao.framework.etl.config;

import cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputPolicy;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "yudao.etl")
public class EtlProperties {

    private FileOutput fileOutput = new FileOutput();

    private ScriptWorker scriptWorker = new ScriptWorker();

    @Data
    public static class ScriptWorker {

        /** 仅在部署了具有操作系统隔离措施的可信工作进程环境后启用。 */
        private boolean enabled;

        private List<String> classpathEntries = List.of();

        private int maxSourceBytes = 1024 * 1024;

        private int maxBatchBytes = 8 * 1024 * 1024;

        private int maxResultBytes = 8 * 1024 * 1024;

        private int maxBatchRows = 10_000;

        private int maxResultRows = 10_000;

        private int maxLogBytes = 64 * 1024;

        private long startupTimeoutMillis = 30_000;

        private long batchTimeoutMillis = 30_000;

        private int heapMegabytes = 256;

        private cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy.Mode mode =
                cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy.Mode.CHILD_JVM;

        private Docker docker = new Docker();
    }

    @Data
    public static class Docker {

        private String executable;

        private String endpoint;

        /** 预先可用的不可变镜像摘要；运行器从不拉取镜像。 */
        private String image;

        private List<HostPathMapping> hostPathMappings = List.of();

        private boolean requireSwapLimit = true;

        private long commandTimeoutMillis = 5000;

        /** 独立的操作系统监管超时，覆盖启动和所有批次。 */
        private long maxLifetimeMillis = 300_000;

        private DockerRecovery recovery = new DockerRecovery();
    }

    @Data
    public static class DockerRecovery {

        private boolean enabled;

        private String directory;

        private String serviceId;

        private int maxRecords = 64;

        private boolean recoverOnStartup = true;

        private long reconciliationTimeoutMillis = 10000;
    }

    @Data
    public static class HostPathMapping {

        private String localRoot;

        private String daemonRoot;
    }

    /**
     * 根据当前输入构建并返回 {@code cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy}。
     */
    public cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy scriptWorkerPolicy() {
        var value = scriptWorker;
        cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy.Docker dockerPolicy = null;
        if (value.getMode() == cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy.Mode.DOCKER) {
            var docker = value.getDocker();
            var recovery = docker.getRecovery();
            var recoveryPolicy = recovery.isEnabled()
                    ? new cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy.Recovery(
                    recovery.getDirectory() == null ? null : Path.of(recovery.getDirectory()), recovery.getServiceId(),
                    recovery.getMaxRecords(), recovery.isRecoverOnStartup(),
                    recovery.getReconciliationTimeoutMillis()) : null;
            dockerPolicy = new cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy.Docker(
                    docker.getExecutable() == null ? null : Path.of(docker.getExecutable()), docker.getEndpoint(),
                    docker.getImage(),
                    docker.getHostPathMappings().stream()
                            .map(mapping -> new cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy.HostPathMapping(
                                    Path.of(mapping.getLocalRoot()), mapping.getDaemonRoot())).toList(),
                    docker.isRequireSwapLimit(),
                    docker.getCommandTimeoutMillis(), docker.getMaxLifetimeMillis(), recoveryPolicy);
        }
        return new cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy(value.isEnabled(),
                value.getClasspathEntries().stream().map(Path::of).toList(), value.getMaxSourceBytes(),
                value.getMaxBatchBytes(),
                value.getMaxResultBytes(), value.getMaxBatchRows(), value.getMaxResultRows(), value.getMaxLogBytes(),
                value.getStartupTimeoutMillis(), value.getBatchTimeoutMillis(), value.getHeapMegabytes(),
                value.getMode(), dockerPolicy);
    }

    @Data
    public static class FileOutput {

        private List<String> allowedDirectories = List.of();

        private boolean replaceExisting;
    }

    /**
     * 根据当前输入构建并返回 {@code EtlFileOutputPolicy}。
     */
    public EtlFileOutputPolicy fileOutputPolicy() {
        return new EtlFileOutputPolicy(fileOutput.getAllowedDirectories().stream().map(Path::of).toList(),
                fileOutput.isReplaceExisting());
    }
}
