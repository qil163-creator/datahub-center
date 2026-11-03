package cn.iocoder.yudao.framework.quality.core.clean;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CleanFunctionConfigTest {

    private final CleanFunctionRegistry registry = new CleanFunctionRegistry();

    @Test
    void allRegisteredFunctionExamplesHaveStrictCanonicalSchemas() {
        assertEquals(40, registry.listStrategies().size());
        registry.listStrategies().forEach(meta -> {
            Map<String, Object> example = JsonUtils.parseObject(meta.getConfigExample(), new TypeReference<>() {});
            assertDoesNotThrow(() -> registry.normalizeConfig(meta.getStrategyKey(), example), meta.getStrategyKey());
        });
    }

    @Test
    void rejectsUnknownParametersAndMalformedRequiredParameters() {
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("REMOVE_WHITESPACE",
                Map.of("handleType", 1, "sql", "select 1")));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("ADD_PREFIX_SUFFIX",
                Map.of("handleType", 1)));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("STRING_SUBSTR",
                Map.of("direction", 1)));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("NOT_A_FUNCTION", Map.of()));
    }

    @Test
    void canonicalizesExactDecimalAndRejectsExcessivePrecision() {
        String largeExact = "9007199254740993.123456789012345678901";
        Map<String, Object> normalized = registry.normalizeConfig("WITHIN_BOUNDARY",
                Map.of("min", largeExact, "max", "9007199254740994.123456789012345678901"));
        assertEquals(largeExact, normalized.get("min"));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("WITHIN_BOUNDARY",
                Map.of("min", 0, "max", 1)));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("WITHIN_BOUNDARY",
                Map.of("min", "0", "max", "1", "unexpected", "x")));
    }

    @Test
    void canonicalizesColumnsAndEnforcesFieldBindingBounds() {
        assertEquals(java.util.List.of("amount"), registry.normalizeConfig("FIX_TO_PRECISION",
                Map.of("scale", 3, "columns", java.util.List.of("amount"))).get("columns"));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("FIX_TO_PRECISION",
                Map.of("scale", 3, "columns", java.util.List.of("amount", "amount"))));
        assertThrows(IllegalArgumentException.class, () -> registry.normalizeConfig("CHECK_EXPIRATION",
                Map.of("dataRange", 0, "dataRangeValue", 2_000_000)));
    }
}
