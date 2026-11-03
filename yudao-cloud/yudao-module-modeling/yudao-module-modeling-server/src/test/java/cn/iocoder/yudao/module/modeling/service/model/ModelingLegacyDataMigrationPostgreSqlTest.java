package cn.iocoder.yudao.module.modeling.service.model;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 当前结构升级的旧数据保留回归测试；不猜测旧分类或关系的主域，不重造已移除的自动适配脚本。
 *
 * <p>默认仅使用本测试创建的容器。无 Docker 时，可显式传入
 * MODELING_LEGACY_TEST_JDBC_URL、MODELING_LEGACY_TEST_USER、
 * MODELING_LEGACY_TEST_PASSWORD；只接受回环地址上的 modeling_legacy_test 专用库。
 * 不读取应用配置，也不允许通过连接串参数绕过目标校验。</p>
 */
@Execution(ExecutionMode.SAME_THREAD)
class ModelingLegacyDataMigrationPostgreSqlTest {

    private static final String MIGRATION =
            ModelingMigrationTestSupport.MIGRATION;
    // 与运维脚本同步的当前只读预检；兼容历史夹具，不查询已退役的物理、概念或过程记录。
    private static final String PRECHECK = "yudao-module-modeling/yudao-module-modeling-server/src/test/resources/modeling/legacy-adaptation/precheck.sql";
    private static final String SCHEMA = "\"datahub-center\".";
    private static PostgreSQLContainer<?> postgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;
    private Connection connection;

