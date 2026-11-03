package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeBoundaryCompilerTest {
    @Test void compatibleTypesKeepEverySourceFieldAndAllowWideningIntegerContracts() {
        var types = Map.of("id", "SMALLINT", "label", "VARCHAR(50)", "extra", "BOOLEAN");
        assertDoesNotThrow(() -> EtlNativeBoundaryCompiler.validate(fields(
                Map.of("name", "id", "type", "Long"), Map.of("name", "label", "type", "String")), types));
        assertEquals(3, types.size());
    }

    @Test void unknownMissingNarrowingAndNonNullContractsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> EtlNativeBoundaryCompiler.validate(fields(
                Map.of("name", "missing", "type", "Object")), Map.of("id", "BIGINT")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeBoundaryCompiler.validate(fields(
                Map.of("name", "id", "type", "Integer")), Map.of("id", "BIGINT")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeBoundaryCompiler.validate(fields(
                Map.of("name", "id", "type", "Long", "nullable", false)), Map.of("id", "BIGINT")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeBoundaryCompiler.validate(fields(
                Map.of("name", "id", "type", "Long")), Map.of("id", "unknown")));
    }

    @Test void decimalDomainMustFitExactlyIncludingZerosAddedByScale() {
        assertDoesNotThrow(() -> EtlNativeBoundaryCompiler.validate(fields(
                Map.of("name", "amount", "type", "Decimal", "precision", 20, "scale", 4)), Map.of("amount", "DECIMAL(18,2)")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeBoundaryCompiler.validate(fields(
                Map.of("name", "amount", "type", "Decimal", "precision", 18, "scale", 4)), Map.of("amount", "DECIMAL(18,2)")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeBoundaryCompiler.validate(fields(
                Map.of("name", "amount", "type", "Decimal", "precision", 20, "scale", 1)), Map.of("amount", "DECIMAL(18,2)")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeBoundaryCompiler.validate(fields(
                Map.of("name", "amount", "type", "Decimal", "precision", 20)), Map.of("amount", "DOUBLE")));
    }

    @SafeVarargs
    private static JSONObject fields(Map<String, Object>... fields) { return new JSONObject().set("fields", List.of(fields)); }
}
