package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDB2Impl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Public FULL and PK diff on a task-owned native DB2 database; no substitute database is admitted. */
@Timeout(180)
@EnabledIfSystemProperty(named = "datahub.test.db2.url", matches = "jdbc:db2://127[.]0[.]0[.]1:[0-9]+/Q17DB")
@EnabledIfEnvironmentVariable(named = "DATAHUB_DB2_TEST_PASSWORD", matches = ".+")
class MigrationDb2Test {
    private Connection control;
    private final String prefix = "M88_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final Set<String> tables = new LinkedHashSet<>();

    @BeforeEach void setup() throws Exception {
        control = DriverManager.getConnection(url(), user(), password());
        assertEquals("DB2/LINUXX8664", control.getMetaData().getDatabaseProductName());
        assertEquals(12, control.getMetaData().getDatabaseMajorVersion());
    }

    @AfterEach void cleanup() throws Exception {
        if (control != null) try (var ignored = control) {
            for (var name : tables) try { sql("DROP TABLE " + table(name)); } catch (SQLException e) {
                if (!"42704".equals(e.getSQLState())) throw e;
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fullThenRepeatedDiffPreservesExactValuesAndPhysicalNames(boolean quoted) throws Exception {
        String source = prefix + (quoted ? " source\"; rows" : "_SOURCE");
        String target = prefix + (quoted ? " target\"; rows" : "_TARGET");
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

    @Test void automaticDdlRetainsDecimalAndTimestampPrecision() throws Exception {
        String source = prefix + "_SOURCE", target = prefix + "_CREATED";
        create(source); seed(source); tables.add(target);
        run(source, target, false);
        assertEquals(rows(source), rows(target));
        try (var statement = control.createStatement(); var rows = statement.executeQuery(
                "SELECT \"amount\",\"event_time\" FROM " + table(target) + " WHERE 1=0")) {
            assertEquals(31, rows.getMetaData().getPrecision(1));
            assertEquals(10, rows.getMetaData().getScale(1));
            assertEquals(6, rows.getMetaData().getScale(2));
        }
    }

    @Test void nationalClobUsesTheDefaultDriverCodecThroughFullAndRepeatedDiff() throws Exception {
        String from = prefix + "_LOB_SOURCE", to = prefix + "_LOB_TARGET";
        for (String name : List.of(from, to)) {
            tables.add(name); sql("CREATE TABLE " + table(name) + "(\"id\" BIGINT NOT NULL PRIMARY KEY,\"value\" DBCLOB(256K))");
        }
        String value = " 中文é😀'\\\n ".repeat(8000);
        try (var statement = control.prepareStatement("INSERT INTO " + table(from) + " VALUES(?,?)")) {
            statement.setLong(1, 1); statement.setString(2, value); statement.executeUpdate();
            statement.setLong(1, 2); statement.setNull(2, Types.NCLOB); statement.executeUpdate();
            statement.setLong(1, 3); statement.setString(2, ""); statement.executeUpdate();
        }
        try (var statement = control.createStatement(); var r = statement.executeQuery("SELECT \"value\" FROM " + table(from) + " WHERE \"id\"=1")) {
            assertEquals(Types.NCLOB, r.getMetaData().getColumnType(1)); assertEquals("DBCLOB", r.getMetaData().getColumnTypeName(1));
            assertTrue(r.next()); assertInstanceOf(NClob.class, r.getObject(1));
        }
        run(from, to, false); assertEquals(lobRows(from), lobRows(to));
        sql("DELETE FROM " + table(from) + " WHERE \"id\"=2");
        try (var statement = control.prepareStatement("UPDATE " + table(from) + " SET \"value\"=? WHERE \"id\"=1")) {
            statement.setString(1, value + "尾部  "); statement.executeUpdate();
        }
        sql("INSERT INTO " + table(from) + " VALUES(4,NULL)");
        run(from, to, true); assertEquals(lobRows(from), lobRows(to));
        run(from, to, true); assertEquals(lobRows(from), lobRows(to));
    }

    @Test void cursorMetadataReadsNationalClobAsBoundedDetachedText() throws Exception {
        String name = prefix + "_LOB_CURSOR"; tables.add(name);
        sql("CREATE TABLE " + table(name) + "(\"id\" BIGINT NOT NULL PRIMARY KEY,\"value\" DBCLOB(256K))");
        String value = "中文😀 ".repeat(9000);
        try (var p = control.prepareStatement("INSERT INTO " + table(name) + " VALUES(1,?)")) { p.setString(1, value); p.executeUpdate(); }
        var shape = cn.iocoder.yudao.framework.dbswitch.product.db2.Db2CursorMetadata.read(control, "DB2INST1", name, 15);
        var column = shape.columns().get("value");
        assertEquals("dbclob", column.getMetadataEvidence().nativeType().value());
        assertEquals(Types.NCLOB, column.getFieldType());
        try (var statement = control.createStatement(); var r = statement.executeQuery("SELECT \"value\" FROM " + table(name))) {
            assertTrue(r.next()); assertEquals(value, cn.iocoder.yudao.framework.dbswitch.product.db2.Db2CursorMetadata.read(r, 1, column, 1024 * 1024));
        }
        try (var statement = control.createStatement(); var r = statement.executeQuery("SELECT \"value\" FROM " + table(name))) {
            assertTrue(r.next()); assertThrows(IllegalArgumentException.class, () -> cn.iocoder.yudao.framework.dbswitch.product.db2.Db2CursorMetadata.read(r, 1, column, 32));
        }
        assertThrows(SQLException.class, () -> cn.iocoder.yudao.framework.dbswitch.product.db2.Db2WriteColumns.of(column));
    }

    private Map<Long, String> lobRows(String name) throws SQLException {
        var values = new LinkedHashMap<Long, String>();
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT \"id\",\"value\" FROM " + table(name) + " ORDER BY \"id\"")) {
            while (rows.next()) values.put(rows.getLong(1), rows.getString(2));
        }
        return values;
    }

    @Test void commentDdlPreservesQuotesUnicodeAndStatementLikeText() throws Exception {
        String name = prefix + " O'Reilly\"; rows", field = "note'\"; field";
        tables.add(name); sql("CREATE TABLE " + table(name) + "(\"" + field.replace("\"", "\"\"") + "\" INTEGER)");
        String remark = "中文 O'Reilly \"quoted\"; DROP TABLE never_execute; -- \\ tail";
        var td = new TableDescription(); td.setSchemaName("DB2INST1"); td.setTableName(name); td.setRemarks(remark);
        var cd = new ColumnDescription(); cd.setFieldName(field); cd.setRemarks(remark);
        for (var ddl : new DatabaseDB2Impl().getTableColumnCommentDefinition(td, List.of(cd))) sql(ddl);
        for (String catalog : List.of("SYSCAT.TABLES", "SYSCAT.COLUMNS")) {
            try (var statement = control.prepareStatement("SELECT REMARKS FROM " + catalog + " WHERE TABSCHEMA=? AND TABNAME=?")) {
                statement.setString(1, "DB2INST1"); statement.setString(2, name);
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next()); assertEquals(remark, result.getString(1)); assertFalse(result.next());
                }
            }
        }
    }

