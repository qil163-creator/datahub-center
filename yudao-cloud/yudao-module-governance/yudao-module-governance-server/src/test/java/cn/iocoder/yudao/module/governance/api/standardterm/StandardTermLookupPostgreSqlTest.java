package cn.iocoder.yudao.module.governance.api.standardterm;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardterm.StandardTermDO;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.mysql.categoryterm.CategoryTermMapper;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import cn.iocoder.yudao.module.governance.dal.mysql.standardterm.StandardTermMapper;
import cn.iocoder.yudao.module.governance.service.standardterm.StandardTermServiceImpl;
import cn.iocoder.yudao.module.governance.service.categoryterm.CategoryTermServiceImpl;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实 PostgreSQL Mapper 验证名称/别名分组查询和逻辑删除。 */
@Timeout(30)
class StandardTermLookupPostgreSqlTest {

    private static PostgreSQLContainer<?> postgres;
    private static String jdbcUrl;
    private static String jdbcUser;
    private static String jdbcPassword;

    private JdbcTemplate jdbc;
    private StandardTermServiceImpl service;
    private CategoryTermServiceImpl categoryService;
    private TransactionTemplate transaction;
    private String schema;

    @BeforeAll
    static void startPostgres() {
        jdbcUrl = System.getenv("STANDARD_TERM_TEST_JDBC_URL");
        if (jdbcUrl != null) {
            jdbcUser = System.getenv("STANDARD_TERM_TEST_JDBC_USER");
            jdbcPassword = System.getenv().getOrDefault("STANDARD_TERM_TEST_JDBC_PASSWORD", "");
            if (!jdbcUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/(codex_term_migration|standard_term_[a-z0-9_]*_test)")
                    || !Set.of("codex_term", "standard_term_test").contains(jdbcUser == null ? "" : jdbcUser)) {
                throw new IllegalStateException("Explicit local isolated standard-term test database and test role required");
            }
            return;
        }
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Requires Docker or explicit STANDARD_TERM_TEST_JDBC_URL / STANDARD_TERM_TEST_JDBC_USER");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        jdbcUrl = postgres.getJdbcUrl();
        jdbcUser = postgres.getUsername();
        jdbcPassword = postgres.getPassword();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void setup() throws Exception {
        var control = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, jdbcUser, jdbcPassword));
        assertEquals(jdbcUser, control.queryForObject("SELECT current_user", String.class));
        schema = "standard_term_test_" + UUID.randomUUID().toString().replace("-", "");
        control.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?")
                + "currentSchema=" + schema, jdbcUser, jdbcPassword);
        jdbc = new JdbcTemplate(source);
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) {
            root = root.getParent();
        }
        assertNotNull(root);
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        var table = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?\"datahub-center\"[.]\"dh_governance_standard_term\" \\(.*?\n\\)\\s*;",
                Pattern.DOTALL).matcher(baseline);
        assertTrue(table.find(), "term table must exist in the delivered baseline");
        jdbc.execute(table.group().replace("\"datahub-center\".", "")
                .replaceAll(" DEFAULT nextval\\([^\n]*?\\)", ""));

        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(jdbc, schema);
        jdbc.execute("ALTER TABLE dh_governance_standard_term DROP COLUMN term_category_id");
        jdbc.execute("CREATE TABLE dh_governance_category_term (id bigint PRIMARY KEY, parent_id bigint NOT NULL, "
                + "category_name varchar(128) NOT NULL, category_code varchar(128), sort integer NOT NULL DEFAULT 0, "
                + "status smallint NOT NULL DEFAULT 0, remark text, creator varchar(64) DEFAULT '', "
                + "create_time timestamp DEFAULT CURRENT_TIMESTAMP, updater varchar(64) DEFAULT '', "
                + "update_time timestamp DEFAULT CURRENT_TIMESTAMP, deleted smallint NOT NULL DEFAULT 0)");
        jdbc.update("INSERT INTO dh_governance_standard_term(id,name,definition,category_id,status,deleted) VALUES (?,?,?,?,?,?)", 1000, "历史术语", "旧定义", 333, 0, 0);
        String migration = Files.readString(root.resolve("sql/postgresql/migrations/0242_governance_term-category-link.sql"))
                .replace("\"datahub-center\"", "\"" + schema + "\"");
        jdbc.execute(migration);
        jdbc.execute(migration);
        assertEquals(333L, jdbc.queryForObject("SELECT category_id FROM dh_governance_standard_term WHERE id=1000", Long.class));
        assertNull(jdbc.queryForObject("SELECT term_category_id FROM dh_governance_standard_term WHERE id=1000", Long.class));
        jdbc.update("DELETE FROM dh_governance_standard_term WHERE id=1000");
        var config = new MybatisConfiguration();
        com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config)
                .setMetaObjectHandler(new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setEnvironment(new Environment("term-lookup-test", new SpringManagedTransactionFactory(), source));
        var plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        config.addInterceptor(plugins);
        config.addMapper(StandardTermMapper.class);
        config.addMapper(CategoryTermMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        service = new StandardTermServiceImpl();
        ReflectionTestUtils.setField(service, "standardTermMapper", session.getMapper(StandardTermMapper.class));
        ReflectionTestUtils.setField(service, "categoryTermMapper", session.getMapper(CategoryTermMapper.class));
        categoryService = new CategoryTermServiceImpl();
        ReflectionTestUtils.setField(categoryService, "categoryTermMapper", session.getMapper(CategoryTermMapper.class));
        ReflectionTestUtils.setField(categoryService, "standardTermMapper", session.getMapper(StandardTermMapper.class));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        TenantContextHolder.clear();
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        if (schema != null && schema.matches("standard_term_test_[0-9a-f]{32}")) {
            new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, jdbcUser, jdbcPassword))
                    .execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void selectionMatchesNameOrAliasAndExcludesDisabledAndDeletedTerms() {
        insert(1L, "客户编号", "顾客代码", 0, 0);
        insert(2L, "账户编号", "客户标识,cust_id", 0, 0);
        insert(3L, "旧编号", "客户代码", 1, 0);
        insert(4L, "已删除", "客户代码", 0, 1);
        insert(5L, "平台术语", "客户代码", 0, 0);
        assertEquals(Set.of(1L, 2L, 5L), ids(service.getEnabledStandardTermList(" 客户 ")));
        assertEquals(Set.of(2L), ids(service.getEnabledStandardTermList("cust_id")));
        TenantContextHolder.clear();
        assertEquals(Set.of(1L, 2L, 5L), ids(service.getEnabledStandardTermList("客户")));
    }

    @Test
    void blankSelectionIsBoundedAndBulkReadRetainsDisabledWithExactIds() {
        for (long id = 10; id < 70; id++) {
            insert(id, "术语" + id, null, 0, 0);
        }
        insert(9007199254740993L, "停用术语", null, 1, 0);
        insert(9007199254740994L, "已删除术语", null, 0, 1);
        insert(9007199254740995L, "平台术语", null, 0, 0);
        assertEquals(50, service.getEnabledStandardTermList("   ").size());
        assertEquals(Set.of(10L, 9007199254740993L, 9007199254740995L), ids(service.getStandardTermList(
                List.of(10L, 9007199254740993L, 9007199254740994L, 9007199254740995L, 999L))));
        assertTrue(service.getStandardTermList(List.of()).isEmpty());
    }

    @Test
    void migrationPreservesOldCategoryAndFiltersByTermTree() {
        jdbc.update("INSERT INTO dh_governance_category_term(id,parent_id,category_name,status) VALUES (?,?,?,?)", 1, 0, "根", 0);
        jdbc.update("INSERT INTO dh_governance_category_term(id,parent_id,category_name,status) VALUES (?,?,?,?)", 2, 1, "子", 1);
        jdbc.update("INSERT INTO dh_governance_category_term(id,parent_id,category_name,status) VALUES (?,?,?,?)", 3, 0, "其他", 0);
        insert(1, "父类术语", null, 0, 0);
        insert(2, "子类术语", null, 0, 0);
        insert(3, "其他术语", null, 0, 0);
        insert(4, "历史未分配术语", null, 0, 0);
        jdbc.update("UPDATE dh_governance_standard_term SET term_category_id = ?, category_id = ? WHERE id = ?", 1, 333, 1);
        jdbc.update("UPDATE dh_governance_standard_term SET term_category_id = ? WHERE id = ?", 2, 2);
        jdbc.update("UPDATE dh_governance_standard_term SET term_category_id = ? WHERE id = ?", 3, 3);
        jdbc.update("UPDATE dh_governance_standard_term SET category_id = ? WHERE id = ?", 333, 4);
        var query = new StandardTermPageReqVO();
        query.setTermCategoryId(1L);
        assertEquals(Set.of(1L, 2L), ids(service.getStandardTermPage(query).getList()));
        query.setTermCategoryId(2L);
        assertEquals(Set.of(2L), ids(service.getStandardTermPage(query).getList()));
        query.setTermCategoryId(null);
        assertEquals(Set.of(1L, 2L, 3L, 4L), ids(service.getStandardTermPage(query).getList()));
        assertEquals(333L, jdbc.queryForObject("SELECT category_id FROM dh_governance_standard_term WHERE id=4", Long.class));
        assertNull(jdbc.queryForObject("SELECT term_category_id FROM dh_governance_standard_term WHERE id=4", Long.class));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE dh_governance_standard_term SET term_category_id = 999 WHERE id=4"));
        var update = new StandardTermSaveReqVO();
        update.setId(2L);
        update.setName("子类术语");
        update.setDefinition("测试定义");
        update.setStatus((short) 0);
        service.updateStandardTerm(update);
        assertEquals(2L, jdbc.queryForObject("SELECT term_category_id FROM dh_governance_standard_term WHERE id=2", Long.class));
        update.setTermCategoryId(null);
        service.updateStandardTerm(update);
        assertNull(jdbc.queryForObject("SELECT term_category_id FROM dh_governance_standard_term WHERE id=2", Long.class));
    }

    @Test
    void assignmentAndCategoryDeletionAreSerialized() throws Exception {
        jdbc.update("INSERT INTO dh_governance_category_term(id,parent_id,category_name,status) VALUES (?,?,?,?)", 2, 0, "类目", 0);
        insert(2, "并发术语", null, 0, 0);
        var update = new StandardTermSaveReqVO();
        update.setId(2L);
        update.setName("并发术语");
        update.setDefinition("测试定义");
        update.setStatus((short) 0);
        update.setTermCategoryId(2L);
        var assigned = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var assignment = executor.submit(() -> transaction.executeWithoutResult(status -> {
                service.updateStandardTerm(update);
                assigned.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("等待超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(assigned.await(5, TimeUnit.SECONDS));
            var deletion = executor.submit(() -> transaction.executeWithoutResult(status -> categoryService.deleteCategoryTerm(2L)));
            try {
                assertThrows(TimeoutException.class, () -> deletion.get(300, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assignment.get(5, TimeUnit.SECONDS);
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> deletion.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ServiceException.class, failure.getCause());
        }
        assertEquals(2L, jdbc.queryForObject("SELECT term_category_id FROM dh_governance_standard_term WHERE id=2", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT deleted FROM dh_governance_category_term WHERE id=2", Integer.class));
    }

    @Test
    void concurrentOmittedCategoryUpdateDoesNotOverwriteAssignment() throws Exception {
        jdbc.update("INSERT INTO dh_governance_category_term(id,parent_id,category_name,status) VALUES (?,?,?,?)", 2, 0, "类目", 0);
        insert(2, "并发术语", null, 0, 0);
        var assignment = new StandardTermSaveReqVO();
        assignment.setId(2L);
        assignment.setName("并发术语");
        assignment.setDefinition("测试定义");
        assignment.setStatus((short) 0);
        assignment.setTermCategoryId(2L);
        var legacy = new StandardTermSaveReqVO();
        legacy.setId(2L);
        legacy.setName("并发术语");
        legacy.setDefinition("旧客户端更新");
        legacy.setStatus((short) 0);
        var assigned = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                service.updateStandardTerm(assignment);
                assigned.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("等待超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(assigned.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> transaction.executeWithoutResult(status -> service.updateStandardTerm(legacy)));
            try {
                assertThrows(TimeoutException.class, () -> second.get(300, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        assertEquals(2L, jdbc.queryForObject("SELECT term_category_id FROM dh_governance_standard_term WHERE id=2", Long.class));
        assertEquals("旧客户端更新", jdbc.queryForObject("SELECT definition FROM dh_governance_standard_term WHERE id=2", String.class));
    }

    private void insert(long id, String name, String alias, int status, int deleted) {
        jdbc.update("INSERT INTO dh_governance_standard_term (id,name,alias,definition,status,deleted) VALUES (?,?,?,?,?,?)",
                id, name, alias, "测试定义", status, deleted);
    }

    private static Set<Long> ids(List<StandardTermDO> terms) {
        return terms.stream().map(StandardTermDO::getId).collect(Collectors.toSet());
    }

}
