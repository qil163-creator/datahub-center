package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.DriverManager;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * StarRocks 3.5.21-85fccf8 logical type mapping. Golden values are the local probe, not the Doris mapper.
 * The live round trip stays gated so the shared reactor does not open a server.
 */
class LogicalTypeMappingStarrocksTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();

    @Test void registryCarriesTheStarrocksMapper() {
        var mapper = REGISTRY.typeMapper(STARROCKS).orElseThrow();
        assertInstanceOf(StarrocksLogicalTypeMapper.class, mapper);
        assertEquals(STARROCKS, mapper.product());
        assertTrue(MAPPINGS.supports(STARROCKS, POSTGRESQL));
        assertTrue(MAPPINGS.supports(STARROCKS, MYSQL));
        assertFalse(MAPPINGS.supports(STARROCKS, STARROCKS));
    }

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "tinyint(4); EXACT|smallint|; \"v\" BETWEEN -128 AND 127",
            "smallint(6); EXACT|smallint|; -",
            "int(11); EXACT|integer|; -",
            "bigint(20); EXACT|bigint|; -",
            "bigint(20) unsigned; EXACT|numeric(39,0)|; \"v\" BETWEEN -170141183460469231731687303715884105728 AND 170141183460469231731687303715884105727",
            "tinyint(1); EXACT|boolean|; -",
            "decimal(10, 2); EXACT|numeric(10,2)|; -",
            "decimal(38, 9); EXACT|numeric(38,9)|; -",
            "float; LOSSY|real|FLOAT32_PRECISION_NOT_PRESERVED; -",
            "double; EXACT|double precision|; -",
            "char(5); UNSUPPORTED|-|TYPE_UNMAPPED; -",
            "varchar(20); EXACT|text COLLATE \"C\"|; \"v\" IS NULL OR (pg_catalog.current_setting('server_encoding') = 'UTF8' AND pg_catalog.octet_length(\"v\") <= 20)",
            "varchar(65533); EXACT|text COLLATE \"C\"|; \"v\" IS NULL OR (pg_catalog.current_setting('server_encoding') = 'UTF8' AND pg_catalog.octet_length(\"v\") <= 65533)",
            "string; UNSUPPORTED|-|TYPE_UNMAPPED; -",
            "json; EXACT|jsonb|; -",
            "varbinary; EXACT|bytea|; -",
            "varbinary(4); UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED; -",
            "date; LOSSY|date|YEAR_ZERO_DRIVER_REJECTED,ZERO_DATE_SILENTLY_CONVERTED; -",
            "datetime; LOSSY|timestamp(6) without time zone|YEAR_ZERO_DRIVER_REJECTED,ZERO_DATE_SILENTLY_CONVERTED; -",
            "array<int(11)>; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED; -",
            "bitmap; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED; -",
            "int(11) unsigned; UNSUPPORTED|-|TYPE_UNMAPPED; -"})
    void starrocksSourceToPostgresGolden(String nativeType, String expected, String check) {
        var mapping = MAPPINGS.map(POSTGRESQL, StarrocksMappingFixtures.column("v", nativeType), false);
        assertEquals(expected, PgFamilyMappingFixtures.outcome(mapping));
        if (mapping.definition() != null)
            assertEquals(check.equals("-") ? null : check, mapping.definition().contains(" CHECK (")
                    ? mapping.definition().substring(mapping.definition().indexOf(" CHECK (") + 8, mapping.definition().length() - 1) : null);
    }

    @ParameterizedTest(name = "{0} -> STARROCKS")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|smallint|",
            "integer; EXACT|int|",
            "bigint; EXACT|bigint|",
            "numeric(38,18); LOSSY|decimal(38,18)|NAN_NOT_REPRESENTABLE",
            "numeric(39,0); LOSSY|largeint|NAN_NOT_REPRESENTABLE,NUMERIC_RANGE_NOT_REPRESENTABLE",
            "numeric; UNSUPPORTED|-|UNBOUNDED_NUMERIC_UNMAPPED",
            "numeric(1001,0); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "real; LOSSY|float|FLOAT32_PRECISION_NOT_PRESERVED,NEGATIVE_ZERO_NOT_PRESERVED,NON_FINITE_STORED_AS_NULL",
            "double precision; LOSSY|double|NEGATIVE_ZERO_NOT_PRESERVED,NON_FINITE_STORED_AS_NULL",
            "boolean; EXACT|boolean|",
            "character varying(40); EXACT|varchar(160)|",
            "character varying(16384); EXACT|varchar(65536)|",
            "character(5); EXACT|char(20)|",
            "character(256); UNSUPPORTED|-|LENGTH_EXCEEDS_TARGET",
            "text; UNSUPPORTED|-|LENGTH_EXCEEDS_TARGET",
            "bytea; LOSSY|varbinary|BINARY_LENGTH_LIMIT",
            "date; LOSSY|date|DATE_RANGE_NOT_REPRESENTABLE,YEAR_ZERO_DRIVER_REJECTED",
            "time(3) without time zone; UNSUPPORTED|-|TIME_24_00_WRAPPED,TIME_TYPE_UNAVAILABLE",
            "timestamp(6) without time zone; LOSSY|datetime|DATE_RANGE_NOT_REPRESENTABLE,YEAR_ZERO_DRIVER_REJECTED",
            "timestamp(6) with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "uuid; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "xml; UNSUPPORTED|-|TYPE_UNMAPPED"})
    void postgresSourceToStarrocksGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(STARROCKS,
                LogicalTypeMappingTest.pg("v", nativeType, pgJdbc(nativeType)), false)));
    }

    @Test void postgresCollationsAndKeysOnStarrocksTarget() {
        assertEquals("EXACT|varchar(160)|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(STARROCKS,
                pgCollated("v", "character varying(40)"), false)));
        assertEquals("LOSSY|char(20)|PAD_SPACE_EQUALITY_CHANGED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(STARROCKS,
                pgCollated("v", "character(5)"), false)));
        assertEquals("EXACT|int|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(STARROCKS,
                LogicalTypeMappingTest.pg("k", "integer", Types.INTEGER), true)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(STARROCKS,
                pgCollated("k", "character varying(20)"), true)));
        assertEquals("EXACT|bigint|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                StarrocksMappingFixtures.column("k", "bigint(20)"), true)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                StarrocksMappingFixtures.column("k", "varchar(20)"), true)));
    }

    @ParameterizedTest(name = "{0} {1} -> STARROCKS")
    @CsvSource(delimiter = ';', value = {
            "MYSQL; tinyint; EXACT|tinyint|",
            "MYSQL; tinyint unsigned; EXACT|smallint|",
            "MYSQL; mediumint(9); EXACT|int|",
            "MYSQL; int unsigned; EXACT|bigint|",
            "MYSQL; bigint; EXACT|bigint|",
            "MYSQL; bigint unsigned; EXACT|largeint|",
            "MYSQL; decimal(12,2) unsigned; EXACT|decimal(12,2)|",
            "MYSQL; double; LOSSY|double|NEGATIVE_ZERO_NOT_PRESERVED",
            "MYSQL; float; LOSSY|float|FLOAT32_PRECISION_NOT_PRESERVED",
            "MYSQL; date; LOSSY|date|YEAR_ZERO_DRIVER_REJECTED",
            "MYSQL; datetime(6); LOSSY|datetime|YEAR_ZERO_DRIVER_REJECTED",
            "MYSQL; json; EXACT|json|",
            "MYSQL; blob; EXACT|varbinary|",
            "MYSQL; time(3); UNSUPPORTED|-|TIME_TYPE_UNAVAILABLE",
            "MARIADB; uuid; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE"})
    void mysqlFamilyToStarrocksGolden(ProductTypeEnum source, String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(STARROCKS,
                LogicalTypeMappingTest.family(source, "v", nativeType), false)));
    }

    @ParameterizedTest(name = "{0} {1} {2} -> STARROCKS")
    @CsvSource(delimiter = ';', value = {
            "MYSQL; varchar(20); utf8mb4_bin; LOSSY|varchar(80)|PAD_SPACE_EQUALITY_CHANGED",
            "MYSQL; varchar(20); utf8mb4_0900_bin; EXACT|varchar(80)|",
            "MYSQL; text; utf8mb4_bin; LOSSY|varchar(65535)|PAD_SPACE_EQUALITY_CHANGED",
            "MYSQL; text; utf8mb4_0900_bin; EXACT|varchar(65535)|",
            "TIDB; char(5); utf8mb4_bin; LOSSY|char(20)|PAD_SPACE_EQUALITY_CHANGED",
            "MYSQL; varchar(20); utf8mb4_0900_ai_ci; UNSUPPORTED|-|COLLATION_UNAVAILABLE"})
    void mysqlFamilyCharacterToStarrocksGolden(ProductTypeEnum source, String nativeType, String collation, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(STARROCKS,
                LogicalTypeMappingTest.text(source, "v", nativeType, collation), false)));
    }

    @Test void starrocksDriverAndCatalogFactsAreEnforced() {
        var wrongBoolean = StarrocksMappingFixtures.column("v", "tinyint(4)");
        wrongBoolean.setFieldType(Types.BIT);
        wrongBoolean.setFiledTypeClassName("java.lang.Boolean");
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongBoolean, false)));
        var wrongPrecision = StarrocksMappingFixtures.column("v", "int(11)");
        wrongPrecision.setPrecisionSize(11);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongPrecision, false)));
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, StarrocksMappingFixtures.column("v", "decimal(39, 9)"), false)));
        var foreign = StarrocksMappingFixtures.column("v", "int(11)");
        StarrocksMappingFixtures.withFacts(foreign, f -> new ColumnMetadataEvidence(
                new MetadataProvenance(STARROCKS, "MySQL", "8.0.33", "NATIVE_CATALOG_V1", Instant.EPOCH),
                f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(),
                f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, foreign, false)));
    }

    @Test void nativeBinaryGrantRejectsDriverOrCatalogDisagreementAndAllBinaryKeys() {
        var binary = StarrocksMappingFixtures.column("payload", "varbinary");
        assertEquals(TypeFidelity.EXACT, MAPPINGS.map(POSTGRESQL, binary, false).fidelity());
        for (var change : java.util.List.<java.util.function.Consumer<ColumnDescription>>of(
                c -> c.setFieldType(Types.CHAR), c -> c.setPrecisionSize(193),
                c -> c.setScaleSize(1), c -> c.setSigned(true), c -> c.setFiledTypeClassName("[B"))) {
            var bad = StarrocksMappingFixtures.column("payload", "varbinary"); change.accept(bad);
            assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,bad,false)));
        }
        var bad = StarrocksMappingFixtures.column("payload", "varbinary");
        StarrocksMappingFixtures.withFacts(bad, f -> new ColumnMetadataEvidence(f.provenance(),f.nativeType(),f.defaultExpression(),f.nullable(),
                f.numericPrecision(),f.numericScale(),MetadataValue.unknown(),f.identity(),f.generatedExpression(),f.charset(),f.collation(),f.sequence()));
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,bad,false)));
        var pg = LogicalTypeMappingTest.pg("payload", "bytea", Types.BINARY);
        assertEquals("UNSUPPORTED|-|BINARY_KEY_UNSUPPORTED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(STARROCKS,pg,true)));
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.starrocks.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
    void probedStarrocksValuesRoundTripThroughConnectorJ() throws Exception {
        String db = "sr_map_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (var c = DriverManager.getConnection(System.getProperty("datahub.test.starrocks.url"),
                System.getProperty("datahub.test.starrocks.user", "root"), "")) {
            try (var s = c.createStatement()) {
                s.execute("CREATE DATABASE " + db);
                s.execute("CREATE TABLE " + db + ".t(k BIGINT NOT NULL, v_tiny TINYINT, v_bool BOOLEAN, v_dec DECIMAL(5,2),"
                        + " v_float FLOAT, v_double DOUBLE, v_char CHAR(5), v_json JSON, v_dt DATETIME, v_day DATE)"
                        + " PRIMARY KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
                try (var version = s.executeQuery("SELECT current_version()")) {
                    assertTrue(version.next());
                    assertEquals("3.5.21-85fccf8", version.getString(1));
                }
                s.execute("INSERT INTO " + db + ".t(k,v_tiny,v_bool,v_dec,v_float,v_double,v_char,v_json,v_dt,v_day) VALUES "
                        + "(1,127,true,999.99,16777217,16777217,'😀X','{\"n\":123456789012345678901234567890}',"
                        + "'2026-10-05 12:34:56.654321','2026-10-05')");
                s.execute("INSERT INTO " + db + ".t(k,v_tiny) VALUES (2,128)");
                s.execute("INSERT INTO " + db + ".t(k,v_tiny) VALUES (3,-128)");
                s.execute("CREATE TABLE " + db + ".`keys`(k BIGINT NOT NULL, v INT) PRIMARY KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")");
                s.execute("INSERT INTO " + db + ".`keys` VALUES (7,10)");
                s.execute("INSERT INTO " + db + ".`keys` VALUES (7,20)");
                try (var r = s.executeQuery("SELECT k, v_tiny, v_bool, v_dec, v_float, v_double, v_char, v_json, v_dt, v_day FROM "
                        + db + ".t ORDER BY k")) {
                    assertTrue(r.next());
                    assertEquals(1L, r.getLong(1));
                    assertEquals(127, r.getInt(2));
                    assertTrue(r.getBoolean(3));
                    assertEquals(0, new java.math.BigDecimal("999.99").compareTo(r.getBigDecimal(4)));
                    assertEquals(16777216f, r.getFloat(5));
                    assertEquals(16777217d, r.getDouble(6));
                    assertEquals("😀X", r.getString(7));
                    assertEquals("{\"n\": 1.2345678901234566e+29}", r.getString(8));
                    assertEquals(LocalDateTime.parse("2026-10-05T12:34:56.654321"), r.getObject(9, LocalDateTime.class));
                    assertEquals("2026-10-05", r.getString(10));
                    assertTrue(r.next());
                    assertEquals(2L, r.getLong(1));
                    assertNull(r.getObject(2));
                    assertTrue(r.next());
                    assertEquals(3L, r.getLong(1));
                    assertEquals(-128, r.getInt(2));
                    assertFalse(r.next());
                }
                try (var r = s.executeQuery("SELECT v FROM " + db + ".`keys`")) {
                    assertTrue(r.next());
                    assertEquals(20, r.getInt(1));
                    assertFalse(r.next());
                }
            }
        } finally {
            try (var c = DriverManager.getConnection(System.getProperty("datahub.test.starrocks.url"),
                    System.getProperty("datahub.test.starrocks.user", "root"), "")) {
                try (var s = c.createStatement()) { s.execute("DROP DATABASE IF EXISTS " + db); }
            } catch (Exception ignored) { }
        }
    }

    private static ColumnDescription pgCollated(String name, String type) {
        var column = LogicalTypeMappingTest.pg(name, type, pgJdbc(type));
        return StarrocksMappingFixtures.withFacts(column, f -> new ColumnMetadataEvidence(
                f.provenance(), f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(),
                f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), MetadataValue.value("pg_catalog.\"C\""), f.sequence()));
    }

    /** pgjdbc column type. Same branches as {@link LogicalTypeMappingTest} for the types this class maps. */
    private static int pgJdbc(String type) {
        if (type.endsWith("[]")) return Types.ARRAY;
        return switch (type.replaceAll("\\(.*", "")) {
            case "smallint" -> Types.SMALLINT;
            case "integer" -> Types.INTEGER;
            case "bigint" -> Types.BIGINT;
            case "numeric" -> Types.NUMERIC;
            case "real" -> Types.REAL;
            case "double precision" -> Types.DOUBLE;
            case "boolean" -> Types.BIT;
            case "character varying", "text" -> Types.VARCHAR;
            case "character" -> Types.CHAR;
            case "bytea" -> Types.BINARY;
            case "date" -> Types.DATE;
            case "xml" -> Types.SQLXML;
            default -> type.startsWith("timestamp") ? Types.TIMESTAMP : type.startsWith("time") ? Types.TIME : Types.OTHER;
        };
    }
}
