package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.product.sybase.SybaseFamilyLogicalTypeMapper;
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
 * Sybase ASE 16.0 SP02 PL02 (jconn4) logical type mapping: golden tables plus real
 * Sybase &#8596; PostgreSQL 16 round trips. Every Sybase fact was probed on the real server.
 */
@Timeout(300)
class LogicalTypeMappingSybaseTest {
    private static final DbSwitchProductRegistry REGISTRY = DbSwitchProductRegistry.discover();
    private static final LogicalTypeMappings MAPPINGS = REGISTRY.typeMappings();
    private final String pgSchema = "sfam_y_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    private final String sybaseTable = "sfam_src_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final List<Runnable> cleanup = new ArrayList<>();
    private DbSwitchRuntime runtime;

    @BeforeAll static void driver() throws Exception {
        // jconn4 does not self-register; the fixture registers it once for DriverManager pools.
        DriverManager.registerDriver((Driver) Class.forName("com.sybase.jdbc4.jdbc.SybDriver").getDeclaredConstructor().newInstance());
    }

    @BeforeEach void setup() throws Exception {
        runtime = DbSwitchRuntime.standalone();
        if (System.getProperty("datahub.test.sybase.url") != null) {
            cleanup.add(() -> quietly("sybase", "DROP TABLE [dbo].[" + sybaseTable + "]"));
            cleanup.add(() -> quietly("sybase", "DROP TABLE [dbo].[copied]"));
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

    @Test void registryCarriesTheSybaseMapper() {
        var mapper = REGISTRY.typeMapper(SYBASE).orElseThrow();
        assertInstanceOf(SybaseFamilyLogicalTypeMapper.class, mapper);
        assertEquals(SybaseFamilyLogicalTypeMapper.Flavor.SYBASE, ((SybaseFamilyLogicalTypeMapper) mapper).flavor());
        assertEquals(SYBASE, mapper.product());
        assertTrue(MAPPINGS.supports(SYBASE, POSTGRESQL));
        assertTrue(MAPPINGS.supports(POSTGRESQL, SYBASE));
        assertFalse(MAPPINGS.supports(SYBASE, SYBASE));
    }

    // ---------------------------------------------------------------- golden: Sybase source -> PostgreSQL

    @ParameterizedTest(name = "{0} -> POSTGRESQL")
    @CsvSource(delimiter = ';', value = {
            "tinyint; EXACT|smallint|", "smallint; EXACT|smallint|", "int; EXACT|integer|", "bigint; EXACT|bigint|",
            "bit; EXACT|boolean|",
            "decimal(38,10); EXACT|numeric(38,10)|", "numeric(12,2); EXACT|numeric(12,2)|",
            "money; EXACT|numeric(19,4)|", "smallmoney; EXACT|numeric(10,4)|",
            "float; EXACT|double precision|", "double precision; EXACT|double precision|", "real; EXACT|real|",
            "date; EXACT|date|", "time; EXACT|time(0) without time zone|",
            "datetime; EXACT|timestamp(3) without time zone|",
            "smalldatetime; EXACT|timestamp(0) without time zone|",
            "bigdatetime; EXACT|timestamp(6) without time zone|",
            "bigtime; EXACT|time(6) without time zone|",
            "char(10); UNSUPPORTED|-|TYPE_UNMAPPED",
            "varchar(40); UNSUPPORTED|-|TYPE_UNMAPPED",
            "unichar(10); EXACT|character(10) COLLATE \"C\"|",
            "univarchar(40); LOSSY|character varying(40) COLLATE \"C\"|PAD_SPACE_EQUALITY_CHANGED",
            "text; UNSUPPORTED|-|TYPE_UNMAPPED",
            "unitext; LOSSY|text COLLATE \"C\"|PAD_SPACE_EQUALITY_CHANGED",
            "binary(8); EXACT|bytea|", "varbinary(16); EXACT|bytea|", "image; EXACT|bytea|"})
    void sybaseSourceToPostgresGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                SqlFamilyMappingFixtures.family(SYBASE, "v", nativeType), false)));
    }

    @Test void sybaseCharacterKeysAreBlocked() {
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                SqlFamilyMappingFixtures.family(SYBASE, "k", "varchar(40)"), true)));
        assertEquals("EXACT|bytea|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL,
                SqlFamilyMappingFixtures.family(SYBASE, "k", "varbinary(16)"), true)));
    }

    @Test void sybaseDriverFactsAreEnforced() {
        var wrongFloat = SqlFamilyMappingFixtures.family(SYBASE, "v", "float"); wrongFloat.setFieldType(Types.REAL);
        assertEquals("UNSUPPORTED|-|DRIVER_METADATA_INCONSISTENT",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, wrongFloat, false)));
        // The JDBC product version alone is not the pinned @@version banner: that provenance is rejected.
        var foreign = SqlFamilyMappingFixtures.family(SYBASE, "v", "int");
        SqlFamilyMappingFixtures.withFacts(foreign, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                new cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance(SYBASE, "Adaptive Server Enterprise", "16.0.02.02",
                        "SYBASE16_CATALOG_V1", java.time.Instant.EPOCH), f.nativeType(), f.defaultExpression(), f.nullable(),
                f.numericPrecision(), f.numericScale(), f.characterLength(), f.identity(), f.generatedExpression(),
                f.charset(), f.collation(), f.sequence()));
        assertEquals("UNSUPPORTED|-|SOURCE_PROVENANCE_UNVERIFIED",
                PgFamilyMappingFixtures.outcome(MAPPINGS.map(POSTGRESQL, foreign, false)));
    }

    // ---------------------------------------------------------------- golden: PostgreSQL source -> Sybase

    @ParameterizedTest(name = "{0} -> SYBASE")
    @CsvSource(delimiter = ';', value = {
            "smallint; EXACT|smallint|", "integer; EXACT|int|", "bigint; EXACT|bigint|",
            "numeric(38,10); EXACT|decimal(38,10)|", "numeric(19,4); EXACT|decimal(19,4)|",
            "numeric; UNSUPPORTED|-|UNBOUNDED_NUMERIC_UNMAPPED",
            "numeric(39,0); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "numeric(10,12); UNSUPPORTED|-|NUMERIC_PRECISION_EXCEEDS_TARGET",
            "real; LOSSY|real|NEGATIVE_ZERO_NOT_PRESERVED",
            "double precision; LOSSY|float|NEGATIVE_ZERO_NOT_PRESERVED",
            "boolean; UNSUPPORTED|-|BOOLEAN_NULL_UNAVAILABLE",
            "character(5); LOSSY|univarchar(5)|PAD_SPACE_EQUALITY_CHANGED",
            "character varying(40); LOSSY|univarchar(40)|PAD_SPACE_EQUALITY_CHANGED",
            "text; LOSSY|unitext|PAD_SPACE_EQUALITY_CHANGED",
            "bytea; EXACT|image|",
            "date; LOSSY|date|DATE_RANGE_NOT_REPRESENTABLE",
            "time(0) without time zone; LOSSY|time|TIME_24_00_WRAPPED",
            "time(3) without time zone; LOSSY|time|TIME_24_00_WRAPPED,TIME_FRACTION_TRUNCATED",
            "time with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "timestamp(0) without time zone; LOSSY|datetime|DATE_RANGE_NOT_REPRESENTABLE",
            "timestamp(6) without time zone; LOSSY|bigdatetime|DATE_RANGE_NOT_REPRESENTABLE",
            "timestamp(6) with time zone; UNSUPPORTED|-|TIME_ZONE_SEMANTICS_UNMAPPED",
            "uuid; UNSUPPORTED|-|TYPE_UNMAPPED",
            "json; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED", "jsonb; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "integer[]; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED", "interval; UNSUPPORTED|-|VALUE_CODEC_UNSUPPORTED",
            "xml; UNSUPPORTED|-|TYPE_UNMAPPED", "money; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED",
            "inet; UNSUPPORTED|-|NATIVE_TYPE_UNRECOGNIZED"})
    void postgresSourceToSybaseGolden(String nativeType, String expected) {
        assertEquals(expected, PgFamilyMappingFixtures.outcome(MAPPINGS.map(SYBASE,
                LogicalTypeMappingTest.pg("v", nativeType, MysqlFamilyExtendedFixtures.pgJdbc(nativeType)), false)));
    }

    @Test void sybaseTargetNotNullBooleanAndCharacterKeys() {
        assertEquals("UNSUPPORTED|-|TEXT_KEY_EQUALITY_UNPROVEN", PgFamilyMappingFixtures.outcome(MAPPINGS.map(SYBASE,
                LogicalTypeMappingTest.pg("k", "character varying(20)", Types.VARCHAR), true)));
        var notNull = LogicalTypeMappingTest.pg("v", "boolean", Types.BIT);
        SqlFamilyMappingFixtures.withFacts(notNull, f -> new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence(
                f.provenance(), f.nativeType(), f.defaultExpression(), MetadataValue.value(false), f.numericPrecision(),
                f.numericScale(), f.characterLength(), f.identity(), f.generatedExpression(), f.charset(), f.collation(), f.sequence()));
        notNull.setNullable(false);
        assertEquals("EXACT|bit|", PgFamilyMappingFixtures.outcome(MAPPINGS.map(SYBASE, notNull, false)));
    }

    // ---------------------------------------------------------------- real Sybase <-> PostgreSQL 16 round trips

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.sybase.url", matches = "jdbc:sybase:Tds:127[.]0[.]0[.]1:[0-9]+/tempdb")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_SYBASE_TEST_PASSWORD", matches = ".+")
    void sybaseSourceCreatesExactPostgresColumnsAndCopiesEveryValue() throws Exception {
        sql("sybase", "CREATE TABLE [dbo].[" + sybaseTable + "]([k] bigint NOT NULL, [v_tiny] tinyint NULL, [v_small] smallint NULL,"
                + " [v_int] int NULL, [v_dec] decimal(38,10) NULL, [v_num] numeric(12,2) NULL, [v_money] money NULL,"
                + " [v_smallmoney] smallmoney NULL, [v_real] real NULL, [v_float] float NULL, [v_date] date NULL,"
                + " [v_dt] datetime NULL, [v_bdt] bigdatetime NULL, [v_bt] bigtime NULL,"
                + " [v_bin] binary(8) NULL, [v_vbin] varbinary(16) NULL, [v_img] image NULL, [v_bit] bit NOT NULL, PRIMARY KEY([k]))");
        sql("sybase", "INSERT INTO [dbo].[" + sybaseTable + "] VALUES (-9223372036854775808, 0, -32768, -2147483648,"
                + " -1234567890123456789012345678.1234567890, -1234567890.12, -922337203685477.5808, -214748.3648, 3.5, -1.5,"
                + " '0001-01-01', '2026-10-06 10:00:00.003', '1753-01-01 00:00:00.000000', '23:59:59.123000', 0xAB, 0xDEADBEEF, 0x010203, 0)");
        sql("sybase", "INSERT INTO [dbo].[" + sybaseTable + "] VALUES (9223372036854775807, 255, 32767, 2147483647,"
                + " 1234567890123456789012345678.1234567890, 1234567890.12, 922337203685477.5807, 214748.3647, -0.0, 0.0,"
                + " '9999-12-31', '9999-12-31 23:59:59.997', '9999-12-31 23:59:59.999999', '00:00:00.004000', 0x0102030405060708, 0x00, 0x00, 1)");
        sql("sybase", "INSERT INTO [dbo].[" + sybaseTable + "]([k],[v_bit]) VALUES (0, 0)");
        assertSuccess(run(SYBASE, "sybase", POSTGRESQL, "postgres"));
        assertEquals(List.of("k|bigint|true", "v_tiny|smallint|false", "v_small|smallint|false", "v_int|integer|false",
                "v_dec|numeric(38,10)|false", "v_num|numeric(12,2)|false", "v_money|numeric(19,4)|false",
                "v_smallmoney|numeric(10,4)|false", "v_real|real|false", "v_float|double precision|false",
                "v_date|date|false", "v_dt|timestamp(3) without time zone|false", "v_bdt|timestamp(6) without time zone|false",
                "v_bt|time(6) without time zone|false",
                "v_bin|bytea|false", "v_vbin|bytea|false", "v_img|bytea|false", "v_bit|boolean|true"),
                columns("postgres", "pg_catalog", pgSchema, "copied"));
        assertEquals(List.of("k"), primaryKey("postgres", "pg_catalog", pgSchema, "copied"));
        String select = "SELECT k, v_tiny, v_small, v_int, v_dec, v_num, v_money, v_smallmoney, v_real, v_float, v_date, v_dt,"
                + " v_bdt, v_bt, v_bin, v_vbin, v_img, CASE WHEN v_bit IS NULL THEN NULL ELSE CAST(v_bit AS int) END FROM {t} ORDER BY 1";
        // The PG target's TIME write binding keeps milliseconds; the comparison reads the target as text for exactness.
        String targetSelect = "SELECT k, v_tiny, v_small, v_int, v_dec, v_num, v_money, v_smallmoney, v_real, v_float, v_date, v_dt,"
                + " v_bdt, v_bt::text, v_bin, v_vbin, v_img, CASE WHEN v_bit IS NULL THEN NULL ELSE CAST(v_bit AS int) END"
                + " FROM " + pgSchema + ".copied ORDER BY 1";
        var source = rows("sybase", select.replace("{t}", "[dbo].[" + sybaseTable + "]"));
        var target = rows("postgres", targetSelect);
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                // bigtime reads as a Timestamp with the 0001-01-01 carrier date on Sybase; both sides canonicalize to
                // HH:mm:ss.SSSSSS (the target text keeps at most milliseconds).
                if (c == 13 && left != null) {
                    var time = left instanceof java.sql.Timestamp ts ? ts.toLocalDateTime().toLocalTime()
                            : left instanceof java.sql.Time t ? t.toLocalTime() : null;
                    String canonical = String.format("%02d:%02d:%02d.%06d", time.getHour(), time.getMinute(), time.getSecond(),
                            time.getNano() / 1000);
                    String targetText = right.toString();
                    if (targetText.indexOf('.') < 0) targetText += ".";
                    targetText += "0".repeat(6 - (targetText.length() - targetText.indexOf('.') - 1));
                    assertEquals(canonical, targetText, "row " + r + " column " + c);
                    continue;
                }
                // datetime values are 1/300s ticks on Sybase and millisecond-rounded on PostgreSQL; compare the
                // tick index and the whole-second wall time (the verified domain granularity).
                if (c == 11 && left != null) {
                    long leftTick = Math.round(((java.sql.Timestamp) left).getNanos() / 3_333_333.0);
                    long rightTick = Math.round(((java.sql.Timestamp) right).getNanos() / 3_333_333.0);
                    assertEquals(((java.sql.Timestamp) left).toLocalDateTime().withNano(0),
                            ((java.sql.Timestamp) right).toLocalDateTime().withNano(0), "row " + r + " column " + c);
                    assertEquals(leftTick, rightTick, "row " + r + " column " + c);
                    continue;
                }
                // SybTimestamp.toString truncates its display to milliseconds; compare the exact nanos instead.
                if (c == 12 && left != null) {
                    assertEquals(((java.sql.Timestamp) left).toLocalDateTime(), ((java.sql.Timestamp) right).toLocalDateTime(),
                            "row " + r + " column " + c);
                    continue;
                }
                assertValue(left, right, "row " + r + " column " + c);
            }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.sybase.url", matches = "jdbc:sybase:Tds:127[.]0[.]0[.]1:[0-9]+/tempdb")
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_SYBASE_TEST_PASSWORD", matches = ".+")
    void postgresSourceCreatesExactSybaseColumnsAndCopiesEveryValue() throws Exception {
        sql("postgres", "CREATE TABLE " + pgSchema + ".source(k bigint NOT NULL, v_int integer, v_small smallint,"
                + " v_dec numeric(38,10), v_bool boolean NOT NULL,"
                + " v_char character(5) COLLATE \"C\", v_bytes bytea, PRIMARY KEY(k))");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (-9223372036854775808, -2147483648, -32768,"
                + " -1.5, false, 'ab', '\\xdeadbeef')");
        sql("postgres", "INSERT INTO " + pgSchema + ".source VALUES (9223372036854775807, 2147483647, 32767,"
                + " 9999999999999999999999999999.9999999999, true, 'abcde', '\\x00')");
        sql("postgres", "INSERT INTO " + pgSchema + ".source(k, v_bool) VALUES (0, false)");
        assertSuccess(run(POSTGRESQL, "postgres", SYBASE, "sybase"));
        assertEquals(List.of("k|bigint|false", "v_int|int|true", "v_small|smallint|true", "v_dec|decimal(38,10)|true",
                "v_bool|bit|false", "v_char|univarchar(5)|true", "v_bytes|image|true"),
                sybaseColumns("dbo", "copied"));
        String select = "SELECT k, v_int, v_small, v_dec, CASE WHEN v_bool IS NULL THEN NULL ELSE CAST(v_bool AS int) END,"
                + " v_char, v_bytes FROM {t} ORDER BY 1";
        var source = rows("postgres", select.replace("{t}", pgSchema + ".source"));
        var target = rows("sybase", select.replace("{t}", "[dbo].[copied]"));
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (c == 5 && left != null) { left = left.toString().stripTrailing(); right = right.toString().stripTrailing(); }
                assertValue(left, right, "row " + r + " column " + c);
            }
    }

    // ---------------------------------------------------------------- helpers

    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, ProductTypeEnum targetProduct,
                               String targetName) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(sourceProduct); source.setDriverClassName(sourceProduct.getDriveClassName());
        source.setUrl(url(sourceName)); source.setUsername(user(sourceName)); source.setPassword(password(sourceName)); source.setPoolSize(1);
        if (sourceProduct == SYBASE) {
            source.setSourceSchema("dbo"); source.setSourceIncludes(sybaseTable);
            source.setRegexTableMapper(List.of(new PatternMapper("^" + sybaseTable + "$", "copied")));
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

    private List<String> sybaseColumns(String schemaName, String table) throws SQLException {
        var result = new ArrayList<String>();
        String sql = "SELECT c.name,"
                + " CASE WHEN t.name IN ('decimal','numeric') THEN t.name + '(' + CAST(c.prec AS varchar(10)) + ',' + CAST(c.scale AS varchar(10)) + ')'"
                + " WHEN t.name IN ('char','varchar','binary','varbinary') THEN t.name + '(' + CAST(c.length AS varchar(10)) + ')'"
                + " WHEN t.name IN ('unichar','univarchar') THEN t.name + '(' + CAST(c.length/2 AS varchar(10)) + ')' ELSE t.name END AS native_type,"
                + " CASE WHEN (c.status & 8) != 0 THEN 1 ELSE 0 END AS is_nullable"
                + " FROM syscolumns c JOIN systypes t ON t.usertype=c.usertype WHERE c.id=OBJECT_ID('" + schemaName + "." + table + "') ORDER BY c.colid";
        try (var c = open("sybase"); var s = c.prepareStatement(sql)) {
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
        return System.getProperty("datahub.test." + name + ".user", name.equals("sybase") ? "sa" : "dataforge_test");
    }
    private static String password(String name) {
        return name.equals("sybase") ? System.getenv("DATAHUB_SYBASE_TEST_PASSWORD") : System.getProperty("datahub.test." + name + ".password", "");
    }
}
