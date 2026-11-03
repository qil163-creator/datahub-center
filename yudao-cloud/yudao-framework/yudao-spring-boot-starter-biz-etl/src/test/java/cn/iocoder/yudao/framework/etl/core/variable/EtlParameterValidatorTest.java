package cn.iocoder.yudao.framework.etl.core.variable;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EtlParameterValidatorTest {

    @Test
    void validatesTypedValuesAndKeepsLargeNumbersAsStrings() {
        var defs = List.of(
                new EtlParameterDefinition("id", "INTEGER", true, "9007199254740993", "1", null, null, null),
                new EtlParameterDefinition("amount", "DECIMAL", false, null, "0.01", "999999999999999999.99", null, null),
                new EtlParameterDefinition("enabled", "BOOLEAN", false, "TRUE", null, null, null, null),
                new EtlParameterDefinition("label", "STRING", false, null, null, null, 2, 10));

        Map<String, String> values = EtlParameterValidator.resolve(defs,
                Map.of("amount", "9007199254740993.01", "label", "ready"),
                EtlParameterValidator.DefaultsPolicy.APPLY_DEFAULTS);

        assertEquals("9007199254740993", values.get("id"));
        assertEquals("9007199254740993.01", values.get("amount"));
        assertEquals("true", values.get("enabled"));
        assertEquals("ready", values.get("label"));
    }

    @Test
    void rejectsUnknownMissingInvalidOutOfRangeAndImplicitSubflowDefaults() {
        var required = List.of(new EtlParameterDefinition("count", "INTEGER", true, null, "1", "8", null, null));
        assertThrows(IllegalArgumentException.class, () -> EtlParameterValidator.resolve(required, Map.of(),
                EtlParameterValidator.DefaultsPolicy.APPLY_DEFAULTS));
        assertThrows(IllegalArgumentException.class, () -> EtlParameterValidator.resolve(required, Map.of("other", "1"),
                EtlParameterValidator.DefaultsPolicy.APPLY_DEFAULTS));
        assertThrows(IllegalArgumentException.class, () -> EtlParameterValidator.resolve(required, Map.of("count", "9"),
                EtlParameterValidator.DefaultsPolicy.APPLY_DEFAULTS));
        assertThrows(IllegalArgumentException.class, () -> EtlParameterValidator.resolve(required, Map.of("count", "1e3"),
                EtlParameterValidator.DefaultsPolicy.APPLY_DEFAULTS));
        var defaulted = List.of(new EtlParameterDefinition("count", "INTEGER", true, "2", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> EtlParameterValidator.resolve(defaulted, Map.of(),
                EtlParameterValidator.DefaultsPolicy.REQUIRE_ALL_EXPLICIT));
    }

    @Test
    void publicationCanRequireDeterministicDefaultsForRequiredParameters() {
        assertThrows(IllegalArgumentException.class, () -> EtlParameterValidator.validateDefinitions(
                List.of(new EtlParameterDefinition("x", "STRING", true, null, null, null, null, null)), true));
    }
}
