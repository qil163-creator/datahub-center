package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-server cross-product structure migration through the public MigrationService: automatic target creation from
 * native source facts, FULL copy, then independent target catalog and value read-back. Rejected mappings must fail
 * before any target object exists. Only uniquely named schemas/databases owned by the test are created and dropped.
 */
@Timeout(180)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationCrossProductSchemaTest {
    private final String schema = "xprod_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    private final List<Runnable> cleanup = new ArrayList<>();
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        runtime = DbSwitchRuntime.standalone();
        sql("postgres", "CREATE SCHEMA " + schema);
        cleanup.add(() -> quietly("postgres", "DROP SCHEMA IF EXISTS " + schema + " CASCADE"));
    }

    @AfterEach void teardown() {
        try { Collections.reverse(cleanup); cleanup.forEach(Runnable::run); }
        finally { if (runtime != null) runtime.close(); }
    }

    @ParameterizedTest @ValueSource(strings = {"mysql", "mariadb", "tidb"})
    void postgresSourceCreatesExactMysqlFamilyColumnsAndCopiesEveryValue(String product) throws Exception {
        family(product);
        // PostgreSQL numeric(p,s) admits NaN: exact for MySQL/TiDB (the write fails loudly), LOSSY for MariaDB
        // (its driver batches NaN as 0), so MariaDB gets the uuid column instead of the decimal columns.
        boolean maria = product.equals("mariadb");
        String max = "9".repeat(35) + "." + "9".repeat(30);
        sql("postgres", "CREATE TABLE " + schema + ".source(k_small smallint NOT NULL, k_big bigint NOT NULL, v_int integer,"
                + (maria ? " v_uuid uuid," : " v_dec numeric(65,30), v_money numeric(12,2) NOT NULL,")
                + " v_bool boolean, v_name varchar(40), v_text text, v_code char(5), v_bytes bytea, PRIMARY KEY(k_big, k_small))");
        sql("postgres", "INSERT INTO " + schema + ".source VALUES (-32768, -9223372036854775808, -2147483648, "
                + (maria ? "'A0EEBC99-9C0B-4EF8-BB6D-6BB9BD380A11'" : "-" + max + ", -9999999999.99")
                + ", false, 'a  ', repeat('中😀', 5000) || E'\\n\\t\\\\', 'ab', decode(" + allBytes() + ",'hex'))");
        sql("postgres", "INSERT INTO " + schema + ".source VALUES (32767, 9223372036854775807, 2147483647, "
                + (maria ? "'ffffffff-ffff-ffff-ffff-ffffffffffff'" : max + ", 9999999999.99") + ", true, '😀zé中', '', 'abcde', '\\x')");
        sql("postgres", "INSERT INTO " + schema + ".source(k_small, k_big" + (maria ? "" : ", v_money") + ") VALUES (0, 0" + (maria ? "" : ", 0.00") + ")");
        var result = run(ProductTypeEnum.POSTGRESQL, "postgres", schema, familyType(product), product, schema, true, false);
        assertSuccess(result);
        String noPad = maria ? "utf8mb4_nopad_bin" : "utf8mb4_0900_bin";
        List<String> expected = new ArrayList<>(List.of(
                "k_small|" + width(product, "smallint", "6") + "|NO|null|null", "k_big|" + width(product, "bigint", "20") + "|NO|null|null",
                "v_int|" + width(product, "int", "11") + "|YES|null|null"));
        expected.addAll(maria ? List.of("v_uuid|uuid|YES|null|null")
                : List.of("v_dec|decimal(65,30)|YES|null|null", "v_money|decimal(12,2)|NO|null|null"));
        expected.addAll(List.of("v_bool|tinyint(1)|YES|null|null", "v_name|varchar(40)|YES|utf8mb4|" + noPad,
                "v_text|longtext|YES|utf8mb4|" + noPad, "v_code|char(5)|YES|utf8mb4|utf8mb4_bin", "v_bytes|longblob|YES|null|null"));
        assertEquals(expected, familyColumns(product, schema, "copied"));
        assertEquals(List.of("k_big", "k_small"), familyPrimaryKey(product, schema, "copied"));
        String columns = "k_small,k_big,v_int," + (maria ? "v_uuid," : "v_dec,v_money,") + "v_bool,v_name,v_text,v_code,v_bytes";
        int fixedChar = maria ? 7 : 8;
        var source = rows("postgres", "SELECT " + columns + " FROM " + schema + ".source ORDER BY k_big, k_small");
        var target = rows(product, "SELECT " + columns + " FROM `" + schema + "`.`copied` ORDER BY k_big, k_small");
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++) {
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                // character(n) and CHAR(n) both treat trailing pad spaces as insignificant; PostgreSQL returns them padded.
                if (c == fixedChar && left != null) { left = left.toString().stripTrailing(); right = right.toString().stripTrailing(); }
                assertValue(left, right, "row " + r + " column " + c);
            }
        }
        try (var c = open(product); var s = c.createStatement(); var r = s.executeQuery("SELECT CAST(v_bool AS SIGNED) FROM `" + schema + "`.`copied` ORDER BY k_big")) {
            assertTrue(r.next()); assertEquals(0, r.getInt(1)); assertTrue(r.next()); r.getInt(1); assertTrue(r.wasNull());
            assertTrue(r.next()); assertEquals(1, r.getInt(1));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"mysql", "tidb"})
    void mysqlFamilySourceCreatesExactPostgresColumnsWithDomainChecksAndCopiesEveryValue(String product) throws Exception {
        family(product);
        String db = "`" + schema + "`";
        sql(product, "CREATE TABLE " + db + ".source(k_u bigint unsigned NOT NULL, k_t tinyint NOT NULL, v_tu tinyint unsigned,"
                + " v_su smallint unsigned, v_m mediumint, v_mu mediumint unsigned, v_iu int unsigned, v_dec decimal(65,30) unsigned,"
                + " v_d2 decimal(10,2) NOT NULL, v_dbl double, v_bin binary(4), v_vb varbinary(9), v_blob blob, v_date date,"
                + " v_dt datetime(6), v_time time(3), v_json json, PRIMARY KEY(k_u, k_t)) ENGINE=InnoDB");
        String max = "9".repeat(35) + "." + "9".repeat(30);
        sql(product, "INSERT INTO " + db + ".source VALUES (18446744073709551615, -128, 255, 65535, -8388608, 16777215, 4294967295, "
                + max + ", -99999999.99, -0e0, x'0102', x'00ff', x'" + allBytesHex() + "', '1000-01-01', '1919-04-13 00:30:00.123456',"
                + " '23:59:59.999', '{\"b\": {\"c\": null}, \"a\": [1, 2.5, \"x\", 12345678901234567890]}')");
        sql(product, "INSERT INTO " + db + ".source VALUES (0, 127, 0, 0, 8388607, 0, 0, 0.000000000000000000000000000001, 99999999.99,"
                + " 1.7976931348623157e308, x'ffffffff', x'', x'', '9999-12-31', '9999-12-31 23:59:59.999999', '00:00:00', '[]')");
        sql(product, "INSERT INTO " + db + ".source(k_u, k_t, v_d2) VALUES (9007199254740993, 0, 0)");
        var result = run(familyType(product), product, schema, ProductTypeEnum.POSTGRESQL, "postgres", schema, true, false);
        assertSuccess(result);
        assertEquals(List.of("k_u|numeric(20,0)|true", "k_t|smallint|true", "v_tu|smallint|false", "v_su|integer|false",
                "v_m|integer|false", "v_mu|integer|false", "v_iu|bigint|false", "v_dec|numeric(65,30)|false",
                "v_d2|numeric(10,2)|true", "v_dbl|double precision|false", "v_bin|bytea|false", "v_vb|bytea|false",
                "v_blob|bytea|false", "v_date|date|false", "v_dt|timestamp(6) without time zone|false",
                "v_time|time(3) without time zone|false", "v_json|jsonb|false"), postgresColumns(schema, "copied"));
        assertEquals(List.of("k_u", "k_t"), postgresPrimaryKey(schema, "copied"));
        // The emulated MySQL domains are enforced by the target, not only by the copy.
        for (String assignment : List.of("v_tu = 256", "v_su = -1", "v_iu = 4294967296", "k_u = -1", "v_dec = -1", "v_bin = '\\x01'::bytea", "v_vb = decode('00112233445566778899','hex')"))
            assertCheckRejected(assignment);
        String columns = "k_u,k_t,v_tu,v_su,v_m,v_mu,v_iu,v_dec,v_d2,v_dbl,v_bin,v_vb,v_blob";
        var source = rows(product, "SELECT " + columns + ", CAST(v_date AS CHAR), DATE_FORMAT(v_dt, '%Y-%m-%d %H:%i:%s.%f'),"
                + " TIME_FORMAT(v_time, '%H:%i:%s.%f'), v_json FROM " + db + ".source ORDER BY k_u, k_t");
        var target = rows("postgres", "SELECT " + columns + ", v_date::text, to_char(v_dt, 'YYYY-MM-DD HH24:MI:SS.US'),"
                + " to_char(v_time, 'HH24:MI:SS.US'), v_json::text FROM " + schema + ".copied ORDER BY k_u, k_t");
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++) {
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (c == source.get(r).size() - 1 && left != null) assertTrue(sameJson(left.toString(), right.toString()), left + " vs " + right);
                else assertValue(left, right, "row " + r + " column " + c);
            }
        }
        if (product.equals("mysql")) {
            try (var c = open("postgres"); var s = c.createStatement(); var r = s.executeQuery("SELECT v_dbl::text FROM " + schema + ".copied WHERE k_t = -128")) {
                assertTrue(r.next()); assertEquals("-0", r.getString(1), "MySQL -0e0 DOUBLE keeps its sign in PostgreSQL");
            }
        }
    }

    @Test void mysqlToMariaDbPlannedColumnsIncludingBinaryCollationTextKeysCopyExactly() throws Exception {
        family("mysql"); family("mariadb");
        String db = "`" + schema + "`";
        sql("mysql", "CREATE TABLE " + db + ".source(k varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, n smallint NOT NULL,"
                + " v_u bigint unsigned, v_dec decimal(30,10) unsigned, v_np varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,"
                + " v_txt mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin, v_bin binary(3), v_blob longblob, v_date date,"
                + " v_dt datetime(6), v_json json, PRIMARY KEY(k, n)) ENGINE=InnoDB");
        sql("mysql", "INSERT INTO " + db + ".source VALUES ('a', 1, 9223372036854775807, 12345678901234567890.0123456789, 'x  ',"
                + " REPEAT('中😀', 3000), x'01', x'" + allBytesHex() + "', '0001-01-01', '1919-04-13 00:30:00.654321', '{\"k\": [1, \"v\"]}')");
        sql("mysql", "INSERT INTO " + db + ".source VALUES ('😀', 2, 0, 0, '', '', x'ffffff', x'', '9999-12-31', '2026-10-04 12:00:00', 'null')");
        sql("mysql", "INSERT INTO " + db + ".source(k, n) VALUES ('B', 3)");
        var mysqlMetadata = runtime.metadata(source("mysql"), ProductTypeEnum.MYSQL);
        List<ColumnDescription> columns = mysqlMetadata.queryTableColumnMetaWithNativeEvidence(schema, "source");
        List<String> keys = mysqlMetadata.queryTablePrimaryKeys(schema, "source");
        for (String statement : GenerateSqlUtils.getDDLCreateTableSQL(runtime.products().metadata(ProductTypeEnum.MARIADB), columns, keys,
                schema, "copied", null, false, Map.of())) sql("mariadb", statement);
        assertEquals(List.of("k|varchar(20)|NO|utf8mb4|utf8mb4_bin", "n|smallint(6)|NO|null|null", "v_u|bigint(20) unsigned|YES|null|null",
                "v_dec|decimal(30,10) unsigned|YES|null|null",
                "v_np|varchar(30)|YES|utf8mb4|utf8mb4_nopad_bin", "v_txt|mediumtext|YES|utf8mb4|utf8mb4_bin",
                "v_bin|binary(3)|YES|null|null", "v_blob|longblob|YES|null|null", "v_date|date|YES|null|null",
                "v_dt|datetime(6)|YES|null|null", "v_json|longtext|YES|utf8mb4|utf8mb4_bin"), familyColumns("mariadb", schema, "copied"));
        assertEquals(List.of("k", "n"), familyPrimaryKey("mariadb", schema, "copied"));
        assertSuccess(run(ProductTypeEnum.MYSQL, "mysql", schema, ProductTypeEnum.MARIADB, "mariadb", schema, true, false));
        String select = "SELECT k, n, v_u, v_dec, v_np, v_txt, v_bin, v_blob, CAST(v_date AS CHAR), DATE_FORMAT(v_dt, '%Y-%m-%d %H:%i:%s.%f'), CAST(v_json AS CHAR) FROM "
                + db + ".{table} ORDER BY n";
        var source = rows("mysql", select.replace("{table}", "source"));
        var target = rows("mariadb", select.replace("{table}", "copied"));
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (c == 10 && left != null) assertTrue(sameJson(left.toString(), right.toString()));
                else assertValue(left, right, "row " + r + " column " + c);
            }
    }

    /** LOSSY (default rejected) and UNSUPPORTED mappings fail before CREATE; diagnostics name the column, types and reason. */
    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "postgres; mysql; date; LOSSY; DATE_RANGE_NOT_REPRESENTABLE",
            "postgres; mariadb; timestamp(3); LOSSY; DATE_RANGE_NOT_REPRESENTABLE",
            "postgres; tidb; timestamp(6) with time zone; UNSUPPORTED; TIME_ZONE_SEMANTICS_UNMAPPED",
            "postgres; mysql; jsonb; UNSUPPORTED; VALUE_CODEC_UNSUPPORTED",
            "postgres; mariadb; real; LOSSY; FLOAT32_PRECISION_NOT_PRESERVED",
            "postgres; mysql; numeric; UNSUPPORTED; UNBOUNDED_NUMERIC_UNMAPPED",
            "postgres; tidb; integer[]; UNSUPPORTED; VALUE_CODEC_UNSUPPORTED",
            "postgres; mysql; uuid; UNSUPPORTED; UUID_TYPE_UNAVAILABLE",
            "postgres; mysql; time(6); LOSSY; TIME_24_00_WRAPPED",
            "postgres; mariadb; numeric(10,2); LOSSY; NAN_NOT_REPRESENTABLE",
            "postgres; tidb; varchar(10); UNSUPPORTED; TEXT_KEY_EQUALITY_UNPROVEN",
            "mysql; postgres; varchar(20); LOSSY; COLLATION_EQUALITY_CHANGED",
            "mysql; postgres; timestamp(3) NULL; UNSUPPORTED; TIMESTAMP_SESSION_ZONE_CODEC_UNVERIFIED",
            "mysql; postgres; float; LOSSY; FLOAT32_PRECISION_NOT_PRESERVED",
            "mysql; postgres; tinyint(1); UNSUPPORTED; BOOLEAN_DRIVER_COERCION",
            "tidb; postgres; enum('x','y'); UNSUPPORTED; TYPE_UNMAPPED",
            "tidb; postgres; time(6); LOSSY; TIME_FRACTION_TRUNCATED",
            "tidb; postgres; year; UNSUPPORTED; TYPE_UNMAPPED"})
    void rejectedColumnBlocksTheWholeTableBeforeAnyTargetObjectExists(String sourceName, String targetName, String type,
                                                                       String fidelity, String reason) throws Exception {
        if (!sourceName.equals("postgres")) family(sourceName);
        if (!targetName.equals("postgres")) family(targetName);
        String relation = sourceName.equals("postgres") ? schema + ".source" : "`" + schema + "`.`source`";
        boolean key = reason.equals("TEXT_KEY_EQUALITY_UNPROVEN");
        List<String> keys = key ? List.of("id", "value") : List.of("id");
        sql(sourceName, "CREATE TABLE " + relation + "(id int NOT NULL, label smallint, value " + type + (key ? " NOT NULL" : "")
                + ", PRIMARY KEY(" + String.join(", ", keys) + "))");
        boolean booleanCoercion = sourceName.equals("mysql") && type.equals("tinyint(1)")
                && reason.equals("BOOLEAN_DRIVER_COERCION");
        String sourceUrl = booleanCoercion ? mysqlTinyIntProfile(true) : url(sourceName);
        if (booleanCoercion) {
            insertTinyIntCounterexamples();
            assertTinyIntReadProfile(sourceUrl, true);
        }
        var result = run(product(sourceName), sourceName, schema, product(targetName), targetName, schema, true, false, sourceUrl);
        assertFalse(result.getIfAllSuccess().get(), "a rejected mapping must fail the table");
        assertFalse(tableExists(targetName, schema, "copied"), "no target object may exist after a rejected plan");
        if (booleanCoercion) {
            var rejected = result.getTableResultList().getFirst().getTypeMappingPlan();
            assertNotNull(rejected);
            assertEquals(cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity.UNSUPPORTED, rejected.column("value").fidelity());
            assertEquals(reason, rejected.column("value").reasonCode());
        }
        var metadata = runtime.metadata(source(sourceName, sourceUrl), product(sourceName));
        var columns = sourceName.equals("mysql") ? metadata.queryTableColumnMetaWithNativeEvidence(schema, "source")
                : metadata.queryTableColumnMeta(schema, "source");
        var failure = assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                runtime.products().metadata(product(targetName)), columns, keys, schema, "copied", null, false, Map.of()));
        String message = failure.getMessage();
        assertTrue(message.contains("value [" + product(sourceName)), message);
        assertTrue(message.contains("] " + fidelity + " "), message);
        assertTrue(message.contains(reason), message);
        assertFalse(message.contains("label ["), "exact columns are not listed as rejected: " + message);
    }

    @Test void mysqlTinyIntOneNumericProfilePreservesEveryIntegerInsteadOfCoercingNonzeroToTrue() throws Exception {
        family("mysql");
        sql("mysql", "CREATE TABLE `" + schema + "`.source(id int PRIMARY KEY, label smallint, value tinyint(1))");
        insertTinyIntCounterexamples();
        String sourceUrl = mysqlTinyIntProfile(false);
        assertTinyIntReadProfile(sourceUrl, false);
        var columns = runtime.metadata(source("mysql", sourceUrl), ProductTypeEnum.MYSQL)
                .queryTableColumnMetaWithNativeEvidence(schema, "source");
        var plan = GenerateSqlUtils.planCrossProductColumns(runtime.products().metadata(ProductTypeEnum.POSTGRESQL),
                columns, List.of("id"), cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeMappingOptions.of(
                        runtime.products().typeMappings()));
        assertTrue(plan.accepted());
        assertEquals(cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity.EXACT, plan.column("value").fidelity());
        assertSuccess(run(ProductTypeEnum.MYSQL, "mysql", schema, ProductTypeEnum.POSTGRESQL,
                "postgres", schema, true, false, sourceUrl));
        assertEquals(List.of("id|integer|true", "label|smallint|false", "value|smallint|false"), postgresColumns(schema, "copied"));
        assertEquals(List.of("id"), postgresPrimaryKey(schema, "copied"));
        try (var connection = open("postgres"); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id,value FROM " + schema + ".copied ORDER BY id")) {
            for (int i = 0; i < tinyIntCounterexamples().size(); i++) {
                assertTrue(rows.next()); assertEquals(i + 1, rows.getInt(1));
                Integer expected = tinyIntCounterexamples().get(i);
                BigDecimal actual = rows.getBigDecimal(2);
                if (expected == null) assertNull(actual);
                else assertEquals(0, BigDecimal.valueOf(expected).compareTo(actual));
            }
            assertFalse(rows.next());
        }
        var outside = assertThrows(SQLException.class, () -> sql("postgres", "UPDATE " + schema + ".copied SET value=128 WHERE id=1"));
        assertEquals("23514", outside.getSQLState(), "the generated tinyint domain must remain enforced");
    }

    private static List<Integer> tinyIntCounterexamples() { return Arrays.asList(-128, -1, 0, 1, 2, 127, null); }

    private void insertTinyIntCounterexamples() throws SQLException {
        sql("mysql", "INSERT INTO `" + schema + "`.source(id,value) VALUES(1,-128),(2,-1),(3,0),(4,1),(5,2),(6,127),(7,NULL)");
    }

    /** Driver flags belong to this connection only; preserve other configured parameters without duplicate profile keys. */
    private static String mysqlTinyIntProfile(boolean booleanCoercion) {
        String original = url("mysql");
        int delimiter = original.indexOf('?');
        String base = delimiter < 0 ? original : original.substring(0, delimiter);
        var parameters = new ArrayList<String>();
        if (delimiter >= 0) for (String parameter : original.substring(delimiter + 1).split("&")) {
            String key = parameter.split("=", 2)[0];
            if (!parameter.isEmpty() && !key.equalsIgnoreCase("tinyInt1isBit") && !key.equalsIgnoreCase("transformedBitIsBoolean"))
                parameters.add(parameter);
        }
        parameters.add("tinyInt1isBit=" + booleanCoercion);
        parameters.add("transformedBitIsBoolean=true");
        return base + '?' + String.join("&", parameters);
    }

    private void assertTinyIntReadProfile(String sourceUrl, boolean booleanCoercion) throws SQLException {
        try (var connection = DriverManager.getConnection(sourceUrl, user("mysql"), password("mysql"));
             var statement = connection.createStatement()) {
            statement.setQueryTimeout(60);
            try (var rows = statement.executeQuery("SELECT id,value,value+0 AS raw_value FROM `" + schema + "`.source ORDER BY id")) {
                var metadata = rows.getMetaData();
                assertEquals(booleanCoercion ? Types.BOOLEAN : Types.TINYINT, metadata.getColumnType(2));
                assertEquals(booleanCoercion ? "java.lang.Boolean" : "java.lang.Integer", metadata.getColumnClassName(2));
                for (int i = 0; i < tinyIntCounterexamples().size(); i++) {
                    assertTrue(rows.next()); assertEquals(i + 1, rows.getInt(1));
                    Integer expected = tinyIntCounterexamples().get(i);
                    Object actual = rows.getObject(2);
                    if (booleanCoercion) {
                        if (expected == null) assertNull(actual);
                        else {
                            assertInstanceOf(Boolean.class, actual);
                            if (expected == 2) assertEquals(Boolean.TRUE, actual, "native integer 2 is exposed as Boolean true");
                        }
                    } else assertEquals(expected, actual);
                    BigDecimal raw = rows.getBigDecimal(3);
                    if (expected == null) assertNull(raw);
                    else assertEquals(0, BigDecimal.valueOf(expected).compareTo(raw), "the native numeric value must be independently observable");
                }
                assertFalse(rows.next());
            }
        }
    }

    /** MySQL-family to MySQL-family rejections at the generator boundary, from real catalog facts. */
    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "mariadb; double; LOSSY; NEGATIVE_ZERO_NOT_PRESERVED",
            "tidb; varchar(10) CHARACTER SET latin1; UNSUPPORTED; CHARSET_UNVERIFIED",
            "mariadb; varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; LOSSY; COLLATION_EQUALITY_UNVERIFIED",
            "tidb; timestamp(6) NULL; UNSUPPORTED; TIMESTAMP_SESSION_ZONE_CODEC_UNVERIFIED",
            "mariadb; time(6); LOSSY; TIME_FRACTION_TRUNCATED",
            "tidb; float; LOSSY; FLOAT32_PRECISION_NOT_PRESERVED"})
    void mysqlSourceRejectionsTowardOtherFamilyProducts(String targetName, String type, String fidelity, String reason) throws Exception {
        family("mysql"); family(targetName);
        sql("mysql", "CREATE TABLE `" + schema + "`.`source`(id int PRIMARY KEY, value " + type + ")");
        var columns = runtime.metadata(source("mysql"), ProductTypeEnum.MYSQL).queryTableColumnMetaWithNativeEvidence(schema, "source");
        var failure = assertThrows(UnsupportedOperationException.class, () -> GenerateSqlUtils.getDDLCreateTableSQL(
                runtime.products().metadata(product(targetName)), columns, List.of("id"), schema, "copied", null, false, Map.of()));
        String message = failure.getMessage();
        assertTrue(message.contains("value [MYSQL") && message.contains("] " + fidelity + " ") && message.contains(reason), message);
        assertFalse(tableExists(targetName, schema, "copied"));
    }

    @Test void rejectedPlanNeverDropsAnExistingTarget() throws Exception {
        family("mysql");
        sql("postgres", "CREATE TABLE " + schema + ".source(id int PRIMARY KEY, value date)");
        sql("mysql", "CREATE TABLE `" + schema + "`.`copied`(keep int)");
        sql("mysql", "INSERT INTO `" + schema + "`.`copied` VALUES (77)");
        var result = run(ProductTypeEnum.POSTGRESQL, "postgres", schema, ProductTypeEnum.MYSQL, "mysql", schema, true, true);
        assertFalse(result.getIfAllSuccess().get());
        var kept = rows("mysql", "SELECT keep FROM `" + schema + "`.`copied`");
        assertEquals(1, kept.size()); assertEquals(0, new BigDecimal("77").compareTo((BigDecimal) kept.getFirst().getFirst()));
    }

    /**
     * NaN is in the source domain of numeric(p,s). Connector/J (MySQL, TiDB) fails the write loudly; MariaDB Connector/J
     * batches would store 0, so that mapping is LOSSY and rejected before CREATE.
     */
    @ParameterizedTest @ValueSource(strings = {"mysql", "mariadb", "tidb"})
    void postgresNaNIsRejectedLoudlyByMysqlFamilyTargets(String product) throws Exception {
        family(product);
        sql("postgres", "CREATE TABLE " + schema + ".source(id int PRIMARY KEY, value numeric(10,2))");
        sql("postgres", "INSERT INTO " + schema + ".source VALUES (1, 1.25), (2, 'NaN')");
        var result = run(ProductTypeEnum.POSTGRESQL, "postgres", schema, familyType(product), product, schema, true, false);
        assertFalse(result.getIfAllSuccess().get());
        if (product.equals("mariadb")) { assertFalse(tableExists(product, schema, "copied")); return; }
        assertTrue(tableExists(product, schema, "copied"));
        assertTrue(rows(product, "SELECT id FROM `" + schema + "`.`copied` WHERE id = 2").isEmpty());
    }

    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, String sourceSchema, ProductTypeEnum targetProduct,
                               String targetName, String targetSchema, boolean insert, boolean drop) throws Exception {
        return run(sourceProduct, sourceName, sourceSchema, targetProduct, targetName, targetSchema, insert, drop, url(sourceName));
    }

    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, String sourceSchema, ProductTypeEnum targetProduct,
                               String targetName, String targetSchema, boolean insert, boolean drop, String sourceUrl) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(sourceProduct); source.setDriverClassName(sourceProduct.getDriveClassName());
        source.setUrl(sourceUrl); source.setUsername(user(sourceName)); source.setPassword(password(sourceName)); source.setPoolSize(1);
        source.setSourceSchema(sourceSchema); source.setSourceIncludes("source"); source.setFetchSize(2);
        source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copied"))); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(targetProduct);
        target.setDriverClassName(targetProduct.getDriveClassName()); target.setPoolSize(1);
        target.setUrl(url(targetName)); target.setUsername(user(targetName)); target.setPassword(password(targetName));
        target.setTargetSchema(targetSchema); target.setWriterEngineInsert(insert); target.setTargetDrop(drop);
        return new MigrationService(request, ignored -> { }, null, runtime).run();
    }

    private javax.sql.DataSource source(String name) {
        return source(name, url(name));
    }

    private javax.sql.DataSource source(String name, String sourceUrl) {
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(sourceUrl, user(name), password(name));
        return ds;
    }

    private void family(String product) throws Exception {
        Assumptions.assumeTrue(url(product) != null, product + " fixture is not configured");
        sql(product, "CREATE DATABASE `" + schema + "`");
        cleanup.add(() -> quietly(product, "DROP DATABASE IF EXISTS `" + schema + "`"));
    }

    private List<String> familyColumns(String product, String database, String table) throws SQLException {
        var result = new ArrayList<String>();
        try (var c = open(product); var s = c.prepareStatement("SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, CHARACTER_SET_NAME, COLLATION_NAME"
                + " FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
            s.setString(1, database); s.setString(2, table);
            try (var r = s.executeQuery()) {
                while (r.next()) result.add(r.getString(1) + "|" + r.getString(2).toLowerCase(Locale.ROOT) + "|" + r.getString(3)
                        + "|" + r.getString(4) + "|" + r.getString(5));
            }
        }
        return result;
    }

    private List<String> familyPrimaryKey(String product, String database, String table) throws SQLException {
        var result = new ArrayList<String>();
        try (var c = open(product); var s = c.prepareStatement("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE"
                + " WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION")) {
            s.setString(1, database); s.setString(2, table);
            try (var r = s.executeQuery()) { while (r.next()) result.add(r.getString(1)); }
        }
        return result;
    }

    private List<String> postgresColumns(String schemaName, String table) throws SQLException {
        var result = new ArrayList<String>();
        try (var c = open("postgres"); var s = c.prepareStatement("SELECT a.attname, pg_catalog.format_type(a.atttypid,a.atttypmod), a.attnotnull"
                + " FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class t ON t.oid=a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace"
                + " WHERE n.nspname=? AND t.relname=? AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum")) {
            s.setString(1, schemaName); s.setString(2, table);
            try (var r = s.executeQuery()) { while (r.next()) result.add(r.getString(1) + "|" + r.getString(2) + "|" + r.getBoolean(3)); }
        }
        return result;
    }

    private List<String> postgresPrimaryKey(String schemaName, String table) throws SQLException {
        var result = new ArrayList<String>();
        try (var c = open("postgres"); var s = c.prepareStatement("SELECT a.attname FROM pg_catalog.pg_constraint p JOIN pg_catalog.pg_class t ON t.oid=p.conrelid"
                + " JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace CROSS JOIN LATERAL unnest(p.conkey) WITH ORDINALITY k(attnum, position)"
                + " JOIN pg_catalog.pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum WHERE n.nspname=? AND t.relname=? AND p.contype='p' ORDER BY k.position")) {
            s.setString(1, schemaName); s.setString(2, table);
            try (var r = s.executeQuery()) { while (r.next()) result.add(r.getString(1)); }
        }
        return result;
    }

    private void assertCheckRejected(String assignment) {
        var failure = assertThrows(SQLException.class, () -> sql("postgres", "UPDATE " + schema + ".copied SET " + assignment + " WHERE k_t = 127"));
        assertEquals("23514", failure.getSQLState(), assignment);
    }

    private boolean tableExists(String name, String database, String table) throws SQLException {
        String sql = name.equals("postgres") ? "SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?"
                : "SELECT count(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?";
        try (var c = open(name); var s = c.prepareStatement(sql)) {
            s.setString(1, database); s.setString(2, table);
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
                    if (value instanceof java.sql.Blob blob) value = blob.getBytes(1, (int) blob.length());
                    if (value instanceof Number number && !(value instanceof BigDecimal) && !(value instanceof Double) && !(value instanceof Float))
                        value = new BigDecimal(number.toString());
                    if (value instanceof Boolean bool && !name.equals("postgres")) value = bool;
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
        if (left instanceof Double a && right instanceof Double b) {
            assertEquals(Double.doubleToRawLongBits(a), Double.doubleToRawLongBits(b), label + " " + a + " vs " + b); return;
        }
        if (left instanceof Boolean || right instanceof Boolean) { assertEquals(truth(left), truth(right), label); return; }
        assertEquals(left.toString(), right.toString(), label);
    }

    private static boolean truth(Object value) {
        return value instanceof Boolean bool ? bool : new BigDecimal(value.toString()).signum() != 0;
    }

    private static boolean sameJson(String left, String right) {
        try {
            var mapper = new ObjectMapper();
            JsonNode a = mapper.readTree(left), b = mapper.readTree(right);
            return a.equals((x, y) -> x.isNumber() && y.isNumber() ? x.decimalValue().compareTo(y.decimalValue()) : x.equals(y) ? 0 : 1, b);
        } catch (Exception invalid) { return false; }
    }

    private static String allBytes() { return "'" + allBytesHex() + "'"; }
    private static String allBytesHex() {
        var hex = new StringBuilder();
        for (int i = 0; i < 256; i++) hex.append(String.format("%02x", i));
        return hex.toString();
    }
    private static String width(String product, String type, String width) { return product.equals("mariadb") ? type + "(" + width + ")" : type; }
    private static ProductTypeEnum familyType(String name) { return ProductTypeEnum.valueOf(name.toUpperCase(Locale.ROOT)); }
    private static ProductTypeEnum product(String name) { return name.equals("postgres") ? ProductTypeEnum.POSTGRESQL : familyType(name); }
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
    private static String password(String name) { return System.getProperty("datahub.test." + name + ".password", ""); }
}
