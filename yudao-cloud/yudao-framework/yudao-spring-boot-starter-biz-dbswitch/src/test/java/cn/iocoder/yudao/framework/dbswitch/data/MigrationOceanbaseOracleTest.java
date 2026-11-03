package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOceanbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Actual OceanBase Oracle tenant; the product and compatibility mode are both verified before any test DDL. */
@Timeout(180)
@EnabledIfSystemProperty(named = "datahub.test.oboracle.url", matches = "jdbc:oceanbase://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfSystemProperty(named = "datahub.test.oboracle.driver", matches = ".+")
class MigrationOceanbaseOracleTest {
    private final String prefix = "O88_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    private final Set<String> tables = new LinkedHashSet<>();
    private ConfiguredJdbcDrivers drivers;
    private DbSwitchRuntime runtime;
    private Connection control;

    @BeforeEach void setup() throws Exception {
        Path jar = Path.of(System.getProperty("datahub.test.oboracle.driver")).toRealPath();
        var driver = new DbSwitchProperties.Driver(); driver.setPath(jar.toString());
        driver.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.OCEANBASE, driver));
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), drivers);
        control = runtime.openConnection(ProductTypeEnum.OCEANBASE, null, url(), user(), password());
        assertEquals("OceanBase", control.getMetaData().getDatabaseProductName());
        assertEquals("4.2.1.7", control.getMetaData().getDatabaseProductVersion());
        try (var s = control.createStatement(); var r = s.executeQuery("SHOW VARIABLES LIKE 'ob_compatibility_mode'")) {
            assertTrue(r.next()); assertEquals("ORACLE", r.getString(2)); assertFalse(r.next());
        }
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (control != null) try (var ignored = control) {
                for (String name : tables) try { sql("DROP TABLE " + table(name) + " PURGE"); }
                catch (SQLException failure) { if (failure.getErrorCode() != 942) throw failure; }
            }
        } finally { if (runtime != null) runtime.close(); if (drivers != null) drivers.close(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fullThenRepeatedDiffPreservesValuesAndPhysicalNames(boolean quoted) throws Exception {
        String source = prefix + (quoted ? " source; rows" : "_SOURCE");
        String target = prefix + (quoted ? " target; rows" : "_TARGET");
        create(source); create(target); seed(source);
        var full = run(source, target, false);
        assertEquals(503, full.getTableResultList().getFirst().getTransferSummary().acknowledgedRows());
        assertEquals(rows(source), rows(target));
        sql("DELETE FROM " + table(source) + " WHERE \"id\"=9007199254740993");
        sql("UPDATE " + table(source) + " SET \"amount\"=-0.0000000001,\"note\"='更新' WHERE \"id\"=9007199254740994");
        sql("INSERT INTO " + table(source) + "(\"part\",\"id\",\"amount\") VALUES(1,9223372036854775807,999999999999999999999.9999999999)");
        run(source, target, true); assertEquals(rows(source), rows(target));
        run(source, target, true); assertEquals(rows(source), rows(target));
    }

    @Test void originalServerRefusesEmbeddedDoubleQuoteIdentifiers() {
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> sql("CREATE TABLE " + table(prefix + "_invalid\"name") + "(\"id\" NUMBER(19,0))"));
    }

    @Test void nullableAddRefusesRequiredColumnsWithoutChangingTheTable() throws Exception {
        String name = prefix + "_ADD_BOUNDARY"; tables.add(name);
        sql("CREATE TABLE " + table(name) + "(\"id\" NUMBER(19,0))"); sql("INSERT INTO " + table(name) + " VALUES(7)");
        var column = new ColumnDescription(); column.setDbType(ProductTypeEnum.OCEANBASE); column.setFieldName("added");
        column.setFieldType(Types.INTEGER); column.setFieldTypeName("INTEGER"); column.setPrecisionSize(9); column.setNullable(false);
        var dialect = new DatabaseOceanbaseImpl(); dialect.prepareDialect(control);
        assertThrows(SQLFeatureNotSupportedException.class, () -> dialect.getAddColumnSql(schema(), name, column));
        try (var s = control.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(name))) {
            assertEquals(1, r.getMetaData().getColumnCount()); assertTrue(r.next()); assertEquals(7, r.getInt(1)); assertFalse(r.next());
        }
    }

    @Test void automaticDdlRetainsNumericBinaryAndTimestampData() throws Exception {
        String source = prefix + "_SOURCE", target = prefix + "_CREATED";
        create(source); seed(source); tables.add(target);
        run(source, target, false); assertEquals(rows(source), rows(target));
        var dialect = new DatabaseOceanbaseImpl();
        var columns = dialect.queryTableColumnMeta(control, schema(), target);
        assertEquals(List.of("part", "id", "amount", "note", "payload", "event_time"), columns.stream().map(ColumnDescription::getFieldName).toList());
        var decimal = columns.stream().filter(c -> c.getFieldName().equals("amount")).findFirst().orElseThrow();
        assertEquals(31, decimal.getPrecisionSize()); assertEquals(10, decimal.getScaleSize());
        assertNotNull(dialect.getTableDDL(control, schema(), target));
        assertEquals(List.of("part", "id"), dialect.queryTablePrimaryKeys(control, schema(), target));
    }

    @Test void nativeMetadataAndCommentDdlPreserveUnicodeQuotesAndStatementLikeText() throws Exception {
        String name = prefix + "_COMMENTS", field = "note"; tables.add(name);
        sql("CREATE TABLE " + table(name) + "(\"note\" NVARCHAR2(80))");
        String remark = " 中文 O'Reilly \"quoted\"; DROP TABLE never_execute; -- \\ tail ";
        var td = new TableDescription(); td.setSchemaName(schema()); td.setTableName(name); td.setRemarks(remark);
        var cd = new ColumnDescription(); cd.setFieldName(field); cd.setRemarks(remark);
        var dialect = new DatabaseOceanbaseImpl(); dialect.prepareDialect(control);
        for (String ddl : dialect.getTableColumnCommentDefinition(td, List.of(cd))) sql(ddl);
        assertFalse(dialect.observedMysqlMode());
        assertTrue(dialect.querySchemaList(control).contains(schema()));
        assertTrue(dialect.queryTableList(control, schema()).stream().anyMatch(t -> name.equals(t.getTableName())));
        assertEquals(remark, dialect.queryTableMeta(control, schema(), name).getRemarks());
        assertEquals(remark, dialect.queryTableColumnMeta(control, schema(), name).getFirst().getRemarks());
        assertTrue(dialect.getTableDDL(control, schema(), name).contains("CREATE TABLE"));
    }

    @Test void primaryKeyOnlyDiffHandlesInsertDeleteAndReplay() throws Exception {
        String source = prefix + "_SOURCE", target = prefix + "_TARGET";
        for (String name : List.of(source, target)) { tables.add(name); sql("CREATE TABLE " + table(name) + "(\"id\" NUMBER(19,0) PRIMARY KEY)"); }
        sql("INSERT INTO " + table(source) + " VALUES(1)"); sql("INSERT INTO " + table(source) + " VALUES(3)");
        sql("INSERT INTO " + table(target) + " VALUES(1)"); sql("INSERT INTO " + table(target) + " VALUES(2)");
        run(source, target, true); run(source, target, true);
        try (var s = control.createStatement(); var r = s.executeQuery("SELECT \"id\" FROM " + table(target) + " ORDER BY \"id\"")) {
            assertTrue(r.next()); assertEquals(1, r.getLong(1)); assertTrue(r.next()); assertEquals(3, r.getLong(1)); assertFalse(r.next());
        }
    }

    @Test void automaticDdlAndDiffPreserveLobsAndOracleDateTime() throws Exception {
        String source = prefix + "_LOB_SOURCE", target = prefix + "_LOB_TARGET";
        tables.add(source); tables.add(target);
        sql("CREATE TABLE " + table(source) + "(\"id\" NUMBER(19,0) PRIMARY KEY,\"note\" CLOB,\"data\" BLOB,\"day\" DATE)");
        String text = " 中文é😀'\\\n ".repeat(8000); byte[] bytes = new byte[100_003];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 251);
        try (var p = control.prepareStatement("INSERT INTO " + table(source) + " VALUES(?,?,?,?)")) {
            p.setLong(1, 1); p.setString(2, text); p.setBytes(3, bytes); p.setTimestamp(4, Timestamp.valueOf("2026-10-08 12:34:56")); p.executeUpdate();
            p.setLong(1, 2); p.setNull(2, Types.CLOB); p.setNull(3, Types.BLOB); p.setNull(4, Types.TIMESTAMP); p.executeUpdate();
        }
        run(source, target, false); assertEquals(lobRows(source), lobRows(target));
        try (var p = control.prepareStatement("UPDATE " + table(source) + " SET \"note\"=? WHERE \"id\"=1")) {
            p.setString(1, text + "尾部  "); p.executeUpdate();
        }
        sql("DELETE FROM " + table(source) + " WHERE \"id\"=2"); sql("INSERT INTO " + table(source) + "(\"id\") VALUES(3)");
        run(source, target, true); assertEquals(lobRows(source), lobRows(target));
        run(source, target, true); assertEquals(lobRows(source), lobRows(target));
    }

    private List<List<Object>> lobRows(String name) throws SQLException {
        var values = new ArrayList<List<Object>>();
        try (var s = control.createStatement(); var r = s.executeQuery("SELECT \"id\",\"note\",\"data\",\"day\" FROM " + table(name) + " ORDER BY \"id\"")) {
            while (r.next()) values.add(Arrays.asList(r.getLong(1), r.getString(2),
                    r.getBytes(3) == null ? null : HexFormat.of().formatHex(r.getBytes(3)), r.getTimestamp(4)));
        }
        return values;
    }

    private void create(String name) throws SQLException {
        tables.add(name); sql("CREATE TABLE " + table(name) + "(\"part\" NUMBER(3,0) NOT NULL,\"id\" NUMBER(19,0) NOT NULL,"
                + "\"amount\" NUMBER(31,10),\"note\" NVARCHAR2(192),\"payload\" RAW(128),\"event_time\" TIMESTAMP(6),PRIMARY KEY(\"part\",\"id\"))");
    }

    private void seed(String name) throws SQLException {
        try (var p = control.prepareStatement("INSERT INTO " + table(name) + " VALUES(?,?,?,?,?,?)")) {
            for (int i = 0; i < 503; i++) {
                p.setInt(1, i % 2); p.setLong(2, 9007199254740993L + i);
                p.setBigDecimal(3, i % 3 == 0 ? null : new BigDecimal("123456789012345678901.1234567890"));
                p.setString(4, i % 3 == 0 ? null : "中文'\\\n😀" + i); p.setBytes(5, new byte[]{0, (byte) i, (byte) 255});
                p.setTimestamp(6, Timestamp.valueOf("2026-10-08 12:34:56.123456")); p.addBatch();
            }
            p.executeBatch();
        }
    }

    private DbSwitchResult run(String from, String to, boolean diff) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.OCEANBASE); source.setUrl(url()); source.setUsername(user()); source.setPassword(password());
        source.setSourceSchema(schema()); source.setSourceIncludes(from); source.setPoolSize(1); source.setFetchSize(500);
        source.setRegexTableMapper(List.of(new PatternMapper("^" + Pattern.quote(from) + "$", to))); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(ProductTypeEnum.OCEANBASE); target.setUrl(url());
        target.setUsername(user()); target.setPassword(password()); target.setTargetSchema(schema()); target.setPoolSize(1);
        target.setWriterEngineInsert(true); target.setChangeDataSync(diff); if (diff) target.setChangeDataSyncType("PK_ORDER_COMPARE");
        var result = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertEquals(1, result.getTableResultList().size()); assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        return result;
    }

    private List<List<Object>> rows(String name) throws SQLException {
        var values = new ArrayList<List<Object>>();
        try (var s = control.createStatement(); var r = s.executeQuery("SELECT \"part\",\"id\",\"amount\",\"note\",\"payload\",\"event_time\" FROM " + table(name) + " ORDER BY \"part\",\"id\"")) {
            while (r.next()) values.add(Arrays.asList(r.getInt(1), r.getLong(2), r.getBigDecimal(3), r.getString(4),
                    r.getBytes(5) == null ? null : HexFormat.of().formatHex(r.getBytes(5)), r.getTimestamp(6)));
        }
        return values;
    }

    private static String q(String name) { return '"' + name.replace("\"", "\"\"") + '"'; }
    private static String table(String name) { return q(schema()) + '.' + q(name); }
    private static String schema() { return System.getProperty("datahub.test.oboracle.schema", "DBS88"); }
    private static String url() { return System.getProperty("datahub.test.oboracle.url"); }
    private static String user() { return System.getProperty("datahub.test.oboracle.user", "DBS88@dbswitch88_oracle"); }
    private static String password() { return System.getenv().getOrDefault("DATAHUB_OBORACLE_TEST_PASSWORD", ""); }
    private void sql(String sql) throws SQLException { try (var s = control.createStatement()) { s.setQueryTimeout(30); s.execute(sql); } }
}