    @Test void automaticDdlKeepsDecimalKeysAndNineDigitTimestamps() throws Exception {
        String source = prefix + "_EXACT_SOURCE", target = prefix + "_EXACT_TARGET";
        tables.add(source); tables.add(target);
        sql("CREATE TABLE " + table(source) + "(\"key\" DECIMAL(31,0) NOT NULL PRIMARY KEY,\"stamp\" TIMESTAMP(9))");
        sql("INSERT INTO " + table(source) + " VALUES(1234567890123456789012345678901,'2026-10-08 12:34:56.123456789')");
        run(source, target, false);
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT \"key\",\"stamp\" FROM " + table(target))) {
            assertEquals(31, rows.getMetaData().getPrecision(1)); assertEquals(9, rows.getMetaData().getScale(2));
            assertTrue(rows.next()); assertEquals(new BigDecimal("1234567890123456789012345678901"), rows.getBigDecimal(1));
            assertEquals(Timestamp.valueOf("2026-10-08 12:34:56.123456789"), rows.getTimestamp(2)); assertFalse(rows.next());
        }
    }

    private void create(String name) throws Exception {
        tables.add(name);
        sql("CREATE TABLE " + table(name) + "(\"part\" SMALLINT NOT NULL,\"id\" BIGINT NOT NULL,"
                + "\"amount\" DECIMAL(31,10),\"note\" VARCHAR(256),\"payload\" VARBINARY(16),"
                + "\"event_time\" TIMESTAMP(6),PRIMARY KEY(\"part\",\"id\"))");
    }

    private void seed(String name) throws Exception {
        try (var statement = control.prepareStatement("INSERT INTO " + table(name) + " VALUES(?,?,?,?,?,?)")) {
            for (int i = 0; i < 503; i++) {
                statement.setShort(1, (short) (i % 2)); statement.setLong(2, 9007199254740993L + i);
                statement.setBigDecimal(3, i % 3 == 0 ? null : new BigDecimal("123456789012345678901.1234567890"));
                statement.setString(4, i % 3 == 0 ? null : "中文'\\\n😀" + i);
                statement.setBytes(5, new byte[]{0, (byte) i, (byte) 255});
                statement.setTimestamp(6, Timestamp.valueOf("2026-10-08 12:34:56.123456")); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private DbSwitchResult run(String from, String to, boolean diff) throws Exception {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.DB2); source.setUrl(url()); source.setUsername(user()); source.setPassword(password());
        source.setSourceSchema("DB2INST1"); source.setSourceIncludes(from); source.setFetchSize(500); source.setPoolSize(1);
        source.setRegexTableMapper(List.of(new PatternMapper("^" + Pattern.quote(from) + "$", to))); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(ProductTypeEnum.DB2);
        target.setUrl(url()); target.setUsername(user()); target.setPassword(password()); target.setTargetSchema("DB2INST1");
        target.setPoolSize(1); target.setWriterEngineInsert(true); target.setChangeDataSync(diff);
        if (diff) target.setChangeDataSyncType("PK_ORDER_COMPARE");
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = new MigrationService(request, ignored -> { }, null, runtime).run();
            assertEquals(1, result.getTableResultList().size());
            assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString()); return result;
        }
    }

    private List<List<Object>> rows(String name) throws Exception {
        var values = new ArrayList<List<Object>>();
        try (var statement = control.createStatement(); var rows = statement.executeQuery(
                "SELECT \"part\",\"id\",\"amount\",\"note\",\"payload\",\"event_time\" FROM " + table(name) + " ORDER BY \"part\",\"id\"")) {
            while (rows.next()) values.add(Arrays.asList(rows.getShort(1), rows.getLong(2), rows.getBigDecimal(3), rows.getString(4),
                    rows.getBytes(5) == null ? null : HexFormat.of().formatHex(rows.getBytes(5)), rows.getTimestamp(6)));
        }
        return values;
    }

    private void sql(String sql) throws SQLException { try (var statement = control.createStatement()) { statement.setQueryTimeout(20); statement.execute(sql); } }
    private static String table(String name) { return "\"DB2INST1\".\"" + name.replace("\"", "\"\"") + '"'; }
    private static String url() { return System.getProperty("datahub.test.db2.url"); }
    private static String user() { return System.getProperty("datahub.test.db2.user", "db2inst1"); }
    private static String password() { return System.getenv("DATAHUB_DB2_TEST_PASSWORD"); }
}