    @BeforeAll
    static void startIsolatedDatabase() throws Exception {
        jdbcUrl = System.getenv("MODELING_LEGACY_TEST_JDBC_URL");
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            if (!jdbcUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]{1,5}/modeling_legacy_test")) {
                throw new IllegalArgumentException("迁移测试只允许连接本机 modeling_legacy_test 专用库");
            }
            username = System.getenv("MODELING_LEGACY_TEST_USER");
            password = System.getenv("MODELING_LEGACY_TEST_PASSWORD");
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("必须显式指定迁移测试用户，禁止读取应用数据库身份");
            }
            if (password == null) {
                password = "";
            }
            try (Connection checked = newConnection();
                 Statement statement = checked.createStatement();
                 var result = statement.executeQuery(
                         "SELECT current_database(), host(inet_server_addr())")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("modeling_legacy_test");
                assertThat(result.getString(2)).isEqualTo("127.0.0.1");
            }
            return;
        }
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "无 Docker 且未配置专用回环 PostgreSQL，真实数据库验证未执行");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("modeling_legacy_test");
        postgres.start();
        jdbcUrl = postgres.getJdbcUrl();
        username = postgres.getUsername();
        password = postgres.getPassword();
    }

    @AfterAll
    static void stopOwnedContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void createLegacyFixture() throws Exception {
        connection = newConnection();
        // 已在类初始化时校验专用数据库；每个用例独占并重建固定测试 schema。
        execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
        execute("CREATE SCHEMA \"datahub-center\"");
        try (var resource = getClass().getResourceAsStream(
                "/modeling/legacy-adaptation/legacy-schema.sql")) {
            assertThat(resource).isNotNull();
            execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
        ModelingMigrationTestSupport.createMissingBaselineTables(connection);
        execute("""
                INSERT INTO "datahub-center".dh_modeling_data_domain (id,tenant_id,status)
                VALUES (11,1,0),(12,1,0),(13,1,0),(14,1,1),(21,2,0);
                INSERT INTO "datahub-center".dh_modeling_biz_category (id,domain_ids)
                VALUES (1,'[11,12]'::jsonb),(2,'[13]'::jsonb);
                INSERT INTO "datahub-center".dh_modeling_business_process (id,biz_category_id)
                VALUES (100,1),(101,2);
                INSERT INTO "datahub-center".dh_modeling_subject_domain (id,tenant_id,status,layer_id)
                VALUES (30,1,0,700),(31,2,0,701),(32,1,1,702);
                """);
    }

    @AfterEach
    void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void preservesLegacySourcesWithoutGuessingPrimaryFromSingleCandidate() throws Exception {
        execute("""
                INSERT INTO "datahub-center".dh_modeling_business_process_data_domain_rel
                  (id,business_process_id,data_domain_id,relation_role)
                VALUES (400,100,11,'PRIMARY'),(401,100,12,'SUPPORTING');
                """);
        String categories = tableSnapshot("dh_modeling_biz_category");
        String relations = tableSnapshot("dh_modeling_business_process_data_domain_rel");
        precheckPreservesRows();
        migrate();
        assertThat(primary(100)).isNull();
        assertThat(primary(101)).isNull();
        assertThat(columnExists("dh_modeling_biz_category", "domain_ids")).isTrue();
        assertThat(columnExists("dh_modeling_subject_domain", "layer_id")).isTrue();
        assertThat(tableSnapshot("dh_modeling_biz_category")).isEqualTo(categories);
        assertThat(tableSnapshot("dh_modeling_business_process_data_domain_rel")).isEqualTo(relations);
        assertThat(count("dh_modeling_business_process_supporting_domain_rel", "deleted=0")).isZero();
        assertThat(count("dh_modeling_business_process_domain_candidate", "deleted=0")).isZero();
    }

    @Test
    void ambiguousDisabledAndCrossTenantSourcesNeverBecomeCurrentRelations() throws Exception {
        execute("""
                INSERT INTO "datahub-center".dh_modeling_business_process (id,biz_category_id,status)
                VALUES (102,1,0),(103,1,0),(104,1,0),(105,1,1),(106,1,0);
                INSERT INTO "datahub-center".dh_modeling_business_process_data_domain_rel
                  (id,business_process_id,data_domain_id,relation_role,tenant_id,deleted)
                VALUES (410,102,11,'PRIMARY',1,0),(411,102,12,'PRIMARY',1,0),
                       (412,103,21,'PRIMARY',1,0),(413,104,14,'PRIMARY',1,0),
                       (414,105,11,'PRIMARY',1,0),(415,106,11,'PRIMARY',2,0),
                       (416,106,12,'PRIMARY',1,1);
                """);
        String relations = tableSnapshot("dh_modeling_business_process_data_domain_rel");
        migrate();
        for (long id : new long[]{102, 103, 104, 105, 106}) {
            assertThat(primary(id)).as("过程 %s 不能被自动确认归属", id).isNull();
        }
        assertThat(tableSnapshot("dh_modeling_business_process_data_domain_rel")).isEqualTo(relations);
        assertThat(count("dh_modeling_business_process_domain_candidate", "deleted=0")).isZero();
    }

    @Test
    void malformedOverflowAndLargeIdsRemainAvailableForManualResolution() throws Exception {
        execute("""
                INSERT INTO "datahub-center".dh_modeling_data_domain (id) VALUES (9007199254740993);
                UPDATE "datahub-center".dh_modeling_biz_category
                SET domain_ids='[11,"9007199254740993","9223372036854775808",
                    "999999999999999999999999999999999999999999999999999999999999",
                    "bad",-1,0,1.5,true,null,{},[],21,14]'::jsonb WHERE id=1;
                """);
        String categories = tableSnapshot("dh_modeling_biz_category");
        migrate();
        assertThat(tableSnapshot("dh_modeling_biz_category")).isEqualTo(categories);
        assertThat(categories).contains("9007199254740993", "9223372036854775808");
        assertThat(primary(100)).isNull();
        assertThat(count("dh_modeling_business_process_domain_candidate", "deleted=0")).isZero();
    }

    @Test
    void existingConfirmedPrimaryIsNeverOverwrittenByLegacyConflict() throws Exception {
        execute("""
                ALTER TABLE "datahub-center".dh_modeling_business_process ADD COLUMN data_domain_id int8;
                UPDATE "datahub-center".dh_modeling_business_process SET data_domain_id=12 WHERE id=100;
                INSERT INTO "datahub-center".dh_modeling_business_process_data_domain_rel
                  (id,business_process_id,data_domain_id,relation_role) VALUES (420,100,11,'PRIMARY');
                """);
        migrate();
        assertThat(primary(100)).isEqualTo("12");
        assertThat(count("dh_modeling_business_process_data_domain_rel", "id=420 AND data_domain_id=11")).isEqualTo(1);
    }

    @Test
    void modelHeadersFrozenVersionsAndUnresolvedLinksRemainUnchanged() throws Exception {
        seedModels();
        execute("""
                INSERT INTO "datahub-center".dh_modeling_logic_model
                  (id,theme_id,layer_id,subject_domain_id,business_process_id,latest_version)
                VALUES (908,600,701,31,100,3),(909,600,702,32,100,4),(910,600,702,30,999,5);
                """);
        Map<String, String> before = modelSnapshots();
        precheckPreservesRows();
        migrate();
        assertThat(modelSnapshots()).isEqualTo(before);
        assertThat(count("dh_modeling_subject_domain_process_rel", "deleted=0")).isZero();
        assertThat(scalar("SELECT fields::text FROM " + SCHEMA + "dh_modeling_logic_model_version WHERE id=1901"))
                .contains("9007199254740993");
        assertThat(scalar("SELECT published_by FROM " + SCHEMA + "dh_modeling_logic_model_version WHERE id=1901")).isNull();
        assertThat(scalar("SELECT published_time FROM " + SCHEMA + "dh_modeling_physical_model_version WHERE id=1902")).isNull();
    }

    @Test
    void replayDoesNotRewriteConfirmedOrInvalidExistingRelations() throws Exception {
        migrate();
        execute("""
                UPDATE "datahub-center".dh_modeling_business_process SET data_domain_id=21 WHERE id=100;
                INSERT INTO "datahub-center".dh_modeling_business_process_domain_candidate
                  (id,business_process_id,data_domain_id,source_biz_category_id,tenant_id)
                VALUES (11000,100,21,1,1),(11001,999,11,1,1);
                INSERT INTO "datahub-center".dh_modeling_business_process_supporting_domain_rel
                  (id,business_process_id,data_domain_id,tenant_id) VALUES (12000,101,13,1),(12001,100,12,1);
                """);
        String candidates = tableSnapshot("dh_modeling_business_process_domain_candidate");
        String supporting = tableSnapshot("dh_modeling_business_process_supporting_domain_rel");
        String processes = tableSnapshot("dh_modeling_business_process");
        migrate();
        assertThat(tableSnapshot("dh_modeling_business_process_domain_candidate")).isEqualTo(candidates);
        assertThat(tableSnapshot("dh_modeling_business_process_supporting_domain_rel")).isEqualTo(supporting);
        assertThat(tableSnapshot("dh_modeling_business_process")).isEqualTo(processes);
    }

    @Test
    void alreadyUpgradedDatabaseWithoutLegacyColumnsCanReplay() throws Exception {
        migrate();
        execute("""
                ALTER TABLE "datahub-center".dh_modeling_biz_category DROP COLUMN domain_ids;
                ALTER TABLE "datahub-center".dh_modeling_subject_domain DROP COLUMN layer_id;
                UPDATE "datahub-center".dh_modeling_business_process SET data_domain_id=13 WHERE id=101;
                """);
        migrate();
        assertThat(primary(101)).isEqualTo("13");
        assertThat(primary(100)).isNull();
        assertThat(columnExists("dh_modeling_biz_category", "domain_ids")).isFalse();
    }

    @Test
    void unresolvedCategoryAndNonArrayJsonArePreservedWithoutInventingLinks() throws Exception {
        execute("""
                INSERT INTO "datahub-center".dh_modeling_business_process (id,biz_category_id) VALUES (102,999);
                INSERT INTO "datahub-center".dh_modeling_business_process_data_domain_rel
                  (id,business_process_id,data_domain_id,relation_role) VALUES (450,102,11,'LEGACY_INHERITED');
                UPDATE "datahub-center".dh_modeling_biz_category
                  SET domain_ids='{"domainId":11,"保留原因":"旧版结构异常"}'::jsonb WHERE id=1;
                """);
        String categories = tableSnapshot("dh_modeling_biz_category");
        String relations = tableSnapshot("dh_modeling_business_process_data_domain_rel");
        precheckPreservesRows();
        migrate();
        assertThat(tableSnapshot("dh_modeling_biz_category")).isEqualTo(categories);
        assertThat(tableSnapshot("dh_modeling_business_process_data_domain_rel")).isEqualTo(relations);
        assertThat(primary(102)).isNull();
        assertThat(count("dh_modeling_business_process_domain_candidate", "deleted=0")).isZero();
    }

    private void seedModels() throws SQLException {
        long id = 900;
        for (String kind : new String[]{"concept", "logic", "physical"}) {
            execute("INSERT INTO " + SCHEMA + "dh_modeling_" + kind + "_model "
                    + "(id,theme_id,layer_id,subject_domain_id,business_process_id,latest_version,table_type) "
                    + "VALUES (" + id + ",600,700,30,100,7,'DETAIL')");
            execute("INSERT INTO " + SCHEMA + "dh_modeling_" + kind + "_model_version "
                    + "(id,model_id,version,fields,model_snapshot) VALUES (" + (id + 1000) + ","
                    + id + ",7,'[{\"id\":9007199254740993,\"name\":\"保留字段\"}]',"
                    + "'{\"themeId\":600,\"layerId\":700,\"frozen\":true}')");
            id++;
        }
    }

    private Map<String, String> modelSnapshots() throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        for (String kind : new String[]{"concept", "logic", "physical"}) {
            for (String suffix : new String[]{"_model", "_model_version"}) {
                String table = "dh_modeling_" + kind + suffix;
                result.put(table, scalar("SELECT jsonb_agg(to_jsonb(m) - ARRAY['data_domain_id','revision','source_logic_version_id','published_by','published_time'] ORDER BY id)::text FROM " + SCHEMA + table + " m"));
            }
        }
        return result;
    }

    private String tableSnapshot(String table) throws SQLException {
        return scalar("SELECT jsonb_agg(to_jsonb(m) ORDER BY id)::text FROM " + SCHEMA + table + " m");
    }

    private void precheckPreservesRows() throws Exception {
        String categories = tableSnapshot("dh_modeling_biz_category");
        String processes = tableSnapshot("dh_modeling_business_process");
        Map<String, String> models = modelSnapshots();
        executeFile(PRECHECK);
        assertThat(tableSnapshot("dh_modeling_biz_category")).isEqualTo(categories);
        assertThat(tableSnapshot("dh_modeling_business_process")).isEqualTo(processes);
        assertThat(modelSnapshots()).isEqualTo(models);
    }

    private String primary(long processId) throws SQLException {
        return scalar("SELECT data_domain_id FROM " + SCHEMA
                + "dh_modeling_business_process WHERE id=" + processId);
    }

    private long count(String table, String condition) throws SQLException {
        return Long.parseLong(scalar("SELECT count(*) FROM " + SCHEMA + table + " WHERE " + condition));
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema='datahub-center' AND table_name=? AND column_name=?)
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private String scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private void migrate() throws Exception {
        executeFile(MIGRATION);
    }

    private void executeFile(String relativePath) throws Exception {
        execute(Files.readString(repoFile(relativePath)));
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            statement.execute(sql);
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private static Path repoFile(String relativePath) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("未找到仓库迁移文件：" + relativePath);
    }
}
