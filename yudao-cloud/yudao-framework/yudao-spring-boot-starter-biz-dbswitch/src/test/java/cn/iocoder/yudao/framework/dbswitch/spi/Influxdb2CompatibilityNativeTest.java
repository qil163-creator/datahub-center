package cn.iocoder.yudao.framework.dbswitch.spi;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 只在显式指定的一次性本机服务上验证组织 ID、限权 Token 和认证失败。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "DATAHUB_INFLUX_COMPAT_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_INFLUX_COMPAT_READ_TOKEN", matches = ".+")
@EnabledIfSystemProperty(named = "datahub.test.influx2.compat.http", matches = "http://127[.]0[.]0[.]1:[0-9]+")
class Influxdb2CompatibilityNativeTest {
    @Test void operatorTokenAcceptsOrganizationNameAndId() {
        verify(System.getenv("DATAHUB_INFLUX_COMPAT_TOKEN"), "probe-org", true);
        verify(System.getenv("DATAHUB_INFLUX_COMPAT_TOKEN"), orgId(), true);
    }

    @Test void bucketReadOnlyTokenDoesNotNeedOrganizationListPermissionWhenUsingId() {
        verify(System.getenv("DATAHUB_INFLUX_COMPAT_READ_TOKEN"), orgId(), true);
    }

    @Test void bucketTokenCannotConnectToAnotherOrganization() {
        verify(System.getenv("DATAHUB_INFLUX_COMPAT_READ_TOKEN"), "1111111111111111", false);
    }

    @Test void wrongTokenNeverPassesForAnExistingOrganizationId() {
        verify(System.getenv("DATAHUB_INFLUX_COMPAT_TOKEN") + "-invalid", orgId(), false);
    }

    private static String orgId() { return System.getProperty("datahub.test.influx2.compat.org-id"); }

    private static void verify(String token, String org, boolean successful) {
        var request = new ConnectionProbeRequest(ProductTypeEnum.INFLUXDB2, null, null,
                System.getProperty("datahub.test.influx2.compat.http"), null, token, null, null,
                Map.of("org", org, "bucket", "probe-bucket"), Duration.ofSeconds(5));
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = runtime.probeConnection(request);
            assertEquals(successful, result.connectionSuccessful(), result.toString());
            assertEquals(successful ? "PASS" : "FAIL", result.status());
            assertFalse(result.toString().contains(token));
        }
    }
}
