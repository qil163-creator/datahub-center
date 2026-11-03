package cn.iocoder.yudao.framework.dolphinscheduler.api;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class LocalApplicationApiTest {

    private static final String NAME = "dh_native_200";

    @Test
    void observesRealCompletionWithoutLeakingLogBody() {
        var execution = parse(item("SUCCEEDED", "0", "false", "true", "true")).getFirst();
        assertEquals(NAME, execution.name());
        assertEquals("datahub", execution.user());
        assertEquals("FLINK", execution.engine());
        assertTrue(execution.started());
        assertTrue(execution.finished());
        assertFalse(execution.processTreeAlive());
        assertEquals(0, execution.exitCode());
        assertFalse(execution.toString().contains("sensitive-output"));
    }

    @Test
    void absenceAndLiveProcessCannotProveTermination() {
        assertTrue(parse("").isEmpty());
        var running = parse(item("RUNNING", "null", "true", "true", "false")).getFirst();
        assertFalse(running.finished());
        assertTrue(running.processTreeAlive());
    }

    @Test
    void acceptsObservedFailureAndCancellation() {
        assertEquals(1, parse(item("FAILED", "1", "false", "true", "true")).getFirst().exitCode());
        assertEquals("CANCELLED", parse(item("CANCELLED", "-15", "false", "true", "true"))
                .getFirst().state());
    }

    @Test
    void rejectsConflictingCompletionAndCoercedValues() {
        for (String candidate : new String[]{
                item("SUCCEEDED", "0", "true", "true", "true"),
                item("SUCCEEDED", "1", "false", "true", "true"),
                item("FAILED", "0", "false", "true", "true"),
                item("RUNNING", "null", "false", "true", "true"),
                item("SUCCEEDED", "0", "false", "false", "true"),
                item("SUCCEEDED", "\"0\"", "false", "true", "true"),
                item("SUCCEEDED", "0.5", "false", "true", "true"),
                item("SUCCEEDED", "0", "false", "\"true\"", "true"),
                item("SUCCEEDED", "0", "false", "true", "true").replace("FLINK", "SHELL")}) {
            assertThrows(IllegalStateException.class, () -> parse(candidate));
        }
    }

    @Test
    void rejectsDifferentIdentityMalformedAndTruncatedResults() {
        for (String candidate : new String[]{
                item("SUCCEEDED", "0", "false", "true", "true").replace(NAME, "dh_native_201"),
                item("SUCCEEDED", "0", "false", "true", "true").replace("datahub", "root"),
                String.join(",", java.util.Collections.nCopies(65,
                        item("SUCCEEDED", "0", "false", "true", "true")))}) {
            assertThrows(IllegalStateException.class, () -> parse(candidate));
        }
        assertThrows(IllegalStateException.class, () -> LocalApplicationApi.parse(NAME, "datahub", "{}".getBytes()));
    }

    @Test
    void onlyTrustsValidatedHttpsEndpoints() {
        for (String endpoint : new String[]{"http://localhost", "https://u:p@localhost", "https://localhost/?x=y",
                "https://localhost/a/../b", "https://localhost/%2e%2e/b", "https://localhost/a//b"}) {
            assertThrows(IllegalArgumentException.class, () -> LocalApplicationApi.validateEndpoint(URI.create(endpoint)));
        }
        assertEquals(URI.create("https://localhost/worker"),
                LocalApplicationApi.validateEndpoint(URI.create("https://localhost/worker")));
    }

    private static java.util.List<LocalApplicationApi.Execution> parse(String items) {
        return LocalApplicationApi.parse(NAME, "datahub", ("{\"executions\":[" + items + "]}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String item(String state, String exitCode, String alive, String started, String finished) {
        return "{\"name\":\"" + NAME + "\",\"user\":\"datahub\",\"engine\":\"FLINK\",\"state\":\"" + state
                + "\",\"exitCode\":" + exitCode + ",\"processTreeAlive\":" + alive + ",\"started\":" + started
                + ",\"finished\":" + finished + ",\"log\":\"sensitive-output\"}";
    }
}
