package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelOverviewMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真正执行概览 Mapper；支持临时 PostgreSQL 集群，未配置时使用 Testcontainers。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelOverviewMapperPostgreSqlTest {

    private PostgreSQLContainer<?> postgres;
    private Connection connection;
    private SqlSessionFactory sessions;
    private final String schema = "overview_test_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeAll
    void connect() throws Exception {
        String url = System.getenv("MODEL_OVERVIEW_TEST_JDBC_URL");
        String user = System.getenv().getOrDefault("MODEL_OVERVIEW_TEST_DB_USER", "overview_test");
        String password = System.getenv().getOrDefault("MODEL_OVERVIEW_TEST_DB_PASSWORD", "");
        if (url == null || url.isBlank()) {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "未配置隔离 PostgreSQL 且 Docker 不可用");
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
            url = postgres.getJdbcUrl();
            user = postgres.getUsername();
            password = postgres.getPassword();
        }
        connection = DriverManager.getConnection(url, user, password);
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema);
        var properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("currentSchema", schema);
        var datasource = new UnpooledDataSource("org.postgresql.Driver", url, properties);
        var configuration = new Configuration(new Environment("overview-test", new JdbcTransactionFactory(), datasource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ModelOverviewMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
        execute("""
                CREATE TABLE dh_modeling_dw_layer (
                    id bigint PRIMARY KEY, layer_name varchar(100), layer_code varchar(50),
                    node_type varchar(20), sort_order integer, deleted smallint NOT NULL DEFAULT 0
                );
                CREATE TABLE dh_modeling_logic_model (
                    id bigint PRIMARY KEY, name varchar(100), layer_id bigint, status varchar(20),
                    create_time timestamp, update_time timestamp, published_time timestamp, deleted smallint NOT NULL DEFAULT 0
                );
                CREATE TABLE dh_modeling_concept_model (id bigint PRIMARY KEY, create_time timestamp, deleted smallint DEFAULT 0);
                CREATE TABLE dh_modeling_indicator_model (id bigint PRIMARY KEY, create_time timestamp, deleted smallint DEFAULT 0);
                CREATE TABLE dh_modeling_model_materialized (
                    id bigint PRIMARY KEY, model_id bigint, status varchar(20),
                    deleted smallint NOT NULL DEFAULT 0
                )
                """);
    }

    @BeforeEach
    void seed() throws Exception {
        execute("""
                TRUNCATE dh_modeling_model_materialized,
                    dh_modeling_logic_model, dh_modeling_dw_layer, dh_modeling_concept_model, dh_modeling_indicator_model;
                INSERT INTO dh_modeling_dw_layer VALUES
                    (1, '明细层', 'DWD', 'LAYER', 1, 0),
                    (2, '自定义层', 'CUSTOM', 'LAYER', 2, 0),
                    (3, '维度层', 'DIM', 'LAYER', 3, 0),
                    (4, '分类节点', NULL, 'CATEGORY', 4, 0),
                    (5, '删除层', 'DELETED', 'LAYER', 5, 1);
                INSERT INTO dh_modeling_logic_model VALUES
                    (11, '客户', 1, 'PUBLISHED', '2026-10-01', '2026-10-14', '2026-10-08', 0),
                    (12, '自定义模型', 2, 'DRAFT', '2026-10-10', '2026-10-10', NULL, 0),
                    (13, '待分层', NULL, 'DRAFT', '2026-10-11', '2026-10-11', NULL, 0),
                    (14, '删除模型不可见', 1, 'PUBLISHED', '2026-10-12', '2026-10-15', '2026-10-14', 1),
                    (15, '废弃模型不可见', 1, 'DISCARDED', '2026-10-12', '2026-10-15', '2026-10-14', 0),
                    (16, '旧分类引用', 4, 'PUBLISHED', '2026-10-12', '2026-10-12', '2026-10-15', 0),
                    (17, '已删除分层引用', 5, 'DRAFT', '2026-10-13', '2026-10-13', '2026-10-14', 0);
                INSERT INTO dh_modeling_model_materialized VALUES
                    (201, 11, 'SUCCESS', 0),
                    (202, 12, 'SUCCESS', 0),
                    (203, 13, 'SUCCESS', 0),
                    (204, 14, 'SUCCESS', 0),
                    (205, 15, 'SUCCESS', 0),
                    (206, 16, 'SUCCESS', 0),
                    (207, 17, 'SUCCESS', 0),
                    (208, 11, 'FAIL', 0),
                    (209, 13, 'SUCCESS', 0),
                    (210, 13, 'SUCCESS', 1)
                """);
    }

    @Test
    void newModelCountsExcludeDeletedRowsAndRespectInclusiveRecentWindow() throws Exception {
        execute("""
                INSERT INTO dh_modeling_concept_model VALUES (1,'2026-10-01',0),(2,'2026-10-08',0),
                (3,'2026-10-15',0),(4,'2026-10-16',0),(5,'2026-10-14',1);
                INSERT INTO dh_modeling_indicator_model VALUES (1,'2026-10-09',0),(2,'2026-10-13',1);
                """);
        try (SqlSession session = sessions.openSession()) {
            var mapper = session.getMapper(ModelOverviewMapper.class);
            var from = LocalDateTime.of(2026,10,8,0,0);
            var until = from.plusDays(7);
            var concept = mapper.selectConceptCounts(from, until);
            assertEquals(4L, concept.getTotal());
            assertEquals(2L, concept.getRecent());
            var indicator = mapper.selectIndicatorCounts(from, until);
            assertEquals(1L, indicator.getTotal());
            assertEquals(1L, indicator.getRecent());
        }
    }

    @Test
    void distributionIncludesCustomZeroAndUnassignedLayersButNotDeletedOrDiscardedModels() {
        try (SqlSession session = sessions.openSession()) {
            var rows = session.getMapper(ModelOverviewMapper.class).selectLayerDistribution();
            assertEquals(4, rows.size());
            assertEquals("DWD", rows.getFirst().getCode());
            assertEquals("unassigned", rows.getLast().getKey());
            Map<String, Long> counts = rows.stream().collect(Collectors.toMap(ModelOverviewMapper.LayerCount::getKey,
                    ModelOverviewMapper.LayerCount::getCount));
            assertEquals(Map.of("1", 1L, "2", 1L, "3", 0L, "unassigned", 3L), counts);
        }
    }

    @Test
    void physicalDistributionCountsSuccessfulTargetsWithoutAnyModelVersionJoin() {
        try (SqlSession session = sessions.openSession()) {
            var rows = session.getMapper(ModelOverviewMapper.class).selectMaterializedDistribution();
            assertEquals(6, rows.stream().mapToLong(ModelOverviewMapper.LayerCount::getCount).sum());
            assertEquals(1L, rows.stream().filter(row -> "CUSTOM".equals(row.getCode())).findFirst().orElseThrow().getCount());
            assertEquals(4L, rows.stream().filter(row -> "unassigned".equals(row.getKey())).findFirst().orElseThrow().getCount());
        }
    }

    @Test
    void recentPublicationUsesCurrentPublishedFactsAndInclusiveTimeBounds() {
        try (SqlSession session = sessions.openSession()) {
            Long count = session.getMapper(ModelOverviewMapper.class).selectRecentlyPublishedCount(
                    LocalDateTime.of(2026, 10, 8, 0, 0), LocalDateTime.of(2026, 10, 15, 0, 0));
            assertEquals(2L, count);
        }
    }

    @Test
    void recentEventsUseCreationAndRecordedPublicationTimesInsteadOfUnreliableUpdateTimes() throws Exception {
        execute("INSERT INTO dh_modeling_logic_model VALUES (18,'旧创建一',NULL,'DRAFT','2026-10-02','2026-10-15',NULL,0),"
                + "(19,'旧创建二',NULL,'DRAFT','2026-10-03','2026-10-15',NULL,0)");
        try (SqlSession session = sessions.openSession()) {
            var rows = session.getMapper(ModelOverviewMapper.class).selectRecentActivities();
            assertEquals(8, rows.size());
            assertTrue(rows.stream().noneMatch(row -> row.getModelName().contains("不可见")));
            assertTrue(rows.stream().noneMatch(row -> "publish-13".equals(row.getId())));
            assertTrue(rows.stream().noneMatch(row -> "publish-17".equals(row.getId())));
            assertTrue(rows.stream().noneMatch(row -> "UPDATE".equals(row.getType())));
            // 旧模型的更新时间不能挤掉真实创建事件，也不据此推断任何修改动作。
            assertTrue(rows.stream().noneMatch(row -> "model-11".equals(row.getId())));
            var created = rows.stream().filter(row -> "model-17".equals(row.getId())).findFirst().orElseThrow();
            assertEquals("CREATE", created.getType());
            assertEquals(LocalDateTime.of(2026, 10, 13, 0, 0), created.getOccurredAt());
            for (int index = 1; index < rows.size(); index++) {
                assertFalse(rows.get(index).getOccurredAt().isAfter(rows.get(index - 1).getOccurredAt()));
            }
        }
    }

    private void execute(String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @AfterAll
    void close() throws Exception {
        try {
            if (connection != null) {
                try {
                    execute("DROP SCHEMA " + schema + " CASCADE");
                } finally {
                    connection.close();
                }
            }
        } finally {
            if (postgres != null) {
                postgres.stop();
            }
        }
    }
}
