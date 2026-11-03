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
 * DM8 8.1.5.60 (DmJdbcDriver11) logical type mapping: golden tables plus real DM↔PostgreSQL 16 round trips.
 * Every DM fact was probed on the real server; none is copied from the SQL Server or Oracle mappers.
 */
@Timeout(300)
class LogicalTypeMappingDmTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();
    /** java.sql.Timestamp materialization shifts wall times only where the JVM zone has transitions. */
    private static final boolean ZONE_GAPS = !java.time.ZoneId.systemDefault().getRules().isFixedOffset();
    private final String pgSchema = "ofam_d_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final String dmTable = "OFAM_SRC_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(java.util.Locale.ROOT);
    private final List<Runnable> cleanup = new ArrayList<>();
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        runtime = DbSwitchRuntime.standalone();
        if (System.getProperty("datahub.test.dm.url") != null) {
            Class.forName("dm.jdbc.driver.DmDriver");
            cleanup.add(() -> quietly("dm", "DROP TABLE IF EXISTS SYSDBA." + dmTable));
            cleanup.add(() -> quietly("dm", "DROP TABLE IF EXISTS SYSDBA.COPIED"));
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

    @Test void registryCarriesTheDmFlavorMapper() {
        var mapper = REGISTRY.typeMapper(DM).orElseThrow();
        assertInstanceOf(OracleFamilyLogicalTypeMapper.class, mapper);
        assertEquals(OracleFamilyLogicalTypeMapper.Flavor.DM, ((OracleFamilyLogicalTypeMapper) mapper).flavor());
        assertEquals(DM, mapper.product());
        assertTrue(MAPPINGS.supports(DM, POSTGRESQL));
        assertFalse(MAPPINGS.supports(DM, DM));
    }

    // ---------------------------------------------------------------- golden: DM source -> PostgreSQL

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "TINYINT; EXACT|smallint|", "SMALLINT; EXACT|smallint|", "INT; EXACT|integer|", "BIGINT; EXACT|bigint|",
            "NUMBER(10,2); EXACT|numeric(10,2)|", "NUMBER; EXACT|numeric|", "DECIMAL(38,8); EXACT|numeric(38,8)|",
            "NUMBER(10,-2); UNSUPPORTED|-|NEGATIVE_SCALE_NUMERIC_ROUNDS",
            "DOUBLE; EXACT|double precision|", "FLOAT; EXACT|double precision|", "REAL; EXACT|real|",
            "BIT; EXACT|boolean|", "BOOLEAN; EXACT|boolean|",
            "VARCHAR(20 BYTE); UNSUPPORTED|-|TYPE_UNMAPPED",
            "VARCHAR(20 CHAR); LOSSY|character varying(20) COLLATE \"C\"|PAD_SPACE_EQUALITY_CHANGED",
            "CHAR(5 BYTE); UNSUPPORTED|-|TYPE_UNMAPPED",
            "TEXT; LOSSY|text COLLATE \"C\"|PAD_SPACE_EQUALITY_CHANGED",
            "CLOB; LOSSY|text COLLATE \"C\"|PAD_SPACE_EQUALITY_CHANGED",
            "BINARY(16); EXACT|bytea|", "VARBINARY(16); EXACT|bytea|", "BLOB; EXACT|bytea|", "IMAGE; EXACT|bytea|",
            "DATE; EXACT|date|",
            "TIME; EXACT|time(0) without time zone|",
            "TIME(3); EXACT|time(3) without time zone|",
            "TIME(6); LOSSY|time(3) without time zone|TIME_FRACTION_TRUNCATED",
            "INTERVAL YEAR TO MONTH; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "INTERVAL DAY TO SECOND; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "JSON; EXACT|jsonb|"})
    void dmSourceToPostgresGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OracleFamilyMappingFixtures.family(DM, "v", nativeType), false)));
    }

    @Test void dmTimestampsRideTheJvmZoneCodec() {
        for (var type : List.of("TIMESTAMP", "TIMESTAMP(3)", "DATETIME", "DATETIME(3)")) {
            var mapping = MAPPINGS.map(POSTGRESQL, OracleFamilyMappingFixtures.family(DM, "v", type), false);
            assertEquals(ZONE_GAPS ? TypeFidelity.LOSSY : TypeFidelity.EXACT, mapping.fidelity(), type);
            int digits = type.contains("(3)") ? 3 : 6;
            assertEquals("timestamp(" + digits + ") without time zone", mapping.targetNativeType());
            if (ZONE_GAPS) assertEquals(Set.of("TIMESTAMP_JVM_ZONE_DST_SHIFT"), mapping.lossCodes());
        }
    }

    @Test void dmCharacterAndDriverFactsAreEnforced() {
        // DM VARCHAR counts bytes on the verified instance (LENGTH_IN_CHAR=0) and compares PAD SPACE.
        assertEquals("LOSSY|character varying(20) COLLATE \"C\"|PAD_SPACE_EQUALITY_CHANGED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OracleFamilyMappingFixtures.text(DM, "v", "VARCHAR(20 CHAR)"), false)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OracleFamilyMappingFixtures.text(DM, "k", "VARCHAR(20 CHAR)"), true)));
        assertEquals("EXACT|bigint|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OracleFamilyMappingFixtures.family(DM, "k", "BIGINT"), true)));
        // DM DATE is date only: a TIMESTAMP report is inconsistent.
        var wrongDate = OracleFamilyMappingFixtures.family(DM, "v", "DATE"); wrongDate.setFieldType(Types.TIMESTAMP);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongDate, false)));
        // DM FLOAT is DOUBLE underneath (p=53), never REAL.
        var wrongFloat = OracleFamilyMappingFixtures.family(DM, "v", "FLOAT"); wrongFloat.setFieldType(Types.REAL);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongFloat, false)));
        var foreign = OracleFamilyMappingFixtures.family(DM, "v", "BIGINT");
        OracleFamilyMappingFixtures.withFacts(foreign, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                new cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance(DM, "PostgreSQL", "16.13",
                        "NATIVE_CATALOG_V1", java.time.Instant.EPOCH), f.nativeType(), f.defaultExpression(), f.nullable(),
                f.numericPrecision(), f.numericScale(), f.characterLength(), f.identity(), f.generatedExpression(),
                f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, foreign, false)));
    }

    // ---------------------------------------------------------------- golden: PostgreSQL source -> DM

    @ParameterizedTest(name = "{0} -> DM")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|SMALLINT|", "integer; EXACT|INT|", "bigint; EXACT|BIGINT|",
            "numeric(38,18); EXACT|NUMBER(38,18)|", "numeric; EXACT|NUMBER|",
            "numeric(66,0); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "numeric(10,12); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "real; EXACT|REAL|", "double precision; EXACT|DOUBLE|",
            "boolean; EXACT|BOOLEAN|",
            "character varying(40); LOSSY|VARCHAR(40)|PAD_SPACE_EQUALITY_CHANGED",
            "character(5); LOSSY|CHAR(5)|PAD_SPACE_EQUALITY_CHANGED",
            "text; LOSSY|CLOB|PAD_SPACE_EQUALITY_CHANGED",
            "bytea; EXACT|BLOB|",
            "date; LOSSY|DATE|DATE_RANGE_NOT_REPRESENTABLE",
            "time(3) without time zone; LOSSY|TIME(3)|TIME_24_00_WRAPPED",
            "timestamp(6) without time zone; LOSSY|TIMESTAMP(6)|DATE_RANGE_NOT_REPRESENTABLE",
            "timestamp(6) with time zone; LOSSY|TIMESTAMP(6) WITH TIME ZONE|DATE_RANGE_NOT_REPRESENTABLE",
            "uuid; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "jsonb; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "integer[]; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED", "interval; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "xml; UNSUPPORTED|-|TYPE_UNMAPPED", "money; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void postgresSourceToDmGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(DM,
                LogicalTypeMappingTest.pg("v", nativeType, MysqlFamilyExtendedFixtures.pgJdbc(nativeType)), false)));
    }

    @Test void dmTargetCharacterSemantics() {
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(DM,
                LogicalTypeMappingTest.pg("k", "character varying(20)", Types.VARCHAR), true)));
        // DM keeps '' distinct from NULL: no empty-string loss.
        var nullable = MAPPINGS.map(DM, LogicalTypeMappingTest.pg("v", "character varying(20)", Types.VARCHAR), false);
        assertFalse(nullable.lossCodes().contains("EMPTY_STRING_BECOMES_NULL"));
        assertEquals(TypeFidelity.LOSSY, nullable.fidelity());
        // MySQL-family NO PAD sources lose trailing-space equality on DM's PAD SPACE VARCHAR.
        assertEquals(TypeFidelity.LOSSY, MAPPINGS.map(DM,
                LogicalTypeMappingTest.text(MYSQL, "v", "varchar(20)", "utf8mb4_0900_bin"), false).fidelity());
    }

    // ---------------------------------------------------------------- real DM <-> PostgreSQL 16 round trips

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.dm.url", matches = "jdbc:dm://127[.]0[.]0[.]1:[0-9]+")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_DM_TEST_PASSWORD", matches = ".+")
    void dmSourceCreatesExactPostgresColumnsAndCopiesEveryValue() throws Exception {
        sql("dm", "CREATE TABLE SYSDBA." + dmTable + "(K NUMBER(19,0) NOT NULL, V_INT INT, V_DEC NUMBER(10,2),"
                + " V_DBL DOUBLE, V_REAL REAL, V_BIT BIT, V_BOOL BOOLEAN, V_DAY DATE, V_TIME3 TIME(3),"
                + " V_BLOB BLOB, V_BIN VARBINARY(16), V_JSON JSON, PRIMARY KEY(K))");
        sql("dm", "INSERT INTO SYSDBA." + dmTable + " VALUES (-9223372036854775808, -2147483648, -123.45,"
                + " -1.5, 2.5, 1, 0, DATE '0001-01-01', TIME '12:34:56.123',"
                + " 0xDEADBEEF, 0x010203, '{\"a\": 12345678901234567890.123456789}')");
        sql("dm", "INSERT INTO SYSDBA." + dmTable + " VALUES (9223372036854775807, 2147483647,"
                + " 99999999.99, 1.5, -2.5, 0, 1, DATE '9999-12-31', TIME '23:59:59.999',"
                + " 0x00, 0x00, '{\"b\":2}')");
        sql("dm", "INSERT INTO SYSDBA." + dmTable + "(K) VALUES (0)");
        assertSuccess(run(DM, "dm", POSTGRESQL, "postgres", true, false));
        assertEquals(List.of("K|numeric(19,0)|true", "V_INT|integer|false", "V_DEC|numeric(10,2)|false",
                "V_DBL|double precision|false", "V_REAL|real|false", "V_BIT|boolean|false", "V_BOOL|boolean|false",
                "V_DAY|date|false", "V_TIME3|time(3) without time zone|false",
                "V_BLOB|bytea|false",
                "V_BIN|bytea|false", "V_JSON|jsonb|false"),
                columns("postgres", "pg_catalog", pgSchema, "COPIED"));
        assertEquals(List.of("K"), primaryKey("postgres", "pg_catalog", pgSchema, "COPIED"));
        String sourceSelect = "SELECT \"K\", \"V_INT\", \"V_DEC\", \"V_DBL\", \"V_REAL\", \"V_BIT\", \"V_BOOL\","
                + " TO_CHAR(\"V_DAY\",'YYYY-MM-DD'), TO_CHAR(\"V_TIME3\",'HH24:MI:SS.FF3'), \"V_BLOB\", \"V_BIN\", \"V_JSON\""
                + " FROM SYSDBA." + dmTable + " ORDER BY 1";
        String targetSelect = "SELECT \"K\", \"V_INT\", \"V_DEC\", \"V_DBL\", \"V_REAL\", \"V_BIT\", \"V_BOOL\","
                + " \"V_DAY\"::text, \"V_TIME3\"::text, \"V_BLOB\", \"V_BIN\", \"V_JSON\"::text"
                + " FROM " + pgSchema + ".\"COPIED\" ORDER BY 1";
        var source = rows("dm", sourceSelect);
        var target = rows("postgres", targetSelect);
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (c == 7 && left != null) { left = left.toString().substring(0, 10); right = right.toString().substring(0, 10); }
                if (c == 11 && left != null) { left = left.toString().replace(" ", ""); right = right.toString().replace(" ", ""); }
                assertValue(left, right, "row " + r + " column " + c);
            }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.dm.url", matches = "jdbc:dm://127[.]0[.]0[.]1:[0-9]+")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_DM_TEST_PASSWORD", matches = ".+")
    void postgresSourceCreatesExactDmColumnsAndCopiesEveryValue() throws Exception {
        // DM CASE_SENSITIVE=1 stores quoted identifiers exactly: uppercase source names make the physical
        // target columns ("K", "V_SMALL", ...) match the write fields without a lossy case mapping.
        sql("postgres", "CREATE TABLE " + pgSchema + ".source(\"K\" bigint NOT NULL, \"V_SMALL\" smallint, \"V_INT\" integer,"
                + " \"V_DEC\" numeric(38,18), \"V_BOOL\" boolean, \"V_BYTES\" bytea, PRIMARY KEY(\"K\"))");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (-9223372036854775808, -32768, -2147483648, -1.5,"
                + " false, '\\xdeadbeef')");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (9223372036854775807, 32767, 2147483647,"
                + " 99999999999999999999.999999999999999999, true, '\\x')");
        sql("postgres", "INSERT INTO " + pgSchema + ".source(\"K\") VALUES (0)");
        assertSuccess(run(POSTGRESQL, "postgres", DM, "dm", true, false));
        assertEquals(List.of("K|BIGINT|false", "V_SMALL|SMALLINT|true", "V_INT|INT|true", "V_DEC|NUMBER(38,18)|true",
                "V_BOOL|BOOLEAN|true", "V_BYTES|BLOB|true"),
                dmColumns("COPIED"));
        assertEquals(List.of("K"), dmPrimaryKey("COPIED"));
        String select = "SELECT \"K\", \"V_SMALL\", \"V_INT\", \"V_DEC\", \"V_BOOL\", \"V_BYTES\" FROM {t} ORDER BY 1";
        var source = rows("postgres", select.replace("{t}", pgSchema + ".source"));
        var target = rows("dm", select.replace("{t}", "SYSDBA.COPIED"));
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                assertValue(left, right, "row " + r + " column " + c);
            }
    }

    @ParameterizedTest(name = "{0} blocks the whole table")
    @CsvSource(delimiter = ';', value = {
            "INTERVAL YEAR TO MONTH; UNSUPPORTED; VALUE_CODEC_UNSUPPORTED",
            "VARCHAR(20); UNSUPPORTED; TYPE_UNMAPPED"})
    @EnabledIfSystemProperty(named = "datahub.test.dm.url", matches = "jdbc:dm://127[.]0[.]0[.]1:[0-9]+")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_DM_TEST_PASSWORD", matches = ".+")
    void dmRejectedColumnsBlockBeforeAnyTargetObjectExists(String type, String fidelity, String reason) throws Exception {
        sql("dm", "CREATE TABLE SYSDBA." + dmTable + "(ID NUMBER(19,0) NOT NULL, LABEL NUMBER(10,2),"
                + " VALUE " + type + ", PRIMARY KEY(ID))");
        var columns = runtime.metadata(source("dm"), DM)
                .queryTableColumnMetaWithNativeEvidence("SYSDBA", dmTable);
        var failure = assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                runtime.products().metadata(POSTGRESQL), columns, List.of("ID"), pgSchema, "copied", null, false, Map.of()));
        String message = failure.getMessage();
        assertTrue(message.contains("VALUE [DM") && message.contains("] " + fidelity + " ") && message.contains(reason), message);
        assertFalse(message.contains("LABEL ["), message);
        assertFalse(tableExists("postgres", pgSchema, "copied"));
    }

    // ---------------------------------------------------------------- helpers

    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, ProductTypeEnum targetProduct,
                               String targetName, boolean insert, boolean drop) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(sourceProduct); source.setDriverClassName(sourceProduct.getDriveClassName());
        source.setUrl(url(sourceName)); source.setUsername(user(sourceName)); source.setPassword(password(sourceName)); source.setPoolSize(1);
        if (sourceProduct == DM) {
            source.setSourceSchema("SYSDBA"); source.setSourceIncludes(dmTable);
            source.setRegexTableMapper(List.of(new PatternMapper("^" + dmTable + "$", "COPIED")));
        } else {
            source.setSourceSchema(pgSchema); source.setSourceIncludes("source");
            source.setRegexTableMapper(List.of(new PatternMapper("^source$", "COPIED")));
        }
        source.setFetchSize(2); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(targetProduct);
        target.setDriverClassName(targetProduct.getDriveClassName()); target.setPoolSize(1);
        target.setUrl(url(targetName)); target.setUsername(user(targetName)); target.setPassword(password(targetName));
        target.setTargetSchema(targetProduct == POSTGRESQL ? pgSchema : "SYSDBA");
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

    private List<String> dmColumns(String table) throws SQLException {
        var result = new ArrayList<String>();
        String sql = "SELECT COLUMN_NAME, DATA_TYPE || CASE WHEN DATA_TYPE IN ('CHAR') THEN '(' || CHAR_LENGTH || ' ' || CHAR_USED || ')'"
                + " WHEN DATA_TYPE IN ('NUMBER','DECIMAL') AND DATA_PRECISION IS NOT NULL THEN '(' || DATA_PRECISION || ',' || DATA_SCALE || ')'"
                + " WHEN DATA_TYPE IN ('TIME','TIMESTAMP','DATETIME') THEN '(' || DATA_SCALE || ')' END AS native_type,"
                + " CASE WHEN NULLABLE='Y' THEN 1 ELSE 0 END AS is_nullable"
                + " FROM SYS.ALL_TAB_COLUMNS WHERE OWNER='SYSDBA' AND TABLE_NAME=? ORDER BY COLUMN_ID";
        try (var c = open("dm"); var s = c.prepareStatement(sql)) {
            s.setString(1, table);
            try (var r = s.executeQuery()) { while (r.next()) result.add(r.getString(1) + "|" + r.getString(2) + "|" + r.getBoolean(3)); }
        }
        return result;
    }

    private List<String> dmPrimaryKey(String table) throws SQLException {
        var result = new ArrayList<String>();
        String sql = "SELECT cc.COLUMN_NAME FROM SYS.ALL_CONSTRAINTS co JOIN SYS.ALL_CONS_COLUMNS cc"
                + " ON cc.OWNER=co.OWNER AND cc.CONSTRAINT_NAME=co.CONSTRAINT_NAME"
                + " WHERE co.OWNER='SYSDBA' AND co.TABLE_NAME=? AND co.CONSTRAINT_TYPE='P' ORDER BY cc.POSITION";
        try (var c = open("dm"); var s = c.prepareStatement(sql)) {
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
        return System.getProperty("datahub.test." + name + ".user", name.equals("dm") ? "SYSDBA" : "dataforge_test");
    }
    private static String password(String name) {
        return name.equals("dm") ? System.getenv("DATAHUB_DM_TEST_PASSWORD") : System.getProperty("datahub.test." + name + ".password", "");
    }
}
