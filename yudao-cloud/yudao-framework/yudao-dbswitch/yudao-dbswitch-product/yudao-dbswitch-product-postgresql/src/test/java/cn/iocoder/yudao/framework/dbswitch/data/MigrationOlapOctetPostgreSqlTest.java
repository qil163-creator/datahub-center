package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.*;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.product.postgresql.PostgresLogicalTypeMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;

/** 原生 OLAP 有限字节 VARCHAR，通过公共 API 全量、主键差异与零变更重放。 */
@Timeout(240)
@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfSystemProperty(named = "datahub.test.starrocks.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationOlapOctetPostgreSqlTest {
    private final String schema = "om74_" + UUID.randomUUID().toString().replace("-", "");
    private String source = "source_%'", note = "note_'%\"";
    private final String target = "copied_%'";
    private ProductTypeEnum product;
    private DbSwitchRuntime runtime;

    @BeforeEach void runtime() {
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), new DbSwitchOptions(
                65536, false, false, 20, 0, 0, 250, 0), null);
    }
    private void prepare(ProductTypeEnum p) throws Exception {
        product = p;
        // StarRocks 的已证投影入口只接受普通源标识符；Doris 同时证明特殊源列与表名。
        if (p == STARROCKS) { source = "source"; note = "note"; }
        try (var c = open(p); var s = c.createStatement(); var r = s.executeQuery("SHOW VARIABLES LIKE 'version_comment'")) {
            assertTrue(r.next()); assertTrue(r.getString(2).contains(p == DORIS ? "doris-4.0.8" : "3.5.21"));
        }
        assertEquals("UTF8", scalar(POSTGRESQL, "SHOW server_encoding"));
        sql(p, "CREATE DATABASE " + q(p, schema)); sql(POSTGRESQL, "CREATE SCHEMA " + q(POSTGRESQL, schema));
    }
    @AfterEach void cleanup() throws Exception {
        try { if (product != null) { sql(product, "DROP DATABASE IF EXISTS " + q(product, schema));
            sql(POSTGRESQL, "DROP SCHEMA IF EXISTS " + q(POSTGRESQL, schema) + " CASCADE"); } }
        finally { if (runtime != null) runtime.close(); }
    }

    @ParameterizedTest @CsvSource({"DORIS,true", "DORIS,false", "STARROCKS,true", "STARROCKS,false"})
    void bytesUnicodeNullTrailingSpacesAndLiteralNamesSurvivePublicFullDifferenceReplay(ProductTypeEnum p, boolean insert) throws Exception {
        prepare(p);
        sql(p, "CREATE TABLE " + table(p, source) + "(id BIGINT NOT NULL," + q(p, note) + " VARCHAR(12))" + model(p, "id"),
                "INSERT INTO " + table(p, source) + " VALUES(-1,'中😀12345'),(0,NULL),(1,''),(2,'x  '),(3,'x')");
        var request = request(insert); success(run(request)); equalRows();
        var plan = runtime.products().typeMappings().plan(POSTGRESQL,
                runtime.metadata(ds(p), p).queryTableColumnMetaWithNativeEvidence(schema, source), List.of("id"), LossyColumnAcceptance.rejectAll());
        assertEquals(TypeFidelity.EXACT, plan.column(note).fidelity());
        assertEquals("text COLLATE \"C\"", plan.column(note).targetNativeType());
        try (var c = open(POSTGRESQL); var s = c.prepareStatement("SELECT a.attname,pg_catalog.format_type(a.atttypid,a.atttypmod),co.collname FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class t ON t.oid=a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace LEFT JOIN pg_catalog.pg_collation co ON co.oid=a.attcollation WHERE n.nspname=? AND t.relname=? AND a.attname=?")) {
            s.setString(1, schema); s.setString(2, target); s.setString(3, note);
            try (var r = s.executeQuery()) { assertTrue(r.next()); assertEquals(note, r.getString(1)); assertEquals("text", r.getString(2)); assertEquals("C", r.getString(3)); }
        }
        try (var c = open(POSTGRESQL); var s = c.prepareStatement("SELECT convalidated,pg_get_constraintdef(k.oid) FROM pg_catalog.pg_constraint k JOIN pg_catalog.pg_class t ON t.oid=k.conrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=? AND t.relname=? AND k.contype='c'")) {
            s.setString(1, schema); s.setString(2, target);
            try (var r = s.executeQuery()) { assertTrue(r.next()); assertTrue(r.getBoolean(1)); var definition = r.getString(2);
                assertTrue(definition.contains("server_encoding")); assertTrue(definition.contains("octet_length")); assertTrue(definition.contains("<= 12")); assertFalse(r.next()); }
        }
        assertEquals("12", scalar(POSTGRESQL, "SELECT octet_length(" + q(POSTGRESQL, note) + ") FROM " + table(POSTGRESQL, target) + " WHERE id=-1"));
        assertEquals("different", scalar(POSTGRESQL, "SELECT CASE WHEN (SELECT " + q(POSTGRESQL, note) + " FROM " + table(POSTGRESQL, target)
                + " WHERE id=2)=(SELECT " + q(POSTGRESQL, note) + " FROM " + table(POSTGRESQL, target) + " WHERE id=3) THEN 'equal' ELSE 'different' END"));
        assertRejectedValue("中文😀123", "23514"); assertRejectedValue("1234567890123", "23514"); assertRejectedValue("a\u0000b", "22021");
        equalRows();
        sql(p, "DELETE FROM " + table(p, source) + " WHERE id=-1", "UPDATE " + table(p, source) + " SET " + q(p, note)
                + "='changed 中' WHERE id=0", "INSERT INTO " + table(p, source) + " VALUES(9007199254740993,'😀😀😀')");
        request.getTarget().setChangeDataSync(true); request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
        success(run(request)); equalRows(); var replay = run(request); success(replay);
        assertEquals(0, replay.getTableResultList().getFirst().getSyncCount().get()); equalRows();
    }

    @ParameterizedTest @CsvSource({"DORIS,CHAR(12),false", "STARROCKS,CHAR(12),false", "DORIS,VARCHAR(12),true", "STARROCKS,VARCHAR(12),true", "DORIS,STRING,false"})
    void unsupportedFixedLargeAndCharacterKeysKeepSentinelBeforeExclusiveOverwrite(ProductTypeEnum p, String type, boolean key) throws Exception {
        prepare(p); String keys = key ? q(p, note) : "id";
        String fields = key ? q(p, note) + " " + type + " NOT NULL,id BIGINT" : "id BIGINT NOT NULL," + q(p, note) + " " + type;
        sql(p, "CREATE TABLE " + table(p, source) + "(" + fields + ")" + model(p, keys));
        sql(POSTGRESQL, "CREATE TABLE " + table(POSTGRESQL, target) + "(keep BIGINT)", "INSERT INTO " + table(POSTGRESQL, target) + " VALUES(77)");
        var request = request(true); request.getTarget().setTargetDrop(true); request.getTarget().setTargetTruncateData(true);
        request.getTarget().setExclusiveTargetDdlOwnership(true);
        var result = run(request); assertFalse(result.getIfAllSuccess().get());
        assertEquals(TypeFidelity.UNSUPPORTED, result.getTableResultList().getFirst().getTypeMappingPlan().column(note).fidelity());
        assertEquals("77", scalar(POSTGRESQL, "SELECT keep FROM " + table(POSTGRESQL, target)));
    }

    @Test void nonUtf8DatabaseRejectsEvenAsciiNonNullValuesAndKeepsSqlNull() throws Exception {
        String database = "codex_octet74_" + UUID.randomUUID().toString().replace("-", "");
        var text = new LogicalType.CharacterType(LogicalType.StorageKind.VARYING, 12L, LogicalType.LengthUnit.OCTETS,
                null, "utf8mb4", LogicalType.Comparison.BINARY_NO_PAD, null, false);
        var mapped = new PostgresLogicalTypeMapper().toNative(new LogicalColumn("note", text, true, false, STARROCKS, "varchar(12)"));
        assertEquals(TypeFidelity.EXACT, mapped.fidelity());
        sql(POSTGRESQL, "CREATE DATABASE " + q(POSTGRESQL, database) + " ENCODING 'LATIN1' TEMPLATE template0 LC_COLLATE 'C' LC_CTYPE 'C'");
        try {
            String dbUrl = url(POSTGRESQL).replaceFirst("/[^/?]+(?:\\?.*)?$", "/" + database);
            try (var c = DriverManager.getConnection(dbUrl, user(POSTGRESQL), ""); var s = c.createStatement()) {
                try (var r = s.executeQuery("SHOW server_encoding")) { assertTrue(r.next()); assertEquals("LATIN1", r.getString(1)); }
                s.execute("CREATE TABLE guarded(note " + mapped.value().typeSql() + " CHECK (" + mapped.value().check("note") + "))");
                assertEquals("23514", assertThrows(SQLException.class, () -> s.execute("INSERT INTO guarded VALUES('ascii')")).getSQLState());
                s.execute("INSERT INTO guarded VALUES(NULL)");
                try (var r = s.executeQuery("SELECT count(*) FROM guarded WHERE note IS NULL")) { assertTrue(r.next()); assertEquals(1, r.getInt(1)); }
            }
        } finally { sql(POSTGRESQL, "DROP DATABASE " + q(POSTGRESQL, database)); }
    }

    @Test void qualifiedBuiltinsKeepTheDomainWhenSessionSearchPathContainsShadowFunctions() throws Exception {
        String shadow = "octet_shadow74_" + UUID.randomUUID().toString().replace("-", "");
        var text = new LogicalType.CharacterType(LogicalType.StorageKind.VARYING, 12L, LogicalType.LengthUnit.OCTETS,
                null, "utf8mb4", LogicalType.Comparison.BINARY_NO_PAD, null, false);
        var mapped = new PostgresLogicalTypeMapper().toNative(new LogicalColumn("note", text, true, false, STARROCKS, "varchar(12)"));
        sql(POSTGRESQL, "CREATE SCHEMA " + q(POSTGRESQL, shadow));
        try (var c = open(POSTGRESQL); var s = c.createStatement()) {
            s.execute("CREATE FUNCTION " + q(POSTGRESQL, shadow) + ".current_setting(text) RETURNS text LANGUAGE sql IMMUTABLE AS 'SELECT ''LATIN1''::text'");
            s.execute("CREATE FUNCTION " + q(POSTGRESQL, shadow) + ".octet_length(text) RETURNS integer LANGUAGE sql IMMUTABLE AS 'SELECT 0'");
            s.execute("SET search_path=" + q(POSTGRESQL, shadow) + ",pg_catalog");
            try (var r = s.executeQuery("SELECT current_setting('server_encoding'),octet_length('1234567890123'::text),pg_catalog.current_setting('server_encoding')")) {
                assertTrue(r.next()); assertEquals("LATIN1", r.getString(1)); assertEquals(0, r.getInt(2)); assertEquals("UTF8", r.getString(3));
            }
            s.execute("CREATE TABLE guarded(note " + mapped.value().typeSql() + " CHECK (" + mapped.value().check("note") + "))");
            s.execute("INSERT INTO guarded VALUES('ascii'),(NULL)");
            assertEquals("23514", assertThrows(SQLException.class, () -> s.execute("INSERT INTO guarded VALUES('1234567890123')")).getSQLState());
            try (var r = s.executeQuery("SELECT count(*) FROM guarded")) { assertTrue(r.next()); assertEquals(2, r.getInt(1)); }
        } finally { sql(POSTGRESQL, "DROP SCHEMA " + q(POSTGRESQL, shadow) + " CASCADE"); }
    }

    private void assertRejectedValue(String value, String state) throws Exception {
        try (var c = open(POSTGRESQL); var s = c.prepareStatement("INSERT INTO " + table(POSTGRESQL, target) + " VALUES(77,?)")) {
            s.setString(1, value); assertEquals(state, assertThrows(SQLException.class, s::executeUpdate).getSQLState());
        }
    }
    private MigrationRequest request(boolean insert) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(product); input.setDriverClassName(product.getDriveClassName()); input.setUrl(url(product)); input.setUsername(user(product)); input.setPassword("");
        input.setSourceSchema(schema); input.setSourceTableNames(List.of(source)); input.setIncludeOrExclude(1); input.setPoolSize(1); input.setFetchSize(2);
        input.setRegexTableMapper(List.of(new PatternMapper("^" + java.util.regex.Pattern.quote(source) + "$", target))); request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(POSTGRESQL); output.setDriverClassName(POSTGRESQL.getDriveClassName()); output.setUrl(url(POSTGRESQL)); output.setUsername(user(POSTGRESQL)); output.setPassword("");
        output.setTargetSchema(schema); output.setPoolSize(1); output.setWriterEngineInsert(insert); return request;
    }
    private DbSwitchResult run(MigrationRequest request) throws Exception { return new MigrationService(request, ignored -> { }, null, runtime).run(); }
    private static void success(DbSwitchResult result) { assertEquals(1, result.getTableResultList().size()); assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString()); }
    private void equalRows() throws Exception { assertEquals(rows(product, source), rows(POSTGRESQL, target)); }
    private List<List<Object>> rows(ProductTypeEnum p, String name) throws Exception {
        try (var c = open(p); var s = c.createStatement(); var r = s.executeQuery("SELECT id," + q(p, note) + " FROM " + table(p, name) + " ORDER BY id")) {
            var rows = new ArrayList<List<Object>>(); while (r.next()) rows.add(Arrays.asList(r.getLong(1), r.getString(2))); return rows;
        }
    }
    private static String model(ProductTypeEnum p, String keys) { return " ENGINE=OLAP " + (p == DORIS ? "UNIQUE" : "PRIMARY") + " KEY(" + keys + ") DISTRIBUTED BY HASH(" + keys + ") BUCKETS 1 PROPERTIES('replication_num'='1'" + (p == DORIS ? ",'enable_unique_key_merge_on_write'='true'" : "") + ")"; }
    private String table(ProductTypeEnum p, String name) { return q(p, schema) + "." + q(p, name); }
    private static String q(ProductTypeEnum p, String name) { return RestrictedSelectGuard.quotePhysicalIdentifier(p, name); }
    private static String url(ProductTypeEnum p) { return System.getProperty(p == DORIS ? "datahub.test.doris.url" : p == STARROCKS ? "datahub.test.starrocks.url" : "datahub.test.postgres.url"); }
    private static String user(ProductTypeEnum p) { return p == POSTGRESQL ? System.getProperty("datahub.test.postgres.user", "dataforge_test") : "root"; }
    private static Connection open(ProductTypeEnum p) throws SQLException { return DriverManager.getConnection(url(p), user(p), ""); }
    private static DriverManagerDataSource ds(ProductTypeEnum p) { return new DriverManagerDataSource(url(p), user(p), ""); }
    private static void sql(ProductTypeEnum p, String... text) throws Exception { try (var c = open(p); var s = c.createStatement()) { s.setQueryTimeout(30); for (String t : text) s.execute(t); } }
    private static String scalar(ProductTypeEnum p, String text) throws Exception { try (var c = open(p); var s = c.createStatement(); var r = s.executeQuery(text)) { assertTrue(r.next()); return r.getString(1); } }
}
