package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;
import java.util.*;

/** 管理员维护脚本隔离资源与每任务证书；源码版本由正常保存发布自动登记。 */
@Data
@Component
@ConfigurationProperties(prefix="dataforge.integration.native-scripts")
public class IntegrationNativeScriptBindingsProperties {
    private boolean enabled;
    private String endpoint;
    private String listenHost = "127.0.0.1";
    private int listenPort;
    private String serverPrivateKeyRef;
    private String serverCertificateRef;
    private String authorityRef;
    private WorkerSettings workerPolicy = new WorkerSettings();
    private Map<String,ResourceBinding> resources = Map.of();
    private Map<String,Launcher> launchers = Map.of();
    private int maximumSessions=16;
    private long sessionLifetimeMillis=300000;
    private long cacheBudgetBytes=33554432;
    @Data public static class WorkerSettings {
        private boolean enabled;
        private List<Path> classpathEntries=List.of();
        private int maxSourceBytes=1048576,maxBatchBytes=8388608,maxResultBytes=8388608,maxBatchRows=10000,maxResultRows=10000,maxLogBytes=65536,heapMegabytes=256;
        private long startupTimeoutMillis=30000,batchTimeoutMillis=30000;
        private EtlScriptWorkerPolicy.Mode mode=EtlScriptWorkerPolicy.Mode.DOCKER;
        private DockerSettings docker;
        public EtlScriptWorkerPolicy toPolicy(){return new EtlScriptWorkerPolicy(enabled,classpathEntries,maxSourceBytes,maxBatchBytes,maxResultBytes,maxBatchRows,maxResultRows,maxLogBytes,startupTimeoutMillis,batchTimeoutMillis,heapMegabytes,mode,docker==null?null:docker.toPolicy());}
    }
    @Data public static class DockerSettings {
        private Path executable;
        private String endpoint,image;
        private List<EtlScriptWorkerPolicy.HostPathMapping> hostPathMappings=List.of();
        private boolean requireSwapLimit;
        private long commandTimeoutMillis=5000,maxLifetimeMillis=300000;
        private RecoverySettings recovery;
        EtlScriptWorkerPolicy.Docker toPolicy(){return new EtlScriptWorkerPolicy.Docker(executable,endpoint,image,hostPathMappings,requireSwapLimit,commandTimeoutMillis,maxLifetimeMillis,recovery==null?null:recovery.toPolicy());}
    }
    @Data public static class RecoverySettings {
        private Path directory;
        private String serviceId;
        private int maxRecords=16;
        private boolean recoverOnStartup;
        private long reconciliationTimeoutMillis=10000;
        EtlScriptWorkerPolicy.Recovery toPolicy(){return new EtlScriptWorkerPolicy.Recovery(directory,serviceId,maxRecords,recoverOnStartup,reconciliationTimeoutMillis);}
    }
    @Data public static class ResourceBinding {
        private List<Identity> identities=List.of();
        private int maximumRows=10000;
        private long maximumFlushMillis=300000;
        private Set<String> httpResourceRefs=Set.of();
        private Map<String,WorkerHttpPolicy> workerHttpResources=Map.of();
        private Set<cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner.Language> workerHttpLanguages=Set.of(cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner.Language.QDATA_TOKEN);
    }
    @Data public static class WorkerHttpPolicy {
        private Set<String> methods=Set.of();
        private int maximumBytes=1048576;
        private int timeoutMillis=30000;
        private Map<String,String> headerSecretRefs=Map.of();
    }
    @Data public static class Identity {
        private Long taskId;
        private String workerGroup;
        private String engine;
        private String certificateSha256;
        private String privateKeyRef;
        private String certificateRef;
        private String authorityRef;
    }
    @Data public static class Launcher {
        private String resourceName;
        private String workerRelativePath;
    }
}
