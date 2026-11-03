package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.mq.core.connection.ExternalMqConnectionConfig;
import cn.iocoder.yudao.framework.mq.core.connection.ExternalMqConnectionProbe;
import cn.iocoder.yudao.framework.redis.core.connection.ExternalRedisConnectionConfig;
import cn.iocoder.yudao.framework.redis.core.connection.ExternalRedisConnectionProbe;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证领域配置确实传到组件公开入口，不以保存字段替代执行参数验证。 */
class ConnectionOnlyDiagnosisMappingTest {
    @Test void nativeProductsReceiveTimeoutTargetAndSeparateSecrets() {
        for (String code : List.of("NEO4J", "INFLUXDB2", "ODPS", "MILVUS")) {
            var runtime = mock(DbSwitchRuntime.class);
            when(runtime.products()).thenReturn(DbSwitchProductRegistry.discover());
            when(runtime.probeConnection(any())).thenReturn(new ConnectionDiagnosticResult("PASS", true, 1,
                    List.of(new ConnectionDiagnosticResult.Check("BASIC", "connection", "连接与认证", "PASS", 1, "验证通过", null, null))));
            var service = new ConnectionOnlyDiagnosis(); ReflectionTestUtils.setField(service, "runtime", runtime);
            String endpoint = "NEO4J".equals(code) ? "bolt://service.example.invalid:7687" : "https://service.example.invalid";
            var db = database("{\"schemaVersion\":1,\"endpoint\":\"" + endpoint + "\",\"timeoutSeconds\":23}");
            assertTrue(service.diagnose(db, type(code), "fixture-secret", LocalDateTime.now()).isConnectionSuccessful());
            var request = ArgumentCaptor.forClass(ConnectionProbeRequest.class); verify(runtime).probeConnection(request.capture());
            assertEquals(23, request.getValue().timeout().toSeconds());
            assertEquals(endpoint, request.getValue().endpoint()); assertEquals("fixture", request.getValue().database());
            assertEquals("fixture-secret", request.getValue().password()); assertNull(request.getValue().jdbcUrl());
            assertFalse(request.getValue().properties().containsValue("fixture-secret"));
        }
    }

    @Test void missingNeo4jCredentialsNeverReachTheDriver() {
        var runtime = mock(DbSwitchRuntime.class);
        var service = new ConnectionOnlyDiagnosis(); ReflectionTestUtils.setField(service, "runtime", runtime);
        var result = service.diagnose(database("{\"schemaVersion\":1,\"endpoint\":\"bolt://service.example.invalid\"}"),
                type("NEO4J"), "", LocalDateTime.now());
        assertFalse(result.isConnectionSuccessful());
        assertEquals("FAIL", result.getOverallStatus());
        verifyNoInteractions(runtime);
    }

    @Test void redisAndMqUseTheConfiguredBudgetAndNormalizedServerList() {
        var service = new ConnectionOnlyDiagnosis();
        try (var probe = mockStatic(ExternalRedisConnectionProbe.class)) {
            var db = database("{\"schemaVersion\":1,\"timeoutSeconds\":7,\"tls\":true}"); db.setDatabaseName("0");
            assertTrue(service.diagnose(db, type("REDIS"), "fixture-secret", LocalDateTime.now()).isConnectionSuccessful());
            var config = ArgumentCaptor.forClass(ExternalRedisConnectionConfig.class);
            probe.verify(() -> ExternalRedisConnectionProbe.test(config.capture()));
            assertEquals(7000, config.getValue().getTimeoutMillis()); assertTrue(config.getValue().isSsl());
        }
        for (String code : List.of("KAFKA", "ROCKETMQ", "RABBITMQ")) {
            try (var probe = mockStatic(ExternalMqConnectionProbe.class)) {
                var db = database("{\"schemaVersion\":1,\"timeoutSeconds\":17,\"servers\":\"broker.example.invalid:9876; other.example.invalid:9876\"}");
                assertTrue(service.diagnose(db, type(code), "fixture-secret", LocalDateTime.now()).isConnectionSuccessful());
                var config = ArgumentCaptor.forClass(ExternalMqConnectionConfig.class);
                probe.verify(() -> ExternalMqConnectionProbe.test(config.capture()));
                assertEquals(17000, config.getValue().getTimeoutMillis());
                if ("ROCKETMQ".equals(code)) {
                    assertEquals("broker.example.invalid:9876;other.example.invalid:9876", config.getValue().getAddresses());
                    assertNull(config.getValue().getPassword());
                } else if ("KAFKA".equals(code)) assertEquals("broker.example.invalid:9876,other.example.invalid:9876", config.getValue().getAddresses());
            }
        }
    }

    private static DatabaseTypeDO type(String code) { return DatabaseTypeDO.builder().typeCode(code).build(); }
    private static DatabaseDO database(String config) {
        return DatabaseDO.builder().databaseIp("service.example.invalid").databasePort("1234")
                .userName("fixture-user").databaseName("fixture").extendConfig(config).build();
    }
}
