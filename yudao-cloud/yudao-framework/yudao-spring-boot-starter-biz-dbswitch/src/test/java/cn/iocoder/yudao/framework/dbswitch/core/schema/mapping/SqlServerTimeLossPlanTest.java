package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.data.util.AcceptedTypeMappingValues;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlServerTimeLossPlanTest {
    private static final LogicalTypeMappings MAPPINGS = DbSwitchProductRegistry.discover().typeMappings();

    @Test void previewQuantifiesPolicyAndInvalidatesTheHistoricalGenericFingerprint() {
        var mapped = mapping(false);
        assertEquals(TypeFidelity.LOSSY, mapped.fidelity());
        assertEquals("time(6) without time zone", mapped.targetNativeType());
        assertEquals(Set.of("TIME_FRACTION_TRUNCATED"), mapped.lossCodes());
        assertEquals("SQLSERVER_TIME7_FLOOR_MICROS_V1; sourceQuantum=100ns; targetQuantum=1000ns; rounding=FLOOR; maximumLoss=900ns; midnightCarry=false",
                mapped.issues().stream().filter(i -> i.code().equals("TIME_FRACTION_TRUNCATED")).findFirst().orElseThrow().detail());
        var old = new ColumnTypeMapping(mapped.column(), mapped.sourceProduct(), mapped.sourceNativeType(), mapped.logicalType(),
                mapped.targetProduct(), mapped.targetNativeType(), mapped.fidelity(),
                List.of(TypeMappingIssue.loss("TIME_FRACTION_TRUNCATED", "PostgreSQL time keeps microseconds")), mapped.definition());
        assertNotEquals(old.fingerprint(), mapped.fingerprint());
        assertFalse(LossyMappingAcknowledgement.acceptanceFor(List.of(LossyMappingAcknowledgement.forColumn("s", "t", old)), POSTGRESQL, "s", "t").accepts(mapped));
    }

    @Test void lossyClockKeysCannotBeAcknowledged() {
        assertEquals(TypeFidelity.UNSUPPORTED, mapping(true).fidelity());
        assertEquals("LOSSY_TIME_KEY_UNSUPPORTED", mapping(true).reasonCode());
        assertThrows(IllegalArgumentException.class, () -> LossyMappingAcknowledgement.forColumn("s", "t", mapping(true)));
    }

    @Test void rejectedPlanCannotCreateExecutionConversion() {
        assertThrows(TypeMappingRejectedException.class, () -> AcceptedTypeMappingValues.fromAccepted(new TableTypeMappingPlan(POSTGRESQL, List.of(mapping(false)), Set.of())));
    }

    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5,6,7,8,9})
    void boundedFloorRetainsRawCarrierAndNeverCarriesAcrossMidnight(int lastDigit) {
        var raw = LocalTime.of(23,59,59,999999000 + lastDigit * 100);
        Object[] input = {17L, raw, null};
        var converted = conversion().convert(List.of("id", "v", "other"), input);
        assertSame(raw, input[1]);
        assertEquals(LocalTime.of(23,59,59,999999000), converted[1]);
        assertEquals(lastDigit * 100, raw.toNanoOfDay() - ((LocalTime) converted[1]).toNanoOfDay());
        assertEquals(17L, converted[0]); assertNull(converted[2]);
    }

    @Test void nullAndReorderedPhysicalTargetFieldsArePreserved() {
        assertNull(conversion().convert(List.of("v", "id"), new Object[]{null, 2L})[0]);
        assertEquals(LocalTime.of(0,0), conversion().convert(List.of("v", "id"), new Object[]{LocalTime.of(0,0,0,100), 2L})[0]);
    }

    @Test void onlyExactClockCarrierAndUnambiguousLayoutAreAdmitted() {
        for (Object value : List.of("23:59:59.9999999", java.sql.Time.valueOf("12:00:00"), LocalTime.MAX)) {
            var error = assertThrows(BatchRejectedException.class, () -> conversion().convert(List.of("v"), new Object[]{value}));
            assertNull(error.getCause()); assertFalse(error.toString().contains(value.toString()));
        }
        assertThrows(BatchRejectedException.class, () -> conversion().convert(List.of("other"), new Object[]{null}));
        assertThrows(BatchRejectedException.class, () -> conversion().convert(List.of("v", "v"), new Object[]{null, null}));
    }

    @Test void diffMapsRenamedReorderedColumnsAfterUserTransformation() {
        var transformer = conversion().forSourceRows((schema, table, fields, row) -> {
            assertEquals(List.of("source_clock", "source_key"), fields);
            return new Object[]{LocalTime.of(12,34,56,987654900), row[1]};
        }, Map.of("source_clock", "v", "source_key", "id"));
        var original = new Object[]{LocalTime.of(1,2,3,123456700), 1L};
        var actual = transformer.transform("s", "t", List.of("source_clock", "source_key"), original);
        assertEquals(LocalTime.of(12,34,56,987654000), actual[0]);
        assertEquals(LocalTime.of(1,2,3,123456700), original[0]);
        assertEquals(1L, actual[1]);
    }

    @Test void wrongPhysicalTargetAndMissingNativeFactsCannotUseTheAcceptedPreview() {
        var time6 = PgFamilyMappingFixtures.family(POSTGRESQL, "v", "time(6) without time zone");
        conversion().requireTargetColumns(List.of(time6));
        for (String type : List.of("time(0) without time zone", "time(3) without time zone", "time(6) with time zone"))
            assertThrows(IllegalArgumentException.class, () -> conversion().requireTargetColumns(List.of(PgFamilyMappingFixtures.family(POSTGRESQL, "v", type))));
        assertThrows(IllegalArgumentException.class, () -> conversion().requireTargetColumns(List.of()));
        assertThrows(IllegalArgumentException.class, () -> conversion().requireTargetColumns(List.of(time6, time6)));
    }

    private static ColumnTypeMapping mapping(boolean key) { return MAPPINGS.map(POSTGRESQL, SqlFamilyMappingFixtures.family(SQLSERVER, "v", "time(7)"), key); }
    private static AcceptedTypeMappingValues conversion() { return AcceptedTypeMappingValues.fromAccepted(new TableTypeMappingPlan(POSTGRESQL, List.of(mapping(false)), Set.of("v"))); }
}
