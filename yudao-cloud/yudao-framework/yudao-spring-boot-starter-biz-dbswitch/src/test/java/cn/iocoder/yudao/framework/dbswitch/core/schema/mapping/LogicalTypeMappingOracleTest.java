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
 * Oracle 23ai/26ai Free 23.26.3 (ojdbc11 23.26.3.0.0) logical type mapping: golden tables plus real
 * Oracle↔PostgreSQL 16 round trips. Every Oracle fact was probed on the real server; none is copied.
 */
@Timeout(300)
class LogicalTypeMappingOracleTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();
    /** java.sql.Timestamp materialization shifts wall times only where the JVM zone has transitions. */
    private static final boolean ZONE_GAPS = !java.time.ZoneId.systemDefault().getRules().isFixedOffset();
    private final String pgSchema = "ofam_o_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final String oracleTable = "ofam_src_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final List<Runnable> cleanup = new ArrayList<>();
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        runtime = DbSwitchRuntime.standalone();
        if (System.getProperty("datahub.test.oracle.url") != null) {
            cleanup.add(() -> quietly("oracle", "DROP TABLE \"SYSTEM\".\"" + oracleTable + "\""));
            cleanup.add(() -> quietly("oracle", "DROP TABLE \"SYSTEM\".\"copied\""));
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

    // ---------------------------------------------------------------- registry

    @Test void registryCarriesTheOracleFlavorMapper() {
        var mapper = REGISTRY.typeMapper(ORACLE).orElseThrow();
        assertInstanceOf(OracleFamilyLogicalTypeMapper.class, mapper);
        assertEquals(OracleFamilyLogicalTypeMapper.Flavor.ORACLE, ((OracleFamilyLogicalTypeMapper) mapper).flavor());
        assertEquals(ORACLE, mapper.product());
        assertTrue(MAPPINGS.supports(ORACLE, POSTGRESQL));
        assertFalse(MAPPINGS.supports(ORACLE, ORACLE));
    }

    // ---------------------------------------------------------------- golden: Oracle source -> PostgreSQL

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "NUMBER(38,0); EXACT|numeric(38,0)|", "NUMBER(10,2); EXACT|numeric(10,2)|", "NUMBER; EXACT|numeric|",
            "NUMBER(10,-2); UNSUPPORTED|-|NEGATIVE_SCALE_NUMERIC_ROUNDS",
            "BINARY_FLOAT; EXACT|real|", "BINARY_DOUBLE; EXACT|double precision|",
            "FLOAT(24); UNSUPPORTED|-|FLOAT_PRECISION_UNMAPPED",
            "VARCHAR2(20 C); EXACT|character varying(20) COLLATE \"C\"|",
            "VARCHAR2(20 B); UNSUPPORTED|-|TYPE_UNMAPPED",
            "CHAR(5 C); EXACT|character(5) COLLATE \"C\"|",
            "NVARCHAR2(20); EXACT|character varying(20) COLLATE \"C\"|",
            "NCHAR(5); EXACT|character(5) COLLATE \"C\"|",
            "RAW(16); EXACT|bytea|",
            "TIMESTAMP(6); UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "TIMESTAMP(6) WITH TIME ZONE; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "TIMESTAMP(6) WITH LOCAL TIME ZONE; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "INTERVAL YEAR(2) TO MONTH; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "INTERVAL DAY(2) TO SECOND(6); UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "BLOB; EXACT|bytea|", "CLOB; EXACT|text COLLATE \"C\"|", "NCLOB; EXACT|text COLLATE \"C\"|",
            "BOOLEAN; EXACT|boolean|",
            "JSON; UNSUPPORTED|-|JSON_UNAVAILABLE_ON_VERIFIED_ENDPOINT",
            "XMLTYPE; UNSUPPORTED|-|XMLTYPE_UNAVAILABLE_ON_VERIFIED_ENDPOINT",
            "ROWID; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void oracleSourceToPostgresGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OracleFamilyMappingFixtures.family(ORACLE, "v", nativeType), false)));
    }

    @Test void oracleDateCarriesTimeOfDay() {
        // Oracle DATE holds a time of day; the driver materializes it as java.sql.Timestamp (portable).
        var mapping = MAPPINGS.map(POSTGRESQL, OracleFamilyMappingFixtures.family(ORACLE, "v", "DATE"), false);
        assertEquals(TypeFidelity.EXACT, mapping.fidelity());
        assertEquals("timestamp(0) without time zone", mapping.targetNativeType());
    }

    @Test void oracleCharacterGolden() {
        assertEquals("EXACT|character varying(20) COLLATE \"C\"|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OracleFamilyMappingFixtures.text(ORACLE, "v", "VARCHAR2(20 CHAR)"), false)));
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OracleFamilyMappingFixtures.text(ORACLE, "k", "VARCHAR2(20 CHAR)"), true)));
        assertEquals("EXACT|bytea|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                OracleFamilyMappingFixtures.family(ORACLE, "k", "RAW(16)"), true)));
    }

    @Test void oracleDriverAndCatalogFactsAreEnforced() {
        // DATE is exposed as TIMESTAMP with precision 7; a DATE report is inconsistent.
        var wrongDate = OracleFamilyMappingFixtures.family(ORACLE, "v", "DATE"); wrongDate.setFieldType(Types.DATE);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongDate, false)));
        // Unbounded NUMBER: the ojdbc driver reports precision 0 and scale -127 (or 0/0 under J2EE13 mode);
        // any other scale is inconsistent.
        var wrongNumber = OracleFamilyMappingFixtures.family(ORACLE, "v", "NUMBER"); wrongNumber.setScaleSize(1);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongNumber, false)));
        // A PostgreSQL provenance is not Oracle native provenance.
        var foreign = OracleFamilyMappingFixtures.family(ORACLE, "v", "NUMBER(10,2)");
        OracleFamilyMappingFixtures.withFacts(foreign, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                new cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance(ORACLE, "PostgreSQL", "16.13",
                        "NATIVE_CATALOG_V1", java.time.Instant.EPOCH), f.nativeType(), f.defaultExpression(), f.nullable(),
                f.numericPrecision(), f.numericScale(), f.characterLength(), f.identity(), f.generatedExpression(),
                f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, foreign, false)));
        // A default blocks the whole column (no verified cross-product default mapping).
        var defaulted = OracleFamilyMappingFixtures.family(ORACLE, "v", "NUMBER(10,2)");
        OracleFamilyMappingFixtures.withFacts(defaulted, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                f.provenance(), f.nativeType(), MetadataValue.value("42"), f.nullable(), f.numericPrecision(),
                f.numericScale(), f.characterLength(), f.identity(), f.generatedExpression(), f.charset(),
                f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|DEFAULT_UNMAPPED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, defaulted, false)));
    }

    // ---------------------------------------------------------------- golden: PostgreSQL source -> Oracle

    @ParameterizedTest(name = "{0} -> ORACLE")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|NUMBER(5,0)|", "integer; EXACT|NUMBER(10,0)|", "bigint; EXACT|NUMBER(19,0)|",
            "numeric(38,18); EXACT|NUMBER(38,18)|", "numeric; EXACT|NUMBER|",
            "numeric(66,0); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "numeric(10,12); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "real; LOSSY|BINARY_FLOAT|NEGATIVE_ZERO_NOT_PRESERVED",
            "double precision; LOSSY|BINARY_DOUBLE|NEGATIVE_ZERO_NOT_PRESERVED",
            "boolean; EXACT|BOOLEAN|",
            "character varying(40); LOSSY|VARCHAR2(40 CHAR)|EMPTY_STRING_BECOMES_NULL",
            "character varying(5000); UNSUPPORTED|-|LENGTH_EXCEEDS_TARGET",
            "text; EXACT|CLOB|",
            "character(5); LOSSY|CHAR(5 CHAR)|EMPTY_STRING_BECOMES_NULL",
            "bytea; EXACT|BLOB|",
            "date; LOSSY|DATE|DATE_RANGE_NOT_REPRESENTABLE",
            "time(3) without time zone; UNSUPPORTED|-|TIME_24_00_WRAPPED,TIME_TYPE_UNAVAILABLE",
            "time with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "timestamp(6) without time zone; LOSSY|TIMESTAMP(6)|DATE_RANGE_NOT_REPRESENTABLE",
            "timestamp(6) with time zone; LOSSY|TIMESTAMP(6) WITH TIME ZONE|DATE_RANGE_NOT_REPRESENTABLE",
            "uuid; UNSUPPORTED|-|UUID_TYPE_UNAVAILABLE",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "jsonb; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "integer[]; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED", "interval; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "xml; UNSUPPORTED|-|TYPE_UNMAPPED", "money; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void postgresSourceToOracleGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(ORACLE,
                LogicalTypeMappingTest.pg("v", nativeType, MysqlFamilyExtendedFixtures.pgJdbc(nativeType)), false)));
    }

    @Test void oracleTargetCharacterKeysAndEmptyString() {
        // Character keys have no proven cross-product equality.
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(ORACLE,
                LogicalTypeMappingTest.pg("k", "character varying(20)", Types.VARCHAR), true)));
        // Oracle stores '' as NULL: nullable text columns are LOSSY, NOT NULL ones fail loudly.
        var nullable = MAPPINGS.map(ORACLE, LogicalTypeMappingTest.pg("v", "character varying(20)", Types.VARCHAR), false);
        assertEquals(TypeFidelity.LOSSY, nullable.fidelity());
        assertTrue(nullable.lossCodes().contains("EMPTY_STRING_BECOMES_NULL"));
        var notNull = LogicalTypeMappingTest.pg("v", "character varying(20)", Types.VARCHAR);
        OracleFamilyMappingFixtures.withFacts(notNull, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                f.provenance(), f.nativeType(), f.defaultExpression(), MetadataValue.value(false), f.numericPrecision(),
                f.numericScale(), f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        notNull.setNullable(false);
        assertEquals(TypeFidelity.EXACT, MAPPINGS.map(ORACLE, notNull, false).fidelity());
    }

    // ---------------------------------------------------------------- real Oracle <-> PostgreSQL 16 round trips

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.oracle.url", matches = "jdbc:oracle:thin:@//127[.]0[.]0[.]1:[0-9]+/FREEPDB1")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_ORACLE_TEST_PASSWORD", matches = ".+")
    void oracleSourceCreatesExactPostgresColumnsAndCopiesEveryValue() throws Exception {
        sql("oracle", "CREATE TABLE \"SYSTEM\".\"" + oracleTable + "\"(k NUMBER(38,0) NOT NULL, v_num NUMBER(10,2),"
                + " v_free NUMBER, v_bf BINARY_FLOAT, v_bd BINARY_DOUBLE, v_name VARCHAR2(40 CHAR),"
                + " v_code CHAR(5 CHAR), v_raw RAW(16), v_clob CLOB, v_blob BLOB, v_bool BOOLEAN,"
                + " PRIMARY KEY(k))");
        sql("oracle", "INSERT INTO \"SYSTEM\".\"" + oracleTable + "\" VALUES (-9223372036854775808, -123.45,"
                + " 12345678901234567890123456789012345678.12, TO_BINARY_FLOAT('NaN'), -1.5, '中é😀', 'ab',"
                + " HEXTORAW('DEADBEEF'), 'clob中', HEXTORAW('010203'), true)");
        sql("oracle", "INSERT INTO \"SYSTEM\".\"" + oracleTable + "\" VALUES (9223372036854775807, 99999999.99,"
                + " 1.5, 2.5, 3.5, '尾é', 'abcde', HEXTORAW('00'), 'x', HEXTORAW(''), false)");
        sql("oracle", "INSERT INTO \"SYSTEM\".\"" + oracleTable + "\"(k) VALUES (0)");
        assertSuccess(run(ORACLE, "oracle", POSTGRESQL, "postgres", true, false));
        assertEquals(List.of("K|numeric(38,0)|true", "V_NUM|numeric(10,2)|false", "V_FREE|numeric|false",
                "V_BF|real|false", "V_BD|double precision|false", "V_NAME|character varying(40)|false",
                "V_CODE|character(5)|false", "V_RAW|bytea|false",
                "V_CLOB|text|false", "V_BLOB|bytea|false", "V_BOOL|boolean|false"),
                columns("postgres", "pg_catalog", pgSchema, "copied"));
        assertEquals(List.of("K"), primaryKey("postgres", "pg_catalog", pgSchema, "copied"));
        String sourceSelect = "SELECT k, v_num, v_free, CASE WHEN v_bf IS NULL THEN NULL WHEN v_bf=v_bf THEN TO_CHAR(v_bf) ELSE 'NaN' END,"
                + " v_bd, v_name, trim(trailing from v_code), v_raw, v_clob, v_blob, v_bool"
                + " FROM \"SYSTEM\".\"" + oracleTable + "\" ORDER BY 1";
        String targetSelect = "SELECT \"K\", \"V_NUM\", \"V_FREE\", CASE WHEN \"V_BF\"='NaN'::real THEN 'NaN' ELSE \"V_BF\"::text END,"
                + " \"V_BD\", \"V_NAME\", trim(trailing from \"V_CODE\"), \"V_RAW\", \"V_CLOB\", \"V_BLOB\", \"V_BOOL\""
                + " FROM " + pgSchema + ".copied ORDER BY 1";
        var source = rows("oracle", sourceSelect);
        var target = rows("postgres", targetSelect);
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (c == 3 && left != null && right != null) {
                    // Oracle renders BINARY_FLOAT in scientific form; compare the double values.
                    try {
                        double a = Double.parseDouble(left.toString().replace("Nan", "NaN"));
                        double b = Double.parseDouble(right.toString().replace("Nan", "NaN"));
                        assertTrue(Double.isNaN(a) ? Double.isNaN(b) : Math.abs(a - b) < 1e-4, "row " + r + " column " + c);
                    } catch (NumberFormatException notNumeric) { assertEquals(left.toString().replace("Nan", "NaN"),
                            right.toString().replace("Nan", "NaN"), "row " + r + " column " + c); }
                    continue;
                }
                if (c == 6 && left != null) { left = left.toString().stripTrailing(); right = right.toString().stripTrailing(); }
                assertValue(left, right, "row " + r + " column " + c);
            }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.oracle.url", matches = "jdbc:oracle:thin:@//127[.]0[.]0[.]1:[0-9]+/FREEPDB1")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_ORACLE_TEST_PASSWORD", matches = ".+")
    void postgresSourceCreatesExactOracleColumnsAndCopiesEveryValue() throws Exception {
        sql("postgres", "CREATE TABLE " + pgSchema + ".source(k bigint NOT NULL, v_int integer, v_dec numeric(38,18),"
                + " v_bool boolean, v_name varchar(40) NOT NULL, v_text text, v_bytes bytea, PRIMARY KEY(k))");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (-9223372036854775808, -2147483648, -1.5, false,"
                + " 'a', 'line1\n中', '\\xdeadbeef')");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (9223372036854775807, 2147483647,"
                + " 99999999999999999999.999999999999999999, true, '😀zé中', '', '\\x')");
        sql("postgres", "INSERT INTO " + pgSchema + ".source(k, v_name) VALUES (0, 'z')");
        assertSuccess(run(POSTGRESQL, "postgres", ORACLE, "oracle", true, false));
        assertEquals(List.of("k|NUMBER(19,0)|false", "v_int|NUMBER(10,0)|true", "v_dec|NUMBER(38,18)|true",
                "v_bool|BOOLEAN|true", "v_name|VARCHAR2(40 C)|false", "v_text|CLOB|true", "v_bytes|BLOB|true"),
                oracleColumns("SYSTEM", "copied"));
        assertEquals(List.of("k"), oraclePrimaryKey("SYSTEM", "copied"));
        String select = "SELECT \"k\", \"v_int\", \"v_dec\", \"v_bool\", \"v_name\", \"v_text\", \"v_bytes\""
                + " FROM {t} ORDER BY 1";
        var source = rows("postgres", select.replace("{t}", pgSchema + ".source"));
        var target = rows("oracle", select.replace("{t}", "\"SYSTEM\".\"copied\""));
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                assertValue(left, right, "row " + r + " column " + c);
            }
    }

    @ParameterizedTest(name = "{0} blocks the whole table")
    @CsvSource(delimiter = ';', value = {
            "TIMESTAMP(6); UNSUPPORTED; VALUE_CODEC_UNSUPPORTED",
            "NUMBER(10,-2); UNSUPPORTED; NEGATIVE_SCALE_NUMERIC_ROUNDS"})
    @EnabledIfSystemProperty(named = "datahub.test.oracle.url", matches = "jdbc:oracle:thin:@//127[.]0[.]0[.]1:[0-9]+/FREEPDB1")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_ORACLE_TEST_PASSWORD", matches = ".+")
    void oracleRejectedColumnsBlockBeforeAnyTargetObjectExists(String type, String fidelity, String reason) throws Exception {
        sql("oracle", "CREATE TABLE \"SYSTEM\".\"" + oracleTable + "\"(id NUMBER(19,0) NOT NULL, label NUMBER(10,2),"
                + " value " + type + ", PRIMARY KEY(id))");
        var columns = runtime.metadata(source("oracle"), ORACLE)
                .queryTableColumnMetaWithNativeEvidence("SYSTEM", oracleTable);
        var failure = assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                runtime.products().metadata(POSTGRESQL), columns, List.of("ID"), pgSchema, "copied", null, false, Map.of()));
        String message = failure.getMessage();
        assertTrue(message.contains("VALUE [ORACLE") && message.contains("] " + fidelity + " ") && message.contains(reason), message);
        assertFalse(message.contains("LABEL ["), message);
        assertFalse(tableExists("postgres", pgSchema, "copied"), "no target object may exist after a rejected plan");
    }

    // ---------------------------------------------------------------- helpers

    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, ProductTypeEnum targetProduct,
                               String targetName, boolean insert, boolean drop) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(sourceProduct); source.setDriverClassName(sourceProduct.getDriveClassName());
        source.setUrl(url(sourceName)); source.setUsername(user(sourceName)); source.setPassword(password(sourceName)); source.setPoolSize(1);
        if (sourceProduct == ORACLE) {
            source.setSourceSchema("SYSTEM"); source.setSourceIncludes(oracleTable);
            source.setRegexTableMapper(List.of(new PatternMapper("^" + oracleTable + "$", "copied")));
        } else {
            source.setSourceSchema(pgSchema); source.setSourceIncludes("source");
            source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copied")));
        }
        source.setFetchSize(2); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(targetProduct);
        target.setDriverClassName(targetProduct.getDriveClassName()); target.setPoolSize(1);
        target.setUrl(url(targetName)); target.setUsername(user(targetName)); target.setPassword(password(targetName));
        target.setTargetSchema(targetProduct == POSTGRESQL ? pgSchema : "SYSTEM");
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

    private List<String> oracleColumns(String schemaName, String table) throws SQLException {
        var result = new ArrayList<String>();
        String sql = "SELECT column_name,"
                + " CASE WHEN data_type='VARCHAR2' THEN 'VARCHAR2(' || CASE WHEN char_used='C' THEN char_length ELSE data_length END || ' ' || char_used || ')'"
                + " WHEN data_type='NUMBER' AND data_precision IS NOT NULL THEN 'NUMBER(' || data_precision || ',' || data_scale || ')'"
                + " ELSE data_type END AS native_type,"
                + " CASE WHEN nullable='Y' THEN 1 ELSE 0 END AS is_nullable"
                + " FROM sys.all_tab_cols WHERE owner=? AND table_name=? AND hidden_column='NO' ORDER BY column_id";
        try (var c = open("oracle"); var s = c.prepareStatement(sql)) {
            s.setString(1, schemaName); s.setString(2, table);
            try (var r = s.executeQuery()) { while (r.next()) result.add(r.getString(1) + "|" + r.getString(2) + "|" + r.getBoolean(3)); }
        }
        return result;
    }

    private List<String> oraclePrimaryKey(String schemaName, String table) throws SQLException {
        var result = new ArrayList<String>();
        String sql = "SELECT cc.column_name FROM sys.all_constraints co JOIN sys.all_cons_columns cc"
                + " ON cc.owner=co.owner AND cc.constraint_name=co.constraint_name"
                + " WHERE co.owner=? AND co.table_name=? AND co.constraint_type='P' ORDER BY cc.position";
        try (var c = open("oracle"); var s = c.prepareStatement(sql)) {
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
        return System.getProperty("datahub.test." + name + ".user", name.equals("oracle") ? "system" : "dataforge_test");
    }
    private static String password(String name) {
        return name.equals("oracle") ? System.getenv("DATAHUB_ORACLE_TEST_PASSWORD") : System.getProperty("datahub.test." + name + ".password", "");
    }
}
