package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.product.db2.Db2FamilyLogicalTypeMapper;
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
 * DB2 LUW 12.1.5.0 (db2jcc4 4.13.127) logical type mapping: golden tables plus real
 * DB2 &#8596; PostgreSQL 16 round trips. Every DB2 fact was probed on the real server.
 */
@Timeout(300)
class LogicalTypeMappingDb2Test {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();
    private final String pgSchema = "sfam_z_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final String db2Table = "SFAM_SRC_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final List<Runnable> cleanup = new ArrayList<>();
    private DbSwitchRuntime runtime;

    @BeforeAll static void driver() throws Exception { Class.forName("com.ibm.db2.jcc.DB2Driver"); }

    @BeforeEach void setup() throws Exception {
        runtime = DbSwitchRuntime.standalone();
        if (System.getProperty("datahub.test.db2.url") != null) {
            cleanup.add(() -> quietly("db2", "DROP TABLE \"DB2INST1\".\"" + db2Table + "\""));
            cleanup.add(() -> quietly("db2", "DROP TABLE \"DB2INST1\".\"COPIED\""));
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

    @Test void registryCarriesTheDb2Mapper() {
        var mapper = REGISTRY.typeMapper(DB2).orElseThrow();
        assertInstanceOf(Db2FamilyLogicalTypeMapper.class, mapper);
        assertEquals(Db2FamilyLogicalTypeMapper.Flavor.DB2, ((Db2FamilyLogicalTypeMapper) mapper).flavor());
        assertEquals(DB2, mapper.product());
        assertTrue(MAPPINGS.supports(DB2, POSTGRESQL));
        assertTrue(MAPPINGS.supports(POSTGRESQL, DB2));
        assertFalse(MAPPINGS.supports(DB2, DB2));
    }

    // ---------------------------------------------------------------- golden: DB2 source -> PostgreSQL

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|smallint|", "integer; EXACT|integer|", "bigint; EXACT|bigint|",
            "boolean; UNSUPPORTED|-|BOOLEAN_DRIVER_CODEC_UNVERIFIED",
            "decimal(31,5); EXACT|numeric(31,5)|", "decimal(12,2); EXACT|numeric(12,2)|",
            "real; EXACT|real|", "double; EXACT|double precision|", "float; EXACT|double precision|",
            "decfloat; UNSUPPORTED|-|DECFLOAT_SEMANTICS_UNMAPPED",
            "date; EXACT|date|", "time; EXACT|time(0) without time zone|",
            "timestamp(6); EXACT|timestamp(6) without time zone|",
            "timestamp(9); LOSSY|timestamp(6) without time zone|TIME_FRACTION_TRUNCATED",
            "timestamp(12); LOSSY|timestamp(6) without time zone|TIME_FRACTION_TRUNCATED",
            "character(10); UNSUPPORTED|-|TYPE_UNMAPPED",
            "varchar(40); UNSUPPORTED|-|TYPE_UNMAPPED",
            "character(8) for bit data; UNSUPPORTED|-|CHAR_FOR_BIT_DATA_PADS_WITH_SPACES",
            "varchar(16) for bit data; EXACT|bytea|",
            "graphic(10); EXACT|character(10) COLLATE \"C\"|",
            "vargraphic(40); LOSSY|character varying(40) COLLATE \"C\"|PAD_SPACE_EQUALITY_CHANGED",
            "long varchar; UNSUPPORTED|-|TYPE_UNMAPPED",
            "clob(65536); UNSUPPORTED|-|TYPE_UNMAPPED",
            "blob(65536); EXACT|bytea|",
            "dbclob(65536); LOSSY|text COLLATE \"C\"|PAD_SPACE_EQUALITY_CHANGED",
            "xml; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED"})
    void db2SourceToPostgresGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                SqlFamilyMappingFixtures.family(DB2, "v", nativeType), false)));
    }

    @Test void db2CharacterKeysAreBlocked() {
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                SqlFamilyMappingFixtures.family(DB2, "k", "varchar(40)"), true)));
        assertEquals("EXACT|bytea|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                SqlFamilyMappingFixtures.family(DB2, "k", "varchar(16) for bit data"), true)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {"graphic(10);-15;EXACT|character(10) COLLATE \"C\"|",
            "vargraphic(40);-9;LOSSY|character varying(40) COLLATE \"C\"|PAD_SPACE_EQUALITY_CHANGED"})
    void modernJccNationalCharacterCodesRetainTheVerifiedNativeDomains(String nativeType, int jdbcType, String expected) {
        var column = SqlFamilyMappingFixtures.family(DB2, "v", nativeType); column.setFieldType(jdbcType);
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, column, false)));
        column.setFieldType(Types.BINARY);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, column, false)));
    }

    @Test void dbclobAcceptsOnlyTheNativeLegacyAndModernNationalLobCodes() {
        var column = SqlFamilyMappingFixtures.family(DB2, "v", "dbclob(65536)");
        for (int jdbcType : List.of(Types.CLOB, Types.NCLOB)) {
            column.setFieldType(jdbcType);
            assertEquals("LOSSY|text COLLATE \"C\"|PAD_SPACE_EQUALITY_CHANGED", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, column, false)));
        }
        for (int jdbcType : List.of(Types.BLOB, Types.LONGVARCHAR, Types.VARCHAR)) {
            column.setFieldType(jdbcType);
            assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, column, false)));
        }
    }

    @Test void db2DriverFactsAreEnforced() {
        var wrongDecimal = SqlFamilyMappingFixtures.family(DB2, "v", "decimal(31,5)"); wrongDecimal.setFieldType(Types.DOUBLE);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongDecimal, false)));
        // The JDBC product version alone is not the pinned service_level: that provenance is rejected.
        var foreign = SqlFamilyMappingFixtures.family(DB2, "v", "integer");
        SqlFamilyMappingFixtures.withFacts(foreign, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                new cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance(DB2, "DB2/LINUXX8664", "SQL12015",
                        "DB2_1215_CATALOG_V1", java.time.Instant.EPOCH), f.nativeType(), f.defaultExpression(), f.nullable(),
                f.numericPrecision(), f.numericScale(), f.characterLength(), f.identity(), f.generatedExpression(),
                f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, foreign, false)));
    }

    // ---------------------------------------------------------------- golden: PostgreSQL source -> DB2

    @ParameterizedTest(name = "{0} -> DB2")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|SMALLINT|", "integer; EXACT|INTEGER|", "bigint; EXACT|BIGINT|",
            "numeric(31,5); EXACT|DECIMAL(31,5)|", "numeric(12,2); EXACT|DECIMAL(12,2)|",
            "numeric; UNSUPPORTED|-|UNBOUNDED_NUMERIC_UNMAPPED",
            "numeric(32,0); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "numeric(10,12); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "real; LOSSY|REAL|NEGATIVE_ZERO_NOT_PRESERVED",
            "double precision; LOSSY|DOUBLE|NEGATIVE_ZERO_NOT_PRESERVED",
            "boolean; UNSUPPORTED|-|BOOLEAN_DRIVER_CODEC_UNVERIFIED",
            "character(5); LOSSY|VARCHAR(20)|PAD_SPACE_EQUALITY_CHANGED",
            "character varying(40); LOSSY|VARCHAR(160)|PAD_SPACE_EQUALITY_CHANGED",
            "character varying(9000); UNSUPPORTED|-|LENGTH_EXCEEDS_TARGET",
            "text; LOSSY|CLOB|PAD_SPACE_EQUALITY_CHANGED",
            "bytea; EXACT|BLOB|",
            "date; LOSSY|DATE|DATE_RANGE_NOT_REPRESENTABLE",
            "time(0) without time zone; LOSSY|TIME|TIME_24_00_WRAPPED",
            "time(3) without time zone; LOSSY|TIME|TIME_24_00_WRAPPED,TIME_FRACTION_TRUNCATED",
            "time with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "timestamp(6) without time zone; LOSSY|TIMESTAMP(6)|DATE_RANGE_NOT_REPRESENTABLE",
            "timestamp(6) with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "uuid; UNSUPPORTED|-|TYPE_UNMAPPED",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED", "jsonb; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "integer[]; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED", "interval; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "xml; UNSUPPORTED|-|TYPE_UNMAPPED", "money; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED",
            "inet; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void postgresSourceToDb2Golden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(DB2,
                LogicalTypeMappingTest.pg("v", nativeType, MysqlFamilyExtendedFixtures.pgJdbc(nativeType)), false)));
    }

    @Test void db2TargetCharacterKeysAreBlocked() {
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(DB2,
                LogicalTypeMappingTest.pg("k", "character varying(20)", Types.VARCHAR), true)));
        assertEquals("EXACT|BLOB|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(DB2,
                LogicalTypeMappingTest.pg("k", "bytea", Types.BINARY), true)));
    }

    // ---------------------------------------------------------------- real DB2 <-> PostgreSQL 16 round trips

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.db2.url", matches = "jdbc:db2://127[.]0[.]0[.]1:[0-9]+/Q17DB")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_DB2_TEST_PASSWORD", matches = ".+")
    void db2SourceCreatesExactPostgresColumnsAndCopiesEveryValue() throws Exception {
        sql("db2", "CREATE TABLE \"DB2INST1\".\"" + db2Table + "\"(\"K\" BIGINT NOT NULL, \"S\" SMALLINT,"
                + " \"I\" INTEGER, \"D\" DECIMAL(31,5), \"R\" REAL, \"F\" DOUBLE, \"DT\" DATE, \"T\" TIME,"
                + " \"TS\" TIMESTAMP(6), \"G\" GRAPHIC(10), \"VB\" VARCHAR(16) FOR BIT DATA, \"BL\" BLOB(64K), PRIMARY KEY(\"K\"))");
        sql("db2", "INSERT INTO \"DB2INST1\".\"" + db2Table + "\" VALUES (-9223372036854775808, -32768, -2147483648,"
                + " -12345678901234567890123456.12345, 3.5, -1.5, DATE('0001-01-01'), TIME('23:59:59'),"
                + " TIMESTAMP('1753-01-01 00:00:00.000001'), '\u4E2D\u00E9', X'DEADBEEF', BLOB(X'010203'))");
        sql("db2", "INSERT INTO \"DB2INST1\".\"" + db2Table + "\" VALUES (9223372036854775807, 32767, 2147483647,"
                + " 12345678901234567890123456.12345, -0.0, 0.0, DATE('9999-12-31'), TIME('00:00:00'),"
                + " TIMESTAMP('9999-12-31 23:59:59.999999'), 'abcde     ', X'00', BLOB(X'00'))");
        sql("db2", "INSERT INTO \"DB2INST1\".\"" + db2Table + "\"(\"K\") VALUES (0)");
        assertSuccess(run(DB2, "db2", POSTGRESQL, "postgres"));
        // The cross-product target keeps the DB2 source's uppercase physical names (quoted, case preserved).
        assertEquals(List.of("K|bigint|true", "S|smallint|false", "I|integer|false",
                "D|numeric(31,5)|false", "R|real|false", "F|double precision|false", "DT|date|false",
                "T|time(0) without time zone|false", "TS|timestamp(6) without time zone|false",
                "G|character(10)|false", "VB|bytea|false", "BL|bytea|false"),
                columns("postgres", "pg_catalog", pgSchema, "COPIED"));
        assertEquals(List.of("K"), primaryKey("postgres", "pg_catalog", pgSchema, "COPIED"));
        String select = "SELECT K, S, I, D, R, F, DT, T, TS, G, VB, BL FROM {t} ORDER BY 1";
        var source = rows("db2", select.replace("{t}", "\"DB2INST1\".\"" + db2Table + "\""));
        // The PG target stores the source's uppercase names (quoted); the projection quotes them the same way.
        String targetSelect = "SELECT \"K\", \"S\", \"I\", \"D\", \"R\", \"F\", \"DT\", \"T\", \"TS\", \"G\", \"VB\", \"BL\""
                + " FROM \"" + pgSchema + "\".\"COPIED\" ORDER BY 1";
        var target = rows("postgres", targetSelect);
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (c == 9 && left != null) { left = left.toString().stripTrailing(); right = right.toString().stripTrailing(); }
                assertValue(left, right, "row " + r + " column " + c);
            }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.db2.url", matches = "jdbc:db2://127[.]0[.]0[.]1:[0-9]+/Q17DB")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_DB2_TEST_PASSWORD", matches = ".+")
    void postgresSourceCreatesExactDb2ColumnsAndCopiesEveryValue() throws Exception {
        sql("postgres", "CREATE TABLE " + pgSchema + ".source(k bigint NOT NULL, v_int integer, v_small smallint,"
                + " v_dec numeric(31,10),"
                + " v_char character(5) COLLATE \"C\", v_bytes bytea, PRIMARY KEY(k))");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (-9223372036854775808, -2147483648, -32768,"
                + " -1.5, 'ab', '\\xdeadbeef')");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (9223372036854775807, 2147483647, 32767,"
                + " 9999999999999999999.9999999999, 'abcde', '\\x00')");
        sql("postgres", "INSERT INTO " + pgSchema + ".source(k) VALUES (0)");
        assertSuccess(run(POSTGRESQL, "postgres", DB2, "db2"));
        // The DDL quotes the lowercase source names, so DB2 stores them lowercase (quoted identifiers keep case).
        assertEquals(List.of("k|BIGINT|false", "v_int|INTEGER|true", "v_small|SMALLINT|true", "v_dec|DECIMAL(31,10)|true",
                "v_char|VARCHAR(20)|true", "v_bytes|BLOB|true"), db2Columns("COPIED"));
        String select = "SELECT k, v_int, v_small, v_dec,"
                + " v_char, v_bytes FROM {t} ORDER BY 1";
        var source = rows("postgres", select.replace("{t}", pgSchema + ".source"));
        // The DDL quotes the lowercase source names, so the DB2 target stores them lowercase; quote the read-back names.
        String targetSelect = "SELECT \"k\", \"v_int\", \"v_small\", \"v_dec\", \"v_char\", \"v_bytes\""
                + " FROM \"DB2INST1\".\"COPIED\" ORDER BY 1";
        var target = rows("db2", targetSelect);
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (c == 4 && left != null) { left = left.toString().stripTrailing(); right = right.toString().stripTrailing(); }
                assertValue(left, right, "row " + r + " column " + c);
            }
    }

    // ---------------------------------------------------------------- helpers

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.db2.url", matches = "jdbc:db2://127[.]0[.]0[.]1:[0-9]+/Q17DB")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_DB2_TEST_PASSWORD", matches = ".+")
    void defaultJccNationalClobCreatesPostgresTextAndPreservesEveryCharacter() throws Exception {
        sql("db2", "CREATE TABLE \"DB2INST1\".\"" + db2Table + "\"(\"K\" BIGINT NOT NULL PRIMARY KEY,\"V\" DBCLOB(256K))");
        String value = " 中文é😀'\\\n ".repeat(8000);
        try (var c = open("db2"); var p = c.prepareStatement("INSERT INTO \"DB2INST1\".\"" + db2Table + "\" VALUES(?,?)")) {
            p.setLong(1, 1); p.setString(2, value); p.executeUpdate();
            p.setLong(1, 2); p.setNull(2, Types.NCLOB); p.executeUpdate();
            p.setLong(1, 3); p.setString(2, ""); p.executeUpdate();
        }
        var metadata = runtime.metadata(source("db2"), DB2).queryTableColumnMetaWithNativeEvidence("DB2INST1", db2Table);
        var lob = metadata.stream().filter(c -> c.getFieldName().equals("V")).findFirst().orElseThrow();
        assertEquals(Types.NCLOB, lob.getFieldType());
        assertEquals("dbclob(262144)", lob.getMetadataEvidence().nativeType().value());
        var plan = MAPPINGS.plan(POSTGRESQL, metadata, List.of("K"), LossyColumnAcceptance.rejectAll());
        var mapped = plan.columns().stream().filter(c -> c.column().equals("V")).findFirst().orElseThrow();
        assertEquals(Set.of("PAD_SPACE_EQUALITY_CHANGED"), mapped.lossCodes());
        var request = new MigrationRequest(); var from = new SourceDataSourceProperties();
        from.setSourceProductType(DB2); from.setUrl(url("db2")); from.setUsername(user("db2")); from.setPassword(password("db2"));
        from.setSourceSchema("DB2INST1"); from.setSourceIncludes(db2Table); from.setPoolSize(1); from.setFetchSize(2);
        from.setRegexTableMapper(List.of(new PatternMapper("^" + db2Table + "$", "COPIED"))); request.getSource().add(from);
        var target = request.getTarget(); target.setTargetProductType(POSTGRESQL); target.setUrl(url("postgres"));
        target.setUsername(user("postgres")); target.setPassword(password("postgres")); target.setTargetSchema(pgSchema);
        target.setPoolSize(1); target.setWriterEngineInsert(true);
        var rejected = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertFalse(rejected.getIfAllSuccess().get());
        assertTrue(columns("postgres", "pg_catalog", pgSchema, "COPIED").isEmpty());
        request.setTypeMappingAcknowledgements(List.of(LossyMappingAcknowledgement.forColumn(pgSchema, "COPIED", mapped)));
        assertSuccess(new MigrationService(request, ignored -> { }, null, runtime).run());
        assertEquals(List.of("K|bigint|true", "V|text|false"), columns("postgres", "pg_catalog", pgSchema, "COPIED"));
        assertEquals(List.of(Arrays.asList(new BigDecimal(1), value), Arrays.asList(new BigDecimal(2), null), Arrays.asList(new BigDecimal(3), "")),
                rows("postgres", "SELECT \"K\",\"V\" FROM \"" + pgSchema + "\".\"COPIED\" ORDER BY \"K\""));
    }

    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, ProductTypeEnum targetProduct,
                               String targetName) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(sourceProduct); source.setDriverClassName(sourceProduct.getDriveClassName());
        source.setUrl(url(sourceName)); source.setUsername(user(sourceName)); source.setPassword(password(sourceName)); source.setPoolSize(1);
        if (sourceProduct == DB2) {
            source.setSourceSchema("DB2INST1"); source.setSourceIncludes(db2Table);
            source.setRegexTableMapper(List.of(new PatternMapper("^" + db2Table + "$", "COPIED")));
        } else {
            source.setSourceSchema(pgSchema); source.setSourceIncludes("source");
            source.setRegexTableMapper(List.of(new PatternMapper("^source$", "COPIED")));
        }
        source.setFetchSize(2); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(targetProduct);
        target.setDriverClassName(targetProduct.getDriveClassName()); target.setPoolSize(1);
        target.setUrl(url(targetName)); target.setUsername(user(targetName)); target.setPassword(password(targetName));
        target.setTargetSchema(targetProduct == POSTGRESQL ? pgSchema : "DB2INST1");
        target.setWriterEngineInsert(true); target.setTargetDrop(false);
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

    private List<String> db2Columns(String table) throws SQLException {
        var result = new ArrayList<String>();
        String sql = "SELECT COLNAME, CASE WHEN TYPENAME IN ('DECIMAL','NUMERIC') THEN 'DECIMAL(' || LENGTH || ',' || SCALE || ')'"
                + " WHEN TYPENAME='CHARACTER' THEN 'CHARACTER(' || LENGTH || ')'"
                + " WHEN TYPENAME='VARCHAR' THEN 'VARCHAR(' || LENGTH || ')'"
                + " WHEN TYPENAME='TIMESTAMP' THEN 'TIMESTAMP(' || SCALE || ')' ELSE TYPENAME END AS native_type,"
                + " CASE WHEN NULLS='Y' THEN 1 ELSE 0 END AS is_nullable"
                + " FROM SYSCAT.COLUMNS WHERE TABSCHEMA='DB2INST1' AND TABNAME=? ORDER BY COLNO";
        try (var c = open("db2"); var s = c.prepareStatement(sql)) {
            s.setString(1, table);
            try (var r = s.executeQuery()) { while (r.next()) result.add(r.getString(1) + "|" + r.getString(2) + "|" + r.getBoolean(3)); }
        }
        return result;
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
        return System.getProperty("datahub.test." + name + ".user", name.equals("db2") ? "db2inst1" : "dataforge_test");
    }
    private static String password(String name) {
        return name.equals("db2") ? System.getenv("DATAHUB_DB2_TEST_PASSWORD") : System.getProperty("datahub.test." + name + ".password", "");
    }
}
