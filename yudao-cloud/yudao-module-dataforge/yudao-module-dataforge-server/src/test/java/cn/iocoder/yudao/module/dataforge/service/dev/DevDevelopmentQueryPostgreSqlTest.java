package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevTaskMapper;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Uses a migrated disposable PostgreSQL database, never an application database. */
class DevDevelopmentQueryPostgreSqlTest {

    @Test
    void latestStatusPaginationAndAggregatesStayInsideTheCurrentTenant() throws Exception {
        String url = System.getenv("DATAFORGE_TEST_JDBC_URL");
        assumeTrue(url != null && !url.isBlank(), "DATAFORGE_TEST_JDBC_URL must point to a disposable migrated PostgreSQL database");
        String user = System.getenv().getOrDefault("DATAFORGE_TEST_JDBC_USER", "postgres");
        String password = System.getenv().getOrDefault("DATAFORGE_TEST_JDBC_PASSWORD", "");
        String schema = "dev_query_" + UUID.randomUUID().toString().replace("-", "");
        String scopedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var source = new PooledDataSource("org.postgresql.Driver", scopedUrl, user, password);
        try (var connection = DriverManager.getConnection(url, user, password);
             var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE TABLE " + schema + ".dh_dataforge_dev_task (LIKE \"datahub-center\".dh_dataforge_dev_task INCLUDING DEFAULTS)");
            sql.execute("CREATE TABLE " + schema + ".dh_dataforge_dev_record (LIKE \"datahub-center\".dh_dataforge_dev_record INCLUDING DEFAULTS)");
            sql.execute("INSERT INTO " + schema + ".dh_dataforge_dev_task(id,dir_id,name,task_type,db_type) VALUES(10,1,'own failed','SQL','POSTGRESQL'),(11,2,'never run','SQL','MYSQL'),(20,1,'other tenant','SQL','POSTGRESQL')");
            sql.execute("INSERT INTO " + schema + ".dh_dataforge_dev_record(id,task_id,task_name,status,start_time,deleted) VALUES(100,10,'own failed','SUCCESS',TIMESTAMP '2026-10-11 12:00:00',0),(101,10,'own failed','FAILED',TIMESTAMP '2026-10-11 12:00:01',0),(102,10,'other tenant same task','SUCCESS',TIMESTAMP '2026-10-11 12:00:02',0),(103,10,'deleted latest','STOPPED',TIMESTAMP '2026-10-11 12:00:03',1),(104,20,'other tenant','RUNNING',TIMESTAMP '2026-10-11 12:00:04',0)");
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(
                    new cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler());
            config.setEnvironment(new Environment("dev-query-postgresql", new JdbcTransactionFactory(), source));
        cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture.upgrade(source);
            var interceptor = new MybatisPlusInterceptor();

            interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
            config.addInterceptor(interceptor);
            config.addMapper(DevTaskMapper.class);
            config.addMapper(DevRecordMapper.class);
            TenantContextHolder.setTenantId(1L);
            try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
                var tasks = session.getMapper(DevTaskMapper.class);
                var records = session.getMapper(DevRecordMapper.class);
                var req = new DevTaskPageReqVO();
                req.setExecutionStatus("FAILED");
                var page = tasks.selectPage(req, List.of(1L, 2L));
                assertEquals(1L, page.getTotal());
                assertEquals(10L, page.getList().getFirst().getId());
                req.setExecutionStatus("SUCCESS");
                assertEquals(0L, tasks.selectPage(req, List.of(1L, 2L)).getTotal());
                req.setExecutionStatus("NEVER");
                assertEquals(11L, tasks.selectPage(req, List.of(1L, 2L)).getList().getFirst().getId());
                req.setExecutionStatus(null);
                req.setDbType("POSTGRESQL");
                assertEquals(1L, tasks.selectPage(req, List.of(1L, 2L)).getTotal());
                req.setExecutionStartTime(new LocalDateTime[] {LocalDateTime.of(2026, 10, 11, 12, 0),
                        LocalDateTime.of(2026, 10, 11, 12, 0, 1)});
                assertEquals(1L, tasks.selectPage(req, List.of(1L, 2L)).getTotal());
                req.setExecutionStartTime(new LocalDateTime[] {LocalDateTime.of(2026, 10, 11, 12, 0, 2),
                        LocalDateTime.of(2026, 10, 11, 12, 0, 4)});
                assertEquals(0L, tasks.selectPage(req, List.of(1L, 2L)).getTotal());
                req.setExecutionStartTime(null);
                var latest = records.selectLatestByTaskIds(List.of(10L, 20L));
                assertEquals(1, latest.size());
                assertEquals(101L, latest.getFirst().getId());
                assertEquals(0L, records.countActiveTasks());
                var today = LocalDateTime.of(2026, 10, 11, 0, 0);
                assertEquals(2L, records.countStartedBetween(today, today.plusDays(1), null));
                assertEquals(1L, records.countStartedBetween(today, today.plusDays(1), "FAILED"));
                var service = new DevTaskServiceImpl();
                org.springframework.test.util.ReflectionTestUtils.setField(service, "taskMapper", tasks);
                org.springframework.test.util.ReflectionTestUtils.setField(service, "dirService",
                        org.mockito.Mockito.mock(DevDirService.class));
                var saveTransaction = org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class);
                org.mockito.Mockito.doAnswer(call -> {
                    ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) call.getArgument(0))
                            .accept(new org.springframework.transaction.support.SimpleTransactionStatus());
                    return null;
                }).when(saveTransaction).executeWithoutResult(org.mockito.ArgumentMatchers.any());
                org.springframework.test.util.ReflectionTestUtils.setField(service, "transactionTemplate", saveTransaction);
                var draft = new cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskSaveReqVO();
                draft.setId(10L); draft.setExpectedRevision(0); draft.setDirId(1L);
                draft.setName("own failed"); draft.setTaskType("SQL"); draft.setDbType("MYSQL");
                var execution = new cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig();
                execution.setRetryTimes(2); draft.setExecutionConfig(execution);
                service.updateTask(draft);
                var saved = tasks.selectById(10L);
                assertEquals("MYSQL", saved.getDbType()); assertNull(saved.getDatabaseId());
                assertEquals(1, saved.getRevision());
                assertEquals(2, DevTaskDefinition.readExecutionConfig(saved.getExecutionConfigJson()).getRetryTimes());
                TenantContextHolder.setTenantId(2L);
                assertEquals(1L, records.countActiveTasks());
                var recordReq = new DevRecordPageReqVO();
                recordReq.setStatus("RUNNING");
                assertEquals(104L, records.selectPage(recordReq).getList().getFirst().getId());
            }
        } finally {
            TenantContextHolder.clear();
            source.forceCloseAll();
            try (var connection = DriverManager.getConnection(url, user, password); var sql = connection.createStatement()) {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
