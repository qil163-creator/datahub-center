package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.starrocks.StarrocksUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.starrocks.StarrocksWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 无库：StarRocks WRITE_COPY 未验证时，打开 Stream Load 必须拒绝，且不借连接、不创建 helper。 */
class StarrocksStreamLoadRefusalTest {
    @Test
    void unverifiedStreamLoadIsRefusedBeforeHelperOrConnection() throws Exception {
        var starrocks = new StarrocksProductProvider().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.STARROCKS)
                .findFirst().orElseThrow();
        var copy = starrocks.capabilities().get(DatabaseOperation.WRITE_COPY);
        var insert = starrocks.capabilities().get(DatabaseOperation.WRITE_INSERT);
        assertEquals(OperationCapability.Status.DECLARED, copy.status());
        assertEquals("PINNED_NATIVE_STREAM_LOAD_VISIBLE_RECEIPT_BOUNDED_FULL_ROW", copy.reason());
        assertEquals(OperationCapability.Status.DECLARED, insert.status());
        assertTrue(DbSwitchOptions.defaults().olapStreamLoadEnabled());

        try (var input = StarrocksProductProvider.class.getClassLoader()
                .getResourceAsStream("META-INF/dbswitch/products/starrocks.json")) {
            assertNotNull(input);
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int marker = json.indexOf("\"WRITE_COPY\"");
            assertTrue(marker > json.indexOf("\"WRITE_INSERT\""));
            String block = json.substring(marker, json.indexOf('}', marker));
            assertTrue(block.contains("\"status\": \"DECLARED\""));
            assertTrue(block.contains("PINNED_NATIVE_STREAM_LOAD_VISIBLE_RECEIPT_BOUNDED_FULL_ROW"));
            assertFalse(block.contains("VERIFIED"));
        }

        var calls = new AtomicInteger();
        DataSource source = rejecting(calls);
        var writer = new StarrocksWriterImpl(source);
        var configured = new StarrocksWriterImpl(source);
        configured.configure(streamOptions());
        var upsert = new StarrocksUpsertWriter(source);
        List<Object[]> rows = List.<Object[]>of(new Object[] {1});
        var failures = new ArrayList<SQLFeatureNotSupportedException>();
        failures.add(assertThrows(SQLFeatureNotSupportedException.class,
                () -> writer.prepareWrite("db", "sample", List.of("id"))));
        failures.add(assertThrows(SQLFeatureNotSupportedException.class,
                () -> writer.write(List.of("id"), rows)));
        failures.add(assertThrows(SQLFeatureNotSupportedException.class,
                () -> writer.write(List.of("id"), List.of())));
        failures.add(assertThrows(SQLFeatureNotSupportedException.class, () -> writer.write(null, null)));
        failures.add(assertThrows(SQLFeatureNotSupportedException.class,
                () -> configured.prepareWrite("db", "sample", List.of("id"))));
        failures.add(assertThrows(SQLFeatureNotSupportedException.class,
                () -> configured.write(List.of("id"), rows)));
        failures.add(assertThrows(SQLFeatureNotSupportedException.class,
                () -> upsert.prepareUpsert("db", "sample", List.of("id"), List.of("id"))));
        failures.add(assertThrows(SQLFeatureNotSupportedException.class, () -> upsert.write(List.of("id"), rows)));
        failures.add(assertThrows(SQLFeatureNotSupportedException.class,
                () -> upsert.writeWithReceipt(List.of("id"), List.of())));
        failures.add(assertThrows(SQLFeatureNotSupportedException.class, () -> upsert.write(null, null)));
        for (SQLFeatureNotSupportedException failure : failures) {
            assertEquals("0A000", failure.getSQLState());
            assertNull(failure.getCause());
            assertTrue(failure.getMessage().contains("StarRocks WRITE_COPY runtime profile"));
            assertTrue(failure.getMessage().contains("driver or endpoint is not verified"));
            assertFalse(failure.getMessage().contains("Doris"));
            assertFalse(failure.getMessage().contains("INSERT"));
        }
        assertNull(field(writer, "streamLoadHelper"));
        assertNull(field(configured, "streamLoadHelper"));
        assertNull(field(upsert, "stream"));
        assertEquals(0, calls.get());
    }

    @Test
    void jdbcInsertStillStartsWhenStreamLoadIsOff() throws Exception {
        var calls = new AtomicInteger();
        var writer = new StarrocksWriterImpl(connecting(calls));
        writer.configure(jdbcOptions());
        IllegalStateException preflight = assertThrows(IllegalStateException.class,
                () -> writer.prepareWrite("db", "sample", List.of("id")));
        assertTrue(preflight.getMessage().contains("native preflight"));
        assertNull(field(writer, "streamLoadHelper"));
        assertEquals(1, calls.get());

        var quiet = new AtomicInteger();
        var idle = new StarrocksWriterImpl(rejecting(quiet));
        idle.configure(jdbcOptions());
        IllegalStateException unprepared = assertThrows(IllegalStateException.class,
                () -> idle.write(List.of("id"), List.<Object[]>of(new Object[] {1})));
        assertTrue(unprepared.getMessage().contains("not prepared"));
        assertEquals(0, quiet.get());

        var upsertCalls = new AtomicInteger();
        var upsert = new StarrocksUpsertWriter(connecting(upsertCalls));
        upsert.configure(jdbcOptions());
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> upsert.prepareUpsert("db", "sample", List.of("id"), List.of("id")));
        assertTrue(rejected.getMessage().contains("unsupported"));
        assertNull(field(upsert, "stream"));
        assertEquals(1, upsertCalls.get());

        var upsertQuiet = new AtomicInteger();
        var idleUpsert = new StarrocksUpsertWriter(rejecting(upsertQuiet));
        idleUpsert.configure(jdbcOptions());
        assertThrows(BatchRejectedException.class,
                () -> idleUpsert.writeWithReceipt(List.of("id"), List.<Object[]>of(new Object[] {1})));
        assertEquals(0, upsertQuiet.get());
    }

    private static DbSwitchOptions streamOptions() {
        return new DbSwitchOptions(128L * 1024 * 1024, true, true, 3600, 0, 300_000, 30_000, 0);
    }

    private static DbSwitchOptions jdbcOptions() {
        return new DbSwitchOptions(128L * 1024 * 1024, true, false, 3600, 0, 300_000, 30_000, 0);
    }

    private static DataSource rejecting(AtomicInteger calls) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    calls.incrementAndGet();
                    throw new AssertionError(method.getName());
                });
    }

    private static DataSource connecting(AtomicInteger calls) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    calls.incrementAndGet();
                    if ("getConnection".equals(method.getName())) throw new SQLException("jdbc-preflight");
                    throw new AssertionError(method.getName());
                });
    }

    private static Object field(Object target, String name) throws Exception {
        var found = target.getClass().getDeclaredField(name);
        found.setAccessible(true);
        return found.get(target);
    }
}
