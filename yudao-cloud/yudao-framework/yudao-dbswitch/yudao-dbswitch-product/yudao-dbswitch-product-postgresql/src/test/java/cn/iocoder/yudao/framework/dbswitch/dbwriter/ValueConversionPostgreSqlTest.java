package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.dbsynch.pgsql.PostgresqlDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresCopyWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.pg.PostgresInsertWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.pgwriter.row.SimpleRowWriter;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.PGConnection;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Struct;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Uses independent PostgreSQL reads to verify stored values, not mocked JDBC acknowledgements. */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class ValueConversionPostgreSqlTest {
    private static final List<String> FIELDS = List.of("id", "amount", "content", "payload", "occurred", "enabled");
    private static final BigDecimal AMOUNT = new BigDecimal("123456789012345678901234567890.12345678901234567890");
    private static final LocalDateTime OCCURRED = LocalDateTime.of(2026, 10, 3, 12, 34, 56, 123456000);
    private HikariDataSource pool;
    private Connection observer;
    private String schema;

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        pool = new HikariDataSource(); pool.setJdbcUrl(url + "?socketTimeout=5"); pool.setUsername(user);
        pool.setMaximumPoolSize(1); pool.setMinimumIdle(0); pool.setConnectionTimeout(3000);
        observer = DriverManager.getConnection(url, user, "");
        schema = "valueconversion_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".rows(id integer PRIMARY KEY, amount numeric, content text, payload bytea, occurred timestamp(6), enabled boolean)");
    }

    @AfterEach void cleanup() throws Exception {
        if (pool != null) pool.close();
        if (observer != null) {
            try {
                assertTrue(schema.matches("valueconversion_[a-f0-9]{32}"));
                sql("DROP SCHEMA " + schema + " CASCADE");
            } finally { observer.close(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"insert", "copy", "sync"})
    void exactValuesAreStoredAndLaterBadRowNeverCommitsAPrefix(String mode) throws Exception {
        ToLongFunction<List<Object[]>> write = prepare(mode);
        var xml = observer.createSQLXML(); xml.setString("<root>first\nsecond</root>");
        Object[] row = {1, AMOUNT, xml, new byte[]{0, 1, -1}, Timestamp.valueOf(OCCURRED), true};
        List<Object[]> input = List.<Object[]>of(row);
        assertEquals(1, write.applyAsLong(input));
        assertSame(row, input.get(0)); assertSame(AMOUNT, row[1]); assertSame(xml, row[2]);
        try (var statement = observer.createStatement(); var result = statement.executeQuery("SELECT * FROM " + schema + ".rows")) {
            assertTrue(result.next()); assertEquals(AMOUNT, result.getBigDecimal("amount"));
            assertEquals("<root>first\nsecond</root>", result.getString("content"));
            assertArrayEquals(new byte[]{0, 1, -1}, result.getBytes("payload"));
            assertEquals(OCCURRED, result.getTimestamp("occurred").toLocalDateTime());
            assertTrue(result.getBoolean("enabled")); assertFalse(result.next());
        }
        var valid = new Object[]{2, AMOUNT, "valid", new byte[]{2}, Timestamp.valueOf(OCCURRED), false};
        var invalid = new Object[]{3, AMOUNT, mock(Struct.class), new byte[]{3}, Timestamp.valueOf(OCCURRED), true};
        assertThrows(IllegalArgumentException.class, () -> write.applyAsLong(List.of(valid, invalid)));
        try (var statement = observer.createStatement(); var result = statement.executeQuery("SELECT count(*) FROM " + schema + ".rows")) {
            assertTrue(result.next()); assertEquals(1, result.getLong(1));
        }
    }

    @Test void directBinaryNumericApiPreservesNumbersLargerThanLongAndDoublePrecision() throws Exception {
        var expected = new BigInteger("1234567890123456789012345678901234567890");
        try (var connection = pool.getConnection()) {
            var writer = new SimpleRowWriter(new SimpleRowWriter.Table(schema, "rows", new String[]{"id", "amount"}),
                    connection.unwrap(PGConnection.class), true);
            try {
                writer.startRow(row -> { row.setInteger(0, 7); row.setNumeric(1, expected); });
                assertEquals(1, writer.finish());
            } catch (Exception failure) { writer.abort(); throw failure; }
        }
        try (var statement = observer.createStatement(); var result = statement.executeQuery("SELECT amount FROM " + schema + ".rows WHERE id=7")) {
            assertTrue(result.next()); assertEquals(new BigDecimal(expected), result.getBigDecimal(1));
        }
    }

    private ToLongFunction<List<Object[]>> prepare(String mode) {
        if (mode.equals("sync")) {
            var sync = new PostgresqlDatabaseSyncImpl(pool); sync.prepare(schema, "rows", FIELDS, List.of("id"));
            return sync::executeInsert;
        }
        IDatabaseWriter writer = mode.equals("copy") ? new PostgresCopyWriterImpl(pool) : new PostgresInsertWriterImpl(pool);
        writer.prepareWrite(schema, "rows", FIELDS);
        return rows -> writer.write(FIELDS, rows);
    }

    private void sql(String sql) throws Exception { try (var statement = observer.createStatement()) { statement.execute(sql); } }
}
