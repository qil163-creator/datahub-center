package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import cn.iocoder.yudao.module.dataforge.service.databasetype.DatabaseTypeService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationRocketBindingSupportTest {
    private final IntegrationRocketBindingSupport support = new IntegrationRocketBindingSupport();
    private final IntegrationRocketBindingsProperties properties = new IntegrationRocketBindingsProperties();
    private final IntegrationRuntimeProperties runtime = new IntegrationRuntimeProperties();
    private final IntegrationRocketBindingsProperties.ManagedBinding binding = new IntegrationRocketBindingsProperties.ManagedBinding();
    private final IntegrationRuntimeProperties.ManagedSecretBinding secret = new IntegrationRuntimeProperties.ManagedSecretBinding();
    private final DatabaseService databases = mock(DatabaseService.class); private final DatabaseTypeService types = mock(DatabaseTypeService.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final IntegrationTaskDO task = new IntegrationTaskDO(); private final DatabaseDO database = new DatabaseDO(); private final DatabaseTypeDO type = new DatabaseTypeDO();
    @BeforeEach void prepare() {
        ReflectionTestUtils.setField(support, "properties", properties); ReflectionTestUtils.setField(support, "runtimeProperties", runtime);
        ReflectionTestUtils.setField(support, "databases", databases); ReflectionTestUtils.setField(support, "databaseTypes", types); ReflectionTestUtils.setField(support, "access", access);
        task.setId(4L); task.setEngine("FLINK"); task.setMode("REALTIME"); task.setWorkerGroup("isolated");
        binding.setAllowedTaskIds(Set.of(4L)); binding.setEngines(Set.of("FLINK")); binding.setWorkerGroups(Set.of("isolated")); binding.setNameServers("rocket-native-namesrv:9876");
        binding.setSourceTopics(Set.of("components_input")); binding.setSinkTopics(Set.of("components_output")); binding.setConsumerGroupTaskIds(Map.of("components_read", 4L)); binding.setProducerGroups(Set.of("components_write"));
        binding.setCredentialRef("rocket.fixture"); binding.setUseTls(false); binding.setFlinkConnectorInstalled(true); properties.setBindings(Map.of(1L, binding));
        secret.setAllowedTaskIds(Set.of(4L)); secret.setWorkerGroups(Set.of("isolated")); secret.setEngines(Set.of("FLINK")); runtime.setManagedSecretBindings(Map.of("rocket.fixture", secret));
        database.setDatabaseType(8); database.setExtendConfig("{\"schemaVersion\":1,\"servers\":[\"rocket-native-namesrv:9876\"],\"tls\":false,\"authenticationMode\":\"ACL\"}"); type.setTypeCode("ROCKETMQ");
        when(databases.getDatabase(1L)).thenReturn(database); when(types.getDatabaseType(8L)).thenReturn(type);
    }
    @Test void independentTaskOwnedGroupAndSeparatePhysicalConnectionAndSecretScopesAreAllRequired() {
        var source = support.require(task, 1L, "read-rocketmq", input()); assertEquals("rocket.fixture", source.credentialRef()); assertEquals(Set.of("components_read"), source.consumerGroups());
        verify(access).requireDatabase(1L);
        assertDoesNotThrow(() -> support.require(task, 1L, "write-rocketmq", Map.of("topic", "components_output", "groupId", "components_write")));
        binding.setConsumerGroupTaskIds(Map.of("components_read", 5L));
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L, "read-rocketmq", input()));
    }
    @Test void taskModeWorkerEngineInstallationAndSecretAuthorizationFailClosed() {
        for (String mismatch : List.of("task", "mode", "worker", "engine", "installed", "secretTask", "secretWorker", "secretEngine")) {
            task.setId(mismatch.equals("task") ? 5L : 4L); task.setMode(mismatch.equals("mode") ? "OFFLINE" : "REALTIME"); task.setWorkerGroup(mismatch.equals("worker") ? "other" : "isolated"); task.setEngine(mismatch.equals("engine") ? "SPARK" : "FLINK");
            binding.setFlinkConnectorInstalled(!mismatch.equals("installed")); secret.setAllowedTaskIds(Set.of(mismatch.equals("secretTask") ? 5L : 4L));
            secret.setWorkerGroups(Set.of(mismatch.equals("secretWorker") ? "other" : "isolated")); secret.setEngines(Set.of(mismatch.equals("secretEngine") ? "SPARK" : "FLINK"));
            assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L, "read-rocketmq", input()));
        }
    }
    @Test void productEndpointAclTlsAndGroupOwnershipConflictsCannotReuseApprovedCredentials() {
        for (String mismatch : List.of("product", "address", "tls", "acl")) {
            type.setTypeCode(mismatch.equals("product") ? "KAFKA" : "ROCKETMQ");
            database.setExtendConfig("{\"schemaVersion\":1,\"servers\":[\"" + (mismatch.equals("address") ? "other:9876" : "rocket-native-namesrv:9876") + "\"],\"tls\":" + mismatch.equals("tls") + ",\"authenticationMode\":\"" + (mismatch.equals("acl") ? "NONE" : "ACL") + "\"}");
            assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L, "read-rocketmq", input()));
        }
        database.setExtendConfig("{\"schemaVersion\":1,\"servers\":\"ROCKET-NATIVE-NAMESRV:9876\",\"tls\":false,\"authenticationMode\":\"ACL\"}");
        assertDoesNotThrow(() -> support.require(task, 1L, "read-rocketmq", input()));
        var conflict = new IntegrationRocketBindingsProperties.ManagedBinding(); conflict.setNameServers("ROCKET-NATIVE-NAMESRV:9876"); conflict.setFlinkConnectorInstalled(true); conflict.setConsumerGroupTaskIds(Map.of("components_read", 5L));
        properties.setBindings(Map.of(1L, binding, 2L, conflict));
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L, "read-rocketmq", input()));
    }
    private static Map<String, Object> input() { return Map.of("topic", "components_input", "groupId", "components_read"); }
}
