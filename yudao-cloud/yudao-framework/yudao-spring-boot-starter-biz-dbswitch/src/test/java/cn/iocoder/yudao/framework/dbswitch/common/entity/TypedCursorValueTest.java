package cn.iocoder.yudao.framework.dbswitch.common.entity;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class TypedCursorValueTest {
    @Test void exactTypesNeverUseFloatingPointOrDelimiterSplitting(){
        var decimal=new BigDecimal("123456789012345678901234567890.12345678");
        assertEquals(decimal,TypedCursorValue.of(Types.NUMERIC,"numeric",decimal).jdbcValue());
        assertEquals("a,b\u0000中",TypedCursorValue.of(Types.VARCHAR,"text","a,b\u0000中").jdbcValue());
        var timestamp=LocalDateTime.parse("2026-10-03T12:30:00.123456789");
        assertEquals(timestamp,TypedCursorValue.of(Types.TIMESTAMP,"timestamp",timestamp).jdbcValue());
        assertThrows(IllegalArgumentException.class,()->new TypedCursorValue(Types.BIGINT,"int8","9223372036854775808"));
        assertThrows(IllegalArgumentException.class,()->TypedCursorValue.of(Types.DOUBLE,"float8",0.1));
        assertThrows(IllegalArgumentException.class,()->TypedCursorValue.of(Types.INTEGER,"int4",null));
    }
}
