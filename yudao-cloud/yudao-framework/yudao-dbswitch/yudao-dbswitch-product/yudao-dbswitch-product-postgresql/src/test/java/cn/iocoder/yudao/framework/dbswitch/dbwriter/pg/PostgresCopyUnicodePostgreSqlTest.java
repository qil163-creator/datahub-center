package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(30)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.*")
class PostgresCopyUnicodePostgreSqlTest {
    @ParameterizedTest
    @ValueSource(strings = {"nul", "high", "low", "reversed"})
    void invalidLaterTextRejectsTheWholeBatchBeforeBorrowing(String kind) throws Exception {
        String value = switch (kind) {
            case "nul" -> "private-\0";
            case "high" -> "private-\uD800";
            case "low" -> "private-\uDC00";
            default -> "private-\uDC00\uD800";
        };
        String url = System.getProperty("datahub.test.postgres.url");
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        String schema = "unicode_" + UUID.randomUUID().toString().replace("-", "");
        String relation = q(schema) + ".\"target\"";
        try (var observer = DriverManager.getConnection(url, user, ""); var pool = new HikariDataSource()) {
            pool.setJdbcUrl(url); pool.setUsername(user); pool.setMinimumIdle(0); pool.setMaximumPoolSize(1);
            try (var statement = observer.createStatement()) {
                statement.execute("CREATE SCHEMA " + q(schema));
                try {
                    statement.execute("CREATE TABLE " + relation + " (id integer, note text)");
                    statement.execute("INSERT INTO " + relation + " VALUES (99,'sentinel')");
                    AtomicInteger borrows = new AtomicInteger();
                    DataSource counted = mock(DataSource.class);
                    when(counted.getConnection()).thenAnswer(call -> { borrows.incrementAndGet(); return pool.getConnection(); });
                    var writer = new PostgresCopyWriterImpl(counted);
                    var fields = List.of("id", "note");
                    writer.prepareWrite(schema, "target", fields);
                    int before = borrows.get();
                    Object[] first = {1, "valid 😀"}, second = {2, value};
                    var failure = assertThrows(IllegalArgumentException.class,
                            () -> writer.writeWithReceipt(fields, List.of(first, second)));
                    assertEquals(before, borrows.get());
                    assertNull(failure.getCause());
                    assertFalse(failure.getMessage().contains("private-"));
                    assertArrayEquals(new Object[]{1, "valid 😀"}, first);
                    assertArrayEquals(new Object[]{2, value}, second);
                    try (var rows = statement.executeQuery("SELECT id,note FROM " + relation)) {
                        assertTrue(rows.next()); assertEquals(99, rows.getInt(1));
                        assertEquals("sentinel", rows.getString(2)); assertFalse(rows.next());
                    }
                } finally { statement.execute("DROP SCHEMA " + q(schema) + " CASCADE"); }
            }
        }
    }

    @Test void validSupplementaryCharactersEmptyAndNullRoundTripWithoutReplacement() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        String schema = "unicode_" + UUID.randomUUID().toString().replace("-", "");
        String relation = q(schema) + ".\"target\"";
        String text = "中文 😀 \uD800\uDC00 \uDBFF\uDFFF";
        try (var observer = DriverManager.getConnection(url, user, ""); var pool = new HikariDataSource()) {
            pool.setJdbcUrl(url); pool.setUsername(user); pool.setMinimumIdle(0); pool.setMaximumPoolSize(1);
            try (var statement = observer.createStatement()) {
                statement.execute("CREATE SCHEMA " + q(schema));
                try {
                    statement.execute("CREATE TABLE " + relation + " (id integer, note text)");
                    var writer = new PostgresCopyWriterImpl(pool);
                    var fields = List.of("id", "note"); writer.prepareWrite(schema, "target", fields);
                    assertEquals(3, writer.write(fields, List.of(new Object[]{1, text}, new Object[]{2, ""}, new Object[]{3, null})));
                    try (var rows = statement.executeQuery("SELECT note FROM " + relation + " ORDER BY id")) {
                        assertTrue(rows.next()); assertEquals(text, rows.getString(1));
                        assertTrue(rows.next()); assertEquals("", rows.getString(1));
                        assertTrue(rows.next()); assertNull(rows.getString(1)); assertFalse(rows.next());
                    }
                } finally { statement.execute("DROP SCHEMA " + q(schema) + " CASCADE"); }
            }
        }
    }

    private static String q(String value) { return PostgreSqlUtils.quoteIdentifier(value); }
}
