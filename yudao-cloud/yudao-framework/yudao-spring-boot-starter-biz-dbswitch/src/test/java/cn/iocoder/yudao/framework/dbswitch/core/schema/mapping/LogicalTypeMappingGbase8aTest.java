package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.product.gbase8a.Gbase8aLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * GBase 8a 8.6.2.43-R7-free (gbase-connector-java-8.3.81.53-build55.5.3) logical type mapping: golden tables plus real
 * GBase 8a↔PostgreSQL 16 and GBase 8a→MySQL 8.4 round trips. Every GBase fact was probed on the real server.
 */
@Timeout(300)
class LogicalTypeMappingGbase8aTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();
    private static final boolean ZONE_GAPS = !java.time.ZoneId.systemDefault().getRules().isFixedOffset();
    private final String gbDb = "mfm_g_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final String pgSchema = "mfm_g_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final String mysqlTable = "mfm_g_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final List<Runnable> cleanup = new ArrayList<>();
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        runtime = DbSwitchRuntime.standalone();
        if (System.getProperty("datahub.test.gbase8a.url") != null) {
            Class.forName("com.gbase.jdbc.Driver");
            sql("gbase", "CREATE DATABASE " + gbDb);
            cleanup.add(() -> quietly("gbase", "DROP DATABASE IF EXISTS " + gbDb));
        }
        if (System.getProperty("datahub.test.postgres.url") != null) {
            sql("postgres", "CREATE SCHEMA " + pgSchema);
            cleanup.add(() -> quietly("postgres", "DROP SCHEMA IF EXISTS " + pgSchema + " CASCADE"));
        }
        if (System.getProperty("datahub.test.mysql.url") != null)
            cleanup.add(() -> quietly("mysql", "DROP TABLE IF EXISTS `dbswitch_test`.`" + mysqlTable + "`"));
    }

    @AfterEach void teardown() {
        try { Collections.reverse(cleanup); cleanup.forEach(Runnable::run); }
        finally { if (runtime != null) runtime.close(); }
    }

    // ---------------------------------------------------------------- registry

    @Test void registryCarriesTheGbase8aMapper() {
        var mapper = REGISTRY.typeMapper(GBASE8A).orElseThrow();
        assertInstanceOf(Gbase8aLogicalTypeMapper.class, mapper);
        assertEquals(GBASE8A, mapper.product());
        assertTrue(MAPPINGS.supports(GBASE8A, POSTGRESQL));
        assertTrue(MAPPINGS.supports(GBASE8A, MYSQL));
        assertFalse(MAPPINGS.supports(GBASE8A, GBASE8A));
    }

    // ---------------------------------------------------------------- golden: GBase 8a source -> PostgreSQL

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "tinyint(4); EXACT|smallint|; \"v\" BETWEEN -128 AND 127",
            "smallint(6); EXACT|smallint|; -",
            "mediumint(9); EXACT|integer|; \"v\" BETWEEN -8388608 AND 8388607",
            "int(11); EXACT|integer|; -",
            "bigint(20); EXACT|bigint|; -",
            "decimal(38,8); EXACT|numeric(38,8)|; -",
            "decimal(65,30); EXACT|numeric(65,30)|; -",
            "float; LOSSY|real|FLOAT32_PRECISION_NOT_PRESERVED; -",
            "double; EXACT|double precision|; -",
            "binary(4); EXACT|bytea|; pg_catalog.octet_length(\"v\") = 4",
            "varbinary(16); EXACT|bytea|; pg_catalog.octet_length(\"v\") <= 16",
            "blob; EXACT|bytea|; -",
            "date; EXACT|date|; -",
            "datetime; EXACT|timestamp(6) without time zone|; -",
            "timestamp; UNSUPPORTED|-|TIMESTAMP_ON_UPDATE_CODEC_UNVERIFIED; -",
            "time; UNSUPPORTED|-|TIME_VALUE_CODEC_UNSUPPORTED; -",
            "year(4); UNSUPPORTED|-|TYPE_UNMAPPED; -",
            "tinyint(1); UNSUPPORTED|-|BOOLEAN_DRIVER_COERCION; -",
            "float(7,2); UNSUPPORTED|-|TYPE_UNMAPPED; -",
            "int unsigned; UNSUPPORTED|-|TYPE_UNMAPPED; -",
            "bit(3); UNSUPPORTED|-|TYPE_UNMAPPED; -",
            "enum('a','b'); UNSUPPORTED|-|TYPE_UNMAPPED; -",
            "json; UNSUPPORTED|-|TYPE_UNMAPPED; -",
            "decimal(10,-2); UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED; -",
            "geometry; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED; -"})
    void gbaseSourceToPostgresGolden(String nativeType, String expected, String check) {
        var mapping = MAPPINGS.map(POSTGRESQL, MysqlFamilyExtendedFixtures.family(GBASE8A, "v", nativeType), false);
        assertEquals(expected, PgFamilyMappingFixtures.outcome(mapping));
        if (mapping.definition() != null)
            assertEquals(check.equals("-") ? null : check, mapping.definition().contains(" CHECK (")
                    ? mapping.definition().substring(mapping.definition().indexOf(" CHECK (") + 8, mapping.definition().length() - 1) : null);
    }

    @ParameterizedTest(name = "{0} {1} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "char(5); utf8mb4_bin; LOSSY|character(5) COLLATE \"C\"|FOUR_BYTE_CHARACTERS_NOT_PRESERVED",
            "varchar(64); utf8mb4_bin; LOSSY|character varying(64) COLLATE \"C\"|FOUR_BYTE_CHARACTERS_NOT_PRESERVED,PAD_SPACE_EQUALITY_CHANGED",
            "varchar(64); utf8mb4_general_ci; LOSSY|character varying(64)|COLLATION_EQUALITY_CHANGED,FOUR_BYTE_CHARACTERS_NOT_PRESERVED",
            "text; utf8mb4_bin; LOSSY|text COLLATE \"C\"|FOUR_BYTE_CHARACTERS_NOT_PRESERVED,PAD_SPACE_EQUALITY_CHANGED"})
    void gbaseCharacterToPostgresGolden(String nativeType, String collation, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                MysqlFamilyExtendedFixtures.text(GBASE8A, "v", nativeType, collation), false)));
    }

    // ---------------------------------------------------------------- golden: PostgreSQL source -> GBase 8a

    @ParameterizedTest(name = "{0} -> GBASE8A")
    @CsvSource(delimiter = ';', value = {
            "smallint; LOSSY|smallint|INTEGER_DOMAIN_NARROWED",
            "integer; LOSSY|int|INTEGER_DOMAIN_NARROWED",
            "bigint; LOSSY|bigint|INTEGER_DOMAIN_NARROWED",
            "numeric(38,18); LOSSY|decimal(38,18)|NAN_NOT_REPRESENTABLE",
            "numeric; UNSUPPORTED|-|UNBOUNDED_NUMERIC_UNMAPPED",
            "numeric(66,0); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "real; LOSSY|float|FLOAT32_PRECISION_NOT_PRESERVED,NEGATIVE_ZERO_NOT_PRESERVED,NON_FINITE_NOT_REPRESENTABLE",
            "double precision; LOSSY|double|NEGATIVE_ZERO_NOT_PRESERVED,NON_FINITE_NOT_REPRESENTABLE",
            "boolean; EXACT|tinyint(1)|",
            "character varying(40); EXACT|varchar(40)|",
            "text; EXACT|text|",
            "bytea; EXACT|blob|",
            "date; LOSSY|date|DATE_RANGE_NOT_REPRESENTABLE",
            "time(3) without time zone; UNSUPPORTED|-|TIME_24_00_WRAPPED,TIME_VALUE_CODEC_UNSUPPORTED",
            "timestamp(6) without time zone; LOSSY|datetime|DATE_RANGE_NOT_REPRESENTABLE",
            "timestamp(6) with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "uuid; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "jsonb; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "integer[]; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "money; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void postgresSourceToGbaseGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(GBASE8A,
                LogicalTypeMappingTest.pg("v", nativeType, MysqlFamilyExtendedFixtures.pgJdbc(nativeType)), false)));
    }

    @Test void postgresCollationsAndKeysOnGbaseTarget() {
        assertEquals("LOSSY|varchar(40)|PAD_SPACE_EQUALITY_CHANGED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(GBASE8A,
                pgCollated("v", "character varying(40)"), false)));
        assertEquals("EXACT|char(5)|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(GBASE8A,
                pgCollated("v", "character(5)"), false)));
        assertEquals("LOSSY|text|PAD_SPACE_EQUALITY_CHANGED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(GBASE8A,
                pgCollated("v", "text"), false)));
        // Keys: GBase 8a PRIMARY KEY is metadata only, so key columns are LOSSY; text keys are rejected outright.
        assertEquals("LOSSY|int|INTEGER_DOMAIN_NARROWED,KEY_UNIQUENESS_NOT_ENFORCED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(GBASE8A,
                LogicalTypeMappingTest.pg("k", "integer", Types.INTEGER), true)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(GBASE8A,
                pgCollated("k", "character varying(20)"), true)));
        assertEquals("EXACT|bigint|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                MysqlFamilyExtendedFixtures.family(GBASE8A, "k", "bigint(20)"), true)));
        assertEquals("UNSUPPORTED|-|FOUR_BYTE_CHARACTERS_NOT_PRESERVED,TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                MysqlFamilyExtendedFixtures.text(GBASE8A, "k", "varchar(20)", "utf8mb4_bin"), true)));
    }

    // ---------------------------------------------------------------- golden: GBase 8a source -> MySQL 8.4

    @ParameterizedTest(name = "{0} -> MYSQL")
    @CsvSource(delimiter = ';', value = {
            "tinyint(4); EXACT|tinyint|", "smallint(6); EXACT|smallint|", "mediumint(9); EXACT|mediumint|",
            "int(11); EXACT|int|", "bigint(20); EXACT|bigint|",
            "decimal(38,8); EXACT|decimal(38,8)|", "double; EXACT|double|",
            "float; LOSSY|float|FLOAT32_PRECISION_NOT_PRESERVED",
            "binary(4); EXACT|binary(4)|", "varbinary(16); EXACT|varbinary(16)|", "blob; EXACT|blob|",
            "date; EXACT|date|", "datetime; EXACT|datetime(6)|",
            "timestamp; UNSUPPORTED|-|TIMESTAMP_ON_UPDATE_CODEC_UNVERIFIED",
            "time; UNSUPPORTED|-|TIME_VALUE_CODEC_UNSUPPORTED",
            "tinyint(1); UNSUPPORTED|-|BOOLEAN_DRIVER_COERCION",
            "year(4); UNSUPPORTED|-|TYPE_UNMAPPED"})
    void gbaseSourceToMysqlGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(MYSQL,
                MysqlFamilyExtendedFixtures.family(GBASE8A, "v", nativeType), false)));
    }

    @ParameterizedTest(name = "{0} {1} -> MYSQL")
    @CsvSource(delimiter = ';', value = {
            "char(5); utf8mb4_bin; LOSSY|char(5) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin|FOUR_BYTE_CHARACTERS_NOT_PRESERVED",
            "varchar(64); utf8mb4_bin; LOSSY|varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin|FOUR_BYTE_CHARACTERS_NOT_PRESERVED",
            "varchar(64); utf8mb4_general_ci; LOSSY|varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci|COLLATION_EQUALITY_UNVERIFIED,FOUR_BYTE_CHARACTERS_NOT_PRESERVED",
            "text; utf8mb4_bin; LOSSY|text CHARACTER SET utf8mb4 COLLATE utf8mb4_bin|FOUR_BYTE_CHARACTERS_NOT_PRESERVED"})
    void gbaseCharacterToMysqlGolden(String nativeType, String collation, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(MYSQL,
                MysqlFamilyExtendedFixtures.text(GBASE8A, "v", nativeType, collation), false)));
    }

    @Test void gbaseDriverAndCatalogFactsAreEnforced() {
        var wrongPrecision = MysqlFamilyExtendedFixtures.family(GBASE8A, "v", "tinyint(4)"); wrongPrecision.setPrecisionSize(3);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongPrecision, false)));
        var wrongCatalog = MysqlFamilyExtendedFixtures.family(GBASE8A, "v", "tinyint(4)");
        MysqlFamilyExtendedFixtures.withFacts(wrongCatalog, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                f.provenance(), f.nativeType(), f.defaultExpression(), f.nullable(), MetadataValue.value(4), f.numericScale(),
                f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongCatalog, false)));
        var foreign = MysqlFamilyExtendedFixtures.family(GBASE8A, "v", "int(11)");
        MysqlFamilyExtendedFixtures.withFacts(foreign, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                new cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance(GBASE8A, "MySQL", "8.4.8", "NATIVE_CATALOG_V1", java.time.Instant.EPOCH),
                f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(),
                f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, foreign, false)));
    }

    // ---------------------------------------------------------------- real GBase 8a <-> PostgreSQL / MySQL round trips

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.gbase8a.url", matches = "jdbc:gbase://127[.]0[.]0[.]1:[0-9]+/gbase")
    @EnabledIfSystemProperty(named = "datahub.test.gbase8a.driver", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_GBASE8A_TEST_PASSWORD", matches = ".+")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void gbaseSourceCreatesExactPostgresColumnsAndCopiesEveryValue() throws Exception {
        sql("gbase", "CREATE TABLE " + gbDb + ".source(k_big bigint NOT NULL, k_small smallint NOT NULL, v_int int,"
                + " v_dec decimal(38,8), v_double double, v_bytes varbinary(16), v_day date, v_at datetime,"
                + " PRIMARY KEY(k_big, k_small)) ENGINE=EXPRESS DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        // k_big deliberately uses 2^62-1: the server misreads exactly 2^62 as NULL from a composite-PK row in a
        // non-autocommit read (probe evidence gbase-bisect*.log); the defect is recorded as a remaining boundary.
        sql("gbase", "INSERT INTO " + gbDb + ".source VALUES (4611686018427387903, -32767, 2147483647,"
                + " 999999999999999999999999999999.12345678, 1.5, 0xDEADBEEF, '0001-01-01', '2026-10-05 12:34:56.654321')");
        sql("gbase", "INSERT INTO " + gbDb + ".source VALUES (-4611686018427387905, 32767, -2147483647,"
                + " -999999999999999999999999999999.12345678, -1.5, 0x0102, '9999-12-31', '2026-01-01 00:00:00.000001')");
        sql("gbase", "INSERT INTO " + gbDb + ".source(k_big, k_small) VALUES (0, 0)");
        assertSuccess(run(GBASE8A, "gbase", POSTGRESQL, "postgres", true, false));
        assertEquals(List.of("k_big|bigint|true", "k_small|smallint|true", "v_int|integer|false", "v_dec|numeric(38,8)|false",
                "v_double|double precision|false", "v_bytes|bytea|false", "v_day|date|false",
                "v_at|timestamp(6) without time zone|false"),
                columns("postgres", "pg_catalog", pgSchema, "copied"));
        assertEquals(List.of("k_big", "k_small"), primaryKey("postgres", "pg_catalog", pgSchema, "copied"));
        List<List<Object>> expected = List.of(
                List.of(new BigDecimal("-4611686018427387905"), new BigDecimal("32767"), new BigDecimal("-2147483647"),
                        new BigDecimal("-999999999999999999999999999999.12345678"), -1.5, new byte[]{1, 2},
                        "9999-12-31", "2026-01-01 00:00:00.000001"),
                Arrays.asList(new BigDecimal("0"), new BigDecimal("0"), null, null, null, null, null, null),
                List.of(new BigDecimal("4611686018427387903"), new BigDecimal("-32767"), new BigDecimal("2147483647"),
                        new BigDecimal("999999999999999999999999999999.12345678"), 1.5, new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF},
                        "0001-01-01", "2026-10-05 12:34:56.654321"));
        String select = "SELECT k_big, k_small, v_int, v_dec, v_double, v_bytes, v_day::text,"
                + " to_char(v_at, 'YYYY-MM-DD HH24:MI:SS.US') FROM " + pgSchema + ".copied ORDER BY k_big, k_small";
        assertRows(expected, rows("postgres", select));
        // The GBase source must hold the same values.
        try (var c = open("gbase"); var s = c.createStatement(); var r = s.executeQuery(
                "SELECT k_big, k_small, v_int, v_dec, v_double, v_day, v_at FROM " + gbDb + ".source ORDER BY k_big, k_small")) {
            assertTrue(r.next());
            assertEquals("-4611686018427387905", r.getString(1));
            assertEquals("32767", r.getString(2));
            assertEquals("-2147483647", r.getString(3));
            assertEquals(0, new BigDecimal("-999999999999999999999999999999.12345678").compareTo(r.getBigDecimal(4)));
            assertEquals("9999-12-31", r.getString(6));
            assertEquals("2026-01-01 00:00:00.000001", r.getString(7));
            assertTrue(r.next());
            assertEquals("0", r.getString(1)); assertNull(r.getObject(3));
            assertTrue(r.next());
            assertEquals("4611686018427387903", r.getString(1));
            assertEquals("2147483647", r.getString(3));
            assertEquals("0001-01-01", r.getString(6));
            assertEquals("2026-10-05 12:34:56.654321", r.getString(7));
            assertFalse(r.next());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.gbase8a.url", matches = "jdbc:gbase://127[.]0[.]0[.]1:[0-9]+/gbase")
    @EnabledIfSystemProperty(named = "datahub.test.gbase8a.driver", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_GBASE8A_TEST_PASSWORD", matches = ".+")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void postgresSourceCreatesGbaseColumnsWithAcknowledgedLossesAndCopiesEveryValue() throws Exception {
        sql("postgres", "CREATE TABLE " + pgSchema + ".source(k bigint NOT NULL, v_int integer, v_bool boolean,"
                + " v_dec numeric(38,18), v_double double precision, v_name varchar(40), v_text text, v_day date,"
                + " v_at timestamp(6), PRIMARY KEY (k))");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (4611686018427387904, 2147483647, true, 1.5, 2.5,"
                + " '中é', 'line1\n中', '2026-10-05', '2026-10-05 12:34:56.654321')");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (-4611686018427387905, -2147483647, false, -1.5, -2.5,"
                + " '', '', '0001-01-01', '2026-01-01 00:00:00.000001')");
        var columns = runtime.metadata(source("postgres"), POSTGRESQL)
                .queryTableColumnMetaWithNativeEvidence(pgSchema, "source");
        // Default policy rejects: integers lose their most negative value and keys are metadata-only on GBase 8a.
        var rejected = assertThrows(TypeMappingRejectedException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                runtime.products().metadata(GBASE8A), columns, List.of("k"), gbDb, "copied", false));
        assertTrue(rejected.getMessage().contains("INTEGER_DOMAIN_NARROWED") && rejected.getMessage().contains("KEY_UNIQUENESS_NOT_ENFORCED"),
                rejected.getMessage());
        Map<String, Set<String>> acknowledged = new LinkedHashMap<>();
        acknowledged.put("k", Set.of("INTEGER_DOMAIN_NARROWED", "KEY_UNIQUENESS_NOT_ENFORCED"));
        acknowledged.put("v_int", Set.of("INTEGER_DOMAIN_NARROWED"));
        acknowledged.put("v_dec", Set.of("NAN_NOT_REPRESENTABLE"));
        acknowledged.put("v_double", Set.of("NEGATIVE_ZERO_NOT_PRESERVED", "NON_FINITE_NOT_REPRESENTABLE"));
        acknowledged.put("v_day", Set.of("DATE_RANGE_NOT_REPRESENTABLE"));
        acknowledged.put("v_at", ZONE_GAPS ? Set.of("DATE_RANGE_NOT_REPRESENTABLE", "TIMESTAMP_JVM_ZONE_DST_SHIFT")
                : Set.of("DATE_RANGE_NOT_REPRESENTABLE"));
        String ddl = GenerateSqlUtils.getDDLCreateTableSQL(runtime.products().metadata(GBASE8A), columns, List.of("k"), gbDb, "copied",
                false, TypeMappingOptions.of(MAPPINGS).withLossyAcceptance(LossyColumnAcceptance.acknowledged(acknowledged)));
        assertTrue(ddl.contains("`k` bigint NOT NULL") && ddl.contains("`v_at` datetime") && ddl.contains("PRIMARY KEY (`k`)"), ddl);
        sql("gbase", ddl);
        try (var pg = open("postgres"); var s = pg.createStatement(); var r = s.executeQuery(
                "SELECT k, v_int, v_bool, v_dec, v_double, v_name, v_text, v_day, v_at FROM " + pgSchema + ".source ORDER BY k")) {
            try (var gb = open("gbase"); var ins = gb.prepareStatement(
                    "INSERT INTO " + gbDb + ".copied VALUES (?,?,?,?,?,?,?,?,?)")) {
                while (r.next()) {
                    for (int i = 1; i <= 9; i++) ins.setObject(i, r.getObject(i));
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        assertEquals(List.of("k|bigint(20)|false", "v_int|int(11)|true", "v_bool|tinyint(1)|true", "v_dec|decimal(38,18)|true",
                "v_double|double|true", "v_name|varchar(40)|true", "v_text|text|true", "v_day|date|true", "v_at|datetime|true"),
                gbaseColumns(gbDb, "copied"));
        try (var c = open("gbase"); var s = c.createStatement(); var r = s.executeQuery(
                "SELECT k, v_int, v_bool, v_dec, v_double, v_name, v_text, v_day, v_at FROM " + gbDb + ".copied ORDER BY k")) {
            assertTrue(r.next());
            assertEquals(-4611686018427387905L, r.getLong(1));
            assertEquals(-2147483647, r.getInt(2));
            assertEquals(Boolean.FALSE, r.getBoolean(3));
            assertEquals(0, new BigDecimal("-1.5").compareTo(r.getBigDecimal(4)));
            assertEquals("0001-01-01", r.getString(8));
            assertEquals("2026-01-01 00:00:00.000001", r.getString(9));
            assertTrue(r.next());
            assertEquals(4611686018427387904L, r.getLong(1));
            assertEquals(2147483647, r.getInt(2));
            assertEquals(Boolean.TRUE, r.getBoolean(3));
            assertEquals(0, new BigDecimal("1.5").compareTo(r.getBigDecimal(4)));
            assertEquals("中é", r.getString(6));
            assertEquals("line1\n中", r.getString(7));
            assertEquals("2026-10-05", r.getString(8));
            assertEquals("2026-10-05 12:34:56.654321", r.getString(9));
            assertFalse(r.next());
        }
    }

    @ParameterizedTest(name = "{0} blocks the whole table")
    @CsvSource(delimiter = ';', value = {
            "timestamp; UNSUPPORTED; DEFAULT_UNMAPPED",
            "time; UNSUPPORTED; TIME_VALUE_CODEC_UNSUPPORTED"})
    @EnabledIfSystemProperty(named = "datahub.test.gbase8a.url", matches = "jdbc:gbase://127[.]0[.]0[.]1:[0-9]+/gbase")
    @EnabledIfSystemProperty(named = "datahub.test.gbase8a.driver", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_GBASE8A_TEST_PASSWORD", matches = ".+")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void gbaseRejectedColumnsBlockBeforeAnyTargetObjectExists(String type, String fidelity, String reason) throws Exception {
        sql("gbase", "CREATE TABLE " + gbDb + ".source(id bigint NOT NULL, label int, value " + type + ", PRIMARY KEY(id))"
                + " ENGINE=EXPRESS DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        var columns = runtime.metadata(source("gbase"), GBASE8A).queryTableColumnMetaWithNativeEvidence(gbDb, "source");
        var failure = assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                runtime.products().metadata(POSTGRESQL), columns, List.of("id"), pgSchema, "copied", null, false, Map.of()));
        String message = failure.getMessage();
        assertTrue(message.contains("value [GBASE8A") && message.contains("] " + fidelity + " ") && message.contains(reason), message);
        assertFalse(message.contains("label ["), message);
        assertFalse(tableExists("postgres", pgSchema, "copied"), "no target object may exist after a rejected plan");
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.gbase8a.url", matches = "jdbc:gbase://127[.]0[.]0[.]1:[0-9]+/gbase")
    @EnabledIfSystemProperty(named = "datahub.test.gbase8a.driver", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_GBASE8A_TEST_PASSWORD", matches = ".+")
    @EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/dbswitch_test(\\?.*)?")
    void gbaseSourceCreatesExactMysqlColumnsAndCopiesEveryValue() throws Exception {
        sql("gbase", "CREATE TABLE " + gbDb + ".source(k_big bigint NOT NULL, v_int int, v_dec decimal(38,8),"
                + " v_bin binary(4), v_day date, v_at datetime, PRIMARY KEY(k_big))"
                + " ENGINE=EXPRESS DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin");
        sql("gbase", "INSERT INTO " + gbDb + ".source VALUES (4611686018427387903, 2147483647,"
                + " 999999999999999999999999999999.12345678, 0xDEADBEEF, '0001-01-01', '2026-10-05 12:34:56.654321')");
        sql("gbase", "INSERT INTO " + gbDb + ".source VALUES (-4611686018427387905, -2147483647,"
                + " -0.00000001, 0x00, '9999-12-31', '2026-01-01 00:00:00.000001')");
        assertSuccess(runMysql());
        assertEquals(List.of("k_big|bigint", "v_int|int", "v_dec|decimal(38,8)", "v_bin|binary(4)", "v_day|date", "v_at|datetime(6)"),
                mysqlColumns("dbswitch_test", mysqlTable));
        String select = "SELECT k_big, v_int, v_dec, HEX(v_bin), v_day, v_at FROM {t} ORDER BY k_big";
        var gbRows = rows("gbase", select.replace("{t}", gbDb + ".source"));
        var myRows = rows("mysql", select.replace("{t}", "dbswitch_test." + mysqlTable));
        assertEquals(2, myRows.size());
        for (int r = 0; r < gbRows.size(); r++) {
            assertEquals(gbRows.get(r).get(0).toString(), myRows.get(r).get(0).toString());
            assertEquals(gbRows.get(r).get(1).toString(), myRows.get(r).get(1).toString());
            assertEquals(new BigDecimal(gbRows.get(r).get(2).toString()).compareTo(new BigDecimal(myRows.get(r).get(2).toString())), 0);
            assertEquals(gbRows.get(r).get(3).toString(), myRows.get(r).get(3).toString());
            assertEquals(gbRows.get(r).get(4).toString(), myRows.get(r).get(4).toString());
            // GBase renders the datetime with a space separator while MySQL JDBC uses 'T'; both carry the same
            // microsecond-exact instant, so compare after normalizing the separator.
            assertEquals(gbRows.get(r).get(5).toString().replace('T', ' '),
                    myRows.get(r).get(5).toString().replace('T', ' '));
        }
    }

    // ---------------------------------------------------------------- helpers

    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, ProductTypeEnum targetProduct,
                               String targetName, boolean insert, boolean drop) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(sourceProduct); source.setDriverClassName(sourceProduct.getDriveClassName());
        source.setUrl(url(sourceName)); source.setUsername(user(sourceName)); source.setPassword(password(sourceName)); source.setPoolSize(1);
        source.setSourceSchema(gbDb); source.setSourceIncludes("source"); source.setFetchSize(2);
        source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copied"))); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(targetProduct);
        target.setDriverClassName(targetProduct.getDriveClassName()); target.setPoolSize(1);
        target.setUrl(url(targetName)); target.setUsername(user(targetName)); target.setPassword(password(targetName));
        target.setTargetSchema(pgSchema); target.setWriterEngineInsert(insert); target.setTargetDrop(drop);
        return new MigrationService(request, ignored -> { }, null, runtime).run();
    }

    private DbSwitchResult runMysql() throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(GBASE8A); source.setDriverClassName(GBASE8A.getDriveClassName());
        source.setUrl(url("gbase")); source.setUsername("root"); source.setPassword(password("gbase")); source.setPoolSize(1);
        source.setSourceSchema(gbDb); source.setSourceIncludes("source"); source.setFetchSize(2);
        source.setRegexTableMapper(List.of(new PatternMapper("^source$", mysqlTable))); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(MYSQL);
        target.setDriverClassName(MYSQL.getDriveClassName()); target.setPoolSize(1);
        target.setUrl(url("mysql")); target.setUsername(user("mysql")); target.setPassword(password("mysql"));
        target.setTargetSchema("dbswitch_test"); target.setWriterEngineInsert(true); target.setTargetDrop(false);
        return new MigrationService(request, ignored -> { }, null, runtime).run();
    }

    private javax.sql.DataSource source(String name) {
        return new org.springframework.jdbc.datasource.DriverManagerDataSource(url(name), user(name), password(name));
    }

    private List<String> columns(String name, String catalog, String schemaName, String table) throws SQLException {
        var result = new ArrayList<String>();
        String sql = "SELECT a.attname, " + catalog + ".format_type(a.atttypid,a.atttypmod), a.attnotnull FROM " + catalog + ".pg_attribute a"
                + " JOIN " + catalog + ".pg_class t ON t.oid=a.attrelid JOIN " + catalog + ".pg_namespace n ON n.oid=t.relnamespace"
                + " WHERE n.nspname=? AND t.relname=? AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum";
        try (var c = open(name); var s = c.prepareStatement(sql)) {
            s.setString(1, schemaName); s.setString(2, table);
            try (var r = s.executeQuery()) { while (r.next()) result.add(r.getString(1) + "|" + r.getString(2) + "|" + r.getBoolean(3)); }
        }
        return result;
    }

    private List<String> primaryKey(String name, String catalog, String schemaName, String table) throws SQLException {
        var result = new ArrayList<String>();
        String sql = "SELECT a.attname FROM " + catalog + ".pg_constraint p JOIN " + catalog + ".pg_class t ON t.oid=p.conrelid"
                + " JOIN " + catalog + ".pg_namespace n ON n.oid=t.relnamespace CROSS JOIN LATERAL unnest(p.conkey) WITH ORDINALITY k(attnum, position)"
                + " JOIN " + catalog + ".pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum"
                + " WHERE n.nspname=? AND t.relname=? AND p.contype='p' ORDER BY k.position";
        try (var c = open(name); var s = c.prepareStatement(sql)) {
            s.setString(1, schemaName); s.setString(2, table);
            try (var r = s.executeQuery()) { while (r.next()) result.add(r.getString(1)); }
        }
        return result;
    }

    private List<String> gbaseColumns(String db, String table) throws SQLException {
        var result = new ArrayList<String>();
        try (var c = open("gbase"); var s = c.prepareStatement("SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
            s.setString(1, db); s.setString(2, table);
            try (var r = s.executeQuery()) {
                while (r.next()) result.add(r.getString(1) + "|" + r.getString(2) + "|" + r.getBoolean(3));
            }
        }
        return result;
    }

    private List<String> mysqlColumns(String db, String table) throws SQLException {
        var result = new ArrayList<String>();
        try (var c = open("mysql"); var s = c.prepareStatement("SELECT COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
            s.setString(1, db); s.setString(2, table);
            try (var r = s.executeQuery()) { while (r.next()) result.add(r.getString(1) + "|" + r.getString(2)); }
        }
        return result;
    }

    private boolean tableExists(String name, String schemaName, String table) throws SQLException {
        try (var c = open(name); var s = c.prepareStatement("SELECT count(*) FROM pg_catalog.pg_class t JOIN pg_catalog.pg_namespace n"
                + " ON n.oid=t.relnamespace WHERE n.nspname=? AND t.relname=?")) {
            s.setString(1, schemaName); s.setString(2, table);
            try (var r = s.executeQuery()) { assertTrue(r.next()); return r.getLong(1) > 0; }
        }
    }

    private List<List<Object>> rows(String name, String sql) throws SQLException {
        var result = new ArrayList<List<Object>>();
        try (var c = open(name); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            int columns = r.getMetaData().getColumnCount();
            while (r.next()) {
                var row = new ArrayList<Object>();
                for (int i = 1; i <= columns; i++) {
                    Object value = r.getObject(i);
                    if (value instanceof Number number && !(value instanceof BigDecimal) && !(value instanceof Double) && !(value instanceof Float))
                        value = new BigDecimal(number.toString());
                    row.add(value);
                }
                result.add(row);
            }
        }
        return result;
    }

    private static void assertRows(List<List<Object>> source, List<List<Object>> target) {
        assertEquals(source.size(), target.size(), "row counts");
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (left == null || right == null) { assertEquals(left, right, "row " + r + " column " + c); continue; }
                if (left instanceof byte[] a) { assertArrayEquals(a, (byte[]) right, "row " + r + " column " + c); continue; }
                if (left instanceof BigDecimal a && right instanceof BigDecimal b) { assertEquals(0, a.compareTo(b), "row " + r + " column " + c); continue; }
                assertEquals(left.toString(), right.toString(), "row " + r + " column " + c);
            }
    }

    private static ColumnDescription pgCollated(String name, String type) {
        var column = LogicalTypeMappingTest.pg(name, type, MysqlFamilyExtendedFixtures.pgJdbc(type));
        return MysqlFamilyExtendedFixtures.withFacts(column, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                f.provenance(), f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(),
                f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), MetadataValue.value("pg_catalog.\"C\""), f.sequence()));
    }

    private static void assertSuccess(DbSwitchResult result) {
        assertEquals(1, result.getTableResultList().size());
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
    }

    private void quietly(String name, String sql) { try { sql(name, sql); } catch (Exception ignored) { } }
    private static void sql(String name, String sql) throws SQLException {
        try (var c = open(name); var s = c.createStatement()) { s.setQueryTimeout(60); s.execute(sql); }
    }
    private static Connection open(String name) throws SQLException { return DriverManager.getConnection(url(name), user(name), password(name)); }
    private static String url(String name) { return System.getProperty("datahub.test." + (name.equals("gbase") ? "gbase8a" : name) + ".url"); }
    private static String user(String name) {
        return System.getProperty("datahub.test." + name + ".user",
                name.equals("postgres") ? "dataforge_test" : "root");
    }
    private static String password(String name) {
        return name.equals("gbase") ? System.getenv("DATAHUB_GBASE8A_TEST_PASSWORD")
                : System.getProperty("datahub.test." + name + ".password", name.equals("mysql") ? "root" : "");
    }
}
