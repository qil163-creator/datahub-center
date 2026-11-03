package cn.iocoder.yudao.framework.dbswitch.dbsynch;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the runtime entry and actual server cancellation, not just a timeout setter. */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class SynchronizerTimeoutPostgreSqlTest {
    @ParameterizedTest
    @ValueSource(strings = {"INSERT", "UPDATE", "DELETE"})
    void eachDmlIsCancelledAndNeverReportsAnUnconfirmedWriteAsSuccess(String operation) throws Exception {
        String schema = "sync_timeout_" + UUID.randomUUID().toString().replace("-", "");
        var defaults = DbSwitchOptions.defaults();
        var options = new DbSwitchOptions(defaults.maxCacheBytes(), true, true, 1, 0, 300_000, 30_000, 0);
        try (var runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), options, null);
             var pool = pool(); var control = pool.getConnection(); var sql = control.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".rows(id bigint PRIMARY KEY,note text)");
                sql.execute("INSERT INTO " + schema + ".rows VALUES(1,'before')");
                sql.execute("CREATE FUNCTION " + schema + ".delay_write() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + "BEGIN PERFORM pg_sleep(3); IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW; END$$");
                sql.execute("CREATE TRIGGER delay_write BEFORE INSERT OR UPDATE OR DELETE ON " + schema
                        + ".rows FOR EACH ROW EXECUTE FUNCTION " + schema + ".delay_write()");
                var synchronizer = runtime.synchronizer(pool, ProductTypeEnum.POSTGRESQL);
                synchronizer.prepare(schema, "rows", List.of("id", "note"), List.of("id"));
                List<Object[]> rows = List.<Object[]>of(new Object[]{operation.equals("INSERT") ? 2L : 1L, "after"});

                var failure = assertThrows(UnconfirmedWriteException.class, () -> {
                    switch (operation) {
                        case "INSERT" -> synchronizer.executeInsert(rows);
                        case "UPDATE" -> synchronizer.executeUpdate(rows);
                        case "DELETE" -> synchronizer.executeDelete(rows);
                        default -> throw new AssertionError(operation);
                    }
                });
                assertNull(failure.getCause(), "vendor errors and row values must not escape the write boundary");
                try (var actual = sql.executeQuery("SELECT id,note FROM " + schema + ".rows ORDER BY id")) {
                    assertTrue(actual.next());
                    assertEquals(1L, actual.getLong(1));
                    assertEquals("before", actual.getString(2));
                    assertFalse(actual.next());
                }
                try (var next = pool.getConnection()) {
                    assertTrue(next.getAutoCommit(), "cancelled transaction must not be reused");
                }
            } finally {
                sql.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private static HikariDataSource pool() {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        var pool = new HikariDataSource();
        pool.setJdbcUrl(url + "?socketTimeout=5");
        pool.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test"));
        pool.setMinimumIdle(0); pool.setMaximumPoolSize(2); pool.setConnectionTimeout(3000);
        return pool;
    }
}
