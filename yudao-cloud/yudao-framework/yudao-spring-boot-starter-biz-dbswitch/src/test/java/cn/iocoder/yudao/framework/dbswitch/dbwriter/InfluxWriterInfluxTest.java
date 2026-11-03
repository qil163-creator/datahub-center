package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.influxdb.InfluxdbWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.InfluxdbDatabaseOperator;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Existing-table writer contract using an explicitly supplied standalone driver bundle.
 * Requires datahub.test.influx.driver and the existing JDK java.nio opens option for Arrow Flight. */
@EnabledIfSystemProperty(named = "datahub.test.influx.http", matches = "http://127[.]0[.]0[.]1:[0-9]+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_INFLUX_TEST_TOKEN", matches = "apiv3_.+")
@Timeout(60)
class InfluxWriterInfluxTest {
    private static final List<String> FIELDS = List.of("time", "id", "value");
    private static final Instant TIME = Instant.parse("2026-01-01T00:00:00.123456789Z");
    private static ConfiguredJdbcDrivers drivers;
    private HttpClient http;
    private HikariDataSource source;
    private String database;
    private String table;

    @BeforeAll
    static void verifyExplicitFixtureDriverBundle() throws Exception {
        String supplied = System.getProperty("datahub.test.influx.driver");
        assertNotNull(supplied, "An enabled Influx fixture requires an explicit datahub.test.influx.driver standalone JAR");
        Path artifact = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(artifact), "The configured Influx fixture driver must be a local JAR file");
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        var spec = new DbSwitchProperties.Driver();
        spec.setPath(artifact.toString()); spec.setSha256(HexFormat.of().formatHex(digest.digest()));
        spec.setDriverClassName(ProductTypeEnum.INFLUXDB3.getDriveClassName());
        drivers = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.INFLUXDB3, spec));
    }

    @AfterAll
    static void closeDriverOwnerAfterEveryPool() {
        if (drivers != null) drivers.close();
    }

    @BeforeEach
    void createExistingTypedTableAndComponentPool() throws Exception {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        database = "writer_" + UUID.randomUUID().toString().replace("-", "");
        table = "sample_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        request("POST", "/api/v3/configure/database", "{\"db\":\"" + database + "\"}", "application/json");
        // Influx creates the table through line protocol. The component's existing-table writer
        // must discover this schema; the test does not claim automatic table creation support.
        request("POST", "/api/v3/write_lp?db=" + database + "&precision=millisecond",
                table + " id=0i,value=0i 1767225600000", "text/plain");
        var endpoint = URI.create(System.getProperty("datahub.test.influx.http"));
        var properties = new TargetDataSourceProperties();
        properties.setTargetProductType(ProductTypeEnum.INFLUXDB3);
        properties.setUrl("jdbc:arrow-flight-sql://127.0.0.1:" + endpoint.getPort()
                + "/?useEncryption=0&database=" + database);
        properties.setPassword(System.getenv("DATAHUB_INFLUX_TEST_TOKEN"));
        properties.setPoolSize(1); properties.setConnectionTimeout(5_000L);
        source = DataSourceUtils.createTargetDataSource(properties, null, null,
                drivers.find(ProductTypeEnum.INFLUXDB3).orElseThrow(), drivers.runtimes());
    }

    @AfterEach
    void deleteOnlyTheOwnedFixture() throws Exception {
        try {
            if (source != null) {
                source.close();
                assertEquals(0, drivers.runtimes().connections().snapshot().active());
                assertEquals(0, drivers.runtimes().connections().snapshot().quarantined());
            }
        } finally {
            try {
                if (database != null && http != null) {
                    request("DELETE", "/api/v3/configure/database?db=" + database, "", "application/json");
                }
            } finally {
                if (http != null) http.close();
            }
        }
    }

    @Test
    void componentPoolAndWriterPreserveSignedIntegerBoundariesAndAdjacentNanoseconds() throws Exception {
        var writer = preparedWriter();
        var secondTime = TIME.plusNanos(1);
        var first = new Object[]{TIME, 9_007_199_254_740_993L, BigInteger.valueOf(Long.MAX_VALUE)};
        var second = new Object[]{secondTime, 9_007_199_254_740_994L, BigInteger.valueOf(Long.MIN_VALUE)};
        var rows = List.of(first, second);
        assertEquals(2, writer.write(FIELDS, rows));
        assertSame(first, rows.getFirst()); assertEquals(BigInteger.valueOf(Long.MAX_VALUE), first[2]);
        try (var connection = source.getConnection(); var query = connection.createStatement()) {
            query.setQueryTimeout(15);
            try (var result = query.executeQuery("SELECT id,value,time FROM iox." + table + " WHERE id<>0 ORDER BY id")) {
                assertTrue(result.next()); assertEquals(9_007_199_254_740_993L, result.getLong("id"));
                assertEquals(Long.MAX_VALUE, result.getLong("value"));
                assertEquals(TIME, result.getTimestamp("time").toInstant());
                assertEquals(TIME, ((Timestamp) result.getObject("time")).toInstant());
                assertEquals(TIME, result.getObject("time", Instant.class));
                assertEquals(TIME, result.getTimestamp("time", Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))).toInstant());
                assertTrue(result.next()); assertEquals(9_007_199_254_740_994L, result.getLong("id"));
                assertEquals(Long.MIN_VALUE, result.getLong("value"));
                assertEquals(secondTime, result.getTimestamp("time").toInstant());
                assertFalse(result.next(), "Adjacent nanosecond points must remain two distinct rows");
            }
        }
        assertEquals(3L, countRows());
    }

    @Test
    void sourceOperatorGetObjectCanFeedTheTargetWriterWithoutShiftingAbsoluteTime() throws Exception {
        preparedWriter().write(FIELDS, List.<Object[]>of(new Object[]{TIME, 1L, 9_007_199_254_740_993L}));
        var operator = new InfluxdbDatabaseOperator(source);
        var copied = new ArrayList<Object[]>();
        try (var read = operator.queryTableData("SELECT time,id,value FROM iox." + table + " WHERE id=1", FIELDS)) {
            var rows = read.getResultset();
            assertTrue(rows.next());
            var row = new Object[]{rows.getObject(1), rows.getObject(2), rows.getObject(3)};
            assertEquals(TIME, ((Timestamp) row[0]).toInstant()); copied.add(row);
            assertFalse(rows.next());
        }
        String target = table + "_copy";
        request("POST", "/api/v3/write_lp?db=" + database + "&precision=millisecond",
                target + " id=0i,value=0i 1767225600000", "text/plain");
        var writer = new InfluxdbWriterImpl(source); writer.prepareWrite("iox", target, FIELDS);
        assertEquals(1, writer.write(FIELDS, copied));
        try (var connection = source.getConnection(); var query = connection.createStatement();
             var rows = query.executeQuery("SELECT value,time FROM iox." + target + " WHERE id=1")) {
            assertTrue(rows.next()); assertEquals(9_007_199_254_740_993L, rows.getLong(1));
            assertEquals(TIME, rows.getObject(2, Instant.class)); assertFalse(rows.next());
        }
    }

    static Stream<Object[]> invalidLaterRows() {
        return Stream.<Object[]>of(new Object[]{TIME.plusNanos(1), 2L, new BigDecimal("1.25")},
                new Object[]{TIME.plusNanos(1), 2L, BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)},
                new Object[]{null, 2L, 2L}, new Object[]{"invalid-time", 2L, 2L},
                new Object[]{new BigDecimal("1.1"), 2L, 2L});
    }

    @ParameterizedTest @MethodSource("invalidLaterRows")
    void invalidLaterRowCannotPersistTheValidPrefix(Object timestamp, Object id, Object value) throws Exception {
        var writer = preparedWriter();
        var rows = List.of(new Object[]{TIME, 1L, 1L}, new Object[]{timestamp, id, value});
        var failure = assertThrows(IllegalArgumentException.class, () -> writer.write(FIELDS, rows));
        assertNull(failure.getCause());
        assertEquals(1L, countRows(), "Only the seed row may remain after a rejected batch");
    }

    @Test
    void omittedTimestampMappingCannotWriteOrReuseThePreviousPreparedTarget() throws Exception {
        var writer = preparedWriter();
        var fieldsWithoutTime = List.of("id", "value");
        assertThrows(IllegalArgumentException.class, () -> writer.prepareWrite("iox", table, fieldsWithoutTime));
        assertThrows(IllegalArgumentException.class,
                () -> writer.write(fieldsWithoutTime, List.<Object[]>of(new Object[]{1L, 1L})));
        assertThrows(IllegalArgumentException.class,
                () -> writer.write(FIELDS, List.<Object[]>of(new Object[]{TIME, 1L, 1L})));
        assertEquals(1L, countRows());
    }

    private InfluxdbWriterImpl preparedWriter() {
        var writer = new InfluxdbWriterImpl(source);
        writer.prepareWrite("iox", table, FIELDS);
        return writer;
    }

    private long countRows() throws Exception {
        try (var connection = source.getConnection(); var query = connection.createStatement()) {
            query.setQueryTimeout(15);
            try (var result = query.executeQuery("SELECT COUNT(*) FROM iox." + table)) {
                assertTrue(result.next()); return result.getLong(1);
            }
        }
    }

    private void request(String method, String path, String body, String contentType) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.influx.http") + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + System.getenv("DATAHUB_INFLUX_TEST_TOKEN"))
                .header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        assertTrue(status >= 200 && status < 300, () -> "Influx fixture HTTP status " + status);
    }
}
