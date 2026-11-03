package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #17 InfluxDB 3 Core 3.11.5 差异验证。SQL 模式名为 iox，不带引号大小写不敏感，双引号按原文。
 * 反斜杠不是转义。表由行协议写入，CREATE TABLE 不被服务器接受。
 */
@EnabledIfSystemProperty(named = "datahub.test.influx.http", matches = "http://127[.]0[.]0[.]1:[0-9]+")
class RestrictedProjectionInfluxDb3Test {
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Connection connection;
    private String database;
    private boolean databaseCreated;

    @BeforeEach
    void create() throws Exception {
        Class.forName(ProductTypeEnum.INFLUXDB3.getDriveClassName());
        if (token() == null || token().isBlank()) throw new IllegalStateException("Influx test credential environment is required");
        database = RestrictedProjectionDifferential.schemaName("q17");
        String http = System.getProperty("datahub.test.influx.http");
        send(HttpRequest.newBuilder(URI.create(http + "/api/v3/configure/database"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"db\":\"" + database + "\"}"))
                .build());
        databaseCreated = true;
        send(HttpRequest.newBuilder(URI.create(http + "/api/v3/write_lp?db=" + database + "&precision=s"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "citizen id=1i,name=\"row\",secret=\"s1\"\ncitizen id=2i,name=\"row\",secret=\"s2\"\n"))
                .build());
        int port = URI.create(http).getPort();
        var properties = new Properties();
        properties.setProperty("token", token());
        connection = DriverManager.getConnection(
                "jdbc:arrow-flight-sql://127.0.0.1:" + port + "/?useEncryption=0&database=" + database, properties);
    }

    @AfterEach
    void close() throws Exception {
        try {
            if (connection != null) connection.close();
        } finally {
            try {
                if (databaseCreated) send(HttpRequest.newBuilder(URI.create(System.getProperty("datahub.test.influx.http")
                                + "/api/v3/configure/database?db=" + database))
                        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token()).DELETE().build());
            } finally { httpClient.close(); }
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumns() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.INFLUXDB3, connection, "iox", "citizen",
                List.of("SELECT 1"));
        assertEquals(0, accepted);
    }

    private void send(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("InfluxDB fixture HTTP " + response.statusCode());
        }
    }
    private static String token() { return System.getenv("DATAHUB_INFLUX_TEST_TOKEN"); }
}
