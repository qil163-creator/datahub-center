package cn.iocoder.yudao.framework.dbswitch.product.neo4j;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ConnectionProbeRequest;
import cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.driver.AuthToken;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;

import javax.net.ssl.SSLHandshakeException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class Neo4jConnectionProbeTest {
    private final Neo4jConnectionProbe probe = new Neo4jConnectionProbe();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"localhost", "http://localhost:7474", "bolt://user:secret@localhost",
            "bolt://localhost/neo4j", "bolt://localhost:0", "bolt://localhost:65536",
            "bolt://localhost:", "bolt://localhost?password=secret", "bolt://localhost#target",
            "bolt+ssc://localhost", "neo4j+ssc://localhost", "bolt://bad host"})
    void rejectsInvalidEndpointsBeforeCreatingTheDriver(String endpoint) {
        try (var factory = mockStatic(GraphDatabase.class)) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> probe.probe(request(endpoint, "user", "secret", "neo4j"), () -> null));
            assertFalse(failure.toString().contains("secret"));
            factory.verifyNoInteractions();
        }
    }

    @Test
    void requiresExplicitDatabaseAndCredentials() {
        try (var factory = mockStatic(GraphDatabase.class)) {
            assertThrows(IllegalArgumentException.class,
                    () -> probe.probe(request("bolt://localhost", "user", "secret", " "), () -> null));
            assertThrows(IllegalArgumentException.class,
                    () -> probe.probe(request("bolt://localhost", " ", "secret", "neo4j"), () -> null));
            assertThrows(IllegalArgumentException.class,
                    () -> probe.probe(request("bolt://localhost", "user", "", "neo4j"), () -> null));
            factory.verifyNoInteractions();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bolt://localhost", "bolt+s://localhost:7687", "neo4j://localhost/", "neo4j+s://[::1]:7687"})
    void acceptsSupportedUrisAndClosesDriverAfterAuthenticationFailure(String endpoint) {
        var driver = mock(Driver.class);
        doThrow(new ClientException("Neo.ClientError.Security.Unauthorized", "vendor-secret"))
                .when(driver).verifyConnectivity();
        try (var factory = mockStatic(GraphDatabase.class)) {
            factory.when(() -> GraphDatabase.driver(eq(URI.create(endpoint)), any(AuthToken.class), any(Config.class)))
                    .thenAnswer(invocation -> {
                        Config config = invocation.getArgument(2);
                        assertEquals(2000, config.connectionTimeoutMillis());
                        assertEquals(2000, config.connectionAcquisitionTimeoutMillis());
                        assertEquals(0, config.maxTransactionRetryTimeMillis());
                        assertEquals(1, config.maxConnectionPoolSize());
                        assertEquals(1, config.eventLoopThreads());
                        assertTrue(config.isTelemetryDisabled());
                        return driver;
                    });
            var failure = assertThrows(ConnectionProbeException.class,
                    () -> probe.probe(request(endpoint, "user", "secret", "neo4j"), () -> null));
            assertEquals(ConnectionProbeException.Reason.AUTHENTICATION, failure.reason());
            assertFalse(failure.toString().contains("vendor-secret"));
            verify(driver).close();
        }
    }

    @Test
    void classifiesMissingTargetAndClosesBothResources() {
        assertTargetFailure(new ClientException("Neo.ClientError.Database.DatabaseNotFound", "private-db"),
                ConnectionProbeException.Reason.TARGET_NOT_FOUND);
    }

    @Test
    void classifiesForbiddenTargetSeparatelyFromInvalidCredentials() {
        assertTargetFailure(new ClientException("Neo.ClientError.Security.Forbidden", "private-account"),
                ConnectionProbeException.Reason.AUTHORIZATION);
    }

    @Test
    void classifiesTlsFailureWithoutAcceptingAnUntrustedCertificate() {
        assertTargetFailure(new ServiceUnavailableException("tls-peer", new SSLHandshakeException("private-peer")),
                ConnectionProbeException.Reason.TLS);
    }

    @Test
    void targetProbeAlsoReadsCurrentUserMetadataWithoutBusinessNodes() throws Exception {
        var driver = mock(Driver.class);
        var session = mock(Session.class);
        var result = mock(org.neo4j.driver.Result.class);
        var record = mock(org.neo4j.driver.Record.class);
        when(driver.session(any(SessionConfig.class))).thenReturn(session);
        when(session.run(eq("RETURN 1 AS connected"), any(TransactionConfig.class))).thenReturn(result);
        when(result.single()).thenReturn(record);
        when(record.get("connected")).thenReturn(org.neo4j.driver.Values.value(1));
        var identity = mock(org.neo4j.driver.Result.class);
        when(session.run(eq("SHOW CURRENT USER YIELD user RETURN user"), any(TransactionConfig.class))).thenReturn(identity);
        when(identity.single()).thenReturn(record);
        when(record.get("user")).thenReturn(org.neo4j.driver.Values.value("user"));
        try (var factory = mockStatic(GraphDatabase.class)) {
            factory.when(() -> GraphDatabase.driver(any(URI.class), any(AuthToken.class), any(Config.class)))
                    .thenReturn(driver);
            probe.probe(request("bolt://localhost", "user", "secret", "neo4j"), () -> null);
            verify(session).run(eq("SHOW CURRENT USER YIELD user RETURN user"), any(TransactionConfig.class));
            verify(session).close();
            verify(driver).close();
        }
    }

    @Test
    void diagnosticPreservesAuthenticationWhenTargetFailsAndNeverPromisesBusinessRead() throws Exception {
        var driver=mock(Driver.class); var session=mock(Session.class);
        when(driver.session(any(SessionConfig.class))).thenReturn(session);
        when(session.run(eq("RETURN 1 AS connected"),any(TransactionConfig.class)))
                .thenThrow(new ClientException("Neo.ClientError.Security.Forbidden","private-target"));
        try(var factory=mockStatic(GraphDatabase.class)) {
            factory.when(()->GraphDatabase.driver(any(URI.class),any(AuthToken.class),any(Config.class))).thenReturn(driver);
            var result=probe.diagnose(request("bolt://localhost","user","secret","neo4j"),()->null);
            assertEquals("FAIL",result.status());
            assertTrue(result.checks().stream().anyMatch(c->c.key().equals("AUTH")&&c.status().equals("PASS")));
            assertTrue(result.checks().stream().anyMatch(c->c.key().equals("TARGET")&&c.status().equals("FAIL")));
            assertTrue(result.checks().stream().anyMatch(c->c.group().equals("READ")&&c.status().equals("SKIPPED")));
            assertTrue(result.checks().stream().anyMatch(c->c.key().equals("CURRENT_USER")&&c.status().equals("SKIPPED")&&"PREREQUISITE_FAILED".equals(c.reasonCode())));
            assertFalse(result.toString().contains("private-target")); verify(session).close(); verify(driver).close();
        }
    }

    private void assertTargetFailure(RuntimeException vendorFailure, ConnectionProbeException.Reason reason) {
        var driver = mock(Driver.class);
        var session = mock(Session.class);
        when(driver.session(any(SessionConfig.class))).thenReturn(session);
        when(session.run(eq("RETURN 1 AS connected"), any(TransactionConfig.class))).thenThrow(vendorFailure);
        try (var factory = mockStatic(GraphDatabase.class)) {
            factory.when(() -> GraphDatabase.driver(any(URI.class), any(AuthToken.class), any(Config.class)))
                    .thenReturn(driver);
            var failure = assertThrows(ConnectionProbeException.class,
                    () -> probe.probe(request("bolt://localhost", "user", "secret", "neo4j"), () -> null));
            assertEquals(reason, failure.reason());
            assertFalse(failure.toString().contains(vendorFailure.getMessage()));
            verify(session).close();
            verify(driver).close();
        }
    }

    static ConnectionProbeRequest request(String endpoint, String username, String password, String database) {
        return new ConnectionProbeRequest(ProductTypeEnum.NEO4J, null, null, endpoint,
                username, password, database, null, Map.of(), Duration.ofSeconds(2));
    }
}
