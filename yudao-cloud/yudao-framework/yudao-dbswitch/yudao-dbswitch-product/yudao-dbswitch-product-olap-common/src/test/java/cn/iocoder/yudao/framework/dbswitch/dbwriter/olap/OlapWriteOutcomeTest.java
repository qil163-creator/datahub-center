package cn.iocoder.yudao.framework.dbswitch.dbwriter.olap;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class OlapWriteOutcomeTest {

    private static final String SUCCESS = receipt(1, 1, 0, 0);

    @ParameterizedTest
    @ValueSource(strings = {
            "", "{}", "null", "[]", "private-error-marker",
            "{\"Status\":\"Publish Timeout\"}",
            "{\"Status\":\"Publish Failed\"}",
            "{\"Status\":\"Fail\",\"Message\":\"private-error-marker\"}",
            "{\"Status\":\"Success\"}",
            "{\"Status\":\"Success\"} private-error-marker"
    })
    void unconfirmedHttpResponseNeverBecomesSuccessOrJdbcRewrite(String response) throws Exception {
        withServer("Doris", response, (helper, requests, payload) -> {
            var writer = new ObservedWriter();
            ReflectionTestUtils.setField(writer, "streamLoadHelper", helper);
            var rows = rows();
            var failure = assertThrows(RuntimeException.class, () -> writer.write(List.of("value"), rows));
            assertEquals("UnconfirmedWriteException", failure.getClass().getSimpleName());
            assertEquals(0, writer.jdbcWrites);
            assertEquals(1, rows.size(), "Unconfirmed input must not be cleared as successful");
            assertEquals(1, requests.get(), "Do not automatically replay the external batch");
            var stack = new StringWriter();
            failure.printStackTrace(new PrintWriter(stack));
            assertFalse(stack.toString().contains("private-error-marker"),
                    "Remote response/parse text must not escape through the error cause chain");
        });
    }

    @ParameterizedTest @ValueSource(strings = {"Doris", "StarRocks"})
    void explicitSuccessAcknowledgesWithoutMutatingCallerOwnedInput(String product) throws Exception {
        withServer(product, SUCCESS, (helper, requests, payload) -> {
            var writer = new ObservedWriter();
            ReflectionTestUtils.setField(writer, "streamLoadHelper", helper);
            var rows = rows();
            assertEquals(1, writer.write(List.of("value"), rows));
            assertEquals(1, rows.size());
            assertArrayEquals(new Object[]{"sample"}, rows.getFirst());
            assertEquals(0, writer.jdbcWrites);
            assertEquals(1, requests.get());
        });
    }

    static Stream<String> incompleteOrLossyReceipts() {
        return Stream.of(receipt(1, 0, 1, 0), receipt(1, 0, 0, 1), receipt(1, 0, 0, 0),
                receipt(0, 0, 0, 0), receipt(2, 2, 0, 0), receipt(1, 1, 1, 0), receipt(1, 1, 0, 1),
                receipt(-1, 1, 0, 0), SUCCESS.replace("\"NumberTotalRows\":1,", ""),
                SUCCESS.replace("\"NumberFilteredRows\":0,", ""),
                SUCCESS.replace("\"NumberLoadedRows\":1", "\"NumberLoadedRows\":1.5"),
                SUCCESS.replace("\"NumberLoadedRows\":1", "\"NumberLoadedRows\":\"1\""),
                SUCCESS.replace("\"NumberLoadedRows\":1", "\"NumberLoadedRows\":9223372036854775808"),
                SUCCESS.replace("\"NumberLoadedRows\":1", "\"NumberLoadedRows\":null"));
    }

    @ParameterizedTest @MethodSource("incompleteOrLossyReceipts")
    void successStatusWithMissingInvalidOrDiscardedRowCountsNeverAcknowledgesOrReplays(String response) throws Exception {
        unconfirmedHttpResponseNeverBecomesSuccessOrJdbcRewrite(response);
    }

    @Test
    void responseCountsMustBelongToTheActualBatchAndCannotAcknowledgeOnlyAPrefix() throws Exception {
        withServer("StarRocks", SUCCESS, (helper, requests, payload) -> {
            var writer = new ObservedWriter(); ReflectionTestUtils.setField(writer, "streamLoadHelper", helper);
            var rows = List.of(new Object[]{"first"}, new Object[]{"second"});
            assertThrows(cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException.class,
                    () -> writer.write(List.of("value"), rows));
            assertEquals(0, writer.jdbcWrites); assertEquals(1, requests.get()); assertEquals(2, rows.size());
        });
    }

    @ParameterizedTest @ValueSource(strings = {"Doris", "StarRocks"})
    void HttpPayloadPreservesMillisecondsMicrosecondsAndNanoseconds(String product) throws Exception {
        withServer(product, receipt(3, 3, 0, 0), (helper, requests, payload) -> {
            var writer = new ObservedWriter(); ReflectionTestUtils.setField(writer, "streamLoadHelper", helper);
            var time = LocalDateTime.of(2026, 1, 2, 10, 30, 0);
            var rows = List.of(new Object[]{time.withNano(123000000)},
                    new Object[]{time.withNano(123456000)}, new Object[]{time.withNano(123456789)});
            assertEquals(3, writer.write(List.of("value"), rows));
            assertTrue(payload.get().contains("2026-01-02 10:30:00.123\""));
            assertTrue(payload.get().contains("2026-01-02 10:30:00.123456\""));
            assertTrue(payload.get().contains("2026-01-02 10:30:00.123456789\""));
            assertEquals(1, requests.get()); assertEquals(0, writer.jdbcWrites);
        });
    }

    @Test
    void lostResponseDoesNotFallBackToJdbc() throws Exception {
        var helper = mock(OlapStreamLoadHelper.class);
        when(helper.write(anyList(), anyList())).thenThrow(new SocketTimeoutException("private-error-marker"));
        var writer = new ObservedWriter();
        ReflectionTestUtils.setField(writer, "streamLoadHelper", helper);
        var rows = rows();
        var failure = assertThrows(RuntimeException.class, () -> writer.write(List.of("value"), rows));
        assertEquals("UnconfirmedWriteException", failure.getClass().getSimpleName());
        assertEquals(0, writer.jdbcWrites);
        assertEquals(1, rows.size());
        assertFalse(failure.toString().contains("private-error-marker"));
    }

    @Test
    void jdbcRemainsAvailableWhenStreamLoadWasNotSelected() {
        var writer = new ObservedWriter();
        var rows = rows();
        assertEquals(1, writer.write(List.of("value"), rows));
        assertEquals(1, writer.jdbcWrites);
        assertEquals(1, rows.size());
        assertArrayEquals(new Object[]{"sample"}, rows.getFirst());
    }

    private static List<Object[]> rows() {
        return new ArrayList<>(List.<Object[]>of(new Object[] {"sample"}));
    }

    private static String receipt(long total, long loaded, long filtered, long unselected) {
        return "{\"Label\":\"$request\",\"TxnId\":7,\"Status\":\"Success\",\"NumberTotalRows\":%d,\"NumberLoadedRows\":%d,\"NumberFilteredRows\":%d,\"NumberUnselectedRows\":%d}"
                .formatted(total, loaded, filtered, unselected);
    }

    private static void withServer(String product, String body, ServerAssertion assertion) throws Exception {
        var requests = new AtomicInteger();
        var payload = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/test_schema/test_table/_stream_load", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.incrementAndGet();
            byte[] response = body.replace("$request", exchange.getRequestHeaders().getFirst("label")).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
            exchange.close();
        });
        server.start();
        try {
            var constructor = OlapStreamLoadHelper.class.getDeclaredConstructor(
                    String.class, String.class, String.class, String.class,
                    String.class, String.class, String.class, int.class);
            constructor.setAccessible(true);
            var helper = constructor.newInstance(product, "test_schema", "test_table",
                    "127.0.0.1", String.valueOf(server.getAddress().getPort()), "", "", 2);
            assertion.check(helper, requests, payload);
        } finally {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface ServerAssertion {
        void check(OlapStreamLoadHelper helper, AtomicInteger requests, AtomicReference<String> payload) throws Exception;
    }

    private static final class ObservedWriter extends MysqlCompatibleOlapWriterImpl {
        int jdbcWrites;

        ObservedWriter() {
            super(mock(DataSource.class));
            schemaName = "test_schema";
            tableName = "test_table";
            columnType = Map.of("value", Types.VARCHAR);
        }

        @Override
        protected String getDatabaseProductName() { return "Doris"; }

        @Override
        protected void batchWrite(List<String> fields, List<Object[]> rows, String sql, int[] types) {
            jdbcWrites++;
        }
    }
}
