package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxDb3LogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Types;
import java.time.Instant;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.INFLUXDB3;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InfluxDB 3 Core 3.11.5 logical type mapping, DB-less. Facts come from the Flight SQL probe of that release.
 * Live Influx tests keep using {@code datahub.test.influx.http}; this class does not open a connection.
 */
@Timeout(60)
class LogicalTypeMappingInfluxDb3Test {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();

    @Test void registryCarriesTheInfluxMapper() {
        var mapper = REGISTRY.typeMapper(INFLUXDB3).orElseThrow();
        assertInstanceOf(InfluxDb3LogicalTypeMapper.class, mapper);
        assertEquals(INFLUXDB3, mapper.product());
        assertTrue(MAPPINGS.supports(INFLUXDB3, POSTGRESQL));
        assertTrue(MAPPINGS.supports(POSTGRESQL, INFLUXDB3));
        assertFalse(MAPPINGS.supports(INFLUXDB3, INFLUXDB3));
    }

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "Int64; EXACT|bigint|",
            "UInt64; UNSUPPORTED|-|UINT64_SIGNED_CODEC",
            "Float64; EXACT|double precision|",
            "Utf8; UNSUPPORTED|-|TYPE_UNMAPPED",
            "Boolean; EXACT|boolean|",
            "Timestamp(ns); LOSSY|timestamp(6) with time zone|TIMESTAMP_JDBC_EPOCH_SHIFTED,TIME_FRACTION_TRUNCATED",
            "Dictionary(Int32, Utf8); UNSUPPORTED|-|DICTIONARY_NOT_ORDINARY_COLUMN",
            "Decimal128(38, 10); UNSUPPORTED|-|DECIMAL_COLUMN_UNPROVEN",
            "string; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED",
            "tag; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void influxSourceToPostgresGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                InfluxDb3MappingFixtures.column("v", nativeType), false)));
    }

    @ParameterizedTest(name = "{0} -> INFLUXDB3")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|Int64|",
            "integer; EXACT|Int64|",
            "bigint; EXACT|Int64|",
            "numeric(38,18); UNSUPPORTED|-|DECIMAL_COLUMN_UNAVAILABLE",
            "numeric; UNSUPPORTED|-|DECIMAL_COLUMN_UNAVAILABLE",
            "numeric(1001,0); UNSUPPORTED|-|DECIMAL_COLUMN_UNAVAILABLE",
            "real; EXACT|Float64|",
            "double precision; EXACT|Float64|",
            "boolean; EXACT|Boolean|",
            "character varying(40); EXACT|Utf8|",
            "text; EXACT|Utf8|",
            "character(5); LOSSY|Utf8|PAD_SPACE_EQUALITY_CHANGED",
            "bytea; UNSUPPORTED|-|BINARY_TYPE_UNAVAILABLE",
            "date; UNSUPPORTED|-|DATE_TYPE_UNAVAILABLE",
            "time(3) without time zone; UNSUPPORTED|-|TIME_24_00_WRAPPED,TIME_TYPE_UNAVAILABLE",
            "timestamp(6) without time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "timestamp(6) with time zone; LOSSY|Timestamp(ns)|DATE_RANGE_NOT_REPRESENTABLE,TIMESTAMP_JDBC_EPOCH_SHIFTED",
            "uuid; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "xml; UNSUPPORTED|-|XML_TYPE_UNAVAILABLE"})
    void postgresSourceToInfluxGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(INFLUXDB3,
                LogicalTypeMappingTest.pg("v", nativeType, PgFamilyMappingFixtures.jdbc(POSTGRESQL, nativeType)), false)));
    }

    @Test void keysNotesAndRejectedDriverFacts() {
        assertEquals("EXACT|bigint|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                InfluxDb3MappingFixtures.column("k", "Int64"), true)));
        assertEquals("LOSSY|Int64|KEY_UNIQUENESS_NOT_ENFORCED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(INFLUXDB3,
                LogicalTypeMappingTest.pg("k", "integer", Types.INTEGER), true)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(INFLUXDB3,
                LogicalTypeMappingTest.pg("k", "character varying(20)", Types.VARCHAR), true)));
        var doubled = MAPPINGS.map(INFLUXDB3, LogicalTypeMappingTest.pg("v", "double precision", Types.DOUBLE), false);
        assertEquals("EXACT|Float64|", PgFamilyMappingFixtures.outcome(doubled));
        assertTrue(doubled.codes(TypeMappingIssue.Severity.NOTE).contains("NON_FINITE_REJECTED_BY_TARGET"));
        var wrongJdbc = InfluxDb3MappingFixtures.column("v", "Int64");
        wrongJdbc.setFieldType(Types.INTEGER);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongJdbc, false)));
        var signed = InfluxDb3MappingFixtures.column("v", "Float64");
        signed.setSigned(true);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, signed, false)));
        var facade = InfluxDb3MappingFixtures.column("v", "Int64");
        facade.setMetadataEvidence(new ColumnMetadataEvidence(
                new MetadataProvenance(INFLUXDB3, "InfluxDB IOx", "2", "NATIVE_CATALOG_V1", Instant.EPOCH),
                facade.getMetadataEvidence().nativeType(), MetadataValue.absent(), MetadataValue.value(true),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, facade, false)));
    }
}
