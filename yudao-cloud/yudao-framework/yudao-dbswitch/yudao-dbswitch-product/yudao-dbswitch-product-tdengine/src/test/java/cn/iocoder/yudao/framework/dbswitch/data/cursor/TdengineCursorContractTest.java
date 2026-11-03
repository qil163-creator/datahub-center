package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineJdbcSupport;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineTimestamp;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TdengineCursorContractTest {
    private static final TdengineCursorMetadata.Column TS = column("ts", "TIMESTAMP", false, false, Types.TIMESTAMP);
    private static final TdengineCursorMetadata.Column UID = column("id", "BIGINT UNSIGNED", true, false, Types.NUMERIC);

    @Test void nativeTimestampIsAnExactEpochCountThatNeverNarrowsSilently() {
        var ns = new TdengineTimestamp(1_791_104_975_340_123_457L, 9);
        assertEquals("1791104975340123457", ns.toString());
        assertEquals(Instant.parse("2026-10-04T09:09:35.340123457Z"), ns.toInstant());
        assertEquals(Instant.parse("1969-12-31T23:59:59.999999Z"), new TdengineTimestamp(-1, 6).toInstant());
        assertEquals(1_791_104_975_340_123_457L, JdbcValueConversions.toLong(ns));
        assertEquals(new BigDecimal("1791104975340123457"), JdbcValueConversions.toDecimal(ns));
        assertThrows(ArithmeticException.class, ns::intValue);
        assertThrows(ArithmeticException.class, ns::doubleValue);
        assertThrows(ArithmeticException.class, ns::floatValue);
        assertThrows(ArithmeticException.class, ns::shortValue);
        assertEquals(1.0e6, new TdengineTimestamp(1_000_000, 3).doubleValue());
        assertNotEquals(new TdengineTimestamp(5, 3), new TdengineTimestamp(5, 6));
        assertThrows(IllegalArgumentException.class, () -> new TdengineTimestamp(5, 3).compareTo(new TdengineTimestamp(5, 6)));
        assertThrows(IllegalArgumentException.class, () -> new TdengineTimestamp(5, 4));
    }

    @Test void typedCursorComponentsKeepPrecisionUnsignedRangeAndChildNameExact() {
        var value = TdengineCursorMetadata.cursorValue(TS, new TdengineTimestamp(1_791_104_975_340_123_457L, 9), 9);
        assertEquals(new TypedCursorValue(Types.BIGINT, "TIMESTAMP(ns)", "1791104975340123457"), value);
        assertEquals(1_791_104_975_340_123_457L, TdengineCursorMetadata.cursorComponent(TS, value, 9));
        // A checkpoint written for another database precision can never be reinterpreted.
        assertThrows(IllegalArgumentException.class, () -> TdengineCursorMetadata.cursorComponent(TS, value, 3));
        var maximum = new BigInteger("18446744073709551615");
        var key = TdengineCursorMetadata.cursorValue(UID, maximum, 9);
        assertEquals(Types.NUMERIC, key.jdbcType()); assertEquals(maximum, TdengineCursorMetadata.cursorComponent(UID, key, 9));
        assertThrows(IllegalArgumentException.class, () -> TdengineCursorMetadata.cursorComponent(UID,
                new TypedCursorValue(Types.NUMERIC, "BIGINT UNSIGNED", "18446744073709551616"), 9));
        assertThrows(IllegalArgumentException.class, () -> TdengineCursorMetadata.cursorComponent(UID,
                new TypedCursorValue(Types.NUMERIC, "BIGINT UNSIGNED", "-1"), 9));
        assertEquals("C_a", TdengineCursorMetadata.cursorComponent(null, TdengineCursorMetadata.cursorValue(null, "C_a", 9), 9));
        assertThrows(IllegalArgumentException.class, () -> TdengineCursorMetadata.cursorComponent(null, new TypedCursorValue(Types.VARCHAR, "TBNAME", ""), 9));
    }

    @Test void sourceEpochKeysReplayIntoExactIntegerTargetsButTdengineTargetsAcceptOnlyIdenticalNativeKeys() {
        var nanos = shape(9, "NORMAL_TABLE"); var millis = shape(3, "NORMAL_TABLE");
        var postgres = new CursorKeyContract(ProductTypeEnum.POSTGRESQL, "b".repeat(64),
                List.of(CursorKeyContract.Domain.integer(64, false), CursorKeyContract.Domain.decimal(20, 0, false)));
        assertDoesNotThrow(() -> nanos.sourceContract().requireReplayCompatible(postgres));
        assertThrows(IllegalArgumentException.class, () -> nanos.sourceContract().requireReplayCompatible(new CursorKeyContract(ProductTypeEnum.POSTGRESQL,
                "b".repeat(64), List.of(CursorKeyContract.Domain.integer(64, false), CursorKeyContract.Domain.integer(64, false)))));
        // An integer from another product carries no TDengine timestamp unit.
        assertThrows(IllegalArgumentException.class, () -> postgres.requireReplayCompatible(nanos.targetContract()));
        assertDoesNotThrow(() -> nanos.sourceContract().requireReplayCompatible(shape(9, "NORMAL_TABLE").targetContract()));
        assertThrows(IllegalArgumentException.class, () -> nanos.sourceContract().requireReplayCompatible(millis.targetContract()));
        assertThrows(IllegalArgumentException.class, () -> nanos.sourceContract().requireReplayCompatible(shape(9, "CHILD_TABLE").targetContract()));
    }

    private static TdengineCursorMetadata.Shape shape(int digits, String kind) {
        var columns = kind.equals("CHILD_TABLE") ? List.of(TS, UID, column("v", "INT", false, false, Types.INTEGER), column("site", "NCHAR(4)", false, true, Types.NCHAR))
                : List.of(TS, UID, column("v", "INT", false, false, Types.INTEGER));
        return new TdengineCursorMetadata.Shape("db", "t", kind, "11", kind.equals("CHILD_TABLE") ? "st" : "", kind.equals("CHILD_TABLE") ? "12" : "",
                digits, columns, kind.equals("CHILD_TABLE") ? List.of("TEXT:a") : List.of(), "c".repeat(64), "1\0ns");
    }
    private static TdengineCursorMetadata.Column column(String name, String type, boolean key, boolean tag, int jdbc) {
        return new TdengineCursorMetadata.Column(name, type, key, tag, new TdengineJdbcSupport.Column(name, jdbc, type, 0, 0));
    }
}
