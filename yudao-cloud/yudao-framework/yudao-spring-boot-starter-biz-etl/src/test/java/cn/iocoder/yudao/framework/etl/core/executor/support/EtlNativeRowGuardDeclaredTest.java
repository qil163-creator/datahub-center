package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeRowGuardDeclaredTest {
    @Test void validatesDeclaredDefaultsBeforeSubmissionAndDoesNotReplaceMalformedRealValues() {
        var guard = JSONUtil.createObj().set("sqlType", "BIGINT");
        EtlNativeRowGuard.declaredOptions(JSONUtil.createObj().set("defaultValue", "9007199254740993"), guard);
        var plan = JSONUtil.createObj().set("resultTypes", JSONUtil.createObj().set("id", guard));
        assertEquals("9007199254740993", JSONUtil.parseObj(EtlNativeRowGuard.normalizeDeclared("{}", plan)).getStr("id"));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRowGuard.normalizeDeclared("{\"id\":\"1.1\"}", plan));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRowGuard.declaredOptions(JSONUtil.createObj().set("defaultValue", "1.1"), JSONUtil.createObj().set("sqlType", "BIGINT")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRowGuard.declaredOptions(JSONUtil.createObj().set("format", "yyyy"), JSONUtil.createObj().set("sqlType", "STRING")));
    }
    @Test void parsesStrictCustomTimestampWithoutRoundingAndLeavesUnconfiguredSourcesUnchanged() {
        var guard = JSONUtil.createObj().set("sqlType", "TIMESTAMP");
        EtlNativeRowGuard.declaredOptions(JSONUtil.createObj().set("format", "yyyy/MM/dd HH:mm:ss.SSSSSS"), guard);
        var plan = JSONUtil.createObj().set("resultTypes", JSONUtil.createObj().set("at", guard));
        assertEquals("2026-11-03T08:09:10.123456", JSONUtil.parseObj(EtlNativeRowGuard.normalizeDeclared("{\"at\":\"2026/11/03 08:09:10.123456\"}", plan)).getStr("at"));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRowGuard.normalizeDeclared("{\"at\":\"2026/02/30 08:09:10.123456\"}", plan));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeRowGuard.validate("{\"at\":\"2026/11/03 08:09:10.123456\"}", plan));
    }
}
