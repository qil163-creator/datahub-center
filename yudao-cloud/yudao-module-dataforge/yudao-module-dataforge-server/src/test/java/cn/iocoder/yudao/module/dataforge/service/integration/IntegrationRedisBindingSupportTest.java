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

class IntegrationRedisBindingSupportTest {
    private final IntegrationRedisBindingSupport support = new IntegrationRedisBindingSupport();
    private final IntegrationRedisBindingsProperties properties = new IntegrationRedisBindingsProperties();
    private final DatabaseService databases = mock(DatabaseService.class);
    private final DatabaseTypeService types = mock(DatabaseTypeService.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final IntegrationRedisBindingsProperties.ManagedBinding binding = new IntegrationRedisBindingsProperties.ManagedBinding();
    private final IntegrationTaskDO task = new IntegrationTaskDO(); private final DatabaseDO database = new DatabaseDO();
    @BeforeEach void prepare() {
        ReflectionTestUtils.setField(support, "properties", properties); ReflectionTestUtils.setField(support, "databases", databases);
        ReflectionTestUtils.setField(support, "databaseTypes", types); ReflectionTestUtils.setField(support, "access", access);
        task.setId(4L); task.setEngine("SPARK"); task.setMode("OFFLINE"); task.setWorkerGroup("isolated");
        binding.setHost("redis-native"); binding.setPort(6379); binding.setDatabase(0); binding.setSparkConnectorInstalled(true);
        binding.setUseSsl(false);
        binding.setAllowedTaskIds(Set.of(4L)); binding.setEngines(Set.of("SPARK")); binding.setWorkerGroups(Set.of("isolated"));
        binding.setCredentialRef("redisAccess"); binding.setSourcePatterns(Set.of("input:*")); binding.setSinkPrefixes(Set.of("result:")); properties.setBindings(Map.of(1L, binding));
        database.setDatabaseType(8); database.setDatabaseIp("redis-native"); database.setDatabasePort("6379"); database.setDatabaseName("0");
        database.setExtendConfig("{\"schemaVersion\":1,\"tls\":false}");
        var type = new DatabaseTypeDO(); type.setTypeCode("REDIS"); when(types.getDatabaseType(8L)).thenReturn(type); when(databases.getDatabase(1L)).thenReturn(database);
    }
    @Test void taskScopeAndPhysicalRedisIdentityAreBothRequired() {
        var actual = support.require(task, 1L); assertEquals("redisAccess", actual.credentialRef()); assertEquals(Set.of("input:*"), actual.sourcePatterns());
        verify(access).requireDatabase(1L); database.setDatabaseIp("other"); assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L));
        database.setDatabaseIp("redis-native"); database.setExtendConfig("{\"schemaVersion\":1,\"tls\":true}");
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L));
    }
    @Test void flinkNeedsItsOwnInstallProofAndCannotBorrowSparkCapability() {
        task.setEngine("FLINK"); binding.setEngines(Set.of("SPARK", "FLINK"));
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L));
        binding.setFlinkConnectorInstalled(true); binding.setSparkConnectorInstalled(false);
        var actual = support.require(task, 1L); assertTrue(actual.flinkConnectorInstalled()); assertFalse(actual.sparkConnectorInstalled());
    }
    @Test void wrongTaskGroupEngineModeMissingBindingAndPlatformProductAreDenied() {
        for (String mismatch : List.of("task", "group", "engine", "mode", "installed")) {
            task.setId(mismatch.equals("task") ? 5L : 4L); task.setWorkerGroup(mismatch.equals("group") ? "other" : "isolated");
            task.setEngine(mismatch.equals("engine") ? "FLINK" : "SPARK"); task.setMode(mismatch.equals("mode") ? "REALTIME" : "OFFLINE"); binding.setSparkConnectorInstalled(!mismatch.equals("installed"));
            assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L));
        }
        binding.setSparkConnectorInstalled(true); task.setMode("OFFLINE");
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 2L));
        var type = new DatabaseTypeDO(); type.setTypeCode("MYSQL"); when(types.getDatabaseType(8L)).thenReturn(type);
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 1L));
    }
}
