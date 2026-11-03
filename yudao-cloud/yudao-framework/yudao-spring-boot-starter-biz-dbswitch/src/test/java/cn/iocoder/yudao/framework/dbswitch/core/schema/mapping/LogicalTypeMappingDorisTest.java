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
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisLogicalTypeMapper;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Doris 4.0.8 (MySQL protocol 5.7.99, Connector/J 9.7.0) logical type mapping: golden tables plus real
 * Doris↔PostgreSQL 16 and Doris→MySQL 8.4 round trips. Every Doris fact was probed on the real server.
 */
@Timeout(300)
class LogicalTypeMappingDorisTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();
    private static final boolean ZONE_GAPS = !java.time.ZoneId.systemDefault().getRules().isFixedOffset();
    private final String dorisDb = "mfm_d_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final String pgSchema = "mfm_d_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final String mysqlTable = "mfm_d_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final List<Runnable> cleanup = new ArrayList<>();
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        // Stream Load for the production Doris writer is covered by the OLAP packages against a port-mapped cluster;
        // this round trip pins the JDBC INSERT write path (olapStreamLoadEnabled=false) for the type-mapping proof.
        runtime = new DbSwitchRuntime(REGISTRY, new cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions(
                65536, false, false, 20, 0, 0, 250, 0), null);
        if (System.getProperty("datahub.test.doris.url") != null) {
            sql("doris", "CREATE DATABASE " + dorisDb);
            cleanup.add(() -> quietly("doris", "DROP DATABASE " + dorisDb));
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

    @Test void registryCarriesTheDorisMapper() {
        var mapper = REGISTRY.typeMapper(DORIS).orElseThrow();
        assertInstanceOf(DorisLogicalTypeMapper.class, mapper);
        assertEquals(DORIS, mapper.product());
        assertTrue(MAPPINGS.supports(DORIS, POSTGRESQL));
        assertTrue(MAPPINGS.supports(DORIS, MYSQL));
        assertFalse(MAPPINGS.supports(DORIS, DORIS));
    }

    // ---------------------------------------------------------------- golden: Doris source -> PostgreSQL

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "tinyint(4); EXACT|smallint|; \"v\" BETWEEN -128 AND 127",
            "smallint(6); EXACT|smallint|; -",
            "int(11); EXACT|integer|; -",
            "bigint(20); EXACT|bigint|; -",
            "largeint; EXACT|numeric(39,0)|; \"v\" BETWEEN -170141183460469231731687303715884105728 AND 170141183460469231731687303715884105727",
            "tinyint(1); EXACT|boolean|; -",
            "decimalv3(38,9); EXACT|numeric(38,9)|; -",
            "float; LOSSY|real|FLOAT32_PRECISION_NOT_PRESERVED; -",
            "double; EXACT|double precision|; -",
            "char(5); UNSUPPORTED|-|TYPE_UNMAPPED; -",
            "varchar(20); EXACT|text COLLATE \"C\"|; \"v\" IS NULL OR (pg_catalog.current_setting('server_encoding') = 'UTF8' AND pg_catalog.octet_length(\"v\") <= 20)",
            "string; LOSSY|text COLLATE \"C\"|LENGTH_EXCEEDS_TARGET; \"v\" IS NULL OR (pg_catalog.current_setting('server_encoding') = 'UTF8' AND pg_catalog.octet_length(\"v\") <= 1073741823)",
            "json; EXACT|jsonb|; -",
            "date; LOSSY|date|ZERO_DATE_SILENTLY_CONVERTED; -",
            "datetime; LOSSY|timestamp(0) without time zone|ZERO_DATE_SILENTLY_CONVERTED; -",
            "datetime(6); LOSSY|timestamp(6) without time zone|ZERO_DATE_SILENTLY_CONVERTED; -",
            "datetimev2(6); LOSSY|timestamp(6) without time zone|ZERO_DATE_SILENTLY_CONVERTED; -",
            "array<int(11)>; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED; -",
            "bitmap; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED; -",
            "int unsigned; UNSUPPORTED|-|TYPE_UNMAPPED; -"})
    void dorisSourceToPostgresGolden(String nativeType, String expected, String check) {
        var mapping = MAPPINGS.map(POSTGRESQL, MysqlFamilyExtendedFixtures.family(DORIS, "v", nativeType), false);
        assertEquals(expected, PgFamilyMappingFixtures.outcome(mapping));
        if (mapping.definition() != null)
            assertEquals(check.equals("-") ? null : check, mapping.definition().contains(" CHECK (")
                    ? mapping.definition().substring(mapping.definition().indexOf(" CHECK (") + 8, mapping.definition().length() - 1) : null);
    }

    // ---------------------------------------------------------------- golden: PostgreSQL source -> Doris

    @ParameterizedTest(name = "{0} -> DORIS")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|smallint|",
            "integer; EXACT|int|",
            "bigint; EXACT|bigint|",
            "numeric(38,18); LOSSY|decimal(38,18)|NAN_NOT_REPRESENTABLE",
            "numeric(39,0); LOSSY|largeint|NAN_NOT_REPRESENTABLE,NUMERIC_RANGE_NOT_REPRESENTABLE",
            "numeric; UNSUPPORTED|-|UNBOUNDED_NUMERIC_UNMAPPED",
            "numeric(1001,0); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "real; LOSSY|float|FLOAT32_PRECISION_NOT_PRESERVED,NEGATIVE_ZERO_NOT_PRESERVED",
            "double precision; LOSSY|double|NEGATIVE_ZERO_NOT_PRESERVED",
            "boolean; EXACT|boolean|",
            "character varying(40); EXACT|varchar(160)|",
            "text; EXACT|string|",
            "bytea; UNSUPPORTED|-|BINARY_TYPE_UNAVAILABLE",
            "date; LOSSY|date|DATE_RANGE_NOT_REPRESENTABLE",
            "time(3) without time zone; UNSUPPORTED|-|TIME_24_00_WRAPPED,TIME_TYPE_UNAVAILABLE",
            "timestamp(6) without time zone; LOSSY|datetime(6)|DATE_RANGE_NOT_REPRESENTABLE",
            "timestamp(6) with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "uuid; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "xml; UNSUPPORTED|-|TYPE_UNMAPPED"})
    void postgresSourceToDorisGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(DORIS,
                LogicalTypeMappingTest.pg("v", nativeType, MysqlFamilyExtendedFixtures.pgJdbc(nativeType)), false)));
    }

    @Test void signed128DomainCannotFallThroughOtherProductsBigintDefaults() {
        var type = new LogicalType.Int128Type();
        var input = new LogicalColumn("v", type, true, false, DORIS, "largeint");
        for (var product : REGISTRY.products().stream().map(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductDescriptor::product).toList()) {
            REGISTRY.typeMapper(product).ifPresent(mapper -> {
                var mapping = mapper.toNative(input);
                if (product == POSTGRESQL) {
                    assertEquals(TypeFidelity.EXACT, mapping.fidelity());
                    assertEquals("numeric(39,0)", mapping.value().typeSql());
                    assertTrue(mapping.value().check("v").contains(type.minimum().toString()));
                    assertTrue(mapping.value().check("v").contains(type.maximum().toString()));
                } else if (product == DORIS || product == STARROCKS) {
                    assertEquals(TypeFidelity.EXACT, mapping.fidelity());assertEquals("largeint", mapping.value().typeSql());
                } else assertEquals(TypeFidelity.UNSUPPORTED, mapping.fidelity(), product.name());
            });
        }
        assertThrows(IllegalArgumentException.class, () -> new LogicalType.IntegerType(128, false));
    }

    @Test void postgresCollationsAndKeysOnDorisTarget() {
        assertEquals("EXACT|varchar(160)|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(DORIS,
                pgCollated("v", "character varying(40)"), false)));
        assertEquals("LOSSY|char(20)|PAD_SPACE_EQUALITY_CHANGED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(DORIS,
                pgCollated("v", "character(5)"), false)));
        assertEquals("EXACT|string|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(DORIS,
                pgCollated("v", "text"), false)));
        // Keys: the generated Doris table uses the DUPLICATE KEY model, so key columns are LOSSY; text keys rejected.
        assertEquals("LOSSY|int|KEY_UNIQUENESS_NOT_ENFORCED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(DORIS,
                LogicalTypeMappingTest.pg("k", "integer", Types.INTEGER), true)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(DORIS,
                pgCollated("k", "character varying(20)"), true)));
        assertEquals("EXACT|bigint|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                MysqlFamilyExtendedFixtures.family(DORIS, "k", "bigint(20)"), true)));
    }

    // ---------------------------------------------------------------- golden: MySQL family source -> Doris

    @ParameterizedTest(name = "{0} {1} -> DORIS")
    @CsvSource(delimiter = ';', value = {
            "MYSQL; tinyint; EXACT|tinyint|", "MYSQL; tinyint unsigned; EXACT|smallint|",
            "MYSQL; mediumint(9); EXACT|int|", "MYSQL; int unsigned; EXACT|bigint|",
            "MYSQL; bigint; EXACT|bigint|", "MYSQL; bigint unsigned; EXACT|largeint|",
            "MYSQL; decimal(12,2) unsigned; EXACT|decimal(12,2)|",
            "MYSQL; double; LOSSY|double|NEGATIVE_ZERO_NOT_PRESERVED",
            "MYSQL; float; LOSSY|float|FLOAT32_PRECISION_NOT_PRESERVED",
            "MYSQL; date; EXACT|date|", "MYSQL; datetime(6); EXACT|datetime(6)|",
            "MYSQL; json; EXACT|json|", "MYSQL; blob; UNSUPPORTED|-|BINARY_TYPE_UNAVAILABLE",
            "MYSQL; time(3); UNSUPPORTED|-|TIME_TYPE_UNAVAILABLE",
            "MARIADB; uuid; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE"})
    void mysqlFamilyToDorisGolden(ProductTypeEnum source, String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(DORIS,
                LogicalTypeMappingTest.family(source, "v", nativeType), false)));
    }

    @ParameterizedTest(name = "{0} {1} {2} -> DORIS")
    @CsvSource(delimiter = ';', value = {
            "MYSQL; varchar(20); utf8mb4_bin; LOSSY|varchar(80)|PAD_SPACE_EQUALITY_CHANGED",
            "MYSQL; varchar(20); utf8mb4_0900_bin; EXACT|varchar(80)|",
            "MYSQL; text; utf8mb4_bin; LOSSY|string|PAD_SPACE_EQUALITY_CHANGED",
            "MYSQL; text; utf8mb4_0900_bin; EXACT|string|",
            "TIDB; char(5); utf8mb4_bin; LOSSY|char(20)|PAD_SPACE_EQUALITY_CHANGED",
            "MYSQL; varchar(20); utf8mb4_0900_ai_ci; UNSUPPORTED|-|COLLATION_UNAVAILABLE"})
    void mysqlFamilyCharacterToDorisGolden(ProductTypeEnum source, String nativeType, String collation, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(DORIS,
                LogicalTypeMappingTest.text(source, "v", nativeType, collation), false)));
    }

    @Test void dorisDriverAndCatalogFactsAreEnforced() {
        var wrongBoolean = MysqlFamilyExtendedFixtures.family(DORIS, "v", "tinyint(4)");
        wrongBoolean.setFieldType(Types.BIT); wrongBoolean.setFiledTypeClassName("java.lang.Boolean");
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongBoolean, false)));
        var wrongPrecision = MysqlFamilyExtendedFixtures.family(DORIS, "v", "int(11)"); wrongPrecision.setPrecisionSize(11);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongPrecision, false)));
        var oversized = MysqlFamilyExtendedFixtures.family(DORIS, "v", "decimalv3(39,9)");
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, oversized, false)));
        var foreign = MysqlFamilyExtendedFixtures.family(DORIS, "v", "int(11)");
        MysqlFamilyExtendedFixtures.withFacts(foreign, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                new cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance(DORIS, "MySQL", "5.7.99", "NATIVE_CATALOG_V1", java.time.Instant.EPOCH),
                f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(),
                f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, foreign, false)));
        // OCTETS-length columns have no verified character-length target anywhere.
        assertEquals("UNSUPPORTED|-|TYPE_UNMAPPED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(MYSQL,
                MysqlFamilyExtendedFixtures.family(DORIS, "v", "varchar(20)"), false)));
    }

    // ---------------------------------------------------------------- real Doris <-> PostgreSQL / MySQL round trips

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void signed128NativeCopyPreservesExtremaNullAndRejectsBothOverflowDirections() throws Exception {
        var domain = new LogicalType.Int128Type();
        sql("doris", "CREATE TABLE " + dorisDb + ".source(id BIGINT NOT NULL,v LARGEINT) UNIQUE KEY(id)"
                + " DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='true')");
        sql("doris", "INSERT INTO " + dorisDb + ".source VALUES(1," + domain.minimum() + "),(2," + domain.maximum() + "),(3,NULL)");
        assertSuccess(run(DORIS, "doris", POSTGRESQL, "postgres", true, false));
        try (var connection = open("postgres"); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT v FROM " + pgSchema + ".copied ORDER BY id")) {
            assertTrue(rows.next()); assertEquals(new BigDecimal(domain.minimum()), rows.getBigDecimal(1));
            assertTrue(rows.next()); assertEquals(new BigDecimal(domain.maximum()), rows.getBigDecimal(1));
            assertTrue(rows.next()); assertNull(rows.getBigDecimal(1)); assertFalse(rows.next());
        }
        for (var overflow : List.of(domain.minimum().subtract(java.math.BigInteger.ONE), domain.maximum().add(java.math.BigInteger.ONE))) {
            assertThrows(SQLException.class, () -> sql("postgres", "INSERT INTO " + pgSchema + ".copied VALUES(4," + overflow + ")"));
            var writer = new cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisWriterImpl(source("doris"));
            writer.configure(new cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions(65536, false, false, 20, 0, 0, 250, 0));
            var fields = List.of("id", "v"); writer.prepareWrite(dorisDb, "source", fields);
            assertThrows(IllegalArgumentException.class, () -> writer.write(fields,
                    List.of(new Object[]{4L, BigDecimal.ONE}, new Object[]{5L, new BigDecimal(overflow)})));
        }
        try (var connection = open("doris"); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + dorisDb + ".source")) {
            assertTrue(rows.next()); assertEquals(3, rows.getLong(1));
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void dorisSourceCreatesPostgresColumnsWithAcknowledgedLossesAndCopiesEveryValue() throws Exception {
        sql("doris", "CREATE TABLE " + dorisDb + ".source(k_big bigint, v_int int, v_bool boolean, v_dec decimalv3(38,9),"
                + " v_dt datetime(6), v_day date, v_json json) UNIQUE KEY(k_big) DISTRIBUTED BY HASH(k_big) BUCKETS 1"
                + " PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='true')");
        sql("doris", "INSERT INTO " + dorisDb + ".source VALUES (9223372036854775807, 2147483647, true,"
                + " 99999999999999999999999999999.123456789, '2026-10-05 12:34:56.654321', '2026-10-05', '{\"a\": [2, 3], \"b\": 1}')");
        sql("doris", "INSERT INTO " + dorisDb + ".source VALUES (-9223372036854775808, -2147483648, false,"
                + " -0.000000001, '0001-01-01 00:00:00.000001', '0001-01-01', '{}')");
        var columns = runtime.metadata(source("doris"), DORIS).queryTableColumnMetaWithNativeEvidence(dorisDb, "source");
        var acknowledged = LossyColumnAcceptance.acknowledged(Map.of(
                "v_dt", Set.of("ZERO_DATE_SILENTLY_CONVERTED"), "v_day", Set.of("ZERO_DATE_SILENTLY_CONVERTED")));
        String ddl = GenerateSqlUtils.getDDLCreateTableSQL(runtime.products().metadata(POSTGRESQL), columns, List.of("k_big"),
                pgSchema, "copied", false, TypeMappingOptions.of(MAPPINGS).withLossyAcceptance(acknowledged));
        assertTrue(ddl.contains("\"k_big\" bigint") && ddl.contains("\"v_dt\" timestamp(6) without time zone")
                && ddl.contains("\"v_json\" jsonb") && ddl.contains("PRIMARY KEY (\"k_big\")"), ddl);
        sql("postgres", ddl);
        try (var d = open("doris"); var s = d.createStatement(); var r = s.executeQuery(
                "SELECT k_big, v_int, v_bool, v_dec, v_dt, v_day, v_json FROM " + dorisDb + ".source ORDER BY k_big")) {
            try (var pg = open("postgres"); var ins = pg.prepareStatement(
                    "INSERT INTO " + pgSchema + ".copied VALUES (?,?,?,?,?,?,?)")) {
                while (r.next()) {
                    ins.setLong(1, r.getLong(1));
                    ins.setInt(2, r.getInt(2));
                    ins.setBoolean(3, r.getBoolean(3));
                    ins.setBigDecimal(4, r.getBigDecimal(4));
                    ins.setObject(5, r.getObject(5));
                    ins.setDate(6, r.getDate(6));
                    ins.setObject(7, r.getString(7), Types.OTHER);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        }
        try (var pg = open("postgres"); var s = pg.createStatement(); var r = s.executeQuery(
                "SELECT k_big, v_int, v_bool, v_dec, to_char(v_dt, 'YYYY-MM-DD HH24:MI:SS.US'), v_day::text, v_json::text"
                        + " FROM " + pgSchema + ".copied ORDER BY k_big")) {
            assertTrue(r.next());
            assertEquals(-9223372036854775808L, r.getLong(1));
            assertEquals(-2147483648, r.getInt(2));
            assertFalse(r.getBoolean(3));
            assertEquals(0, new BigDecimal("-0.000000001").compareTo(r.getBigDecimal(4)));
            assertEquals("0001-01-01 00:00:00.000001", r.getString(5));
            assertEquals("0001-01-01", r.getString(6));
            assertEquals("{}", r.getString(7).replace(" ", ""));
            assertTrue(r.next());
            assertEquals(9223372036854775807L, r.getLong(1));
            assertEquals(2147483647, r.getInt(2));
            assertTrue(r.getBoolean(3));
            assertEquals("2026-10-05 12:34:56.654321", r.getString(5));
            assertEquals("2026-10-05", r.getString(6));
            assertEquals("{\"a\":[2,3],\"b\":1}", r.getString(7).replace(" ", ""));
            assertFalse(r.next());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void postgresSourceCreatesExactDorisColumnsAndCopiesEveryValue() throws Exception {
        sql("postgres", "CREATE TABLE " + pgSchema + ".source(v_int integer, v_bool boolean, v_name varchar(40), v_text text)");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (2147483647, true, '中😀zé', E'line1\\n中😀')");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (-2147483648, false, '', '')");
        assertSuccess(run(POSTGRESQL, "postgres", DORIS, "doris", true, false));
        // information_schema.COLUMNS renders Doris integers with MySQL display widths; the physical types are int/boolean.
        assertEquals(List.of("v_int|int(11)|true", "v_bool|tinyint(1)|true", "v_name|varchar(160)|true", "v_text|string|true"),
                dorisColumns(dorisDb, "copied"));
        try (var d = open("doris"); var s = d.createStatement(); var r = s.executeQuery(
                "SELECT v_int, v_bool, v_name, v_text FROM " + dorisDb + ".copied ORDER BY v_int")) {
            assertTrue(r.next());
            assertEquals(-2147483648, r.getInt(1));
            assertFalse(r.getBoolean(2));
            assertEquals("", r.getString(3));
            assertTrue(r.next());
            assertEquals(2147483647, r.getInt(1));
            assertTrue(r.getBoolean(2));
            assertEquals("中😀zé", r.getString(3));
            assertEquals("line1\n中😀", r.getString(4));
            assertFalse(r.next());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    void dorisKeyedPlanIsRejectedByDefaultAndTheDuplicateModelProvesTheLoss() throws Exception {
        sql("postgres", "CREATE TABLE " + pgSchema + ".source(k integer NOT NULL, v_name varchar(20), PRIMARY KEY (k))");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (1, 'one')");
        var columns = runtime.metadata(source("postgres"), POSTGRESQL)
                .queryTableColumnMetaWithNativeEvidence(pgSchema, "source");
        var rejected = assertThrows(TypeMappingRejectedException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                runtime.products().metadata(DORIS), columns, List.of("k"), dorisDb, "copied", false));
        assertTrue(rejected.getMessage().contains("KEY_UNIQUENESS_NOT_ENFORCED"), rejected.getMessage());
        assertFalse(dorisTableExists(dorisDb, "copied"));
        var acknowledged = LossyColumnAcceptance.acknowledged(Map.of("k", Set.of("KEY_UNIQUENESS_NOT_ENFORCED")));
        String ddl = GenerateSqlUtils.getDDLCreateTableSQL(runtime.products().metadata(DORIS), columns, List.of("k"),
                dorisDb, "copied", false, TypeMappingOptions.of(MAPPINGS).withLossyAcceptance(acknowledged));
        assertTrue(ddl.contains("DUPLICATE KEY(`k`)") && ddl.contains("DISTRIBUTED BY HASH(`k`)"), ddl);
        sql("doris", ddl);
        sql("doris", "INSERT INTO " + dorisDb + ".copied VALUES (1,'one')");
        sql("doris", "INSERT INTO " + dorisDb + ".copied VALUES (1,'again')");
        try (var d = open("doris"); var s = d.createStatement(); var r = s.executeQuery(
                "SELECT count(*) FROM " + dorisDb + ".copied")) {
            assertTrue(r.next());
            assertEquals(2, r.getLong(1), "DUPLICATE KEY model stores both rows: uniqueness is not enforced");
        }
        // The UNIQUE KEY model replaces duplicates: the verified server semantics the loss is measured against.
        sql("doris", "CREATE TABLE " + dorisDb + ".unique_check(k bigint, v int) UNIQUE KEY(k) DISTRIBUTED BY HASH(k) BUCKETS 1"
                + " PROPERTIES('replication_num'='1','enable_unique_key_merge_on_write'='true')");
        sql("doris", "INSERT INTO " + dorisDb + ".unique_check VALUES (7,10)");
        sql("doris", "INSERT INTO " + dorisDb + ".unique_check VALUES (7,20)");
        try (var d = open("doris"); var s = d.createStatement(); var r = s.executeQuery(
                "SELECT v FROM " + dorisDb + ".unique_check")) {
            assertTrue(r.next());
            assertEquals(20, r.getInt(1));
            assertFalse(r.next(), "UNIQUE KEY merge-on-write replaces the previous row");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
    @EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/dbswitch_test(\\?.*)?")
    void dorisSourceCreatesExactMysqlColumnsAndCopiesEveryValue() throws Exception {
        sql("doris", "CREATE TABLE " + dorisDb + ".source(k_big bigint, v_int int, v_bool boolean, v_dec decimalv3(38,9))"
                + " DUPLICATE KEY(k_big) DISTRIBUTED BY HASH(k_big) BUCKETS 1 PROPERTIES('replication_num'='1')");
        sql("doris", "INSERT INTO " + dorisDb + ".source VALUES (9223372036854775807, 2147483647, true,"
                + " 99999999999999999999999999999.123456789)");
        sql("doris", "INSERT INTO " + dorisDb + ".source VALUES (-9223372036854775808, -2147483648, false, -0.000000001)");
        assertSuccess(runMysql());
        assertEquals(List.of("k_big|bigint", "v_int|int", "v_bool|tinyint(1)", "v_dec|decimal(38,9)"),
                mysqlColumns("dbswitch_test", mysqlTable));
        try (var m = open("mysql"); var s = m.createStatement(); var r = s.executeQuery(
                "SELECT k_big, v_int, v_bool, v_dec FROM dbswitch_test." + mysqlTable + " ORDER BY k_big")) {
            assertTrue(r.next());
            assertEquals(-9223372036854775808L, r.getLong(1));
            assertEquals(-2147483648, r.getInt(2));
            assertFalse(r.getBoolean(3));
            assertEquals(0, new BigDecimal("-0.000000001").compareTo(r.getBigDecimal(4)));
            assertTrue(r.next());
            assertEquals(9223372036854775807L, r.getLong(1));
            assertEquals(2147483647, r.getInt(2));
            assertTrue(r.getBoolean(3));
            assertEquals(0, new BigDecimal("99999999999999999999999999999.123456789").compareTo(r.getBigDecimal(4)));
            assertFalse(r.next());
        }
    }

    // ---------------------------------------------------------------- helpers

    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, ProductTypeEnum targetProduct,
                               String targetName, boolean insert, boolean drop) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(sourceProduct); source.setDriverClassName(sourceProduct.getDriveClassName());
        source.setUrl(url(sourceName)); source.setUsername(user(sourceName)); source.setPassword(password(sourceName)); source.setPoolSize(1);
        source.setSourceSchema(sourceProduct == POSTGRESQL ? pgSchema : dorisDb);
        source.setSourceIncludes("source"); source.setFetchSize(2);
        source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copied"))); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(targetProduct);
        target.setDriverClassName(targetProduct.getDriveClassName()); target.setPoolSize(1);
        target.setUrl(url(targetName)); target.setUsername(user(targetName)); target.setPassword(password(targetName));
        target.setTargetSchema(targetProduct == DORIS ? dorisDb : pgSchema); target.setWriterEngineInsert(insert); target.setTargetDrop(drop);
        return new MigrationService(request, ignored -> { }, null, runtime).run();
    }

    private DbSwitchResult runMysql() throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(DORIS); source.setDriverClassName(DORIS.getDriveClassName());
        source.setUrl(url("doris")); source.setUsername(user("doris")); source.setPassword(password("doris")); source.setPoolSize(1);
        source.setSourceSchema(dorisDb); source.setSourceIncludes("source"); source.setFetchSize(2);
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

    private List<String> dorisColumns(String db, String table) throws SQLException {
        var result = new ArrayList<String>();
        try (var c = open("doris"); var s = c.prepareStatement("SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
            s.setString(1, db); s.setString(2, table);
            try (var r = s.executeQuery()) {
                while (r.next()) result.add(r.getString(1) + "|" + r.getString(2) + "|" + r.getBoolean(3));
            }
        }
        return result;
    }

    private boolean dorisTableExists(String db, String table) throws SQLException {
        try (var c = open("doris"); var s = c.prepareStatement("SELECT count(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            s.setString(1, db); s.setString(2, table);
            try (var r = s.executeQuery()) { assertTrue(r.next()); return r.getLong(1) > 0; }
        }
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
    private static String url(String name) { return System.getProperty("datahub.test." + name + ".url"); }
    private static String user(String name) {
        return System.getProperty("datahub.test." + name + ".user", name.equals("postgres") ? "dataforge_test" : "root");
    }
    private static String password(String name) {
        return System.getProperty("datahub.test." + name + ".password", "");
    }
}
