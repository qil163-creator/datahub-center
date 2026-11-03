package cn.iocoder.yudao.framework.etl.core.compile;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeHttpCompilerTest {
    @Test void preservesMultiPathAndDeclaredDateDefaultInBothEnginePlans() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            var config = new java.util.LinkedHashMap<>(configuration());
            var api = new java.util.LinkedHashMap<>((Map<String, Object>) config.get("apiConfig"));
            api.put("dataKeys", List.of("left.rows", "$.right.rows"));
            api.put("dataColumns", List.of(Map.of("fieldName", "date", "fieldType", 91, "format", "yyyy/MM/dd", "defaultValue", "2026/11/03")));
            config.put("apiConfig", api);
            var resource = new EtlDataxConnectorCompiler.Binding(Set.of("rest-api"), null, null, binding().addresses(), null,
                    EtlDataxConnectorCompiler.Authentication.NONE, Set.of(engine == EtlNativeSqlCompiler.Engine.SPARK ? "spark-http" : "flink-bounded-http"));
            var result = EtlNativeHttpCompiler.compile(engine, false, "date-default", "rest-api", config, resource, Set.of("DH_READ_API"), Set.of());
            String sql = result.initializationSql(); String payload = sql.substring(sql.indexOf(engine == EtlNativeSqlCompiler.Engine.SPARK ? "DH_READ_API('" : "'plan' = '") + (engine == EtlNativeSqlCompiler.Engine.SPARK ? 13 : 10)).split("'", 2)[0];
            var plan = cn.hutool.json.JSONUtil.parseObj(new String(java.util.Base64.getDecoder().decode(payload), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("yyyy/MM/dd", plan.getJSONObject("resultTypes").getJSONObject("date").getStr("format"));
            assertEquals("2026/11/03", plan.getJSONObject("resultTypes").getJSONObject("date").getStr("defaultValue"));
            assertEquals(2, plan.getJSONObject("apiConfig").getJSONArray("dataKeys").size());
            api.put("dataKeys", List.of("left.rows", "$.left.rows"));
            assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(engine, false, "n", "rest-api", config, resource, Set.of("DH_READ_API"), Set.of()));
        }
    }
    @Test void contextAuthenticationRequiresPublishedUpstreamKeyAndRejectsHeaderCollisions() {
        var authenticated = new EtlDataxConnectorCompiler.Binding(Set.of("rest-api"), null, null, binding().addresses(), null,
                EtlDataxConnectorCompiler.Authentication.REQUIRED, Set.of("spark-http"));
        var config = new java.util.LinkedHashMap<>(configuration());
        config.put("headerContextRefs", Map.of("Authorization", Map.of("tokenKey", "accessToken", "prefix", "Bearer ")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "rest-api", config, authenticated, Set.of("DH_READ_API"), Set.of()));
        assertNotNull(EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "rest-api", config, authenticated, Set.of("DH_READ_API"), Set.of(), Set.of("accessToken")));
        config.put("headerSecretRefs", Map.of("authorization", "apiAccess"));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "rest-api", config, authenticated, Set.of("DH_READ_API"), Set.of("apiAccess"), Set.of("accessToken")));
        config.remove("headerSecretRefs"); config.put("headerContextRefs", Map.of("Authorization", Map.of("tokenKey", "accessToken", "prefix", "Bearer\r\n")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "rest-api", config, authenticated, Set.of("DH_READ_API"), Set.of(), Set.of("accessToken")));
    }
    private EtlDataxConnectorCompiler.Binding binding() {
        return new EtlDataxConnectorCompiler.Binding(Set.of("rest-api"), null, null, List.of("http://127.0.0.1:19084/rows"), null,
                EtlDataxConnectorCompiler.Authentication.NONE, Set.of("spark-http"));
    }
    private Map<String, Object> configuration() {
        return Map.of("resourceBindingId", "http", "fetchSize", 1, "maxPages", 4, "apiConfig", Map.of("requestUrl", "http://127.0.0.1:19084/rows", "requestMethod", "GET",
                "dataColumns", List.of(Map.of("fieldName", "id", "fieldType", -5), Map.of("fieldName", "label", "fieldType", 12)),
                "dataKey", "data.rows", "ifPage", 1, "pageKey", "pageNo", "pageSizeKey", "pageSize"));
    }
    @Test void declaresTypedBoundedSourceAndConsumesBudgets() throws Exception {
        var plan = EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "task-node", "rest-api", configuration(), binding(), Set.of("DH_READ_API"), Set.of());
        assertFalse(plan.table().continuousSource()); assertEquals(Map.of("id", "BIGINT", "label", "STRING"), plan.table().columns());
        assertTrue(plan.initializationSql().contains("LATERAL VIEW DH_READ_API('"));
        assertTrue(plan.initializationSql().contains("TRY_CAST")); assertTrue(plan.initializationSql().contains("FLOOR"));
        assertFalse(plan.initializationSql().contains("http://fixture"));
        var directory = java.nio.file.Path.of("target/native-http-sql"); java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("source.sql"), plan.initializationSql() + "SELECT id, label FROM `" + plan.table().path().getFirst() + "` ORDER BY id;\n");
    }
    @Test void rejectsWrongEngineMissingFunctionUnboundUrlAndInlineAuthentication() {
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.FLINK, true, "n", "rest-api", configuration(), binding(), Set.of("DH_READ_API"), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "rest-api", configuration(), binding(), Set.of(), Set.of()));
        var config = new java.util.LinkedHashMap<>(configuration());
        var api = new java.util.LinkedHashMap<>((Map<String, Object>) config.get("apiConfig"));
        api.put("requestHeader", List.of(Map.of("name", "Authorization", "value", "Bearer test-only"))); config.put("apiConfig", api);
        var failure = assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "rest-api", config, binding(), Set.of("DH_READ_API"), Set.of()));
        assertFalse(failure.getMessage().contains("Bearer"));
        api.remove("requestHeader"); api.put("requestUrl", "http://fixture:8080/private");
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "rest-api", config, binding(), Set.of("DH_READ_API"), Set.of()));
    }
    @Test void bindsOnlyAuthorizedHeaderReferencesAndWritesAuthenticatedRuntimeFixture() throws Exception {
        var authenticated = new EtlDataxConnectorCompiler.Binding(Set.of("rest-api"), null, null, binding().addresses(), null,
                EtlDataxConnectorCompiler.Authentication.REQUIRED, Set.of("spark-http"));
        var config = new java.util.LinkedHashMap<>(configuration());
        config.put("headerSecretRefs", Map.of("Authorization", "apiAccess"));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "rest-api", config, authenticated, Set.of("DH_READ_API"), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "rest-api", config, binding(), Set.of("DH_READ_API"), Set.of("apiAccess")));
        var plan = EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "authenticated", "rest-api", config, authenticated, Set.of("DH_READ_API"), Set.of("apiAccess"));
        assertFalse(plan.initializationSql().contains("Bearer"));
        var directory = java.nio.file.Path.of("target/native-http-sql"); java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("authenticated.sql"), plan.initializationSql() + "SELECT id, label FROM `" + plan.table().path().getFirst() + "` ORDER BY id;\n");
        config.put("headerSecretRefs", Map.of("Host", "apiAccess"));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "rest-api", config, authenticated, Set.of("DH_READ_API"), Set.of("apiAccess")));
        config.remove("headerSecretRefs");
        var api = new java.util.LinkedHashMap<>((Map<String, Object>) config.get("apiConfig"));
        api.put("requestMethod", "POST"); api.put("bodyType", "raw"); api.put("bodyRaw", "{\"nested\":{\"password\":\"test-only\"}}");
        config.put("apiConfig", api);
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "rest-api", config, binding(), Set.of("DH_READ_API"), Set.of()));
    }
}
