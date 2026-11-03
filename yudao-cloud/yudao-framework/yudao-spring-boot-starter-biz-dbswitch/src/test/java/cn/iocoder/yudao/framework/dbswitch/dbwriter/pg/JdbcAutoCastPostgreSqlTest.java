package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.entity.JdbcArrayValue;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.util.PGobject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Actual PG binding and public writer checks; unit contracts do not certify other vendor drivers. */
@Timeout(45)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.*")
class JdbcAutoCastPostgreSqlTest {
    private HikariDataSource pool;
    private Connection observer;
    private String schema;

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        pool = new HikariDataSource();
        pool.setJdbcUrl(url + "?socketTimeout=5"); pool.setUsername(user);
        pool.setMaximumPoolSize(2); pool.setMinimumIdle(0); pool.setConnectionTimeout(3000);
        observer = DriverManager.getConnection(url, user, "");
        schema = "autocast87_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
    }

    @AfterEach void cleanup() throws Exception {
        if (pool != null) pool.close();
        if (observer != null) try (var owned = observer) {
            if (schema != null) {
                assertTrue(schema.matches("autocast87_[a-f0-9]{32}"));
                sql("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test void publicInsertWriterPreservesBinaryTextAndBooleanNumericMeaning() throws Exception {
        sql("CREATE TABLE " + schema + ".rows(id integer PRIMARY KEY, value text, flag numeric, count integer)");
        var fields = List.of("id", "value", "flag", "count");
        var writer = new PostgresInsertWriterImpl(pool);
        writer.prepareWrite(schema, "rows", fields);
        byte[] text = "中文🙂\ntext".getBytes(StandardCharsets.UTF_8);
        List<Object[]> input = List.of(new Object[]{1, text, " \tTrUe\r\n", " false "},
                new Object[]{2, new byte[0], "FALSE", true}, new Object[]{3, null, null, null});
        assertEquals(3, writer.writeWithReceipt(fields, input).acknowledgedRows());
        assertSame(text, input.getFirst()[1]);
        try (var statement = observer.createStatement();
             var rows = statement.executeQuery("SELECT value,flag,count FROM " + schema + ".rows ORDER BY id")) {
            assertTrue(rows.next()); assertEquals("中文🙂\ntext", rows.getString(1));
            assertEquals(BigDecimal.ONE, rows.getBigDecimal(2)); assertEquals(0, rows.getInt(3));
            assertTrue(rows.next()); assertEquals("", rows.getString(1));
            assertEquals(BigDecimal.ZERO, rows.getBigDecimal(2)); assertEquals(1, rows.getInt(3));
            assertTrue(rows.next()); assertNull(rows.getObject(1)); assertNull(rows.getObject(2)); assertNull(rows.getObject(3));
            assertFalse(rows.next());
        }
    }

    @Test void invalidUtf8InLaterRowCannotPartiallyWriteOrBecomeReplacementCharacters() throws Exception {
        sql("CREATE TABLE " + schema + ".rows(id integer PRIMARY KEY, value text)");
        var fields = List.of("id", "value");
        var writer = new PostgresInsertWriterImpl(pool);
        writer.prepareWrite(schema, "rows", fields);
        var failure = assertThrows(RuntimeException.class, () -> writer.write(fields,
                List.of(new Object[]{1, "valid".getBytes(StandardCharsets.UTF_8)},
                        new Object[]{2, new byte[]{(byte) 0xc3, 0x28}})));
        assertNull(failure.getCause());
        try (var statement = observer.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM " + schema + ".rows")) {
            assertTrue(rows.next()); assertEquals(0, rows.getLong(1));
        }
    }

    @Test void existingNativeArrayJsonBinaryDecimalAndTemporalBindingsRemainExact() throws Exception {
        sql("CREATE TABLE " + schema + ".rows(id integer PRIMARY KEY, raw bytea, j jsonb, a integer[], n numeric, t timestamp(6))");
        byte[] raw = {0, (byte) 0xff, (byte) 0x80};
        var json = new PGobject(); json.setType("jsonb"); json.setValue("{\"n\":9223372036854775808}");
        var array = new JdbcArrayValue("int4", Types.INTEGER, List.of(1, 2, 3));
        var number = new BigInteger("9223372036854775808");
        var timestamp = Timestamp.valueOf("2026-10-08 12:34:56.123456");
        var fields = List.of("id", "raw", "j", "a", "n", "t");
        var writer = new PostgresInsertWriterImpl(pool);
        writer.prepareWrite(schema, "rows", fields);
        assertEquals(1, writer.write(fields, List.<Object[]>of(new Object[]{1, raw, json, array, number, timestamp})));
        try (var statement = observer.createStatement(); var rows = statement.executeQuery("SELECT raw,j->>'n',a,n,t FROM " + schema + ".rows")) {
            assertTrue(rows.next()); assertArrayEquals(raw, rows.getBytes(1));
            assertEquals(number.toString(), rows.getString(2));
            var observedArray = rows.getArray(3);
            try { assertArrayEquals(new Integer[]{1, 2, 3}, (Integer[]) observedArray.getArray()); }
            finally { observedArray.free(); }
            assertEquals(new BigDecimal(number), rows.getBigDecimal(4));
            assertEquals(timestamp, rows.getTimestamp(5)); assertFalse(rows.next());
        }
    }

    @Test void sharedUpdateBindingUsesTheSameConversion() throws Exception {
        sql("CREATE TABLE " + schema + ".rows(id integer PRIMARY KEY, value text, flag numeric)");
        sql("INSERT INTO " + schema + ".rows VALUES(1,'old',0)");
        JdbcBatchExecutor.execute(pool, "UPDATE " + schema + ".rows SET value=?,flag=? WHERE id=?",
                List.<Object[]>of(new Object[]{"updated 中文".getBytes(StandardCharsets.UTF_8), "true", 1}),
                new int[]{Types.VARCHAR, Types.NUMERIC, Types.INTEGER}, 5);
        try (var statement = observer.createStatement(); var rows = statement.executeQuery("SELECT value,flag FROM " + schema + ".rows")) {
            assertTrue(rows.next()); assertEquals("updated 中文", rows.getString(1));
            assertEquals(BigDecimal.ONE, rows.getBigDecimal(2)); assertFalse(rows.next());
        }
    }

    private void sql(String sql) throws Exception {
        try (var statement = observer.createStatement()) { statement.setQueryTimeout(5); statement.execute(sql); }
    }
}
