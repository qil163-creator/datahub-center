package cn.iocoder.yudao.module.dataforge.service.integration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 管理员维护的 TDengine 原生 TMQ 绑定，默认没有任务、工作组、主题或凭据授权。 */
@Data
@Component
@ConfigurationProperties(prefix = "dataforge.integration.tdengine")
public class IntegrationTdengineBindingsProperties {
    private Map<String, ManagedBinding> bindings = Map.of();

    @Data
    public static class ManagedBinding {
        private Long databaseId;
        private Set<Long> allowedTaskIds = Set.of();
        private Set<String> workerGroups = Set.of();
        private Set<String> engines = Set.of();
        private String expectedJdbcUrlSha256;
        private String sourceTableName;
        private String bootstrapServers;
        private Set<String> sourceTopics = Set.of();
        private Set<String> consumerGroups = Set.of();
        private String credentialRef;
        private List<Column> columns = List.of();
        private int pollIntervalMillis = 1000;
        private boolean useSsl = true;
        private boolean flinkConnectorInstalled;
        private boolean sparkConnectorInstalled;
        private boolean flinkBoundedConnectorInstalled;
    }

    @Data
    public static class Column {
        private String name;
        private String type;
    }
}
