package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.TDENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * TDengine 3.3.8.8 logical type mapping. Golden values come from the local WebSocket probe; the live test is gated
 * and is not part of the DB-less run.
 */
class LogicalTypeMappingTdengineTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();

    @Test void registryCarriesTheTdengineMapper() {
        var mapper = REGISTRY.typeMapper(TDENGINE).orElseThrow();
        assertInstanceOf(TdengineLogicalTypeMapper.class, mapper);
        assertEquals(TDENGINE, mapper.product());
        assertTrue(MAPPINGS.supports(TDENGINE, POSTGRESQL));
        assertTrue(MAPPINGS.supports(POSTGRESQL, TDENGINE));
        assertFalse(MAPPINGS.supports(TDENGINE, TDENGINE));
    }

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "bool; EXACT|boolean|; -",
            "tinyint; EXACT|smallint|; \"v\" BETWEEN -128 AND 127",
            "smallint; EXACT|smallint|; -",
            "int; EXACT|integer|; -",
            "bigint; EXACT|bigint|; -",
            "tinyint unsigned; EXACT|smallint|; \"v\" BETWEEN 0 AND 255",
            "smallint unsigned; EXACT|integer|; \"v\" BETWEEN 0 AND 65535",
            "int unsigned; EXACT|bigint|; \"v\" BETWEEN 0 AND 4294967295",
            "bigint unsigned; EXACT|numeric(20,0)|; \"v\" BETWEEN 0 AND 18446744073709551615",
            "float; LOSSY|real|FLOAT32_PRECISION_NOT_PRESERVED; -",
            "double; EXACT|double precision|; -",
            "decimal(10, 2); EXACT|numeric(10,2)|; -",
            "decimal(38,9); EXACT|numeric(38,9)|; -",
            "varchar(20); UNSUPPORTED|-|; -",
            "nchar(20); EXACT|character varying(20) COLLATE \"C\"|; -",
            "varbinary(20); EXACT|bytea|; pg_catalog.octet_length(\"v\") <= 20",
            "json; UNSUPPORTED|-|; -",
            "blob; UNSUPPORTED|-|; -",
            "geometry(64); UNSUPPORTED|-|; -"})
    void tdengineSourceToPostgresGolden(String nativeType, String expected, String check) {
        var mapping = MAPPINGS.map(POSTGRESQL, TdengineMappingFixtures.column("v", nativeType), false);
        assertEquals(code(nativeType, expected), PgFamilyMappingFixtures.outcome(mapping));
        String actual = mapping.definition() != null && mapping.definition().contains(" CHECK (")
                ? mapping.definition().substring(mapping.definition().indexOf(" CHECK (") + 8, mapping.definition().length() - 1) : null;
        assertEquals(check.equals("-") ? null : check, actual);
    }

    @ParameterizedTest(name = "timestamp digits {0}")
    @CsvSource(delimiter = ';', value = {
            "3; EXACT|timestamp(3) with time zone|",
            "6; EXACT|timestamp(6) with time zone|",
            "9; LOSSY|timestamp(6) with time zone|TIMESTAMP_OVERFLOW_SILENT,TIME_FRACTION_TRUNCATED"})
    void timestampPrecisionFollowsTheDatabase(int digits, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                TdengineMappingFixtures.timestamp("v", digits), false)));
    }

    @ParameterizedTest(name = "{0} -> TDENGINE")
    @CsvSource(delimiter = ';', value = {
            "smallint; SMALLINT; EXACT|SMALLINT|",
            "integer; INTEGER; EXACT|INT|",
            "bigint; BIGINT; EXACT|BIGINT|",
            "numeric(10,2); NUMERIC; LOSSY|DECIMAL(10,2)|NAN_STORED_AS_ZERO",
            "numeric(38,9); NUMERIC; LOSSY|DECIMAL(38,9)|NAN_STORED_AS_ZERO",
            "numeric; NUMERIC; UNSUPPORTED|-|UNBOUNDED_NUMERIC_UNMAPPED",
            "numeric(39,0); NUMERIC; UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "real; REAL; LOSSY|FLOAT|FLOAT32_PRECISION_NOT_PRESERVED",
            "double precision; DOUBLE; EXACT|DOUBLE|",
            "boolean; BIT; EXACT|BOOL|",
            "character varying(40); VARCHAR; EXACT|NCHAR(40)|",
            "character(5); CHAR; EXACT|NCHAR(5)|",
            "text; VARCHAR; UNSUPPORTED|-|UNBOUNDED_CHARACTER_UNMAPPED",
            "bytea; BINARY; UNSUPPORTED|-|UNBOUNDED_BINARY_UNMAPPED",
            "date; DATE; UNSUPPORTED|-|DATE_TYPE_UNAVAILABLE",
            "time(3) without time zone; TIME; UNSUPPORTED|-|TIME_24_00_WRAPPED,TIME_TYPE_UNAVAILABLE",
            "timestamp(6) without time zone; TIMESTAMP; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "timestamp(6) with time zone; TIMESTAMP; LOSSY|TIMESTAMP|DATE_RANGE_NOT_REPRESENTABLE,TIME_FRACTION_TRUNCATED",
            "uuid; OTHER; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE",
            "json; OTHER; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "xml; SQLXML; UNSUPPORTED|-|TYPE_UNMAPPED"})
    void postgresSourceToTdengineGolden(String nativeType, String jdbcName, String expected) {
        int jdbcType = switch (jdbcName) {
            case "SMALLINT" -> Types.SMALLINT;
            case "INTEGER" -> Types.INTEGER;
            case "BIGINT" -> Types.BIGINT;
            case "NUMERIC" -> Types.NUMERIC;
            case "REAL" -> Types.REAL;
            case "DOUBLE" -> Types.DOUBLE;
            case "BIT" -> Types.BIT;
            case "VARCHAR" -> Types.VARCHAR;
            case "CHAR" -> Types.CHAR;
            case "BINARY" -> Types.BINARY;
            case "DATE" -> Types.DATE;
            case "TIME" -> Types.TIME;
            case "TIMESTAMP" -> Types.TIMESTAMP;
            case "OTHER" -> Types.OTHER;
            case "SQLXML" -> Types.SQLXML;
            default -> throw new IllegalArgumentException(jdbcName);
        };
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(TDENGINE,
                LogicalTypeMappingTest.pg("v", nativeType, jdbcType), false)));
    }

    @Test void keysOverwriteAndCharacterKeysStayUnproved() {
        assertEquals("LOSSY|INT|KEY_UNIQUENESS_NOT_ENFORCED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(TDENGINE,
                LogicalTypeMappingTest.pg("k", "integer", Types.INTEGER), true)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(TDENGINE,
                LogicalTypeMappingTest.pg("k", "character varying(20)", Types.VARCHAR), true)));
        assertEquals("EXACT|integer|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                TdengineMappingFixtures.column("k", "int"), true)));
    }

    @Test void driverAndCatalogFactsAreEnforced() {
        var wrongBoolean = TdengineMappingFixtures.column("v", "bool");
        wrongBoolean.setFieldType(Types.BIT);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongBoolean, false)));
        var unsignedBytes = TdengineMappingFixtures.column("v", "tinyint unsigned");
        unsignedBytes.setSigned(false);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, unsignedBytes, false)));
        var missingPrecision = TdengineMappingFixtures.timestamp("v", 3);
        TdengineMappingFixtures.withFacts(missingPrecision, facts -> new ColumnMetadataEvidence(facts.provenance(),
                facts.nativeType(), facts.defaultExpression(), facts.nullable(), facts.numericPrecision(),
                MetadataValue.absent(), facts.characterLength(), facts.identity(), facts.generatedExpression(),
                facts.charset(), facts.collation(), facts.sequence()));
        assertEquals("UNSUPPORTED|-|INCOMPLETE_METADATA",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, missingPrecision, false)));
        var foreign = TdengineMappingFixtures.column("v", "int");
        TdengineMappingFixtures.withFacts(foreign, facts -> new ColumnMetadataEvidence(
                new MetadataProvenance(TDENGINE, "TDengine", "3.3.7.0", "TDENGINE_CATALOG_V1", Instant.EPOCH),
                facts.nativeType(), facts.defaultExpression(), facts.nullable(), facts.numericPrecision(), facts.numericScale(),
                facts.characterLength(), facts.identity(), facts.generatedExpression(), facts.charset(), facts.collation(),
                facts.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, foreign, false)));
        assertEquals("EXACT|character varying(16376) COLLATE \"C\"|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                TdengineMappingFixtures.column("v", "nchar(16376)"), false)));
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                TdengineMappingFixtures.column("v", "nchar(16377)"), false)));
        assertEquals("UNSUPPORTED|-|TYPE_UNMAPPED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                TdengineMappingFixtures.column("v", "varchar(65517)"), false)));
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.tdengine.url", matches = "jdbc:TAOS-WS://127[.]0[.]0[.]1:[0-9]+/.*")
    void liveRegularColumnsMatchTheProbedDriverShapes() throws Exception {
        String password = System.getenv("DATAHUB_TDENGINE_TEST_PASSWORD");
        assertNotNull(password, "DATAHUB_TDENGINE_TEST_PASSWORD is required");
        String database = "w14g" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Class.forName("com.taosdata.jdbc.ws.WebSocketDriver");
        try (Connection connection = DriverManager.getConnection(System.getProperty("datahub.test.tdengine.url"), "root", password)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database);
                statement.execute("CREATE DATABASE " + database + " PRECISION 'ms'");
                statement.execute("CREATE TABLE " + database + ".probe (`ts` TIMESTAMP, `b` BOOL, `t` TINYINT, `tu` TINYINT UNSIGNED,"
                        + " `i` INT, `g` BIGINT, `f` FLOAT, `d` DOUBLE, `vc` VARCHAR(5), `nc` NCHAR(2), `vb` VARBINARY(4),"
                        + " `c` DECIMAL(10,2))");
                try (ResultSet rows = statement.executeQuery("SELECT `precision` FROM information_schema.ins_databases WHERE name='" + database + "'")) {
                    assertTrue(rows.next());
                    assertEquals("ms", rows.getString(1));
                }
                try (ResultSet rows = statement.executeQuery("SELECT SERVER_VERSION()")) {
                    assertTrue(rows.next());
                    assertTrue(rows.getString(1).matches("3\\.3\\.8\\.[0-9]+"));
                }
                try (ResultSet rows = statement.executeQuery("SELECT * FROM " + database + ".probe LIMIT 0")) {
                    ResultSetMetaData meta = rows.getMetaData();
                    assertEquals(Types.TIMESTAMP, meta.getColumnType(1));
                    assertEquals(0, meta.isNullable(1));
                    assertEquals(Types.BOOLEAN, meta.getColumnType(2));
                    assertEquals("java.lang.Boolean", meta.getColumnClassName(2));
                    assertEquals(Types.TINYINT, meta.getColumnType(3));
                    assertEquals("java.lang.Byte", meta.getColumnClassName(3));
                    assertEquals(Types.SMALLINT, meta.getColumnType(4));
                    assertEquals("TINYINT UNSIGNED", meta.getColumnTypeName(4));
                    assertEquals("BINARY", meta.getColumnTypeName(9));
                    assertEquals("[B", meta.getColumnClassName(9));
                    assertEquals(5, meta.getPrecision(9));
                    assertEquals(Types.NCHAR, meta.getColumnType(10));
                    assertEquals("java.lang.String", meta.getColumnClassName(10));
                    assertEquals(Types.VARBINARY, meta.getColumnType(11));
                    assertEquals(Types.DECIMAL, meta.getColumnType(12));
                    assertEquals(10, meta.getPrecision(12));
                    assertEquals(2, meta.getScale(12));
                }
                statement.execute("INSERT INTO " + database + ".probe VALUES ('2026-10-06 12:34:56.123', true, -128, 255, 1, 1, -0.0, -0.0, '中', '😀', NULL, 1.239)");
                try (ResultSet rows = statement.executeQuery("SELECT `ts`, `b`, `t`, `tu`, `i`, `f`, `d`, `vc`, `nc`, `c` FROM " + database + ".probe")) {
                    assertTrue(rows.next());
                    assertEquals(1791290096123L, rows.getTimestamp(1).getTime());
                    assertTrue(rows.getBoolean(2));
                    assertEquals(-128, rows.getByte(3));
                    assertEquals(255, rows.getShort(4));
                    assertEquals(1, rows.getInt(5));
                    assertEquals(0x80000000, Float.floatToRawIntBits(rows.getFloat(6)));
                    assertEquals(0x8000000000000000L, Double.doubleToRawLongBits(rows.getDouble(7)));
                    assertEquals(3, rows.getBytes(8).length);
                    assertEquals("😀", rows.getString(9));
                    assertEquals(0, new java.math.BigDecimal("1.24").compareTo(rows.getBigDecimal(10)));
                    assertFalse(rows.next());
                }
                statement.execute("INSERT INTO " + database + ".probe VALUES ('2026-10-06 12:34:56.123', true, -128, 255, 2, 1, -0.0, -0.0, '中', '😀', NULL, 1.239)");
                try (ResultSet rows = statement.executeQuery("SELECT `i` FROM " + database + ".probe")) {
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt(1));
                    assertFalse(rows.next());
                }
                assertThrows(java.sql.SQLException.class, () -> statement.execute(
                        "CREATE TABLE " + database + ".bad (`ts` TIMESTAMP, `v` JSON)"));
                assertThrows(java.sql.SQLException.class, () -> statement.execute(
                        "INSERT INTO " + database + ".probe (`ts`) VALUES (NULL)"));
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP DATABASE IF EXISTS " + database);
                }
            }
        }
    }

    private static String code(String nativeType, String expected) {
        if (expected.startsWith("UNSUPPORTED")) {
            String blocker = switch (nativeType) {
                case "varchar(20)" -> "TYPE_UNMAPPED";
                case "json" -> "JSON_TAG_ONLY";
                case "blob", "geometry(64)" -> "VALUE_CODEC_UNSUPPORTED";
                default -> "";
            };
            return "UNSUPPORTED|-|" + blocker;
        }
        int bars = expected.length() - expected.replace("|", "").length();
        return bars >= 2 && !expected.endsWith("|") || expected.endsWith("|") ? expected : expected + "|";
    }
}
