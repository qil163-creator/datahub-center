package cn.iocoder.yudao.module.supply.service.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** 使用显式本机测试实例或 Testcontainers；每个测试新建并清理自己的空数据库。 */
public abstract class SharingPostgresSupport {
    protected Connection connection;
    protected String jdbcUrl;
    protected String jdbcUser;
    protected String jdbcPassword;
    private static org.testcontainers.containers.PostgreSQLContainer<?> docker;
    private String controlUrl;
    private String databaseName;

    @BeforeEach
    void createTestDatabase() throws Exception {
        controlUrl = System.getenv("SHARING_TEST_JDBC_URL");
        if (controlUrl == null || controlUrl.isBlank()) {
            var database = dockerDatabase();
            controlUrl = "jdbc:postgresql://127.0.0.1:" + database.getMappedPort(5432) + "/postgres";
            jdbcUser = database.getUsername();
            jdbcPassword = database.getPassword();
        } else {
            jdbcUser = System.getenv().getOrDefault("SHARING_TEST_JDBC_USER", "sharing_test");
            jdbcPassword = "";
            if (!java.util.Set.of("sharing_test", "dataforge_test").contains(jdbcUser)) {
                throw new IllegalStateException("Only isolated test roles are accepted");
            }
            if (!controlUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")) {
                throw new IllegalStateException("Explicit local isolated SHARING_TEST_JDBC_URL is required");
            }
        }
        try (var control = DriverManager.getConnection(controlUrl, jdbcUser, jdbcPassword);
             var statement = control.createStatement()) {
            try (var rs = statement.executeQuery("SELECT current_user")) {
                rs.next();
                if (!jdbcUser.equals(rs.getString(1))) throw new IllegalStateException("Not test role");
            }
            databaseName = "sharing_test_" + UUID.randomUUID().toString().replace("-", "");
            statement.execute("CREATE DATABASE " + databaseName);
        }
        jdbcUrl = controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + databaseName;
        connection = open();
        if (!useLegacyFixture()) return;
        try (var input = getClass().getResourceAsStream("/sql/sharing-legacy-prerequisites.sql")) {
            if (input == null) throw new IllegalStateException("Missing test-only legacy fixture");
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    protected boolean useLegacyFixture() { return true; }

    private static synchronized org.testcontainers.containers.PostgreSQLContainer<?> dockerDatabase() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.testcontainers.DockerClientFactory.instance().isDockerAvailable(), "Docker or isolated PostgreSQL is required");
        if (docker == null) {
            docker = new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");
            docker.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> docker.stop()));
        }
        return docker;
    }

    protected Connection open() throws SQLException {
        var c = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword);
        try (var s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS \"datahub-center\"");
            s.execute("SET search_path TO \"datahub-center\", public");
        }
        return c;
    }

    protected void migrate(String filename) throws Exception {
        try {
            sql(migrationSql(filename));
        } catch (SQLException ex) {
            sql("ROLLBACK");
            throw ex;
        }
    }

    /** 已被基线吸收的编号块从当前基线读取，不恢复已删除的发布迁移文件。 */
    public static String migrationSql(String filename) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot locate repository migrations");
        Path migration = root.resolve("sql/postgresql/migrations").resolve(filename);
        if (Files.exists(migration)) return Files.readString(migration);
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"));
        String number = filename.substring(0, 4);
        if (java.util.Set.of("0070", "0071").contains(number)) {
            int start = baseline.indexOf("-- " + number + " ");
            int end = baseline.indexOf("-- " + number + " ", start + 1);
            if (start >= 0 && end > start) return baseline.substring(start, end);
        }
        int start = baseline.indexOf("-- Migration: " + number);
        int end = start < 0 ? -1 : baseline.indexOf("\nCOMMIT;", start);
        if (start < 0 || end < 0) throw new IllegalStateException("Missing baseline migration block: " + filename);
        return baseline.substring(start, end + "\nCOMMIT;".length());
    }

    protected void sql(String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    protected long scalar(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(sql)) {
            rs.next(); return rs.getLong(1);
        }
    }

    @AfterEach
    void closeTestDatabase() throws Exception {
        if (connection != null) connection.close();
        if (databaseName != null && databaseName.matches("sharing_test_[0-9a-f]{32}")) {
            try (var control = DriverManager.getConnection(controlUrl, jdbcUser, jdbcPassword);
                 var statement = control.createStatement()) {
                statement.execute("DROP DATABASE " + databaseName);
            }
        }
    }
}
