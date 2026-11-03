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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.DORIS;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.POSTGRESQL;
import static org.junit.jupiter.api.Assertions.*;

/** Native Doris FE/BE, registered mappings and public migration API; JDBC INSERT is explicitly selected. */
@Timeout(240)
@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationDorisPostgreSqlMappingTest {
    private final String schema = "dm74_" + UUID.randomUUID().toString().replace("-", "");
    private final String source = "source_%";
    private final String target = "copied_'%";
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), new DbSwitchOptions(
                65536, false, false, 20, 0, 0, 250, 0), null);
        try (var c = open(DORIS); var s = c.createStatement(); var r = s.executeQuery("SHOW VARIABLES LIKE 'version_comment'")) {
            assertTrue(r.next()); assertTrue(r.getString(2).contains("doris-4.0.8"));
        }
        try (var c = open(DORIS); var s = c.createStatement(); var r = s.executeQuery("SHOW BACKENDS")) {
            assertTrue(r.next()); assertTrue(r.getBoolean("Alive")); assertTrue(r.getString("Version").contains("doris-4.0.8"));
        }
        sql(DORIS, "CREATE DATABASE " + q(DORIS, schema));
        sql(POSTGRESQL, "CREATE SCHEMA " + q(POSTGRESQL, schema));
    }

    @AfterEach void cleanup() throws Exception {
        try {
            sql(DORIS, "DROP DATABASE IF EXISTS " + q(DORIS, schema));
            sql(POSTGRESQL, "DROP SCHEMA IF EXISTS " + q(POSTGRESQL, schema) + " CASCADE");
        } finally { if (runtime != null) runtime.close(); }
    }

    @ParameterizedTest @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void dorisExactNumericDomainsUsePublicFullDifferenceAndZeroChangeReplay(boolean existing, boolean insert) throws Exception {
        sql(DORIS, "CREATE TABLE " + table(DORIS, source) + "(id BIGINT NOT NULL,part SMALLINT NOT NULL,tiny TINYINT,"
                + "amount DECIMAL(38,18),flag BOOLEAN)" + model("UNIQUE", "id,part"),
                "INSERT INTO " + table(DORIS, source) + " VALUES(-9223372036854775808,-32768,-128,-99999999999999999999.999999999999999999,false),"
                        + "(9223372036854775807,32767,127,0.000000000000000001,true),(0,0,NULL,NULL,NULL)");
        if (existing) sql(POSTGRESQL, "CREATE TABLE " + table(POSTGRESQL, target)
                + "(id BIGINT NOT NULL,part SMALLINT NOT NULL,tiny SMALLINT,amount NUMERIC(38,18),flag BOOLEAN,PRIMARY KEY(id,part))");
        var request = request(DORIS, POSTGRESQL, insert);
        success(run(request)); equalNumeric();
        assertEquals(List.of("id|bigint", "part|smallint", "tiny|smallint", "amount|numeric(38,18)", "flag|boolean"), pgColumns());
        assertEquals(List.of("id", "part"), runtime.metadata(ds(POSTGRESQL), POSTGRESQL).queryTablePrimaryKeys(schema, target));
        sql(DORIS, "DELETE FROM " + table(DORIS, source) + " WHERE id=-9223372036854775808",
                "UPDATE " + table(DORIS, source) + " SET tiny=7,amount=-0.000000000000000001,flag=false WHERE id=0",
                "INSERT INTO " + table(DORIS, source) + " VALUES(9007199254740993,1,1,1.000000000000000001,true)");
        diff(request); success(run(request)); equalNumeric();
        var replay = run(request); success(replay); assertEquals(0, replay.getTableResultList().getFirst().getSyncCount().get()); equalNumeric();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void dorisDatetimeRequiresExactAcknowledgementAndRetainsMicrosecondsThroughDifference(boolean existing) throws Exception {
        sql(DORIS, "CREATE TABLE " + table(DORIS, source) + "(id BIGINT NOT NULL,moment DATETIME(6),day DATE)" + model("UNIQUE", "id"),
                "INSERT INTO " + table(DORIS, source) + " VALUES(-1,'0001-01-01 00:00:00.000001','0001-01-01'),"
                        + "(2,'9999-12-31 23:59:59.999999','9999-12-31'),(0,NULL,NULL)");
        if (existing) sql(POSTGRESQL, "CREATE TABLE " + table(POSTGRESQL, target) + "(id BIGINT PRIMARY KEY,moment TIMESTAMP(6),day DATE)");
        var request = request(DORIS, POSTGRESQL, true);
        var rejected = run(request); assertFalse(rejected.getIfAllSuccess().get());
        assertEquals(Set.of("ZERO_DATE_SILENTLY_CONVERTED"), rejected.getTableResultList().getFirst().getTypeMappingPlan().column("moment").lossCodes());
        acknowledge(request, DORIS, POSTGRESQL); success(run(request)); equalTemporal();
        sql(DORIS, "DELETE FROM " + table(DORIS, source) + " WHERE id=-1", "UPDATE " + table(DORIS, source)
                + " SET moment='2026-10-08 12:34:56.654321',day='2026-10-08' WHERE id=0");
        diff(request); success(run(request)); equalTemporal();
        var replay = run(request); success(replay); assertEquals(0, replay.getTableResultList().getFirst().getSyncCount().get()); equalTemporal();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void postgresExistingDorisUniqueTargetRetainsNumbersUnicodeNullsAndMicroseconds(boolean insert) throws Exception {
        postgresMixedSource();
        sql(DORIS, "CREATE TABLE " + table(DORIS, target)
                + "(id BIGINT NOT NULL,amount DECIMAL(38,18),note VARCHAR(160),moment DATETIME(6),flag BOOLEAN)" + model("UNIQUE", "id"));
        var request = request(POSTGRESQL, DORIS, insert);
        assertFalse(run(request).getIfAllSuccess().get(), "Mapping remains conservative even for an existing UNIQUE target");
        acknowledge(request, POSTGRESQL, DORIS); success(run(request)); equalMixed();
        var metadata = runtime.metadata(ds(DORIS), DORIS);
        assertEquals(Set.of("id", "amount", "note", "moment", "flag"), new HashSet<>(metadata.queryTableColumnName(schema, target)));
        assertEquals(List.of("id"), metadata.queryTablePrimaryKeys(schema, target));
        sql(POSTGRESQL, "DELETE FROM " + table(POSTGRESQL, source) + " WHERE id=-1", "UPDATE " + table(POSTGRESQL, source)
                + " SET amount=-0.000000000000000001,note='changed 中😀  ',moment='2026-10-08 12:34:56.000007',flag=false WHERE id=0",
                "INSERT INTO " + table(POSTGRESQL, source) + " VALUES(9007199254740993,1.000000000000000001,NULL,NULL,NULL)");
        diff(request); success(run(request)); equalMixed();
        var replay = run(request); success(replay); assertEquals(0, replay.getTableResultList().getFirst().getSyncCount().get()); equalMixed();
    }

    @Test void acknowledgedAutoCreatedDuplicateModelKeepsFullRowsButNeverPretendsToSupportPrimaryKeyDifference() throws Exception {
        postgresMixedSource(); var request = request(POSTGRESQL, DORIS, true);
        assertFalse(run(request).getIfAllSuccess().get()); acknowledge(request, POSTGRESQL, DORIS);
        success(run(request)); equalMixed();
        assertTrue(showCreate().contains("DUPLICATE KEY"));
        var before = mixedRows(DORIS, target); diff(request);
        assertFalse(run(request).getIfAllSuccess().get()); assertEquals(before, mixedRows(DORIS, target));
    }

    @ParameterizedTest @CsvSource(delimiter = ';', value = {
            "bytea;BINARY_TYPE_UNAVAILABLE", "time(6) without time zone;TIME_TYPE_UNAVAILABLE",
            "uuid;UUID_TYPE_UNAVAILABLE", "numeric;UNBOUNDED_NUMERIC_UNMAPPED"})
    void unsupportedPostgresNativeTypesPreserveDorisSentinelWhenExclusiveOverwriteRequested(String type, String reason) throws Exception {
        sql(POSTGRESQL, "CREATE TABLE " + table(POSTGRESQL, source) + "(id BIGINT PRIMARY KEY,value " + type + ")");
        sql(DORIS, "CREATE TABLE " + table(DORIS, target) + "(keep BIGINT NOT NULL)" + model("UNIQUE", "keep"),
                "INSERT INTO " + table(DORIS, target) + " VALUES(77)");
        var request = request(POSTGRESQL, DORIS, true); request.getTarget().setTargetDrop(true); request.getTarget().setTargetTruncateData(true);
        request.getTarget().setExclusiveTargetDdlOwnership(true); acknowledge(request, POSTGRESQL, DORIS);
        var result = run(request); assertFalse(result.getIfAllSuccess().get());
        assertEquals(reason, result.getTableResultList().getFirst().getTypeMappingPlan().column("value").reasonCode());
        assertEquals(List.of(List.of(77L)), rows(DORIS, "SELECT keep FROM " + table(DORIS, target)));
    }

    private void postgresMixedSource() throws Exception {
        sql(POSTGRESQL, "CREATE TABLE " + table(POSTGRESQL, source)
                + "(id BIGINT PRIMARY KEY,amount NUMERIC(38,18),note VARCHAR(40),moment TIMESTAMP(6),flag BOOLEAN)",
                "INSERT INTO " + table(POSTGRESQL, source) + " VALUES(-1,-99999999999999999999.999999999999999999,'中😀 x  ','0001-01-01 00:00:00.000001',false),"
                        + "(2,0.000000000000000001,'','9999-12-31 23:59:59.999999',true),(0,NULL,NULL,NULL,NULL)");
    }
    private void acknowledge(MigrationRequest request, ProductTypeEnum from, ProductTypeEnum to) {
        var metadata = runtime.metadata(ds(from), from);
        var columns = metadata.queryTableColumnMetaWithNativeEvidence(schema, source);
        var plan = runtime.products().typeMappings().plan(to, columns, metadata.queryTablePrimaryKeys(schema, source), LossyColumnAcceptance.rejectAll());
        request.setTypeMappingAcknowledgements(plan.columns().stream().filter(c -> c.fidelity() == TypeFidelity.LOSSY)
                .map(c -> LossyMappingAcknowledgement.forColumn(schema, target, c)).toList());
    }
    private MigrationRequest request(ProductTypeEnum from, ProductTypeEnum to, boolean insert) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(from); input.setDriverClassName(from.getDriveClassName()); input.setUrl(url(from)); input.setUsername(user(from)); input.setPassword("");
        input.setSourceSchema(schema); input.setSourceTableNames(List.of(source)); input.setIncludeOrExclude(1); input.setPoolSize(1); input.setFetchSize(2);
        input.setRegexTableMapper(List.of(new PatternMapper("^" + java.util.regex.Pattern.quote(source) + "$", target))); request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(to); output.setDriverClassName(to.getDriveClassName()); output.setUrl(url(to)); output.setUsername(user(to)); output.setPassword("");
        output.setTargetSchema(schema); output.setPoolSize(1); output.setWriterEngineInsert(insert); return request;
    }
    private DbSwitchResult run(MigrationRequest request) throws Exception { return new MigrationService(request, ignored -> { }, null, runtime).run(); }
    private static void diff(MigrationRequest request) { request.getTarget().setChangeDataSync(true); request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE"); }
    private static void success(DbSwitchResult result) { assertEquals(1, result.getTableResultList().size()); assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString()); }
    private void equalNumeric() throws Exception { assertEquals(rows(DORIS, "SELECT id,part,tiny,amount,flag FROM " + table(DORIS, source) + " ORDER BY id,part"), rows(POSTGRESQL, "SELECT id,part,tiny,amount,flag FROM " + table(POSTGRESQL, target) + " ORDER BY id,part")); }
    private void equalTemporal() throws Exception { assertEquals(temporalRows(DORIS, source), temporalRows(POSTGRESQL, target)); }
    private List<List<Object>> temporalRows(ProductTypeEnum p, String name) throws Exception { return rows(p, "SELECT id," + (p == DORIS ? "DATE_FORMAT(moment,'%Y-%m-%d %H:%i:%s.%f'),CAST(day AS CHAR)" : "to_char(moment,'YYYY-MM-DD HH24:MI:SS.US'),day::text") + " FROM " + table(p, name) + " ORDER BY id"); }
    private void equalMixed() throws Exception { assertEquals(mixedRows(POSTGRESQL, source), mixedRows(DORIS, target)); }
    private List<List<Object>> mixedRows(ProductTypeEnum p, String name) throws Exception { return rows(p, "SELECT id,amount,note," + (p == DORIS ? "DATE_FORMAT(moment,'%Y-%m-%d %H:%i:%s.%f')" : "to_char(moment,'YYYY-MM-DD HH24:MI:SS.US')") + ",flag FROM " + table(p, name) + " ORDER BY id"); }
    private List<List<Object>> rows(ProductTypeEnum p, String text) throws Exception {
        try (var c = open(p); var s = c.createStatement(); var r = s.executeQuery(text)) {
            var result = new ArrayList<List<Object>>(); int count = r.getMetaData().getColumnCount();
            while (r.next()) { var row = new ArrayList<Object>(); for (int i = 1; i <= count; i++) {
                Object value = r.getObject(i); if (value instanceof BigDecimal d) value = d.stripTrailingZeros();
                else if (value instanceof Byte || value instanceof Short || value instanceof Integer) value = ((Number) value).longValue(); row.add(value);
            } result.add(row); } return result;
        }
    }
    private List<String> pgColumns() throws Exception {
        try (var c = open(POSTGRESQL); var s = c.prepareStatement("SELECT a.attname,pg_catalog.format_type(a.atttypid,a.atttypmod) FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class t ON t.oid=a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace WHERE n.nspname=? AND t.relname=? AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum")) {
            s.setString(1, schema); s.setString(2, target); try (var r = s.executeQuery()) { var result = new ArrayList<String>(); while (r.next()) result.add(r.getString(1) + "|" + r.getString(2)); return result; }
        }
    }
    private String showCreate() throws Exception { try (var c = open(DORIS); var s = c.createStatement(); var r = s.executeQuery("SHOW CREATE TABLE " + table(DORIS, target))) { assertTrue(r.next()); return r.getString(2); } }
    private static String model(String model, String keys) { return " ENGINE=OLAP " + model + " KEY(" + keys + ") DISTRIBUTED BY HASH(" + keys + ") BUCKETS 1 PROPERTIES('replication_num'='1'" + (model.equals("UNIQUE") ? ",'enable_unique_key_merge_on_write'='true'" : "") + ")"; }
    private String table(ProductTypeEnum p, String name) { return q(p, schema) + "." + q(p, name); }
    private static String q(ProductTypeEnum p, String name) { return RestrictedSelectGuard.quotePhysicalIdentifier(p, name); }
    private static String url(ProductTypeEnum p) { return System.getProperty(p == DORIS ? "datahub.test.doris.url" : "datahub.test.postgres.url"); }
    private static String user(ProductTypeEnum p) { return p == DORIS ? "root" : System.getProperty("datahub.test.postgres.user", "dataforge_test"); }
    private static Connection open(ProductTypeEnum p) throws SQLException { return DriverManager.getConnection(url(p), user(p), ""); }
    private static DriverManagerDataSource ds(ProductTypeEnum p) { return new DriverManagerDataSource(url(p), user(p), ""); }
    private static void sql(ProductTypeEnum p, String... statements) throws Exception { try (var c = open(p); var s = c.createStatement()) { s.setQueryTimeout(30); for (String text : statements) s.execute(text); } }
}
