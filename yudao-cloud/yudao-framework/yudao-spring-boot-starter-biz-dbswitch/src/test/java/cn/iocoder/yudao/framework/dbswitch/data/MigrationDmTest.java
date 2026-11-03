package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Public migration with a verified, isolated original DM driver bundle and a native DM8 server. */
@Timeout(120)
@EnabledIfSystemProperty(named = "datahub.test.dm.url", matches = "jdbc:dm://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfSystemProperty(named = "datahub.test.dm.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_DM_TEST_PASSWORD", matches = ".+")
class MigrationDmTest {
    private Connection control;
    private String schema;
    private ConfiguredJdbcDrivers controlDrivers;
    private DbSwitchRuntime controlRuntime;

    @BeforeEach void setup() throws Exception {
        controlDrivers = configuredDrivers();
        controlRuntime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), controlDrivers);
        control = controlRuntime.openConnection(ProductTypeEnum.DM, null, url(), "SYSDBA", password());
        assertEquals("DM DBMS", control.getMetaData().getDatabaseProductName());
        schema = "MIG_DM_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        sql("CREATE SCHEMA " + q(schema));
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (control != null) try (var ignored = control) {
                if (schema != null) sql("DROP SCHEMA " + q(schema) + " CASCADE");
            }
        } finally {
            if (controlRuntime != null) controlRuntime.close();
            if (controlDrivers != null) controlDrivers.close();
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fullThenDiffRetainsExactValuesAndPhysicalNames(boolean quoted) throws Exception {
        String source = quoted ? "source\"; rows" : "source_rows";
        String target = quoted ? "target\"; rows" : "target_rows";
        String note = quoted ? "note\"; value" : "note";
        sql("CREATE TABLE " + table(source) + columns(note));
        sql("CREATE TABLE " + table(target) + columns(note));
        seed(source, note);
        assertSuccess(run(source, target, false));
        assertEquals(rows(source, note), rows(target, note));
        sql("DELETE FROM " + table(source) + " WHERE \"id\"=9007199254740993");
        sql("UPDATE " + table(source) + " SET \"amount\"=-0.000000000000000001," + q(note) + "='更新' WHERE \"id\"=9007199254740994");
        sql("INSERT INTO " + table(source) + " (\"part\",\"id\",\"amount\") VALUES(1,9223372036854775807,99999999999999999999.999999999999999999)");
        assertSuccess(run(source, target, true));
        assertEquals(rows(source, note), rows(target, note));
        assertSuccess(run(source, target, true));
        assertEquals(rows(source, note), rows(target, note));
    }

    @ParameterizedTest @ValueSource(ints = {0, 3, 6, 9})
    void autoCreatePreservesExactNumericBinaryAndTimestampValues(int scale) throws Exception {
        sql("CREATE TABLE " + table("source_rows") + columns("note").replace("TIMESTAMP(6)", "TIMESTAMP(" + scale + ")"));
        seed("source_rows", "note");
        assertSuccess(run("source_rows", "created_rows", false));
        assertEquals(rows("source_rows", "note"), rows("created_rows", "note"));
        try (var statement = control.createStatement(); var result = statement.executeQuery("SELECT \"amount\",\"event_time\" FROM " + table("created_rows") + " WHERE 1=0")) {
            assertEquals(38, result.getMetaData().getPrecision(1));
            assertEquals(18, result.getMetaData().getScale(1));
            assertEquals(scale, result.getMetaData().getScale(2));
        }
    }

    @Test void primaryKeyOnlyDiffHasNoEmptyUpdateClause() throws Exception {
        for (String name : List.of("source_rows", "target_rows")) sql("CREATE TABLE " + table(name) + "(\"id\" BIGINT PRIMARY KEY)");
        sql("INSERT INTO " + table("source_rows") + " VALUES(1),(3)");
        sql("INSERT INTO " + table("target_rows") + " VALUES(1),(2)");
        assertSuccess(run("source_rows", "target_rows", true));
        assertSuccess(run("source_rows", "target_rows", true));
        try (var statement = control.createStatement(); var result = statement.executeQuery("SELECT \"id\" FROM " + table("target_rows") + " ORDER BY \"id\"")) {
            assertTrue(result.next()); assertEquals(1, result.getLong(1));
            assertTrue(result.next()); assertEquals(3, result.getLong(1)); assertFalse(result.next());
        }
    }

    @ParameterizedTest @ValueSource(ints = {0, 3, 6})
    void timeValuesRetainFractionsThroughFullAndDiff(int scale) throws Exception {
        sql("CREATE TABLE " + table("source_rows") + "(\"id\" BIGINT PRIMARY KEY,\"at\" TIME(" + scale + "))");
        try (var statement = control.prepareStatement("INSERT INTO " + table("source_rows") + " VALUES(?,?)")) {
            statement.setLong(1, 1); statement.setObject(2, java.time.LocalTime.of(1, 2, 3, 123456789), Types.TIME); statement.executeUpdate();
            statement.setLong(1, 2); statement.setNull(2, Types.TIME); statement.executeUpdate();
        }
        assertSuccess(run("source_rows", "target_rows", false));
        assertEquals(timeRows("source_rows"), timeRows("target_rows"));
        try (var statement = control.prepareStatement("UPDATE " + table("source_rows") + " SET \"at\"=? WHERE \"id\"=1")) {
            statement.setObject(1, java.time.LocalTime.of(23, 59, 58, 987654321), Types.TIME); statement.executeUpdate();
        }
        assertSuccess(run("source_rows", "target_rows", true));
        assertEquals(timeRows("source_rows"), timeRows("target_rows"));
        assertSuccess(run("source_rows", "target_rows", true));
        assertEquals(timeRows("source_rows"), timeRows("target_rows"));
    }

    private List<List<Object>> timeRows(String name) throws Exception {
        var values = new ArrayList<List<Object>>();
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT \"id\",\"at\" FROM " + table(name) + " ORDER BY \"id\"")) {
            while (rows.next()) values.add(Arrays.asList(rows.getLong(1), rows.getObject(2, java.time.LocalTime.class)));
        }
        return values;
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.dm.legacy-driver", matches = ".+")
    void knownBrokenLegacyDriverFailsBeforeOverwritingTheTarget() throws Exception {
        for (String name : List.of("source_rows", "target_rows")) sql("CREATE TABLE " + table(name) + "(\"id\" BIGINT PRIMARY KEY)");
        sql("INSERT INTO " + table("source_rows") + " VALUES(1)");
        sql("INSERT INTO " + table("target_rows") + " VALUES(7)");
        var result = run("source_rows", "target_rows", false, Path.of(System.getProperty("datahub.test.dm.legacy-driver")));
        assertFalse(result.getIfAllSuccess().get());
        assertEquals(1, result.getTableResultList().size());
        assertEquals(0, result.getTableResultList().getFirst().getSyncCount().get());
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT \"id\" FROM " + table("target_rows"))) {
            assertTrue(rows.next()); assertEquals(7, rows.getLong(1)); assertFalse(rows.next());
        }
    }

    private DbSwitchResult run(String sourceTable, String targetTable, boolean diff) throws Exception {
        return run(sourceTable, targetTable, diff, Path.of(System.getProperty("datahub.test.dm.driver")));
    }

    private DbSwitchResult run(String sourceTable, String targetTable, boolean diff, Path driver) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.DM); source.setUrl(url()); source.setUsername("SYSDBA"); source.setPassword(password());
        source.setSourceSchema(schema); source.setSourceIncludes(sourceTable); source.setPoolSize(1); source.setFetchSize(500);
        source.setRegexTableMapper(List.of(new PatternMapper("^" + java.util.regex.Pattern.quote(sourceTable) + "$", targetTable)));
        request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(ProductTypeEnum.DM); target.setUrl(url());
        target.setUsername("SYSDBA"); target.setPassword(password()); target.setTargetSchema(schema); target.setPoolSize(1);
        target.setWriterEngineInsert(true); target.setChangeDataSync(diff);
        if (diff) target.setChangeDataSyncType("PK_ORDER_COMPARE");
        try (var drivers = configuredDrivers(driver);
             var runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), drivers)) {
            return new MigrationService(request, ignored -> {}, null, runtime).run();
        }
    }

    private ConfiguredJdbcDrivers configuredDrivers() throws Exception {
        return configuredDrivers(Path.of(System.getProperty("datahub.test.dm.driver")));
    }

    private ConfiguredJdbcDrivers configuredDrivers(Path path) throws Exception {
        Path jar = path.toRealPath();
        var driver = new DbSwitchProperties.Driver(); driver.setPath(jar.toString());
        driver.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        return new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.DM, driver));
    }

    private void seed(String table, String note) throws Exception {
        try (var statement = control.prepareStatement("INSERT INTO " + table(table) + " (\"part\",\"id\",\"amount\"," + q(note) + ",\"payload\",\"event_time\") VALUES (?,?,?,?,?,?)")) {
            statement.setQueryTimeout(20);
            for (int i = 0; i < 503; i++) {
                statement.setInt(1, i % 3); statement.setLong(2, 9007199254740993L + i);
                statement.setBigDecimal(3, i % 5 == 0 ? null : i % 2 == 0 ? new BigDecimal("-0.000000000000000001")
                        : new BigDecimal("12345678901234567890.123456789012345678"));
                statement.setString(4, i % 7 == 0 ? null : "中文'\\\"; " + i);
                statement.setBytes(5, i % 11 == 0 ? null : new byte[]{0, 2, (byte) 255});
                statement.setTimestamp(6, Timestamp.valueOf("2026-10-03 12:34:56.123456789"));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<List<Object>> rows(String name, String note) throws Exception {
        var values = new ArrayList<List<Object>>();
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT \"part\",\"id\",\"amount\"," + q(note) + ",\"payload\",\"event_time\" FROM " + table(name) + " ORDER BY \"part\",\"id\"")) {
            while (rows.next()) values.add(Arrays.asList(rows.getInt(1), rows.getLong(2), rows.getBigDecimal(3), rows.getString(4),
                    rows.getBytes(5) == null ? null : HexFormat.of().formatHex(rows.getBytes(5)), rows.getTimestamp(6)));
        }
        return values;
    }

    private String columns(String note) { return "(\"part\" INT NOT NULL,\"id\" BIGINT NOT NULL,\"amount\" DECIMAL(38,18)," + q(note)
            + " VARCHAR(192),\"payload\" BLOB,\"event_time\" TIMESTAMP(6),PRIMARY KEY(\"part\",\"id\"))"; }
    private String table(String name) { return q(schema) + '.' + q(name); }
    private static String q(String name) { return '"' + name.replace("\"", "\"\"") + '"'; }
    private static String url() { return System.getProperty("datahub.test.dm.url"); }
    private static String password() { return System.getenv("DATAHUB_DM_TEST_PASSWORD"); }
    private void sql(String sql) throws SQLException { try (var statement = control.createStatement()) { statement.setQueryTimeout(20); statement.execute(sql); } }
    private static void assertSuccess(DbSwitchResult result) { assertEquals(1, result.getTableResultList().size()); assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString()); }
}
