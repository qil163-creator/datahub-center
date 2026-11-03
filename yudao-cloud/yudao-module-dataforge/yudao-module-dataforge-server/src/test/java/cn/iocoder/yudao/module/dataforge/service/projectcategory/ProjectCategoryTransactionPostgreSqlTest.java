package cn.iocoder.yudao.module.dataforge.service.projectcategory;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.projectcategory.vo.ProjectCategoryStatusReqVO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevDirMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationCategoryMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.projectcategory.ProjectCategoryMapper;
import cn.iocoder.yudao.module.dataforge.service.dev.DevDirService;
import cn.iocoder.yudao.module.dataforge.service.dev.DevDirServiceImpl;
import cn.iocoder.yudao.module.dataforge.service.integration.IntegrationTaskService;
import cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_DIR_HAS_TASK;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_DIR_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 独立 PostgreSQL 验证真实代理事务、类目批删回滚和任务引用的锁顺序。 */
class ProjectCategoryTransactionPostgreSqlTest {
    private static final DataforgePostgreSqlContainer POSTGRES = new DataforgePostgreSqlContainer();
    private String schema;
    private DriverManagerDataSource source;
    private JdbcTemplate sql;
    private DataSourceTransactionManager manager;
    private DevDirService directories;
    private ProjectCategoryService service;
    private AdminUserApi users;

    @BeforeAll static void start() { POSTGRES.start(); }
    @AfterAll static void stop() { POSTGRES.stop(); }

    @BeforeEach void setup() throws Exception {
        schema = "project_category_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        source = new DriverManagerDataSource(POSTGRES.getJdbcUrl() + "?currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        sql = new JdbcTemplate(source);
        sql.execute("CREATE TABLE dh_dataforge_dev_dir (id bigint PRIMARY KEY,parent_id bigint DEFAULT 0,name varchar(100),sort integer DEFAULT 0,status integer DEFAULT 0,description varchar(500),remark varchar(500),source_creator_name varchar(100),creator varchar(64),updater varchar(64),create_time timestamp DEFAULT now(),update_time timestamp DEFAULT now(),deleted smallint DEFAULT 0)");
        sql.execute("CREATE TABLE dh_dataforge_integration_category (id bigint PRIMARY KEY,parent_id bigint DEFAULT 0,name varchar(100),sort integer DEFAULT 0,valid_flag boolean DEFAULT true,description varchar(256),source_creator_name varchar(100),creator varchar(64),updater varchar(64),create_time timestamp DEFAULT now(),update_time timestamp DEFAULT now(),deleted smallint DEFAULT 0)");
        sql.execute("CREATE TABLE dh_dataforge_dev_task (id bigint PRIMARY KEY,dir_id bigint,deleted smallint DEFAULT 0)");
        sql.execute("CREATE TABLE dh_dataforge_integration_task (id bigint PRIMARY KEY,category_id bigint,deleted smallint DEFAULT 0)");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("project-category", new SpringManagedTransactionFactory(), source));
        configuration.addMapper(DevDirMapper.class);
        configuration.addMapper(DevTaskMapper.class);
        configuration.addMapper(IntegrationCategoryMapper.class);
        configuration.addMapper(ProjectCategoryMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var dirMapper = session.getMapper(DevDirMapper.class);
        manager = new DataSourceTransactionManager(source);
        var implementation = new DevDirServiceImpl();
        ReflectionTestUtils.setField(implementation, "dirMapper", dirMapper);
        ReflectionTestUtils.setField(implementation, "taskMapper", session.getMapper(DevTaskMapper.class));
        directories = proxy(implementation);
        users = mock(AdminUserApi.class);
        var security = mock(SecurityFrameworkService.class);
        when(security.hasPermission(anyString())).thenReturn(true);
        service = proxy(new ProjectCategoryService(directories, dirMapper, mock(IntegrationTaskService.class),
                session.getMapper(IntegrationCategoryMapper.class), session.getMapper(ProjectCategoryMapper.class), users, security));
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
    }

