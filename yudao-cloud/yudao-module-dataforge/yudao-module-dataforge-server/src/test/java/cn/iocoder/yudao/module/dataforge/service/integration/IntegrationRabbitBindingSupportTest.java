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

class IntegrationRabbitBindingSupportTest {
    private final IntegrationRabbitBindingSupport support = new IntegrationRabbitBindingSupport();
    private final IntegrationRabbitBindingsProperties properties = new IntegrationRabbitBindingsProperties();
    private final IntegrationRuntimeProperties runtime = new IntegrationRuntimeProperties();
    private final DatabaseService databases = mock(DatabaseService.class);
    private final DatabaseTypeService types = mock(DatabaseTypeService.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final IntegrationRabbitBindingsProperties.ManagedBinding binding = new IntegrationRabbitBindingsProperties.ManagedBinding();
    private final IntegrationRuntimeProperties.ManagedSecretBinding secret = new IntegrationRuntimeProperties.ManagedSecretBinding();
    private final IntegrationTaskDO task = new IntegrationTaskDO();
    private final DatabaseDO database = new DatabaseDO();
    private final DatabaseTypeDO type = new DatabaseTypeDO();
    @BeforeEach void prepare() {
        ReflectionTestUtils.setField(support, "properties", properties); ReflectionTestUtils.setField(support, "runtimeProperties", runtime);
        ReflectionTestUtils.setField(support, "databases", databases); ReflectionTestUtils.setField(support, "databaseTypes", types); ReflectionTestUtils.setField(support, "access", access);
        task.setId(4L); task.setEngine("FLINK"); task.setMode("REALTIME"); task.setWorkerGroup("isolated");
        binding.setAllowedTaskIds(Set.of(4L)); binding.setWorkerGroups(Set.of("isolated")); binding.setEngines(Set.of("FLINK"));
        binding.setHost("rabbit-native"); binding.setPort(5672); binding.setVirtualHost("/components"); binding.setUseSsl(false);
        binding.setCredentialRef("rabbit.fixture"); binding.setFlinkConnectorInstalled(true); binding.setSourceQueues(Set.of("components.in")); binding.setSinkQueues(Set.of("components.out"));
        properties.setBindings(Map.of(1L, binding)); secret.setAllowedTaskIds(Set.of(4L)); secret.setWorkerGroups(Set.of("isolated")); secret.setEngines(Set.of("FLINK"));
        runtime.setManagedSecretBindings(Map.of("rabbit.fixture", secret));
        database.setDatabaseType(8); database.setDatabaseIp("rabbit-native"); database.setDatabasePort("5672");
        database.setExtendConfig("{\"schemaVersion\":1,\"virtualHost\":\"/components\",\"tls\":false}");
        type.setTypeCode("RABBITMQ"); when(types.getDatabaseType(8L)).thenReturn(type); when(databases.getDatabase(1L)).thenReturn(database);
    }
    @Test void platformPhysicalIdentityDirectionAndSeparateSecretScopeAreRequired() {
        var actual = support.require(task, 1L, "read-rabbitmq", Map.of("topic", "components.in"));
        assertEquals("rabbit.fixture", actual.credentialRef()); assertEquals("/components", actual.virtualHost()); verify(access).requireDatabase(1L);
        assertDoesNotThrow(() -> support.require(task, 1L, "write-rabbitmq", Map.of("topic", "components.out")));
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L, "write-rabbitmq", Map.of("topic", "components.in")));
        secret.setAllowedTaskIds(Set.of(5L));
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L, "read-rabbitmq", Map.of("topic", "components.in")));
    }
    @Test void taskWorkerModeEngineInstallationAndMissingResourcesFailClosed() {
        for (String mismatch : List.of("task", "worker", "mode", "engine", "installed", "secretWorker", "secretEngine")) {
            task.setId(mismatch.equals("task") ? 5L : 4L); task.setWorkerGroup(mismatch.equals("worker") ? "other" : "isolated");
            task.setMode(mismatch.equals("mode") ? "OFFLINE" : "REALTIME"); task.setEngine(mismatch.equals("engine") ? "SPARK" : "FLINK");
            binding.setFlinkConnectorInstalled(!mismatch.equals("installed")); secret.setWorkerGroups(Set.of(mismatch.equals("secretWorker") ? "other" : "isolated"));
            secret.setEngines(Set.of(mismatch.equals("secretEngine") ? "SPARK" : "FLINK"));
            assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L, "read-rabbitmq", Map.of("topic", "components.in")));
        }
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 2L, "read-rabbitmq", Map.of("topic", "components.in")));
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L, "read-redis", Map.of("topic", "components.in")));
    }
    @Test void platformProductHostPortVirtualHostAndTlsDriftCannotReuseTheBinding() {
        for (String mismatch : List.of("type", "host", "port", "vhost", "tls")) {
            type.setTypeCode(mismatch.equals("type") ? "MYSQL" : "RABBITMQ"); database.setDatabaseIp(mismatch.equals("host") ? "other" : "rabbit-native");
            database.setDatabasePort(mismatch.equals("port") ? "5671" : "5672");
            database.setExtendConfig("{\"schemaVersion\":1,\"virtualHost\":\"" + (mismatch.equals("vhost") ? "/other" : "/components") + "\",\"tls\":" + mismatch.equals("tls") + "}");
            assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L, "read-rabbitmq", Map.of("topic", "components.in")));
        }
    }
}
