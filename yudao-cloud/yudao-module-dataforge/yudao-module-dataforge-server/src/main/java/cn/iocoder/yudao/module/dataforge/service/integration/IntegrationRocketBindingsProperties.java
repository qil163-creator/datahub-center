package cn.iocoder.yudao.module.dataforge.service.integration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** RocketMQ 按平台数据源绑定；消费者组还必须有唯一任务归属，防止不同任务共用位点。 */
@Data
@Component
@ConfigurationProperties(prefix = "dataforge.integration.rocketmq")
public class IntegrationRocketBindingsProperties {
    private Map<Long, ManagedBinding> bindings = Map.of();
    @Data
    public static class ManagedBinding {
        private Set<Long> allowedTaskIds = Set.of();
        private Set<String> workerGroups = Set.of();
        private Set<String> engines = Set.of();
        private String nameServers;
        private Set<String> sourceTopics = Set.of();
        private Set<String> sinkTopics = Set.of();
        private Map<String, Long> consumerGroupTaskIds = Map.of();
        private Set<String> producerGroups = Set.of();
        private String credentialRef;
        private boolean useTls = true;
        private boolean flinkConnectorInstalled;
        private boolean sparkConnectorInstalled;
        private boolean flinkBoundedConnectorInstalled;
    }
}
