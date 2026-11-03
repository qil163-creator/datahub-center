package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisUpsertWriter;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.doris.DorisWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 无库：Doris WRITE_COPY 未验证时，打开 Stream Load 必须拒绝，且不创建 helper、不发 HTTP。 */
class DorisStreamLoadRefusalTest {
    @Test
    void enabledStreamLoadIsRefusedBeforeHelperOrHttp() throws Exception {
        var doris = new DorisProductProvider().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.DORIS)
                .findFirst().orElseThrow();
        var copy = doris.capabilities().get(DatabaseOperation.WRITE_COPY);
        assertEquals(OperationCapability.Status.DECLARED, copy.status());
        assertEquals("PINNED_NATIVE_STREAM_LOAD_VISIBLE_RECEIPT_BOUNDED_FULL_ROW", copy.reason());
        assertFalse(copy.status() == OperationCapability.Status.VERIFIED);

        try (var input = DorisProductProvider.class.getClassLoader()
                .getResourceAsStream("META-INF/dbswitch/products/doris.json")) {
            assertNotNull(input);
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int marker = json.indexOf("\"WRITE_COPY\"");
            String block = json.substring(marker, json.indexOf('}', marker));
            assertTrue(block.contains("\"status\": \"DECLARED\""));
            assertTrue(block.contains("PINNED_NATIVE_STREAM_LOAD_VISIBLE_RECEIPT_BOUNDED_FULL_ROW"));
            assertFalse(block.contains("VERIFIED"));
        }

        var calls = new AtomicInteger();
        DataSource source = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    calls.incrementAndGet();
                    if ("getConnection".equals(method.getName())) {
                        throw new SQLException("no-database");
                    }
                    throw new AssertionError(method.getName());
                });
        List<Object[]> rows = List.<Object[]>of(new Object[] {1});
        for (boolean configured : List.of(false, true)) {
            var writer = new DorisWriterImpl(source);
            var upsert = new DorisUpsertWriter(source);
            if (configured) {
                writer.configure(options(true));
                upsert.configure(options(true));
            }
            for (SQLFeatureNotSupportedException failure : List.of(
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> writer.prepareWrite("db", "t", List.of("id"))),
                    assertThrows(SQLFeatureNotSupportedException.class, () -> writer.write(List.of("id"), rows)),
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> writer.writeWithReceipt(List.of("id"), rows)),
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> writer.writeWithReceipt(List.of("id"), List.of())),
                    assertThrows(SQLFeatureNotSupportedException.class, () -> writer.write(null, null)),
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> upsert.prepareWrite("db", "t", List.of("id"))),
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> upsert.prepareUpsert("db", "t", List.of("id"), List.of("id"))),
                    assertThrows(SQLFeatureNotSupportedException.class, () -> upsert.write(List.of("id"), rows)),
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> upsert.writeWithReceipt(List.of("id"), rows)),
                    assertThrows(SQLFeatureNotSupportedException.class,
                            () -> upsert.writeWithReceipt(List.of("id"), List.of())),
                    assertThrows(SQLFeatureNotSupportedException.class, () -> upsert.write(null, null)))) {
                assertEquals("0A000", failure.getSQLState());
                assertTrue(failure.getMessage().contains("Doris WRITE_COPY runtime profile"));
                assertTrue(failure.getMessage().contains("driver or endpoint is not verified"));
                assertFalse(failure.getMessage().contains("INSERT"));
            }
            assertNull(stream(writer));
            assertNull(stream(upsert));
        }
        assertEquals(0, calls.get());

        var insert = new DorisWriterImpl(source);
        insert.configure(options(false));
        IllegalStateException unprepared = assertThrows(IllegalStateException.class,
                () -> insert.write(List.of("id"), rows));
        assertTrue(unprepared.getMessage().contains("not prepared"));
        assertEquals(0, calls.get());
        IllegalStateException preflight = assertThrows(IllegalStateException.class,
                () -> insert.prepareWrite("db", "t", List.of("id")));
        assertTrue(preflight.getMessage().contains("preflight"));
        assertEquals(1, calls.get());
        assertNull(stream(insert));

        calls.set(0);
        var upsert = new DorisUpsertWriter(source);
        upsert.configure(options(false));
        assertThrows(IllegalArgumentException.class, () -> upsert.prepareWrite("db", "t", List.of("id")));
        BatchRejectedException rejected = assertThrows(BatchRejectedException.class,
                () -> upsert.writeWithReceipt(List.of("id"), rows));
        assertTrue(rejected.getMessage().contains("differ"));
        assertEquals(0, calls.get());
        IllegalArgumentException upsertPreflight = assertThrows(IllegalArgumentException.class,
                () -> upsert.prepareUpsert("db", "t", List.of("id"), List.of("id")));
        assertTrue(upsertPreflight.getMessage().contains("unsupported"));
        assertEquals(1, calls.get());
        assertNull(stream(upsert));
    }

    private static DbSwitchOptions options(boolean streamLoad) {
        return new DbSwitchOptions(128L * 1024 * 1024, true, streamLoad, 3600, 0, 300_000, 30_000, 0);
    }

    private static Object stream(Object writer) throws Exception {
        var field = writer.getClass().getDeclaredField("stream");
        field.setAccessible(true);
        return field.get(writer);
    }
}
