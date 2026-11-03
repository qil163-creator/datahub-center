package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Types;
import java.time.Instant;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.HIVE;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hive 4.1.0 logical type mapping. Golden values are the probed TEXTFILE facts. No live database. */
@Timeout(60)
class LogicalTypeMappingHiveTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();

    @Test void registryCarriesTheHiveMapper() {
        var mapper = REGISTRY.typeMapper(HIVE).orElseThrow();
        assertInstanceOf(HiveLogicalTypeMapper.class, mapper);
        assertEquals(HIVE, mapper.product());
        assertTrue(MAPPINGS.supports(HIVE, POSTGRESQL));
        assertTrue(MAPPINGS.supports(POSTGRESQL, HIVE));
        assertFalse(MAPPINGS.supports(HIVE, HIVE));
    }

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', quoteCharacter = '`', value = {
            "tinyint; EXACT|smallint|",
            "smallint; EXACT|smallint|",
            "int; EXACT|integer|",
            "bigint; EXACT|bigint|",
            "float; LOSSY|real|FLOAT32_PRECISION_NOT_PRESERVED",
            "double; EXACT|double precision|",
            "decimal(10,2); EXACT|numeric(10,2)|",
            "decimal(38,10); EXACT|numeric(38,10)|",
            "string; EXACT|text COLLATE \"C\"|",
            "varchar(5); EXACT|character varying(5) COLLATE \"C\"|",
            "varchar(65535); EXACT|character varying(65535) COLLATE \"C\"|",
            "char(5); EXACT|character(5) COLLATE \"C\"|",
            "char(255); EXACT|character(255) COLLATE \"C\"|",
            "boolean; EXACT|boolean|",
            "date; EXACT|date|",
            "timestamp; LOSSY|timestamp(6) without time zone|TIME_FRACTION_TRUNCATED",
            "binary; EXACT|bytea|",
            "array<int>; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "map<string,int>; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "struct<a:int>; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "uniontype<int,string>; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "timestamp with local time zone; UNSUPPORTED|-|TYPE_UNMAPPED",
            "interval day to second; UNSUPPORTED|-|TYPE_UNMAPPED",
            "int unsigned; UNSUPPORTED|-|TYPE_UNMAPPED",
            "void; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void hiveSourceToPostgresGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                HiveMappingFixtures.family("v", nativeType), false)));
    }

    @ParameterizedTest(name = "{0} -> HIVE")
    @CsvSource(delimiter = ';', quoteCharacter = '`', value = {
            "smallint; EXACT|smallint|",
            "integer; EXACT|int|",
            "bigint; EXACT|bigint|",
            "numeric(12,2); LOSSY|decimal(12,2)|NAN_NOT_REPRESENTABLE",
            "numeric; UNSUPPORTED|-|UNBOUNDED_NUMERIC_UNMAPPED",
            "numeric(39,0); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "real; LOSSY|float|FLOAT32_PRECISION_NOT_PRESERVED,NEGATIVE_ZERO_NOT_PRESERVED",
            "double precision; LOSSY|double|NEGATIVE_ZERO_NOT_PRESERVED",
            "boolean; EXACT|boolean|",
            "character varying(40); EXACT|varchar(40)|",
            "character varying(65536); LOSSY|string|VARCHAR_LENGTH_NOT_ENFORCED",
            "character(5); LOSSY|char(5)|PAD_SPACE_EQUALITY_CHANGED",
            "character(255); LOSSY|char(255)|PAD_SPACE_EQUALITY_CHANGED",
            "character(256); UNSUPPORTED|-|LENGTH_EXCEEDS_TARGET",
            "text; EXACT|string|",
            "bytea; EXACT|binary|",
            "date; LOSSY|date|DATE_RANGE_NOT_REPRESENTABLE",
            "time(3) without time zone; UNSUPPORTED|-|TIME_24_00_WRAPPED,TIME_TYPE_UNAVAILABLE",
            "timestamp(6) without time zone; LOSSY|timestamp|DATE_RANGE_NOT_REPRESENTABLE",
            "timestamp(6) with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "uuid; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "xml; UNSUPPORTED|-|TYPE_UNMAPPED",
            "integer[]; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED"})
    void postgresSourceToHiveGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(HIVE,
                LogicalTypeMappingTest.pg("v", nativeType, MysqlFamilyExtendedFixtures.pgJdbc(nativeType)), false)));
    }

    @Test void keysCollationsAndDriverFacts() {
        var tinyint = MAPPINGS.map(POSTGRESQL, HiveMappingFixtures.family("v", "tinyint"), false);
        assertTrue(tinyint.definition().contains("CHECK (\"v\" BETWEEN -128 AND 127)"), tinyint.definition());
        var smallint = MAPPINGS.map(POSTGRESQL, HiveMappingFixtures.family("v", "smallint"), false);
        assertFalse(smallint.definition().contains("CHECK ("), smallint.definition());
        assertEquals("EXACT|integer|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                HiveMappingFixtures.family("k", "int"), true)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                HiveMappingFixtures.family("k", "varchar(5)"), true)));
        assertEquals("LOSSY|int|KEY_UNIQUENESS_NOT_ENFORCED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(HIVE,
                LogicalTypeMappingTest.pg("k", "integer", Types.INTEGER), true)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(HIVE,
                LogicalTypeMappingTest.pg("k", "character varying(20)", Types.VARCHAR), true)));
        assertEquals("EXACT|char(5)|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(HIVE, pgCollated("v", "character(5)"), false)));
        assertEquals("EXACT|varchar(40)|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(HIVE,
                pgCollated("v", "character varying(40)"), false)));
        assertEquals("EXACT|string|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(HIVE, pgCollated("v", "text"), false)));
        var wrongInteger = HiveMappingFixtures.family("v", "int");
        wrongInteger.setFieldType(Types.BIGINT);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongInteger, false)));
        var foreign = HiveMappingFixtures.family("v", "int");
        HiveMappingFixtures.withFacts(foreign, facts -> new ColumnMetadataEvidence(
                new MetadataProvenance(HIVE, "Apache Hive", "4.1.0", "HIVE_CATALOG_V1", Instant.EPOCH),
                facts.nativeType(), facts.defaultExpression(), facts.nullable(), facts.numericPrecision(), facts.numericScale(),
                facts.characterLength(), facts.identity(), facts.generatedExpression(), facts.charset(), facts.collation(),
                facts.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, foreign, false)));
        var charset = HiveMappingFixtures.family("v", "string");
        HiveMappingFixtures.withFacts(charset, facts -> new ColumnMetadataEvidence(
                facts.provenance(), facts.nativeType(), facts.defaultExpression(), facts.nullable(), facts.numericPrecision(),
                facts.numericScale(), facts.characterLength(), facts.identity(), facts.generatedExpression(),
                MetadataValue.value("UTF-8"), facts.collation(), facts.sequence()));
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, charset, false)));
    }

    private static cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription pgCollated(String name, String type) {
        var column = LogicalTypeMappingTest.pg(name, type, MysqlFamilyExtendedFixtures.pgJdbc(type));
        return HiveMappingFixtures.withFacts(column, facts -> new ColumnMetadataEvidence(
                facts.provenance(), facts.nativeType(), facts.defaultExpression(), facts.nullable(), facts.numericPrecision(),
                facts.numericScale(), facts.characterLength(), facts.identity(), facts.generatedExpression(), facts.charset(),
                MetadataValue.value("pg_catalog.\"C\""), facts.sequence()));
    }
}
