package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeOutputMappingsTest {
    @Test void editorMappingsPreserveSourceNamesAndUnicodeTargets() throws Exception {
        var fixture = EtlCompilerUiFixtures.configs();
        for (String code : List.of("write-hive", "write-odps", "write-ftp", "write-rabbitmq", "write-rocketmq")) {
            var mappings = EtlNativeOutputMappings.require(fixture.getJSONObject(code));
            assertEquals(List.of(Map.of("source", "id", "target", "id", "enabled", true),
                    Map.of("source", "name", "target", "标签", "enabled", true)), mappings, code);
            assertThrows(UnsupportedOperationException.class, () -> mappings.getFirst().put("source", "changed"));
        }
    }

    @Test void mismatchedDisabledReorderedOrInjectedMappingsAreRejected() {
        var config = new JSONObject().set("columns", List.of(Map.of("name", "标签", "key", "name", "type", "STRING")));
        for (var bad : List.of(Map.of("source", "标签", "target", "标签", "enabled", true),
                Map.of("source", "name", "target", "标签", "enabled", false),
                Map.of("source", "name", "target", "标签", "enabled", "true"),
                Map.of("source", "name", "target", "标签", "enabled", true, "expression", "secret"))) {
            config.set("fieldMappings", List.of(bad));
            assertThrows(IllegalArgumentException.class, () -> EtlNativeOutputMappings.require(config));
        }
        config.set("fieldMappings", List.of());
        assertThrows(IllegalArgumentException.class, () -> EtlNativeOutputMappings.require(config));
        config.remove("fieldMappings"); config.set("columns", List.of("id", "id"));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeOutputMappings.require(config));
        config.set("columns", List.of(Map.of("name", "id", "key", "name); DELETE FROM t")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeOutputMappings.require(config));
    }
}
