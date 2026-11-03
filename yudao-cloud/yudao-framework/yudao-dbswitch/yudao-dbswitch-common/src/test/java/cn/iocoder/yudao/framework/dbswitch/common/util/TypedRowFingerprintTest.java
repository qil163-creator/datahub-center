package cn.iocoder.yudao.framework.dbswitch.common.util;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TypedRowFingerprintTest {
    @Test void typedHashNormalizesEquivalentJdbcScalarsAndPreservesPrecisionNullAndBinary() {
        var first = new LinkedHashMap<String, Object>();
        first.put("id", 9007199254740993L); first.put("small", 7); first.put("decimal", new BigDecimal("12.3400"));
        first.put("time", java.sql.Timestamp.valueOf("2026-10-03 12:34:56.123456"));
        first.put("bytes", new byte[] {0, 1, -1}); first.put("nullable", null);
        var equivalent = new TreeMap<>(first); equivalent.put("small", 7L);
        equivalent.put("decimal", new BigDecimal("12.34")); equivalent.put("time", LocalDateTime.parse("2026-10-03T12:34:56.123456"));
        assertEquals(TypedRowFingerprint.hash(first), TypedRowFingerprint.hash(equivalent));
        equivalent.put("id", 9007199254740992L); assertNotEquals(TypedRowFingerprint.hash(first), TypedRowFingerprint.hash(equivalent));
        equivalent.put("id", first.get("id")); equivalent.put("nullable", ""); assertNotEquals(TypedRowFingerprint.hash(first), TypedRowFingerprint.hash(equivalent));
        assertThrows(IllegalArgumentException.class, () -> TypedRowFingerprint.hash(Map.of("vendor", new Object())));
        assertThrows(IllegalArgumentException.class, () -> TypedRowFingerprint.hash(Map.of("invalid", "\ud800")));
    }

}
