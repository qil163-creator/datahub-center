package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushTaskSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.*;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 使用随机测试命名空间验证真实 Spring 代理、JSONB 写入和任务事务回滚。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(30)
class DataPushTaskInlineConfigPostgreSqlTest {
    private Connection control;
    private String schema;
    private AnnotationConfigApplicationContext context;
    private DataPushTaskService taskService;
    private DataPushTaskMapper tasks;
    private DataPushConfigMapper configs;

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {
    }

    @BeforeEach
    void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        control = DriverManager.getConnection(url, user, "");
        schema = "pushinline_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("SET search_path TO " + schema);
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("sql/postgresql/datahub-center.sql"))) {
            root = root.getParent();
        }
        assertNotNull(root);
        String baseline = Files.readString(root.resolve("sql/postgresql/datahub-center.sql")).replace("\r\n", "\n");
        for (String table : List.of("dh_dataforge_push_config", "dh_dataforge_push_task")) {
            sql("CREATE SEQUENCE " + table + "_seq");
            var ddl = Pattern.compile("CREATE TABLE \"datahub-center\"[.]\"" + table + "\" \\(.*?\n\\)\\s*;", Pattern.DOTALL)
                    .matcher(baseline);
            assertTrue(ddl.find(), table);
            sql(ddl.group().replace("\"datahub-center\".", "")
                    .replaceAll(" DEFAULT nextval\\([^\n]*?::regclass\\)", ""));
        }
        // 夹具仅抽取 CREATE TABLE，补齐 0246 后续添加的当前 Mapper 列。
        sql("ALTER TABLE dh_dataforge_push_task ADD COLUMN IF NOT EXISTS category_id bigint");
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, "");
        var manager = new DataSourceTransactionManager(source);
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(control, schema);
        var configuration = new MybatisConfiguration();
        var global = GlobalConfigUtils.getGlobalConfig(configuration);
        global.setMetaObjectHandler(new DefaultDBFieldHandler());
        global.getDbConfig().setIdType(IdType.INPUT).setKeyGenerators(List.of(new PostgreKeyGenerator()));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setEnvironment(new Environment("push-inline-test", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        configuration.addInterceptor(interceptor);
        configuration.addMapper(DataPushTaskMapper.class);
        configuration.addMapper(DataPushConfigMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        tasks = session.getMapper(DataPushTaskMapper.class);
        configs = session.getMapper(DataPushConfigMapper.class);
        var databaseMapper = mock(DatabaseMapper.class);
        when(databaseMapper.selectById(5L)).thenReturn(DatabaseDO.builder().id(5L).build());
        var configService = new DataPushConfigServiceImpl();
        ReflectionTestUtils.setField(configService, "configMapper", configs);
        ReflectionTestUtils.setField(configService, "taskMapper", tasks);
        ReflectionTestUtils.setField(configService, "databaseMapper", databaseMapper);
        ReflectionTestUtils.setField(configService, "httpPushUrlGuard", mock(HttpPushUrlGuard.class));
        var coordinator = new DataPushRunCoordinator();
        ReflectionTestUtils.setField(coordinator, "tasks", tasks);
        ReflectionTestUtils.setField(coordinator, "runs", mock(DataPushTaskLogMapper.class));
        ReflectionTestUtils.setField(coordinator, "transactionManager", manager);
        context = new AnnotationConfigApplicationContext();
        // 依赖已按验证范围显式装配，只启用真实事务代理，避免拉起外部集成客户端。
        context.addBeanFactoryPostProcessor(beanFactory -> {
            if (beanFactory instanceof BeanDefinitionRegistry registry
                    && registry.containsBeanDefinition(AnnotationConfigUtils.COMMON_ANNOTATION_PROCESSOR_BEAN_NAME)) {
                registry.removeBeanDefinition(AnnotationConfigUtils.COMMON_ANNOTATION_PROCESSOR_BEAN_NAME);
            }
        });
        context.register(Transactions.class);
        context.registerBean("transactionManager", PlatformTransactionManager.class, () -> manager);
        context.registerBean("pushConfigService", DataPushConfigServiceImpl.class, () -> configService);
        context.registerBean("pushTaskService", DataPushTaskServiceImpl.class, () -> {
            var service = new DataPushTaskServiceImpl();
            ReflectionTestUtils.setField(service, "taskMapper", tasks);
            ReflectionTestUtils.setField(service, "configMapper", configs);
            ReflectionTestUtils.setField(service, "configService", context.getBean(DataPushConfigService.class));
            ReflectionTestUtils.setField(service, "databaseMapper", databaseMapper);
            ReflectionTestUtils.setField(service, "runCoordinator", coordinator);
            ReflectionTestUtils.setField(service, "logMapper", mock(DataPushTaskLogMapper.class));
            ReflectionTestUtils.setField(service, "increaseLogMapper", mock(DataPushIncreaseLogMapper.class));
            return service;
        });
        context.refresh();
        taskService = context.getBean(DataPushTaskService.class);
        TenantContextHolder.setTenantId(1L);
        TenantContextHolder.setIgnore(false);
    }

    @AfterEach
    void cleanup() throws Exception {
        TenantContextHolder.clear();
        if (context != null) {
            context.close();
        }
        if (control != null) {
            try {
                if (schema != null && schema.matches("pushinline_[a-f0-9]{32}")) {
                    sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            } finally {
                control.close();
            }
        }
    }

    @Test
    void separateNewTasksReceiveSeparateConfigurations() {
        Long first = taskService.createTask(DataPushTaskInlineConfigTest.taskRequest());
        Long second = taskService.createTask(DataPushTaskInlineConfigTest.taskRequest());
        assertNotEquals(tasks.selectById(first).getPushConfigId(), tasks.selectById(second).getPushConfigId());
        assertEquals(2L, configs.selectCount());
    }

    @Test
    void rejectedTaskInsertRollsBackInlineConfiguration() throws Exception {
        sql("ALTER TABLE dh_dataforge_push_task ADD CONSTRAINT reject_name CHECK(name <> '拒绝创建')");
        var request = DataPushTaskInlineConfigTest.taskRequest();
        request.setName("拒绝创建");
        var failure = assertThrows(RuntimeException.class, () -> taskService.createTask(request));
        assertTrue(failure.getMessage().contains("reject_name"));
        assertEquals(0L, configs.selectCount());
        assertEquals(0L, tasks.selectCount());
    }

    @Test
    void taskUpdateFailureRollsBackExclusiveConfigurationUpdate() throws Exception {
        Long id = taskService.createTask(DataPushTaskInlineConfigTest.taskRequest());
        Long configId = tasks.selectById(id).getPushConfigId();
        String originalName = configs.selectById(configId).getName();
        sql("ALTER TABLE dh_dataforge_push_task ADD CONSTRAINT reject_update CHECK(name <> '拒绝编辑')");
        var request = updateRequest(id);
        request.setName("拒绝编辑");
        request.getPushConfig().setName("失败时不能留下的配置");
        var failure = assertThrows(RuntimeException.class, () -> taskService.updateTask(request));
        assertTrue(failure.getMessage().contains("reject_update"));
        assertEquals(originalName, configs.selectById(configId).getName());
        assertNotEquals("拒绝编辑", tasks.selectById(id).getName());
        assertEquals(1L, configs.selectCount());
    }

    @Test
    void sharedConfigurationEditChangesOnlyTheEditedTask() {
        Long first = taskService.createTask(DataPushTaskInlineConfigTest.taskRequest());
        Long originalConfigId = tasks.selectById(first).getPushConfigId();
        var legacy = DataPushTaskInlineConfigTest.taskRequest();
        legacy.setPushConfig(null);
        legacy.setPushConfigId(originalConfigId);
        Long second = taskService.createTask(legacy);
        var request = updateRequest(first);
        request.getPushConfig().setName("当前任务独立接收端");
        taskService.updateTask(request);
        assertNotEquals(originalConfigId, tasks.selectById(first).getPushConfigId());
        assertEquals(originalConfigId, tasks.selectById(second).getPushConfigId());
        assertEquals("任务接收端", configs.selectById(originalConfigId).getName());
        assertEquals(2L, configs.selectCount());
    }

    @Test
    void sharedConfigurationCopyRollsBackWhenTaskUpdateFails() throws Exception {
        Long first = taskService.createTask(DataPushTaskInlineConfigTest.taskRequest());
        Long originalConfigId = tasks.selectById(first).getPushConfigId();
        var legacy = DataPushTaskInlineConfigTest.taskRequest();
        legacy.setPushConfig(null);
        legacy.setPushConfigId(originalConfigId);
        taskService.createTask(legacy);
        sql("ALTER TABLE dh_dataforge_push_task ADD CONSTRAINT reject_update CHECK(name <> '拒绝编辑')");
        var request = updateRequest(first);
        request.setName("拒绝编辑");
        var failure = assertThrows(RuntimeException.class, () -> taskService.updateTask(request));
        assertTrue(failure.getMessage().contains("reject_update"));
        assertEquals(originalConfigId, tasks.selectById(first).getPushConfigId());
        assertEquals(1L, configs.selectCount());
    }

    @Test
    void repeatedExclusiveEditsKeepOneConfiguration() {
        Long id = taskService.createTask(DataPushTaskInlineConfigTest.taskRequest());
        Long configId = tasks.selectById(id).getPushConfigId();
        for (int index = 0; index < 3; index++) {
            var request = updateRequest(id);
            request.getPushConfig().setName("接收端编辑" + index);
            taskService.updateTask(request);
            assertEquals(configId, tasks.selectById(id).getPushConfigId());
        }
        assertEquals(1L, configs.selectCount());
    }

    @Test
    void missingTaskAndConfigurationAreRejected() {
        Long id = taskService.createTask(DataPushTaskInlineConfigTest.taskRequest());
        var request = updateRequest(id); request.setId(-1L);
        assertThrows(RuntimeException.class, () -> taskService.updateTask(request));
        var legacy = DataPushTaskInlineConfigTest.taskRequest();
        legacy.setPushConfig(null); legacy.setPushConfigId(-1L);
        assertThrows(RuntimeException.class, () -> taskService.createTask(legacy));
        assertEquals(1L, tasks.selectCount()); assertEquals(1L, configs.selectCount());
    }

    @Test
    void taskDeletionRemovesItsLastUnreferencedConfiguration() {
        Long id = taskService.createTask(DataPushTaskInlineConfigTest.taskRequest());
        taskService.deleteTask(id);
        assertEquals(0L, tasks.selectCount());
        assertEquals(0L, configs.selectCount());
    }

    @Test
    void deletingOneSharedTaskKeepsTheOtherTasksConfiguration() {
        Long first = taskService.createTask(DataPushTaskInlineConfigTest.taskRequest());
        Long configId = tasks.selectById(first).getPushConfigId();
        var legacy = DataPushTaskInlineConfigTest.taskRequest();
        legacy.setPushConfig(null);
        legacy.setPushConfigId(configId);
        Long second = taskService.createTask(legacy);
        taskService.deleteTask(first);
        assertEquals(configId, tasks.selectById(second).getPushConfigId());
        assertEquals(1L, configs.selectCount());
    }

    @Test
    void configurationDeleteFailureRollsBackTaskDeletion() throws Exception {
        Long id = taskService.createTask(DataPushTaskInlineConfigTest.taskRequest());
        sql("ALTER TABLE dh_dataforge_push_config ADD CONSTRAINT reject_delete CHECK(deleted=0)");
        var failure = assertThrows(RuntimeException.class, () -> taskService.deleteTask(id));
        assertTrue(failure.getMessage().contains("reject_delete"));
        assertNotNull(tasks.selectById(id));
        assertEquals(1L, configs.selectCount());
    }

    @Test
    void copiedTaskReceivesIndependentConfiguration() {
        Long original = taskService.createTask(DataPushTaskInlineConfigTest.taskRequest());
        Long copied = taskService.copyTask(original);
        assertNotEquals(tasks.selectById(original).getPushConfigId(), tasks.selectById(copied).getPushConfigId());
        assertEquals(2L, configs.selectCount());
        assertEquals(2L, tasks.selectCount());
    }

    @Test
    void rejectedTaskCopyRollsBackItsNewConfiguration() throws Exception {
        Long original = taskService.createTask(DataPushTaskInlineConfigTest.taskRequest());
        sql("CREATE FUNCTION reject_copy_task() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$");
        sql("CREATE TRIGGER reject_copy_task BEFORE INSERT ON dh_dataforge_push_task FOR EACH ROW EXECUTE FUNCTION reject_copy_task()");
        var failure = assertThrows(RuntimeException.class, () -> taskService.copyTask(original));
        assertTrue(failure.getMessage().contains("fixture"));
        assertEquals(1L, configs.selectCount());
        assertEquals(1L, tasks.selectCount());
    }

    private DataPushTaskSaveReqVO updateRequest(Long id) {
        var request = DataPushTaskInlineConfigTest.taskRequest();
        request.setId(id);
        request.getPushConfig().setId(tasks.selectById(id).getPushConfigId());
        return request;
    }

    private void sql(String statement) throws Exception {
        try (var command = control.createStatement()) {
            command.execute(statement);
        }
    }
}
