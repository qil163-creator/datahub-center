package cn.iocoder.yudao.framework.dbswitch.dbchange;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.type.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.RecordTransformer;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class ChangeCalculatorPostgreSqlTest {
    @TempDir Path temporary;
    HikariDataSource datasource;
    Connection connection;
    String schema;
    @BeforeEach void setup() throws Exception {
        datasource = new HikariDataSource(); datasource.setJdbcUrl(System.getProperty("datahub.test.postgres.url"));
        datasource.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test"));
        datasource.setPassword(System.getProperty("datahub.test.postgres.password", ""));
        datasource.setMaximumPoolSize(4); datasource.setMinimumIdle(0);
        connection = datasource.getConnection(); schema = "diff_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
    }
    @AfterEach void cleanup() throws Exception {
        try { if (connection != null) { sql("DROP SCHEMA " + schema + " CASCADE"); connection.close(); } }
        finally { if (datasource != null) datasource.close(); }
        try (var children = Files.list(temporary)) { assertEquals(0, children.count(), "operation must clean encrypted runs"); }
    }
    private DbSwitchOptions options(long disk) {
        return new DbSwitchOptions(1024 * 1024, false, true, 10, 0, 300000, 30000, 0,
                new DiffSortLimits(4096, disk, temporary.toString()));
    }
    private TaskParamEntity task(List<String> fields, Map<String, String> mapping, RecordTransformer transformer) {
        return TaskParamEntity.builder().oldDataSource(datasource).newDataSource(datasource)
                .oldProductType(ProductTypeEnum.POSTGRESQL).newProductType(ProductTypeEnum.POSTGRESQL)
                .oldSchemaName(schema).newSchemaName(schema).oldTableName("old_rows").newTableName("new_rows")
                .sourceType(SourceType.TABLE.getCode()).sourcePrimaryKeys(List.of("id"))
                .fieldColumns(fields).columnsMap(mapping).transformer(transformer).build();
    }
    private ChangeCalculatorService calculator(long disk) {
        var calculator = new ChangeCalculatorService(DbSwitchProductRegistry.discover(), BatchLimits.defaults(), options(disk));
        calculator.setFetchSize(2); return calculator;
    }
    private IDatabaseRowHandler forbidden(AtomicInteger calls) {
        return new IDatabaseRowHandler() {
            @Override public void handle(List<String> fields, Object[] row, RecordChangeTypeEnum type) { calls.incrementAndGet(); }
            @Override public void destroy(List<String> fields) { calls.incrementAndGet(); }
        };
    }

    @Test void unicodeDatabaseOrderCannotTurnExistingSupplementaryKeyIntoAnInsert() throws Exception {
        sql("CREATE TABLE " + schema + ".new_rows(id text COLLATE \"C\" PRIMARY KEY, note text)");
        sql("CREATE TABLE " + schema + ".old_rows(id text COLLATE \"C\" PRIMARY KEY, note text)");
        sql("INSERT INTO " + schema + ".new_rows VALUES ('😀','unchanged')");
        sql("INSERT INTO " + schema + ".old_rows VALUES ('😀','unchanged'),('\uE000','delete')");
        try (var runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), options(1024 * 1024), null)) {
            var sync = runtime.synchronizer(datasource, ProductTypeEnum.POSTGRESQL);
            sync.prepare(schema, "old_rows", List.of("id", "note"), List.of("id"));
            var observed = new ArrayList<RecordChangeTypeEnum>();
            calculator(1024 * 1024).executeCalculate(task(List.of("id", "note"), Map.of(), null), new IDatabaseRowHandler() {
                @Override public void handle(List<String> fields, Object[] row, RecordChangeTypeEnum type) {
                    observed.add(type);
                    switch (type) {
                        case VALUE_INSERT -> sync.executeInsert(List.<Object[]>of(row));
                        case VALUE_CHANGED -> sync.executeUpdate(List.<Object[]>of(row));
                        case VALUE_DELETED -> sync.executeDelete(List.<Object[]>of(row));
                        default -> fail("Unexpected unchanged callback");
                    }
                }
                @Override public void destroy(List<String> fields) { }
            });
            assertEquals(List.of(RecordChangeTypeEnum.VALUE_DELETED), observed);
            try (var rows = connection.createStatement().executeQuery("SELECT id,note FROM " + schema + ".old_rows")) {
                assertTrue(rows.next()); assertEquals("😀", rows.getString(1)); assertEquals("unchanged", rows.getString(2)); assertFalse(rows.next());
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "null", "transformed-duplicate", "shape", "disk"})
    void everyInputIsValidatedBeforeAnyTargetCallback(String failure) throws Exception {
        sql("CREATE TABLE " + schema + ".new_rows(id bigint, note text)");
        sql("CREATE TABLE " + schema + ".old_rows(id bigint PRIMARY KEY, note text)");
        sql("INSERT INTO " + schema + ".old_rows VALUES (999,'keep')");
        sql("INSERT INTO " + schema + ".new_rows SELECT n,'payload-'||n FROM generate_series(1,200) n");
        if (failure.equals("duplicate")) sql("INSERT INTO " + schema + ".new_rows VALUES (1,'late')");
        if (failure.equals("null")) sql("INSERT INTO " + schema + ".new_rows VALUES (NULL,'late')");
        RecordTransformer transformer = failure.equals("transformed-duplicate") ? (s, t, f, r) -> new Object[]{1L, r[1]}
                : failure.equals("shape") ? (s, t, f, r) -> new Object[]{r[0]} : null;
        var calls = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> calculator(failure.equals("disk") ? 1024 : 1024 * 1024)
                .executeCalculate(task(List.of("id", "note"), Map.of(), transformer), forbidden(calls)));
        assertEquals(0, calls.get());
        try (var rows = connection.createStatement().executeQuery("SELECT id,note FROM " + schema + ".old_rows")) {
            assertTrue(rows.next()); assertEquals(999, rows.getLong(1)); assertEquals("keep", rows.getString(2)); assertFalse(rows.next());
        }
    }

    @Test void implicitAllColumnsUsesSourceNamesAndMapsTargetPhysicalProjection() throws Exception {
        sql("CREATE TABLE " + schema + ".new_rows(id bigint PRIMARY KEY, note text)");
        sql("CREATE TABLE " + schema + ".old_rows(target_id bigint PRIMARY KEY, target_note text)");
        sql("INSERT INTO " + schema + ".new_rows VALUES (9007199254740993,'中文')");
        sql("INSERT INTO " + schema + ".old_rows VALUES (9007199254740993,'中文')");
        var calls = new AtomicInteger();
        calculator(1024 * 1024).executeCalculate(task(null, Map.of("id", "target_id", "note", "target_note"), null), forbidden(calls));
        assertEquals(1, calls.get(), "only destroy, no false DML callbacks");
    }
    private void sql(String value) throws Exception { try (var statement = connection.createStatement()) { statement.execute(value); } }
}
