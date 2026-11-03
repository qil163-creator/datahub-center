package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.product.opengauss.OpengaussLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Types;
import java.time.Instant;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.OPENGAUSS;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * openGauss 7.0.0-RC3 (server_version 9.2.4, sql_compatibility A) logical type mapping.
 * DB-less golden only: openGauss to PostgreSQL, and common PostgreSQL types to openGauss.
 * Facts come from the probed server, not from the PostgreSQL 16 mapper.
 */
class LogicalTypeMappingOpengaussTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();

    @Test void registryCarriesTheOpengaussMapper() {
        var mapper = REGISTRY.typeMapper(OPENGAUSS).orElseThrow();
        assertInstanceOf(OpengaussLogicalTypeMapper.class, mapper);
        assertEquals(OPENGAUSS, mapper.product());
        assertTrue(MAPPINGS.supports(OPENGAUSS, POSTGRESQL));
        assertTrue(MAPPINGS.supports(POSTGRESQL, OPENGAUSS));
        assertFalse(MAPPINGS.supports(OPENGAUSS, OPENGAUSS));
    }

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|smallint|",
            "integer; EXACT|integer|",
            "bigint; EXACT|bigint|",
            "numeric; EXACT|numeric|",
            "numeric(38,9); EXACT|numeric(38,9)|",
            "real; EXACT|real|",
            "double precision; EXACT|double precision|",
            "boolean; EXACT|boolean|",
            "character(8); UNSUPPORTED|-|EMPTY_STRING_BECOMES_NULL,TYPE_UNMAPPED",
            "character varying(32); UNSUPPORTED|-|EMPTY_STRING_BECOMES_NULL,TYPE_UNMAPPED",
            "text; LOSSY|text|EMPTY_STRING_BECOMES_NULL",
            "bytea; EXACT|bytea|",
            "timestamp(0) without time zone; EXACT|timestamp(0) without time zone|",
            "time without time zone; LOSSY|time(6) without time zone|TIME_24_00_WRAPPED,TIME_FRACTION_TRUNCATED",
            "time(6) without time zone; LOSSY|time(6) without time zone|TIME_24_00_WRAPPED,TIME_FRACTION_TRUNCATED",
            "timestamp without time zone; EXACT|timestamp(6) without time zone|",
            "timestamp(6) without time zone; EXACT|timestamp(6) without time zone|",
            "timestamp with time zone; LOSSY|timestamp(6) with time zone|DATE_RANGE_NOT_REPRESENTABLE",
            "timestamp(6) with time zone; LOSSY|timestamp(6) with time zone|DATE_RANGE_NOT_REPRESENTABLE",
            "json; EXACT|json|",
            "uuid; EXACT|uuid|",
            "date; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED",
            "int2; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void opengaussSourceToPostgresGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OpengaussMappingFixtures.column("v", nativeType), false)));
    }

    @ParameterizedTest(name = "{0} -> OPENGAUSS")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|smallint|",
            "integer; EXACT|integer|",
            "bigint; EXACT|bigint|",
            "numeric(38,18); EXACT|numeric(38,18)|",
            "numeric(1000,0); EXACT|numeric(1000,0)|",
            "numeric; EXACT|numeric|",
            "numeric(1001,0); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "real; LOSSY|real|NEGATIVE_ZERO_NOT_PRESERVED",
            "double precision; LOSSY|double precision|NEGATIVE_ZERO_NOT_PRESERVED",
            "boolean; EXACT|boolean|",
            "character varying(40); LOSSY|character varying(160)|EMPTY_STRING_BECOMES_NULL",
            "character(5); LOSSY|character(20)|EMPTY_STRING_BECOMES_NULL",
            "text; LOSSY|text|EMPTY_STRING_BECOMES_NULL",
            "bytea; EXACT|bytea|",
            "date; LOSSY|date|DATE_RANGE_NOT_REPRESENTABLE",
            "time(3) without time zone; LOSSY|time(3) without time zone|TIME_24_00_FRACTION_REJECTED,TIME_24_00_WRAPPED",
            "timestamp(6) without time zone; LOSSY|timestamp(6) without time zone|DATE_RANGE_NOT_REPRESENTABLE",
            "timestamp(6) with time zone; EXACT|timestamp(6) with time zone|",
            "uuid; EXACT|uuid|",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "xml; UNSUPPORTED|-|TYPE_UNMAPPED"})
    void postgresSourceToOpengaussGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(OPENGAUSS,
                LogicalTypeMappingTest.pg("v", nativeType, MysqlFamilyExtendedFixtures.pgJdbc(nativeType)), false)));
    }

    @Test void keysAndRejectedDriverFacts() {
        assertEquals("EXACT|integer|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OpengaussMappingFixtures.column("k", "integer"), true)));
        assertEquals("EXACT|integer|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(OPENGAUSS,
                LogicalTypeMappingTest.pg("k", "integer", Types.INTEGER), true)));
        assertEquals("UNSUPPORTED|-|EMPTY_STRING_BECOMES_NULL,TEXT_KEY_EQUALITY_UNPROVEN",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                        OpengaussMappingFixtures.column("k", "character varying(32)"), true)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(OPENGAUSS,
                LogicalTypeMappingTest.pg("k", "character varying(20)", Types.VARCHAR), true)));

        var wrongBoolean = OpengaussMappingFixtures.column("v", "boolean");
        wrongBoolean.setFieldType(Types.BIT);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                wrongBoolean, false)));
        var wrongPrecision = OpengaussMappingFixtures.column("v", "integer");
        wrongPrecision.setPrecisionSize(11);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                wrongPrecision, false)));
        var foreign = OpengaussMappingFixtures.column("v", "integer");
        OpengaussMappingFixtures.withFacts(foreign, facts -> new ColumnMetadataEvidence(
                new MetadataProvenance(OPENGAUSS, "PostgreSQL", "16.15", "NATIVE_CATALOG_V1", Instant.EPOCH),
                facts.nativeType(), facts.defaultExpression(), facts.nullable(), facts.numericPrecision(), facts.numericScale(),
                facts.characterLength(), facts.identity(), facts.generatedExpression(), facts.charset(), facts.collation(),
                facts.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                foreign, false)));
    }
}
