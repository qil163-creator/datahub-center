package cn.iocoder.yudao.framework.dbswitch.product.neo4j;

import cn.iocoder.yudao.framework.dbswitch.spi.ConnectionProbeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 显式选择本机临时实例；测试不创建、修改或删除数据库节点。 */
@EnabledIfEnvironmentVariable(named = "DATAHUB_NEO4J_TEST_PASSWORD", matches = ".+")
@EnabledIfSystemProperty(named = "datahub.test.neo4j.bolt", matches = "bolt://127[.]0[.]0[.]1:[0-9]+")
@Timeout(15)
class Neo4jNativeConnectionTest {
    private final Neo4jConnectionProbe probe = new Neo4jConnectionProbe();

    @Test
    void authenticatesAndChecksTheExplicitDatabase() {
        assertDoesNotThrow(() -> probe.probe(Neo4jConnectionProbeTest.request(endpoint(), "neo4j", password(), "neo4j"), () -> null));
    }

    @Test
    void rejectsWrongPasswordWithAnAuthenticationDiagnosis() {
        var failure = assertThrows(ConnectionProbeException.class,
                () -> probe.probe(Neo4jConnectionProbeTest.request(endpoint(), "neo4j", UUID.randomUUID().toString(), "neo4j"), () -> null));
        assertEquals(ConnectionProbeException.Reason.AUTHENTICATION, failure.reason());
        assertFalse(failure.toString().contains(password()));
    }

    @Test
    void rejectsMissingDatabaseWithATargetDiagnosis() {
        var failure = assertThrows(ConnectionProbeException.class,
                () -> probe.probe(Neo4jConnectionProbeTest.request(endpoint(), "neo4j", password(), "absent-" + UUID.randomUUID()), () -> null));
        assertEquals(ConnectionProbeException.Reason.TARGET_NOT_FOUND, failure.reason());
        assertFalse(failure.toString().contains(password()));
    }

    @Test
    void rejectsTlsAgainstAPlaintextServerWithoutDowngrade() {
        assertThrows(Exception.class,
                () -> probe.probe(Neo4jConnectionProbeTest.request(endpoint().replace("bolt:", "bolt+s:"), "neo4j", password(), "neo4j"), () -> null));
    }

    @Test
    void silentBoltPeerStopsWithinTheConnectionBudgetAndReleasesTheSocket() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            CompletableFuture<Socket> accepted = new CompletableFuture<>();
            Thread listener = Thread.ofPlatform().daemon().start(() -> {
                try { accepted.complete(server.accept()); }
                catch (Exception failure) { accepted.completeExceptionally(failure); }
            });
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThrows(Exception.class,
                        () -> probe.probe(Neo4jConnectionProbeTest.request("bolt://127.0.0.1:" + server.getLocalPort(),
                                "neo4j", password(), "neo4j"), () -> null)));
                try (var peer = accepted.get(1, TimeUnit.SECONDS)) {
                    peer.setSoTimeout(2000);
                    // 读完客户端握手后必须收到关闭，不能留下无限占用的套接字。
                    assertTrue(peer.getInputStream().readAllBytes().length > 0);
                }
            } finally {
                if (accepted.isDone() && !accepted.isCompletedExceptionally()) accepted.join().close();
                listener.interrupt();
            }
        }
    }

    private static String endpoint() { return System.getProperty("datahub.test.neo4j.bolt"); }
    private static String password() { return System.getenv("DATAHUB_NEO4J_TEST_PASSWORD"); }
}
