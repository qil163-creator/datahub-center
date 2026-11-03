package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@Timeout(120)
class DatabaseCapacityIntegrationTest {
    private static final String SECRET = UUID.randomUUID().toString();
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", SECRET).withEnv("MYSQL_DATABASE", "capacity_fixture")
            .withExposedPorts(3306).waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1))
            .withStartupTimeout(Duration.ofMinutes(2));

    @Test void postgresReadsTheConnectedDatabaseAndRemainsUsableAfterCollection() throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var metadata = runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL);
            var bytes = metadata.queryDatabaseUsedBytes(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), 5000);
            assertTrue(bytes > 0);
            metadata.testQuerySQL(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), "SELECT 1");
        }
    }

    @Test void mysqlReadsOnlyCurrentCatalogTableStatistics() throws Exception {
        String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/capacity_fixture?allowPublicKeyRetrieval=true&useSSL=false";
        try (var connection = DriverManager.getConnection(url, "root", SECRET);
             var statement = connection.createStatement(); var runtime = DbSwitchRuntime.standalone()) {
            statement.execute("CREATE TABLE capacity_fixture.sample(id integer PRIMARY KEY, content varchar(1000))");
            statement.execute("INSERT INTO capacity_fixture.sample VALUES(1, REPEAT('x', 1000))");
            statement.execute("ANALYZE TABLE capacity_fixture.sample");
            long bytes = runtime.jdbcMetadata(ProductTypeEnum.MYSQL).queryDatabaseUsedBytes(url, "root", SECRET, 5000);
            assertTrue(bytes > 0);
            assertTrue(connection.isValid(2));
        }
    }
}