    @AfterEach void cleanup() throws Exception {
        SecurityContextHolder.clearContext();
        try (var connection = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @Test void sourceCreatorProjectionUsesBothRealTablesAndPreservesLargeIdentifiers() {
        sql.update("INSERT INTO dh_dataforge_dev_dir(id,name,source_creator_name,creator) VALUES(9007199254740993,'开发类目','源开发人','migration:0216')");
        sql.update("INSERT INTO dh_dataforge_integration_category(id,name,source_creator_name,creator) VALUES(9007199254740995,'集成类目','源集成人','migration:0216')");
        sql.update("INSERT INTO dh_dataforge_dev_dir(id,parent_id,name) VALUES(42,9007199254740993,'开发子类目')");
        sql.update("INSERT INTO dh_dataforge_integration_category(id,parent_id,name) VALUES(43,9007199254740995,'集成子类目')");
        sql.update("INSERT INTO dh_dataforge_dev_task(id,dir_id,deleted) VALUES(1,9007199254740993,0),(2,9007199254740993,0),(3,9007199254740993,1)");
        sql.update("INSERT INTO dh_dataforge_integration_task(id,category_id,deleted) VALUES(1,9007199254740995,0),(2,9007199254740995,1)");
        var development = service.get("development", 9007199254740993L);
        var integration = service.get("integration", 9007199254740995L);
        assertEquals(9007199254740993L, development.getId());
        assertEquals("源开发人", development.getCreatorName());
        assertEquals("源集成人", integration.getCreatorName());
        assertEquals(0, integration.getStatus());
        assertEquals(2L, development.getTaskCount());
        assertEquals(1L, integration.getTaskCount());
        assertEquals(1L, development.getChildCount());
        assertEquals(1L, integration.getChildCount());
        assertNotNull(development.getCreateTime());
        verifyNoInteractions(users);
    }

    @Test void aReferencedLeafRollsBackAnEarlierDeletionInTheSameBatch() {
        sql.update("INSERT INTO dh_dataforge_dev_dir(id,parent_id,name,status) VALUES(1,0,'父',1),(2,1,'先删子',1),(3,1,'有引用子',1)");
        sql.update("INSERT INTO dh_dataforge_dev_task(id,dir_id) VALUES(11,3)");
        var failure = assertThrows(ServiceException.class, () -> service.deleteList("development", List.of(1L, 2L, 3L)));
        assertEquals(DEV_DIR_HAS_TASK.getCode(), failure.getCode());
        assertEquals(3, sql.queryForObject("SELECT count(*) FROM dh_dataforge_dev_dir WHERE deleted=0", Integer.class));
    }

    @Test void fullySelectedParentAndChildrenAreDeletedTogether() {
        sql.update("INSERT INTO dh_dataforge_dev_dir(id,parent_id,name,status) VALUES(1,0,'父',1),(2,1,'子',1),(9,0,'保留',0)");
        service.deleteList("development", List.of(1L, 2L, 2L));
        assertEquals(2, sql.queryForObject("SELECT count(*) FROM dh_dataforge_dev_dir WHERE deleted=1", Integer.class));
        assertEquals(0, sql.queryForObject("SELECT deleted FROM dh_dataforge_dev_dir WHERE id=9", Integer.class));
    }

    @Test void statusWritesPreserveNamesDescriptionsAndCreatorSnapshots() {
        sql.update("INSERT INTO dh_dataforge_dev_dir(id,name,description,source_creator_name) VALUES(1,'开发名称','开发描述','开发来源')");
        sql.update("INSERT INTO dh_dataforge_integration_category(id,name,description,source_creator_name) VALUES(2,'集成名称','集成描述','集成来源')");
        var request = new ProjectCategoryStatusReqVO();
        request.setKind("development"); request.setId(1L); request.setStatus(1);
        service.updateStatus(request);
        request.setKind("integration"); request.setId(2L);
        service.updateStatus(request);
        assertEquals("开发名称/开发描述/开发来源/1", sql.queryForObject(
                "SELECT name||'/'||description||'/'||source_creator_name||'/'||status FROM dh_dataforge_dev_dir WHERE id=1", String.class));
        assertEquals("集成名称/集成描述/集成来源/false", sql.queryForObject(
                "SELECT name||'/'||description||'/'||source_creator_name||'/'||valid_flag FROM dh_dataforge_integration_category WHERE id=2", String.class));
    }

    @Test void enabledCategoriesCannotBeDeletedIndividuallyOrInsideAMixedBatch() {
        sql.update("INSERT INTO dh_dataforge_dev_dir(id,name,status) VALUES(1,'已启用',0),(2,'已停用',1)");
        sql.update("INSERT INTO dh_dataforge_integration_category(id,name,valid_flag) VALUES(3,'已启用',true)");
        assertThrows(ServiceException.class, () -> service.delete("development", 1L));
        assertThrows(ServiceException.class, () -> service.delete("integration", 3L));
        assertThrows(ServiceException.class, () -> service.deleteList("development", List.of(2L, 1L)));
        assertEquals(2, sql.queryForObject("SELECT count(*) FROM dh_dataforge_dev_dir WHERE deleted=0", Integer.class));
        assertEquals(0, sql.queryForObject("SELECT deleted FROM dh_dataforge_integration_category WHERE id=3", Integer.class));
    }

    @Test void theTaskDirectoryGuardRequiresAnExistingTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> directories.lockAndValidateDirExists(0L));
        new TransactionTemplate(manager).executeWithoutResult(status -> directories.lockAndValidateDirExists(0L));
    }

