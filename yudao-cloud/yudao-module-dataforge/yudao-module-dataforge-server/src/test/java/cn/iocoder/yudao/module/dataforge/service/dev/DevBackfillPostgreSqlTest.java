package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.backfill.DevBackfillCreateReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevBackfillDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevBackfillItemDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskVersionDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevBackfillItemMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevBackfillMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevRecordMapper;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实 PostgreSQL 上验证补数据批次的串行推进、失败策略、版本冻结、取消与单活约束；运行登记与实例状态用替身。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(60)
class DevBackfillPostgreSqlTest {

    private static final long TASK = 41L;
    private static final long VERSION = 700L;
    private static final long USER = 9L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final String schema = "dev_backfill_" + UUID.randomUUID().toString().replace("-", "");
    private Connection control;
    private DevBackfillMapper backfills;
    private DevBackfillItemMapper items;
    private DevTaskService tasks;
    private DevDsSubmitter submitter;
    private DevBackfillServiceImpl service;
    private MockedStatic<SecurityFrameworkUtils> security;
    private final Map<Long, DevRecordDO> records = new HashMap<>();
    private final Map<String, Long> runKeys = new HashMap<>();
    private final AtomicLong nextRecord = new AtomicLong(5000);

    @BeforeEach
    void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        control = DriverManager.getConnection(url, user, "");
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema);
        execute(migrationDdl());
        var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, "");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        GlobalConfigUtils.getGlobalConfig(config).getDbConfig().setIdType(IdType.ASSIGN_ID);
        GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
        config.setEnvironment(new Environment("dev-backfill-real-pg", new SpringManagedTransactionFactory(), source));
        config.addMapper(DevBackfillMapper.class);
        config.addMapper(DevBackfillItemMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        backfills = session.getMapper(DevBackfillMapper.class);
        items = session.getMapper(DevBackfillItemMapper.class);

        DevRecordMapper recordMapper = mock(DevRecordMapper.class);
        when(recordMapper.selectById(any())).thenAnswer(inv -> records.get((Long) inv.getArgument(0)));
        tasks = mock(DevTaskService.class);
        when(tasks.validateBackfill(TASK)).thenReturn(version());
        when(tasks.enqueueBackfill(eq(TASK), eq(VERSION), anyString(), any())).thenAnswer(inv -> {
            String runKey = inv.getArgument(2);
            return runKeys.computeIfAbsent(runKey, key -> {
                long id = nextRecord.incrementAndGet();
                records.put(id, DevRecordDO.builder().id(id).runKey(key).businessDate(inv.getArgument(3))
                        .status(DevRecordDO.STATUS_WAITING).qualityStatus("NOT_CONFIGURED").build());
                return id;
            });
        });
        submitter = mock(DevDsSubmitter.class);
        doAnswer(inv -> {
            DevRecordDO record = records.get((Long) inv.getArgument(0));
            if (record != null && DevRecordDO.ACTIVE_STATUSES.contains(record.getStatus())) {
                record.setCancelRequested(true);
            }
            return null;
        }).when(submitter).stop(anyLong());
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        service = new DevBackfillServiceImpl(backfills, items, recordMapper, tasks, submitter, tx,
                Clock.fixed(LocalDate.of(2026, 11, 9).atTime(10, 0).atZone(ZONE).toInstant(), ZONE));
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(USER);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (security != null) security.close();
        if (control != null) {
            try {
                assertTrue(schema.matches("dev_backfill_[a-f0-9]{32}"));
                execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            } finally {
                control.close();
            }
        }
    }

    @Test
    void datesRunStrictlyOneAfterAnotherAndBatchSucceedsOnlyAfterEveryDate() {
        Long id = service.createBackfill(request("req-serial-0001", "2026-10-01", "2026-10-03", "ASC", "STOP"));
        assertEquals(id, service.createBackfill(request("req-serial-0001", "2026-10-01", "2026-10-03", "ASC", "STOP")));
        assertEquals(List.of("RUNNING", "PENDING", "PENDING"), statuses(id));
        verify(tasks).enqueueBackfill(TASK, VERSION, "BACKFILL:" + id + ":20261001", LocalDate.of(2026, 10, 1));

        service.advance(id); // 首日仍在运行：不登记次日
        verify(tasks, times(1)).enqueueBackfill(eq(TASK), eq(VERSION), anyString(), any());

        finish(id, 1, DevRecordDO.STATUS_SUCCESS, "PENDING");
        service.advance(id); // 运行成功但质量结论未出：仍占用
        assertEquals(List.of("RUNNING", "PENDING", "PENDING"), statuses(id));

        finish(id, 1, DevRecordDO.STATUS_SUCCESS, "PASSED");
        service.advance();
        assertEquals(List.of("SUCCESS", "RUNNING", "PENDING"), statuses(id));
        finish(id, 2, DevRecordDO.STATUS_SUCCESS, "NOT_CONFIGURED");
        service.advance(id);
        finish(id, 3, DevRecordDO.STATUS_SUCCESS, "PASSED");
        service.advance(id);

        DevBackfillDO done = backfills.selectById(id);
        assertEquals(DevBackfillDO.STATUS_SUCCESS, done.getStatus());
        assertEquals(3, done.getSuccessCount());
        assertNotNull(done.getFinishTime());
        assertEquals(List.of("SUCCESS", "SUCCESS", "SUCCESS"), statuses(id));
        assertEquals(1, service.getBackfill(id).getItems().getFirst().getSeq());
    }

    @Test
    void otherActiveRunOnlyDelaysTheNextDate() {
        when(tasks.enqueueBackfill(eq(TASK), eq(VERSION), anyString(), any()))
                .thenThrow(exception(DEV_TASK_RUNNING));
        Long id = service.createBackfill(request("req-busy-00001", "2026-10-01", "2026-10-02", "DESC", "STOP"));
        service.advance(id);
        assertEquals(List.of("PENDING", "PENDING"), statuses(id));
        assertEquals(DevBackfillDO.STATUS_RUNNING, backfills.selectById(id).getStatus());
        assertEquals(LocalDate.of(2026, 10, 2), items.selectListByBackfillId(id).getFirst().getBusinessDate());
    }

    @Test
    void stopPolicyCancelsRemainingDatesAfterAFailure() {
        Long id = service.createBackfill(request("req-stop-00001", "2026-10-01", "2026-10-03", "ASC", "STOP"));
        finish(id, 1, DevRecordDO.STATUS_FAILED, "NOT_CONFIGURED");
        service.advance(id);
        DevBackfillDO batch = backfills.selectById(id);
        assertEquals(DevBackfillDO.STATUS_FAILED, batch.getStatus());
        assertEquals(1, batch.getFailedCount());
        assertEquals(2, batch.getCancelledCount());
        assertEquals(List.of("FAILED", "CANCELLED", "CANCELLED"), statuses(id));
        verify(tasks, times(1)).enqueueBackfill(eq(TASK), eq(VERSION), anyString(), any());
    }

    @Test
    void continuePolicyRunsEveryDateAndReportsFailure() {
        Long id = service.createBackfill(request("req-cont-00001", "2026-10-01", "2026-10-03", "ASC", "CONTINUE"));
        finish(id, 1, DevRecordDO.STATUS_SUCCESS, "FAILED"); // 质量不通过计为失败
        service.advance(id);
        finish(id, 2, DevRecordDO.STATUS_SUCCESS, "PASSED");
        service.advance(id);
        finish(id, 3, DevRecordDO.STATUS_SUCCESS, "PASSED");
        service.advance(id);
        DevBackfillDO batch = backfills.selectById(id);
        assertEquals(DevBackfillDO.STATUS_FAILED, batch.getStatus());
        assertEquals(2, batch.getSuccessCount());
        assertEquals(1, batch.getFailedCount());
        assertTrue(batch.getLastError().startsWith("2026-10-01"));
    }

    @Test
    void versionChangeTerminatesTheBatch() {
        Long id = service.createBackfill(request("req-ver-000001", "2026-10-01", "2026-10-03", "ASC", "CONTINUE"));
        finish(id, 1, DevRecordDO.STATUS_SUCCESS, "PASSED");
        when(tasks.enqueueBackfill(eq(TASK), eq(VERSION), anyString(), any()))
                .thenThrow(exception(DEV_BACKFILL_VERSION_CHANGED));
        service.advance(id);
        DevBackfillDO batch = backfills.selectById(id);
        assertEquals(DevBackfillDO.STATUS_FAILED, batch.getStatus());
        assertEquals(List.of("SUCCESS", "FAILED", "CANCELLED"), statuses(id));
        assertEquals(DEV_BACKFILL_VERSION_CHANGED.getMsg(), batch.getLastError());
    }

    @Test
    void cancelStopsTheSubmittedDateAndFinishesAfterTheEngineConfirms() {
        Long id = service.createBackfill(request("req-cancel-001", "2026-10-01", "2026-10-03", "ASC", "STOP"));
        Long running = items.selectListByBackfillId(id).getFirst().getRecordId();
        service.cancelBackfill(id);
        verify(submitter).stop(running);
        assertEquals(List.of("RUNNING", "CANCELLED", "CANCELLED"), statuses(id));
        assertEquals(DevBackfillDO.STATUS_RUNNING, backfills.selectById(id).getStatus());

        records.get(running).setStatus(DevRecordDO.STATUS_STOPPED);
        service.advance(id);
        DevBackfillDO batch = backfills.selectById(id);
        assertEquals(DevBackfillDO.STATUS_CANCELLED, batch.getStatus());
        assertEquals(3, batch.getCancelledCount());
        ServiceException again = assertThrows(ServiceException.class, () -> service.cancelBackfill(id));
        assertEquals(DEV_BACKFILL_NOT_RUNNING.getCode(), again.getCode());
    }

    @Test
    void onlyTheExecutionIdentityMayCancelAndOneRunningBatchPerTask() {
        Long id = service.createBackfill(request("req-own-000001", "2026-10-01", "2026-10-02", "ASC", "STOP"));
        ServiceException busy = assertThrows(ServiceException.class,
                () -> service.createBackfill(request("req-own-000002", "2026-09-01", "2026-09-02", "ASC", "STOP")));
        assertEquals(DEV_BACKFILL_ALREADY_RUNNING.getCode(), busy.getCode());
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> service.createBackfill(request("req-own-000001", "2026-09-01", "2026-09-02", "ASC", "STOP")));
        assertEquals(DEV_RUN_REQUEST_CONFLICT.getCode(), conflict.getCode());
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(USER + 1);
        ServiceException denied = assertThrows(ServiceException.class, () -> service.cancelBackfill(id));
        assertEquals(OPERATION_PERMISSION_DENIED.getCode(), denied.getCode());
        // 数据库部分唯一索引兜底：同任务第二个 RUNNING 批次无法写入。
        assertThrows(SQLException.class, () -> execute("INSERT INTO dh_dataforge_dev_backfill(task_id,version_id,"
                + "execution_user_id,start_date,end_date,request_id) VALUES (" + TASK + ",1,1,'2026-01-01','2026-01-01','raw-dup')"));
        assertThrows(SQLException.class, () -> execute("INSERT INTO dh_dataforge_dev_backfill(task_id,version_id,"
                + "execution_user_id,start_date,end_date,status,request_id) VALUES (99,1,1,'2025-01-01','2026-01-02','DONE','raw-range')"));
    }

    private void finish(Long backfillId, int seq, String status, String quality) {
        DevBackfillItemDO item = items.selectListByBackfillId(backfillId).get(seq - 1);
        DevRecordDO record = records.get(item.getRecordId());
        record.setStatus(status);
        record.setQualityStatus(quality);
    }

    private List<String> statuses(Long backfillId) {
        return items.selectListByBackfillId(backfillId).stream().map(DevBackfillItemDO::getStatus).toList();
    }

    private static DevBackfillCreateReqVO request(String requestId, String start, String end, String order,
            String policy) {
        var req = new DevBackfillCreateReqVO();
        req.setTaskId(TASK);
        req.setStartDate(LocalDate.parse(start));
        req.setEndDate(LocalDate.parse(end));
        req.setDateOrder(order);
        req.setFailurePolicy(policy);
        req.setRequestId(requestId);
        return req;
    }

    private static DevTaskVersionDO version() {
        var snapshot = new DevTaskSnapshot();
        snapshot.setTask(DevTaskDO.builder().id(TASK).name("日汇总").executionUserId(USER).build());
        return DevTaskVersionDO.builder().id(VERSION).taskId(TASK)
                .snapshotJson(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(snapshot)).build();
    }

    private String migrationDdl() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        String sql = Files.readString(root.resolve("sql/postgresql/migrations/0269_dataforge_dev-backfill.sql"));
        // 只取本迁移的建表/索引语句；dev_record 注释依赖既有表，不在隔离 schema 中执行。
        return sql.substring(sql.indexOf("CREATE SEQUENCE IF NOT EXISTS dh_dataforge_dev_backfill_seq;"),
                sql.indexOf("COMMENT ON COLUMN dh_dataforge_dev_record.trigger_type"));
    }

    private void execute(String sql) throws SQLException {
        try (var statement = control.createStatement()) {
            statement.execute(sql);
        }
    }

}
