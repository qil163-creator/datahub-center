package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class NativeUpsertMySqlTest {
    private DriverManagerDataSource source;
    private Connection control;
    private String schema;

    @BeforeEach void setup() throws Exception {
        source = new DriverManagerDataSource(System.getProperty("datahub.test.mysql.url"),
                System.getProperty("datahub.test.mysql.user", "root"), System.getProperty("datahub.test.mysql.password", ""));
        control = source.getConnection();
        schema = "upsert_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE DATABASE " + schema);
    }

    @AfterEach void cleanup() throws Exception {
        if (control != null) {
            try { if (schema != null) sql("DROP DATABASE IF EXISTS " + schema); }
            finally { control.close(); }
        }
    }

    private void sql(String sql) throws Exception {
        try (var statement = control.createStatement()) { statement.execute(sql); }
    }
    private IDatabaseUpsertWriter writer() {
        return DbSwitchProductRegistry.discover().upsertWriter(source, ProductTypeEnum.MYSQL);
    }
    private long count() throws Exception {
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM " + schema + ".records")) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }

    @Test void acknowledgeInputRowsRatherThanMysqlAffectedRowsAndPreserveDecimals() throws Exception {
        sql("CREATE TABLE " + schema + ".records(id integer PRIMARY KEY, value decimal(38,8)) ENGINE=InnoDB");
        var writer = writer();
        writer.prepareUpsert(schema, "records", List.of("id", "value"), List.of("id"));
        var exact = new BigDecimal("123456789012345678901234567890.12345678");
        var batch = List.of(new Object[]{1, BigDecimal.ZERO}, new Object[]{1, exact}, new Object[]{2, exact});
        assertEquals(3, writer.writeWithReceipt(List.of("id", "value"), batch).acknowledgedRows());
        assertEquals(2, count());
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT value FROM " + schema + ".records")) {
            while (rows.next()) assertEquals(exact, rows.getBigDecimal(1));
        }
        assertEquals(BigDecimal.ZERO, batch.getFirst()[1]);
    }

    @Test void concurrentMissingKeysUseOneNativeStatement() throws Exception {
        sql("CREATE TABLE " + schema + ".records(id integer PRIMARY KEY, value varchar(30)) ENGINE=InnoDB");
        var first = writer(); var second = writer();
        first.prepareUpsert(schema, "records", List.of("id", "value"), List.of("id"));
        second.prepareUpsert(schema, "records", List.of("id", "value"), List.of("id"));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return first.write(List.of("id", "value"), List.<Object[]>of(new Object[]{1, "a"})); });
            var b = executor.submit(() -> { start.await(); return second.write(List.of("id", "value"), List.<Object[]>of(new Object[]{1, "b"})); });
            start.countDown();
            assertEquals(1, a.get(10, TimeUnit.SECONDS)); assertEquals(1, b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count());
    }

    @ParameterizedTest @ValueSource(strings = {"nullable", "prefix", "expression", "competing", "wrong-key", "nontransactional"})
    void ambiguousOrNontransactionalKeysFailBeforeData(String kind) throws Exception {
        sql("CREATE TABLE " + schema + ".records(id varchar(30)" + (kind.equals("nullable") ? "" : " NOT NULL")
                + ", value varchar(30) NOT NULL) ENGINE=" + (kind.equals("nontransactional") ? "MyISAM" : "InnoDB"));
        sql("CREATE UNIQUE INDEX row_key ON " + schema + ".records(" + switch (kind) {
            case "prefix" -> "id(3)";
            case "expression" -> "(lower(id))";
            case "wrong-key" -> "value";
            default -> "id";
        } + ")");
        if (kind.equals("competing")) sql("CREATE UNIQUE INDEX another_key ON " + schema + ".records(value)");
        assertThrows(IllegalArgumentException.class, () -> writer().prepareUpsert(schema, "records", List.of("id", "value"), List.of("id")));
        assertEquals(0, count());
    }

    @Test void lateInvalidRowRejectsWholeBatchAndKeyChangesAreRevalidated() throws Exception {
        sql("CREATE TABLE " + schema + ".records(id integer PRIMARY KEY, value varchar(30)) ENGINE=InnoDB");
        var writer = writer();
        writer.prepareUpsert(schema, "records", List.of("id", "value"), List.of("id"));
        assertThrows(BatchRejectedException.class, () -> writer.write(List.of("id", "value"),
                List.of(new Object[]{1, "valid"}, new Object[]{null, "invalid"})));
        assertEquals(0, count());
        sql("ALTER TABLE " + schema + ".records DROP PRIMARY KEY");
        var failure = assertThrows(IllegalStateException.class, () -> writer.write(List.of("id", "value"), List.<Object[]>of(new Object[]{1, "valid"})));
        assertFalse(UnconfirmedWriteException.class.isInstance(failure));
        assertEquals(0, count());
    }
}
