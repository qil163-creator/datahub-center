package cn.iocoder.yudao.module.dataforge.service.integration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** RabbitMQ 管理员资源绑定；任务、工作环境、队列和秘密引用默认不授权。 */
@Data
@Component
@ConfigurationProperties(prefix = "dataforge.integration.rabbitmq")
public class IntegrationRabbitBindingsProperties {
    private Map<Long, ManagedBinding> bindings = Map.of();

    @Data
    public static class ManagedBinding {
        private Set<Long> allowedTaskIds = Set.of();
        private Set<String> workerGroups = Set.of();
        private Set<String> engines = Set.of();
        private String host;
        private int port = 5671;
        private String virtualHost;
        private boolean useSsl = true;
        private String credentialRef;
        private Set<String> sourceQueues = Set.of();
        private Set<String> sinkQueues = Set.of();
        private boolean flinkConnectorInstalled;
        private boolean sparkConnectorInstalled;
        private boolean flinkBoundedConnectorInstalled;
    }
}
