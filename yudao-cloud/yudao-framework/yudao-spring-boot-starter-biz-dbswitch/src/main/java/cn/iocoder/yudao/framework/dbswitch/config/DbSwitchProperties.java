package cn.iocoder.yudao.framework.dbswitch.config;

import lombok.Data;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.driver.DriverBundleSpec;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.Map;

/**
 * dbswitch 组件的全局配置（Starter 配置入口）
 * <p>
 * 说明：任务级配置（源/目标连接、表映射等）仍由业务侧通过
 * {@code MigrationRequest} 按任务传入；调优参数由自动配置冻结为当前上下文的
 * {@code DbSwitchOptions}；驱动注册表仅接受服务端可信部署配置。
 * <p>
 * 配置示例：
 * <pre>
 * yudao:
 *   dbswitch:
 *     migration:
 *       max-cache-bytes: 134217728   # 单表批量缓存上限（默认 128MB）
 *       pipeline-write-enabled: true # 全量迁移读写流水线（默认开启）
 *       query-timeout-seconds: 3600  # JDBC 查询超时
 *     pool:
 *       minimum-idle: 0
 *       idle-timeout-ms: 300000
 *       validation-timeout-ms: 30000
 *       leak-detection-threshold-ms: 0
 * </pre>
 *
 * @author yudao
 */
@Data
@ConfigurationProperties(prefix = "yudao.dbswitch")
public class DbSwitchProperties {

    private Migration migration = new Migration();

    private Pool pool = new Pool();

    private Query query = new Query();

    @Data
    public static class Query {

        /** 上下文内全部已登记且尚未证明终止的查询操作上限。 */
        private int maximumOperations = 256;

        /** 同一已授权资源所有者的操作上限；业务适配层传入真实租户。 */
        private int maximumOperationsPerScope = 2;
    }

    /** 默认不覆盖 classpath 驱动；仅显式接入注册表的调用方使用此配置。 */
    private Map<ProductTypeEnum, Driver> drivers = new EnumMap<>(ProductTypeEnum.class);

    /** 具名且已验证的制品包；数据源只能选择这些部署 ID 中的一个。 */
    private Map<String, DriverProfile> driverProfiles = new java.util.LinkedHashMap<>();

    @Data
    @lombok.EqualsAndHashCode(callSuper = true)
    public static class DriverProfile extends Driver implements cn.iocoder.yudao.framework.dbswitch.driver.DriverProfileSpec {

        private ProductTypeEnum product;

        private boolean defaultForProduct;
    }

    /** 显式启用的源端代际协议；键是部署标签，绝不是业务授权。 */
    private Map<String, SourceGeneration> sourceGenerations = new java.util.LinkedHashMap<>();

    @Data
    public static class SourceGeneration implements ConfiguredSourceGenerations.Specification {

        private ProductTypeEnum product;

        private String targetFingerprint;

        private String schema;

        private String instanceId;

        private String generation;

        /** 必须显式确认：隔离恢复或克隆的源，并在恢复读取前旋转代际。 */
        private boolean restoreRotationRequired;
    }

    @Data
    public static class Driver implements DriverBundleSpec {

        /** 只读、版本化的单个 standalone JAR 绝对路径，不接受 URL 或目录。 */
        private String path;

        /** 必填，由部署方从可信发布物预先核验的 SHA-256，不是运行时自动信任。 */
        private String sha256;

        /** 缺省使用产品默认 JDBC 驱动类。 */
        private String driverClassName;

        /** 有序制品列表对应的可信部署根目录绝对路径。 */
        private String bundleRoot;

        /** 显式依赖顺序；绝不通过递归扫描目录发现。 */
        private java.util.List<DriverArtifact> artifacts = new java.util.ArrayList<>();

        /** LEASED 是此驱动可安全卸载的显式声明。 */
        private DriverBundleSpec.Lifecycle lifecycle = DriverBundleSpec.Lifecycle.PROCESS;
    }

    @Data
    public static class DriverArtifact implements DriverBundleSpec.Artifact {

        private String file;

        private String sha256;
    }

    @Data
    public static class Migration {

        /**
         * 单表迁移时批量缓存的最大字节数上限，默认 128MB
         */
        private long maxCacheBytes = 128L * 1024 * 1024;

        /**
         * 全量迁移是否启用读写流水线（读线程与写线程双缓冲并行），默认开启
         */
        private boolean pipelineWriteEnabled = true;

        /**
         * Doris/StarRocks 目标端是否优先使用 Stream Load（HTTP）批量写入，默认开启。
         * 已发送或结果未知的批次不得自动回退 JDBC 重发。
         */
        private boolean olapStreamLoadEnabled = true;

        /**
         * JDBC 查询超时时间（秒），默认 1 小时
         */
        private int queryTimeoutSeconds = 60 * 60;

        /** DIFF 在发出任何变更前完成双方有界排序和重复键检查，暂存内容使用临时密钥加密。 */
        private long diffSortMemoryBytes = 64L * 1024 * 1024;

        private long diffSortMaxSpillBytes = 8L * 1024 * 1024 * 1024;

        /** 同一进程、同一真实目录所有 DIFF 的总额度；不宣称跨进程共享磁盘配额。 */
        private long diffSortMaxSharedSpillBytes = 16L * 1024 * 1024 * 1024;

        /** 可信部署配置；目录须存在且可写，不能由迁移任务客户端指定。 */
        private String diffSortDirectory = java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
                .toString();
    }

    @Data
    public static class Pool {

        /** 全上下文物理连接硬上限，包含正在创建及隔离等待关闭的连接。 */
        private int maximumPhysicalConnections = 32;

        /** 长查询和搬数不得消耗的元数据保留额度。 */
        private int metadataReservedConnections = 4;

        /**
         * 迁移连接池最小空闲连接数，默认 0
         */
        private int minimumIdle = 0;

        /**
         * 空闲连接超时（毫秒），默认 5 分钟
         */
        private long idleTimeoutMs = 300_000L;

        /**
         * 连接有效性校验超时（毫秒），默认 30 秒
         */
        private long validationTimeoutMs = 30_000L;

        /**
         * 连接泄漏检测阈值（毫秒），0 表示关闭；排查连接未归还问题时可设为 60000
         */
        private long leakDetectionThresholdMs = 0L;
    }
}
