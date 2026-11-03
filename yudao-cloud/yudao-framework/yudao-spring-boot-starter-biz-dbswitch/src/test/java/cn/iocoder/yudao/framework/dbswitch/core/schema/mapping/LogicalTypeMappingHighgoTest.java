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
 * HighGo 6.0.4.2 (HgdbJdbc 6.2.4, server_version 12.7) logical type mapping: golden table plus real HighGo↔PostgreSQL 16
 * round trips. Every HighGo fact was probed on the real server; none is copied from the PostgreSQL mapper.
 */
@Timeout(180)
class LogicalTypeMappingHighgoTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();
    private static final boolean ZONE_GAPS = !java.time.ZoneId.systemDefault().getRules().isFixedOffset();
    private final String schema = "pfamh_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    private final List<Runnable> cleanup = new ArrayList<>();
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        runtime = DbSwitchRuntime.standalone();
        if (System.getProperty("datahub.test.highgo.url") != null) {
            sql("highgo", "CREATE SCHEMA " + schema);
            cleanup.add(() -> quietly("highgo", "DROP SCHEMA IF EXISTS " + schema + " CASCADE"));
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

    @Test void registryCarriesTheHighgoFlavorMapper() {
        var mapper = REGISTRY.typeMapper(HIGHGO).orElseThrow();
        assertInstanceOf(PgFamilyLogicalTypeMapper.class, mapper);
        assertEquals(PgFamilyLogicalTypeMapper.Flavor.HIGHGO, ((PgFamilyLogicalTypeMapper) mapper).flavor());
        assertEquals(HIGHGO, mapper.product());
        assertTrue(MAPPINGS.supports(HIGHGO, POSTGRESQL));
        assertFalse(MAPPINGS.supports(HIGHGO, HIGHGO));
    }

    // ---------------------------------------------------------------- golden: HighGo source -> PostgreSQL

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
    void highgoSourceToPostgresGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                PgFamilyMappingFixtures.family(HIGHGO, "v", nativeType), false)));
    }

    @Test void highgoTimestampAndDriverFactsAreEnforced() {
        var local = MAPPINGS.map(POSTGRESQL, PgFamilyMappingFixtures.family(HIGHGO, "v", "timestamp(6) without time zone"), false);
        assertEquals("timestamp(6) without time zone", local.targetNativeType());
        assertEquals(ZONE_GAPS ? Set.of("TIMESTAMP_JVM_ZONE_DST_SHIFT") : Set.of(), local.lossCodes());
        // The HighGo driver is the only family member reporting text as LONGVARCHAR; a VARCHAR report is inconsistent.
        var longText = PgFamilyMappingFixtures.family(HIGHGO, "v", "text");
        assertEquals(TypeFidelity.EXACT, MAPPINGS.map(POSTGRESQL, longText, false).fidelity());
        var shortText = PgFamilyMappingFixtures.family(HIGHGO, "v", "text"); shortText.setFieldType(Types.VARCHAR);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, shortText, false)));
        // PostgreSQL provenance is not HighGo native provenance.
        var foreign = PgFamilyMappingFixtures.family(HIGHGO, "v", "integer");
        PgFamilyMappingFixtures.withFacts(foreign, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                new cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance(HIGHGO, "PostgreSQL", "16.13", "NATIVE_CATALOG_V1", java.time.Instant.EPOCH),
                f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(),
                f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, foreign, false)));
        // serial defaults and identity columns have no verified cross-product mapping.
        assertEquals("UNSUPPORTED|-|DEFAULT_UNMAPPED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                PgFamilyMappingFixtures.withFacts(PgFamilyMappingFixtures.family(HIGHGO, "v", "integer"),
                        f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(f.provenance(), f.nativeType(),
                                MetadataValue.value("nextval('s'::regclass)"), f.nullable(), f.numericPrecision(), f.numericScale(),
                                f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence())), false)));
    }

    @Test void highgoCollationsAndCharacterKeys() {
        assertEquals("EXACT|text COLLATE \"C\"|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                PgFamilyMappingFixtures.text(HIGHGO, "v", "text", "pg_catalog.\"C\""), false)));
        assertEquals("UNSUPPORTED|-|COLLATION_UNVERIFIED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                PgFamilyMappingFixtures.text(HIGHGO, "v", "text", "public.\"en_US\""), false)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                PgFamilyMappingFixtures.text(HIGHGO, "k", "character varying(20)", null), true)));
        assertEquals("EXACT|bytea|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                PgFamilyMappingFixtures.family(HIGHGO, "k", "bytea"), true)));
    }

    // ---------------------------------------------------------------- golden: PostgreSQL source -> HighGo

    @ParameterizedTest(name = "{0} -> HIGHGO")
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
    void postgresSourceToHighgoGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(HIGHGO,
                PgFamilyMappingFixtures.family(POSTGRESQL, "v", nativeType), false)));
    }

    @Test void highgoTargetLimits() {
        var mapper = new PgFamilyLogicalTypeMapper(PgFamilyLogicalTypeMapper.Flavor.HIGHGO);
        // The HighGo driver rejects the Types.OTHER String binding for json/jsonb (verified 22007): no JSON target.
        assertEquals(TypeFidelity.UNSUPPORTED, mapper.toNative(new LogicalColumn("v",
                new LogicalType.JsonType(LogicalType.JsonRepresentation.TEXT, LogicalType.JsonNumbers.ARBITRARY_DECIMAL),
                true, false, MYSQL, "json")).fidelity());
        // Verified: HighGo rejects negative-scale numeric at DDL, unlike PostgreSQL 16.
        assertEquals("UNSUPPORTED", String.valueOf(mapper.toNative(new LogicalColumn("v",
                new LogicalType.DecimalType(10, -2, false, true), true, false, POSTGRESQL, "numeric(10,-2)")).fidelity()));
        assertEquals("UNSUPPORTED", String.valueOf(mapper.toNative(new LogicalColumn("v",
                new LogicalType.DecimalType(1001, 0, false, true), true, false, POSTGRESQL, "numeric(1001,0)")).fidelity()));
    }

    // ---------------------------------------------------------------- real HighGo <-> PostgreSQL 16 round trips

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.highgo.url", matches = "jdbc:highgo://127[.]0[.]0[.]1:[0-9]+/highgo")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_HIGHGO_TEST_PASSWORD", matches = ".+")
    void highgoSourceCreatesExactPostgresColumnsAndCopiesEveryValue() throws Exception {
        sql("highgo", "CREATE TABLE " + schema + ".source(k_big bigint NOT NULL, k_small smallint NOT NULL, v_int integer,"
                + " v_dec numeric(38,18), v_bool boolean, v_name varchar(40), v_text text, v_code char(5), v_bytes bytea,"
                + " v_date date, v_tstz timestamptz, v_uuid uuid, PRIMARY KEY(k_big, k_small))");
        sql("highgo", "INSERT INTO " + schema + ".source VALUES (-9223372036854775808, -32768, -2147483648, -1.5, false,"
                + " 'a  ', E'line1\\n\\t中😀', 'ab', '\\xdeadbeef', '0001-01-01', '1919-04-13 00:30:00.123456+05:30',"
                + " 'A0EEBC99-9C0B-4EF8-BB6D-6BB9BD380A11')");
        sql("highgo", "INSERT INTO " + schema + ".source VALUES (9223372036854775807, 32767, 2147483647,"
                + " 99999999999999999999.999999999999999999, true, '😀zé中', '', 'abcde', '\\x', '9999-12-31',"
                + " '9999-12-31 23:59:59.999999-08:00', 'ffffffff-ffff-ffff-ffff-ffffffffffff')");
        sql("highgo", "INSERT INTO " + schema + ".source(k_big, k_small) VALUES (0, 0)");
        assertSuccess(run(HIGHGO, "highgo", POSTGRESQL, "postgres", true, false));
        assertEquals(List.of("k_big|bigint|true", "k_small|smallint|true", "v_int|integer|false", "v_dec|numeric(38,18)|false",
                "v_bool|boolean|false", "v_name|character varying(40)|false", "v_text|text|false", "v_code|character(5)|false",
                "v_bytes|bytea|false", "v_date|date|false", "v_tstz|timestamp(6) with time zone|false", "v_uuid|uuid|false"),
                columns("postgres", "pg_catalog", schema, "copied"));
        assertEquals(List.of("k_big", "k_small"), primaryKey("postgres", "pg_catalog", schema, "copied"));
        String select = "SELECT k_big, k_small, v_int, v_dec, v_bool, v_name, v_text, v_code, v_bytes, v_date::text,"
                + " to_char(v_tstz AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US'), v_uuid::text FROM " + schema + ".{t} ORDER BY k_big, k_small";
        var source = rows("highgo", select.replace("{t}", "source"));
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
    @EnabledIfSystemProperty(named = "datahub.test.highgo.url", matches = "jdbc:highgo://127[.]0[.]0[.]1:[0-9]+/highgo")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_HIGHGO_TEST_PASSWORD", matches = ".+")
    void postgresSourceCreatesExactHighgoColumnsAndCopiesEveryValue() throws Exception {
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
        assertSuccess(run(POSTGRESQL, "postgres", HIGHGO, "highgo", true, false));
        assertEquals(List.of("k_big|bigint|true", "k_small|smallint|true", "v_int|integer|false", "v_dec|numeric(38,18)|false",
                "v_bool|boolean|false", "v_name|character varying(40)|false", "v_text|text|false", "v_code|character(5)|false",
                "v_bytes|bytea|false", "v_date|date|false", "v_tstz|timestamp(6) with time zone|false", "v_uuid|uuid|false"),
                columns("highgo", "pg_catalog", schema, "copied"));
        assertEquals(List.of("k_big", "k_small"), primaryKey("highgo", "pg_catalog", schema, "copied"));
        String select = "SELECT k_big, k_small, v_int, v_dec, v_bool, v_name, v_text, v_code, v_bytes, v_date::text,"
                + " to_char(v_tstz AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US'), v_uuid::text FROM " + schema + ".{t} ORDER BY k_big, k_small";
        var source = rows("postgres", select.replace("{t}", "source"));
        var target = rows("highgo", select.replace("{t}", "copied"));
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
    @EnabledIfSystemProperty(named = "datahub.test.highgo.url", matches = "jdbc:highgo://127[.]0[.]0[.]1:[0-9]+/highgo")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_HIGHGO_TEST_PASSWORD", matches = ".+")
    void highgoRejectedColumnsBlockBeforeAnyTargetObjectExists(String type, String fidelity, String reason) throws Exception {
        sql("highgo", "CREATE TABLE " + schema + ".source(id int NOT NULL, label smallint, value " + type + ", PRIMARY KEY(id))");
        var result = run(HIGHGO, "highgo", POSTGRESQL, "postgres", true, false);
        assertFalse(result.getIfAllSuccess().get(), "a rejected mapping must fail the table");
        assertFalse(tableExists("postgres", schema, "copied"), "no target object may exist after a rejected plan");
        var columns = runtime.metadata(source("highgo"), HIGHGO).queryTableColumnMetaWithNativeEvidence(schema, "source");
        var failure = assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                runtime.products().metadata(POSTGRESQL), columns, List.of("id"), schema, "copied", null, false, Map.of()));
        String message = failure.getMessage();
        assertTrue(message.contains("value [HIGHGO") && message.contains("] " + fidelity + " ") && message.contains(reason), message);
        assertFalse(message.contains("label ["), message);
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.highgo.url", matches = "jdbc:highgo://127[.]0[.]0[.]1:[0-9]+/highgo")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_HIGHGO_TEST_PASSWORD", matches = ".+")
    void highgoTextKeyIsRejectedCrossProduct() throws Exception {
        sql("highgo", "CREATE TABLE " + schema + ".source(id varchar(20) NOT NULL, n int, PRIMARY KEY(id))");
        var columns = runtime.metadata(source("highgo"), HIGHGO).queryTableColumnMetaWithNativeEvidence(schema, "source");
        var failure = assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                runtime.products().metadata(POSTGRESQL), columns, List.of("id"), schema, "copied", null, false, Map.of()));
        assertTrue(failure.getMessage().contains("TEXT_KEY_EQUALITY_UNPROVEN"), failure.getMessage());
        assertFalse(tableExists("postgres", schema, "copied"));
    }

    // ---------------------------------------------------------------- helpers

    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, ProductTypeEnum targetProduct,
                               String targetName, boolean insert, boolean drop) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(sourceProduct); source.setDriverClassName(sourceProduct.getDriveClassName());
        source.setUrl(url(sourceName)); source.setUsername(user(sourceName)); source.setPassword(password(sourceName)); source.setPoolSize(1);
        source.setSourceSchema(schema); source.setSourceIncludes("source"); source.setFetchSize(2);
        source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copied"))); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(targetProduct);
        target.setDriverClassName(targetProduct.getDriveClassName()); target.setPoolSize(1);
        target.setUrl(url(targetName)); target.setUsername(user(targetName)); target.setPassword(password(targetName));
        target.setTargetSchema(schema); target.setWriterEngineInsert(insert); target.setTargetDrop(drop);
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
        return System.getProperty("datahub.test." + name + ".user", name.equals("highgo") ? "highgo" : "dataforge_test");
    }
    private static String password(String name) {
        return name.equals("highgo") ? System.getenv("DATAHUB_HIGHGO_TEST_PASSWORD") : System.getProperty("datahub.test." + name + ".password", "");
    }
}
