package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.entity.*;
import cn.iocoder.yudao.framework.dbswitch.common.util.BoundedRowCodec;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GroupRewriteMaterialTest {
    private static final BatchLimits LIMITS = new BatchLimits(20, 32768, 4096, 100, 1000);
    private static final List<String> FIELDS = List.of("ts", "id", "amount", "bytes", "native", "missing");
    private static final Timestamp TS = Timestamp.from(Instant.parse("2026-10-03T00:00:00.123456789Z"));
    private static Object[] row(int key) { return new Object[]{TS, key, new BigDecimal("12345678901234567890123456789.123456789"),
            new byte[]{0,-1,3}, new NativeCellValue("TEST_SCALAR_V1", new byte[]{1,2}, new byte[]{7}), MissingCellValue.INSTANCE}; }
    private static GroupRewriteMaterial material(List<Object[]> before, List<Object[]> after) {
        return GroupRewriteMaterial.create("a".repeat(64), "b".repeat(64), "c".repeat(64), FIELDS, List.of("ts", "id"), before, after, LIMITS);
    }
    @Test void materialSurvivesTypedRoundtripAndNeverExposesMutableImages() {
        var first = row(1); var second = row(2); var material = material(List.of(first, second), Collections.singletonList(second));
        String hash = material.hash(); ((byte[])first[3])[0] = 9; first[2] = null;
        var returned = material.before(); ((byte[])returned.getFirst()[3])[0] = 8; returned.getFirst()[0] = null;
        assertEquals(hash, material.hash()); assertEquals(TS, material.before().getFirst()[0]);
        assertArrayEquals(new byte[]{0,-1,3}, (byte[])material.before().getFirst()[3]);
        var decoded = GroupRewriteMaterial.decode(material.encoded(), LIMITS);
        assertEquals(hash, decoded.hash()); assertEquals(1, decoded.logicalDeletes());
        assertEquals(row(1)[2], decoded.before().getFirst()[2]); assertEquals(row(1)[4], decoded.before().getFirst()[4]);
        assertSame(MissingCellValue.INSTANCE, decoded.before().getFirst()[5]); assertEquals(2, decoded.after().getFirst()[1]);
        assertFalse(material.toString().contains("123456789"));
    }
    @Test void rewrittenSurvivorDuplicateKeyNullKeyAndInventedRowAreRejected() {
        Object[] changed = row(2); changed[2] = BigDecimal.ZERO;
        assertThrows(IllegalArgumentException.class, () -> material(List.of(row(1), row(2)), Collections.singletonList(changed)));
        assertThrows(IllegalArgumentException.class, () -> material(List.of(row(1), row(1)), List.of()));
        Object[] noKey = row(1); noKey[1] = null;
        assertThrows(IllegalArgumentException.class, () -> material(Collections.singletonList(noKey), List.of()));
        assertThrows(IllegalArgumentException.class, () -> material(List.of(row(1), row(2)), Collections.singletonList(row(3))));
    }
    @Test void damagedTrailingAndOversizedMaterialAreRejectedBeforePersistence() {
        var original = material(List.of(row(1), row(2)), Collections.singletonList(row(2))).encoded();
        byte[] damaged = original.clone(); damaged[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> GroupRewriteMaterial.decode(damaged, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> GroupRewriteMaterial.decode(Arrays.copyOf(original, original.length + 1), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> GroupRewriteMaterial.decode(original, new BatchLimits(20, 100, 100, 100, 1000)));
        assertThrows(IllegalArgumentException.class, () -> material(Collections.singletonList(row(1)), Collections.singletonList(row(1))));
    }
    @Test void sharedCodecRetainsNativeAndMissingTagsAndUnsignedAndArrayValues() {
        var values = new Object[]{new BigInteger("18446744073709551615"), row(1)[4], MissingCellValue.INSTANCE,
                new JdbcArrayValue("text", java.sql.Types.VARCHAR, Arrays.asList("中文", null, "NULL"))};
        var codec = new BoundedRowCodec(values.length, 4096); var result = codec.decode(codec.encode(values));
        assertEquals(values[0], result[0]); assertEquals(values[1], result[1]); assertSame(values[2], result[2]);
        assertEquals(((JdbcArrayValue)values[3]).elements(), ((JdbcArrayValue)result[3]).elements());
    }
    @Test void matchingKeysRejectEquivalentNativeNumericAndInstantRepresentations() {
        var nativeA = new NativeCellValue("KEY_V1", new byte[]{1}, new byte[]{7});
        var nativeB = new NativeCellValue("KEY_V1", new byte[]{2}, new byte[]{7});
        for (var pair : List.of(new Object[]{nativeA, nativeB}, new Object[]{1, 1L},
                new Object[]{new BigDecimal("1.0"), new BigDecimal("1.00")}, new Object[]{TS, TS.toInstant()})) {
            assertThrows(IllegalArgumentException.class, () -> GroupRewriteMaterial.create("a".repeat(64), "b".repeat(64), "c".repeat(64),
                    List.of("id", "value"), List.of("id"), List.of(new Object[]{pair[0], "a"}, new Object[]{pair[1], "b"}), List.of(), LIMITS));
        }
    }
}
