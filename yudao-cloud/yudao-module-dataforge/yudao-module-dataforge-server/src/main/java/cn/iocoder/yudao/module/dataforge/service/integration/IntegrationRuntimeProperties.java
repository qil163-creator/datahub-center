package cn.iocoder.yudao.module.dataforge.service.integration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** 集成任务的受管 DS 环境；浏览器不能指定执行项目、系统用户或外部数据源编号。 */
@Data
@Component
@ConfigurationProperties(prefix = "dataforge.integration")
public class IntegrationRuntimeProperties {
    private Long dsProjectCode;
    private String tenantCode;
    private Long environmentCode = -1L;
    private Map<String, Long> engineEnvironmentCodes = Map.of();
    private Set<String> workerGroups = Set.of("default");
    /** 管理员确认已安装 DATAX_UPSERT 插件的工作组；事务钩子另由 V2 运行契约校验，默认不开放。 */
    private Set<String> dataxUpsertWorkerGroups = Set.of();
    private Map<Long, DatabaseBinding> databaseBindings = Map.of();
    private String sparkMaster = "yarn";
    private String flinkDeployMode = "CLUSTER";
    private String flinkJobManager;
    private int flinkRestartAttempts = 3;
    private long flinkRestartDelayMs = 1000;
    private String sparkInitializationSql = "";
    private String flinkInitializationSql = "";
    private Map<Long, NativeTableBinding> nativeTableBindings = Map.of();
    /** Kafka Topic 与无凭据工作环境由管理员逐数据源绑定，默认不开放。 */
    private Map<Long, cn.iocoder.yudao.framework.etl.core.compile.EtlNativeConnectorCompiler.KafkaBinding> nativeKafkaBindings = Map.of();
    /** 管理员确认已在引擎会话中注册的受管函数；任务不能自行声明。 */
    private Set<String> managedFunctions = Set.of();
    /** 仅保存秘密引用及授权范围，不保存密钥材料。 */
    private Map<String, ManagedSecretBinding> managedSecretBindings = Map.of();

    @Data
    public static class ManagedSecretBinding {
        private Set<Long> allowedTaskIds = Set.of();
        private Set<String> workerGroups = Set.of();
        private Set<String> engines = Set.of();
    }

    @Data
    public static class NativeTableBinding {
        /** 只有管理员绑定实际持续流表（如 CDC/Kafka）时开启，普通 JDBC 表保持 false。 */
        private boolean flinkContinuousSource;
        /** 官方 JDBC 输出校验适配器已安装；节点配置不能覆盖，默认关闭。 */
        private boolean flinkVerifiedJdbcSinkInstalled;
        /** 逐个真实物理表声明引擎 Catalog 的主键；仍需与 DB Switch 事实及运行时 Catalog 一致。 */
        private Map<String, java.util.List<String>> flinkUpsertPrimaryKeys = Map.of();
        /** 仅增量输出使用的可选独立 Catalog 别名，不覆盖已有读表和追加写入绑定。 */
        private Map<String, String> flinkUpsertTableAliases = Map.of();
        /** 混合 Catalog 中仅白名单内的逻辑表可作为实时源。 */
        private Set<String> flinkContinuousTables = Set.of();
        private java.util.List<String> sparkNamespace = java.util.List.of();
        private java.util.List<String> flinkNamespace = java.util.List.of();
        private Map<String, String> tableAliases = Map.of();
    }

    @Data
    public static class DatabaseBinding {
        private Integer dsDatasourceId;
        private String dsType;
    }
}
