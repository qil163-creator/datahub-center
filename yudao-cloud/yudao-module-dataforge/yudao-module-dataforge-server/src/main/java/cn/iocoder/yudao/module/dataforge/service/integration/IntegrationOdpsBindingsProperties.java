package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeOdpsCompiler;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.*;

/** ODPS 项目、表分区及工作环境均由管理员精确绑定；不保存访问密钥。 */
@Data
@Component
@ConfigurationProperties(prefix = "dataforge.integration.odps")
public class IntegrationOdpsBindingsProperties {
    private Map<Long, ManagedBinding> bindings = Map.of();
    @Data
    public static class ManagedBinding {
        private Set<Long> allowedTaskIds = Set.of();
        private Set<String> workerGroups = Set.of();
        private Set<String> engines = Set.of();
        private String serviceEndpoint;
        private String tunnelEndpoint;
        private String project;
        private String credentialRef;
        private List<EtlNativeOdpsCompiler.Resource> resources = List.of();
        private boolean sparkConnectorInstalled;
        private boolean flinkConnectorInstalled;
        private boolean allowPlainHttp;
    }
}
