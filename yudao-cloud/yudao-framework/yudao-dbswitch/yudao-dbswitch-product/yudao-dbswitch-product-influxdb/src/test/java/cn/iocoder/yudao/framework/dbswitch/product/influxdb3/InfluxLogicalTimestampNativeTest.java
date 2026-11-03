package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LogicalType;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Isolated;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.TimeZone;
import java.util.UUID;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.INFLUXDB3;
import static org.junit.jupiter.api.Assertions.*;

/** Catalog and actual Flight JDBC values must justify the timestamp mapping warning. */
@Timeout(60)
@Isolated("Pins the JVM zone while observing the Flight SQL timestamp codec")
@EnabledIfSystemProperty(named = "datahub.test.influx.http", matches = "http://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_INFLUX_TEST_TOKEN", matches = "apiv3_.+")
class InfluxLogicalTimestampNativeTest {
    @Test void nativeNanosecondsAndJdbcEpochShiftKeepTheMappingLossVisible() throws Exception {
        String database = "time88_" + UUID.randomUUID().toString().replace("-", "");
        URI endpoint = URI.create(System.getProperty("datahub.test.influx.http"));
        TimeZone previous = TimeZone.getDefault();
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
                request(http, "/api/v3/configure/database", "POST", "{\"db\":\"" + database + "\"}", "application/json");
                long nanos = 1791290096123456789L;
                request(http, "/api/v3/write_lp?db=" + database + "&precision=nanosecond", "POST",
                        "sample value=1i " + nanos, "text/plain");
                var properties = new Properties();
                properties.setProperty("token", System.getenv("DATAHUB_INFLUX_TEST_TOKEN"));
                Class.forName(INFLUXDB3.getDriveClassName());
                try (var connection = DriverManager.getConnection("jdbc:arrow-flight-sql://127.0.0.1:"
                        + endpoint.getPort() + "/?useEncryption=0&database=" + database, properties)) {
                    var snapshot = NativeSchemaReader.read(connection, INFLUXDB3, "iox", "sample", 10);
                    var time = snapshot.columns().stream().filter(c -> c.getFieldName().equals("time")).findFirst().orElseThrow();
                    assertEquals("Timestamp(ns)", time.getMetadataEvidence().nativeType().value());
                    var mapping = new InfluxDb3LogicalTypeMapper().toLogical(time);
                    assertEquals(TypeFidelity.LOSSY, mapping.fidelity());
                    var type = assertInstanceOf(LogicalType.TimestampType.class, mapping.value());
                    assertEquals(9, type.fractionalDigits());
                    assertEquals(LogicalType.TimeZoneSemantics.INSTANT, type.zone());
                    assertTrue(mapping.issues().stream().anyMatch(i -> i.code().equals("TIMESTAMP_JDBC_EPOCH_SHIFTED")));
                    try (var statement = connection.createStatement();
                         var rows = statement.executeQuery("SELECT time,CAST(time AS BIGINT) AS epoch FROM iox.sample")) {
                        assertTrue(rows.next());
                        assertEquals(nanos, rows.getLong("epoch"));
                        Instant stored = Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
                        var decoded = rows.getTimestamp("time");
                        assertEquals(123456789, decoded.getNanos());
                        assertEquals(stored.minus(Duration.ofHours(16)), decoded.toInstant());
                        assertFalse(rows.next());
                    }
                }
            } finally {
                TimeZone.setDefault(previous);
                request(http, "/api/v3/configure/database?db=" + database, "DELETE", "", "application/json");
            }
        }
    }

    private static void request(HttpClient http, String path, String method, String body, String contentType) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.influx.http") + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + System.getenv("DATAHUB_INFLUX_TEST_TOKEN"))
                .header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        assertTrue(status >= 200 && status < 300, () -> "Influx fixture HTTP status " + status);
    }
}
