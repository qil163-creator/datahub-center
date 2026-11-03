package cn.iocoder.yudao.framework.etl.core.script;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EtlScriptRuntimeContextTest {
    private static EtlScriptAuthenticatedHttp.Context context() {
        var binding = new EtlScriptBrokerClient.Binding("https://broker.internal/v1/script", UUID.randomUUID().toString(), "script-fixed", "a".repeat(64), "1004", "isolated", "FLINK", "key", "cert", "ca");
        return new EtlScriptAuthenticatedHttp.Context(binding, "run-1", Map.of("login", UUID.randomUUID().toString()));
    }
    @Test void trustedScopeIsThreadBoundDoesNotInheritAndAlwaysClearsAfterFailure() throws Exception {
        assertNull(EtlScriptAuthenticatedHttp.currentRuntimeContext());
        var context = context();
        try (var scope = EtlScriptAuthenticatedHttp.openRuntimeContext(context)) {
            assertEquals(context, EtlScriptAuthenticatedHttp.decode(EtlScriptAuthenticatedHttp.currentRuntimeContext()));
            assertEquals("ManagedScriptRuntimeScope", scope.toString());
            assertThrows(IllegalArgumentException.class, () -> EtlScriptAuthenticatedHttp.openRuntimeContext(context));
            var inherited = new AtomicReference<String>();
            var thread = new Thread(() -> inherited.set(EtlScriptAuthenticatedHttp.currentRuntimeContext())); thread.start(); thread.join();
            assertNull(inherited.get());
        }
        assertNull(EtlScriptAuthenticatedHttp.currentRuntimeContext());
        assertThrows(IllegalStateException.class, () -> {
            try (var ignored = EtlScriptAuthenticatedHttp.openRuntimeContext(context)) { throw new IllegalStateException("fixture"); }
        });
        assertNull(EtlScriptAuthenticatedHttp.currentRuntimeContext());
    }
}
