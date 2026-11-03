package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursorValue;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorKeyContract;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxCursorMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxNativeCatalog;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InfluxCursorContractTest {
    private static final InfluxNativeCatalog.Column TIME = column("time", InfluxNativeCatalog.Kind.TIME);
    private static final InfluxNativeCatalog.Column HOST = column("host", InfluxNativeCatalog.Kind.TAG);
    private static final InfluxNativeCatalog.Column REGION = column("region", InfluxNativeCatalog.Kind.TAG);

    @Test void typedCursorComponentsKeepExactNanosecondsAndTagLiterals() {
        var time = InfluxCursorMetadata.cursorValue(TIME, 1_791_104_975_340_123_457L);
        assertEquals(new TypedCursorValue(Types.BIGINT, "TIMESTAMP(NS)", "1791104975340123457"), time);
        assertEquals(1_791_104_975_340_123_457L, InfluxCursorMetadata.cursorComponent(TIME, time));
        assertThrows(IllegalArgumentException.class, () -> InfluxCursorMetadata.cursorValue(TIME, 1_791_104_975_340_123_457d));
        assertEquals(-1L, InfluxCursorMetadata.cursorComponent(TIME, new TypedCursorValue(Types.BIGINT, "TIMESTAMP(NS)", "-1")));
        assertThrows(IllegalArgumentException.class, () -> InfluxCursorMetadata.cursorComponent(TIME, new TypedCursorValue(Types.BIGINT, "TIMESTAMP(us)", "1")));
        var tag = InfluxCursorMetadata.cursorValue(HOST, "a'b\\c");
        assertEquals(new TypedCursorValue(Types.VARCHAR, "INFLUX_TAG", "a'b\\c"), tag);
        assertEquals("a'b\\c", InfluxCursorMetadata.cursorComponent(HOST, tag));
        // Tags outside the verified SQL literal contract can never be persisted as a cursor key.
        assertThrows(IllegalArgumentException.class, () -> InfluxCursorMetadata.cursorValue(HOST, ""));
        assertThrows(IllegalArgumentException.class, () -> InfluxCursorMetadata.cursorValue(HOST, "a\nb"));
        assertThrows(IllegalArgumentException.class, () -> InfluxCursorMetadata.cursorValue(HOST, "x".repeat(1025)));
        assertThrows(IllegalArgumentException.class, () -> InfluxCursorMetadata.cursorComponent(HOST, new TypedCursorValue(Types.VARCHAR, "OTHER", "x")));
        // A NULL tag cannot be a typed cursor component at all.
        assertThrows(IllegalArgumentException.class, () -> InfluxCursorMetadata.cursorValue(HOST, null));
    }

    @Test void sourceTagsetTimeKeysReplayOnlyUnderTheIdenticalNativeContract() {
        var nanos = shape("Apache DataFusion 51.0.0, x86_64 on linux");
        // Same product: identical native fingerprint required.
        assertDoesNotThrow(() -> nanos.sourceContract().requireReplayCompatible(nanos.targetContract()));
        assertThrows(IllegalArgumentException.class, () -> nanos.sourceContract().requireReplayCompatible(
                shape("Apache DataFusion 52.0.0, x86_64 on linux").targetContract()));
        // Tags are native-only, so no other product can offer a verified lossless key mapping.
        var postgres = new CursorKeyContract(ProductTypeEnum.POSTGRESQL, "b".repeat(64), List.of(
                CursorKeyContract.Domain.nativeOnly(), CursorKeyContract.Domain.nativeOnly(), CursorKeyContract.Domain.integer(64, false)));
        assertThrows(IllegalArgumentException.class, () -> nanos.sourceContract().requireReplayCompatible(postgres));
        // An integer from another product carries no InfluxDB3 nanosecond unit.
        assertThrows(IllegalArgumentException.class, () -> postgres.requireReplayCompatible(nanos.targetContract()));
        assertEquals(List.of("host", "region", "time"), nanos.keys());
        assertEquals(CursorKeyContract.Domain.integer(64, false).numeric(), nanos.sourceContract().keys().getLast().numeric());
        assertNull(nanos.targetContract().keys().getLast().numeric());
    }

    private static InfluxCursorMetadata.Shape shape(String engine) {
        var table = new InfluxNativeCatalog.Table("m", List.of(HOST, column("note", InfluxNativeCatalog.Kind.STRING),
                REGION, TIME, column("v", InfluxNativeCatalog.Kind.INT64)));
        return new InfluxCursorMetadata.Shape("db", "m", "0".repeat(8) + "-" + "0".repeat(4) + "-" + "0".repeat(4) + "-" + "0".repeat(4) + "-" + "0".repeat(12), engine, table);
    }
    private static InfluxNativeCatalog.Column column(String name, InfluxNativeCatalog.Kind kind) { return new InfluxNativeCatalog.Column(name, kind); }
}
