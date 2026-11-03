package cn.iocoder.yudao.module.modeling.service.conceptmodel;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.module.modeling.dal.dataobject.conceptmodel.ConceptModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.conceptmodel.ConceptModelMapper;
import cn.iocoder.yudao.module.modeling.framework.mybatis.core.type.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 在独立 schema 中验证生产 Mapper、JSONB 类型处理器和修订条件。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConceptModelMapperPostgreSqlTest {
    private PostgreSQLContainer<?> postgres;
    private Connection connection;
    private SqlSessionFactory sessions;
    private final String schema = "concept_test_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeAll
    void initialize() throws Exception {
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
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/migrations/0171_modeling_concept-indicator-models.sql"))) root = root.getParent();
        assertNotNull(root, "缺少概念模型迁移");
        String migration = Files.readString(root.resolve("sql/postgresql/migrations/0171_modeling_concept-indicator-models.sql"));
        // 直接使用本次迁移的概念表结构，避免测试夹具与真实 JSONB/唯一约束分叉。
        execute(migration.substring(migration.indexOf("CREATE SEQUENCE IF NOT EXISTS dh_modeling_concept_model_seq"),
                migration.indexOf("CREATE SEQUENCE IF NOT EXISTS dh_modeling_indicator_model_seq")));
        var properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("currentSchema", schema);
        var datasource = new UnpooledDataSource("org.postgresql.Driver", url, properties);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("concept-model-test", new JdbcTransactionFactory(), datasource));
        var global = GlobalConfigUtils.getGlobalConfig(configuration);
        global.setMetaObjectHandler(new DefaultDBFieldHandler());
        global.getDbConfig().setIdType(IdType.INPUT).setLogicDeleteValue("1").setLogicNotDeleteValue("0")
                .setKeyGenerators(List.of(new PostgreKeyGenerator()));
        configuration.addMapper(ConceptModelMapper.class);
        sessions = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void realMapperRoundTripsJsonbAndRejectsStaleRevisionAndDeletedRows() {
        try (var session = sessions.openSession(true)) {
            var mapper = session.getMapper(ConceptModelMapper.class);
            var row = new ConceptModelDO();
            row.setName("订单概念");
            row.setCode("order_concept");
            row.setDataDomainId(1L);
            row.setStatus("DRAFT");
            row.setRevision(0L);
            row.setDesign("{\"entities\":[{\"key\":\"order\",\"name\":\"订单\",\"code\":\"order\",\"attributes\":[]}],\"relations\":[]}");
            assertEquals(1, mapper.insert(row));
            assertNotNull(row.getId());
            assertEquals(JsonUtils.parseTree(row.getDesign()), JsonUtils.parseTree(mapper.selectById(row.getId()).getDesign()));
            String next = "{\"entities\":[],\"relations\":[]}";
            assertEquals(1, mapper.update(null, Wrappers.lambdaUpdate(ConceptModelDO.class)
                    .set(ConceptModelDO::getDesign, next, JsonbStringTypeHandler.MAPPING)
                    .set(ConceptModelDO::getRevision, 1L)
                    .eq(ConceptModelDO::getId, row.getId()).eq(ConceptModelDO::getRevision, 0L)
                    .eq(ConceptModelDO::getStatus, "DRAFT")));
            assertEquals(JsonUtils.parseTree(next), JsonUtils.parseTree(mapper.selectById(row.getId()).getDesign()));
            assertEquals(0, mapper.update(null, Wrappers.lambdaUpdate(ConceptModelDO.class)
                    .set(ConceptModelDO::getName, "过期请求")
                    .eq(ConceptModelDO::getId, row.getId()).eq(ConceptModelDO::getRevision, 0L)));
            assertEquals(0, mapper.deleteDraft(row.getId(), 0L, "test"));
            assertEquals(1, mapper.deleteDraft(row.getId(), 1L, "test"));
            assertNull(mapper.selectById(row.getId()));
            assertEquals(0, mapper.deleteDraft(row.getId(), 1L, "test"));
        }
    }

    @Test
    void activeRowCannotBeDeletedByDraftMapper() {
        try (var session = sessions.openSession(true)) {
            var mapper = session.getMapper(ConceptModelMapper.class);
            var row = new ConceptModelDO();
            row.setName("客户概念");
            row.setCode("customer_concept");
            row.setDataDomainId(1L);
            row.setStatus("ACTIVE");
            row.setRevision(0L);
            row.setDesign("{\"entities\":[],\"relations\":[]}");
            mapper.insert(row);
            assertEquals(0, mapper.deleteDraft(row.getId(), 0L, "test"));
            assertNotNull(mapper.selectById(row.getId()));
        }
    }

    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    @AfterAll
    void close() throws Exception {
        try {
            if (connection != null) {
                try { execute("DROP SCHEMA " + schema + " CASCADE"); }
                finally { connection.close(); }
            }
        } finally {
            if (postgres != null) postgres.stop();
        }
    }
}
