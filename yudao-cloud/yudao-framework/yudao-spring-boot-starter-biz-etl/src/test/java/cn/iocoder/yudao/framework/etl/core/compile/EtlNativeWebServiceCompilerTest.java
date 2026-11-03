package cn.iocoder.yudao.framework.etl.core.compile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeWebServiceCompilerTest {
    @org.junit.jupiter.api.Test void consumesActualEditorJsonTextDefaults() throws Exception {
        var config = EtlCompilerUiFixtures.configs().getJSONObject("read-webservice");
        var binding = new EtlDataxConnectorCompiler.Binding(java.util.Set.of("read-webservice"), null, null, java.util.List.of(config.getStr("apiUrl")), null,
                EtlDataxConnectorCompiler.Authentication.NONE, java.util.Set.of("spark-webservice"));
        var plan = EtlNativeWebServiceCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "ui-default", "read-webservice", config, binding, java.util.Set.of("DH_READ_SOAP"), java.util.Set.of());
        org.junit.jupiter.api.Assertions.assertEquals("BIGINT", plan.table().columns().get("id"));
        org.junit.jupiter.api.Assertions.assertEquals("STRING", plan.table().columns().get("name"));
    }
    private EtlDataxConnectorCompiler.Binding binding() {
        return new EtlDataxConnectorCompiler.Binding(Set.of("read-webservice"), null, null, List.of("http://127.0.0.1:19085/wsdl", "http://127.0.0.1:19085/soap11", "http://127.0.0.1:19085/soap12", "http://127.0.0.1:19085/httpGet/rows", "http://127.0.0.1:19085/httpPost/rows"), null,
                EtlDataxConnectorCompiler.Authentication.REQUIRED, Set.of("spark-webservice"));
    }
    private Map<String, Object> config(String method) {
        return new LinkedHashMap<>(Map.of("apiUrl", "http://127.0.0.1:19085/wsdl", "operationName", "List", "apiMethod", method, "serviceName", "Service", "portName", method + "Port",
                "requestParams", Map.of("label", "O'Reilly<&水"), "returnDataLine", "ListResponse/rows/row", "headerSecretRefs", Map.of("Authorization", "apiAccess"),
                "columns", List.of(Map.of("name", "id", "type", "LONG"), Map.of("name", "label", "type", "STRING"))));
    }
    @Test void generatesAllFourProtocolFixturesWithExplicitTypesAndOnlySecretReferences() throws Exception {
        Path directory = Path.of("target/native-webservice-sql"); Files.createDirectories(directory);
        for (String method : List.of("soap11", "soap12", "httpGet", "httpPost")) {
            var plan = EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, method, "read-webservice", config(method), binding(), Set.of("DH_READ_SOAP"), Set.of("apiAccess"));
            assertEquals(Map.of("id", "BIGINT", "label", "STRING"), plan.table().columns()); assertTrue(plan.initializationSql().contains("LATERAL VIEW DH_READ_SOAP('"));
            Files.writeString(directory.resolve(method + ".sql"), plan.initializationSql() + "SELECT id, label FROM `" + plan.table().path().getFirst() + "` ORDER BY id;\n");
        }
        Files.writeString(directory.resolve("fixture.wsdl"), cn.iocoder.yudao.framework.etl.core.executor.support.EtlWebServiceRowsTest.wsdl("http://127.0.0.1:19085"));
    }
    @Test void rejectsUninstalledUnauthorizedAndInlineAuthentication() {
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.FLINK, true, "n", "read-webservice", config("soap11"), binding(), Set.of("DH_READ_SOAP"), Set.of("apiAccess")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "read-webservice", config("soap11"), binding(), Set.of("DH_READ_SOAP"), Set.of()));
        var config = config("soap11"); config.put("headerParams", Map.of("Authorization", "Bearer test-only"));
        var failure = assertThrows(IllegalArgumentException.class, () -> EtlNativeHttpCompiler.compile(EtlNativeSqlCompiler.Engine.SPARK, false, "n", "read-webservice", config, binding(), Set.of("DH_READ_SOAP"), Set.of("apiAccess")));
        assertFalse(failure.toString().contains("Bearer"));
    }
}
