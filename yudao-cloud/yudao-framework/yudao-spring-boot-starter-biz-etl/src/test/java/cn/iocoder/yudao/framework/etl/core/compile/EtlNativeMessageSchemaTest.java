package cn.iocoder.yudao.framework.etl.core.compile;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeMessageSchemaTest {
    @Test void nativeDecimalMetadataAndMillisecondTimestampRemainLosslessAndImmutable() {
        var shape = EtlNativeMessageSchema.columns(List.of(Map.of("name", "amount", "type", "DECIMAL(30,4)", "length", 30, "precision", 4), Map.of("name", "time", "type", "TIMESTAMP(3)")));
        assertEquals(List.of("amount", "time"), new ArrayList<>(shape.keySet())); assertEquals("DECIMAL(30,4)", shape.get("amount"));
        assertThrows(UnsupportedOperationException.class, () -> shape.put("extra", "STRING"));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeMessageSchema.columns(List.of(Map.of("name", "amount", "type", "DECIMAL(30,4)", "precision", 3))));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeMessageSchema.columns(List.of(Map.of("name", "time", "type", "TIMESTAMP(6)"))));
        assertEquals(Map.of("水位", "BIGINT"), EtlNativeMessageSchema.columns(List.of(Map.of("name", "水位", "key", "$.水位", "type", "LONG"))));
        assertEquals(Map.of("target", "STRING"), EtlNativeMessageSchema.columns(List.of(Map.of("name", "target", "key", "source", "type", "STRING")), true));
    }
}
