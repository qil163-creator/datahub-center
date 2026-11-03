package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MariaDB 11.4 as a native source and MySQL to MariaDB through the public MigrationService. These directions need the
 * MariaDB catalog reader and the native-fact routing in target/type-mapping-shared-proposed.patch.
 */
@Timeout(180)
@EnabledIfSystemProperty(named = "datahub.test.mariadb.url", matches = "jdbc:mariadb://127[.]0[.]0[.]1:.*")
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationCrossProductMariaDbSourceTest {
    private final String schema = "xmaria_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    private final List<Runnable> cleanup = new ArrayList<>();
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        runtime = DbSwitchRuntime.standalone();
        sql("postgres", "CREATE SCHEMA " + schema);
        cleanup.add(() -> quietly("postgres", "DROP SCHEMA IF EXISTS " + schema + " CASCADE"));
        for (String product : List.of("mariadb", "mysql")) {
            sql(product, "CREATE DATABASE `" + schema + "`");
            cleanup.add(() -> quietly(product, "DROP DATABASE IF EXISTS `" + schema + "`"));
        }
    }

    @AfterEach void teardown() {
        try { Collections.reverse(cleanup); cleanup.forEach(Runnable::run); }
        finally { if (runtime != null) runtime.close(); }
    }

    @Test void mariaDbSourceCreatesExactPostgresColumnsAndCopiesEveryValue() throws Exception {
        sql("mariadb", "CREATE TABLE `" + schema + "`.source(k int NOT NULL, t tinyint NOT NULL, v_su smallint unsigned, v_m mediumint,"
                + " v_iu int unsigned, v_b bigint, v_dec decimal(20,4) unsigned, v_dbl double,"
                + " v_name varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_nopad_bin, v_text text CHARACTER SET utf8mb4 COLLATE utf8mb4_nopad_bin,"
                + " v_code char(4) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin, v_bin binary(4), v_vb varbinary(9), v_blob blob, v_uuid uuid, v_json json,"
                + " PRIMARY KEY(k, t)) ENGINE=InnoDB");
        sql("mariadb", "INSERT INTO `" + schema + "`.source VALUES (-2147483648, -128, 65535, -8388608, 4294967295, -9223372036854775808,"
                + " 9999999999999999.9999, -2.5e-300, 'a  ', REPEAT('中😀', 2000), 'ab', x'01', x'00ff', x'0001ff', 'A0EEBC99-9C0B-4EF8-BB6D-6BB9BD380A11',"
                + " '{\"b\": 1,  \"a\": [1, 2.50, 12345678901234567890123.5], \"a\": 3}')");
        sql("mariadb", "INSERT INTO `" + schema + "`.source VALUES (2147483647, 127, 0, 8388607, 0, 9223372036854775807, 0, 1.7976931348623157e308,"
                + " '😀zé中', '', 'abcd', x'ffffffff', x'', x'', 'ffffffff-ffff-ffff-ffff-ffffffffffff', '[]')");
        sql("mariadb", "INSERT INTO `" + schema + "`.source(k, t) VALUES (0, 0)");
        assertSuccess(run(ProductTypeEnum.MARIADB, "mariadb", ProductTypeEnum.POSTGRESQL, "postgres", false));
        assertEquals(List.of("k|integer|true|", "t|smallint|true|", "v_su|integer|false|", "v_m|integer|false|", "v_iu|bigint|false|",
                "v_b|bigint|false|", "v_dec|numeric(20,4)|false|", "v_dbl|double precision|false|", "v_name|character varying(30)|false|C",
                "v_text|text|false|C", "v_code|character(4)|false|C", "v_bin|bytea|false|", "v_vb|bytea|false|", "v_blob|bytea|false|",
                "v_uuid|uuid|false|", "v_json|json|false|"), postgresColumns());
        String columns = "k, t, v_su, v_m, v_iu, v_b, v_dec, v_dbl, v_name, v_text, v_code, v_bin, v_vb, v_blob";
        var source = rows("mariadb", "SELECT " + columns + ", CAST(v_uuid AS CHAR), v_json FROM `" + schema + "`.source ORDER BY k, t");
        var target = rows("postgres", "SELECT " + columns + ", v_uuid::text, v_json::text FROM " + schema + ".copied ORDER BY k, t");
        assertEquals(3, target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) {
                Object left = source.get(r).get(c), right = target.get(r).get(c);
                if (c == 10 && left != null) { left = left.toString().stripTrailing(); right = right.toString().stripTrailing(); }
                assertValue(left, right, "row " + r + " column " + c); // json text, whitespace and duplicate keys included
            }
    }

    @Test void mysqlToMariaDbAndBackCreateTargetsThroughTheRoutedPlan() throws Exception {
        sql("mysql", "CREATE TABLE `" + schema + "`.source(k varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, n smallint NOT NULL,"
                + " v_u bigint unsigned, v_dec decimal(30,10) unsigned, v_np varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin,"
                + " v_bin binary(3), v_blob longblob, v_date date, v_dt datetime(6), v_json json, PRIMARY KEY(k, n)) ENGINE=InnoDB");
        sql("mysql", "INSERT INTO `" + schema + "`.source VALUES ('a', 1, 9223372036854775807, 12345678901234567890.0123456789, 'x  ', x'01',"
                + " x'00ff', '0001-01-01', '1919-04-13 00:30:00.654321', '{\"k\": [1, \"v\"]}')");
        sql("mysql", "INSERT INTO `" + schema + "`.source(k, n) VALUES ('😀', 2)");
        assertSuccess(run(ProductTypeEnum.MYSQL, "mysql", ProductTypeEnum.MARIADB, "mariadb", false));
        assertEquals(List.of("k|varchar(20)|NO|utf8mb4|utf8mb4_bin", "n|smallint(6)|NO|null|null", "v_u|bigint(20) unsigned|YES|null|null",
                "v_dec|decimal(30,10) unsigned|YES|null|null", "v_np|varchar(30)|YES|utf8mb4|utf8mb4_nopad_bin", "v_bin|binary(3)|YES|null|null",
                "v_blob|longblob|YES|null|null", "v_date|date|YES|null|null", "v_dt|datetime(6)|YES|null|null",
                "v_json|longtext|YES|utf8mb4|utf8mb4_bin"), familyColumns("mariadb", "copied"));
        String select = "SELECT k, n, v_u, v_dec, v_np, v_bin, v_blob, CAST(v_date AS CHAR), DATE_FORMAT(v_dt, '%Y-%m-%d %H:%i:%s.%f'),"
                + " CAST(v_json AS CHAR) FROM `" + schema + "`.{table} ORDER BY n";
        assertRows(rows("mysql", select.replace("{table}", "source")), rows("mariadb", select.replace("{table}", "copied")));
        // Back to MySQL: MariaDB facts (display widths, nopad_bin) plan the MySQL equivalents; dates are LOSSY, so omit them.
        sql("mariadb", "CREATE TABLE `" + schema + "`.back_source AS SELECT k, n, v_u, v_dec, v_np, v_bin, v_blob FROM `" + schema + "`.copied");
        sql("mariadb", "ALTER TABLE `" + schema + "`.back_source ADD PRIMARY KEY(k, n)");
        assertSuccess(run(ProductTypeEnum.MARIADB, "mariadb", "back_source", ProductTypeEnum.MYSQL, "mysql", "back_copied", false));
        assertEquals(List.of("k|varchar(20)|NO|utf8mb4|utf8mb4_bin", "n|smallint|NO|null|null", "v_u|bigint unsigned|YES|null|null",
                "v_dec|decimal(30,10) unsigned|YES|null|null", "v_np|varchar(30)|YES|utf8mb4|utf8mb4_0900_bin", "v_bin|binary(3)|YES|null|null",
                "v_blob|longblob|YES|null|null"), familyColumns("mysql", "back_copied"));
        String back = "SELECT k, n, v_u, v_dec, v_np, v_bin, v_blob FROM `" + schema + "`.{table} ORDER BY n";
        assertRows(rows("mariadb", back.replace("{table}", "back_source")), rows("mysql", back.replace("{table}", "back_copied")));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "mariadb; postgres; date",
            "mariadb; postgres; datetime(6)",
            "mariadb; postgres; time(3)",
            "mariadb; postgres; varchar(10)",
            "mariadb; mysql; uuid",
            "mariadb; mysql; json",
            "mysql; mariadb; double",
            "mysql; mariadb; varchar(10) CHARACTER SET latin1"})
    void routedRejectionsLeaveNoTargetObject(String sourceName, String targetName, String type) throws Exception {
        String relation = sourceName.equals("postgres") ? schema + ".source" : "`" + schema + "`.source";
        sql(sourceName, "CREATE TABLE " + relation + "(id int PRIMARY KEY, value " + type + ")");
        var result = run(product(sourceName), sourceName, product(targetName), targetName, false);
        assertFalse(result.getIfAllSuccess().get());
        assertFalse(tableExists(targetName, "copied"));
    }

    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, ProductTypeEnum targetProduct, String targetName, boolean drop) throws Exception {
        return run(sourceProduct, sourceName, "source", targetProduct, targetName, "copied", drop);
    }

    private DbSwitchResult run(ProductTypeEnum sourceProduct, String sourceName, String sourceTable, ProductTypeEnum targetProduct,
                               String targetName, String targetTable, boolean drop) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(sourceProduct); source.setDriverClassName(sourceProduct.getDriveClassName());
        source.setUrl(url(sourceName)); source.setUsername(user(sourceName)); source.setPassword(""); source.setPoolSize(1);
        source.setSourceSchema(schema); source.setSourceIncludes(sourceTable); source.setFetchSize(2);
        source.setRegexTableMapper(List.of(new PatternMapper("^" + sourceTable + "$", targetTable))); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(targetProduct);
        target.setDriverClassName(targetProduct.getDriveClassName()); target.setPoolSize(1);
        target.setUrl(url(targetName)); target.setUsername(user(targetName)); target.setPassword("");
        target.setTargetSchema(schema); target.setWriterEngineInsert(true); target.setTargetDrop(drop);
        return new MigrationService(request, ignored -> { }, null, runtime).run();
    }

    private List<String> postgresColumns() throws SQLException {
        var result = new ArrayList<String>();
        try (var c = open("postgres"); var s = c.prepareStatement("SELECT a.attname, pg_catalog.format_type(a.atttypid,a.atttypmod), a.attnotnull,"
                + " CASE WHEN a.attcollation<>t.typcollation THEN co.collname ELSE '' END FROM pg_catalog.pg_attribute a"
                + " JOIN pg_catalog.pg_class r ON r.oid=a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid=r.relnamespace"
                + " JOIN pg_catalog.pg_type t ON t.oid=a.atttypid LEFT JOIN pg_catalog.pg_collation co ON co.oid=a.attcollation"
                + " WHERE n.nspname=? AND r.relname='copied' AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum")) {
            s.setString(1, schema);
            try (var r = s.executeQuery()) {
                while (r.next()) result.add(r.getString(1) + "|" + r.getString(2) + "|" + r.getBoolean(3) + "|" + r.getString(4));
            }
        }
        return result;
    }

    private List<String> familyColumns(String product, String table) throws SQLException {
        var result = new ArrayList<String>();
        try (var c = open(product); var s = c.prepareStatement("SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, CHARACTER_SET_NAME, COLLATION_NAME"
                + " FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
            s.setString(1, schema); s.setString(2, table);
            try (var r = s.executeQuery()) {
                while (r.next()) result.add(r.getString(1) + "|" + r.getString(2).toLowerCase(Locale.ROOT) + "|" + r.getString(3)
                        + "|" + r.getString(4) + "|" + r.getString(5));
            }
        }
        return result;
    }

    private boolean tableExists(String name, String table) throws SQLException {
        String sql = name.equals("postgres") ? "SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?"
                : "SELECT count(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?";
        try (var c = open(name); var s = c.prepareStatement(sql)) {
            s.setString(1, schema); s.setString(2, table);
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
                    row.add(value);
                }
                result.add(row);
            }
        }
        return result;
    }

    private static void assertRows(List<List<Object>> source, List<List<Object>> target) {
        assertEquals(source.size(), target.size());
        for (int r = 0; r < source.size(); r++)
            for (int c = 0; c < source.get(r).size(); c++) assertValue(source.get(r).get(c), target.get(r).get(c), "row " + r + " column " + c);
    }

    private static void assertValue(Object left, Object right, String label) {
        if (left == null || right == null) { assertEquals(left, right, label); return; }
        if (left instanceof byte[] a) { assertArrayEquals(a, (byte[]) right, label); return; }
        if (left instanceof BigDecimal a && right instanceof BigDecimal b) { assertEquals(0, a.compareTo(b), label + " " + a + " vs " + b); return; }
        if (left instanceof Double a && right instanceof Double b) {
            assertEquals(Double.doubleToRawLongBits(a), Double.doubleToRawLongBits(b), label + " " + a + " vs " + b); return;
        }
        assertEquals(left.toString(), right.toString(), label);
    }

    private static ProductTypeEnum product(String name) {
        return name.equals("postgres") ? ProductTypeEnum.POSTGRESQL : ProductTypeEnum.valueOf(name.toUpperCase(Locale.ROOT));
    }
    private static void assertSuccess(DbSwitchResult result) {
        assertEquals(1, result.getTableResultList().size());
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
    }
    private void quietly(String name, String sql) { try { sql(name, sql); } catch (Exception ignored) { } }
    private static void sql(String name, String sql) throws SQLException {
        try (var c = open(name); var s = c.createStatement()) { s.setQueryTimeout(60); s.execute(sql); }
    }
    private static Connection open(String name) throws SQLException { return DriverManager.getConnection(url(name), user(name), ""); }
    private static String url(String name) { return System.getProperty("datahub.test." + name + ".url"); }
    private static String user(String name) {
        return System.getProperty("datahub.test." + name + ".user", name.equals("postgres") ? "dataforge_test" : "root");
    }
}
