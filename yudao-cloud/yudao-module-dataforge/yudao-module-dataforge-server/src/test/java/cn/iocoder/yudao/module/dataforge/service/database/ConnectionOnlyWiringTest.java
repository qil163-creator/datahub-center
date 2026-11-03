package cn.iocoder.yudao.module.dataforge.service.database;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import cn.iocoder.yudao.module.dataforge.service.databasetype.DatabaseTypeCapabilityService;
import cn.iocoder.yudao.module.system.api.dept.DeptApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证真实消费方的组件发现、最小装配与服务脱敏后的线协议，不连接外部中间件。 */
class ConnectionOnlyWiringTest {
    private static final List<String> CONNECTION_ONLY_CODES = List.of(
            "GBASE8C", "GAUSSDB", "PHOENIX", "SAPHANA", "PRESTO", "ODPS", "NEO4J", "MILVUS",
            "INFLUXDB2", "OSS", "MINIO", "REDIS", "KAFKA", "RABBITMQ", "ROCKETMQ", "SFTP");

    @Test
    void missingExplicitCredentialsFailConfigurationBeforeAnyNetworkProbe() {
        withContext(context -> {
            var diagnosis = context.getBean(ConnectionOnlyDiagnosis.class);
            for (String code : List.of("MILVUS", "ROCKETMQ")) {
                String config = "MILVUS".equals(code)
                        ? "{\"schemaVersion\":1,\"endpoint\":\"http://127.0.0.1:1\",\"authenticationMode\":\"TOKEN\"}"
                        : "{\"schemaVersion\":1,\"servers\":[\"127.0.0.1:1\"],\"authenticationMode\":\"ACL\"}";
                var db = DatabaseDO.builder().userName("fixture-user").databaseName("fixture").extendConfig(config).build();
                var result = diagnosis.diagnose(db, DatabaseTypeDO.builder().typeCode(code).build(), "", LocalDateTime.now());
                assertFalse(result.isConnectionSuccessful());
                assertEquals("FAIL", result.getOverallStatus());
                var items = result.getGroups().getFirst().getItems();
                assertEquals("FAIL", items.stream().filter(i -> "CONFIG".equals(i.getCheckCode())).findFirst().orElseThrow().getStatus());
                assertEquals("SKIPPED", items.stream().filter(i -> "AUTH".equals(i.getCheckCode())).findFirst().orElseThrow().getStatus());
            }
        });
    }

    @Test
    void realRuntimeAndDomainConsumersExposeSixteenIndependentConnectCapabilities() {
        withContext(context -> {
            assertNotNull(context.getBean(DbSwitchRuntime.class));
            assertNotNull(context.getBean(ConnectionOnlyDiagnosis.class));
            var capabilities = context.getBean(DatabaseTypeCapabilityService.class);
            for (String code : CONNECTION_ONLY_CODES) {
                var response = capabilities.describe(DatabaseTypeDO.builder().typeCode(code).typeName(code).build());
                assertEquals("DECLARED", response.getCapabilities().get("CONNECT").status(), code);
                for (DatabaseOperation operation : DatabaseOperation.values()) {
                    if (operation != DatabaseOperation.CONNECT) {
                        assertEquals("UNSUPPORTED", response.getCapabilities().get(operation.name()).status(),
                                code + "/" + operation);
                    }
                }
                assertTrue(response.getCursorTargets().isEmpty(), code);
            }
            assertTrue(context.getBeansOfType(javax.sql.DataSource.class).isEmpty());
            assertTrue(context.getBeansOfType(org.redisson.api.RedissonClient.class).isEmpty());
        });
    }

    @Test
    void actualDataForgeClasspathContainsProtocolClientsIncludingRocketAcl() throws Exception {
        for (String name : List.of("org.apache.kafka.clients.admin.Admin", "com.rabbitmq.client.ConnectionFactory",
                "org.apache.rocketmq.remoting.netty.NettyRemotingClient", "org.apache.rocketmq.acl.common.AclClientRPCHook",
                "software.amazon.awssdk.services.s3.S3Client", "com.jcraft.jsch.JSch",
                "org.neo4j.driver.GraphDatabase", "com.aliyun.odps.Odps")) {
            assertNotNull(Class.forName(name, true, getClass().getClassLoader()), name);
        }
    }

    @Test
    void serviceRedactionThenBootJacksonKeepsCredentialPresenceAndLosslessIdentifiers() {
        withContext(context -> {
            var type = DatabaseTypeDO.builder().id(197L).typeCode("MINIO").typeName("MinIO").build();
            var typeMapper = mock(DatabaseTypeMapper.class);
            when(typeMapper.selectByIds(anyCollection())).thenReturn(List.of(type));
            var service = new DatabaseServiceImpl();
            ReflectionTestUtils.setField(service, "databaseTypeMapper", typeMapper);
            ReflectionTestUtils.setField(service, "typeCapabilityService", context.getBean(DatabaseTypeCapabilityService.class));
            ReflectionTestUtils.setField(service, "deptApi", mock(DeptApi.class));
            ReflectionTestUtils.setField(service, "adminUserApi", mock(AdminUserApi.class));
            String secretKey = UUID.randomUUID().toString().replace("-", "");
            String plaintext = UUID.randomUUID().toString();
            String ciphertext = DatabasePasswordEncryptUtils.encrypt(plaintext, secretKey);
            LocalDateTime time = LocalDateTime.of(2026, 10, 19, 8, 9, 10, 123000000);
            var protectedRecord = DatabaseDO.builder().id(9007199254740993L).databaseType(197)
                    .password(ciphertext).secretKey(secretKey).build();
            protectedRecord.setCreateTime(time);
            var anonymousRecord = DatabaseDO.builder().id(42L).databaseType(197).password("").build();

            // 脱敏发生在真实服务转换方法；此处没有手工清除响应的秘密字段。
            var responses = service.convertToRespList(List.of(protectedRecord, anonymousRecord));
            var mapper = context.getBean(ObjectMapper.class);
            byte[] bytes = mapper.writeValueAsBytes(responses);
            var json = mapper.readTree(bytes);
            assertTrue(json.get(0).path("hasPassword").asBoolean());
            assertFalse(json.get(1).path("hasPassword").asBoolean());
            assertTrue(json.get(0).get("id").isString());
            assertEquals("9007199254740993", json.get(0).get("id").asString());
            assertTrue(json.get(1).get("id").isNumber());
            assertTrue(json.get(0).get("createTime").isNumber());
            assertEquals(time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    json.get(0).get("createTime").asLong());
            assertEquals("DECLARED", json.get(0).path("capabilities").path("CONNECT").path("status").asString());
            for (var row : json) {
                assertTrue(row.path("password").isMissingNode() || row.path("password").isNull());
                assertTrue(row.path("secretKey").isMissingNode() || row.path("secretKey").isNull());
            }
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(text.contains(plaintext));
            assertFalse(text.contains(ciphertext));
            assertFalse(text.contains(secretKey));
            assertEquals(ciphertext, protectedRecord.getPassword());
        });
    }

    private static void withContext(Consumer<AssertableApplicationContext> assertion) {
        var previous = JsonUtils.getObjectMapper();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                    YudaoDbSwitchAutoConfiguration.class, JacksonAutoConfiguration.class,
                    YudaoJacksonAutoConfiguration.class))
                    .withUserConfiguration(Consumers.class).run(context -> {
                        assertNull(context.getStartupFailure());
                        assertion.accept(context);
                    });
        } finally {
            JsonUtils.init(previous);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ConnectionOnlyDiagnosis.class, DatabaseTypeCapabilityService.class})
    static class Consumers {
    }
}
