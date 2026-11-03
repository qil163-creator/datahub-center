package cn.iocoder.yudao.framework.etl.core.script;

import java.nio.file.Path;
import java.util.List;

/** 可信服务端配置。子 JVM 是故障隔离边界，并非操作系统沙箱。 */
public record EtlScriptWorkerPolicy(boolean enabled, List<Path> classpathEntries,
                                    int maxSourceBytes, int maxBatchBytes, int maxResultBytes, int maxBatchRows,
                                    int maxResultRows,
                                    int maxLogBytes, long startupTimeoutMillis, long batchTimeoutMillis,
                                    int heapMegabytes,
                                    Mode mode, Docker docker) {

    public enum Mode {CHILD_JVM, DOCKER}

    public record HostPathMapping(Path localRoot, String daemonRoot) {

        /**
         * 校验构造参数并初始化 {@code HostPathMapping}。
         */
        public HostPathMapping {
            if (localRoot == null || !localRoot.isAbsolute() || daemonRoot == null
                    || !daemonRoot.matches("/[A-Za-z0-9_./ -]*") || daemonRoot.contains("..") || daemonRoot.contains(
                    "//")) {
                throw new IllegalArgumentException("Invalid trusted worker host path mapping");
            }
        }
    }

    public record Docker(Path executable, String endpoint, String image, List<HostPathMapping> hostPathMappings,
                         boolean requireSwapLimit, long commandTimeoutMillis, long maxLifetimeMillis,
                         Recovery recovery) {

        /**
         * 使用给定参数初始化 {@code Docker}。
         */
        public Docker(Path executable, String endpoint, String image, List<HostPathMapping> hostPathMappings,
                boolean requireSwapLimit, long commandTimeoutMillis, long maxLifetimeMillis) {
            this(executable, endpoint, image, hostPathMappings, requireSwapLimit, commandTimeoutMillis,
                    maxLifetimeMillis, null);
        }

        /**
         * 使用给定参数初始化 {@code Docker}。
         */
        public Docker(Path executable, String endpoint, String image, List<HostPathMapping> hostPathMappings,
                boolean requireSwapLimit, long commandTimeoutMillis) {
            this(executable, endpoint, image, hostPathMappings, requireSwapLimit, commandTimeoutMillis, 300_000);
        }

        /**
         * 校验构造参数并初始化 {@code Docker}。
         */
        public Docker {
            hostPathMappings = List.copyOf(hostPathMappings);
            if (executable == null || !executable.isAbsolute() || endpoint == null
                    || !endpoint.matches(
                    "(?:tcp://(?:127\\.0\\.0\\.1|\\[::1\\]):[0-9]{1,5}|unix:///[A-Za-z0-9_./-]+|npipe:////\\./pipe/[A-Za-z0-9_.-]+)")
                    || image == null || !image.matches("[a-z0-9][a-z0-9._/:+-]*@sha256:[a-f0-9]{64}")
                    || hostPathMappings.size() > 8 || commandTimeoutMillis < 500 || commandTimeoutMillis > 5000
                    || maxLifetimeMillis < 1000 || maxLifetimeMillis > 300_000 || maxLifetimeMillis % 1000 != 0) {
                throw new IllegalArgumentException("Invalid trusted worker Docker configuration");
            }
        }
    }

    /** 可信的本地持久化目录，绝不挂载到脚本容器中。 */
    public record Recovery(Path directory, String serviceId, int maxRecords, boolean recoverOnStartup,
                           long reconciliationTimeoutMillis) {

        /**
         * 使用给定参数初始化 {@code Recovery}。
         */
        public Recovery(Path directory, String serviceId, int maxRecords, boolean recoverOnStartup) {
            this(directory, serviceId, maxRecords, recoverOnStartup, 10000);
        }

        /**
         * 校验构造参数并初始化 {@code Recovery}。
         */
        public Recovery {
            if (directory == null || !directory.isAbsolute() || directory.toString().startsWith("\\\\")
                    || serviceId == null || !serviceId.matches(
                    "[a-zA-Z0-9_-]{1,64}") || maxRecords < 4 || maxRecords > 256
                    || reconciliationTimeoutMillis < 1000 || reconciliationTimeoutMillis > 30000) {
                throw new IllegalArgumentException("Invalid trusted worker recovery configuration");
            }
        }
    }

    /** 现有调用方保留显式子 JVM 策略；绝不自动回退到 Docker。 */
    public EtlScriptWorkerPolicy(boolean enabled, List<Path> classpathEntries, int maxSourceBytes, int maxBatchBytes,
            int maxResultBytes, int maxBatchRows, int maxResultRows, int maxLogBytes,
            long startupTimeoutMillis, long batchTimeoutMillis, int heapMegabytes) {
        this(enabled, classpathEntries, maxSourceBytes, maxBatchBytes, maxResultBytes, maxBatchRows, maxResultRows,
                maxLogBytes, startupTimeoutMillis, batchTimeoutMillis, heapMegabytes, Mode.CHILD_JVM, null);
    }

    /**
     * 校验构造参数并初始化 {@code EtlScriptWorkerPolicy}。
     */
    public EtlScriptWorkerPolicy {
        classpathEntries = List.copyOf(classpathEntries);
        if (mode == null || mode == Mode.DOCKER && docker == null) {
            throw new IllegalArgumentException("ETL worker execution mode is invalid");
        }
        if (maxSourceBytes < 1 || maxSourceBytes > 1024 * 1024
                || maxBatchBytes < 1024 || maxBatchBytes > 8 * 1024 * 1024
                || maxResultBytes < 1024 || maxResultBytes > 8 * 1024 * 1024
                || maxBatchRows < 1 || maxBatchRows > 10_000 || maxResultRows < 1 || maxResultRows > 10_000
                || maxLogBytes < 1 || maxLogBytes > 64 * 1024
                || startupTimeoutMillis < 100 || startupTimeoutMillis > 300_000
                || batchTimeoutMillis < 100 || batchTimeoutMillis > 300_000
                || heapMegabytes < 64 || heapMegabytes > 512 || classpathEntries.size() > 32) {
            throw new IllegalArgumentException("ETL script worker resource configuration is invalid");
        }
    }

    /**
     * 构造未启用当前功能的配置。
     */
    public static EtlScriptWorkerPolicy disabled() {
        return defaults(false);
    }

    /** 适用于可信且具有独立隔离措施的部署。不继承应用类路径或环境。 */
    public static EtlScriptWorkerPolicy defaults(boolean enabled) {
        return new EtlScriptWorkerPolicy(enabled, List.of(), 1024 * 1024, 8 * 1024 * 1024,
                8 * 1024 * 1024, 10_000, 10_000, 64 * 1024, 30_000, 30_000, 256);
    }
}
