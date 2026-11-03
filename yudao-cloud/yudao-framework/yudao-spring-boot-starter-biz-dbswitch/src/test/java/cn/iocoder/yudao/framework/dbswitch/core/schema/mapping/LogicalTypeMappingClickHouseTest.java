package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickHouseLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.CLICKHOUSE;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClickHouse 25.8.33.6 (clickhouse-jdbc 0.5.0) logical type mapping. Golden values are the probed server facts.
 * The live round trip runs only when datahub.test.clickhouse.url is set.
 */
@Timeout(300)
class LogicalTypeMappingClickHouseTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();

    @Test void registryCarriesTheClickHouseMapper() {
        var mapper = REGISTRY.typeMapper(CLICKHOUSE).orElseThrow();
        assertInstanceOf(ClickHouseLogicalTypeMapper.class, mapper);
        assertEquals(CLICKHOUSE, mapper.product());
        assertTrue(MAPPINGS.supports(CLICKHOUSE, POSTGRESQL));
        assertTrue(MAPPINGS.supports(POSTGRESQL, CLICKHOUSE));
        assertFalse(MAPPINGS.supports(CLICKHOUSE, CLICKHOUSE));
    }

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', quoteCharacter = '"', value = {
            "Int8; EXACT|smallint|",
            "Int16; EXACT|smallint|",
            "Int32; EXACT|integer|",
            "Int64; EXACT|bigint|",
            "UInt8; EXACT|smallint|",
            "UInt16; EXACT|integer|",
            "UInt32; EXACT|bigint|",
            "UInt64; EXACT|numeric(20,0)|",
            "Bool; EXACT|boolean|",
            "Decimal(38, 9); EXACT|numeric(38,9)|",
            "Float32; EXACT|real|",
            "Float64; EXACT|double precision|",
            "String; UNSUPPORTED|-|STRING_MAX_OCTETS_UNPROVEN",
            "FixedString(8); UNSUPPORTED|-|FIXEDSTRING_STRING_CODEC",
            "Date; LOSSY|date|DATE_OUT_OF_RANGE_SATURATED",
            "Date32; EXACT|date|",
            "DateTime; LOSSY|timestamp(0) without time zone|DATETIME_OUT_OF_RANGE_WRAPPED",
            "DateTime64(0); EXACT|timestamp(0) without time zone|",
            "DateTime64(3); EXACT|timestamp(3) without time zone|",
            "DateTime64(6); EXACT|timestamp(6) without time zone|",
            "DateTime64(9); LOSSY|timestamp(6) without time zone|TIME_FRACTION_TRUNCATED",
            "DateTime('UTC'); UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "UUID; EXACT|uuid|",
            "Nullable(Int32); EXACT|integer|",
            "LowCardinality(String); UNSUPPORTED|-|STRING_MAX_OCTETS_UNPROVEN",
            "Array(Int32); UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "Map(String, Int64); UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "Int128; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED",
            "JSON; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void clickHouseSourceToPostgresGolden(String nativeType, String expected) {
        var mapping = MAPPINGS.map(POSTGRESQL, ClickHouseMappingFixtures.family("v", nativeType), false);
        assertEquals(expected, PgFamilyMappingFixtures.outcome(mapping));
    }

    @ParameterizedTest(name = "{0} -> CLICKHOUSE")
    @CsvSource(delimiter = ';', quoteCharacter = '"', value = {
            "smallint; EXACT|Nullable(Int16)|",
            "integer; EXACT|Nullable(Int32)|",
            "bigint; EXACT|Nullable(Int64)|",
            "numeric(38,18); LOSSY|Nullable(Decimal(38, 18))|NAN_NOT_REPRESENTABLE",
            "numeric; UNSUPPORTED|-|UNBOUNDED_NUMERIC_UNMAPPED",
            "numeric(77,0); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "numeric(10,12); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "real; LOSSY|Nullable(Float32)|NEGATIVE_ZERO_NOT_PRESERVED",
            "double precision; LOSSY|Nullable(Float64)|NEGATIVE_ZERO_NOT_PRESERVED",
            "boolean; EXACT|Nullable(Bool)|",
            "character varying(40); LOSSY|Nullable(String)|LENGTH_NOT_ENFORCED",
            "character(5); LOSSY|Nullable(String)|LENGTH_NOT_ENFORCED",
            "text; EXACT|Nullable(String)|",
            "bytea; UNSUPPORTED|-|BINARY_TYPE_UNAVAILABLE",
            "date; LOSSY|Nullable(Date32)|DATE_RANGE_NOT_REPRESENTABLE",
            "time(0) without time zone; UNSUPPORTED|-|TIME_24_00_WRAPPED,TIME_TYPE_UNAVAILABLE",
            "time(3) without time zone; UNSUPPORTED|-|TIME_24_00_WRAPPED,TIME_TYPE_UNAVAILABLE",
            "time with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "timestamp(6) without time zone; LOSSY|Nullable(DateTime64(6))|DATE_RANGE_NOT_REPRESENTABLE",
            "timestamp(6) with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "uuid; EXACT|Nullable(UUID)|",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "xml; UNSUPPORTED|-|TYPE_UNMAPPED",
            "integer[]; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED"})
    void postgresSourceToClickHouseGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(CLICKHOUSE,
                LogicalTypeMappingTest.pg("v", nativeType, MysqlFamilyExtendedFixtures.pgJdbc(nativeType)), false)));
    }

    @Test void keysCollationsAndDriverFacts() {
        assertEquals("LOSSY|Nullable(Int32)|KEY_UNIQUENESS_NOT_ENFORCED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(CLICKHOUSE,
                LogicalTypeMappingTest.pg("k", "integer", Types.INTEGER), true)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(CLICKHOUSE,
                LogicalTypeMappingTest.pg("k", "character varying(20)", Types.VARCHAR), true)));
        assertEquals("EXACT|bigint|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                ClickHouseMappingFixtures.family("k", "Int64"), true)));
        assertEquals("LOSSY|Nullable(String)|LENGTH_NOT_ENFORCED,PAD_SPACE_EQUALITY_CHANGED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(CLICKHOUSE, pgCollated("v", "character(5)"), false)));
        assertEquals("LOSSY|Nullable(String)|LENGTH_NOT_ENFORCED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(CLICKHOUSE, pgCollated("v", "character varying(40)"), false)));
        var wrongInteger = ClickHouseMappingFixtures.family("v", "Int32");
        wrongInteger.setFieldType(Types.BIGINT);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongInteger, false)));
        var foreign = ClickHouseMappingFixtures.family("v", "Int32");
        ClickHouseMappingFixtures.withFacts(foreign, facts -> new ColumnMetadataEvidence(
                new MetadataProvenance(CLICKHOUSE, "ClickHouse", "25.8.33.6", "NATIVE_CATALOG_V1", Instant.EPOCH),
                facts.nativeType(), facts.defaultExpression(), facts.nullable(), facts.numericPrecision(), facts.numericScale(),
                facts.characterLength(), facts.identity(), facts.generatedExpression(), facts.charset(), facts.collation(),
                facts.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, foreign, false)));
        var int8 = MAPPINGS.map(POSTGRESQL, ClickHouseMappingFixtures.family("v", "Int8"), false);
        assertTrue(int8.definition().contains("CHECK (\"v\" BETWEEN -128 AND 127)"), int8.definition());
        var uint8 = MAPPINGS.map(POSTGRESQL, ClickHouseMappingFixtures.family("v", "UInt8"), false);
        assertTrue(uint8.definition().contains("CHECK (\"v\" BETWEEN 0 AND 255)"), uint8.definition());
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.clickhouse.url", matches = "jdbc:clickhouse://127[.]0[.]0[.]1:[0-9]+/default")
    void clickHouseRoundTripPreservesProbedBoundaries() throws Exception {
        Class.forName("com.clickhouse.jdbc.ClickHouseDriver");
        String database = "w14_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = open()) {
            execute(connection, "CREATE DATABASE " + database + " ENGINE = Atomic");
            try {
                execute(connection, "CREATE TABLE " + database + ".t (k Int32, v_dec Decimal(38, 9), v_f64 Float64,"
                        + " v_uuid UUID, v_fs FixedString(4)) ENGINE = MergeTree ORDER BY k");
                execute(connection, "INSERT INTO " + database + ".t VALUES (1, 99999999999999999999999999999.999999999,"
                        + " -0.0, '550E8400-E29B-41D4-A716-446655440000', 'ab')");
                execute(connection, "INSERT INTO " + database + ".t VALUES (1, -1.5, nan,"
                        + " '00000000-0000-0000-0000-000000000000', 'abcd')");
                boolean overflow = false;
                try {
                    execute(connection, "INSERT INTO " + database + ".t VALUES (2, 100000000000000000000000000000, 0,"
                            + " '00000000-0000-0000-0000-000000000000', 'abcd')");
                } catch (java.sql.SQLException rejected) {
                    overflow = rejected.getMessage().contains("Decimal value is too big");
                }
                assertTrue(overflow, "Decimal(38, 9) overflow must be rejected");
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT k, toString(v_dec), toString(v_f64), toString(v_uuid),"
                             + " hex(v_fs), count() FROM " + database + ".t GROUP BY k, v_dec, v_f64, v_uuid, v_fs ORDER BY v_dec")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                    assertEquals("-1.5", rows.getString(2));
                    assertEquals("nan", rows.getString(3));
                    assertEquals("00000000-0000-0000-0000-000000000000", rows.getString(4));
                    assertEquals("61626364", rows.getString(5));
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                    assertEquals(0, new BigDecimal("99999999999999999999999999999.999999999").compareTo(new BigDecimal(rows.getString(2))));
                    assertEquals("0", rows.getString(3));
                    assertEquals("550e8400-e29b-41d4-a716-446655440000", rows.getString(4));
                    assertEquals("61620000", rows.getString(5));
                    assertFalse(rows.next());
                }
                try (Statement statement = connection.createStatement();
                     ResultSet version = statement.executeQuery("SELECT version()")) {
                    assertTrue(version.next());
                    assertTrue(version.getString(1).startsWith("25.8."), version.getString(1));
                }
            } finally {
                execute(connection, "DROP DATABASE IF EXISTS " + database);
            }
        }
    }

    private static ColumnDescription pgCollated(String name, String type) {
        var column = LogicalTypeMappingTest.pg(name, type, MysqlFamilyExtendedFixtures.pgJdbc(type));
        return ClickHouseMappingFixtures.withFacts(column, facts -> new ColumnMetadataEvidence(
                facts.provenance(), facts.nativeType(), facts.defaultExpression(), facts.nullable(), facts.numericPrecision(),
                facts.numericScale(), facts.characterLength(), facts.identity(), facts.generatedExpression(), facts.charset(),
                MetadataValue.value("pg_catalog.\"C\""), facts.sequence()));
    }

    private static void execute(Connection connection, String sql) throws java.sql.SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection open() throws java.sql.SQLException {
        return DriverManager.getConnection(System.getProperty("datahub.test.clickhouse.url"), "default", "");
    }
}