    @Test void deletionCommittingFirstPreventsAWaitingTaskFromCreatingAnOrphan() throws Exception {
        sql.update("INSERT INTO dh_dataforge_dev_dir(id,name) VALUES(1,'目录')");
        var deleted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var deletion = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                directories.deleteDir(1L); deleted.countDown(); await(release);
            }));
            assertTrue(deleted.await(5, TimeUnit.SECONDS));
            var creation = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                directories.lockAndValidateDirExists(1L);
                sql.update("INSERT INTO dh_dataforge_dev_task(id,dir_id) VALUES(1,1)");
            }));
            try { awaitAdvisoryWaiter(); } finally { release.countDown(); }
            deletion.get(5, TimeUnit.SECONDS);
            var failure = assertThrows(ExecutionException.class, () -> creation.get(5, TimeUnit.SECONDS));
            assertEquals(DEV_DIR_NOT_EXISTS.getCode(), assertInstanceOf(ServiceException.class, failure.getCause()).getCode());
            assertEquals(0, sql.queryForObject("SELECT count(*) FROM dh_dataforge_dev_task", Integer.class));
        } finally { release.countDown(); }
    }

    @Test void taskCommittingFirstMakesAWaitingDeletionRejectItsReference() throws Exception {
        sql.update("INSERT INTO dh_dataforge_dev_dir(id,name) VALUES(1,'目录')");
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var creation = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                directories.lockAndValidateDirExists(1L);
                sql.update("INSERT INTO dh_dataforge_dev_task(id,dir_id) VALUES(1,1)");
                inserted.countDown(); await(release);
            }));
            assertTrue(inserted.await(5, TimeUnit.SECONDS));
            var deletion = executor.submit(() -> directories.deleteDir(1L));
            try { awaitAdvisoryWaiter(); } finally { release.countDown(); }
            creation.get(5, TimeUnit.SECONDS);
            var failure = assertThrows(ExecutionException.class, () -> deletion.get(5, TimeUnit.SECONDS));
            assertEquals(DEV_DIR_HAS_TASK.getCode(), assertInstanceOf(ServiceException.class, failure.getCause()).getCode());
            assertEquals(0, sql.queryForObject("SELECT deleted FROM dh_dataforge_dev_dir WHERE id=1", Integer.class));
        } finally { release.countDown(); }
    }

    private void awaitAdvisoryWaiter() throws InterruptedException {
        BooleanSupplier waiting = () -> Boolean.TRUE.equals(sql.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND classid=0 AND objid=216031 AND NOT granted)", Boolean.class));
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!waiting.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(waiting.getAsBoolean(), "必须观察到另一事务等待同一类目树锁");
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
