package cn.iocoder.yudao.framework.etl.core.script;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.etl.core.schema.EtlFieldContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtlNativeScriptPublicationTest {
    @TempDir Path directory;
    private static final String CERT = "a".repeat(64);
    private static final String SOURCE = "import java.util.*;import cn.iocoder.yudao.framework.etl.core.script.EtlJavaScript;public class Source implements EtlJavaScript {public List<Map<String,Object>> execute(List<Map<String,Object>> rows){return rows;}}";
    private EtlScriptRunner runner() {
        var runner = mock(EtlScriptRunner.class);
        var docker = new EtlScriptWorkerPolicy.Docker(Path.of("/usr/bin/false"), "unix:///tmp/docker.sock", "worker@sha256:" + "a".repeat(64), List.of(), false, 1000, 1000,
                new EtlScriptWorkerPolicy.Recovery(directory.toAbsolutePath(), "script-publication-test", 4, true));
        when(runner.policy()).thenReturn(new EtlScriptWorkerPolicy(true, List.of(), 65536, 1024, 1024, 10, 10, 1024, 100, 100, 64, EtlScriptWorkerPolicy.Mode.DOCKER, docker));
        return runner;
    }
    private EtlNativeScriptBroker broker(Map<String, EtlNativeScriptBroker.ResourcePolicy> resources) throws Exception {
        return new EtlNativeScriptBroker(new InetSocketAddress("127.0.0.1", 0), SSLContext.getDefault(), runner(), Map.of(), resources, 4, 10_000, 4096);
    }
    private static EtlNativeScriptBroker.Grant grant(String cert) {
        var fields = List.of(new EtlFieldContract("id", "Long", false, null, null, null));
        return new EtlNativeScriptBroker.Grant("4", "isolated", "SPARK", cert, DigestUtil.sha256Hex(SOURCE), SOURCE, EtlScriptRunner.Language.JAVA, fields, fields);
    }
    private static Map<String, EtlNativeScriptBroker.ResourcePolicy> resources() { return Map.of("scripts", new EtlNativeScriptBroker.ResourcePolicy(Set.of(new EtlNativeScriptBroker.Identity("4", "isolated", "SPARK", CERT)))); }
    @Test void preparedAndRolledBackVersionsCannotPassTheActualRuntimeAuthorizationGate() throws Exception {
        try (var broker = broker(resources())) {
            var version = broker.prepareVersion("scripts", "task4-rev2-graphA", grant(CERT));
            var claim = new EtlScriptBrokerProtocol.Claim(version.bindingId(), grant(CERT).sourceSha256(), version.epoch(), "4", "isolated", "SPARK", "run4", "0");
            assertFalse(broker.active(version));
            assertThrows(IllegalStateException.class, () -> open(broker, claim, CERT));
            broker.discardPrepared(List.of(version)); assertThrows(IllegalStateException.class, () -> broker.activateVersions(List.of(version)));
            var committed = broker.prepareVersion("scripts", "task4-rev2-graphA", grant(CERT)); broker.activateVersions(List.of(committed));
            assertTrue(broker.active(committed)); assertNotNull(open(broker, claim, CERT));
            broker.discardPrepared(List.of(committed)); assertTrue(broker.active(committed));
        }
    }
    @Test void taskIdentityDefaultDenyAndRestartedEpochPreventCrossTaskOrStaleVersionExecution() throws Exception {
        try (var broker = broker(resources()); var restarted = broker(resources()); var unconfigured = broker(Map.of())) {
            assertThrows(IllegalStateException.class, () -> unconfigured.prepareVersion("scripts", "task4-rev2", grant(CERT)));
            assertThrows(IllegalStateException.class, () -> broker.prepareVersion("scripts", "task4-rev2", grant("b".repeat(64))));
            var version = broker.prepareVersion("scripts", "task4-rev2", grant(CERT)); broker.activateVersions(List.of(version));
            assertFalse(restarted.active(version)); assertThrows(IllegalStateException.class, () -> restarted.activateVersions(List.of(version)));
            var claim = new EtlScriptBrokerProtocol.Claim(version.bindingId(), grant(CERT).sourceSha256(), version.epoch(), "4", "isolated", "SPARK", "run4", "0");
            assertThrows(IllegalStateException.class, () -> open(broker, claim, "b".repeat(64)));
        }
    }
    private static Object open(EtlNativeScriptBroker broker, EtlScriptBrokerProtocol.Claim claim, String fingerprint) {
        try {
            var method = EtlNativeScriptBroker.class.getDeclaredMethod("open", EtlScriptBrokerProtocol.Claim.class, String.class); method.setAccessible(true);
            return method.invoke(broker, claim, fingerprint);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
