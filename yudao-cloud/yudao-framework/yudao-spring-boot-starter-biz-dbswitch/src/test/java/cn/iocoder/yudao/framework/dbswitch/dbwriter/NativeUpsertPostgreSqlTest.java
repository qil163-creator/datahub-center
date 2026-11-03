package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class NativeUpsertPostgreSqlTest {
    private DriverManagerDataSource source;
    private Connection control;
    private String schema;

    @BeforeEach void setup() throws Exception {
        source = new DriverManagerDataSource(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "dataforge_test"), System.getProperty("datahub.test.postgres.password", ""));
        control = source.getConnection(); schema = "upsert_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
    }
    @AfterEach void cleanup() throws Exception {
        if (control != null) { try { sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); } finally { control.close(); } }
    }
    private void sql(String sql) throws Exception { try (var statement = control.createStatement()) { statement.execute(sql); } }
    private IDatabaseUpsertWriter writer() { return DbSwitchProductRegistry.discover().upsertWriter(source, ProductTypeEnum.POSTGRESQL); }
    private long count() throws Exception {
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM " + schema + ".rows")) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }

    @Test void updateInsertAndRepeatedKeyKeepExactValuesAndReturnCommitEvidence() throws Exception {
        sql("CREATE TABLE " + schema + ".rows(id integer PRIMARY KEY, value numeric(38,8))");
        var writer = writer(); writer.prepareUpsert(schema, "rows", List.of("id", "value"), List.of("id"));
        BigDecimal exact = new BigDecimal("123456789012345678901234567890.12345678");
        var batch = List.of(new Object[]{1, BigDecimal.ZERO}, new Object[]{1, exact}, new Object[]{2, exact});
        var receipt = writer.writeWithReceipt(List.of("id", "value"), batch);
        assertEquals(3, receipt.acknowledgedRows()); assertEquals(2, count());
        if (control.getMetaData().getDatabaseMajorVersion() == 16) assertNotNull(receipt.receipt());
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT value FROM " + schema + ".rows ORDER BY id")) {
            while (rows.next()) assertEquals(exact, rows.getBigDecimal(1));
        }
        assertEquals(BigDecimal.ZERO, batch.getFirst()[1]);
    }

    @Test void concurrentMissingKeyNeverRunsAReadThenInsertRace() throws Exception {
        sql("CREATE TABLE " + schema + ".rows(id integer PRIMARY KEY, value text)");
        var first = writer(); var second = writer();
        first.prepareUpsert(schema, "rows", List.of("id", "value"), List.of("id"));
        second.prepareUpsert(schema, "rows", List.of("id", "value"), List.of("id"));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return first.write(List.of("id", "value"), List.<Object[]>of(new Object[]{1, "a"})); });
            var b = executor.submit(() -> { start.await(); return second.write(List.of("id", "value"), List.<Object[]>of(new Object[]{1, "b"})); });
            start.countDown(); assertEquals(1, a.get(10, TimeUnit.SECONDS)); assertEquals(1, b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count());
    }

    @ParameterizedTest @ValueSource(strings = {"nullable", "partial", "expression", "deferrable", "wrong-key"})
    void unsupportedKeySemanticsFailBeforeSendingData(String kind) throws Exception {
        sql("CREATE TABLE " + schema + ".rows(id integer" + (kind.equals("nullable") ? "" : " NOT NULL") + ", value text NOT NULL)");
        sql(switch (kind) {
            case "partial" -> "CREATE UNIQUE INDEX row_key ON " + schema + ".rows(id) WHERE id>0";
            case "expression" -> "CREATE UNIQUE INDEX row_key ON " + schema + ".rows((id+1))";
            case "deferrable" -> "ALTER TABLE " + schema + ".rows ADD CONSTRAINT row_key UNIQUE(id) DEFERRABLE";
            case "wrong-key" -> "ALTER TABLE " + schema + ".rows ADD CONSTRAINT row_key UNIQUE(value)";
            default -> "ALTER TABLE " + schema + ".rows ADD CONSTRAINT row_key UNIQUE(id)";
        });
        assertThrows(IllegalArgumentException.class, () -> writer().prepareUpsert(schema, "rows", List.of("id", "value"), List.of("id")));
        assertEquals(0, count());
    }

    @Test void nullKeyInALaterRowRejectsWholeBatchAndDroppedKeyIsRechecked() throws Exception {
        sql("CREATE TABLE " + schema + ".rows(id integer PRIMARY KEY, value text)");
        var writer = writer(); writer.prepareUpsert(schema, "rows", List.of("id", "value"), List.of("id"));
        assertThrows(BatchRejectedException.class, () -> writer.write(List.of("id", "value"),
                List.of(new Object[]{1, "valid"}, new Object[]{null, "invalid"})));
        assertEquals(0, count());
        sql("ALTER TABLE " + schema + ".rows DROP CONSTRAINT rows_pkey");
        var failure = assertThrows(IllegalStateException.class, () -> writer.write(List.of("id", "value"), List.<Object[]>of(new Object[]{1, "valid"})));
        assertFalse(UnconfirmedWriteException.class.isInstance(failure)); assertEquals(0, count());
    }

    @Test void identifiersAreQuotedWithoutChangingTheirIdentity() throws Exception {
        sql("CREATE TABLE " + schema + ".\"quoted\"\"rows\"(\"key\"\"name\" integer PRIMARY KEY, value text)");
        var writer = writer(); writer.prepareUpsert(schema, "quoted\"rows", List.of("key\"name", "value"), List.of("key\"name"));
        assertEquals(1, writer.write(List.of("key\"name", "value"), List.<Object[]>of(new Object[]{1, "ok"})));
    }
}
