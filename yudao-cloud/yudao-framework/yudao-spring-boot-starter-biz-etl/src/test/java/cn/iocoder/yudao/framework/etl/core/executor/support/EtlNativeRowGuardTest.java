package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeRowGuardTest {
    private String validate(String type, String value) {
        var plan = JSONUtil.createObj().set("resultTypes", Map.of("v", Map.of("sqlType", type, "nullable", false)));
        return EtlNativeRowGuard.validate(JSONUtil.createObj().set("v", value).toString(), plan);
    }
    @Test void preservesLargeIntegersExactDecimalUnicodeAndMicrosecondTimestamp() {
        assertEquals("9007199254740993", JSONUtil.parseObj(validate("BIGINT", "9007199254740993")).getStr("v"));
        assertEquals("123.4500", JSONUtil.parseObj(validate("DECIMAL(10,4)", "123.45")).getStr("v"));
        assertEquals("水情😀", JSONUtil.parseObj(validate("STRING", "水情😀")).getStr("v"));
        assertTrue(validate("TIMESTAMP", "2026-11-02 08:01:02.123456").contains("123456"));
    }
    @Test void rejectsRoundingOverflowInvalidShapesAndUnboundedExponentExpansion() {
        for (String[] input : new String[][]{{"BIGINT", "0.000000000000000000001"}, {"BIGINT", "9223372036854775808"},
                {"DECIMAL(10,2)", "0.000000000000000000001"}, {"DECIMAL(10,2)", "999999999.12"}, {"DECIMAL(10,2)", "1e1000000000"},
                {"DECIMAL(10,2)", "1e-1000000000"}, {"DOUBLE", "1e-1000000000"}, {"BOOLEAN", "truthy"}, {"DATE", "2026-02-30"}, {"TIMESTAMP", "2026-11-02T08:01:02.123456789"}})
            assertThrows(IllegalArgumentException.class, () -> validate(input[0], input[1]), input[0]);
        var plan = JSONUtil.createObj().set("resultTypes", Map.of("v", Map.of("sqlType", "STRING", "nullable", false, "length", 2)));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRowGuard.validate("{}", plan));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRowGuard.validate("{\"v\":\"水情😀\"}", plan));
    }
}
