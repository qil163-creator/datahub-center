package cn.iocoder.yudao.framework.dbswitch.spi;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过显式环境变量选择真实本地服务，只验证连接、认证和目标资源，不读取业务数据。 */
@Timeout(30)
class NewConnectionNativeTest {

    private DbSwitchRuntime runtime;

    @BeforeEach
    void openRuntime() {
        runtime = DbSwitchRuntime.standalone();
    }

    @AfterEach
    void closeRuntime() {
        if (runtime != null) runtime.close();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DATAHUB_NEO4J_TEST_PASSWORD", matches = ".+")
    @EnabledIfSystemProperty(named = "datahub.test.neo4j.bolt", matches = "bolt://127[.]0[.]0[.]1:[0-9]+")
    void neo4jAuthenticatesAndChecksTheConfiguredDatabase() {
        var result = runtime.probeConnection(neo4j(password(), neo4jDatabase()));
        assertTrue(result.connectionSuccessful(), result.toString());
        assertEquals("PASS", result.status());
        assertRedacted(result, password());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DATAHUB_NEO4J_TEST_PASSWORD", matches = ".+")
    @EnabledIfSystemProperty(named = "datahub.test.neo4j.bolt", matches = "bolt://127[.]0[.]0[.]1:[0-9]+")
    void neo4jRejectsWrongCredentialsAfterTheBoltHandshake() {
        String rejectedPassword = password() + "-invalid";
        var result = runtime.probeConnection(neo4j(rejectedPassword, neo4jDatabase()));
        assertRejected(result, rejectedPassword);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DATAHUB_NEO4J_TEST_PASSWORD", matches = ".+")
    @EnabledIfSystemProperty(named = "datahub.test.neo4j.bolt", matches = "bolt://127[.]0[.]0[.]1:[0-9]+")
    void neo4jRejectsAnAbsentDatabaseEvenWhenAuthenticationSucceeds() {
        var result = runtime.probeConnection(neo4j(password(), absentResource()));
        assertRejected(result, password());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DATAHUB_INFLUX_TEST_TOKEN", matches = ".+")
    @EnabledIfSystemProperty(named = "datahub.test.influx2.http", matches = "http://127[.]0[.]0[.]1:[0-9]+")
    void influx2AuthenticatesAndChecksOrganizationAndBucket() {
        var result = runtime.probeConnection(influx(token(), influxOrg(), influxBucket()));
        assertTrue(result.connectionSuccessful(), result.toString());
        assertEquals("PASS", result.status());
        assertRedacted(result, token());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DATAHUB_INFLUX_TEST_TOKEN", matches = ".+")
    @EnabledIfSystemProperty(named = "datahub.test.influx2.http", matches = "http://127[.]0[.]0[.]1:[0-9]+")
    void influx2RejectsWrongTokenInsteadOfAcceptingPublicHealth() {
        String rejectedToken = token() + "-invalid";
        var result = runtime.probeConnection(influx(rejectedToken, influxOrg(), influxBucket()));
        assertRejected(result, rejectedToken);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DATAHUB_INFLUX_TEST_TOKEN", matches = ".+")
    @EnabledIfSystemProperty(named = "datahub.test.influx2.http", matches = "http://127[.]0[.]0[.]1:[0-9]+")
    void influx2RejectsAnAbsentBucketEvenWhenAuthenticationSucceeds() {
        var result = runtime.probeConnection(influx(token(), influxOrg(), absentResource()));
        assertRejected(result, token());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DATAHUB_INFLUX_TEST_TOKEN", matches = ".+")
    @EnabledIfSystemProperty(named = "datahub.test.influx2.http", matches = "http://127[.]0[.]0[.]1:[0-9]+")
    void influx2RejectsAnAbsentOrganizationEvenWhenAuthenticationSucceeds() {
        var result = runtime.probeConnection(influx(token(), absentResource(), influxBucket()));
        assertRejected(result, token());
    }

    private static ConnectionProbeRequest neo4j(String password, String database) {
        String endpoint = loopbackProperty("datahub.test.neo4j.bolt", "bolt://127[.]0[.]0[.]1:[0-9]+");
        return new ConnectionProbeRequest(ProductTypeEnum.NEO4J, null, null, endpoint,
                System.getProperty("datahub.test.neo4j.user", "neo4j"), password, database,
                null, Map.of(), Duration.ofSeconds(10));
    }

    private static ConnectionProbeRequest influx(String token, String org, String bucket) {
        String endpoint = loopbackProperty("datahub.test.influx2.http", "http://127[.]0[.]0[.]1:[0-9]+");
        return new ConnectionProbeRequest(ProductTypeEnum.INFLUXDB2, null, null, endpoint,
                org, token, bucket, null, Map.of("org", org, "bucket", bucket), Duration.ofSeconds(10));
    }

    private static String loopbackProperty(String key, String pattern) {
        String value = System.getProperty(key, "");
        assertTrue(value.matches(pattern), () -> "真实连接测试仅接受显式配置的本机临时服务：" + key);
        return value;
    }

    private static String password() {
        return System.getenv("DATAHUB_NEO4J_TEST_PASSWORD");
    }

    private static String token() {
        return System.getenv("DATAHUB_INFLUX_TEST_TOKEN");
    }

    private static String neo4jDatabase() {
        return System.getProperty("datahub.test.neo4j.database", "neo4j");
    }

    private static String influxOrg() {
        return System.getProperty("datahub.test.influx2.org", "codex-connect-org");
    }

    private static String influxBucket() {
        return System.getProperty("datahub.test.influx2.bucket", "codex-connect-bucket");
    }

    private static String absentResource() {
        return "absent-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void assertRejected(ConnectionDiagnosticResult result, String secret) {
        assertFalse(result.connectionSuccessful(), result.toString());
        assertEquals("FAIL", result.status());
        assertRedacted(result, secret);
    }

    private static void assertRedacted(ConnectionDiagnosticResult result, String secret) {
        assertFalse(result.toString().contains(secret), "诊断结果不能暴露凭据");
    }
}
