package cn.iocoder.yudao.framework.dbswitch.spi;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.data.diagnostic.ConnectionProbes;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 固定诊断分类可读且不回显连接地址、密码或厂商消息。 */
class ConnectionProbeDiagnosticsTest {
    private static final String SECRET = "fixture-sensitive-text";
    private static final ConnectionProbeRequest REQUEST = new ConnectionProbeRequest(ProductTypeEnum.NEO4J,
            null, null, "bolt://private.example.invalid", "fixture-user", SECRET, "neo4j", null, Map.of(), Duration.ofSeconds(1));

    @Test void explicitProductFailuresRemainActionableWithoutVendorText() {
        for (var reason : ConnectionProbeException.Reason.values()) {
            verify(new ConnectionProbeException(reason, new IllegalStateException(SECRET)), reason.diagnosticMessage());
        }
    }

    @Test void standardTransportFailuresAreClassifiedThroughWrappers() {
        verify(new IllegalStateException(SECRET, new SSLHandshakeException(SECRET)), "TLS_VERIFICATION_FAILED");
        verify(new UnknownHostException(SECRET), "HOST_NOT_FOUND");
        verify(new ConnectException(SECRET), "CONNECTION_REFUSED");
        verify(new SocketTimeoutException(SECRET), "CONNECTION_TIMEOUT");
    }

    @Test void jdbcStandardStatesDistinguishCredentialsPermissionAndMissingTarget() {
        verify(new SQLException(SECRET, "28000"), "AUTHENTICATION_FAILED");
        verify(new SQLException(SECRET, "28P01"), "AUTHENTICATION_FAILED");
        verify(new SQLException(SECRET, "42501"), "AUTHORIZATION_FAILED");
        verify(new SQLException(SECRET, "3D000"), "TARGET_NOT_FOUND");
        verify(new SQLException(SECRET, "3F000"), "TARGET_NOT_FOUND");
        verify(new SQLException(SECRET, "08001"), "CONNECTION_FAILED");
    }

    @Test void protocolStrategiesCanPreserveSuccessfulStagesBeforeTargetFailure() {
        var strategy = new ConnectionProbe() {
            public void probe(ConnectionProbeRequest request, JdbcOpener opener) {
                throw new UnsupportedOperationException("不应丢弃分项诊断结果");
            }

            public cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult diagnose(
                    ConnectionProbeRequest request, JdbcOpener opener) {
                var auth = new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                        "BASIC", "AUTH", "认证", "PASS", 1, "认证成功", null, null);
                var target = new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult.Check(
                        "TARGET", "SCHEMA", "资源", "FAIL", 1, "目标资源不可访问", null, null);
                return new cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionDiagnosticResult(
                        "FAIL", false, 2, java.util.List.of(auth, target));
            }
        };
        try (var probes = new ConnectionProbes()) {
            var result = probes.probe(REQUEST, strategy, () -> null);
            assertEquals(2, result.checks().size());
            assertEquals("PASS", result.checks().getFirst().status());
            assertEquals("FAIL", result.checks().getLast().status());
            assertFalse(result.connectionSuccessful());
        }
    }

    @Test void nativeInapplicabilityDoesNotBecomeMissingImplementationOrLeakDetails() {
        try (var probes = new ConnectionProbes()) {
            var result = probes.probe(REQUEST, (request, opener) -> {
                throw new cn.iocoder.yudao.framework.dbswitch.core.model.DiagnosticNotApplicableException(SECRET);
            }, () -> null);
            assertEquals("SKIPPED", result.status());
            assertEquals("NOT_APPLICABLE", result.checks().getFirst().reasonCode());
            assertFalse(result.toString().contains(SECRET));
        }
    }

    @Test void unsupportedProtocolDiagnosticsAreUnverifiedNotConnectionFailures() {
        try (var probes = new ConnectionProbes()) {
            var result = probes.probe(REQUEST, (request, opener) -> {
                throw new java.sql.SQLFeatureNotSupportedException(SECRET, "0A000");
            }, () -> null);
            assertEquals("SKIPPED", result.status());
            assertEquals("NOT_IMPLEMENTED", result.checks().getFirst().reasonCode());
            assertFalse(result.connectionSuccessful());
            assertFalse(result.toString().contains(SECRET));
        }
    }

    private void verify(Exception failure, String expected) {
        try (var probes = new ConnectionProbes()) {
            var result = probes.probe(REQUEST, (request, opener) -> { throw failure; }, () -> null);
            assertEquals("FAIL", result.status());
            assertFalse(result.connectionSuccessful());
            assertTrue(result.checks().getFirst().message().startsWith(expected), result.toString());
            assertFalse(result.toString().contains(SECRET));
            assertFalse(result.toString().contains("private.example.invalid"));
        }
    }
}
