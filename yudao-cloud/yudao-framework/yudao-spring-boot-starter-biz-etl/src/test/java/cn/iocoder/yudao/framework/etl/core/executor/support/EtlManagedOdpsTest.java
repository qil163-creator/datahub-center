package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.json.JSONUtil;
import com.aliyun.odps.Column;
import com.aliyun.odps.TableSchema;
import com.aliyun.odps.type.TypeInfoParser;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.net.URI;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EtlManagedOdpsTest {
    @Test void scalarConversionRejectsRoundingOverflowAndDatetimePrecisionLoss() {
        assertEquals(9007199254740993L, EtlManagedOdps.value("BIGINT", "9007199254740993"));
        assertEquals(java.time.LocalDate.of(2026, 11, 2), EtlManagedOdps.value("DATE", "2026-11-02"));
        assertEquals(new BigDecimal("12345678901234567890.1234"), EtlManagedOdps.value("DECIMAL(24,4)", "12345678901234567890.1234"));
        assertThrows(IllegalArgumentException.class, () -> EtlManagedOdps.value("DECIMAL(24,4)", "12345678901234567890.12345"));
        assertThrows(IllegalArgumentException.class, () -> EtlManagedOdps.value("DECIMAL(24,4)", "123456789012345678901.1234"));
        assertThrows(ArithmeticException.class, () -> EtlManagedOdps.value("BIGINT", "1.25"));
        assertThrows(IllegalArgumentException.class, () -> EtlManagedOdps.value("SMALLINT", "32768"));
        assertThrows(IllegalArgumentException.class, () -> EtlManagedOdps.value("FLOAT", "1.100000000001"));
        assertThrows(IllegalArgumentException.class, () -> EtlManagedOdps.value("DOUBLE", "1e-10000"));
        assertThrows(IllegalArgumentException.class, () -> EtlManagedOdps.value("DATETIME", "2026-11-02T00:00:00.000001Z"));
    }
    @Test void sdkSchemaRequiresExactPhysicalOrderTypesAndEveryPartitionKey() {
        var expected = new LinkedHashMap<String, String>(); expected.put("id", "BIGINT"); expected.put("amount", "DECIMAL(24,4)");
        var plan = JSONUtil.createObj().set("columns", expected).set("partition", "ds='20261102'");
        var schema = new TableSchema(); schema.addColumn(new Column("id", TypeInfoParser.getTypeInfoFromTypeString("BIGINT")));
        schema.addColumn(new Column("amount", TypeInfoParser.getTypeInfoFromTypeString("DECIMAL(24,4)"))); schema.addPartitionColumn(new Column("ds", TypeInfoParser.getTypeInfoFromTypeString("STRING")));
        assertDoesNotThrow(() -> EtlManagedOdps.validateSchema(plan, schema));
        plan.set("partition", ""); assertThrows(IllegalArgumentException.class, () -> EtlManagedOdps.validateSchema(plan, schema)); plan.set("partition", "ds='20261102'");
        var reversed = new LinkedHashMap<String, String>(); reversed.put("amount", "DECIMAL(24,4)"); reversed.put("id", "BIGINT");
        plan.set("columns", reversed); assertThrows(IllegalArgumentException.class, () -> EtlManagedOdps.validateSchema(plan, schema));
        plan.set("columns", expected); expected.put("amount", "DECIMAL(25,4)"); plan.set("columns", expected);
        assertThrows(IllegalArgumentException.class, () -> EtlManagedOdps.validateSchema(plan, schema));
    }
    @Test void endpointsCannotCarryCredentialsQueriesOrUnmanagedProtocols() {
        for (String endpoint : new String[]{"file:///tmp/data", "https://name:private@service/api", "https://service/api?key=private", "https://service/api#fragment"})
            assertThrows(IllegalArgumentException.class, () -> EtlOdpsTransport.endpoint(URI.create(endpoint)));
        assertEquals("https://service/api", EtlOdpsTransport.endpoint(URI.create("https://service/api")).toString());
    }
    @Test void sdkFloatValuesArePromotedAndWrittenWithoutChangingTheirBinaryValue() throws Exception {
        var column = new Column("value", TypeInfoParser.getTypeInfoFromTypeString("FLOAT"));
        var record = new com.aliyun.odps.data.ArrayRecord(new Column[]{column});
        for (float value : new float[]{1.1f, -0.0f, Float.MIN_VALUE, Float.MAX_VALUE}) {
            record.setFloat("value", value);
            String expanded = EtlManagedOdps.text(record.get("value"), record, "value", "FLOAT");
            assertEquals((double) value, Double.parseDouble(expanded));
            assertEquals(value, EtlManagedOdps.value("FLOAT", expanded));
        }
        assertThrows(IllegalArgumentException.class, () -> EtlManagedOdps.value("FLOAT", "1.1"));
        assertThrows(IllegalArgumentException.class, () -> EtlManagedOdps.value("FLOAT", "1.40129846432481700000001E-45"));
    }
    @Test void tunnelCannotImplicitlyReplayServerFailuresOrRateLimitedFinalCommits() throws Exception {
        for (int status : new int[]{429, 500, 503}) {
            var count = new AtomicInteger(); var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/projects/fixture/tables/target", exchange -> {
                count.incrementAndGet(); byte[] body = "private-fixture-error-body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length); try (var output = exchange.getResponseBody()) { output.write(body); }
            }); server.start();
            try {
                URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
                var transport = new EtlOdpsTransport(endpoint, endpoint, "fixture", "target", true);
                var request = new com.aliyun.odps.commons.transport.Request(); request.setURI(endpoint.resolve("/projects/fixture/tables/target?uploadid=session"));
                request.setMethod(com.aliyun.odps.commons.transport.Request.Method.POST);
                var failure = assertThrows(IOException.class, () -> new com.aliyun.odps.tunnel.io.TunnelRetryHandler().executeWithRetry(() -> {
                    var connection = transport.connect(request);
                    try { return connection.getResponse(); } finally { connection.disconnect(); }
                }));
                assertEquals(1, count.get()); assertFalse(failure.getMessage().contains("private-fixture"));
            } finally { server.stop(0); }
        }
    }
}
