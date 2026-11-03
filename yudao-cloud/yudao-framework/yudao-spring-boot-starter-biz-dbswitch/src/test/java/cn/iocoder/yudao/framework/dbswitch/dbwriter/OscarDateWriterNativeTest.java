package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.WriteMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Native ShenTong JDBC 4.1.164 and V8.0.251217; every test owns and removes its schema. */
@Timeout(30)
@EnabledIfSystemProperty(named = "datahub.test.oscar.url", matches = "jdbc:oscar://127[.]0[.]0[.]1:[0-9]+/OSRDB")
@EnabledIfSystemProperty(named = "datahub.test.oscar.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_OSCAR_PASSWORD", matches = ".+")
class OscarDateWriterNativeTest {
    @Test
    void registryWriterAdaptsLocalDateRejectedByTheNativeTypedBinding() throws Exception {
        try (var fixture = new Fixture(); var connection = fixture.source.getConnection()) {
            assertTrue(connection.getMetaData().getDatabaseProductVersion().contains("V8.0.251217"));
            try (var nativeInsert = connection.prepareStatement("INSERT INTO " + fixture.table + "(ID,D) VALUES(99,?)")) {
                nativeInsert.setQueryTimeout(5);
                var failure = assertThrows(SQLException.class,
                        () -> nativeInsert.setObject(1, LocalDate.of(2026, 10, 8), Types.DATE));
                assertEquals("88888", failure.getSQLState());
                assertEquals(710, failure.getErrorCode());
            }
            var writer = fixture.writer();
            var fields = List.of("D", "ID");
            Object[] original = {LocalDate.of(2024, 2, 29), 1};
            assertEquals(5, writer.write(fields, List.<Object[]>of(original, new Object[]{"2026-10-08", 2},
                    new Object[]{java.sql.Date.valueOf("2026-10-08"), 3},
                    new Object[]{Timestamp.valueOf("2026-10-08 12:34:56.123456789"), 4}, new Object[]{null, 5})));
            assertInstanceOf(LocalDate.class, original[0]);
            try (var query = connection.createStatement();
                 var result = query.executeQuery("SELECT ID,D FROM " + fixture.table + " ORDER BY ID")) {
                for (int id = 1; id <= 5; id++) {
                    assertTrue(result.next());
                    assertEquals(id, result.getInt(1));
                    if (id == 5) assertNull(result.getDate(2));
                    else assertEquals(id == 1 ? LocalDate.of(2024, 2, 29) : LocalDate.of(2026, 10, 8),
                            result.getDate(2).toLocalDate());
                }
                assertFalse(result.next());
            }
        }
    }

    @Test
    void invalidIsoDateCannotBeSilentlyNormalizedOrPartiallyWritten() throws Exception {
        try (var fixture = new Fixture(); var connection = fixture.source.getConnection()) {
            var writer = fixture.writer();
            assertThrows(BatchRejectedException.class, () -> writer.write(List.of("D", "ID"),
                    List.<Object[]>of(new Object[]{LocalDate.of(2026, 10, 8), 1}, new Object[]{"2026-02-30", 2})));
            try (var query = connection.createStatement();
                 var result = query.executeQuery("SELECT count(*) FROM " + fixture.table)) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
            assertFalse(connection.isClosed());
            assertTrue(connection.getAutoCommit());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final String schema = "DATE87_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        private final String table = "\"" + schema + "\".\"DATES\"";
        private final VerifiedDriverTestHandle driver = new VerifiedDriverTestHandle(
                Path.of(System.getProperty("datahub.test.oscar.driver")), ProductTypeEnum.OSCAR);
        private final DataSource source;

        Fixture() throws Exception {
            source = driver.dataSource(System.getProperty("datahub.test.oscar.url"), "com.oscar.Driver",
                    System.getenv("DATAHUB_TEST_OSCAR_USER"), System.getenv("DATAHUB_TEST_OSCAR_PASSWORD"), new Properties());
            try (var connection = source.getConnection()) {
                execute(connection, "CREATE SCHEMA " + schema);
                try { execute(connection, "CREATE TABLE " + table + " (ID INTEGER PRIMARY KEY,D DATE)"); }
                catch (Exception failure) { execute(connection, "DROP SCHEMA " + schema + " CASCADE"); throw failure; }
            }
        }

        IDatabaseWriter writer() {
            var writer = DbSwitchProductRegistry.discover().writer(source, ProductTypeEnum.OSCAR, WriteMode.INSERT);
            writer.prepareWrite(schema, "DATES", List.of("D", "ID"));
            return writer;
        }

        @Override
        public void close() throws Exception {
            try (var connection = source.getConnection()) { execute(connection, "DROP SCHEMA " + schema + " CASCADE"); }
            finally { driver.close(); }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.setQueryTimeout(5); statement.execute(sql); }
    }
}
