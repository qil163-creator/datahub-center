package cn.iocoder.yudao.framework.dbswitch.common.entity;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FrozenRecordTransformerTest {
    @Test void nullCallbackCannotMutateOriginalBinaryOrTimestamp() {
        byte[] bytes = {1,2}; var stamp=java.sql.Timestamp.valueOf("2026-10-03 12:30:00.123456789");
        var transform = new FrozenRecordTransformer(new RecordTransformDefinition("mutable","1","a".repeat(64)),
                (schema,table,fields,values)->{((byte[])values[0])[0]=9;((java.sql.Timestamp)values[1]).setNanos(0);return null;}, BatchLimits.defaults());
        Object[] output=transform.transform("s","t",List.of("binary","stamp"),new Object[]{bytes,stamp});
        assertArrayEquals(new byte[]{1,2},bytes);assertArrayEquals(new byte[]{1,2},(byte[])output[0]);
        assertEquals(123456789,stamp.getNanos());assertEquals(123456789,((java.sql.Timestamp)output[1]).getNanos());
        assertNotSame(bytes,output[0]);assertNotSame(stamp,output[1]);
    }
    @Test void callbackReceivesOwnedArrayAndImmutableFieldsAndVersionIdentityIsRetained() {
        var definition = new RecordTransformDefinition("fixture", "v1", "a".repeat(64));
        var fields = new ArrayList<>(List.of("id", "text")); var row = new Object[]{1, "before"};
        var transform = new FrozenRecordTransformer(definition, (schema, table, names, values) -> {
            assertThrows(UnsupportedOperationException.class, () -> names.add("illegal"));
            values[1] = "after"; return values;
        }, BatchLimits.defaults());
        var result = transform.transform("fixture", "rows", fields, row);
        assertSame(definition, transform.definition()); assertTrue(definition.versioned());
        assertArrayEquals(new Object[]{1, "before"}, row); assertArrayEquals(new Object[]{1, "after"}, result);
        assertEquals(List.of("id", "text"), fields);
    }

    @Test void nullResultDoesNotSilentlyFilterOrPartiallyMutateInput() {
        var transform = new FrozenRecordTransformer(RecordTransformDefinition.legacy(), (s, t, f, row) -> {
            row[0] = "changed"; return null;
        }, BatchLimits.defaults());
        assertFalse(transform.definition().versioned());
        assertArrayEquals(new Object[]{"before"}, transform.transform("s", "t", List.of("f"), new Object[]{"before"}));
    }

    @Test void badWidthAndCallbackFailureAreFailClosedAndSanitized() {
        for (RecordTransformer callback : List.<RecordTransformer>of((s, t, f, row) -> new Object[0],
                (s, t, f, row) -> { throw new IllegalStateException("private-source-value"); })) {
            var transform = new FrozenRecordTransformer(RecordTransformDefinition.legacy(), callback, BatchLimits.defaults());
            var failure = assertThrows(BatchRejectedException.class,
                    () -> transform.transform("s", "t", List.of("f"), new Object[]{"before"}));
            assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("private-source-value"));
        }
    }
}
