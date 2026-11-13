package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillEventOutboxDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillMergeBatchDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillNotifyRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillEventInboxMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillEventOutboxMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillMergeBatchMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillNotifyRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillWorkItemMapper;
import cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer;
import cn.iocoder.yudao.module.dataforge.support.SingleTenantPostgreSqlFixture;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 在线填报定时任务用到的扫描与抢占 SQL（真实 PostgreSQL，表结构取自全量基线）
 */
@Testcontainers
class FillSchedulerSqlPostgreSqlTest {

    private static final String SCHEMA = "fill_sql";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new DataforgePostgreSqlContainer();

    private JdbcTemplate jdbc;
    private FillWorkItemMapper workItems;
    private FillPlanMapper plans;
    private FillTaskMapper tasks;
    private FillNotifyRecordMapper notifyRecords;
    private FillMergeBatchMapper mergeBatches;
    private FillEventOutboxMapper outbox;
    private FillEventInboxMapper inbox;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        try (var connection = source.getConnection()) {
            SingleTenantPostgreSqlFixture.createBaselineTables(connection, SCHEMA,
                    "dh_dataforge_fill_work_item", "dh_dataforge_fill_correction", "dh_dataforge_fill_plan",
                    "dh_dataforge_fill_task", "dh_dataforge_fill_notify_record", "dh_dataforge_fill_merge_batch",
                    "dh_dataforge_fill_event_outbox", "dh_dataforge_fill_event_inbox");
        }
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setEnvironment(new Environment("fill-scheduler-sql", new SpringManagedTransactionFactory(), source));
        var global = GlobalConfigUtils.getGlobalConfig(configuration);
        global.setMetaObjectHandler(new DefaultDBFieldHandler());
        global.getDbConfig().setIdType(IdType.INPUT).setLogicDeleteValue("1").setLogicNotDeleteValue("0")
                .setKeyGenerators(List.of(new PostgreKeyGenerator()));
        configuration.addMapper(FillWorkItemMapper.class);
        configuration.addMapper(FillPlanMapper.class);
        configuration.addMapper(FillTaskMapper.class);
        configuration.addMapper(FillNotifyRecordMapper.class);
        configuration.addMapper(FillMergeBatchMapper.class);
        configuration.addMapper(FillEventOutboxMapper.class);
        configuration.addMapper(FillEventInboxMapper.class);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        workItems = sessions.getMapper(FillWorkItemMapper.class);
        plans = sessions.getMapper(FillPlanMapper.class);
        tasks = sessions.getMapper(FillTaskMapper.class);
        notifyRecords = sessions.getMapper(FillNotifyRecordMapper.class);
        mergeBatches = sessions.getMapper(FillMergeBatchMapper.class);
        outbox = sessions.getMapper(FillEventOutboxMapper.class);
        inbox = sessions.getMapper(FillEventInboxMapper.class);
    }

    private void workItem(long id, int status, String deadline) {
        jdbc.update("INSERT INTO dh_dataforge_fill_work_item(id, task_id, plan_id, table_version_id, reporting_org_id,"
                + " org_name, status, overdue, deadline_time) VALUES (?, 9, 1, 1, ?, ?, ?, 0, ?::timestamp)",
                id, id, "组织" + id, status, deadline);
    }

    private static List<Long> ids(List<FillWorkItemDO> items) {
        return items.stream().map(FillWorkItemDO::getId).toList();
    }

    @Test
    void remindCandidatesArePagedByDeadlineAndIdAndSkipItemsUnderCorrection() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 8, 0);
        workItem(1, 1, "2026-11-10 12:00:00");
        workItem(2, 1, "2026-11-10 12:00:00");
        workItem(3, 0, "2026-11-10 12:00:00");
        workItem(4, 5, "2026-11-11 09:00:00");
        workItem(5, 5, "2026-11-11 10:00:00");
        workItem(6, 4, "2026-11-10 12:00:00");   // 审批中，不提醒
        workItem(7, 1, "2026-12-30 00:00:00");   // 超出窗口
        // 5 号正在更正中：由发起者重新开放，不按原截止时间提醒
        jdbc.update("INSERT INTO dh_dataforge_fill_correction(id, origin_submission_id, work_item_id, task_id, reason,"
                + " status) VALUES (1, 100, 5, 9, '口径调整', 0)");
        // 4 号的更正已结束，不影响提醒
        jdbc.update("INSERT INTO dh_dataforge_fill_correction(id, origin_submission_id, work_item_id, task_id, reason,"
                + " status) VALUES (2, 100, 4, 9, '已完成的更正', 2)");

        List<FillWorkItemDO> first = workItems.selectRemindCandidates(now, now.plusHours(168), null, null, 2);
        assertEquals(List.of(1L, 2L), ids(first));
        FillWorkItemDO last = first.get(1);
        List<FillWorkItemDO> second = workItems.selectRemindCandidates(now, now.plusHours(168),
                last.getDeadlineTime(), last.getId(), 2);
        assertEquals(List.of(3L, 4L), ids(second));
        last = second.get(1);
        assertEquals(List.of(), ids(workItems.selectRemindCandidates(now, now.plusHours(168),
                last.getDeadlineTime(), last.getId(), 2)));
    }

    @Test
    void overdueCandidatesSkipItemsUnderCorrection() {
        workItem(1, 1, "2026-11-01 00:00:00");
        workItem(2, 5, "2026-11-01 00:00:00");
        jdbc.update("INSERT INTO dh_dataforge_fill_correction(id, origin_submission_id, work_item_id, task_id, reason,"
                + " status) VALUES (1, 100, 2, 9, '更正中', 1)");

        assertEquals(List.of(1L), ids(workItems.selectOverdueCandidates(LocalDateTime.of(2026, 11, 10, 0, 0), 10)));
    }

    @Test
    void generatePointRollbackOnlyAppliesToTheAdvancedValue() {
        jdbc.update("INSERT INTO dh_dataforge_fill_plan(id, name, table_id, table_version_id, period_type,"
                + " process_definition_key, next_generate_time, last_period_key)"
                + " VALUES (1, '月报', 1, 1, 4, 'approval', '2026-12-01 08:00:00', '2026-11')");
        // 一次性计划：生成后下次生成时间为空
        jdbc.update("INSERT INTO dh_dataforge_fill_plan(id, name, table_id, table_version_id, period_type,"
                + " process_definition_key, next_generate_time, last_period_key)"
                + " VALUES (2, '一次性', 1, 1, 1, 'approval', NULL, 'ONCE-2')");
        LocalDateTime november = LocalDateTime.of(2026, 11, 1, 8, 0);

        // 期间被其他操作改动（值对不上）：不覆盖
        assertEquals(0, plans.rollbackGeneratePoint(1L, LocalDateTime.of(2027, 1, 1, 8, 0), november, "2026-10"));
        assertEquals(1, plans.rollbackGeneratePoint(1L, LocalDateTime.of(2026, 12, 1, 8, 0), november, "2026-10"));
        assertEquals("2026-11-01 08:00:00|2026-10", jdbc.queryForObject(
                "SELECT to_char(next_generate_time, 'YYYY-MM-DD HH24:MI:SS') || '|' || last_period_key"
                        + " FROM dh_dataforge_fill_plan WHERE id = 1", String.class));
        // 空值也能匹配并恢复，最近期次可以恢复为空
        assertEquals(1, plans.rollbackGeneratePoint(2L, null, november, null));
        assertEquals("2026-11-01 08:00:00", jdbc.queryForObject(
                "SELECT to_char(next_generate_time, 'YYYY-MM-DD HH24:MI:SS') FROM dh_dataforge_fill_plan WHERE id = 2",
                String.class));
        assertNull(jdbc.queryForObject("SELECT last_period_key FROM dh_dataforge_fill_plan WHERE id = 2",
                String.class));
    }

    @Test
    void pendingTasksOpenOnceWhenTheirOpenTimeArrives() {
        String insert = "INSERT INTO dh_dataforge_fill_task(id, plan_id, name, table_id, table_version_id,"
                + " process_definition_key, period_key, status, open_time) VALUES (?, 1, ?, 1, 1, 'approval', ?, ?,"
                + " ?::timestamp)";
        jdbc.update(insert, 1, "已到开放时间", "ONCE-1", 0, "2026-11-10 08:00:00");
        jdbc.update(insert, 2, "尚未开放", "ONCE-2", 0, "2026-11-20 08:00:00");
        jdbc.update(insert, 3, "已在进行", "ONCE-3", 1, "2026-11-01 08:00:00");

        List<FillTaskDO> due = tasks.selectListToOpen(LocalDateTime.of(2026, 11, 10, 9, 0), 10);
        assertEquals(List.of(1L), due.stream().map(FillTaskDO::getId).toList());
        assertEquals(1, tasks.compareAndSetStatus(1L, 0, 1));
        // 另一个实例再抢占：失败，不会重复通知
        assertEquals(0, tasks.compareAndSetStatus(1L, 0, 1));
        assertEquals(List.of(), tasks.selectListToOpen(LocalDateTime.of(2026, 11, 10, 9, 0), 10));
    }

    @Test
    void failedNotificationsAreClaimedForRetryWithinLimits() {
        String insert = "INSERT INTO dh_dataforge_fill_notify_record(id, notify_type, receiver_user_id, send_status,"
                + " template_params, retry_count, create_time, update_time)"
                + " VALUES (?, 5, 88, ?, ?, ?, ?::timestamp, ?::timestamp)";
        jdbc.update(insert, 1, 2, "{\"taskName\":\"月报\"}", 0, "2026-11-10 07:00:00", "2026-11-10 07:00:00");
        jdbc.update(insert, 2, 2, "{}", 3, "2026-11-10 07:00:00", "2026-11-10 07:00:00");      // 已到重试上限
        jdbc.update(insert, 3, 2, null, 0, "2026-11-10 07:00:00", "2026-11-10 07:00:00");      // 历史记录无参数快照
        jdbc.update(insert, 4, 2, "{}", 0, "2026-11-08 07:00:00", "2026-11-08 07:00:00");      // 超出重试时间窗
        jdbc.update(insert, 5, 2, "{}", 1, "2026-11-10 07:00:00", "2026-11-10 07:58:00");      // 刚失败，未到间隔
        jdbc.update(insert, 6, 1, "{}", 0, "2026-11-10 07:00:00", "2026-11-10 07:00:00");      // 已发送
        jdbc.update(insert, 7, 0, "{}", 0, "2026-11-10 07:00:00", "2026-11-10 07:00:00");      // 中断遗留的待发送
        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 8, 0);

        List<FillNotifyRecordDO> retry = notifyRecords.selectListToRetry(now.minusMinutes(5), now.minusHours(24), 3, 10);
        assertEquals(List.of(1L, 7L), retry.stream().map(FillNotifyRecordDO::getId).toList());
        assertEquals("{\"taskName\":\"月报\"}", retry.get(0).getTemplateParams());

        assertEquals(1, notifyRecords.claimForRetry(1L, 0));
        assertEquals(0, notifyRecords.claimForRetry(1L, 0));
        assertEquals("0|1", jdbc.queryForObject(
                "SELECT send_status || '|' || retry_count FROM dh_dataforge_fill_notify_record WHERE id = 1",
                String.class));
    }

    @Test
    void mergeBatchScanHonoursRetryLimitAndStaleRunning() {
        String insert = "INSERT INTO dh_dataforge_fill_merge_batch(id, submission_id, work_item_id, task_id,"
                + " merge_strategy, target_database_id, target_table_name, status, retry_count, next_retry_time,"
                + " started_at) VALUES (?, ?, 1, 9, 'UPSERT', 1, 't', ?, ?, ?::timestamp, ?::timestamp)";
        jdbc.update(insert, 1, 101, 0, 0, null, null);                                  // 待执行
        jdbc.update(insert, 2, 102, 0, 9, "2026-11-10 20:00:00", null);                 // 人工重试置回待执行：立即执行
        jdbc.update(insert, 3, 103, 3, 2, "2026-11-10 07:00:00", null);                 // 失败，已到重试时间
        jdbc.update(insert, 4, 104, 3, 2, "2026-11-10 20:00:00", null);                 // 失败，未到重试时间
        jdbc.update(insert, 5, 105, 3, 8, "2026-11-10 07:00:00", null);                 // 失败，已到自动重试上限
        jdbc.update(insert, 6, 106, 1, 0, null, "2026-11-10 06:00:00");                 // 执行中，已超时
        jdbc.update(insert, 7, 107, 1, 0, null, "2026-11-10 07:50:00");                 // 执行中，正常
        jdbc.update(insert, 8, 108, 2, 0, null, null);                                  // 成功
        jdbc.update(insert, 9, 109, 4, 3, null, null);                                  // 已退回整改
        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 8, 0);
        LocalDateTime staleBefore = now.minusMinutes(60);

        List<FillMergeBatchDO> due = mergeBatches.selectListToExecute(now, staleBefore, 8, 20);
        assertEquals(List.of(1L, 2L, 3L, 6L), due.stream().map(FillMergeBatchDO::getId).toList());

        assertEquals(0, mergeBatches.takeOverStaleRunning(7L, staleBefore));
        assertEquals(1, mergeBatches.takeOverStaleRunning(6L, staleBefore));
        // 接管后开始时间已刷新，另一实例不能再接管
        assertEquals(0, mergeBatches.takeOverStaleRunning(6L, staleBefore));
        assertEquals(1, mergeBatches.compareAndSetStatus(5L, 3, 4));
        assertEquals(0, mergeBatches.compareAndSetStatus(5L, 3, 4));
    }

    @Test
    void staleSendingEventsAndFailedInboxEventsCanBeTakenOver() {
        String outboxInsert = "INSERT INTO dh_dataforge_fill_event_outbox(id, event_id, event_type, biz_type, biz_id,"
                + " status, next_retry_time, update_time) VALUES (?, ?, 'FILL_BPM_START_REQUESTED', 'SUBMISSION', ?, ?,"
                + " ?::timestamp, ?::timestamp)";
        jdbc.update(outboxInsert, 1, "e1", 101, 0, null, "2026-11-10 07:59:00");                    // 待发送
        jdbc.update(outboxInsert, 2, "e2", 102, 3, "2026-11-10 09:00:00", "2026-11-10 07:59:00");   // 失败，未到重试时间
        jdbc.update(outboxInsert, 3, "e3", 103, 1, null, "2026-11-10 07:30:00");                    // 发送中，已超时
        jdbc.update(outboxInsert, 4, "e4", 104, 1, null, "2026-11-10 07:59:00");                    // 发送中，正常
        jdbc.update(outboxInsert, 5, "e5", 105, 2, null, "2026-11-10 07:00:00");                    // 成功
        LocalDateTime now = LocalDateTime.of(2026, 11, 10, 8, 0);
        LocalDateTime staleBefore = now.minusMinutes(10);

        assertEquals(List.of(1L, 3L), outbox.selectListToSend(now, staleBefore, 10).stream()
                .map(FillEventOutboxDO::getId).toList());
        assertEquals(0, outbox.takeOverStaleSending(4L, staleBefore));
        assertEquals(1, outbox.takeOverStaleSending(3L, staleBefore));
        assertEquals(0, outbox.takeOverStaleSending(3L, staleBefore));

        String inboxInsert = "INSERT INTO dh_dataforge_fill_event_inbox(id, event_id, event_type, status, update_time)"
                + " VALUES (?, ?, 'FILL_BPM_APPROVED', ?, ?::timestamp)";
        jdbc.update(inboxInsert, 1, "pi-1:2", 3, "2026-11-10 07:59:00");   // 处理失败
        jdbc.update(inboxInsert, 2, "pi-2:2", 0, "2026-11-10 07:30:00");   // 已接收，超时未处理完
        jdbc.update(inboxInsert, 3, "pi-3:2", 0, "2026-11-10 07:59:00");   // 已接收，处理中
        jdbc.update(inboxInsert, 4, "pi-4:2", 1, "2026-11-10 07:00:00");   // 已处理
        assertEquals(1, inbox.reacquireForRetry(1L, staleBefore));
        assertEquals(0, inbox.reacquireForRetry(1L, staleBefore));
        assertEquals(1, inbox.reacquireForRetry(2L, staleBefore));
        assertEquals(0, inbox.reacquireForRetry(3L, staleBefore));
        assertEquals(0, inbox.reacquireForRetry(4L, staleBefore));
    }

}
