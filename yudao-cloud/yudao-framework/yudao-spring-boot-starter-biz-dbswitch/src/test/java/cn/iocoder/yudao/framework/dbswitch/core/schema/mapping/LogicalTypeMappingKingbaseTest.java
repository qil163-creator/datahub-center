package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleFamilyLogicalTypeMapper;
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
 * KingbaseES V008R006C009B0014 oracle mode (Kingbase8 JDBC V008R006B0301) logical type mapping: golden tables
 * plus real Kingbase↔PostgreSQL 16 round trips. Every Kingbase fact was probed on the real server.
 */
@Timeout(300)
class LogicalTypeMappingKingbaseTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();
    /** java.sql.Timestamp materialization shifts wall times only where the JVM zone has transitions. */
    private static final boolean ZONE_GAPS = !java.time.ZoneId.systemDefault().getRules().isFixedOffset();
    private final String pgSchema = "ofam_k_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final String kbTable = "ofam_src_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final List<Runnable> cleanup = new ArrayList<>();
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        runtime = DbSwitchRuntime.standalone();
        if (System.getProperty("datahub.test.kingbase.url") != null) {
            cleanup.add(() -> quietly("kingbase", "DROP TABLE IF EXISTS PUBLIC." + kbTable));
            cleanup.add(() -> quietly("kingbase", "DROP TABLE IF EXISTS PUBLIC.copied"));
        }
        if (System.getProperty("datahub.test.postgres.url") != null) {
            sql("postgres", "CREATE SCHEMA " + pgSchema);
            cleanup.add(() -> quietly("postgres", "DROP SCHEMA IF EXISTS " + pgSchema + " CASCADE"));
        }
    }

    @AfterEach void teardown() {
        try { Collections.reverse(cleanup); cleanup.forEach(Runnable::run); }
        finally { if (runtime != null) runtime.close(); }
    }

    @Test void registryCarriesTheKingbaseFlavorMapper() {
        var mapper = REGISTRY.typeMapper(KINGBASE).orElseThrow();
        assertInstanceOf(OracleFamilyLogicalTypeMapper.class, mapper);
        assertEquals(OracleFamilyLogicalTypeMapper.Flavor.KINGBASE, ((OracleFamilyLogicalTypeMapper) mapper).flavor());
        assertEquals(KINGBASE, mapper.product());
        assertTrue(MAPPINGS.supports(KINGBASE, POSTGRESQL));
        assertFalse(MAPPINGS.supports(KINGBASE, KINGBASE));
    }

    // ---------------------------------------------------------------- golden: Kingbase source -> PostgreSQL

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "numeric(38,0); EXACT|numeric(38,0)|", "numeric(10,2); EXACT|numeric(10,2)|", "numeric; EXACT|numeric|",
            "numeric(10,-2); UNSUPPORTED|-|NEGATIVE_SCALE_NUMERIC_ROUNDS",
            "real; EXACT|real|", "double precision; EXACT|double precision|",
            "boolean; EXACT|boolean|",
            "character varying(20 char); EXACT|character varying(20) COLLATE \"C\"|",
            "character varying(20 byte); UNSUPPORTED|-|TYPE_UNMAPPED",
            "character(5 char); EXACT|character(5) COLLATE \"C\"|",
            "text; EXACT|text COLLATE \"C\"|", "clob; EXACT|text COLLATE \"C\"|",
            "bytea; EXACT|bytea|", "blob; EXACT|bytea|",
            "date; LOSSY|timestamp(0) without time zone|DATE_RANGE_NOT_REPRESENTABLE",
            "timestamp with time zone; EXACT|timestamp(6) with time zone|",
            "interval year(2) to month; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "interval day(2) to second(6); UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "money; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void kingbaseSourceToPostgresGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OracleFamilyMappingFixtures.family(KINGBASE, "v", nativeType), false)));
    }

    @Test void kingbaseLocalTimestampsArePortableThroughTheDriver() {
        for (var type : List.of("timestamp without time zone", "timestamp(3) without time zone")) {
            var mapping = MAPPINGS.map(POSTGRESQL, OracleFamilyMappingFixtures.family(KINGBASE, "v", type), false);
            assertEquals(TypeFidelity.EXACT, mapping.fidelity(), type);
            assertEquals(type.equals("timestamp without time zone") ? "timestamp(6) without time zone" : "timestamp(3) without time zone",
                    mapping.targetNativeType());
        }
    }

    @Test void kingbaseDriverFactsAreEnforced() {
        // Oracle-mode DATE is exposed as TIMESTAMP; a DATE report is inconsistent.
        var wrongDate = OracleFamilyMappingFixtures.family(KINGBASE, "v", "date"); wrongDate.setFieldType(Types.DATE);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongDate, false)));
        var wrongNumber = OracleFamilyMappingFixtures.family(KINGBASE, "v", "numeric(10,2)"); wrongNumber.setScaleSize(1);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongNumber, false)));
        // Text keys have no proven cross-product equality; numeric keys pass through.
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OracleFamilyMappingFixtures.text(KINGBASE, "k", "character varying(20 char)"), true)));
        assertEquals("EXACT|numeric(19,0)|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OracleFamilyMappingFixtures.family(KINGBASE, "k", "numeric(19,0)"), true)));
    }

    // ---------------------------------------------------------------- golden: PostgreSQL source -> Kingbase

    @ParameterizedTest(name = "{0} -> KINGBASE")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|NUMBER(5,0)|", "integer; EXACT|NUMBER(10,0)|", "bigint; EXACT|NUMBER(19,0)|",
            "numeric(38,18); EXACT|NUMBER(38,18)|", "numeric; EXACT|NUMBER|",
            "numeric(66,0); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "real; EXACT|BINARY_FLOAT|", "double precision; EXACT|BINARY_DOUBLE|",
            "boolean; EXACT|BOOLEAN|",
            "character varying(40); LOSSY|VARCHAR2(40 CHAR)|EMPTY_STRING_BECOMES_NULL",
            "text; LOSSY|CLOB|EMPTY_STRING_BECOMES_NULL",
            "character(5); LOSSY|CHAR(5 CHAR)|EMPTY_STRING_BECOMES_NULL",
            "bytea; LOSSY|BLOB|EMPTY_BLOB_BECOMES_NULL",
            "date; EXACT|DATE|",
            "time(3) without time zone; UNSUPPORTED|-|TIME_24_00_WRAPPED,TIME_TYPE_UNAVAILABLE",
            "timestamp(6) with time zone; EXACT|TIMESTAMP(6) WITH TIME ZONE|",
            "uuid; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "jsonb; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "integer[]; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED", "interval; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "xml; UNSUPPORTED|-|TYPE_UNMAPPED", "money; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void postgresSourceToKingbaseGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(KINGBASE,
                LogicalTypeMappingTest.pg("v", nativeType, MysqlFamilyExtendedFixtures.pgJdbc(nativeType)), false)));
    }

    @Test void postgresLocalTimestampToKingbaseRidesTheJvmZoneCodec() {
        var mapping = MAPPINGS.map(KINGBASE, LogicalTypeMappingTest.pg("v", "timestamp(6) without time zone", Types.TIMESTAMP), false);
        assertEquals("TIMESTAMP(6)", mapping.targetNativeType());
        assertEquals(ZONE_GAPS ? Set.of("TIMESTAMP_JVM_ZONE_DST_SHIFT") : Set.of(), mapping.lossCodes());
    }

    @Test void kingbaseTargetEmptyStringAndKeys() {
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(KINGBASE,
                LogicalTypeMappingTest.pg("k", "character varying(20)", Types.VARCHAR), true)));
        var nullable = MAPPINGS.map(KINGBASE, LogicalTypeMappingTest.pg("v", "character varying(20)", Types.VARCHAR), false);
        assertEquals(TypeFidelity.LOSSY, nullable.fidelity());
        assertTrue(nullable.lossCodes().contains("EMPTY_STRING_BECOMES_NULL"));
    }

    // ---------------------------------------------------------------- real Kingbase <-> PostgreSQL 16 round trips

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.kingbase.url", matches = "jdbc:kingbase8://127[.]0[.]0[.]1:[0-9]+/test")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_KINGBASE_TEST_PASSWORD", matches = ".+")
    void kingbaseSourceCreatesExactPostgresColumnsAndCopiesEveryValue() throws Exception {
        sql("kingbase", "CREATE TABLE PUBLIC." + kbTable + "(k NUMBER(19,0) NOT NULL, v_num NUMBER(10,2), v_free NUMBER,"
                + " v_bf BINARY_FLOAT, v_bd BINARY_DOUBLE, v_name VARCHAR2(40 CHAR), v_code CHAR(5 CHAR),"
                + " v_tstz TIMESTAMP WITH TIME ZONE, v_bool BOOLEAN,"
                + " v_text CLOB, v_bytes BLOB, PRIMARY KEY(k))");
        sql("kingbase", "INSERT INTO PUBLIC." + kbTable + " VALUES (-9223372036854775808, -123.45,"
                + " 12345678901234567890123456789012345678.12, 'NaN'::float4, -1.5, '中é😀', 'ab',"
                + " '1919-04-13 00:30:00.123456+05:30'::timestamptz, true, 'clob中', '\\xdeadbeef'::bytea)");
        sql("kingbase", "INSERT INTO PUBLIC." + kbTable + " VALUES (9223372036854775807, 99999999.99,"
                + " 1.5, 2.5, 3.5, '尾é', 'abcde',"
                + " '9999-12-31 23:59:59.999999-08:00'::timestamptz, false, 'x', ''::bytea)");
        sql("kingbase", "INSERT INTO PUBLIC." + kbTable + "(k) VALUES (0)");
        assertSuccess(run(KINGBASE, "kingbase", POSTGRESQL, "postgres", true, false));
        assertEquals(List.of("k|numeric(19,0)|true", "v_num|numeric(10,2)|false", "v_free|numeric|false",
                "v_bf|real|false", "v_bd|double precision|false", "v_name|character varying(40)|false",
                "v_code|character(5)|false",
                "v_tstz|timestamp(6) with time zone|false",
                "v_bool|boolean|false", "v_text|text|false", "v_bytes|bytea|false"),
                columns("postgres", "pg_catalog", pgSchema, "copied"));
        assertEquals(List.of("k"), primaryKey("postgres", "pg_catalog", pgSchema, "copied"));
        String select = "SELECT k, v_num, v_free, CASE WHEN v_bf='NaN'::real THEN 'NaN' ELSE v_bf::text END, v_bd, v_name,"
                + " trim(trailing from v_code), v_tstz AT TIME ZONE 'UTC', v_bool, v_text, encode(v_bytes,'hex')"
                + " FROM {t} ORDER BY k";
        var source = rows("kingbase", select.replace("{t}", "PUBLIC." + kbTable));
        var target = rows("postgres", select.replace("{t}", pgSchema + ".copied"));
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (c == 6 && left != null) { left = left.toString().stripTrailing(); right = right.toString().stripTrailing(); }
                if (c == 7 && left != null) { left = left.toString().substring(0, Math.min(19, left.toString().length()));
                    right = right.toString().substring(0, Math.min(19, right.toString().length())); }
                assertValue(left, right, "row " + r + " column " + c);
            }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.kingbase.url", matches = "jdbc:kingbase8://127[.]0[.]0[.]1:[0-9]+/test")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_KINGBASE_TEST_PASSWORD", matches = ".+")
    void postgresSourceCreatesExactKingbaseColumnsAndCopiesEveryValue() throws Exception {
        // text/bytea are excluded: the Kingbase driver stores '' and empty byte[] as NULL even in TEXT/BLOB
        // (verified LOSSY in the golden table above).
        sql("postgres", "CREATE TABLE " + pgSchema + ".source(k bigint NOT NULL, v_int integer, v_dec numeric(38,18),"
                + " v_bool boolean, v_name varchar(40) NOT NULL,"
                + " v_day date, v_tstz timestamptz, PRIMARY KEY(k))");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (-9223372036854775808, -2147483648, -1.5, false,"
                + " 'a', '0001-01-01',"
                + " '1919-04-13 00:30:00.123456+05:30')");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (9223372036854775807, 2147483647,"
                + " 99999999999999999999.999999999999999999, true, '😀zé中', '9999-12-31',"
                + " '9999-12-31 23:59:59.999999-08:00')");
        sql("postgres", "INSERT INTO " + pgSchema + ".source(k, v_name) VALUES (0, 'z')");
        assertSuccess(run(POSTGRESQL, "postgres", KINGBASE, "kingbase", true, false));
        assertEquals(List.of("K|NUMBER(19,0)|false", "V_INT|NUMBER(10,0)|true", "V_DEC|NUMBER(38,18)|true",
                "V_BOOL|BOOLEAN|true", "V_NAME|VARCHAR2(40 C)|false",
                "V_DAY|DATE|true", "V_TSTZ|TIMESTAMP(6) WITH TIME ZONE|true"),
                kingbaseColumns("PUBLIC", "copied"));
        assertEquals(List.of("K"), kingbasePrimaryKey("PUBLIC", "copied"));
        String select = "SELECT k, v_int, v_dec, v_bool, v_name, v_day,"
                + " v_tstz AT TIME ZONE 'UTC' FROM {t} ORDER BY 1";
        var source = rows("postgres", select.replace("{t}", pgSchema + ".source"));
        var target = rows("kingbase", select.replace("{t}", "PUBLIC.copied"));
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (c == 5 && left != null && right != null) {
                    // Kingbase DATE holds a time of day; compare the date part only.
                    left = left.toString().substring(0, Math.min(10, left.toString().length()));
                    right = right.toString().substring(0, Math.min(10, right.toString().length()));
                }
                if (c == 6 && left != null) { left = left.toString().substring(0, Math.min(19, left.toString().length()));
                    right = right.toString().substring(0, Math.min(19, right.toString().length())); }
                assertValue(left, right, "row " + r + " column " + c);
            }
    }

    @ParameterizedTest(name = "{0} blocks the whole table")
    @CsvSource(delimiter = ';', value = {
            "json; UNSUPPORTED; VALUE_CODEC_UNSUPPORTED",
            "interval year(2) to month; UNSUPPORTED; VALUE_CODEC_UNSUPPORTED"})
    @EnabledIfSystemProperty(named = "datahub.test.kingbase.url", matches = "jdbc:kingbase8://127[.]0[.]0[.]1:[0-9]+/test")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_KINGBASE_TEST_PASSWORD", matches = ".+")
    void kingbaseRejectedColumnsBlockBeforeAnyTargetObjectExists(String type, String fidelity, String reason) throws Exception {
        sql("kingbase", "CREATE TABLE PUBLIC." + kbTable + "(id NUMBER(19,0) NOT NULL, label NUMBER(10,2),"
                + " value " + type + ", PRIMARY KEY(id))");
        var columns = runtime.metadata(source("kingbase"), KINGBASE)
                .queryTableColumnMetaWithNativeEvidence("PUBLIC", kbTable);
        var failure = assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                runtime.products().metadata(POSTGRESQL), columns, List.of("id"), pgSchema, "copied", null, false, Map.of()));
        String message = failure.getMessage();
        assertTrue(message.contains("value [KINGBASE") && message.contains("] " + fidelity + " ") && message.contains(reason), message);
        assertFalse(message.contains("label ["), message);
        assertFalse(tableExists("postgres", pgSchema, "copied"));
    }

    // ---------------------------------------------------------------- helpers

    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, ProductTypeEnum targetProduct,
                               String targetName, boolean insert, boolean drop) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(sourceProduct); source.setDriverClassName(sourceProduct.getDriveClassName());
        source.setUrl(url(sourceName)); source.setUsername(user(sourceName)); source.setPassword(password(sourceName)); source.setPoolSize(1);
        if (sourceProduct == KINGBASE) {
            source.setSourceSchema("PUBLIC"); source.setSourceIncludes(kbTable);
            source.setRegexTableMapper(List.of(new PatternMapper("^" + kbTable + "$", "copied")));
        } else {
            source.setSourceSchema(pgSchema); source.setSourceIncludes("source");
            source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copied")));
        }
        source.setFetchSize(2); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(targetProduct);
        target.setDriverClassName(targetProduct.getDriveClassName()); target.setPoolSize(1);
        target.setUrl(url(targetName)); target.setUsername(user(targetName)); target.setPassword(password(targetName));
        target.setTargetSchema(targetProduct == POSTGRESQL ? pgSchema : "PUBLIC");
        target.setWriterEngineInsert(insert); target.setTargetDrop(drop);
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

    private List<String> kingbaseColumns(String schemaName, String table) throws SQLException {
        var result = new ArrayList<String>();
        String sql = "SELECT column_name,"
                + " CASE WHEN data_type='VARCHAR' THEN 'VARCHAR2(' || char_length || ' ' || char_used || ')'"
                + " WHEN data_type='VARCHARBYTE' THEN 'VARCHAR2(' || data_length || ' B)'"
                + " WHEN data_type='NUMERIC' AND data_precision IS NOT NULL THEN 'NUMBER(' || data_precision || ',' || CASE WHEN data_scale=65531 THEN '0' ELSE TO_CHAR(data_scale) END || ')'"
                + " WHEN data_type='BOOL' THEN 'BOOLEAN'"
                + " WHEN data_type='TIMESTAMPTZ' THEN 'TIMESTAMP(6) WITH TIME ZONE'"
                + " ELSE data_type END AS native_type,"
                + " CASE WHEN nullable='Y' THEN 1 ELSE 0 END AS is_nullable"
                + " FROM SYS.ALL_TAB_COLUMNS WHERE owner='SYSTEM' AND table_name=? ORDER BY column_id";
        try (var c = open("kingbase"); var s = c.prepareStatement(sql)) {
            s.setString(1, table);
            try (var r = s.executeQuery()) { while (r.next()) result.add(r.getString(1) + "|" + r.getString(2) + "|" + r.getBoolean(3)); }
        }
        return result;
    }

    private List<String> kingbasePrimaryKey(String schemaName, String table) throws SQLException {
        var result = new ArrayList<String>();
        String sql = "SELECT cc.column_name FROM SYS.ALL_CONSTRAINTS co JOIN SYS.ALL_CONS_COLUMNS cc"
                + " ON cc.owner=co.owner AND cc.constraint_name=co.constraint_name"
                + " WHERE co.owner='SYSTEM' AND co.table_name=? AND co.constraint_type='P' ORDER BY cc.position";
        try (var c = open("kingbase"); var s = c.prepareStatement(sql)) {
            s.setString(1, table);
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
                    if (value instanceof Blob blob) value = blob.getBytes(1, (int) blob.length());
                    if (value instanceof Clob clob) value = clob.getSubString(1, (int) clob.length());
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
        assertTrue(result.getTableResultList().stream().allMatch(table -> table.getIfSuccess().get()),
                () -> result.getTableResultList().toString());
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
    }

    private void quietly(String name, String sql) { try { sql(name, sql); } catch (Exception ignored) { } }
    private static void sql(String name, String sql) throws SQLException {
        try (var c = open(name); var s = c.createStatement()) { s.setQueryTimeout(60); s.execute(sql); }
    }
    private static Connection open(String name) throws SQLException { return DriverManager.getConnection(url(name), user(name), password(name)); }
    private static String url(String name) { return System.getProperty("datahub.test." + name + ".url"); }
    private static String user(String name) {
        return System.getProperty("datahub.test." + name + ".user", name.equals("kingbase") ? "system" : "dataforge_test");
    }
    private static String password(String name) {
        return name.equals("kingbase") ? System.getenv("DATAHUB_KINGBASE_TEST_PASSWORD") : System.getProperty("datahub.test." + name + ".password", "");
    }
}
