package cn.iocoder.yudao.module.asset.service.market;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** 每个测试类使用独立的随机数据库，每个用例重建业务 schema，避免测试间残留。 */
abstract class SharingPostgresSupport {
    protected Connection connection;
    protected String jdbcUrl;
    protected String jdbcUser;
    private record TestDatabase(String controlUrl, String databaseName, String user) {
        String url() { return controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + databaseName; }
    }
    private static final java.util.Map<Class<?>, TestDatabase> DATABASES = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeAll
    static void createTestDatabase(TestInfo info) throws Exception {
        String controlUrl = System.getenv("SHARING_TEST_JDBC_URL");
        String jdbcUser = System.getenv().getOrDefault("SHARING_TEST_JDBC_USER", "sharing_test");
        if (!java.util.Set.of("sharing_test", "dataforge_test").contains(jdbcUser)) throw new IllegalStateException("Only isolated test roles are accepted");
        if (controlUrl == null || !controlUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")) {
            throw new IllegalStateException("Explicit local isolated SHARING_TEST_JDBC_URL is required");
        }
        try (var control = DriverManager.getConnection(controlUrl, jdbcUser, "");
             var statement = control.createStatement()) {
            try (var rs = statement.executeQuery("SELECT current_user")) {
                rs.next();
                if (!jdbcUser.equals(rs.getString(1))) throw new IllegalStateException("Not test role");
            }
            String databaseName = "sharing_test_" + UUID.randomUUID().toString().replace("-", "");
            statement.execute("CREATE DATABASE " + databaseName);
            DATABASES.put(info.getTestClass().orElseThrow(), new TestDatabase(controlUrl, databaseName, jdbcUser));
        }
    }

    @BeforeEach
    void resetTestSchema() throws Exception {
        var database = DATABASES.get(getClass());
        jdbcUrl = database.url();
        jdbcUser = database.user();
        connection = open();
        sql("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE; CREATE SCHEMA \"datahub-center\"; SET search_path TO \"datahub-center\", public");
        try (var input = getClass().getResourceAsStream("/sql/sharing-legacy-prerequisites.sql")) {
            if (input == null) throw new IllegalStateException("Missing test-only legacy fixture");
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    protected Connection open() throws SQLException {
        var c = DriverManager.getConnection(jdbcUrl, jdbcUser, "");
        try (var s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS \"datahub-center\"");
            s.execute("SET search_path TO \"datahub-center\", public");
        }
        return c;
    }

    protected void migrate(String filename) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot locate repository migrations");
        try {
            Path migration = root.resolve("sql/postgresql/migrations").resolve(filename);
            if (Files.isRegularFile(migration)) {
                sql(Files.readString(migration));
            } else {
                // Older SQL is now incorporated in the published baseline. These are runtime fixtures,
                // not a reconstruction or verification of a deleted historical migration file.
                String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql"))
                        .replace("\r\n", "\n");
                String fixture = switch (filename.substring(0, 4)) {
                    case "0042" -> slice(baseline, "-- Migration: 0042 ", "\nCOMMIT;")
                            .replace(slice(baseline, "-- 0050：", "\nALTER TABLE dh_market_delivery_operation"), "\nALTER TABLE dh_market_delivery_operation");
                    case "0047" -> slice(baseline, "ALTER TABLE dh_market_resource_apply\n    ADD COLUMN IF NOT EXISTS renewal_of_apply_id", "-- 0050：")
                            .replace("-- 0050：", "");
                    case "0050" -> slice(baseline, "-- 0050：", "\nALTER TABLE dh_market_delivery_operation")
                            .replace("\nALTER TABLE dh_market_delivery_operation", "");
                    case "0066" -> slice(baseline, "-- 0066：", "-- 0066 核查审计结构结束。");
                    case "0067" -> slice(baseline, "-- 0067：", "-- 0067 批准凭证结构结束。");
                    case "0068" -> slice(baseline, "CREATE UNIQUE INDEX uk_market_demand_match_active", ";");
                    case "0069" -> slice(baseline, "-- 0069：", "-- 0069 交付处置审计结构结束。");
                    case "0072" -> slice(baseline, "-- 0072 交付受控重投意图审计唯一索引开始。", "-- 0072 交付受控重投意图审计唯一索引结束。");
                    default -> throw new IllegalStateException("No current baseline fixture for " + filename);
                };
                sql(fixture);
            }
        } catch (SQLException ex) {
            sql("ROLLBACK");
            throw ex;
        }
    }

    private static String slice(String source, String start, String end) {
        int from = source.indexOf(start);
        int through = from < 0 ? -1 : source.indexOf(end, from + start.length());
        if (through < 0) throw new IllegalStateException("Missing baseline fixture: " + start);
        return source.substring(from, through + end.length());
    }

    /** Runtime mappers always run against the actual current single-tenant schema. */
    protected com.baomidou.mybatisplus.core.MybatisConfiguration singleTenantConfiguration() {
        try {
            // Current catalog retirement fields from the baseline are required by the live DO.
            sql("ALTER TABLE IF EXISTS dh_asset_data_asset "
                    + "ADD COLUMN IF NOT EXISTS retirement_requested boolean NOT NULL DEFAULT false, "
                    + "ADD COLUMN IF NOT EXISTS stop_supply_user_id bigint, "
                    + "ADD COLUMN IF NOT EXISTS stop_supply_dept_id bigint, "
                    + "ADD COLUMN IF NOT EXISTS stop_supply_reason varchar(500), "
                    + "ADD COLUMN IF NOT EXISTS stop_supply_time timestamp, "
                    + "ADD COLUMN IF NOT EXISTS registration_config_json text");
            // 运行时 Mapper 使用当前申请字段，旧迁移夹具仍保持原始结构。
            sql("ALTER TABLE IF EXISTS dh_market_resource_apply ADD COLUMN IF NOT EXISTS source_work_order_id bigint");
            migrate("0169_datahub_remove-business-tenancy.sql");
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot prepare single-tenant runtime schema", failure);
        }
        return new com.baomidou.mybatisplus.core.MybatisConfiguration();
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
    void closeTestConnection() throws Exception {
        if (connection != null) connection.close();
    }

    @AfterAll
    static void closeTestDatabase(TestInfo info) throws Exception {
        var database = DATABASES.remove(info.getTestClass().orElseThrow());
        if (database != null && database.databaseName().matches("sharing_test_[0-9a-f]{32}")) {
            try (var control = DriverManager.getConnection(database.controlUrl(), database.user(), "");
                 var statement = control.createStatement()) {
                try {
                    statement.execute("DROP DATABASE " + database.databaseName() + " WITH (FORCE)");
                } catch (SQLException error) {
                    // A server maintenance backend can run as another role; ordinary DROP
                    // lets PostgreSQL stop that backend without granting broad signaling rights.
                    if (!"42501".equals(error.getSQLState())) throw error;
                    statement.execute("DROP DATABASE " + database.databaseName());
                }
            }
        }
    }
}
