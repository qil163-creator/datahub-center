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
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver.SqlServerFamilyLogicalTypeMapper;
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
 * SQL Server 2022 RTM-CU27 (mssql-jdbc 10.2.3) logical type mapping: golden tables plus real
 * SQL Server &#8596; PostgreSQL 16 round trips. Every SQL Server fact was probed on the real server.
 */
@Timeout(300)
class LogicalTypeMappingSqlserverTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();
    private final String pgSchema = "sfam_s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final String sqlserverTable = "sfam_src_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final List<Runnable> cleanup = new ArrayList<>();
    private DbSwitchRuntime runtime;

    @BeforeAll static void driver() throws Exception { Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver"); }

    @BeforeEach void setup() throws Exception {
        runtime = DbSwitchRuntime.standalone();
        if (System.getProperty("datahub.test.sqlserver.url") != null) {
            cleanup.add(() -> quietly("sqlserver", "DROP TABLE [dbo].[" + sqlserverTable + "]"));
            cleanup.add(() -> quietly("sqlserver", "DROP TABLE [dbo].[copied]"));
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

    @Test void registryCarriesTheSqlServerMapper() {
        var mapper = REGISTRY.typeMapper(SQLSERVER).orElseThrow();
        assertInstanceOf(SqlServerFamilyLogicalTypeMapper.class, mapper);
        assertEquals(SqlServerFamilyLogicalTypeMapper.Flavor.SQLSERVER, ((SqlServerFamilyLogicalTypeMapper) mapper).flavor());
        assertEquals(SQLSERVER, mapper.product());
        assertTrue(MAPPINGS.supports(SQLSERVER, POSTGRESQL));
        assertTrue(MAPPINGS.supports(POSTGRESQL, SQLSERVER));
        assertFalse(MAPPINGS.supports(SQLSERVER, SQLSERVER));
    }

    // ---------------------------------------------------------------- golden: SQL Server source -> PostgreSQL

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "bigint; EXACT|bigint|", "int; EXACT|integer|", "smallint; EXACT|smallint|",
            "tinyint; EXACT|smallint|",
            "bit; EXACT|boolean|",
            "decimal(38,10); EXACT|numeric(38,10)|", "numeric(12,2); EXACT|numeric(12,2)|",
            "money; EXACT|numeric(19,4)|", "smallmoney; EXACT|numeric(10,4)|",
            "float(53); EXACT|double precision|", "float(24); EXACT|real|", "real; EXACT|real|",
            "date; EXACT|date|",
            "datetime2(7); LOSSY|timestamp(6) without time zone|TIME_FRACTION_TRUNCATED",
            "datetime2(3); EXACT|timestamp(3) without time zone|",
            "datetime; EXACT|timestamp(3) without time zone|",
            "smalldatetime; EXACT|timestamp(0) without time zone|",
            "datetimeoffset(7); UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "time(7); LOSSY|time(6) without time zone|TIME_FRACTION_TRUNCATED",
            "time(6); EXACT|time(6) without time zone|",
            "time(5); EXACT|time(5) without time zone|",
            "time(4); EXACT|time(4) without time zone|",
            "time(3); EXACT|time(3) without time zone|",
            "char(10); UNSUPPORTED|-|TYPE_UNMAPPED",
            "varchar(40); UNSUPPORTED|-|TYPE_UNMAPPED",
            "varchar(max); UNSUPPORTED|-|TYPE_UNMAPPED",
            "nchar(10); LOSSY|character(10)|COLLATION_EQUALITY_CHANGED",
            "nvarchar(40); LOSSY|character varying(40)|COLLATION_EQUALITY_CHANGED",
            "nvarchar(max); LOSSY|text|COLLATION_EQUALITY_CHANGED",
            "binary(8); EXACT|bytea|", "varbinary(16); EXACT|bytea|", "varbinary(max); EXACT|bytea|",
            "image; EXACT|bytea|", "text; UNSUPPORTED|-|TYPE_UNMAPPED", "ntext; LOSSY|text|COLLATION_EQUALITY_CHANGED",
            "uniqueidentifier; EXACT|uuid|",
            "xml; UNSUPPORTED|-|TYPE_UNMAPPED",
            "timestamp; UNSUPPORTED|-|ROWVERSION_SEMANTICS",
            "sql_variant; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "hierarchyid; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED"})
    void sqlserverSourceToPostgresGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                SqlFamilyMappingFixtures.family(SQLSERVER, "v", nativeType), false)));
    }

    @Test void sqlserverCharacterKeysAreBlockedButUuidKeysAreExact() {
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                SqlFamilyMappingFixtures.family(SQLSERVER, "k", "varchar(40)"), true)));
        assertEquals("EXACT|uuid|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                SqlFamilyMappingFixtures.family(SQLSERVER, "k", "uniqueidentifier"), true)));
    }

    @Test void sqlserverDriverAndCatalogFactsAreEnforced() {
        var wrongFloat = SqlFamilyMappingFixtures.family(SQLSERVER, "v", "float(53)"); wrongFloat.setFieldType(Types.REAL);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongFloat, false)));
        var foreign = SqlFamilyMappingFixtures.family(SQLSERVER, "v", "int");
        SqlFamilyMappingFixtures.withFacts(foreign, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                new cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance(SQLSERVER, "Microsoft SQL Server", "16.0",
                        "SQLSERVER16_CATALOG_V1", java.time.Instant.EPOCH), f.nativeType(), f.defaultExpression(), f.nullable(),
                f.numericPrecision(), f.numericScale(), f.characterLength(), f.identity(), f.generatedExpression(),
                f.charset(), f.collation(), f.sequence()));
        assertEquals(TypeFidelity.EXACT, MAPPINGS.map(POSTGRESQL, foreign, false).fidelity());
        // A collation other than the verified default blocks character columns.
        var collated = SqlFamilyMappingFixtures.family(SQLSERVER, "v", "varchar(40)");
        SqlFamilyMappingFixtures.withFacts(collated, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                f.provenance(), f.nativeType(), f.defaultExpression(), f.nullable(), f.numericPrecision(), f.numericScale(),
                f.characterLength(), f.identity(), f.generatedExpression(), f.charset(),
                MetadataValue.value("Latin1_General_100_BIN2_UTF8"), f.sequence()));
        assertEquals("UNSUPPORTED|-|COLLATION_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, collated, false)));
    }

    // ---------------------------------------------------------------- golden: PostgreSQL source -> SQL Server

    @ParameterizedTest(name = "{0} -> SQLSERVER")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|smallint|", "integer; EXACT|int|", "bigint; EXACT|bigint|",
            "numeric(38,10); EXACT|decimal(38,10)|", "numeric(19,4); EXACT|decimal(19,4)|",
            "numeric; UNSUPPORTED|-|UNBOUNDED_NUMERIC_UNMAPPED",
            "numeric(39,0); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "numeric(10,12); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "real; EXACT|real|", "double precision; EXACT|float(53)|",
            "boolean; EXACT|bit|",
            "character(5); LOSSY|nvarchar(5)|PAD_SPACE_EQUALITY_CHANGED",
            "character varying(40); LOSSY|nvarchar(40)|PAD_SPACE_EQUALITY_CHANGED",
            "text; LOSSY|nvarchar(max)|PAD_SPACE_EQUALITY_CHANGED",
            "character varying(9000); UNSUPPORTED|-|LENGTH_EXCEEDS_TARGET",
            "bytea; EXACT|varbinary(max)|",
            "date; LOSSY|date|DATE_RANGE_NOT_REPRESENTABLE",
            "time(3) without time zone; LOSSY|time(3)|TIME_24_00_WRAPPED",
            "time with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "timestamp(3) without time zone; LOSSY|datetime2(3)|DATE_RANGE_NOT_REPRESENTABLE",
            "timestamp(3) with time zone; LOSSY|datetimeoffset(3)|DATE_RANGE_NOT_REPRESENTABLE",
            "uuid; EXACT|uniqueidentifier|",
            "xml; EXACT|xml|",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED", "jsonb; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "integer[]; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED", "interval; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "money; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED", "inet; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void postgresSourceToSqlserverGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(SQLSERVER,
                LogicalTypeMappingTest.pg("v", nativeType, MysqlFamilyExtendedFixtures.pgJdbc(nativeType)), false)));
    }

    @Test void sqlserverTargetCharacterKeysAndChecks() {
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(SQLSERVER,
                LogicalTypeMappingTest.pg("k", "character varying(20)", Types.VARCHAR), true)));
        assertEquals("EXACT|uniqueidentifier|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(SQLSERVER,
                LogicalTypeMappingTest.pg("k", "uuid", Types.OTHER), true)));
        var fixed = MAPPINGS.map(SQLSERVER, LogicalTypeMappingTest.pg("v", "bytea", Types.BINARY), false);
        assertEquals(TypeFidelity.EXACT, fixed.fidelity());
    }

    // ---------------------------------------------------------------- real SQL Server <-> PostgreSQL 16 round trips

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.sqlserver.url", matches = "jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+.*")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_SQLSERVER_TEST_PASSWORD", matches = ".+")
    void sqlserverSourceCreatesExactPostgresColumnsAndCopiesEveryValue() throws Exception {
        sql("sqlserver", "CREATE TABLE [dbo].[" + sqlserverTable + "](k bigint NOT NULL, v_int int NULL, v_small smallint NULL,"
                + " v_tiny tinyint NULL, v_bit bit NULL, v_dec decimal(38,10) NULL, v_money money NULL, v_smallmoney smallmoney NULL,"
                + " v_float float(53) NULL, v_real real NULL, v_date date NULL, v_dt2 datetime2(3) NULL, v_time time(3) NULL,"
                + " v_bin varbinary(16) NULL, v_uuid uniqueidentifier NULL, PRIMARY KEY(k))");
        sql("sqlserver", "INSERT INTO [dbo].[" + sqlserverTable + "] VALUES (-9223372036854775808, -2147483648, -32768, 0, 0,"
                + " -1234567890123456789012345678.1234567890, -922337203685477.5808, -214748.3648, -1.5, 3.5,"
                + " '0001-01-01', '2026-10-06 12:34:56.789', '23:59:59.123', 0xDEADBEEF, '6F9619FF-8B86-D011-B42D-00C04FC964FF')");
        sql("sqlserver", "INSERT INTO [dbo].[" + sqlserverTable + "] VALUES (9223372036854775807, 2147483647, 32767, 255, 1,"
                + " 1234567890123456789012345678.1234567890, 922337203685477.5807, 214748.3647, 0.0, -0.0,"
                + " '9999-12-31', '9999-12-31 23:59:59.999', '00:00:00.001', 0x00, 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11')");
        sql("sqlserver", "INSERT INTO [dbo].[" + sqlserverTable + "](k) VALUES (0)");
        assertSuccess(run(SQLSERVER, "sqlserver", POSTGRESQL, "postgres"));
        assertEquals(List.of("k|bigint|true", "v_int|integer|false", "v_small|smallint|false", "v_tiny|smallint|false",
                "v_bit|boolean|false", "v_dec|numeric(38,10)|false", "v_money|numeric(19,4)|false",
                "v_smallmoney|numeric(10,4)|false", "v_float|double precision|false", "v_real|real|false",
                "v_date|date|false", "v_dt2|timestamp(3) without time zone|false", "v_time|time(3) without time zone|false",
                "v_bin|bytea|false", "v_uuid|uuid|false"),
                columns("postgres", "pg_catalog", pgSchema, "copied"));
        assertEquals(List.of("k"), primaryKey("postgres", "pg_catalog", pgSchema, "copied"));
        String select = "SELECT k, v_int, v_small, v_tiny, CASE WHEN v_bit IS NULL THEN NULL ELSE CAST(v_bit AS int) END, v_dec,"
                + " v_money, v_smallmoney, v_float, v_real, v_date, v_dt2, v_time, v_bin, v_uuid FROM {t} ORDER BY 1";
        var source = rows("sqlserver", select.replace("{t}", "[dbo].[" + sqlserverTable + "]"));
        var target = rows("postgres", select.replace("{t}", pgSchema + ".copied"));
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (c == 14 && left != null && right != null) {
                    assertEquals(left.toString().toLowerCase(java.util.Locale.ROOT), right.toString().toLowerCase(java.util.Locale.ROOT), "row " + r + " column " + c);
                    continue;
                }
                assertValue(left, right, "row " + r + " column " + c);
            }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.sqlserver.url", matches = "jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+.*")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_SQLSERVER_TEST_PASSWORD", matches = ".+")
    void postgresSourceCreatesExactSqlServerColumnsAndCopiesEveryValue() throws Exception {
        sql("postgres", "CREATE TABLE " + pgSchema + ".source(k bigint NOT NULL, v_int integer, v_small smallint, v_bool boolean,"
                + " v_dec numeric(38,10), v_money numeric(19,4), v_double double precision, v_real real,"
                + " v_uuid uuid, v_bytes bytea, v_char character(5) COLLATE \"C\", PRIMARY KEY(k))");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (-9223372036854775808, -2147483648, -32768, false,"
                + " -1.5, -922337203685477.5808, -1.5, 3.5,"
                + " '6f9619ff-8b86-d011-b42d-00c04fc964ff', '\\xdeadbeef', 'ab')");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (9223372036854775807, 2147483647, 32767, true,"
                + " 9999999999999999999999999999.9999999999, 922337203685477.5807, 0.0, -0.0,"
                + " 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '\\x00', 'abcde')");
        sql("postgres", "INSERT INTO " + pgSchema + ".source(k) VALUES (0)");
        assertSuccess(run(POSTGRESQL, "postgres", SQLSERVER, "sqlserver"));
        assertEquals(List.of("k|bigint|false", "v_int|int|true", "v_small|smallint|true", "v_bool|bit|true",
                "v_dec|decimal(38,10)|true", "v_money|decimal(19,4)|true", "v_double|float(53)|true", "v_real|real|true",
                "v_uuid|uniqueidentifier|true", "v_bytes|varbinary(max)|true",
                "v_char|nvarchar(5)|true"), sqlserverColumns("dbo", "copied"));
        assertEquals(List.of("k"), sqlserverPrimaryKey("dbo", "copied"));
        String select = "SELECT k, v_int, v_small, CASE WHEN v_bool IS NULL THEN NULL ELSE CAST(v_bool AS int) END, v_dec,"
                + " v_money, v_double, v_real, v_uuid, v_bytes, v_char FROM {t} ORDER BY 1";
        var source = rows("postgres", select.replace("{t}", pgSchema + ".source"));
        var target = rows("sqlserver", select.replace("{t}", "[dbo].[copied]"));
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (c == 8 && left != null && right != null) {
                    assertEquals(left.toString().toLowerCase(java.util.Locale.ROOT), right.toString().toLowerCase(java.util.Locale.ROOT), "row " + r + " column " + c);
                    continue;
                }
                if (c == 10 && left != null) { left = left.toString().stripTrailing(); right = right.toString().stripTrailing(); }
                assertValue(left, right, "row " + r + " column " + c);
            }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.sqlserver.url", matches = "jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+.*")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_SQLSERVER_TEST_PASSWORD", matches = ".+")
    void sqlserverRejectedColumnsBlockBeforeAnyTargetObjectExists() throws Exception {
        sql("sqlserver", "CREATE TABLE [dbo].[" + sqlserverTable + "](id bigint NOT NULL, label int NULL,"
                + " value datetimeoffset(7) NULL, PRIMARY KEY(id))");
        var columns = runtime.metadata(source("sqlserver"), SQLSERVER)
                .queryTableColumnMetaWithNativeEvidence("dbo", sqlserverTable);
        var failure = assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                runtime.products().metadata(POSTGRESQL), columns, List.of("id"), pgSchema, "copied", null, false, Map.of()));
        String message = failure.getMessage();
        assertTrue(message.contains("value [SQLSERVER") && message.contains("] UNSUPPORTED [BLOCKER:VALUE_CODEC_UNSUPPORTED"), message);
        assertFalse(message.contains("LABEL ["), message);
        assertFalse(tableExists("postgres", pgSchema, "copied"), "no target object may exist after a rejected plan");
    }

    // ---------------------------------------------------------------- helpers

    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, ProductTypeEnum targetProduct,
                               String targetName) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(sourceProduct); source.setDriverClassName(sourceProduct.getDriveClassName());
        source.setUrl(url(sourceName)); source.setUsername(user(sourceName)); source.setPassword(password(sourceName)); source.setPoolSize(1);
        if (sourceProduct == SQLSERVER) {
            source.setSourceSchema("dbo"); source.setSourceIncludes(sqlserverTable);
            source.setRegexTableMapper(List.of(new PatternMapper("^" + sqlserverTable + "$", "copied")));
        } else {
            source.setSourceSchema(pgSchema); source.setSourceIncludes("source");
            source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copied")));
        }
        source.setFetchSize(2); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(targetProduct);
        target.setDriverClassName(targetProduct.getDriveClassName()); target.setPoolSize(1);
        target.setUrl(url(targetName)); target.setUsername(user(targetName)); target.setPassword(password(targetName));
        target.setTargetSchema(targetProduct == POSTGRESQL ? pgSchema : "dbo");
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

    private List<String> sqlserverColumns(String schemaName, String table) throws SQLException {
        var result = new ArrayList<String>();
        String sql = "SELECT c.name, CASE WHEN t.name IN ('decimal','numeric') THEN t.name + '(' + CAST(c.precision AS varchar(10)) + ',' + CAST(c.scale AS varchar(10)) + ')'"
                + " WHEN t.name IN ('char','varchar','binary','varbinary') THEN t.name + '(' + CASE WHEN c.max_length=-1 THEN 'max' ELSE CAST(c.max_length AS varchar(10)) END + ')'"
                + " WHEN t.name IN ('nchar','nvarchar') THEN t.name + '(' + CASE WHEN c.max_length=-1 THEN 'max' ELSE CAST(c.max_length/2 AS varchar(10)) END + ')'"
                + " WHEN t.name IN ('datetime2','time','datetimeoffset') THEN t.name + '(' + CAST(c.scale AS varchar(10)) + ')'"
                + " WHEN t.name='float' THEN 'float(' + CAST(c.precision AS varchar(10)) + ')' ELSE t.name END AS native_type, c.is_nullable"
                + " FROM sys.columns c JOIN sys.types t ON t.user_type_id=c.user_type_id JOIN sys.tables tb ON tb.object_id=c.object_id"
                + " JOIN sys.schemas s ON s.schema_id=tb.schema_id WHERE s.name=? AND tb.name=? ORDER BY c.column_id";
        try (var c = open("sqlserver"); var s = c.prepareStatement(sql)) {
            s.setString(1, schemaName); s.setString(2, table);
            try (var r = s.executeQuery()) { while (r.next()) result.add(r.getString(1) + "|" + r.getString(2) + "|" + r.getBoolean(3)); }
        }
        return result;
    }

    private List<String> sqlserverPrimaryKey(String schemaName, String table) throws SQLException {
        var result = new ArrayList<String>();
        String sql = "SELECT c.name FROM sys.indexes i JOIN sys.index_columns ic ON ic.object_id=i.object_id AND ic.index_id=i.index_id"
                + " JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id JOIN sys.tables tb ON tb.object_id=i.object_id"
                + " JOIN sys.schemas s ON s.schema_id=tb.schema_id WHERE s.name=? AND tb.name=? AND i.is_primary_key=1 ORDER BY ic.key_ordinal";
        try (var c = open("sqlserver"); var s = c.prepareStatement(sql)) {
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
        return System.getProperty("datahub.test." + name + ".user", name.equals("sqlserver") ? "sa" : "dataforge_test");
    }
    private static String password(String name) {
        return name.equals("sqlserver") ? System.getenv("DATAHUB_SQLSERVER_TEST_PASSWORD") : System.getProperty("datahub.test." + name + ".password", "");
    }
}
