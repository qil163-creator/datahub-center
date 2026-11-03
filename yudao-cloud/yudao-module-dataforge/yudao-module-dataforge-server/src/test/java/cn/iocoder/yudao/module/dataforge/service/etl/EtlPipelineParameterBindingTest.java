package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EtlPipelineParameterBindingTest {

    @Test
    void protectedIdentityScanMatchesTrimmedReferencesInJsonStringLeavesOnly() {
        var references = new HashSet<String>();
        var config = JSONUtil.parse("""
                {"${params.key}":"literal","source":"${ params.databaseId }",
                 "escaped":"${params.\\u0066ormat}","parameters":{"batch":"${params.batch}"}}
                """);
        EtlPipelineServiceImpl.collectParameterReferences(config, references);
        assertEquals(new HashSet<>(java.util.List.of("databaseId", "format", "batch")), references);
        assertFalse(references.contains("key"));
    }

    @Test
    void requestFingerprintBindsDefinitionRevisionEvenWithoutOverrides() {
        var service = new EtlPipelineServiceImpl();
        String first = service.manualInputFingerprint(5, Map.of());
        String retry = service.manualInputFingerprint(5, Map.of());
        String changedDefinition = service.manualInputFingerprint(6, Map.of());
        assertNotNull(first);
        assertEquals(first, retry);
        assertNotEquals(first, changedDefinition);
        assertNull(service.manualInputFingerprint(null, Map.of()));
    }
}
