package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationTdengineBindingSupportTest {
    private final IntegrationTdengineBindingSupport support = new IntegrationTdengineBindingSupport();
    private final IntegrationTdengineBindingsProperties properties = new IntegrationTdengineBindingsProperties();
    private final IntegrationRuntimeProperties runtime = new IntegrationRuntimeProperties();
    private final AccessDatabaseResolver connections = mock(AccessDatabaseResolver.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final DbSwitchRuntime dbSwitch = mock(DbSwitchRuntime.class);
    private final MetaDataByJdbcServiceImpl metadata = mock(MetaDataByJdbcServiceImpl.class);
    private final IntegrationTaskDO task = new IntegrationTaskDO();
    private final IntegrationTdengineBindingsProperties.ManagedBinding binding = new IntegrationTdengineBindingsProperties.ManagedBinding();
    private final IntegrationRuntimeProperties.ManagedSecretBinding secret = new IntegrationRuntimeProperties.ManagedSecretBinding();
    private final AccessDatabaseResolver.ConnectionInfo connection = new AccessDatabaseResolver.ConnectionInfo();
    private final Map<String, Object> config = Map.of("resourceBindingId", "fixture", "topic", "sensor_topic", "groupId", "sensor_group");

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(support, "properties", properties); ReflectionTestUtils.setField(support, "runtimeProperties", runtime);
        ReflectionTestUtils.setField(support, "connections", connections); ReflectionTestUtils.setField(support, "access", access); ReflectionTestUtils.setField(support, "dbSwitch", dbSwitch);
        task.setId(4L); task.setEngine("FLINK"); task.setMode("REALTIME"); task.setWorkerGroup("isolated");
        binding.setDatabaseId(9L); binding.setAllowedTaskIds(Set.of(4L)); binding.setWorkerGroups(Set.of("isolated")); binding.setEngines(Set.of("FLINK"));
        binding.setExpectedJdbcUrlSha256(DigestUtil.sha256Hex("jdbc:TAOS-WS://managed:6041/sensors")); binding.setSourceTableName("sensor_values");
        binding.setBootstrapServers("managed:6041"); binding.setSourceTopics(Set.of("sensor_topic")); binding.setConsumerGroups(Set.of("sensor_group"));
        binding.setCredentialRef("tdengine-test"); binding.setFlinkConnectorInstalled(true);
        var timestamp = new IntegrationTdengineBindingsProperties.Column(); timestamp.setName("ts"); timestamp.setType("TIMESTAMP(3)");
        var reading = new IntegrationTdengineBindingsProperties.Column(); reading.setName("reading"); reading.setType("DOUBLE"); binding.setColumns(List.of(timestamp, reading));
        properties.setBindings(Map.of("fixture", binding)); secret.setAllowedTaskIds(Set.of(4L)); secret.setWorkerGroups(Set.of("isolated")); secret.setEngines(Set.of("FLINK"));
        runtime.setManagedSecretBindings(Map.of("tdengine-test", secret));
        connection.setProductType(ProductTypeEnum.TDENGINE); connection.setJdbcUrl("jdbc:TAOS-WS://managed:6041/sensors"); connection.setUsername("metadata_user"); connection.setPassword("not-logged"); connection.setSchema("sensors");
        when(connections.resolve(9L, DATABASE_NOT_EXISTS)).thenReturn(connection); when(dbSwitch.jdbcMetadata(ProductTypeEnum.TDENGINE)).thenReturn(metadata);
        var physical = new SchemaTableMeta(); physical.setColumns(List.of(column("ts", "TIMESTAMP", MetadataValue.value(3)), column("reading", "DOUBLE", MetadataValue.unknown())));
        when(metadata.queryTableMeta(connection.getJdbcUrl(), "metadata_user", "not-logged", "sensors", "sensor_values")).thenReturn(physical);
    }

    @Test void validBindingReusesAuthorizedDbSwitchNativeMetadataAndDoesNotReturnCredentials() {
        var result = support.require(task, 9L, config);
        assertEquals("tdengine-test", result.credentialRef()); assertEquals(List.of("ts", "reading"), result.columns().keySet().stream().toList());
        assertFalse(result.toString().contains("not-logged")); verify(access).requireDatabase(9L);
        verify(metadata).queryTableMeta(connection.getJdbcUrl(), "metadata_user", "not-logged", "sensors", "sensor_values");
    }
    @Test void noBindingOrNoInstalledConnectorFailsBeforeConnectionResolution() {
        properties.setBindings(Map.of()); assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, config));
        properties.setBindings(Map.of("fixture", binding)); binding.setFlinkConnectorInstalled(false);
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, config)); verifyNoInteractions(access, connections, dbSwitch);
    }
    @Test void taskWorkerAndEngineResourceAuthorizationAreIndependent() {
        binding.setAllowedTaskIds(Set.of(99L)); assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, config)); binding.setAllowedTaskIds(Set.of(4L));
        binding.setWorkerGroups(Set.of("other")); assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, config)); binding.setWorkerGroups(Set.of("isolated"));
        binding.setEngines(Set.of("SPARK")); assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, config)); binding.setEngines(Set.of("FLINK"));
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 10L, config)); verifyNoInteractions(access, connections, dbSwitch);
    }
    @Test void secretGrantNeedsExactTaskWorkerAndEngineAndDefaultsToDenied() {
        runtime.setManagedSecretBindings(null); assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, config)); runtime.setManagedSecretBindings(Map.of("tdengine-test", secret));
        secret.setAllowedTaskIds(Set.of(99L)); assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, config)); secret.setAllowedTaskIds(Set.of(4L));
        secret.setWorkerGroups(Set.of("other")); assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, config)); secret.setWorkerGroups(Set.of("isolated"));
        secret.setEngines(Set.of("SPARK")); assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, config)); verifyNoInteractions(access, connections, dbSwitch);
    }
    @Test void topicGroupPhysicalConnectionAndProductCannotBeSubstituted() {
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, Map.of("topic", "unauthorized", "groupId", "sensor_group")));
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, Map.of("topic", "sensor_topic", "groupId", "unauthorized")));
        connection.setJdbcUrl("jdbc:TAOS-WS://redirected:6041/sensors"); assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, config));
        connection.setJdbcUrl("jdbc:TAOS-WS://managed:6041/sensors"); connection.setProductType(ProductTypeEnum.POSTGRESQL);
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, config)); verifyNoInteractions(dbSwitch);
    }
    @Test void ambiguousBindingsAndDatabasePermissionRevocationFailClosed() {
        properties.setBindings(Map.of("fixture", binding, "duplicate", binding));
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, Map.of("topic", "sensor_topic", "groupId", "sensor_group")));
        doThrow(new IllegalStateException("revoked")).when(access).requireDatabase(9L);
        assertThrows(IllegalStateException.class, () -> support.require(task, 9L, config)); verifyNoInteractions(connections, dbSwitch);
    }
    @Test void nativeMetadataChangesAndUnprovenTimestampPrecisionRejectCompilation() {
        var physical = new SchemaTableMeta(); physical.setColumns(List.of(column("ts", "TIMESTAMP", MetadataValue.absent()), column("reading", "DOUBLE", MetadataValue.unknown())));
        when(metadata.queryTableMeta(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(physical);
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, config));
        binding.setColumns(List.of(binding.getColumns().getFirst(), binding.getColumns().getFirst()));
        assertThrows(IllegalArgumentException.class, () -> support.require(task, 9L, config));
    }
    private static ColumnDescription column(String name, String type, MetadataValue<Integer> scale) {
        var result = new ColumnDescription(); result.setDbType(ProductTypeEnum.TDENGINE); result.setFieldName(name);
        result.setMetadataEvidence(new ColumnMetadataEvidence(new MetadataProvenance(ProductTypeEnum.TDENGINE, "TDengine", "3.3.8.8", "TDENGINE_CATALOG_V1", Instant.EPOCH),
                MetadataValue.value(type), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), scale, MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown())); return result;
    }
}
