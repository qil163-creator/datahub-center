package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.product.postgresql.PgFamilyLogicalTypeMapper;
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
 * Greenplum 7.1.0 (pgjdbc 42.7.13, server_version 12.12, hash distribution) logical type mapping: golden table plus
 * real Greenplum↔PostgreSQL 16 round trips. Facts were probed on the real server (distribution defaults, typed
 * json/jsonb binding, CHECK enforcement); none is copied from the PostgreSQL mapper.
 */
@Timeout(180)
class LogicalTypeMappingGreenplumTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();
    private static final boolean ZONE_GAPS = !java.time.ZoneId.systemDefault().getRules().isFixedOffset();
    private final String schema = "pfamg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    private final List<Runnable> cleanup = new ArrayList<>();
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        runtime = DbSwitchRuntime.standalone();
        if (System.getProperty("datahub.test.greenplum.url") != null) {
            sql("greenplum", "CREATE SCHEMA " + schema);
            cleanup.add(() -> quietly("greenplum", "DROP SCHEMA IF EXISTS " + schema + " CASCADE"));
        }
        if (System.getProperty("datahub.test.postgres.url") != null) {
            sql("postgres", "CREATE SCHEMA " + schema);
            cleanup.add(() -> quietly("postgres", "DROP SCHEMA IF EXISTS " + schema + " CASCADE"));
        }
    }

    @AfterEach void teardown() {
        try { Collections.reverse(cleanup); cleanup.forEach(Runnable::run); }
        finally { if (runtime != null) runtime.close(); }
    }

    // ---------------------------------------------------------------- registry

    @Test void registryCarriesTheGreenplumFlavorMapper() {
        var mapper = REGISTRY.typeMapper(GREENPLUM).orElseThrow();
        assertInstanceOf(PgFamilyLogicalTypeMapper.class, mapper);
        assertEquals(PgFamilyLogicalTypeMapper.Flavor.GREENPLUM, ((PgFamilyLogicalTypeMapper) mapper).flavor());
        assertEquals(GREENPLUM, mapper.product());
        assertTrue(MAPPINGS.supports(GREENPLUM, POSTGRESQL));
        assertFalse(MAPPINGS.supports(GREENPLUM, GREENPLUM));
    }

    // ---------------------------------------------------------------- golden: Greenplum source -> PostgreSQL

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|smallint|", "integer; EXACT|integer|", "bigint; EXACT|bigint|",
            "numeric(38,18); EXACT|numeric(38,18)|", "numeric; EXACT|numeric|",
            "real; EXACT|real|", "double precision; EXACT|double precision|",
            "boolean; EXACT|boolean|",
            "character varying(40); EXACT|character varying(40)|",
            "text; EXACT|text|",
            "character(5); EXACT|character(5)|",
            "bytea; EXACT|bytea|", "date; EXACT|date|",
            "time(3) without time zone; LOSSY|time(3) without time zone|TIME_24_00_WRAPPED",
            "time(6) without time zone; LOSSY|time(6) without time zone|TIME_24_00_WRAPPED,TIME_FRACTION_TRUNCATED",
            "time with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "timestamp(6) with time zone; EXACT|timestamp(6) with time zone|",
            "interval; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "uuid; EXACT|uuid|", "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED", "jsonb; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "integer[]; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED", "money; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED",
            "bit(3); UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void greenplumSourceToPostgresGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                PgFamilyMappingFixtures.family(GREENPLUM, "v", nativeType), false)));
    }

    @Test void greenplumTimestampAndDriverFactsAreEnforced() {
        var local = MAPPINGS.map(POSTGRESQL, PgFamilyMappingFixtures.family(GREENPLUM, "v", "timestamp(6) without time zone"), false);
        assertEquals("timestamp(6) without time zone", local.targetNativeType());
        assertEquals(ZONE_GAPS ? Set.of("TIMESTAMP_JVM_ZONE_DST_SHIFT") : Set.of(), local.lossCodes());
        // Greenplum text is VARCHAR through pgjdbc; a LONGVARCHAR report is inconsistent.
        var shortText = PgFamilyMappingFixtures.family(GREENPLUM, "v", "text"); shortText.setFieldType(Types.LONGVARCHAR);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, shortText, false)));
        // PostgreSQL provenance is not Greenplum native provenance.
        var foreign = PgFamilyMappingFixtures.family(GREENPLUM, "v", "integer");
        PgFamilyMappingFixtures.withFacts(foreign, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                new cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance(GREENPLUM, "PostgreSQL", "16.13", "NATIVE_CATALOG_V1", java.time.Instant.EPOCH),
                f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(),
                f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, foreign, false)));
    }

    @Test void greenplumCollationsAndCharacterKeys() {
        assertEquals("EXACT|text COLLATE \"C\"|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                PgFamilyMappingFixtures.text(GREENPLUM, "v", "text", "pg_catalog.\"C\""), false)));
        assertEquals("UNSUPPORTED|-|COLLATION_UNVERIFIED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                PgFamilyMappingFixtures.text(GREENPLUM, "v", "text", "public.\"en_US\""), false)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                PgFamilyMappingFixtures.text(GREENPLUM, "k", "character varying(20)", null), true)));
    }

    // ---------------------------------------------------------------- golden: PostgreSQL source -> Greenplum

    @ParameterizedTest(name = "{0} -> GREENPLUM")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|smallint|", "integer; EXACT|integer|", "bigint; EXACT|bigint|",
            "numeric(38,18); EXACT|numeric(38,18)|", "numeric; EXACT|numeric|",
            "real; EXACT|real|", "double precision; EXACT|double precision|",
            "boolean; EXACT|boolean|",
            "character varying(40); EXACT|character varying(40)|",
            "text; EXACT|text|",
            "character(5); EXACT|character(5)|",
            "bytea; EXACT|bytea|", "date; EXACT|date|",
            "time(3) without time zone; LOSSY|time(3) without time zone|TIME_24_00_WRAPPED",
            "timestamp(6) with time zone; EXACT|timestamp(6) with time zone|",
            "uuid; EXACT|uuid|", "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "interval; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED", "integer[]; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED"})
    void postgresSourceToGreenplumGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(GREENPLUM,
                PgFamilyMappingFixtures.family(POSTGRESQL, "v", nativeType), false)));
    }

    @Test void greenplumTargetLimits() {
        var mapper = new PgFamilyLogicalTypeMapper(PgFamilyLogicalTypeMapper.Flavor.GREENPLUM);
        // pgjdbc binds cross-driver JSON strings through the target's Types.OTHER metadata type (verified on the
        // real server), so json/jsonb targets are exact, exactly like PostgreSQL 16.
        assertEquals(TypeFidelity.EXACT, mapper.toNative(new LogicalColumn("v",
                new LogicalType.JsonType(LogicalType.JsonRepresentation.NORMALIZED, LogicalType.JsonNumbers.ARBITRARY_DECIMAL),
                true, false, MYSQL, "json")).fidelity());
        assertEquals(TypeFidelity.EXACT, mapper.toNative(new LogicalColumn("v",
                new LogicalType.JsonType(LogicalType.JsonRepresentation.TEXT, LogicalType.JsonNumbers.BINARY64),
                true, false, MYSQL, "json")).fidelity());
        // Verified: Greenplum rejects negative-scale numeric at DDL.
        assertEquals(TypeFidelity.UNSUPPORTED, mapper.toNative(new LogicalColumn("v",
                new LogicalType.DecimalType(10, -2, false, true), true, false, POSTGRESQL, "numeric(10,-2)")).fidelity());
    }

    // ---------------------------------------------------------------- real Greenplum <-> PostgreSQL 16 round trips

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.greenplum.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/(demo|postgres)([?]sslmode=disable)?")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_GREENPLUM_TEST_PASSWORD", matches = ".+")
    void greenplumSourceCreatesExactPostgresColumnsAndCopiesEveryValue() throws Exception {
        sql("greenplum", "CREATE TABLE " + schema + ".source(k_big bigint NOT NULL, k_small smallint NOT NULL, v_int integer,"
                + " v_dec numeric(38,18), v_bool boolean, v_name varchar(40), v_text text, v_code char(5), v_bytes bytea,"
                + " v_date date, v_tstz timestamptz, v_uuid uuid, PRIMARY KEY(k_big, k_small)) DISTRIBUTED BY (k_big)");
        sql("greenplum", "INSERT INTO " + schema + ".source VALUES (-9223372036854775808, -32768, -2147483648, -1.5, false,"
                + " 'a  ', E'line1\\n\\t中😀', 'ab', '\\xdeadbeef', '0001-01-01', '1919-04-13 00:30:00.123456+05:30',"
                + " 'A0EEBC99-9C0B-4EF8-BB6D-6BB9BD380A11')");
        sql("greenplum", "INSERT INTO " + schema + ".source VALUES (9223372036854775807, 32767, 2147483647,"
                + " 99999999999999999999.999999999999999999, true, '😀zé中', '', 'abcde', '\\x', '9999-12-31',"
                + " '9999-12-31 23:59:59.999999-08:00', 'ffffffff-ffff-ffff-ffff-ffffffffffff')");
        sql("greenplum", "INSERT INTO " + schema + ".source(k_big, k_small) VALUES (0, 0)");
        assertSuccess(run(GREENPLUM, "greenplum", POSTGRESQL, "postgres", true, false));
        assertEquals(List.of("k_big|bigint|true", "k_small|smallint|true", "v_int|integer|false", "v_dec|numeric(38,18)|false",
                "v_bool|boolean|false", "v_name|character varying(40)|false", "v_text|text|false", "v_code|character(5)|false",
                "v_bytes|bytea|false", "v_date|date|false", "v_tstz|timestamp(6) with time zone|false", "v_uuid|uuid|false"),
                columns("postgres", schema, "copied"));
        assertEquals(List.of("k_big", "k_small"), primaryKey("postgres", schema, "copied"));
        String select = "SELECT k_big, k_small, v_int, v_dec, v_bool, v_name, v_text, v_code, v_bytes, v_date::text,"
                + " to_char(v_tstz AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US'), v_uuid::text FROM " + schema + ".{t} ORDER BY k_big, k_small";
        var source = rows("greenplum", select.replace("{t}", "source"));
        var target = rows("postgres", select.replace("{t}", "copied"));
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (c == 7 && left != null) { left = left.toString().stripTrailing(); right = right.toString().stripTrailing(); }
                assertValue(left, right, "row " + r + " column " + c);
            }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.greenplum.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/(demo|postgres)([?]sslmode=disable)?")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_GREENPLUM_TEST_PASSWORD", matches = ".+")
    void postgresSourceCreatesExactGreenplumColumnsAndCopiesEveryValue() throws Exception {
        sql("postgres", "CREATE TABLE " + schema + ".source(k_big bigint NOT NULL, k_small smallint NOT NULL, v_int integer,"
                + " v_dec numeric(38,18), v_bool boolean, v_name varchar(40), v_text text, v_code char(5), v_bytes bytea,"
                + " v_date date, v_tstz timestamptz, v_uuid uuid, PRIMARY KEY(k_big, k_small))");
        sql("postgres", "INSERT INTO " + schema + ".source VALUES (-9223372036854775808, -32768, -2147483648, -1.5, false,"
                + " 'a  ', E'line1\\n\\t中😀', 'ab', '\\xdeadbeef', '0001-01-01', '1919-04-13 00:30:00.123456+05:30',"
                + " 'A0EEBC99-9C0B-4EF8-BB6D-6BB9BD380A11')");
        sql("postgres", "INSERT INTO " + schema + ".source VALUES (9223372036854775807, 32767, 2147483647,"
                + " 99999999999999999999.999999999999999999, true, '😀zé中', '', 'abcde', '\\x', '9999-12-31',"
                + " '9999-12-31 23:59:59.999999-08:00', 'ffffffff-ffff-ffff-ffff-ffffffffffff')");
        sql("postgres", "INSERT INTO " + schema + ".source(k_big, k_small) VALUES (0, 0)");
        assertSuccess(run(POSTGRESQL, "postgres", GREENPLUM, "greenplum", true, false));
        assertEquals(List.of("k_big|bigint|true", "k_small|smallint|true", "v_int|integer|false", "v_dec|numeric(38,18)|false",
                "v_bool|boolean|false", "v_name|character varying(40)|false", "v_text|text|false", "v_code|character(5)|false",
                "v_bytes|bytea|false", "v_date|date|false", "v_tstz|timestamp(6) with time zone|false", "v_uuid|uuid|false"),
                columns("greenplum", schema, "copied"));
        assertEquals(List.of("k_big", "k_small"), primaryKey("greenplum", schema, "copied"));
        String select = "SELECT k_big, k_small, v_int, v_dec, v_bool, v_name, v_text, v_code, v_bytes, v_date::text,"
                + " to_char(v_tstz AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US'), v_uuid::text FROM " + schema + ".{t} ORDER BY k_big, k_small";
        var source = rows("postgres", select.replace("{t}", "source"));
        var target = rows("greenplum", select.replace("{t}", "copied"));
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (c == 7 && left != null) { left = left.toString().stripTrailing(); right = right.toString().stripTrailing(); }
                assertValue(left, right, "row " + r + " column " + c);
            }
    }

    @ParameterizedTest(name = "{0} blocks the whole table")
    @CsvSource(delimiter = ';', value = {
            "jsonb; UNSUPPORTED; VALUE_CODEC_UNSUPPORTED",
            "time(3) without time zone; LOSSY; TIME_24_00_WRAPPED"})
    @EnabledIfSystemProperty(named = "datahub.test.greenplum.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/(demo|postgres)([?]sslmode=disable)?")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_GREENPLUM_TEST_PASSWORD", matches = ".+")
    void greenplumRejectedColumnsBlockBeforeAnyTargetObjectExists(String type, String fidelity, String reason) throws Exception {
        sql("greenplum", "CREATE TABLE " + schema + ".source(id int NOT NULL, label smallint, value " + type
                + ", PRIMARY KEY(id)) DISTRIBUTED BY (id)");
        var result = run(GREENPLUM, "greenplum", POSTGRESQL, "postgres", true, false);
        assertFalse(result.getIfAllSuccess().get(), "a rejected mapping must fail the table");
        assertFalse(tableExists("postgres", schema, "copied"), "no target object may exist after a rejected plan");
        var columns = runtime.metadata(source("greenplum"), GREENPLUM).queryTableColumnMetaWithNativeEvidence(schema, "source");
        var failure = assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                runtime.products().metadata(POSTGRESQL), columns, List.of("id"), schema, "copied", null, false, Map.of()));
        String message = failure.getMessage();
        assertTrue(message.contains("value [GREENPLUM") && message.contains("] " + fidelity + " ") && message.contains(reason), message);
        assertFalse(message.contains("label ["), message);
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.greenplum.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/(demo|postgres)([?]sslmode=disable)?")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_GREENPLUM_TEST_PASSWORD", matches = ".+")
    void greenplumTextKeyIsRejectedCrossProduct() throws Exception {
        sql("greenplum", "CREATE TABLE " + schema + ".source(id varchar(20) NOT NULL, n int, PRIMARY KEY(id)) DISTRIBUTED BY (id)");
        var columns = runtime.metadata(source("greenplum"), GREENPLUM).queryTableColumnMetaWithNativeEvidence(schema, "source");
        var failure = assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                runtime.products().metadata(POSTGRESQL), columns, List.of("id"), schema, "copied", null, false, Map.of()));
        assertTrue(failure.getMessage().contains("TEXT_KEY_EQUALITY_UNPROVEN"), failure.getMessage());
        assertFalse(tableExists("postgres", schema, "copied"));
    }

    // ---------------------------------------------------------------- helpers

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.greenplum.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/(demo|postgres)([?]sslmode=disable)?")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_GREENPLUM_TEST_PASSWORD", matches = ".+")
    void generatedDistributionPreservesQuotedCompositePhysicalKeys() throws Exception {
        sql("postgres", "CREATE TABLE " + schema + ".source(\"Id\" bigint NOT NULL, \"SecondId\" integer NOT NULL,"
                + " note text, PRIMARY KEY(\"Id\", \"SecondId\"))");
        sql("postgres", "INSERT INTO " + schema + ".source VALUES (1,2,'DISTRIBUTED BY (not a key)')");
        assertSuccess(run(POSTGRESQL, "postgres", GREENPLUM, "greenplum", true, false));
        assertEquals(List.of("Id", "SecondId"), primaryKey("greenplum", schema, "copied"));
        assertEquals(rows("postgres", "SELECT * FROM " + schema + ".source"),
                rows("greenplum", "SELECT * FROM " + schema + ".copied"));
        try (var c = open("greenplum"); var s = c.prepareStatement("SELECT a.attname FROM gp_distribution_policy p"
                + " JOIN pg_class t ON t.oid=p.localoid JOIN pg_namespace n ON n.oid=t.relnamespace"
                + " CROSS JOIN LATERAL unnest(p.distkey) WITH ORDINALITY k(attnum,position)"
                + " JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum"
                + " WHERE n.nspname=? AND t.relname='copied' ORDER BY k.position")) {
            s.setString(1, schema);
            try (var r = s.executeQuery()) {
                assertTrue(r.next()); assertEquals("Id", r.getString(1));
                assertTrue(r.next()); assertEquals("SecondId", r.getString(1)); assertFalse(r.next());
            }
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @EnabledIfSystemProperty(named = "datahub.test.greenplum.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/(demo|postgres)([?]sslmode=disable)?")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_GREENPLUM_TEST_PASSWORD", matches = ".+")
    void explicitDistributionUsesPhysicalColumnOrPrimaryKeySubset(boolean primary) throws Exception {
        sql("postgres", "CREATE TABLE " + schema + ".source(\"Id\" bigint NOT NULL, \"Part\" integer NOT NULL, note text"
                + (primary ? ", PRIMARY KEY(\"Id\",\"Part\")" : "") + ")");
        sql("postgres", "INSERT INTO " + schema + ".source VALUES(1,2,'ok'),(2,2,'same distribution')");
        assertSuccess(run(POSTGRESQL, "postgres", GREENPLUM, "greenplum", true, false, List.of("Part")));
        assertEquals(List.of("Part"), distribution());
        assertEquals(rows("postgres", "SELECT * FROM " + schema + ".source ORDER BY \"Id\""),
                rows("greenplum", "SELECT * FROM " + schema + ".copied ORDER BY \"Id\""));
        assertEquals(primary ? List.of("Id", "Part") : List.of(), primaryKey("greenplum", schema, "copied"));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "note"})
    @EnabledIfSystemProperty(named = "datahub.test.greenplum.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/(demo|postgres)([?]sslmode=disable)?")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_GREENPLUM_TEST_PASSWORD", matches = ".+")
    void invalidDistributionRejectsBeforeReplacingSentinel(String key) throws Exception {
        sql("postgres", "CREATE TABLE " + schema + ".source(id bigint PRIMARY KEY, note text)");
        sql("greenplum", "CREATE TABLE " + schema + ".copied(id bigint,note text) DISTRIBUTED BY(id)");
        sql("greenplum", "INSERT INTO " + schema + ".copied VALUES(42,'sentinel')");
        var result = run(POSTGRESQL, "postgres", GREENPLUM, "greenplum", true, true, List.of(key));
        assertFalse(result.getIfAllSuccess().get());
        assertEquals(List.of(List.of(new BigDecimal("42"), "sentinel")), rows("greenplum", "SELECT * FROM " + schema + ".copied"));
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.greenplum.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/(demo|postgres)([?]sslmode=disable)?")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_GREENPLUM_TEST_PASSWORD", matches = ".+")
    void nativeCloneAndExistingTargetCannotSilentlyIgnoreCustomDistribution() throws Exception {
        sql("greenplum", "CREATE TABLE " + schema + ".source(id bigint,part integer) DISTRIBUTED BY(id)");
        sql("greenplum", "CREATE TABLE " + schema + ".copied(id bigint,part integer) DISTRIBUTED BY(id)");
        sql("greenplum", "INSERT INTO " + schema + ".copied VALUES(42,7)");
        assertFalse(run(GREENPLUM, "greenplum", GREENPLUM, "greenplum", true, true, List.of("part")).getIfAllSuccess().get());
        sql("postgres", "CREATE TABLE " + schema + ".source(id bigint,part integer)");
        assertFalse(run(POSTGRESQL, "postgres", GREENPLUM, "greenplum", true, false, List.of("part")).getIfAllSuccess().get());
        assertEquals(List.of("id"), distribution());
        assertEquals(List.of(List.of(new BigDecimal("42"), new BigDecimal("7"))), rows("greenplum", "SELECT * FROM " + schema + ".copied"));
    }

    private List<String> distribution() throws Exception {
        var result = new ArrayList<String>();
        try (var connection = open("greenplum"); var statement = connection.prepareStatement("SELECT a.attname FROM gp_distribution_policy p"
                + " JOIN pg_class t ON t.oid=p.localoid JOIN pg_namespace n ON n.oid=t.relnamespace"
                + " CROSS JOIN LATERAL unnest(p.distkey) WITH ORDINALITY k(attnum,position)"
                + " JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum WHERE n.nspname=? AND t.relname='copied' ORDER BY k.position")) {
            statement.setString(1, schema);
            try (var rows = statement.executeQuery()) { while(rows.next()) result.add(rows.getString(1)); }
        }
        return result;
    }

    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, ProductTypeEnum targetProduct,
                               String targetName, boolean insert, boolean drop) throws Exception {
        return run(sourceProduct, sourceName, targetProduct, targetName, insert, drop, List.of());
    }
    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, ProductTypeEnum targetProduct,
                               String targetName, boolean insert, boolean drop, List<String> distribution) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(sourceProduct); source.setDriverClassName(sourceProduct.getDriveClassName());
        source.setUrl(url(sourceName)); source.setUsername(user(sourceName)); source.setPassword(password(sourceName)); source.setPoolSize(1);
        source.setSourceSchema(schema); source.setSourceIncludes("source"); source.setFetchSize(2);
        source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copied"))); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(targetProduct);
        target.setDriverClassName(targetProduct.getDriveClassName()); target.setPoolSize(1);
        target.setUrl(url(targetName)); target.setUsername(user(targetName)); target.setPassword(password(targetName));
        target.setTargetSchema(schema); target.setWriterEngineInsert(insert); target.setTargetDrop(drop);
        target.setDistributedKeys(distribution);
        return new MigrationService(request, ignored -> { }, null, runtime).run();
    }

    private javax.sql.DataSource source(String name) {
        return new org.springframework.jdbc.datasource.DriverManagerDataSource(url(name), user(name), password(name));
    }

    private List<String> columns(String name, String schemaName, String table) throws SQLException {
        var result = new ArrayList<String>();
        String sql = "SELECT a.attname, pg_catalog.format_type(a.atttypid,a.atttypmod), a.attnotnull FROM pg_catalog.pg_attribute a"
                + " JOIN pg_catalog.pg_class t ON t.oid=a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace"
                + " WHERE n.nspname=? AND t.relname=? AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum";
        try (var c = open(name); var s = c.prepareStatement(sql)) {
            s.setString(1, schemaName); s.setString(2, table);
            try (var r = s.executeQuery()) { while (r.next()) result.add(r.getString(1) + "|" + r.getString(2) + "|" + r.getBoolean(3)); }
        }
        return result;
    }

    private List<String> primaryKey(String name, String schemaName, String table) throws SQLException {
        var result = new ArrayList<String>();
        String sql = "SELECT a.attname FROM pg_catalog.pg_constraint p JOIN pg_catalog.pg_class t ON t.oid=p.conrelid"
                + " JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace CROSS JOIN LATERAL unnest(p.conkey) WITH ORDINALITY k(attnum, position)"
                + " JOIN pg_catalog.pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum"
                + " WHERE n.nspname=? AND t.relname=? AND p.contype='p' ORDER BY k.position";
        try (var c = open(name); var s = c.prepareStatement(sql)) {
            s.setString(1, schemaName); s.setString(2, table);
            try (var r = s.executeQuery()) { while (r.next()) result.add(r.getString(1)); }
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

    private static void assertValue(Object left, Object right, String label) {
        if (left == null || right == null) { assertEquals(left, right, label); return; }
        if (left instanceof byte[] a) { assertArrayEquals(a, (byte[]) right, label); return; }
        if (left instanceof BigDecimal a && right instanceof BigDecimal b) { assertEquals(0, a.compareTo(b), label + " " + a + " vs " + b); return; }
        assertEquals(left.toString(), right.toString(), label);
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
    private static String url(String name) { return System.getProperty("datahub.test." + name + ".url"); }
    private static String user(String name) {
        return System.getProperty("datahub.test." + name + ".user", name.equals("greenplum") ? "gpadmin" : "dataforge_test");
    }
    private static String password(String name) {
        return name.equals("greenplum") ? System.getenv("DATAHUB_GREENPLUM_TEST_PASSWORD") : System.getProperty("datahub.test." + name + ".password", "");
    }
}
