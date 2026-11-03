package cn.iocoder.yudao.framework.etl.core.script;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeScriptProgramTest {
    private static EtlScriptBrokerClient.Binding binding(String engine) { return new EtlScriptBrokerClient.Binding("https://broker.internal:19443/v1/script", UUID.randomUUID().toString(), "script-fixed", "a".repeat(64), "9007199254740993", "isolated", engine, "task4.key", "task4.cert", "broker.ca"); }
    @Test void versionedProgramRetainsSqlSchemasAndIdentityReferencesWithoutEmbeddingSourceOrCredentials() {
        var fields = List.of(new EtlNativeScriptProgram.Field("id", "BIGINT", false), new EtlNativeScriptProgram.Field("amount", "DECIMAL(24,4)", true));
        var stage = new EtlNativeScriptProgram.Stage("java1", "SELECT id,amount FROM `input` WHERE label='汉字'", "dh_native_script_" + "a".repeat(24), fields, fields, binding("SPARK"), true, 1000, 0);
        var program = new EtlNativeScriptProgram("SPARK", false, 1, List.of("CREATE TEMPORARY VIEW input AS SELECT 1 AS id,2 AS amount"), List.of(stage), List.of("INSERT INTO output SELECT * FROM " + stage.view()));
        var chunks = program.argumentChunks(); assertTrue(chunks.stream().allMatch(value -> value.length() <= 2048));
        assertEquals(program, EtlNativeScriptProgram.fromArguments(chunks.toArray(String[]::new)));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeScriptProgram.fromArguments(new String[]{"${token}"}));
        assertThrows(IllegalArgumentException.class, () -> new EtlNativeScriptProgram.Field("amount", "DECIMAL(2,4)", true));
    }
    @Test void realtimeStagesRequireExplicitFiniteBatchBoundariesAndRejectPartitionBuffers() {
        var fields = List.of(new EtlNativeScriptProgram.Field("id", "BIGINT", true));
        var partition = new EtlNativeScriptProgram.Stage("java1", "SELECT id FROM source", "dh_native_script_" + "a".repeat(24), fields, fields, binding("FLINK"), true, 10, 0);
        assertThrows(IllegalArgumentException.class, () -> new EtlNativeScriptProgram("FLINK", true, 1, List.of(), List.of(partition), List.of("INSERT INTO target SELECT id FROM source")));
        var window = new EtlNativeScriptProgram.Stage("java1", "SELECT id FROM source", "dh_native_script_" + "a".repeat(24), fields, fields, binding("FLINK"), false, 10, 1000);
        var program = new EtlNativeScriptProgram("FLINK", true, 1, List.of(), List.of(window), List.of("INSERT INTO target SELECT id FROM source"));
        assertEquals(program, EtlNativeScriptProgram.fromArguments(program.argumentChunks().toArray(String[]::new)));
        assertThrows(IllegalArgumentException.class, () -> new EtlNativeScriptProgram.Stage("java1", "SELECT id FROM source", "dh_native_script_" + "a".repeat(24), fields, fields, binding("FLINK"), false, 10, 0));
    }
    @Test void privateHttpContextOnlyContainsProtectedReferencesAndKeepsBaseEndpointSeparateFromQuery() {
        var context = new EtlScriptAuthenticatedHttp.Context(binding("SPARK"), "run4", Map.of("login", UUID.randomUUID().toString()));
        assertEquals(context, EtlScriptAuthenticatedHttp.decode(EtlScriptAuthenticatedHttp.encode(context)));
        var request = new EtlScriptAuthenticatedHttp.Request("weather", "https://api.internal/data", "GET", Map.of("page", "2"), Map.of(), new byte[0], 1024, 1000);
        assertEquals("2", request.query().get("page"));
        assertThrows(IllegalArgumentException.class, () -> EtlScriptAuthenticatedHttp.exchange(null, request, Map.of("Authorization", new EtlScriptAuthenticatedHttp.HeaderReference("login", "Bearer "))));
        assertThrows(IllegalArgumentException.class, () -> new EtlScriptAuthenticatedHttp.HeaderReference("login", "Bearer\r\n"));
        assertFalse(new EtlScriptAuthenticatedHttp.Request("weather", "https://api.internal/data", "POST", Map.of(), Map.of("Authorization", "sensitive-value"), new byte[0], 1024, 1000).toString().contains("sensitive-value"));
    }
}
