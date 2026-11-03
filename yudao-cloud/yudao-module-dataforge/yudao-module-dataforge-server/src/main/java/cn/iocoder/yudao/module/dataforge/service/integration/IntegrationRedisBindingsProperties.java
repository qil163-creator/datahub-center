package cn.iocoder.yudao.module.dataforge.service.integration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Redis 非 JDBC 资源绑定，任务、工作环境、Key 与凭据默认全部拒绝。 */
@Data
@Component
@ConfigurationProperties(prefix = "dataforge.integration.redis")
public class IntegrationRedisBindingsProperties {
    private Map<Long, ManagedBinding> bindings = Map.of();
    @Data
    public static class ManagedBinding {
        private Set<Long> allowedTaskIds = Set.of();
        private Set<String> workerGroups = Set.of();
        private Set<String> engines = Set.of();
        private String host;
        private int port = 6379;
        private int database;
        private boolean useSsl = true;
        private String credentialRef;
        private Set<String> sourcePatterns = Set.of();
        private Set<String> sinkPrefixes = Set.of();
        private boolean sparkConnectorInstalled;
        private boolean flinkConnectorInstalled;
    }
}
