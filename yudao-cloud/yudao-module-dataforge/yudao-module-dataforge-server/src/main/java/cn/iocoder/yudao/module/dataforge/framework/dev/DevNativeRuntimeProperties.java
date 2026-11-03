package cn.iocoder.yudao.module.dataforge.framework.dev;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** 管理员部署的项目执行沙箱。前端只选择代码和受管资源引用。 */
@Data
@Component
@ConfigurationProperties(prefix = "dataforge.dev.native")
public class DevNativeRuntimeProperties {

    private List<Profile> profiles = List.of();

    @Data
    public static class Profile {

        private String code;

        private String name;

        private String revision;

        private Long dsProjectCode;

        private String dsVersion = "3.4.2";

        private String engine;

        private String engineVersion;

        private String deployMode = "YARN_ATTACHED";

        private String workerGroup;

        private String tenantCode;

        private Long environmentCode;

        private String queue;

        private String yarnEndpoint;

        private String yarnSslBundle;

        /** 本地 SQL 引擎的受信 Worker 观察入口，不向浏览器返回。 */
        private String localObserverEndpoint;

        private String localObserverSslBundle;

        /** 管理员固定的 Spark 本地并行度，任务参数不能覆盖。 */
        private String sparkMaster = "local[1]";

        private List<String> allowedTaskTypes = List.of();

        private List<Artifact> artifacts = List.of();

        private List<Artifact> dependencyResources = List.of();
    }

    @Data
    public static class Artifact {

        private Long fileId;

        private String name;

        private String sha256;

        /** DS ResourceContext 的受管名称；绝不向管理端返回。 */
        private String resourceName;

        private String workerRelativePath;

        /** 在线校验的硬上限；不得无限下载制品。 */
        private Long maxBytes = 67_108_864L;

        private List<String> programTypes = List.of();

        private String entryPoint;
    }
}
