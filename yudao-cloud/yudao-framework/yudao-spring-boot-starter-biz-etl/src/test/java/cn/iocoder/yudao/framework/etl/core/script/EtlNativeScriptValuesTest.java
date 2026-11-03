package cn.iocoder.yudao.framework.etl.core.script;

import org.junit.jupiter.api.Test;
import java.math.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeScriptValuesTest {
    @Test void retainsUnsafeJsLongDecimalNanosNullAndExactDeclaredScalarTypes() {
        var fields = List.of(field("id", "BIGINT", false), field("amount", "DECIMAL(30,4)", false), field("time", "TIMESTAMP(9)", false), field("day", "DATE", false), field("text", "STRING", true));
        var time = LocalDateTime.of(2026, 11, 2, 1, 2, 3, 123456789); var day = LocalDate.of(2026, 11, 2);
        var original = Arrays.asList(9007199254740993L, new BigDecimal("12345678901234567890.1234"), time, day, null);
        var row = EtlNativeScriptValues.input(original, fields);
        assertEquals(original, EtlNativeScriptValues.output(row, fields));
        assertInstanceOf(Byte.class, EtlNativeScriptValues.scalar(1L, field("n", "TINYINT", false)));
        assertInstanceOf(Short.class, EtlNativeScriptValues.scalar(1L, field("n", "SMALLINT", false)));
        assertInstanceOf(Integer.class, EtlNativeScriptValues.scalar(1L, field("n", "INT", false)));
        assertInstanceOf(Long.class, EtlNativeScriptValues.scalar(1L, field("n", "BIGINT", false)));
        assertEquals(time, EtlNativeScriptValues.scalar(java.sql.Timestamp.valueOf(time), fields.get(2)));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeScriptValues.scalar(time, field("n", "TIMESTAMP(6)", false)));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeScriptValues.scalar(new BigDecimal("1.00001"), fields.get(1)));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeScriptValues.scalar(2147483648L, field("n", "INT", false)));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeScriptValues.scalar(1.5, fields.get(0)));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeScriptValues.scalar(Double.NaN, field("n", "DOUBLE", true)));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeScriptValues.scalar(null, fields.get(0)));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeScriptValues.output(Map.of("extra", "value"), fields));
    }
    private static EtlNativeScriptProgram.Field field(String name, String type, Boolean nullable) { return new EtlNativeScriptProgram.Field(name, type, nullable); }
}
