package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.extra.spring.SpringUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderCommandReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 独立回环测试库、随机隔离 schema，真实代理/事务/租户插件；不接触业务数据库。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = ".+")
@Timeout(30)
class QualityWorkOrderRecheckPostgreSqlTest {
    private static final String PREFIX = "dh_governance_quality_";
    private final String schema = "quality_attempt_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private HikariDataSource platformSource;
    private Object previousBeanFactory;
    private boolean beanFactoryInstalled;
    private String url;
    private boolean created;
    private Node first;
    private Node second;

    @BeforeEach void setup() throws Exception {
        url = System.getProperty("datahub.test.postgres.url");
        if (!url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]{1,5}/[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Only explicit loopback test databases are allowed");
        }
        admin = new JdbcTemplate(source(null));
        admin.execute("CREATE SCHEMA " + schema);
        created = true;
        // 每 fixture 一个小池；多轮真实回归避免大量短连接耗尽 Windows 回环临时端口。
        // 同 fixture 的所有节点仍共享同一数据源身份，池内多连接保留真实事务/并发语义。
        var pool = new HikariConfig();
        pool.setDataSource(source(schema)); pool.setMaximumPoolSize(8); pool.setMinimumIdle(0);
        pool.setConnectionTimeout(5000); pool.setValidationTimeout(3000);
        pool.setConnectionInitSql("SET TIME ZONE 'Asia/Shanghai'");
        pool.setPoolName(schema);
        platformSource = new HikariDataSource(pool);
        db = new JdbcTemplate(platformSource);
        // BaseMapperX.insertBatch 通过 SpringUtils 获取真实数据源，再调用 Db.saveBatch。
        // 多个 Mapper/事务代理共享本 fixture 的数据源身份，批量会话才会加入同一真实事务。
        previousBeanFactory = ReflectionTestUtils.getField(SpringUtil.class, "beanFactory");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("dataSource", platformSource);
        new SpringUtil().postProcessBeanFactory(beans);
        beanFactoryInstalled = true;
        assertThat(db.queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);
        // 使用测试库已导入的最终基线；每个用例仍使用独立 schema、序列和真实事务。
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.copyCurrentTables(db, schema,
                List.of("work_order", "work_order_issue", "issue_data", "work_order_submission", "work_order_action",
                        "work_order_notify", "task_instance", "audit_log", "work_order_recheck", "work_order_notify_review",
                        "exception_expiry_cursor", "expiry_failure").stream().map(suffix -> PREFIX + suffix).toList());
        db.update("INSERT INTO " + PREFIX + "work_order(id,order_name,task_id,owner_user_id,lock_version,order_status,current_submission_id) VALUES (8,'复检测试',3,4,1,'PENDING_RECHECK',11)");
        db.update("INSERT INTO " + PREFIX + "work_order_submission(id,work_order_id,revision,submission_type,payload_hash,submitted_by,submitted_at,status,request_key) VALUES(11,8,1,'SOURCE_FIX',repeat('b',64),4,now()-interval '1 hour','ACTIVE','wo:8:sub:1')");
        TenantContextHolder.clear();
        first = new Node();
        second = new Node();
    }

    @AfterEach void cleanup() {
        TenantContextHolder.clear();
        if (beanFactoryInstalled) ReflectionTestUtils.setField(SpringUtil.class, "beanFactory", previousBeanFactory);
        if (platformSource != null) platformSource.close();
        if (created && admin != null) {
            if (!schema.matches("quality_attempt_[a-f0-9]{32}")) throw new IllegalStateException("Invalid owned schema");
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test void committedAttemptIsVisibleBeforeDispatchAndRetriesCreateNewInstances() {
        doAnswer(call -> {
            // 使用另一个数据源连接读取，证明不是未提交事务中的局部可见。
            assertThat(admin.queryForObject("SELECT count(*) FROM " + schema + "." + PREFIX
                    + "work_order_recheck WHERE instance_id=" + call.getArgument(0), Integer.class)).isEqualTo(1);
            assertThat(admin.queryForObject("SELECT count(*) FROM " + schema + "." + PREFIX
                    + "work_order_action", Integer.class)).isPositive();
            return null;
        }).when(first.executor).dispatchGate(any());
        var initial = first.service.recheck(command("first", 1));
        long oldInstance = initial.getCurrentSubmission().getInstanceId();
        assertThat(first.service.recheck(command("first", 1)).getCurrentSubmission().getInstanceId()).isEqualTo(oldInstance);
        verify(first.executor, times(1)).dispatchGate(oldInstance);

        db.update("UPDATE " + PREFIX + "task_instance SET instance_status='FAILED' WHERE id=?", oldInstance);
        second.service.evaluateRechecks();
        assertThat(status()).isEqualTo("PENDING_RECHECK");
        var next = first.service.recheck(command("second", 3));
        assertThat(next.getCurrentSubmission().getInstanceId()).isNotEqualTo(oldInstance);
        assertThat(next.getCurrentSubmission().getRechecks()).extracting("attemptNo").containsExactly(2, 1);
        assertThat(db.queryForList("SELECT status FROM " + PREFIX + "work_order_recheck ORDER BY attempt_no", String.class))
                .containsExactly("ERROR", "RUNNING");
        assertThat(count("task_instance")).isEqualTo(2);
        var oldCommandReplay = first.service.recheck(command("first", 1));
        assertThat(oldCommandReplay.getRecheckAttempt().getInstanceId()).isEqualTo(oldInstance);
        assertThat(oldCommandReplay.getCurrentSubmission().getInstanceId()).isEqualTo(next.getCurrentSubmission().getInstanceId());
        assertThat(count("task_instance")).isEqualTo(2);
    }

    @Test void actionWriteFailureRollsBackInstanceAttemptAndOrderWithoutDispatch() {
        db.execute("ALTER TABLE " + PREFIX + "work_order_action ADD CONSTRAINT fixture_reject CHECK(request_id <> 'reject')");
        assertThatThrownBy(() -> first.service.recheck(command("reject", 1))).isInstanceOf(RuntimeException.class);
        assertThat(count("task_instance")).isZero();
        assertThat(count("work_order_recheck")).isZero();
        assertThat(status()).isEqualTo("PENDING_RECHECK");
        verify(first.executor, never()).dispatchGate(any());
        first.service.recheck(command("retry-after-rollback", 1));
        assertThat(first.rechecks.selectBySubmission(11L)).extracting("attemptNo").containsExactly(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE_FIX", "PLATFORM_REPAIR", "EXPLAIN", "EXCEPTION"})
    void resolutionTypesPersistAndAnotherNodeReplaysTheSameReceipt(String type) {
        db.update("UPDATE " + PREFIX + "work_order SET order_status='IN_PROGRESS' WHERE id=8");
        var req = command("submission-" + type, 1);
        req.setSubmissionType(type);
        req.setContent("书面整改依据");
        req.setIssueIds(List.of(Long.MAX_VALUE));
        for (var node : List.of(first, second)) {
            when(node.relations.selectListByWorkOrderId(8L)).thenReturn(List.of(QualityWorkOrderIssueDO.builder().issueId(Long.MAX_VALUE).build()));
            when(node.issues.selectById(Long.MAX_VALUE)).thenReturn(QualityIssueDataDO.builder().id(Long.MAX_VALUE).objectId(5L).build());
        }
        if ("PLATFORM_REPAIR".equals(type)) {
            req.setRepairOrderId(31L);
            when(first.repairs.selectById(31L)).thenReturn(QualityRepairOrderDO.builder().id(31L)
                    .objectId(5L).approveStatus("PASSED").execStatus("SUCCESS").build());
        }
        if ("EXCEPTION".equals(type)) {
            req.setExceptionScope("机构甲");
            req.setExceptionExpireAt(LocalDateTime.now().plusDays(1).withNano(0));
        }
        var result = first.service.submitResolution(req);
        var replay = second.service.submitResolution(req);
        assertThat(replay.getCurrentSubmissionId()).isEqualTo(result.getCurrentSubmissionId());
        assertThat(count("work_order_submission")).isEqualTo(2);
        assertThat(count("work_order_action")).isEqualTo(1);
        assertThat(count("task_instance")).isZero();
        assertThat(db.queryForObject("SELECT issue_ids->>0 FROM " + PREFIX + "work_order_submission WHERE id=?", String.class,
                result.getCurrentSubmissionId())).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(db.queryForObject("SELECT status FROM " + PREFIX + "work_order_submission WHERE id=11", String.class))
                .isEqualTo("SUPERSEDED");
        assertThat(result.getCurrentSubmission().getContent()).isEqualTo(req.getContent());
        if ("EXPLAIN".equals(type) || "EXCEPTION".equals(type)) {
            assertThat(status()).isEqualTo("PENDING_CONFIRMATION");
            when(second.guard.requireUserId()).thenReturn(9L);
            var accept = command("accept-" + type, 2);
            accept.setSubmissionId(result.getCurrentSubmissionId());
            accept.setConfirmAction("ACCEPT_" + type);
            var closed = second.service.confirm(accept);
            assertThat(closed.getCloseType()).isEqualTo("EXPLAIN".equals(type) ? "EXPLAINED" : "EXCEPTION_ACCEPTED");
            if ("EXCEPTION".equals(type)) {
                assertThat(closed.getCurrentSubmission().getExceptionScope()).isEqualTo("机构甲");
                assertThat(closed.getCurrentSubmission().getExceptionExpireAt()).isEqualTo(req.getExceptionExpireAt());
            }
        } else {
            assertThat(status()).isEqualTo("PENDING_RECHECK");
        }
    }

    @Test void failedSubmissionAuditRollsBackSupersedingAndNewSubmission() {
        db.update("UPDATE " + PREFIX + "work_order SET order_status='IN_PROGRESS' WHERE id=8");
        db.execute("ALTER TABLE " + PREFIX + "work_order_action ADD CONSTRAINT fixture_reject_submission CHECK(request_id <> 'reject-submit')");
        var req = command("reject-submit", 1);
        req.setSubmissionType("EXPLAIN"); req.setContent("说明");
        assertThatThrownBy(() -> first.service.submitResolution(req)).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fixture_reject_submission");
        assertThat(status()).isEqualTo("IN_PROGRESS");
        assertThat(count("work_order_submission")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM " + PREFIX + "work_order_submission WHERE id=11", String.class)).isEqualTo("ACTIVE");
        assertThat(count("work_order_action")).isZero();
    }

    @Test void returnPersistsReasonAndNotificationAtomicallyAndReplaysAcrossNodes() {
        db.update("UPDATE " + PREFIX + "work_order SET order_status='PENDING_CONFIRMATION',confirmation_pending_type='EXPLAIN' WHERE id=8");
        db.update("UPDATE " + PREFIX + "work_order_submission SET submission_type='EXPLAIN',content='说明' WHERE id=11");
        when(first.guard.requireUserId()).thenReturn(9L);
        when(second.guard.requireUserId()).thenReturn(9L);
        var req = command("return-submission", 1); req.setConfirmAction("RETURN");
        assertThatThrownBy(() -> first.service.confirm(req)).isInstanceOf(RuntimeException.class);
        assertThat(count("work_order_action")).isZero();
        req.setReason("请补充可核验依据");
        first.service.confirm(req); second.service.confirm(req);
        assertThat(status()).isEqualTo("IN_PROGRESS");
        assertThat(count("work_order_action")).isEqualTo(1);
        assertThat(count("work_order_notify")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT reason FROM " + PREFIX + "work_order_action", String.class)).isEqualTo(req.getReason());
        assertThat(db.queryForObject("SELECT close_type FROM " + PREFIX + "work_order WHERE id=8", String.class)).isNull();
        verify(first.notifications, never()).sendSingleMessageToAdmin(any());
        verify(second.notifications, never()).sendSingleMessageToAdmin(any());
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo("PENDING");
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(admin.queryForObject("SELECT count(*) FROM " + schema + "." + PREFIX + "work_order_action", Integer.class)).isEqualTo(1);
            return CommonResult.success(100L);
        }).when(second.notifications).sendSingleMessageToAdmin(any());
        second.service.dispatchDueNotifies();
        second.service.dispatchDueNotifies();
        verify(second.notifications, times(1)).sendSingleMessageToAdmin(any());
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo("SENT");
    }

    @Test void returnAuditFailureRollsBackStateAndNotification() {
        db.update("UPDATE " + PREFIX + "work_order SET order_status='PENDING_CONFIRMATION',confirmation_pending_type='EXPLAIN' WHERE id=8");
        db.execute("ALTER TABLE " + PREFIX + "work_order_action ADD CONSTRAINT fixture_reject_return CHECK(request_id <> 'reject-return')");
        when(first.guard.requireUserId()).thenReturn(9L);
        var req = command("reject-return", 1); req.setConfirmAction("RETURN"); req.setReason("补充材料");
        assertThatThrownBy(() -> first.service.confirm(req)).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("fixture_reject_return");
        assertThat(status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(count("work_order_notify")).isZero();
        assertThat(count("work_order_action")).isZero();
        verify(first.notifications, never()).sendSingleMessageToAdmin(any());
        second.service.dispatchDueNotifies();
        verify(second.notifications, never()).sendSingleMessageToAdmin(any());
    }

    @Test void pausedLegacyEditCannotOverwriteAnotherNodesConfirmedSubmission() throws Exception {
        db.update("UPDATE " + PREFIX + "work_order SET order_status='IN_PROGRESS',current_submission_id=null WHERE id=8");
        db.update("INSERT INTO " + PREFIX + "issue_data(id) VALUES(30),(31)");
        db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(8,30)");
        var editing = new Node(true);
        var confirming = new Node(true);
        var authorized = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        doAnswer(call -> {
            authorized.countDown();
            if (!resume.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Edit resume timeout");
            return null;
        }).when(editing.guard).assertHandle(any(), any());
        var edit = new QualityWorkOrderSaveReqVO();
        edit.setId(8L); edit.setOrderName("过时编辑"); edit.setIssueIds(List.of(31L));
        edit.setExpectedVersion(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Throwable> outcome = pool.submit(() -> {
                TenantContextHolder.clear();
                try { editing.service.updateWorkOrder(edit); return null; }
                catch (Throwable failure) { return failure; }
                finally { TenantContextHolder.clear(); }
            });
            try {
                assertThat(authorized.await(3, TimeUnit.SECONDS)).isTrue();
                var submit = command("edit-race-submit", 1);
                submit.setSubmissionType("EXPLAIN"); submit.setContent("已核验的说明"); submit.setIssueIds(List.of(30L));
                var submitted = confirming.service.submitResolution(submit);
                when(confirming.guard.requireUserId()).thenReturn(9L);
                var accept = command("edit-race-confirm", 2);
                accept.setSubmissionId(submitted.getCurrentSubmissionId()); accept.setConfirmAction("ACCEPT_EXPLAIN");
                confirming.service.confirm(accept);
            } finally { resume.countDown(); }
            assertThat(outcome.get(8, TimeUnit.SECONDS)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        }
        assertThat(status()).isEqualTo("PROCESSED");
        assertThat(db.queryForObject("SELECT order_name FROM " + PREFIX + "work_order WHERE id=8", String.class)).isEqualTo("复检测试");
        assertThat(db.queryForList("SELECT issue_id FROM " + PREFIX + "work_order_issue WHERE work_order_id=8 AND deleted=0", Long.class)).containsExactly(30L);
        assertThat(db.queryForObject("SELECT lock_version FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isEqualTo(3);
        assertThat(count("work_order_action")).isEqualTo(2);
    }

    @Test void successfulLegacyEditAdvancesVersionAndChangesRealRelationsAtomically() {
        db.update("UPDATE " + PREFIX + "work_order SET order_status='IN_PROGRESS',current_submission_id=null WHERE id=8");
        db.update("INSERT INTO " + PREFIX + "issue_data(id) VALUES(30),(31)");
        db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(8,30)");
        var editing = new Node(true);
        var edit = new QualityWorkOrderSaveReqVO();
        edit.setId(8L); edit.setOrderName("有效编辑"); edit.setIssueIds(List.of(31L, 31L));
        edit.setExpectedVersion(1);
        editing.service.updateWorkOrder(edit);
        assertThat(db.queryForObject("SELECT order_name FROM " + PREFIX + "work_order WHERE id=8", String.class)).isEqualTo("有效编辑");
        assertThat(db.queryForObject("SELECT lock_version FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT issue_count FROM " + PREFIX + "work_order WHERE id=8", Long.class)).isEqualTo(1L);
        assertThat(db.queryForList("SELECT issue_id FROM " + PREFIX + "work_order_issue WHERE work_order_id=8 AND deleted=0", Long.class)).containsExactly(31L);
        var staleSubmit = command("stale-after-edit", 1); staleSubmit.setSubmissionType("EXPLAIN"); staleSubmit.setContent("说明");
        assertThatThrownBy(() -> second.service.submitResolution(staleSubmit)).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode());
        assertThat(count("work_order_action")).isZero();
    }

    @Test void staleLegacyEditOpenedBeforeAnotherSaveCannotOverwriteItsNameOrRelations() {
        legacyEditFixture(); var author = new Node(true); var staleClient = new Node(true);
        int openedVersion = staleClient.service.getWorkOrder(8L).getLockVersion();
        author.service.updateWorkOrder(legacyEdit("另一人新版本", openedVersion, 31L));
        var stale = legacyEdit("打开很久的旧内容", openedVersion, 30L);
        assertThatThrownBy(() -> staleClient.service.updateWorkOrder(stale)).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode());
        assertLegacyEdit("另一人新版本", 2, 31L);
        assertThat(stale.getOrderName()).isEqualTo("打开很久的旧内容"); assertThat(stale.getExpectedVersion()).isEqualTo(1);
        // 用户明确重新读取并确认后，带新版本的独立请求可以修改；服务不擅自替客户端换版本。
        var refreshed = staleClient.service.getWorkOrder(8L);
        staleClient.service.updateWorkOrder(legacyEdit("已比较确认的内容", refreshed.getLockVersion(), 30L));
        assertLegacyEdit("已比较确认的内容", 3, 30L);
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING", "NEGATIVE", "STALE", "FUTURE", "OVERFLOW"})
    void legacyEditRejectsMissingOrUnboundClientVersionBeforeAnyWrites(String variant) {
        legacyEditFixture(); var node = new Node(true);
        Integer version = switch (variant) {
            case "MISSING" -> null;
            case "NEGATIVE" -> -1;
            case "STALE" -> 0;
            case "FUTURE" -> 2;
            case "OVERFLOW" -> Integer.MAX_VALUE;
            default -> throw new IllegalArgumentException(variant);
        };
        assertThatThrownBy(() -> node.service.updateWorkOrder(legacyEdit("拒绝的旧编辑", version, 31L))).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode());
        assertLegacyEdit("复检测试", 1, 30L);
    }

    @Test void zeroVersionIsValidAndNotTreatedAsMissingByLegacyEdit() {
        legacyEditFixture(); db.update("UPDATE " + PREFIX + "work_order SET lock_version=0 WHERE id=8");
        new Node(true).service.updateWorkOrder(legacyEdit("零版本编辑", 0, 31L)); assertLegacyEdit("零版本编辑", 1, 31L);
    }

    @Test void legacyEditCannotOverflowVersionAfterLastRepresentableAdvance() {
        legacyEditFixture(); db.update("UPDATE " + PREFIX + "work_order SET lock_version=? WHERE id=8", Integer.MAX_VALUE - 1);
        var node = new Node(true); node.service.updateWorkOrder(legacyEdit("最后有效版本", Integer.MAX_VALUE - 1, 31L));
        assertLegacyEdit("最后有效版本", Integer.MAX_VALUE, 31L);
        assertThatThrownBy(() -> node.service.updateWorkOrder(legacyEdit("不能溢出", Integer.MAX_VALUE, 30L))).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode());
        assertLegacyEdit("最后有效版本", Integer.MAX_VALUE, 31L);
    }

    @RepeatedTest(3) void twoLegacyEditorsWithSameOpenedVersionCommitOnlyOneCompleteEdit() throws Exception {
        legacyEditFixture(); var nodes = List.of(new Node(true), new Node(true));
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        for (var node : nodes) doAnswer(call -> {
            ready.countDown(); if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("editor start timeout");
            return null;
        }).when(node.guard).assertHandle(any(), any());
        var requests = List.of(legacyEdit("编辑A", 1, 30L), legacyEdit("编辑B", 1, 31L));
        var workers = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<Future<Throwable>>();
            for (int i = 0; i < 2; i++) {
                var node = nodes.get(i); var request = requests.get(i);
                futures.add(workers.submit(() -> {
                    TenantContextHolder.clear();
                    try { return catchThrowable(() -> node.service.updateWorkOrder(request)); } finally { TenantContextHolder.clear(); }
                }));
            }
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var outcomes = new ArrayList<Throwable>(); for (var future : futures) outcomes.add(future.get(10, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(Objects::isNull).hasSize(1);
            var failure = outcomes.stream().filter(Objects::nonNull).findFirst().orElseThrow();
            assertThat(failure).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class)
                    .extracting("code").isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode());
            int winner = outcomes.get(0) == null ? 0 : 1;
            assertLegacyEdit(requests.get(winner).getOrderName(), 2, requests.get(winner).getIssueIds().getFirst());
        } finally { start.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void legacyEditClientVersionDoesNotReplaceObjectPermission() {
        legacyEditFixture(); var node = new Node(true);
        TenantContextHolder.clear();
        doThrow(cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_NOT_EXISTS))
                .when(node.guard).assertHandle(any(), any());
        assertThatThrownBy(() -> node.service.updateWorkOrder(legacyEdit("无处理范围", 1, 31L))).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_NOT_EXISTS.getCode());
        assertLegacyEdit("复检测试", 1, 30L);
    }

    private void legacyEditFixture() {
        db.update("UPDATE " + PREFIX + "work_order SET order_status='IN_PROGRESS',current_submission_id=null WHERE id=8");
        db.update("INSERT INTO " + PREFIX + "issue_data(id) VALUES(30),(31)");
        db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(8,30)");
    }

    private QualityWorkOrderSaveReqVO legacyEdit(String name, Integer version, long issueId) {
        var request = new QualityWorkOrderSaveReqVO(); request.setId(8L); request.setOrderName(name);
        request.setExpectedVersion(version); request.setIssueIds(List.of(issueId)); return request;
    }

    private void assertLegacyEdit(String name, int version, long issueId) {
        assertThat(db.queryForObject("SELECT order_name FROM " + PREFIX + "work_order WHERE id=8", String.class)).isEqualTo(name);
        assertThat(db.queryForObject("SELECT lock_version FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isEqualTo(version);
        assertThat(db.queryForList("SELECT issue_id FROM " + PREFIX + "work_order_issue WHERE work_order_id=8 AND deleted=0", Long.class))
                .containsExactly(issueId);
        assertThat(count("work_order_action")).isZero(); assertThat(count("work_order_notify")).isZero();
    }

    @Test void zeroRowLegacyEditKeepsOriginalOrderAndRelations() {
        db.update("UPDATE " + PREFIX + "work_order SET order_status='IN_PROGRESS',current_submission_id=null WHERE id=8");
        db.update("INSERT INTO " + PREFIX + "issue_data(id) VALUES(30),(31)");
        db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(8,30)");
        db.execute("CREATE FUNCTION ignore_edit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$");
        db.execute("CREATE TRIGGER ignore_edit BEFORE UPDATE ON " + PREFIX + "work_order FOR EACH ROW EXECUTE FUNCTION ignore_edit()");
        var editing = new Node(true);
        var edit = new QualityWorkOrderSaveReqVO(); edit.setId(8L); edit.setOrderName("不能落库"); edit.setIssueIds(List.of(31L));
        edit.setExpectedVersion(1);
        assertThatThrownBy(() -> editing.service.updateWorkOrder(edit)).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode());
        assertThat(db.queryForObject("SELECT order_name FROM " + PREFIX + "work_order WHERE id=8", String.class)).isEqualTo("复检测试");
        assertThat(db.queryForObject("SELECT lock_version FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isEqualTo(1);
        assertThat(db.queryForList("SELECT issue_id FROM " + PREFIX + "work_order_issue WHERE work_order_id=8 AND deleted=0", Long.class)).containsExactly(30L);
    }

    @Test void notifyDispatchRejectsOuterTransactionAndCannotSendItsUncommittedIntent() {
        db.update("UPDATE " + PREFIX + "work_order SET order_status='PENDING_CONFIRMATION',confirmation_pending_type='EXPLAIN' WHERE id=8");
        when(first.guard.requireUserId()).thenReturn(9L);
        var req = command("outer-rollback-return", 1); req.setConfirmAction("RETURN"); req.setReason("补充材料");
        new TransactionTemplate(first.transactions).executeWithoutResult(transaction -> {
            first.service.confirm(req);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThatThrownBy(() -> first.service.dispatchDueNotifies())
                    .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            verify(first.notifications, never()).sendSingleMessageToAdmin(any());
            transaction.setRollbackOnly();
        });
        assertThat(count("work_order_notify")).isZero(); assertThat(count("work_order_action")).isZero();
        assertThat(status()).isEqualTo("PENDING_CONFIRMATION");
        second.service.dispatchDueNotifies();
        verify(second.notifications, never()).sendSingleMessageToAdmin(any());
    }

    @Test void relationInsertFailureRollsBackLegacyEditVersionAndPreviousBinding() {
        db.update("UPDATE " + PREFIX + "work_order SET order_status='IN_PROGRESS',current_submission_id=null WHERE id=8");
        db.update("INSERT INTO " + PREFIX + "issue_data(id) VALUES(30),(31)");
        db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(8,30)");
        db.execute("ALTER TABLE " + PREFIX + "work_order_issue ADD CONSTRAINT reject_edit_relation CHECK(issue_id <> 31)");
        var edit = new QualityWorkOrderSaveReqVO(); edit.setId(8L); edit.setOrderName("必须回滚"); edit.setIssueIds(List.of(31L));
        edit.setExpectedVersion(1);
        var editing = new Node(true);
        assertThatThrownBy(() -> editing.service.updateWorkOrder(edit)).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("reject_edit_relation");
        assertThat(db.queryForObject("SELECT order_name FROM " + PREFIX + "work_order WHERE id=8", String.class)).isEqualTo("复检测试");
        assertThat(db.queryForObject("SELECT lock_version FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isEqualTo(1);
        assertThat(db.queryForList("SELECT issue_id FROM " + PREFIX + "work_order_issue WHERE work_order_id=8 AND deleted=0", Long.class)).containsExactly(30L);
    }

    @ParameterizedTest @ValueSource(strings = {"BUSINESS", "PARAM_MISSING", "GENERIC_ERROR", "TRANSPORT", "EMPTY_RECEIPT", "ZERO_RECEIPT", "NEGATIVE_RECEIPT"})
    void notifyFailureKeepsCommittedIntentAndRetriesWithSameIdempotencyKey(String failure) {
        db.update("UPDATE " + PREFIX + "work_order SET order_status='PENDING_CONFIRMATION',confirmation_pending_type='EXPLAIN' WHERE id=8");
        when(first.guard.requireUserId()).thenReturn(9L);
        var req = command("notify-failure-" + failure, 1); req.setConfirmAction("RETURN"); req.setReason("补充材料");
        first.service.confirm(req);
        if ("BUSINESS".equals(failure)) {
            when(second.notifications.sendSingleMessageToAdmin(any())).thenReturn(CommonResult.error(
                    cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.NOTICE_NOT_FOUND));
        } else if ("PARAM_MISSING".equals(failure)) {
            when(second.notifications.sendSingleMessageToAdmin(any())).thenReturn(CommonResult.error(
                    cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.NOTIFY_SEND_TEMPLATE_PARAM_MISS));
        } else if ("GENERIC_ERROR".equals(failure)) {
            when(second.notifications.sendSingleMessageToAdmin(any())).thenReturn(CommonResult.error(500, "响应可能晚于远端提交"));
        } else if ("TRANSPORT".equals(failure)) {
            when(second.notifications.sendSingleMessageToAdmin(any())).thenThrow(new IllegalStateException("测试投递断开"));
        } else {
            Long receipt = switch (failure) {
                case "ZERO_RECEIPT" -> 0L;
                case "NEGATIVE_RECEIPT" -> -1L;
                default -> null;
            };
            when(second.notifications.sendSingleMessageToAdmin(any())).thenReturn(CommonResult.success(receipt));
        }
        second.service.dispatchDueNotifies();
        second.service.dispatchDueNotifies();
        verify(second.notifications, times(1)).sendSingleMessageToAdmin(any());
        assertThat(status()).isEqualTo("IN_PROGRESS");
        assertThat(count("work_order_action")).isEqualTo(1);
        boolean knownNoSend = "BUSINESS".equals(failure) || "PARAM_MISSING".equals(failure) || "EMPTY_RECEIPT".equals(failure);
        String expected = knownNoSend ? "FAILED" : "UNKNOWN";
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo(expected);
        // FAILED 与 UNKNOWN 都计入自动重试次数并退避；退避期内第二次扫描不再发送
        assertThat(db.queryForObject("SELECT retry_count FROM " + PREFIX + "work_order_notify", Integer.class)).isEqualTo(1);
        db.update("UPDATE " + PREFIX + "work_order_notify SET next_retry_time=now()-interval '1 second'");
        first.service.dispatchDueNotifies();
        // 通知服务按幂等键去重：结果未知也可安全重试，远端已创建时只会返回原消息编号
        verify(first.notifications, times(1)).sendSingleMessageToAdmin(any());
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo("SENT");
        var firstAttempt = org.mockito.ArgumentCaptor.forClass(
                cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO.class);
        var retryAttempt = org.mockito.ArgumentCaptor.forClass(
                cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO.class);
        verify(second.notifications).sendSingleMessageToAdmin(firstAttempt.capture());
        verify(first.notifications).sendSingleMessageToAdmin(retryAttempt.capture());
        Long notifyId = db.queryForObject("SELECT id FROM " + PREFIX + "work_order_notify", Long.class);
        assertThat(firstAttempt.getValue().getIdempotencyKey())
                .isEqualTo("governance:quality-work-order-notify:" + notifyId)
                .isEqualTo(retryAttempt.getValue().getIdempotencyKey());
    }

    @Test void twoApplicationInstancesReplayOneRequestIntoOneDurableRun() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Long> left = concurrent(first, "concurrent", ready, start);
            Callable<Long> right = concurrent(second, "concurrent", ready, start);
            Future<Long> a = pool.submit(left);
            Future<Long> b = pool.submit(right);
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
        }
        assertThat(count("task_instance")).isEqualTo(1);
        assertThat(count("work_order_recheck")).isEqualTo(1);
        assertThat(count("work_order_action")).isEqualTo(1);
    }

    @RepeatedTest(3) void overlappingNotifyWorkersDoNotSendTheSameIntentTwice() throws Exception {
        db.update("INSERT INTO " + PREFIX + "work_order_notify(work_order_id,event_type,receiver_user_id,time_slice,send_status,next_retry_time) VALUES(8,'RETURN',4,'overlap','PENDING',now()-interval '1 minute')");
        var sending = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            sending.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Notify release timeout");
            return CommonResult.success(100L);
        }).when(first.notifications).sendSingleMessageToAdmin(any());
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> running = pool.submit(() -> {
                TenantContextHolder.clear();
                try { first.service.dispatchDueNotifies(); } finally { TenantContextHolder.clear(); }
            });
            try {
                assertThat(sending.await(3, TimeUnit.SECONDS)).isTrue();
                second.service.dispatchDueNotifies();
                verify(second.notifications, never()).sendSingleMessageToAdmin(any());
            } finally { release.countDown(); }
            running.get(8, TimeUnit.SECONDS);
        }
        verify(first.notifications, times(1)).sendSingleMessageToAdmin(any());
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo("SENT");
    }

    @Test void lostNotifyResponseIsUnknownThenRetriedWithTheSameIdempotencyKey() {
        db.update("INSERT INTO " + PREFIX + "work_order_notify(work_order_id,event_type,receiver_user_id,time_slice,send_status,next_retry_time) VALUES(8,'RETURN',4,'response-lost','PENDING',now()-interval '1 minute')");
        when(first.notifications.sendSingleMessageToAdmin(any())).thenThrow(new IllegalStateException("远端可能已创建消息，回执丢失"));
        first.service.dispatchDueNotifies();
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo("UNKNOWN");
        second.service.dispatchDueNotifies();
        verify(second.notifications, never()).sendSingleMessageToAdmin(any());
        db.update("UPDATE " + PREFIX + "work_order_notify SET next_retry_time=now()-interval '1 day'");
        second.service.dispatchDueNotifies();
        var lost = org.mockito.ArgumentCaptor.forClass(
                cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO.class);
        var retried = org.mockito.ArgumentCaptor.forClass(
                cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO.class);
        verify(first.notifications, times(1)).sendSingleMessageToAdmin(lost.capture());
        verify(second.notifications, times(1)).sendSingleMessageToAdmin(retried.capture());
        assertThat(retried.getValue().getIdempotencyKey()).isNotBlank().isEqualTo(lost.getValue().getIdempotencyKey());
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo("SENT");
    }

    @Test void exhaustedNotifyStopsAutomaticDeliveryAndAwaitsManualReview() {
        db.update("INSERT INTO " + PREFIX + "work_order_notify(work_order_id,event_type,receiver_user_id,time_slice,send_status,retry_count,next_retry_time) VALUES(8,'RETURN',4,'exhausted','FAILED',7,now()-interval '1 minute')");
        when(second.notifications.sendSingleMessageToAdmin(any())).thenReturn(CommonResult.error(
                cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.NOTICE_NOT_FOUND));
        second.service.dispatchDueNotifies();
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo("EXHAUSTED");
        assertThat(db.queryForObject("SELECT retry_count FROM " + PREFIX + "work_order_notify", Integer.class)).isEqualTo(8);
        db.update("UPDATE " + PREFIX + "work_order_notify SET next_retry_time=now()-interval '1 day'");
        first.service.dispatchDueNotifies();
        second.service.dispatchDueNotifies();
        verify(second.notifications, times(1)).sendSingleMessageToAdmin(any());
        verifyNoInteractions(first.notifications);
    }

    @Test void ignoredNotifyCompletionWriteFailsAndDoesNotLeaveRetryableIntent() {
        db.update("INSERT INTO " + PREFIX + "work_order_notify(work_order_id,event_type,receiver_user_id,time_slice,send_status,next_retry_time) VALUES(8,'RETURN',4,'receipt-write-lost','PENDING',now()-interval '1 minute')");
        db.execute("CREATE FUNCTION ignore_sent() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.send_status='SENT' THEN RETURN NULL; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER ignore_sent BEFORE UPDATE ON " + PREFIX + "work_order_notify FOR EACH ROW EXECUTE FUNCTION ignore_sent()");
        assertThatThrownBy(() -> first.service.dispatchDueNotifies()).isInstanceOf(RuntimeException.class);
        second.service.dispatchDueNotifies();
        verify(first.notifications, times(1)).sendSingleMessageToAdmin(any());
        verify(second.notifications, never()).sendSingleMessageToAdmin(any());
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo("SENDING");
        assertThat(db.queryForObject("SELECT claim_token IS NOT NULL AND lease_until IS NOT NULL FROM " + PREFIX + "work_order_notify", Boolean.class)).isTrue();
        db.update("UPDATE " + PREFIX + "work_order_notify SET lease_until=now()-interval '1 second'");
        second.service.dispatchDueNotifies();
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo("UNKNOWN");
        verify(second.notifications, never()).sendSingleMessageToAdmin(any());
    }

    @Test void expiredUnfinishedClaimBecomesUnknownAndIsRetriedOnlyAfterBackoff() {
        db.update("INSERT INTO " + PREFIX + "work_order_notify(work_order_id,event_type,receiver_user_id,time_slice,send_status,claim_token,lease_until,next_retry_time) VALUES(8,'RETURN',4,'expired-claim','SENDING','orphaned-claim',now()-interval '1 second',now()-interval '1 minute')");
        second.service.dispatchDueNotifies();
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo("UNKNOWN");
        assertThat(db.queryForObject("SELECT claim_token IS NULL AND lease_until IS NULL FROM " + PREFIX + "work_order_notify", Boolean.class)).isTrue();
        assertThat(db.queryForObject("SELECT retry_count FROM " + PREFIX + "work_order_notify", Integer.class)).isEqualTo(1);
        // 租约过期的同一次扫描不立即重发，避免与仍在运行的旧工作者撞车
        verifyNoInteractions(second.notifications);
        db.update("UPDATE " + PREFIX + "work_order_notify SET next_retry_time=now()-interval '1 day'");
        first.service.dispatchDueNotifies();
        verify(first.notifications, times(1)).sendSingleMessageToAdmin(any());
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo("SENT");
    }

    @Test void notificationDispatchWorksWithoutTenantContext() {
        db.update("INSERT INTO " + PREFIX + "work_order_notify(work_order_id,event_type,receiver_user_id,time_slice,send_status,next_retry_time) VALUES(8,'RETURN',4,'global-notice','PENDING',now()-interval '1 minute')");
        TenantContextHolder.clear();
        first.service.dispatchDueNotifies();
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify WHERE time_slice='global-notice'", String.class)).isEqualTo("SENT");
    }

    @Test void newlyEnqueuedNotifyIsImmediatelyDueWithUtcPostgresAndShanghaiLocalTimestampContract() throws Exception {
        setPlatformPoolTimeZone("UTC");
        assertThat(db.queryForObject("SELECT current_setting('TimeZone')", String.class)).isEqualTo("UTC");
        db.update("UPDATE " + PREFIX + "work_order SET order_status='PENDING_CONFIRMATION',confirmation_pending_type='EXPLAIN' WHERE id=8");
        when(first.guard.requireUserId()).thenReturn(9L);
        var req = command("utc-enqueue-notify", 1);
        req.setConfirmAction("RETURN");
        req.setReason("需要补充材料");
        first.service.confirm(req);
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo("PENDING");
        second.service.dispatchDueNotifies();
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo("SENT");
        verify(second.notifications, times(1)).sendSingleMessageToAdmin(any());
    }

    private void setPlatformPoolTimeZone(String zone) throws Exception {
        if (!Set.of("UTC", "Asia/Shanghai").contains(zone)) throw new IllegalArgumentException("Unexpected test time zone");
        List<java.sql.Connection> connections = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                var connection = platformSource.getConnection();
                connections.add(connection);
                try (var statement = connection.createStatement()) {
                    statement.execute("SET TIME ZONE '" + zone + "'");
                }
            }
        } finally {
            for (var connection : connections) connection.close();
        }
    }

    private LocalDateTime expiredExceptionFixture() {
        var now = LocalDateTime.now().withNano(0);
        db.update("UPDATE " + PREFIX + "work_order SET order_status='PROCESSED',close_type='EXCEPTION_ACCEPTED',issue_count=2 WHERE id=8");
        db.update("UPDATE " + PREFIX + "work_order_submission SET submission_type='EXCEPTION',exception_expire_at=? WHERE id=11", now.minusDays(1));
        db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(8,30),(8,31)");
        return now;
    }


    @ParameterizedTest @ValueSource(strings = {"STANDARD", "DUPLICATE_LARGE_IDS", "EMPTY_UNASSIGNED"})
    void expiryResultPersistsExactCreationSnapshotAndSurvivesAnotherNode(String variant) {
        var now = expiredExceptionFixture();
        String canonical = "expiry-issues-v1:30,31";
        long expectedCount = 2;
        if (variant.equals("DUPLICATE_LARGE_IDS")) {
            db.update("UPDATE " + PREFIX + "work_order_issue SET issue_id=9007199254740993 WHERE issue_id=31");
            db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(8,30),(8,2)");
            canonical = "expiry-issues-v1:2,30,30,9007199254740993"; expectedCount = 4;
        } else if (variant.equals("EMPTY_UNASSIGNED")) {
            db.update("UPDATE " + PREFIX + "work_order_issue SET deleted=1 WHERE work_order_id=8");
            db.update("UPDATE " + PREFIX + "work_order SET owner_user_id=null WHERE id=8");
            canonical = "expiry-issues-v1:"; expectedCount = 0;
        }
        new Node(true).target.expireExceptions(now);
        var result = db.queryForMap("SELECT * FROM " + PREFIX + "work_order_action");
        long child = db.queryForObject("SELECT id FROM " + PREFIX + "work_order WHERE source_type='EXCEPTION_EXPIRED'", Long.class);
        assertThat(result).containsEntry("follow_up_work_order_id", child)
                .containsEntry("expiry_result_version", 1)
                .containsEntry("expiry_issue_count", expectedCount)
                .containsEntry("expiry_issue_hash", cn.hutool.crypto.digest.DigestUtil.sha256Hex(canonical))
                .containsEntry("expiry_receiver_user_id", variant.equals("EMPTY_UNASSIGNED") ? null : 4L);
        var before = expiryInventoryData(); new Node(true).target.expireExceptions(now);
        assertThat(expiryInventoryData()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"ERROR", "ZERO", "CHILD", "HASH", "COUNT", "RECEIVER"})
    void expiryResultBindingFailureRollsBackAllWritesAndRetryCreatesOneResult(String fault) {
        var now = expiredExceptionFixture(); var node = new Node(true);
        String effect = switch (fault) {
            case "ERROR" -> "RAISE EXCEPTION 'fixture-only-result-binding';";
            case "ZERO" -> "RETURN NULL;";
            case "CHILD" -> "NEW.follow_up_work_order_id=777;";
            case "HASH" -> "NEW.expiry_issue_hash=repeat('d',64);";
            case "COUNT" -> "NEW.expiry_issue_count=7;";
            case "RECEIVER" -> "NEW.expiry_receiver_user_id=7;";

            default -> throw new IllegalArgumentException(fault);
        };
        db.execute("CREATE FUNCTION expiry_result_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + effect + " RETURN NEW; END $$");
        db.execute("CREATE TRIGGER expiry_result_fault BEFORE UPDATE ON " + PREFIX + "work_order_action FOR EACH ROW EXECUTE FUNCTION expiry_result_fault()");
        assertThatThrownBy(() -> node.target.expireExceptions(now)).isInstanceOf(RuntimeException.class);
        assertThat(count("work_order")).isEqualTo(1); assertThat(count("work_order_action")).isZero();
        assertThat(count("work_order_issue")).isEqualTo(2); assertThat(count("work_order_notify")).isZero();
        verifyNoInteractions(node.notifications);
        db.execute("DROP TRIGGER expiry_result_fault ON " + PREFIX + "work_order_action");
        elapseExpiryBackoff();
        node.target.expireExceptions(now); new Node(true).target.expireExceptions(now); assertExpiredFollowUp();
        assertThat(db.queryForObject("SELECT expiry_result_version FROM " + PREFIX + "work_order_action", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"NULL", "ZERO"})
    void expiryResultRequiresGeneratedAuditIdBeforeCreatingChild(String fault) {
        var now = expiredExceptionFixture(); var node = new Node(true); var actions = spy(node.actions);
        doAnswer(call -> { call.<QualityWorkOrderActionDO>getArgument(0).setId(fault.equals("ZERO") ? 0L : null); return 1; })
                .when(actions).insert(any(QualityWorkOrderActionDO.class));
        ReflectionTestUtils.setField(node.target, "actionMapper", actions);
        assertThatThrownBy(() -> node.target.expireExceptions(now)).isInstanceOf(RuntimeException.class);
        assertThat(count("work_order")).isEqualTo(1); assertThat(count("work_order_action")).isZero();
        assertThat(count("work_order_issue")).isEqualTo(2); assertThat(count("work_order_notify")).isZero();
    }

    @Test void expiryResultNeverAdoptsLegacyRootCandidateOrRewritesOldAudit() {
        var now = expiredExceptionFixture(); inventoryAudit("legacy");
        var before = expiryInventoryData(); new Node(true).target.expireExceptions(now);
        assertThat(expiryInventoryData()).isEqualTo(before);
        assertThat(db.queryForObject("SELECT follow_up_work_order_id FROM " + PREFIX + "work_order_action", Long.class)).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"ONLY_RECEIVER", "NO_VERSION", "NO_CHILD", "NO_HASH", "NO_COUNT", "WRONG_ACTION", "VERSION_TWO", "NEGATIVE_COUNT", "SELF_CHILD", "UPPER_HASH"})
    void expiryResultConstraintRejectsPartialOrUnsupportedSnapshots(String variant) {
        inventoryAudit("constraint");
        String values = switch (variant) {
            case "ONLY_RECEIVER" -> "expiry_receiver_user_id=4";
            case "NO_VERSION" -> "follow_up_work_order_id=99,expiry_issue_hash=repeat('a',64),expiry_issue_count=2";
            case "NO_CHILD" -> "expiry_result_version=1,expiry_issue_hash=repeat('a',64),expiry_issue_count=2";
            case "NO_HASH" -> "expiry_result_version=1,follow_up_work_order_id=99,expiry_issue_count=2";
            case "NO_COUNT" -> "expiry_result_version=1,follow_up_work_order_id=99,expiry_issue_hash=repeat('a',64)";
            case "WRONG_ACTION" -> "action_type='RECHECK',expiry_result_version=1,follow_up_work_order_id=99,expiry_issue_hash=repeat('a',64),expiry_issue_count=2";
            case "VERSION_TWO" -> "expiry_result_version=2,follow_up_work_order_id=99,expiry_issue_hash=repeat('a',64),expiry_issue_count=2";
            case "NEGATIVE_COUNT" -> "expiry_result_version=1,follow_up_work_order_id=99,expiry_issue_hash=repeat('a',64),expiry_issue_count=-1";
            case "SELF_CHILD" -> "expiry_result_version=1,follow_up_work_order_id=8,expiry_issue_hash=repeat('a',64),expiry_issue_count=2";
            case "UPPER_HASH" -> "expiry_result_version=1,follow_up_work_order_id=99,expiry_issue_hash=repeat('A',64),expiry_issue_count=2";
            default -> throw new IllegalArgumentException(variant);
        };
        assertThatThrownBy(() -> db.update("UPDATE " + PREFIX + "work_order_action SET " + values))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void singleTenantMigrationReplayPreservesExpiryReceiptAndBinding() throws Exception {
        var now = expiredExceptionFixture(); new Node(true).target.expireExceptions(now);
        var receipt = expiryInventoryData(); var structure = expiryBindingStructure();
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(db, schema);
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(db, schema);
        assertThat(expiryInventoryData()).isEqualTo(receipt);
        assertThat(expiryBindingStructure()).isEqualTo(structure);
    }

    private String expiryBindingStructure() {
        return db.queryForObject("SELECT jsonb_build_object('columns', (SELECT jsonb_agg(jsonb_build_object('name',a.attname,'type',format_type(a.atttypid,a.atttypmod),'not_null',a.attnotnull,'comment',col_description(a.attrelid,a.attnum)) ORDER BY a.attname) FROM pg_catalog.pg_attribute a WHERE a.attrelid='" + PREFIX + "work_order_action'::regclass AND (a.attname LIKE 'expiry_%' OR a.attname='follow_up_work_order_id')), 'constraint', (SELECT pg_get_constraintdef(c.oid) FROM pg_catalog.pg_constraint c WHERE c.conrelid='" + PREFIX + "work_order_action'::regclass AND c.conname='ck_quality_expiry_result'), 'index',pg_get_indexdef('uk_quality_expiry_result_target'::regclass))::text", String.class);
    }

    @Test void expiryResultBaselineIndexAndConstraintMatchIncrementalUpgrade() throws Exception {
        var upgraded = expiryBindingStructure();
        String baseline = Files.readString(repoFile("sql/postgresql/datahub-center.sql"));
        var index = Pattern.compile("CREATE UNIQUE INDEX IF NOT EXISTS \"uk_quality_expiry_result_target\"[\\s\\S]*?;").matcher(baseline);
        assertThat(index.find()).isTrue(); db.execute("DROP INDEX uk_quality_expiry_result_target");
        db.execute("ALTER TABLE " + PREFIX + "work_order_action ADD COLUMN tenant_id bigint DEFAULT 1");
        db.execute(index.group().replace("\"datahub-center\"", schema));
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(db, schema);
        assertThat(expiryBindingStructure()).isEqualTo(upgraded);
    }

    @Test void expiryResultTargetCannotBeReusedGloballyEvenAfterLogicalDelete() {
        long firstId = inventoryAudit("first"); long secondId = inventoryAudit("second");
        db.update("UPDATE " + PREFIX + "work_order_action SET expiry_result_version=1,follow_up_work_order_id=99,expiry_issue_hash=repeat('a',64),expiry_issue_count=0,deleted=1 WHERE id=?", firstId);
        assertThatThrownBy(() -> db.update("UPDATE " + PREFIX + "work_order_action SET expiry_result_version=1,follow_up_work_order_id=99,expiry_issue_hash=repeat('a',64),expiry_issue_count=0 WHERE id=?", secondId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void expiryInventoryEmptyPageKeepsExplicitBoundaryAndReadOnlyScope() throws Exception {
        var report = expiryInventory( 0, 0, 100);
        assertThat(report).containsEntry("through_action_id", "0").containsEntry("next_after_action_id", "0")
                .containsEntry("scanned_count", 0).containsEntry("has_more", false)
                .containsEntry("transaction_read_only", "on").containsEntry("transaction_isolation", "repeatable read")
                .containsEntry("snapshot_scope", "THIS_PAGE_ONLY");
        assertThat(inventoryRows(report)).isEmpty();
    }

    @Test void expiryInventoryCompleteLookingCandidateIsNeverBindingProofAndNeverWrites() throws Exception {
        var now = expiredExceptionFixture(); new Node(true).target.expireExceptions(now);
        var before = expiryInventoryData(); var report = expiryInventory( 0, 0, 100);
        assertThat(report).containsEntry("report_version", 2)
                .containsEntry("binding_proof", "DIRECT_RESULT_V1_OR_UNPROVEN_LEGACY");
        var row = inventoryRows(report).getFirst();
        assertThat(row).containsEntry("candidate_binding", "UNPROVEN").containsEntry("candidate_count", 1)
                .containsEntry("submission_missing", false).containsEntry("submission_wrong_source", false);
        var candidate = inventoryCandidates(row).getFirst();
        assertThat(candidate).containsEntry("binding", "UNPROVEN").containsEntry("current_source_relations_match", true)
                .containsEntry("current_relation_count", 2).containsEntry("current_issue_count_matches", true)
                .containsEntry("notify_intent_count", 1).containsEntry("live_notify_intent_count", 1);
        assertThat(candidate.get("live_notify_status_counts")).isEqualTo(Map.of("PENDING", 1));
        assertThat(expiryInventoryData()).isEqualTo(before);
        expiryInventory( 0, 0, 100); assertThat(expiryInventoryData()).isEqualTo(before);
    }

    @Test void expiryInventoryAuditWithoutFollowUpReportsMissingCandidateWithoutRepair() throws Exception {
        expiredExceptionFixture(); inventoryAudit("audit-only"); var before = expiryInventoryData();
        var row = inventoryRows(expiryInventory( 0, 0, 100)).getFirst();
        assertThat(row).containsEntry("candidate_binding", "NO_SAME_ROOT_CANDIDATE").containsEntry("candidate_count", 0);
        assertThat(row).containsEntry("result_binding_status", "LEGACY_UNPROVEN").containsEntry("direct_result", null);
        assertThat(inventoryCandidates(row)).isEmpty(); assertThat(expiryInventoryData()).isEqualTo(before);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inventoryDirect(Map<String, Object> row) { return (Map<String, Object>) row.get("direct_result"); }

    @ParameterizedTest @ValueSource(strings = {"EXACT", "DUPLICATE_LARGE_IDS", "SOURCE_CHANGED", "RELATIONS_CHANGED", "SNAPSHOT_COUNT", "SNAPSHOT_HASH", "OWNER_CHANGED", "MISSING_NOTIFY", "DELETED_NOTIFY", "MISSING_TARGET", "DELETED_TARGET", "WRONG_TYPE", "ROOT_CHANGED"})
    void expiryInventoryDirectReferenceUsesFrozenMultisetAndOriginalReceiverWithoutClaimingSuccess(String variant) throws Exception {
        var now = expiredExceptionFixture();
        if (variant.equals("DUPLICATE_LARGE_IDS")) {
            db.update("UPDATE " + PREFIX + "work_order_issue SET issue_id=9007199254740993 WHERE issue_id=31");
            db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(8,2),(8,30)");
        }
        new Node(true).target.expireExceptions(now);
        long child = db.queryForObject("SELECT follow_up_work_order_id FROM " + PREFIX + "work_order_action", Long.class);
        switch (variant) {
            case "SOURCE_CHANGED" -> db.update("DELETE FROM " + PREFIX + "work_order_issue WHERE work_order_id=8 AND issue_id=30");
            case "RELATIONS_CHANGED" -> db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(?,30)", child);
            case "SNAPSHOT_COUNT" -> db.update("UPDATE " + PREFIX + "work_order_action SET expiry_issue_count=7");
            case "SNAPSHOT_HASH" -> db.update("UPDATE " + PREFIX + "work_order_action SET expiry_issue_hash=repeat('d',64)");
            case "OWNER_CHANGED" -> {
                db.update("UPDATE " + PREFIX + "work_order SET owner_user_id=7 WHERE id=?", child);
                db.update("INSERT INTO " + PREFIX + "work_order_notify(work_order_id,event_type,receiver_user_id,time_slice,send_status) VALUES(?,'EXCEPTION_EXPIRED',7,'new-owner','PENDING'),(?,'EXCEPTION_EXPIRED',4,'extra-notice','SENT')", child, child);
            }
            case "MISSING_NOTIFY" -> db.update("DELETE FROM " + PREFIX + "work_order_notify WHERE work_order_id=?", child);
            case "DELETED_NOTIFY" -> db.update("UPDATE " + PREFIX + "work_order_notify SET deleted=1 WHERE work_order_id=?", child);
            case "MISSING_TARGET" -> db.update("DELETE FROM " + PREFIX + "work_order WHERE id=?", child);

            case "DELETED_TARGET" -> db.update("UPDATE " + PREFIX + "work_order SET deleted=1 WHERE id=?", child);
            case "WRONG_TYPE" -> db.update("UPDATE " + PREFIX + "work_order SET source_type='MANUAL' WHERE id=?", child);
            case "ROOT_CHANGED" -> db.update("UPDATE " + PREFIX + "work_order SET root_order_id=99 WHERE id=8");
            default -> { }
        }
        var before = expiryInventoryData(); var row = inventoryRows(expiryInventory( 0, 0, 100)).getFirst();
        assertThat(row).containsEntry("result_binding_status", "BOUND_V1_REFERENCE");
        var direct = inventoryDirect(row);
        boolean missing = List.of("MISSING_TARGET").contains(variant);
        assertThat(direct).containsEntry("follow_up_work_order_id", Long.toString(child)).containsEntry("receiver_user_id", "4")
                .containsEntry("target_missing", missing).containsEntry("target_deleted", variant.equals("DELETED_TARGET"));
        if (missing) {
            assertThat(direct).containsEntry("current_relations_match_snapshot", null).containsEntry("type_matches", null);
        } else {
            assertThat(direct).containsEntry("current_relations_match_snapshot", !List.of("RELATIONS_CHANGED", "SNAPSHOT_COUNT", "SNAPSHOT_HASH").contains(variant))
                    .containsEntry("type_matches", !variant.equals("WRONG_TYPE"))
                    .containsEntry("current_root_matches_source", !variant.equals("ROOT_CHANGED"));
        }
        int expectedNotices = variant.equals("OWNER_CHANGED") ? 2 : 1;
        assertThat(direct).containsEntry("expected_notify_intent_count", variant.equals("MISSING_NOTIFY") ? 0 : expectedNotices)
                .containsEntry("live_expected_notify_intent_count", List.of("MISSING_NOTIFY", "DELETED_NOTIFY").contains(variant) ? 0 : expectedNotices);
        assertThat(inventoryCandidates(row)).allSatisfy(c -> assertThat(c.get("binding")).isEqualTo("UNPROVEN"));
        assertThat(expiryInventoryData()).isEqualTo(before);
    }

    @Test void expiryInventoryDirectReferenceHasNoCreationNotificationObligationForUnassignedEmptyChild() throws Exception {
        var now = expiredExceptionFixture();
        db.update("UPDATE " + PREFIX + "work_order SET owner_user_id=null WHERE id=8");
        db.update("UPDATE " + PREFIX + "work_order_issue SET deleted=1 WHERE work_order_id=8");
        new Node(true).target.expireExceptions(now);
        db.update("UPDATE " + PREFIX + "work_order SET owner_user_id=7 WHERE source_type='EXCEPTION_EXPIRED'");
        var direct = inventoryDirect(inventoryRows(expiryInventory( 0, 0, 100)).getFirst());
        assertThat(direct).containsEntry("receiver_user_id", null).containsEntry("issue_count", 0)
                .containsEntry("current_relations_match_snapshot", true)
                .containsEntry("expected_notify_intent_count", 0).containsEntry("live_expected_notify_intent_count", 0);
    }

    @Test void expiryInventoryMissingNewColumnFailsForEmptyPlatform() {
        db.execute("ALTER TABLE " + PREFIX + "work_order_action DROP COLUMN expiry_issue_hash CASCADE");
        assertThatThrownBy(() -> expiryInventory( 0, 0, 100)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void expiryInventoryDirectReferenceIsIndependentOfCandidateCapAndMissingCurrentSource() throws Exception {
        var now = expiredExceptionFixture(); new Node(true).target.expireExceptions(now);
        long child = db.queryForObject("SELECT follow_up_work_order_id FROM " + PREFIX + "work_order_action", Long.class);
        for (long id = 100; id < 121; id++) {
            db.update("INSERT INTO " + PREFIX + "work_order(id,order_name,root_order_id,source_type) VALUES(?,'候选',8,'EXCEPTION_EXPIRED')", id);
        }
        var row = inventoryRows(expiryInventory( 0, 0, 100)).getFirst();
        assertThat(row).containsEntry("candidates_truncated", true);
        assertThat(inventoryCandidates(row)).noneSatisfy(c -> assertThat(c.get("order_id")).isEqualTo(Long.toString(child)));
        assertThat(inventoryDirect(row)).containsEntry("follow_up_work_order_id", Long.toString(child))
                .containsEntry("current_relations_match_snapshot", true);
        db.update("DELETE FROM " + PREFIX + "work_order WHERE id=8");
        row = inventoryRows(expiryInventory( 0, 0, 100)).getFirst();
        assertThat(row).containsEntry("source_order_missing", true).containsEntry("candidate_binding", "SOURCE_ORDER_MISSING");
        assertThat(inventoryDirect(row)).containsEntry("target_missing", false)
                .containsEntry("current_root_matches_source", null).containsEntry("current_relations_match_snapshot", true);
    }

    @ParameterizedTest @ValueSource(strings = {"PARTIAL", "UNSUPPORTED", "BAD_HASH", "NEGATIVE_COUNT"})
    void expiryInventoryMalformedReceiptIsDiagnosticOnlyAndNeverBindingProof(String variant) throws Exception {
        // 仅自有随机夹具撤掉约束，模拟错误部署/损坏；真实升级仍必须保留0076门禁。
        inventoryAudit("malformed"); db.execute("ALTER TABLE " + PREFIX + "work_order_action DROP CONSTRAINT ck_quality_expiry_result");
        String values = switch (variant) {
            case "PARTIAL" -> "follow_up_work_order_id=99";
            case "UNSUPPORTED" -> "follow_up_work_order_id=99,expiry_result_version=2,expiry_issue_hash=repeat('a',64),expiry_issue_count=0";
            case "BAD_HASH" -> "follow_up_work_order_id=99,expiry_result_version=1,expiry_issue_hash='invalid',expiry_issue_count=0";
            case "NEGATIVE_COUNT" -> "follow_up_work_order_id=99,expiry_result_version=1,expiry_issue_hash=repeat('a',64),expiry_issue_count=-1";
            default -> throw new IllegalArgumentException(variant);
        };
        db.update("UPDATE " + PREFIX + "work_order_action SET " + values);
        var row = inventoryRows(expiryInventory( 0, 0, 100)).getFirst();
        assertThat(row).containsEntry("result_binding_status", "INCOMPLETE_OR_UNSUPPORTED");
        assertThat(inventoryDirect(row)).containsEntry("current_relations_match_snapshot", null);
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING_RELATION", "DUPLICATE_RELATION", "SOURCE_CHANGED", "MISSING_NOTIFY", "DELETED_NOTIFY", "EXTRA_NOTIFY", "SENT", "OWNER_CHANGED"})
    void expiryInventoryDescribesCurrentCandidateGapsWithoutClaimingHistoricalCause(String variant) throws Exception {
        var now = expiredExceptionFixture(); new Node(true).target.expireExceptions(now);
        long child = db.queryForObject("SELECT id FROM " + PREFIX + "work_order WHERE source_type='EXCEPTION_EXPIRED'", Long.class);
        switch (variant) {
            case "MISSING_RELATION" -> db.update("DELETE FROM " + PREFIX + "work_order_issue WHERE work_order_id=? AND issue_id=30", child);
            case "DUPLICATE_RELATION" -> db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(?,30)", child);
            case "SOURCE_CHANGED" -> db.update("DELETE FROM " + PREFIX + "work_order_issue WHERE work_order_id=8 AND issue_id=30");
            case "MISSING_NOTIFY" -> db.update("DELETE FROM " + PREFIX + "work_order_notify WHERE work_order_id=?", child);
            case "DELETED_NOTIFY" -> db.update("UPDATE " + PREFIX + "work_order_notify SET deleted=1 WHERE work_order_id=?", child);
            case "EXTRA_NOTIFY" -> db.update("INSERT INTO " + PREFIX + "work_order_notify(work_order_id,event_type,receiver_user_id,time_slice,send_status) VALUES(?,'EXCEPTION_EXPIRED',4,'extra','FAILED')", child);
            case "SENT" -> db.update("UPDATE " + PREFIX + "work_order_notify SET send_status='SENT' WHERE work_order_id=?", child);
            case "OWNER_CHANGED" -> db.update("UPDATE " + PREFIX + "work_order SET owner_user_id=null WHERE id=?", child);
            default -> throw new IllegalArgumentException(variant);
        }
        var before = expiryInventoryData();
        var row = inventoryRows(expiryInventory( 0, 0, 100)).getFirst();
        assertThat(row.get("candidate_binding")).isEqualTo("UNPROVEN");
        var candidate = inventoryCandidates(row).getFirst();
        if (List.of("MISSING_RELATION", "DUPLICATE_RELATION", "SOURCE_CHANGED").contains(variant)) {
            assertThat(candidate.get("current_source_relations_match")).isEqualTo(false);
            assertThat(candidate.get("current_issue_count_matches")).isEqualTo("SOURCE_CHANGED".equals(variant));
        } else if ("MISSING_NOTIFY".equals(variant)) {
            assertThat(candidate).containsEntry("notify_intent_count", 0).containsEntry("live_notify_intent_count", 0);
        } else if ("DELETED_NOTIFY".equals(variant)) {
            assertThat(candidate).containsEntry("notify_intent_count", 1).containsEntry("live_notify_intent_count", 0);
        } else if ("EXTRA_NOTIFY".equals(variant)) {
            assertThat(candidate).containsEntry("notify_intent_count", 2).containsEntry("live_notify_intent_count", 2);
        } else if ("SENT".equals(variant)) {
            assertThat(candidate.get("live_notify_status_counts")).isEqualTo(Map.of("SENT", 1));
        } else {
            assertThat(candidate).containsEntry("currently_assigned", false).containsEntry("notify_intent_count", 1);
        }
        assertThat(expiryInventoryData()).isEqualTo(before);
    }

    @Test void expiryInventoryIncludesTombstonesAndDoesNotMislabelThemAsNeverExecuted() throws Exception {
        var now = expiredExceptionFixture(); new Node(true).target.expireExceptions(now);
        db.update("UPDATE " + PREFIX + "work_order SET deleted=1");
        db.update("UPDATE " + PREFIX + "work_order_action SET deleted=1");
        db.update("UPDATE " + PREFIX + "work_order_submission SET deleted=1 WHERE id=11");
        var row = inventoryRows(expiryInventory( 0, 0, 100)).getFirst();
        assertThat(row).containsEntry("action_deleted", true).containsEntry("source_order_missing", false)
                .containsEntry("source_order_deleted", true).containsEntry("submission_missing", false)
                .containsEntry("submission_deleted", true).containsEntry("candidate_deleted_count", 1)
                .containsEntry("candidate_binding", "UNPROVEN");
        assertThat(inventoryCandidates(row).getFirst()).containsEntry("deleted", true);
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING_SOURCE", "MISSING_SUBMISSION", "NULL_SUBMISSION", "WRONG_SOURCE", "WRONG_KIND"})
    void expiryInventoryDoesNotSilentlyDiscardBrokenAuditReferences(String variant) throws Exception {
        expiredExceptionFixture(); inventoryAudit("broken-reference");
        switch (variant) {
            case "MISSING_SOURCE" -> db.update("DELETE FROM " + PREFIX + "work_order WHERE id=8");
            case "MISSING_SUBMISSION" -> db.update("DELETE FROM " + PREFIX + "work_order_submission WHERE id=11");
            case "NULL_SUBMISSION" -> db.update("UPDATE " + PREFIX + "work_order_action SET submission_id=null");
            case "WRONG_SOURCE" -> db.update("UPDATE " + PREFIX + "work_order_submission SET work_order_id=99 WHERE id=11");
            case "WRONG_KIND" -> db.update("UPDATE " + PREFIX + "work_order_submission SET submission_type='EXPLAIN' WHERE id=11");

            default -> throw new IllegalArgumentException(variant);
        }
        var row = inventoryRows(expiryInventory( 0, 0, 100)).getFirst();
        String flag = switch (variant) {
            case "MISSING_SOURCE" -> "source_order_missing";
            case "WRONG_SOURCE" -> "submission_wrong_source";
            case "WRONG_KIND" -> "submission_not_exception";
            default -> "submission_missing";
        };
        assertThat(row).containsEntry(flag, true).containsEntry("candidate_count", 0);
    }



    @Test void expiryInventoryBoundsCandidatesAndNeverUsesRootAsAnExactParentBinding() throws Exception {
        expiredExceptionFixture(); inventoryAudit("many-candidates");
        db.update("INSERT INTO " + PREFIX + "work_order(id,root_order_id,source_type,order_name,issue_count) SELECT n,8,'EXCEPTION_EXPIRED','candidate',0 FROM generate_series(100,120) n");
        var row = inventoryRows(expiryInventory( 0, 0, 100)).getFirst();
        assertThat(row).containsEntry("candidate_count", 21).containsEntry("candidates_truncated", true)
                .containsEntry("candidate_binding", "UNPROVEN");
        assertThat(inventoryCandidates(row)).hasSize(20).allSatisfy(c -> assertThat(c.get("binding")).isEqualTo("UNPROVEN"));
    }

    @Test void expiryInventoryKeysetPagesKeepOriginalUpperBoundAndExposeMore() throws Exception {
        expiredExceptionFixture(); long firstId = inventoryAudit("page-first"); long upper = inventoryAudit("page-last");
        var firstPage = expiryInventory( 0, 0, 1);
        assertThat(firstPage).containsEntry("scanned_count", 1).containsEntry("has_more", true)
                .containsEntry("through_action_id", Long.toString(upper)).containsEntry("next_after_action_id", Long.toString(firstId));
        assertThat(inventoryRows(firstPage).getFirst()).containsEntry("same_submission_audit_count", 2);
        long added = inventoryAudit("added-after-page");
        var lastPage = expiryInventory( firstId, upper, 1);
        assertThat(lastPage).containsEntry("has_more", false).containsEntry("next_after_action_id", Long.toString(upper));
        assertThat(inventoryRows(lastPage).getFirst().get("action_id")).isEqualTo(Long.toString(upper));
        assertThat(inventoryRows(expiryInventory( upper, 0, 1)).getFirst().get("action_id")).isEqualTo(Long.toString(added));
    }

    @Test void expiryInventoryMaximumPageReturnsOnlyTwoHundredActionsAndExplicitTail() throws Exception {
        expiredExceptionFixture();
        db.update("INSERT INTO " + PREFIX + "work_order_action(work_order_id,submission_id,action_type,request_id,payload_hash,actor_user_id) SELECT 8,11,'EXCEPTION_EXPIRED','page-'||n,repeat('c',64),0 FROM generate_series(1,201) n");
        var firstPage = expiryInventory( 0, 0, 200);
        assertThat(firstPage).containsEntry("scanned_count", 200).containsEntry("has_more", true);
        assertThat(inventoryRows(firstPage)).hasSize(200);
        long after = Long.parseLong((String) firstPage.get("next_after_action_id"));
        long through = Long.parseLong((String) firstPage.get("through_action_id"));
        assertThat(expiryInventory( after, through, 200)).containsEntry("scanned_count", 1).containsEntry("has_more", false);
    }

    @Test void expiryInventoryEmptyTailPreservesCallerBoundaryRatherThanRecapturingIt() throws Exception {
        var report = expiryInventory( 10, 20, 1);
        assertThat(report).containsEntry("through_action_id", "20").containsEntry("next_after_action_id", "10")
                .containsEntry("scanned_count", 0).containsEntry("has_more", false);
    }

    @Test void expiryInventoryLockWaitFailsWithoutAResultOrAnyBusinessWrites() throws Exception {
        expiredExceptionFixture(); inventoryAudit("locked"); var before = expiryInventoryData();
        try (var blocker = platformSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("LOCK TABLE " + PREFIX + "work_order_action IN ACCESS EXCLUSIVE MODE");
                assertThatThrownBy(() -> expiryInventory( 0, 0, 100))
                        .isInstanceOf(org.springframework.dao.DataAccessException.class)
                        .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
            } finally { blocker.rollback(); }
        }
        assertThat(expiryInventoryData()).isEqualTo(before);
        assertThat(expiryInventory( 0, 0, 100)).containsEntry("scanned_count", 1);
    }

    @Test void expiryInventoryLongIdentifiersRemainDecimalStringsAndNoPayloadIsReturned() throws Exception {
        expiredExceptionFixture(); long id = inventoryAudit("large-id");
        db.update("UPDATE " + PREFIX + "work_order_action SET id=9007199254740993,submission_id=9223372036854775807,reason='private-detail',payload_hash='private-hash' WHERE id=?", id);
        var report = expiryInventory( 0, 0, 100); var row = inventoryRows(report).getFirst();
        assertThat(report).containsEntry("through_action_id", "9007199254740993").containsEntry("next_after_action_id", "9007199254740993");
        assertThat(row).containsEntry("action_id", "9007199254740993").containsEntry("submission_id", "9223372036854775807");
        assertThat(report.toString()).doesNotContain("private-detail", "private-hash", "payload_hash", "reason", "receiver_user_id");
    }

    @ParameterizedTest @ValueSource(strings = {"NEGATIVE_AFTER", "NEGATIVE_UPPER", "UPPER_BEFORE_AFTER", "PAGE_ZERO", "PAGE_TOO_LARGE"})
    void expiryInventoryInvalidParametersFailRatherThanReturnSuccessEmptyPage(String variant) throws Exception {
        long after = "NEGATIVE_AFTER".equals(variant) ? -1 : "UPPER_BEFORE_AFTER".equals(variant) ? 2 : 0;
        long through = "NEGATIVE_UPPER".equals(variant) ? -1 : "UPPER_BEFORE_AFTER".equals(variant) ? 1 : 0;
        int size = "PAGE_ZERO".equals(variant) ? 0 : "PAGE_TOO_LARGE".equals(variant) ? 201 : 100;
        assertThatThrownBy(() -> expiryInventory( after, through, size)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private long inventoryAudit(String key) {
        return db.queryForObject("INSERT INTO " + PREFIX + "work_order_action(work_order_id,submission_id,action_type,request_id,payload_hash,actor_user_id) VALUES(8,11,'EXCEPTION_EXPIRED',?,repeat('c',64),0) RETURNING id", Long.class, key);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> expiryInventory( long after, long through, int size) throws Exception {
        String script = Files.readString(repoFile("tools/operations/quality_exception_expiry_inventory.sql"));
        String query = script.substring(script.indexOf("-- BEGIN INVENTORY QUERY") + "-- BEGIN INVENTORY QUERY".length(),
                script.indexOf("-- END INVENTORY QUERY"));
        assertThat(schema).matches("quality_attempt_[a-f0-9]{32}");
        query = query.replace(":\"inventory_schema\"", "\"" + schema + "\"")
                .replace(":'after_action_id'", "?")
                .replace(":'through_action_id'", "?").replace(":'page_size'", "?");
        var manager = new DataSourceTransactionManager(platformSource); manager.setEnforceReadOnly(true);
        var transaction = new TransactionTemplate(manager); transaction.setReadOnly(true);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        final String sql = query;
        String json = transaction.execute(tx -> {
            db.execute("SET LOCAL statement_timeout='10s'; SET LOCAL lock_timeout='3s';");
            return db.queryForObject(sql, String.class, after, through, size);
        });
        return JsonUtils.getObjectMapper().readValue(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> inventoryRows(Map<String, Object> report) { return (List<Map<String, Object>>) report.get("rows"); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> inventoryCandidates(Map<String, Object> row) { return (List<Map<String, Object>>) row.get("candidates"); }

    private List<String> expiryInventoryData() {
        return List.of("work_order", "work_order_submission", "work_order_action", "work_order_issue", "work_order_notify").stream()
                .map(table -> db.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM " + PREFIX + table + " t", String.class)).toList();
    }

    private void assertExpiredFollowUp() {
        assertThat(count("work_order")).isEqualTo(2);
        assertThat(count("work_order_action")).isEqualTo(1);
        assertThat(count("work_order_issue")).isEqualTo(4);
        assertThat(count("work_order_notify")).isEqualTo(1);
        assertThat(status()).isEqualTo("PROCESSED");
        assertThat(db.queryForObject("SELECT close_type FROM " + PREFIX + "work_order WHERE id=8", String.class)).isEqualTo("EXCEPTION_ACCEPTED");
        assertThat(db.queryForObject("SELECT current_submission_id FROM " + PREFIX + "work_order WHERE id=8", Long.class)).isEqualTo(11L);
        assertThat(db.queryForObject("SELECT lock_version FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT request_id FROM " + PREFIX + "work_order_action", String.class)).isEqualTo("expire-11");
        assertThat(db.queryForObject("SELECT send_status FROM " + PREFIX + "work_order_notify", String.class)).isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT expiry_result_version FROM " + PREFIX + "work_order_action", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT follow_up_work_order_id FROM " + PREFIX + "work_order_action", Long.class))
                .isEqualTo(db.queryForObject("SELECT id FROM " + PREFIX + "work_order WHERE source_type='EXCEPTION_EXPIRED'", Long.class));
    }

    private void addExpiredParent(long id, LocalDateTime now) {
        db.update("INSERT INTO " + PREFIX + "work_order(id,order_name,task_id,owner_user_id,order_status,close_type,current_submission_id,lock_version,issue_count) VALUES(?,'隔离测试',3,4,'PROCESSED','EXCEPTION_ACCEPTED',?,0,2)", id, id);
        db.update("INSERT INTO " + PREFIX + "work_order_submission(id,work_order_id,revision,submission_type,payload_hash,submitted_by,submitted_at,status,request_key,exception_expire_at) VALUES(?,?,1,'EXCEPTION',repeat('c',64),4,now(),'ACTIVE',?,?)", id, id, "isolation-" + id, now.minusDays(1));
        db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(?,?),(?,?)",
                id, id * 10, id, id * 10 + 1);
    }

    /** 模拟失败账本的退避时间已过；失败项会在下一轮扫描重新处理 */
    private void elapseExpiryBackoff() {
        db.update("UPDATE " + PREFIX + "expiry_failure SET next_attempt_at=NULL WHERE status='RETRYING'");
    }

    private String ledgerStatus(long submissionId) {
        return db.queryForObject("SELECT status FROM " + PREFIX + "expiry_failure WHERE submission_id=? AND deleted=0",
                String.class, submissionId);
    }

    @Test void expiryFailureIsPersistedWithBackoffAndDoesNotBlockOtherItems() {
        var now = expiredExceptionFixture(); addExpiredParent(100, now);
        expiryFaultOnParents("work_order", "ERROR", "8");
        var node = new Node(true);
        assertThat(catchThrowable(() -> node.target.expireExceptions(now))).isInstanceOf(IllegalStateException.class);
        assertExpiredParents(List.of(100L));
        assertThat(ledgerStatus(11)).isEqualTo("RETRYING");
        assertThat(db.queryForObject("SELECT attempt_count FROM " + PREFIX + "expiry_failure WHERE submission_id=11", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT next_attempt_at > ? FROM " + PREFIX + "expiry_failure WHERE submission_id=11", Boolean.class, now)).isTrue();
        // 安全摘要不落驱动/数据库原文
        assertThat(db.queryForObject("SELECT last_error FROM " + PREFIX + "expiry_failure WHERE submission_id=11", String.class))
                .doesNotContain("fixture-only-sensitive-detail");
        // 退避期内不再尝试：不报错、不占批次
        assertThat(catchThrowable(() -> node.target.expireExceptions(now))).isNull();
        assertThat(db.queryForObject("SELECT attempt_count FROM " + PREFIX + "expiry_failure WHERE submission_id=11", Integer.class)).isEqualTo(1);
        db.execute("DROP TRIGGER expiry_batch_fault ON " + PREFIX + "work_order");
        elapseExpiryBackoff();
        node.target.expireExceptions(now);
        assertExpiredParents(List.of(8L, 100L));
        assertThat(ledgerStatus(11)).isEqualTo("RESOLVED");
        assertThat(db.queryForObject("SELECT resolution_action FROM " + PREFIX + "expiry_failure WHERE submission_id=11", String.class)).isEqualTo("SUCCEEDED");
    }

    @Test void expiryFailureExhaustsAfterMaxAttemptsAndOnlyManualRetryResumes() {
        var now = expiredExceptionFixture();
        expiryFaultOnParents("work_order", "ERROR", "8");
        var node = new Node(true);
        for (int attempt = 1; attempt <= cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityExceptionExpiryFailureDO.MAX_ATTEMPTS; attempt++) {
            elapseExpiryBackoff();
            assertThat(catchThrowable(() -> node.target.expireExceptions(now))).isInstanceOf(IllegalStateException.class);
        }
        assertThat(ledgerStatus(11)).isEqualTo("EXHAUSTED");
        db.execute("DROP TRIGGER expiry_batch_fault ON " + PREFIX + "work_order");
        elapseExpiryBackoff();
        // 耗尽后即使故障已消除也不自动处理，等待人工排查后授权
        node.target.expireExceptions(now);
        assertExpiredParents(List.of());
        Long ledgerId = db.queryForObject("SELECT id FROM " + PREFIX + "expiry_failure WHERE submission_id=11", Long.class);
        Integer version = db.queryForObject("SELECT version FROM " + PREFIX + "expiry_failure WHERE submission_id=11", Integer.class);
        var failures = (cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityExceptionExpiryFailureMapper)
                ReflectionTestUtils.getField(node.target, "expiryFailureMapper");
        assertThat(failures.authorizeRetry(ledgerId, version - 1, "旧页面版本", 4L, now)).isZero();
        assertThat(failures.authorizeRetry(ledgerId, version, "已修复责任人配置", 4L, now)).isEqualTo(1);
        assertThat(ledgerStatus(11)).isEqualTo("RETRYING");
        node.target.expireExceptions(now);
        assertExpiredParents(List.of(8L));
        assertThat(ledgerStatus(11)).isEqualTo("RESOLVED");
        // 再次扫描不会重复创建复核单
        elapseExpiryBackoff(); node.target.expireExceptions(now); assertExpiredParents(List.of(8L));
    }

    private void expiryFaultOnParents(String table, String mode, String parentIds) {
        // parentIds 仅来自本测试固定整数常量，不接收用户输入。
        String condition = switch (table) {
            case "work_order" -> "NEW.source_type='EXCEPTION_EXPIRED' AND NEW.root_order_id IN (" + parentIds + ")";
            case "work_order_action" -> "NEW.action_type='EXCEPTION_EXPIRED' AND NEW.work_order_id IN (" + parentIds + ")";
            case "work_order_issue", "work_order_notify" -> "EXISTS(SELECT 1 FROM " + PREFIX
                    + "work_order WHERE id=NEW.work_order_id AND source_type='EXCEPTION_EXPIRED' AND root_order_id IN (" + parentIds + "))";
            default -> throw new IllegalArgumentException(table);
        };
        db.execute("CREATE FUNCTION expiry_batch_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF " + condition + " THEN "
                + (mode.equals("ERROR") ? "RAISE EXCEPTION 'fixture-only-sensitive-detail';" : "RETURN NULL;")
                + " END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER expiry_batch_fault BEFORE INSERT ON " + PREFIX + table + " FOR EACH ROW EXECUTE FUNCTION expiry_batch_fault()");
    }

    private void assertExpiredParents(List<Long> parents) {
        assertThat(db.queryForList("SELECT work_order_id FROM " + PREFIX + "work_order_action ORDER BY work_order_id", Long.class)).containsExactlyElementsOf(parents);
        assertThat(db.queryForList("SELECT root_order_id FROM " + PREFIX + "work_order WHERE source_type='EXCEPTION_EXPIRED' ORDER BY root_order_id", Long.class)).containsExactlyElementsOf(parents);
        assertThat(db.queryForObject("SELECT count(*) FROM " + PREFIX + "work_order_action a JOIN " + PREFIX + "work_order w ON w.id=a.follow_up_work_order_id WHERE a.expiry_result_version=1 AND w.source_type='EXCEPTION_EXPIRED' AND a.expiry_issue_count=2 AND a.expiry_receiver_user_id=4", Integer.class)).isEqualTo(parents.size());
        assertThat(db.queryForObject("SELECT count(*) FROM " + PREFIX + "work_order_issue i JOIN " + PREFIX
                + "work_order w ON i.work_order_id=w.id WHERE w.source_type='EXCEPTION_EXPIRED' AND i.deleted=0", Integer.class)).isEqualTo(parents.size() * 2);
        assertThat(count("work_order_notify")).isEqualTo(parents.size());
        assertThat(db.queryForObject("SELECT count(*) FROM " + PREFIX + "work_order_notify WHERE send_status='PENDING'", Integer.class)).isEqualTo(parents.size());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"work_order:ERROR:FIRST", "work_order:ZERO:FIRST", "work_order_issue:ERROR:FIRST", "work_order_issue:ZERO:FIRST",
            "work_order_action:ERROR:FIRST", "work_order_action:ZERO:FIRST", "work_order_notify:ERROR:FIRST", "work_order_notify:ZERO:FIRST",
            "work_order:ERROR:MIDDLE", "work_order:ZERO:MIDDLE", "work_order_issue:ERROR:MIDDLE", "work_order_issue:ZERO:MIDDLE",
            "work_order_action:ERROR:MIDDLE", "work_order_action:ZERO:MIDDLE", "work_order_notify:ERROR:MIDDLE", "work_order_notify:ZERO:MIDDLE"})
    void expiryBatchContinuesAfterIndividualFailureAndReportsFailure(String fault) {
        var now = expiredExceptionFixture(); addExpiredParent(100, now); addExpiredParent(101, now);
        String[] parts = fault.split(":"); boolean firstFails = parts[2].equals("FIRST");
        expiryFaultOnParents(parts[0], parts[1], firstFails ? "8" : "100");
        var node = new Node(true);
        Throwable failure = catchThrowable(node.service::dispatchDueNotifies);
        assertThat(failure).isInstanceOf(RuntimeException.class);
        assertExpiredParents(firstFails ? List.of(100L, 101L) : List.of(8L, 101L));
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining(firstFails ? "[11]" : "[100]");
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(cn.hutool.core.exceptions.ExceptionUtil.getRootCauseMessage(failure))
                .contains(firstFails ? "[11]" : "[100]").doesNotContain("fixture-only-sensitive-detail");
        verifyNoInteractions(node.notifications);
        db.execute("DROP TRIGGER expiry_batch_fault ON " + PREFIX + parts[0]);
        elapseExpiryBackoff();
        node.target.expireExceptions(now); second.target.expireExceptions(now);
        assertExpiredParents(List.of(8L, 100L, 101L));
        assertThat(count("work_order")).isEqualTo(6);
    }

    @Test void expiryBatchRetainsMultipleFailuresAndCommittedMiddleOrder() {
        var now = expiredExceptionFixture(); addExpiredParent(100, now); addExpiredParent(101, now);
        expiryFaultOnParents("work_order", "ERROR", "8,101"); var node = new Node(true);
        Throwable failure = catchThrowable(() -> node.target.expireExceptions(now));
        assertExpiredParents(List.of(100L));
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("[11, 101]");
        assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).hasSize(2);
        db.execute("DROP TRIGGER expiry_batch_fault ON " + PREFIX + "work_order");
        elapseExpiryBackoff();
        node.target.expireExceptions(now); assertExpiredParents(List.of(8L, 100L, 101L));
    }



    @Test void failedFirstPageDoesNotStarveLaterExpiryAcrossRestartedNodes() {
        var now = expiredExceptionFixture(); for (long id = 100; id < 120; id++) addExpiredParent(id, now);
        expiryFaultOnParents("work_order", "ERROR", "8,100,101,102,103,104,105,106,107,108,109,110,111,112,113,114,115,116,117,118");
        var node = new Node(true);
        Throwable failure = catchThrowable(() -> node.target.expireExceptions(now));
        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(failure.getSuppressed()).hasSize(20);
        assertExpiredParents(List.of());
        // 新节点必须接着处理第 21 条，不能继续被同一页永久故障挡住。
        new Node(true).target.expireExceptions(now);
        assertExpiredParents(List.of(119L));
        // 失败账本：退避期内失败项不再占批次，扫描正常结束；退避到期后重新尝试并再次报告失败
        assertThat(catchThrowable(() -> new Node(true).target.expireExceptions(now))).isNull();
        assertThat(db.queryForObject("SELECT count(*) FROM " + PREFIX + "expiry_failure WHERE status='RETRYING'", Integer.class)).isEqualTo(20);
        elapseExpiryBackoff();
        Throwable retry = catchThrowable(() -> new Node(true).target.expireExceptions(now));
        assertThat(retry).isInstanceOf(IllegalStateException.class);
        assertThat(retry.getSuppressed()).hasSize(20);
        assertThat(db.queryForObject("SELECT min(attempt_count) FROM " + PREFIX + "expiry_failure", Integer.class)).isEqualTo(2);
        db.execute("DROP TRIGGER expiry_batch_fault ON " + PREFIX + "work_order");
        elapseExpiryBackoff();
        new Node(true).target.expireExceptions(now);
        assertThat(count("work_order_action")).isEqualTo(21);
        assertThat(count("work_order")).isEqualTo(42);
    }

    @Test void allFailedExpiryCandidatesRemainBoundedAndCannotReportSuccess() {
        var now = expiredExceptionFixture(); for (long id = 100; id < 120; id++) addExpiredParent(id, now);
        var node = new Node(true); expiryFaultOnParents("work_order", "ERROR", "8,100,101,102,103,104,105,106,107,108,109,110,111,112,113,114,115,116,117,118,119");
        Throwable failure = catchThrowable(() -> node.target.expireExceptions(now));
        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(failure.getSuppressed()).hasSize(20); assertThat(failure.getMessage()).doesNotContain("119");
        assertExpiredParents(List.of()); assertThat(count("work_order")).isEqualTo(21);
        // 明确验证限制：本轮不绕过整页故障去处理第 21 条，更不伪称解决持久饥饿。
        assertThat(count("work_order_action")).isZero();
        db.execute("DROP TRIGGER expiry_batch_fault ON " + PREFIX + "work_order");
        elapseExpiryBackoff();
        node.target.expireExceptions(now); assertThat(count("work_order_action")).isEqualTo(1);
        node.target.expireExceptions(now); assertThat(count("work_order_action")).isEqualTo(21);
    }

    @Test void reservedButUnexecutedExpiryPageReturnsAfterBoundedWraparound() {
        var now = expiredExceptionFixture(); for (long id = 100; id < 120; id++) addExpiredParent(id, now);
        var node = new Node(true);
        assertThat(node.expiryScan.reserve(now)).hasSize(20);
        assertThat(admin.queryForObject("SELECT after_submission_id FROM " + schema + "." + PREFIX + "exception_expiry_cursor WHERE id=1", Long.class)).isEqualTo(118L);
        assertExpiredParents(List.of());
        // 模拟进程在预约提交后、任何业务写入前消失；新对象不依赖本机内存。
        new Node(true).target.expireExceptions(now); assertExpiredParents(List.of(119L));
        new Node(true).target.expireExceptions(now);
        assertThat(count("work_order_action")).isEqualTo(21); assertThat(count("work_order")).isEqualTo(42);
    }

    @RepeatedTest(3) void concurrentExpiryReservationsAreGloballySerializedAndDisjoint() throws Exception {
        var now = expiredExceptionFixture(); for (long id = 100; id < 160; id++) addExpiredParent(id, now);
        var nodes = List.of(new Node(true), new Node(true), new Node(true));
        var ready = new CountDownLatch(3); var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(3);
        try {
            var futures = new ArrayList<Future<List<Long>>>();
            for (var node : nodes) futures.add(workers.submit(() -> {
                TenantContextHolder.clear();
                try {
                    ready.countDown(); if (!start.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    var reserved = node.expiryScan.reserve(now);
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return reserved.stream().map(QualityWorkOrderSubmissionDO::getId).toList();
                } finally { TenantContextHolder.clear(); }
            }));
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var ids = new ArrayList<Long>();
            for (var future : futures) { var page = future.get(10, TimeUnit.SECONDS); assertThat(page).hasSize(20); ids.addAll(page); }
            assertThat(ids).hasSize(60).doesNotHaveDuplicates();
            assertThat(count("exception_expiry_cursor")).isEqualTo(1);
            assertExpiredParents(List.of());
            new Node(true).target.expireExceptions(now); assertExpiredParents(List.of(159L));
            for (int i = 0; i < 3; i++) new Node(true).target.expireExceptions(now);
            assertThat(count("work_order_action")).isEqualTo(61);
        } finally { start.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void fixedExpiryCycleUpperBoundPreventsNewArrivalsFromDelayingFailedItemsForever() {
        var now = expiredExceptionFixture(); for (long id = 100; id <= 120; id++) addExpiredParent(id, now);
        var node = new Node(true); expiryFaultOnParents("work_order", "ERROR", "8");
        assertThatThrownBy(() -> node.target.expireExceptions(now)).isInstanceOf(IllegalStateException.class);
        for (long id = 121; id <= 145; id++) addExpiredParent(id, now);
        new Node(true).target.expireExceptions(now);
        assertThat(count("work_order_action")).isEqualTo(21);
        assertThat(db.queryForObject("SELECT through_submission_id FROM " + PREFIX + "exception_expiry_cursor", Long.class))
                .isEqualTo(120L);
        elapseExpiryBackoff();
        Throwable retry = catchThrowable(() -> new Node(true).target.expireExceptions(now));
        assertThat(retry).isInstanceOf(IllegalStateException.class); assertThat(retry.getSuppressed()).hasSize(1);
        assertThat(retry.getMessage()).contains("异常提交=[11]");
        assertThat(count("work_order_action")).isEqualTo(40);
    }

    @Test void newlyDueLowerIdIsRevisitedInNextExpiryCycle() {
        var now = expiredExceptionFixture(); for (long id = 100; id <= 120; id++) addExpiredParent(id, now);
        db.update("UPDATE " + PREFIX + "work_order_submission SET exception_expire_at=? WHERE id=11", now.plusDays(1));
        var node = new Node(true); node.target.expireExceptions(now); assertThat(count("work_order_action")).isEqualTo(20);
        db.update("UPDATE " + PREFIX + "work_order_submission SET exception_expire_at=? WHERE id=11", now);
        new Node(true).target.expireExceptions(now); assertThat(count("work_order_action")).isEqualTo(21);
        new Node(true).target.expireExceptions(now); assertThat(count("work_order_action")).isEqualTo(22);
    }

    @Test void expiryCursorIsSharedAcrossNodesAndResumesTheNextPage() {
        var now = expiredExceptionFixture(); for (long id = 100; id < 120; id++) addExpiredParent(id, now);
        addExpiredParent(500, now); var node = new Node(true);
        assertThat(node.expiryScan.reserve(now)).hasSize(20);
        TenantContextHolder.clear();
        new Node(true).target.expireExceptions(now);
        assertExpiredParents(List.of(119L, 500L));
        assertThat(count("exception_expiry_cursor")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"INSERT_ERROR", "UPDATE_ERROR", "UPDATE_ZERO"})
    void expiryCursorWriteFailureCannotReleaseCandidatesOrCreateBusinessEffects(String fault) {
        var now = expiredExceptionFixture(); var node = new Node(true);
        String body = fault.endsWith("ZERO") ? "RETURN NULL;" : "RAISE EXCEPTION 'fixture-only-cursor-failure';";
        db.execute("CREATE FUNCTION expiry_cursor_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN " + body + " END $$");
        db.execute("CREATE TRIGGER expiry_cursor_fault BEFORE " + (fault.startsWith("INSERT") ? "INSERT" : "UPDATE")
                + " ON " + PREFIX + "exception_expiry_cursor FOR EACH ROW EXECUTE FUNCTION expiry_cursor_fault()");
        assertThatThrownBy(() -> node.target.expireExceptions(now)).isInstanceOf(RuntimeException.class);
        assertThat(count("exception_expiry_cursor")).isZero(); assertExpiredParents(List.of());
        db.execute("DROP TRIGGER expiry_cursor_fault ON " + PREFIX + "exception_expiry_cursor");
        new Node(true).target.expireExceptions(now); assertExpiredFollowUp();
    }

    @Test void expiryReservationWorksWithoutTenantContext() {
        var now = expiredExceptionFixture();
        TenantContextHolder.clear();
        new Node(true).target.expireExceptions(now);
        assertExpiredFollowUp();
        assertThat(count("exception_expiry_cursor")).isEqualTo(1);
    }

    @Test void unknownExpiryReservationCommitDoesNotLoseUnexecutedBusinessItems() {
        var now = expiredExceptionFixture(); for (long id = 100; id < 120; id++) addExpiredParent(id, now);
        var node = new Node(true);
        var manager = new org.springframework.transaction.PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return node.transactions.getTransaction(definition);
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) {
                node.transactions.commit(status);
                throw new org.springframework.transaction.TransactionSystemException("fixture-only-reservation-response-lost");
            }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) { node.transactions.rollback(status); }
        };
        ReflectionTestUtils.setField(node.expiryScan, "transactionManager", manager);
        assertThatThrownBy(() -> node.target.expireExceptions(now)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("游标提交结果待核查")
                .satisfies(failure -> assertThat(failure.getSuppressed()).anySatisfy(cause ->
                        assertThat(cause).isInstanceOf(org.springframework.transaction.TransactionSystemException.class)));
        assertExpiredParents(List.of());
        new Node(true).target.expireExceptions(now); assertExpiredParents(List.of(119L));
        new Node(true).target.expireExceptions(now); assertThat(count("work_order_action")).isEqualTo(21);
    }

    @Test void singleTenantMigrationReplayKeepsCursorProgressAndSingletonConstraint() throws Exception {
        var now = expiredExceptionFixture(); var node = new Node(true); node.expiryScan.reserve(now);
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(db, schema);
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(db, schema);
        assertThat(db.queryForObject("SELECT after_submission_id FROM " + PREFIX + "exception_expiry_cursor", Long.class)).isEqualTo(11L);
        assertThat(count("exception_expiry_cursor")).isEqualTo(1);
        assertThatThrownBy(() -> db.update("INSERT INTO " + PREFIX + "exception_expiry_cursor(id) VALUES(2)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private List<Map<String, Object>> expiryCursorMetadata() {
        return db.queryForList("SELECT a.attname,format_type(a.atttypid,a.atttypmod) AS type,a.attnotnull,"
                + "pg_get_expr(d.adbin,d.adrelid) AS default_value,col_description(a.attrelid,a.attnum) AS comment "
                + "FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum "
                + "WHERE a.attrelid='" + PREFIX + "exception_expiry_cursor'::regclass AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum");
    }

    private List<String> expiryCursorConstraints() {
        return db.queryForList("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='"
                + PREFIX + "exception_expiry_cursor'::regclass ORDER BY conname", String.class);
    }

    @Test void emptyExpiryCycleMustNotFreezeUpperBoundBeforeNewArrivals() {
        var now = LocalDateTime.now().withNano(0); var node = new Node(true);
        assertThat(node.expiryScan.reserve(now)).isEmpty();
        addExpiredParent(100, now);
        new Node(true).target.expireExceptions(now); assertExpiredParents(List.of(100L));
    }

    @Test void expiryScannerBeanWiresExistingMapperAndTransactionComponents() {
        var now = expiredExceptionFixture(); var node = new Node(true);
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("qualityExceptionExpiryCursorMapper", node.expiryCursors);
            context.getBeanFactory().registerSingleton("qualityWorkOrderSubmissionMapper",
                    Objects.requireNonNull(ReflectionTestUtils.getField(node.expiryScan, "submissionMapper")));
            context.getBeanFactory().registerSingleton("transactionManager", node.transactions);
            context.register(QualityExceptionExpiryScanService.class); context.refresh();
            var scanner = context.getBean(QualityExceptionExpiryScanService.class);
            assertThat(scanner.reserve(now)).extracting(QualityWorkOrderSubmissionDO::getId).containsExactly(11L);
            ReflectionTestUtils.setField(node.target, "exceptionExpiryScanService", scanner);
            node.target.expireExceptions(now); assertExpiredFollowUp();
        }
    }

    @Test void ineligibleExpiryTailCannotFreezeSubsequentCycles() {
        var now = expiredExceptionFixture(); addExpiredParent(500, now);
        db.update("UPDATE " + PREFIX + "work_order_submission SET submission_type='EXPLAIN' WHERE id=500");
        var node = new Node(true); node.target.expireExceptions(now); assertExpiredParents(List.of(8L));
        assertThat(new Node(true).expiryScan.reserve(now)).isEmpty();
        addExpiredParent(600, now);
        new Node(true).target.expireExceptions(now); assertExpiredParents(List.of(8L, 600L));
    }

    @Test void arrivalAfterEmptyCycleUpperReadIsRecoveredByNextNode() throws Exception {
        var now = LocalDateTime.now().withNano(0); var node = new Node(true);
        var mapper = (QualityWorkOrderSubmissionMapper) ReflectionTestUtils.getField(node.expiryScan, "submissionMapper");
        var spied = spy(mapper); var upperRead = new CountDownLatch(1); var arrived = new CountDownLatch(1);
        doAnswer(call -> {
            long through = mapper.selectScanUpperId(); upperRead.countDown();
            if (!arrived.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("arrival timeout");
            return through;
        }).when(spied).selectScanUpperId();
        ReflectionTestUtils.setField(node.expiryScan, "submissionMapper", spied);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var reserved = worker.submit(() -> {
                TenantContextHolder.clear();
                try { return node.expiryScan.reserve(now); } finally { TenantContextHolder.clear(); }
            });
            assertThat(upperRead.await(3, TimeUnit.SECONDS)).isTrue(); addExpiredParent(100, now); arrived.countDown();
            assertThat(reserved.get(10, TimeUnit.SECONDS)).isEmpty();
            new Node(true).target.expireExceptions(now); assertExpiredParents(List.of(100L));
        } finally { arrived.countDown(); worker.shutdownNow(); assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest @ValueSource(strings = {"NEGATIVE", "BEYOND_UPPER", "DELETED", "DUPLICATE_SINGLETON"})
    void expiryCursorConstraintsRejectInvalidStateWithoutResettingProgress(String invalid) {
        var now = expiredExceptionFixture(); var node = new Node(true); node.expiryScan.reserve(now);
        String sql = switch (invalid) {
            case "NEGATIVE" -> "UPDATE " + PREFIX + "exception_expiry_cursor SET after_submission_id=-1";
            case "BEYOND_UPPER" -> "UPDATE " + PREFIX + "exception_expiry_cursor SET after_submission_id=12";
            case "DELETED" -> "UPDATE " + PREFIX + "exception_expiry_cursor SET deleted=1";
            case "DUPLICATE_SINGLETON" -> "INSERT INTO " + PREFIX + "exception_expiry_cursor(id) VALUES(1)";
            default -> throw new IllegalArgumentException(invalid);
        };
        assertThatThrownBy(() -> db.execute(sql)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(db.queryForObject("SELECT after_submission_id FROM " + PREFIX + "exception_expiry_cursor", Long.class)).isEqualTo(11L);
        assertThat(count("exception_expiry_cursor")).isEqualTo(1);
        new Node(true).target.expireExceptions(now); assertExpiredFollowUp();
    }

    @ParameterizedTest @ValueSource(strings = {"RESOURCE"})
    void expiryReservationRejectsUnbalancedCompletionBeforeReleasingCandidates(String poison) {
        var now = expiredExceptionFixture(); var node = new Node(true); Object key = new Object();
        var manager = new org.springframework.transaction.PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return node.transactions.getTransaction(definition);
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) {
                node.transactions.commit(status);
                switch (poison) {
                    case "RESOURCE" -> TransactionSynchronizationManager.bindResource(key, new Object());


                    default -> throw new IllegalArgumentException(poison);
                }
            }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) { node.transactions.rollback(status); }
        };
        ReflectionTestUtils.setField(node.expiryScan, "transactionManager", manager);
        try {
            assertThatThrownBy(() -> node.target.expireExceptions(now)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("游标提交结果待核查")
                    .satisfies(failure -> assertThat(failure.getSuppressed()).anySatisfy(cause ->
                            assertThat(cause).hasMessageContaining("预约上下文未清理")));
        } finally {
            if (TransactionSynchronizationManager.hasResource(key)) TransactionSynchronizationManager.unbindResource(key);
            TenantContextHolder.clear(); TenantContextHolder.setIgnore(false);
        }
        assertThat(count("exception_expiry_cursor")).isEqualTo(1); assertExpiredParents(List.of());
        new Node(true).target.expireExceptions(now); assertExpiredFollowUp();
    }

    @ParameterizedTest @ValueSource(strings = {"CANCEL", "INTERRUPTED", "ROLLBACK_CANCEL", "ROLLBACK_INTERRUPT", "COMMIT_INTERRUPT", "FATAL"})
    void expiryReservationHonoursCancellationIncludingRollbackOverrideAndPostCommitInterrupt(String mode) throws Exception {
        var now = expiredExceptionFixture(); var node = new Node(true);
        if (!mode.equals("COMMIT_INTERRUPT")) {
            var mapper = (QualityWorkOrderSubmissionMapper) ReflectionTestUtils.getField(node.expiryScan, "submissionMapper");
            var spied = spy(mapper);
            doAnswer(call -> {
                if (mode.equals("FATAL")) throw new AssertionError("fixture-fatal");
                if (mode.contains("INTERRUPT")) throw new IllegalStateException("fixture-wrapped-interrupt", new InterruptedException("fixture-interrupt"));
                throw new IllegalStateException("fixture-wrapped-cancel", new CancellationException("fixture-cancel"));
            }).when(spied).selectExpiredExceptions(any(), anyInt(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
            ReflectionTestUtils.setField(node.expiryScan, "submissionMapper", spied);
        }
        if (mode.startsWith("ROLLBACK") || mode.equals("COMMIT_INTERRUPT")) {
            var manager = new org.springframework.transaction.PlatformTransactionManager() {
                @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                    return node.transactions.getTransaction(definition);
                }
                @Override public void commit(org.springframework.transaction.TransactionStatus status) {
                    node.transactions.commit(status); Thread.currentThread().interrupt();
                }
                @Override public void rollback(org.springframework.transaction.TransactionStatus status) {
                    node.transactions.rollback(status);
                    throw new org.springframework.transaction.TransactionSystemException("fixture-rollback-completion-lost");
                }
            };
            ReflectionTestUtils.setField(node.expiryScan, "transactionManager", manager);
        }
        var worker = Executors.newSingleThreadExecutor();
        try {
            var future = worker.submit(() -> {
                TenantContextHolder.clear();
                try {
                    Throwable failure = catchThrowable(() -> node.target.expireExceptions(now));
                    boolean interrupted = Thread.currentThread().isInterrupted();
                    assertThat(failure).isInstanceOf(mode.equals("FATAL") ? AssertionError.class : CancellationException.class);
                    assertThat(interrupted).isEqualTo(mode.contains("INTERRUPT"));
                    if (!mode.equals("FATAL")) assertThat(failure.getSuppressed()).isNotEmpty();
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return true;
                } finally { Thread.interrupted(); TenantContextHolder.clear(); }
            });
            assertThat(future.get(10, TimeUnit.SECONDS)).isTrue();
        } finally { worker.shutdownNow(); assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        assertExpiredParents(List.of());
        assertThat(count("exception_expiry_cursor")).isEqualTo(mode.equals("COMMIT_INTERRUPT") ? 1 : 0);
        new Node(true).target.expireExceptions(now); assertExpiredFollowUp();
    }

    @ParameterizedTest @ValueSource(strings = {"ERROR", "CANCELLED", "WRAPPED_CANCEL", "INTERRUPTED", "FLAGGED_FAILURE"})
    void expiryBatchStopsOnCancellationInterruptionAndFatalError(String mode) throws Exception {
        var now = expiredExceptionFixture(); addExpiredParent(100, now); addExpiredParent(101, now);
        var node = new Node(true); var actions = spy(node.actions);
        doAnswer(call -> {
            QualityWorkOrderActionDO action = call.getArgument(0); int result = node.actions.insert(action);
            if (action.getWorkOrderId().equals(100L)) {
                switch (mode) {
                    case "ERROR" -> throw new AssertionError("fixture fatal error");
                    case "CANCELLED" -> throw new CancellationException("fixture cancelled");
                    case "WRAPPED_CANCEL" -> throw new IllegalStateException("fixture wrapped", new CancellationException("fixture cancelled"));
                    case "INTERRUPTED" -> throw new IllegalStateException("fixture wrapped", new InterruptedException("fixture interrupted"));
                    case "FLAGGED_FAILURE" -> { Thread.currentThread().interrupt(); throw new IllegalStateException("fixture flagged"); }
                    default -> throw new IllegalArgumentException(mode);
                }
            }
            return result;
        }).when(actions).insert(any(QualityWorkOrderActionDO.class));
        ReflectionTestUtils.setField(node.target, "actionMapper", actions);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var stopped = pool.submit(() -> {
                TenantContextHolder.clear();
                try {
                    Throwable failure = catchThrowable(() -> node.target.expireExceptions(now));
                    return Map.of("failure", failure, "interrupted", Thread.currentThread().isInterrupted(),
                            "transactionActive", TransactionSynchronizationManager.isActualTransactionActive());
                } finally { Thread.interrupted(); TenantContextHolder.clear(); }
            }).get(10, TimeUnit.SECONDS);
            assertThat(stopped.get("failure")).isInstanceOf(mode.equals("ERROR") ? AssertionError.class : CancellationException.class);
            assertThat(stopped.get("interrupted")).isEqualTo(mode.equals("INTERRUPTED") || mode.equals("FLAGGED_FAILURE"));
            assertThat(stopped.get("transactionActive")).isEqualTo(false);
        }
        assertExpiredParents(List.of(8L));
        ReflectionTestUtils.setField(node.target, "actionMapper", node.actions);
        node.target.expireExceptions(now); assertExpiredParents(List.of(8L, 100L, 101L));
    }

    @Test void expiryBatchHonoursInterruptionBeforeFirstCandidate() throws Exception {
        var now = expiredExceptionFixture(); var node = new Node(true);
        try (var pool = Executors.newSingleThreadExecutor()) {
            Throwable failure = pool.submit(() -> {
                TenantContextHolder.clear(); Thread.currentThread().interrupt();
                try { return catchThrowable(() -> node.target.expireExceptions(now)); }
                finally { Thread.interrupted(); TenantContextHolder.clear(); }
            }).get(8, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOf(CancellationException.class);
        }
        assertExpiredParents(List.of()); assertThat(count("work_order")).isEqualTo(1);
    }

    @Test void committedExpiryWithLostLocalReturnRemainsCommittedAndReplaysWithoutDuplication() {
        var now = expiredExceptionFixture(); addExpiredParent(100, now); addExpiredParent(101, now);
        var node = new Node(true); var firstCommit = new java.util.concurrent.atomic.AtomicBoolean(true);
        var manager = new org.springframework.transaction.PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return node.transactions.getTransaction(definition);
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) {
                node.transactions.commit(status);
                if (firstCommit.getAndSet(false)) throw new IllegalStateException("fixture post-commit return lost");
            }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) { node.transactions.rollback(status); }
        };
        ReflectionTestUtils.setField(node.target, "transactionManager", manager);
        Throwable failure = catchThrowable(() -> node.target.expireExceptions(now));
        assertExpiredParents(List.of(8L));
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("事务完成异常")
                .hasMessageContaining("[11]").hasMessageContaining("待核查");
        assertThat(failure.getSuppressed()).hasSize(1);
        new Node(true).target.expireExceptions(now);
        node.target.expireExceptions(now); assertExpiredParents(List.of(8L, 100L, 101L));
        assertThat(count("work_order")).isEqualTo(6);
    }

    @ParameterizedTest @ValueSource(strings = {"BEGIN:FIRST", "BEGIN:MIDDLE", "COMMIT_BEFORE:FIRST", "COMMIT_BEFORE:MIDDLE",
            "COMMIT_AFTER:FIRST", "COMMIT_AFTER:MIDDLE", "ROLLBACK_BEFORE:FIRST", "ROLLBACK_BEFORE:MIDDLE",
            "ROLLBACK_AFTER:FIRST", "ROLLBACK_AFTER:MIDDLE"})
    void expiryPlainTransactionBoundaryFailureStopsBeforeNextCandidateAndPreservesOriginal(String fault) {
        var now = expiredExceptionFixture(); addExpiredParent(100, now); addExpiredParent(101, now);
        String[] parts = fault.split(":"); String phase = parts[0]; boolean middle = parts[1].equals("MIDDLE");
        int occurrence = middle ? 2 : 1; long parent = middle ? 100L : 8L;
        var node = new Node(true); var starts = new java.util.concurrent.atomic.AtomicInteger();
        var active = new java.util.concurrent.atomic.AtomicReference<org.springframework.transaction.TransactionStatus>();
        var original = new IllegalStateException("fixture-only-original-application");
        if (phase.startsWith("ROLLBACK")) {
            var actions = spy(node.actions);
            doAnswer(call -> {
                QualityWorkOrderActionDO action = call.getArgument(0); int result = node.actions.insert(action);
                if (action.getWorkOrderId().equals(parent)) throw original;
                return result;
            }).when(actions).insert(any(QualityWorkOrderActionDO.class));
            ReflectionTestUtils.setField(node.target, "actionMapper", actions);
        }
        ReflectionTestUtils.setField(node.target, "transactionManager", expiryPlainCompletionFault(node, phase, occurrence, starts, active));
        Throwable failure;
        try { failure = catchThrowable(() -> node.target.expireExceptions(now)); }
        finally {
            // BEFORE 回滚故障留下真实事务；仅测试夹具清理自己的连接，不让测试掩盖生产未清理状态。
            if (active.get() != null && !active.get().isCompleted()) node.transactions.rollback(active.get());
        }
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("事务完成异常")
                .hasMessageContaining("待核查").hasMessageNotContaining("fixture-only");
        assertThat(starts.get()).isEqualTo(occurrence);
        var contextual = failure.getSuppressed()[0];
        if (phase.startsWith("ROLLBACK")) assertThat(contextual.getSuppressed()).contains(original);
        assertExpiredParents(phase.equals("COMMIT_AFTER") ? (middle ? List.of(8L, 100L) : List.of(8L))
                : (middle ? List.of(8L) : List.of()));
        verifyNoInteractions(node.notifications);
        new Node(true).target.expireExceptions(now); assertExpiredParents(List.of(8L, 100L, 101L));
        new Node(true).target.expireExceptions(now); assertThat(count("work_order")).isEqualTo(6);
    }

    private org.springframework.transaction.PlatformTransactionManager expiryPlainCompletionFault(Node node, String phase, int occurrence,
            java.util.concurrent.atomic.AtomicInteger starts,
            java.util.concurrent.atomic.AtomicReference<org.springframework.transaction.TransactionStatus> active) {
        return new org.springframework.transaction.PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                if (starts.incrementAndGet() == occurrence && phase.equals("BEGIN")) throw new IllegalStateException("fixture-only-begin");
                var status = node.transactions.getTransaction(definition); active.set(status); return status;
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) {
                if (starts.get() == occurrence && phase.equals("COMMIT_BEFORE")) {
                    // 真实回滚仅用于有限夹具清理；调用方收到的是提交路径普通异常，不可据此假定结果。
                    node.transactions.rollback(status); throw new IllegalStateException("fixture-only-before-commit");
                }
                node.transactions.commit(status);
                if (starts.get() == occurrence && phase.equals("COMMIT_AFTER")) throw new IllegalStateException("fixture-only-after-commit");
            }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) {
                if (starts.get() == occurrence && phase.equals("ROLLBACK_BEFORE")) throw new IllegalStateException("fixture-only-before-rollback");
                node.transactions.rollback(status);
                if (starts.get() == occurrence && phase.equals("ROLLBACK_AFTER")) throw new IllegalStateException("fixture-only-after-rollback");
                if (starts.get() == occurrence && phase.equals("ROLLBACK_AFTER_TSE")) throw new org.springframework.transaction.TransactionSystemException("fixture-only-rollback-system");
            }
        };
    }

    @ParameterizedTest @ValueSource(strings = {"INTERRUPTED:ROLLBACK_BEFORE", "INTERRUPTED:ROLLBACK_AFTER",
            "CANCELLED:ROLLBACK_BEFORE", "CANCELLED:ROLLBACK_AFTER", "ERROR:ROLLBACK_BEFORE", "ERROR:ROLLBACK_AFTER", "ERROR:ROLLBACK_AFTER_TSE"})
    void expiryPlainRollbackOverrideCannotHideApplicationCancellationInterruptOrFatalError(String fault) throws Exception {
        var now = expiredExceptionFixture(); addExpiredParent(100, now); addExpiredParent(101, now);
        String[] parts = fault.split(":"); var node = new Node(true); var actions = spy(node.actions);
        Throwable original = switch (parts[0]) {
            case "INTERRUPTED" -> new IllegalStateException("fixture-only-application", new InterruptedException("fixture-only-interrupt"));
            case "CANCELLED" -> new CancellationException("fixture-only-cancellation");
            case "ERROR" -> new AssertionError("fixture-only-fatal-error");
            default -> throw new IllegalArgumentException(fault);
        };
        doAnswer(call -> {
            QualityWorkOrderActionDO action = call.getArgument(0); int result = node.actions.insert(action);
            if (action.getWorkOrderId().equals(100L)) throw original;
            return result;
        }).when(actions).insert(any(QualityWorkOrderActionDO.class));
        ReflectionTestUtils.setField(node.target, "actionMapper", actions);
        var starts = new java.util.concurrent.atomic.AtomicInteger();
        var active = new java.util.concurrent.atomic.AtomicReference<org.springframework.transaction.TransactionStatus>();
        ReflectionTestUtils.setField(node.target, "transactionManager", expiryPlainCompletionFault(node, parts[1], 2, starts, active));
        try (var pool = Executors.newSingleThreadExecutor()) {
            var outcome = pool.submit(() -> {
                TenantContextHolder.clear();
                try {
                    Throwable failure = catchThrowable(() -> node.target.expireExceptions(now));
                    return Map.of("failure", failure, "interrupted", Thread.currentThread().isInterrupted());
                } finally {
                    Thread.interrupted();
                    if (active.get() != null && !active.get().isCompleted()) node.transactions.rollback(active.get());
                    TenantContextHolder.clear();
                }
            }).get(10, TimeUnit.SECONDS);
            assertThat(outcome.get("failure")).isInstanceOf(parts[0].equals("ERROR") ? AssertionError.class : CancellationException.class);
            assertThat(outcome.get("interrupted")).isEqualTo(parts[0].equals("INTERRUPTED"));
            if (parts[0].equals("ERROR")) assertThat(outcome.get("failure")).isSameAs(original);
        }
        assertThat(starts.get()).isEqualTo(2); assertExpiredParents(List.of(8L));
        new Node(true).target.expireExceptions(now); assertExpiredParents(List.of(8L, 100L, 101L));
    }

    @ParameterizedTest @ValueSource(strings = {"BEFORE_COMMIT:FIRST", "BEFORE_COMMIT:MIDDLE", "AFTER_COMMIT:FIRST", "AFTER_COMMIT:MIDDLE"})
    void expiryRealSpringCommitSynchronizationFailureStopsBatchWithoutReplayingCommittedChild(String fault) {
        var now = expiredExceptionFixture(); addExpiredParent(100, now); addExpiredParent(101, now);
        String[] parts = fault.split(":"); boolean middle = parts[1].equals("MIDDLE"); long parent = middle ? 100L : 8L;
        var original = new IllegalStateException("fixture-only-spring-commit-synchronization");
        var node = new Node(true); var actions = spy(node.actions); var attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            QualityWorkOrderActionDO action = call.getArgument(0); int result = node.actions.insert(action); attempts.incrementAndGet();
            if (action.getWorkOrderId().equals(parent)) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) { if (parts[0].equals("BEFORE_COMMIT")) throw original; }
                    @Override public void afterCommit() { if (parts[0].equals("AFTER_COMMIT")) throw original; }
                });
            }
            return result;
        }).when(actions).insert(any(QualityWorkOrderActionDO.class));
        ReflectionTestUtils.setField(node.target, "actionMapper", actions);
        Throwable failure = catchThrowable(() -> node.target.expireExceptions(now));
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("事务完成异常")
                .hasMessageContaining("待核查").hasMessageNotContaining("fixture-only");
        assertThat(failure.getSuppressed()[0].getCause()).isSameAs(original);
        assertThat(attempts.get()).isEqualTo(middle ? 2 : 1);
        assertExpiredParents(parts[0].equals("AFTER_COMMIT") ? (middle ? List.of(8L, 100L) : List.of(8L))
                : (middle ? List.of(8L) : List.of()));
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        verifyNoInteractions(node.notifications);
        new Node(true).target.expireExceptions(now); assertExpiredParents(List.of(8L, 100L, 101L));
        new Node(true).target.expireExceptions(now); assertThat(count("work_order")).isEqualTo(6);
    }

    @Test void expiryScanAcceptsBalancedNonTransactionalSynchronization() {
        var now = expiredExceptionFixture(); var node = new Node(true);
        var support = new TransactionTemplate(node.transactions);
        support.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_SUPPORTS);
        support.executeWithoutResult(tx -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
            node.target.expireExceptions(now);
            // 单项已真正提交，而不是等待空的外同步完成。
            assertThat(admin.queryForObject("SELECT count(*) FROM " + schema + "." + PREFIX
                    + "work_order WHERE source_type='EXCEPTION_EXPIRED'", Integer.class)).isEqualTo(1);
            assertExpiredFollowUp();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
        });
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    @Test void expiryScanHonoursInterruptionAfterFinalCommit() throws Exception {
        var now = expiredExceptionFixture(); var node = new Node(true);
        var manager = new org.springframework.transaction.PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return node.transactions.getTransaction(definition);
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) {
                node.transactions.commit(status); Thread.currentThread().interrupt();
            }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) { node.transactions.rollback(status); }
        };
        ReflectionTestUtils.setField(node.target, "transactionManager", manager);
        try (var pool = Executors.newSingleThreadExecutor()) {
            Throwable failure = pool.submit(() -> {
                TenantContextHolder.clear();
                try { return catchThrowable(() -> node.target.expireExceptions(now)); }
                finally { Thread.interrupted(); TenantContextHolder.clear(); }
            }).get(8, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOf(CancellationException.class);
        }
        assertExpiredFollowUp();
        ReflectionTestUtils.setField(node.target, "transactionManager", node.transactions);
        node.target.expireExceptions(now); assertExpiredFollowUp();
    }

    @Test void expiryRollbackFailureStopsBatchAndRetainsApplicationFailure() {
        var now = expiredExceptionFixture(); addExpiredParent(100, now); addExpiredParent(101, now);
        expiryFaultOnParents("work_order", "ERROR", "8"); var node = new Node(true);
        var manager = new org.springframework.transaction.PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return node.transactions.getTransaction(definition);
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) { node.transactions.commit(status); }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) {
                node.transactions.rollback(status);
                throw new org.springframework.transaction.TransactionSystemException("fixture rollback completion failure");
            }
        };
        ReflectionTestUtils.setField(node.target, "transactionManager", manager);
        Throwable failure = catchThrowable(() -> node.target.expireExceptions(now));
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("事务完成异常").hasMessageContaining("待核查");
        assertThat(failure.getSuppressed()).hasSize(1);
        Throwable contextual = failure.getSuppressed()[0];
        assertThat(contextual.getCause()).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        var completion = (org.springframework.transaction.TransactionSystemException) contextual.getCause();
        assertThat(completion.getApplicationException()).isNotNull();
        assertThat(contextual.getSuppressed()).contains(completion.getApplicationException());
        assertExpiredParents(List.of());
        ReflectionTestUtils.setField(node.target, "transactionManager", node.transactions);
        db.execute("DROP TRIGGER expiry_batch_fault ON " + PREFIX + "work_order");
        elapseExpiryBackoff();
        node.target.expireExceptions(now); assertExpiredParents(List.of(8L, 100L, 101L));
    }

    @ParameterizedTest @ValueSource(strings = {"INTERRUPTED", "CANCELLED"})
    void expiryHonoursApplicationCancellationHiddenByRollbackFailure(String mode) throws Exception {
        var now = expiredExceptionFixture(); addExpiredParent(100, now); addExpiredParent(101, now);
        var node = new Node(true); var actions = spy(node.actions);
        doAnswer(call -> {
            QualityWorkOrderActionDO action = call.getArgument(0); int result = node.actions.insert(action);
            if (action.getWorkOrderId().equals(100L)) {
                Throwable stop = mode.equals("INTERRUPTED") ? new InterruptedException("fixture interrupted") : new CancellationException("fixture cancelled");
                throw new IllegalStateException("fixture application stop", stop);
            }
            return result;
        }).when(actions).insert(any(QualityWorkOrderActionDO.class));
        ReflectionTestUtils.setField(node.target, "actionMapper", actions);
        var manager = new org.springframework.transaction.PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return node.transactions.getTransaction(definition);
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) { node.transactions.commit(status); }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) {
                node.transactions.rollback(status);
                throw new org.springframework.transaction.TransactionSystemException("fixture rollback completion failure");
            }
        };
        ReflectionTestUtils.setField(node.target, "transactionManager", manager);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var stopped = pool.submit(() -> {
                TenantContextHolder.clear();
                try {
                    Throwable failure = catchThrowable(() -> node.target.expireExceptions(now));
                    return Map.of("failure", failure, "interrupted", Thread.currentThread().isInterrupted());
                } finally { Thread.interrupted(); TenantContextHolder.clear(); }
            }).get(10, TimeUnit.SECONDS);
            assertThat(stopped.get("failure")).isInstanceOf(CancellationException.class);
            assertThat(stopped.get("interrupted")).isEqualTo(mode.equals("INTERRUPTED"));
            Throwable contextual = ((Throwable) stopped.get("failure")).getSuppressed()[0];
            assertThat(contextual.getCause()).isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
            assertThat(contextual.getSuppressed()).hasSize(1);
        }
        assertExpiredParents(List.of(8L));
        ReflectionTestUtils.setField(node.target, "actionMapper", node.actions);
        ReflectionTestUtils.setField(node.target, "transactionManager", node.transactions);
        node.target.expireExceptions(now); assertExpiredParents(List.of(8L, 100L, 101L));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SYNC:EMPTY", "SYNC:NONEMPTY", "ACTUAL_TX:EMPTY", "ACTUAL_TX:NONEMPTY"})
    void expiryRevalidatesQueryContextBeforeAnyCandidateWrites(String poison) {
        var now = expiredExceptionFixture(); var node = new Node(true);
        String[] parts = poison.split(":"); var scan = spy(node.expiryScan);
        doAnswer(call -> {
            var candidates = node.expiryScan.reserve(now);
            switch (parts[0]) {


                case "SYNC" -> TransactionSynchronizationManager.initSynchronization();
                case "ACTUAL_TX" -> TransactionSynchronizationManager.setActualTransactionActive(true);
                default -> throw new IllegalArgumentException(poison);
            }
            return parts[1].equals("EMPTY") ? List.of() : candidates;
        }).when(scan).reserve(now);
        ReflectionTestUtils.setField(node.target, "exceptionExpiryScanService", scan);
        Throwable failure;
        try { failure = catchThrowable(() -> node.target.expireExceptions(now)); }
        finally {
            if (parts[0].equals("SYNC") && TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationUtils.triggerAfterCompletion(
                        org.springframework.transaction.support.TransactionSynchronization.STATUS_UNKNOWN);
                TransactionSynchronizationManager.clearSynchronization();
            }
            if (parts[0].equals("ACTUAL_TX")) TransactionSynchronizationManager.setActualTransactionActive(false);
            TenantContextHolder.clear(); TenantContextHolder.setIgnore(false);
        }
        assertExpiredParents(List.of()); assertThat(count("work_order")).isEqualTo(1);
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("候选查询上下文异常");
        ReflectionTestUtils.setField(node.target, "exceptionExpiryScanService", node.expiryScan);
        node.target.expireExceptions(now); assertExpiredFollowUp();
    }

    @ParameterizedTest @ValueSource(strings = {"RESOURCE_BINDING"})
    void expiryBatchRefusesToContinueAfterUnbalancedContext(String poison) {
        var now = expiredExceptionFixture(); addExpiredParent(100, now); addExpiredParent(101, now);
        var node = new Node(true); Object key = new Object(); var firstCommit = new java.util.concurrent.atomic.AtomicBoolean(true);
        var manager = new org.springframework.transaction.PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return node.transactions.getTransaction(definition);
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus status) {
                node.transactions.commit(status);
                if (firstCommit.getAndSet(false)) {
                    switch (poison) {
                        case "RESOURCE_BINDING" -> TransactionSynchronizationManager.bindResource(key, new Object());


                        default -> throw new IllegalArgumentException(poison);
                    }
                }
            }
            @Override public void rollback(org.springframework.transaction.TransactionStatus status) { node.transactions.rollback(status); }
        };
        ReflectionTestUtils.setField(node.target, "transactionManager", manager);
        Throwable failure;
        try { failure = catchThrowable(() -> node.target.expireExceptions(now)); }
        finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(key);
            TenantContextHolder.clear(); TenantContextHolder.setIgnore(false);
        }
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("上下文未清理");
        assertExpiredParents(List.of(8L));
        ReflectionTestUtils.setField(node.target, "transactionManager", node.transactions);
        node.target.expireExceptions(now); assertExpiredParents(List.of(8L, 100L, 101L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"work_order:ERROR", "work_order:ZERO", "work_order_issue:ERROR", "work_order_issue:ZERO",
            "work_order_action:ERROR", "work_order_action:ZERO", "work_order_notify:ERROR", "work_order_notify:ZERO"})
    void expiredExceptionWriteFailureRollsBackAndCanRetry(String fault) {
        var now = expiredExceptionFixture();
        var node = new Node(true);
        String[] parts = fault.split(":");
        db.execute("CREATE FUNCTION expiry_fault() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + (parts[1].equals("ERROR") ? "RAISE EXCEPTION 'expiry_fault';" : "RETURN NULL;") + " END $$");
        db.execute("CREATE TRIGGER expiry_fault BEFORE INSERT ON " + PREFIX + parts[0] + " FOR EACH ROW EXECUTE FUNCTION expiry_fault()");
        assertThatThrownBy(() -> node.target.expireExceptions(now)).isInstanceOf(RuntimeException.class);
        assertThat(count("work_order")).isEqualTo(1);
        assertThat(count("work_order_action")).isZero();
        assertThat(count("work_order_issue")).isEqualTo(2);
        assertThat(count("work_order_notify")).isZero();
        assertThat(status()).isEqualTo("PROCESSED");
        verifyNoInteractions(node.notifications);
        db.execute("DROP TRIGGER expiry_fault ON " + PREFIX + parts[0]);
        elapseExpiryBackoff();
        node.target.expireExceptions(now);
        second.target.expireExceptions(now);
        assertExpiredFollowUp();
    }

    @RepeatedTest(6)
    void overlappingExpiryScansCreateOneAtomicFollowUp(RepetitionInfo repetition) throws Exception {
        var now = expiredExceptionFixture();
        // 直接使用生产结构，重复验证真实父工单锁保证同一到期单只创建一个后续工单。
        var node = new Node(true); var other = new Node(true);
        var checked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var actions = spy(node.actions);
        doAnswer(call -> {
            var result = node.actions.selectListByWorkOrderId(8L);
            checked.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Expiry resume timeout");
            return result;
        }).when(actions).selectListByWorkOrderId(8L);
        ReflectionTestUtils.setField(node.target, "actionMapper", actions);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> a = pool.submit(() -> {
                TenantContextHolder.clear();
                try { node.target.expireExceptions(now); } finally { TenantContextHolder.clear(); }
            });
            Future<?> b = null;
            try {
                assertThat(checked.await(3, TimeUnit.SECONDS)).isTrue();
                b = pool.submit(() -> {
                    TenantContextHolder.clear();
                    try { other.target.expireExceptions(now); } finally { TenantContextHolder.clear(); }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean locked = false;
                while (!b.isDone() && !locked && System.nanoTime() < deadline) {
                    locked = db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE pid<>pg_backend_pid() "
                            + "AND datname=current_database() AND state='active' AND wait_event_type='Lock' "
                            + "AND query LIKE '%dh_governance_quality_work_order%'", Integer.class) > 0;
                    if (!locked && !b.isDone()) Thread.sleep(10);
                }
                assertThat(b.isDone() || locked).as("second node completed or reached actual parent row lock").isTrue();
            } finally { release.countDown(); }
            a.get(8, TimeUnit.SECONDS); b.get(8, TimeUnit.SECONDS);
        }
        assertExpiredFollowUp();
        verifyNoInteractions(node.notifications, other.notifications);
    }

    @Test void expiredFollowUpIsInvisibleUntilProblemRelationsCommit() throws Exception {
        var now = expiredExceptionFixture(); var node = new Node(true);
        var mapper = (QualityWorkOrderMapper) ReflectionTestUtils.getField(node.target, "workOrderMapper");
        var spy = spy(mapper); var inserted = new CountDownLatch(1); var release = new CountDownLatch(1);
        var childId = new java.util.concurrent.atomic.AtomicReference<Long>();
        doAnswer(call -> {
            QualityWorkOrderDO child = call.getArgument(0);
            int result = mapper.insert(child); childId.set(child.getId()); inserted.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Child resume timeout");
            return result;
        }).when(spy).insert(any(QualityWorkOrderDO.class));
        ReflectionTestUtils.setField(node.target, "workOrderMapper", spy);
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<?> created = pool.submit(() -> {
                TenantContextHolder.clear();
                try { node.target.expireExceptions(now); } finally { TenantContextHolder.clear(); }
            });
            try {
                assertThat(inserted.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(db.queryForObject("SELECT count(*) FROM " + PREFIX + "work_order WHERE id=?", Integer.class, childId.get())).isZero();
                assertThatThrownBy(() -> second.service.deleteWorkOrder(childId.get())).extracting("code")
                        .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_NOT_EXISTS.getCode());
            } finally { release.countDown(); }
            created.get(8, TimeUnit.SECONDS);
        }
        assertExpiredFollowUp();
        assertThat(db.queryForObject("SELECT count(*) FROM " + PREFIX + "work_order_issue i JOIN " + PREFIX
                + "work_order w ON i.work_order_id=w.id WHERE i.deleted=0 AND w.deleted=1", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"WITHDRAWN", "SUPERSEDED", "FUTURE", "TYPE", "CURRENT", "CLOSE", "OPEN", "DELETED",
            "NULL_EXPIRY", "ORDER_BINDING", "ORDER_DELETED"})
    void staleExpiryCandidatesAreRevalidatedUnderParentLock(String change) {
        var now = expiredExceptionFixture(); var node = new Node(true);
        var mapper = (QualityWorkOrderSubmissionMapper) ReflectionTestUtils.getField(node.target, "submissionMapper");
        var candidates = mapper.selectExpiredExceptions(now, 20); assertThat(candidates).hasSize(1);
        switch (change) {
            case "WITHDRAWN", "SUPERSEDED" -> db.update("UPDATE " + PREFIX + "work_order_submission SET status=? WHERE id=11", change);
            case "FUTURE" -> db.update("UPDATE " + PREFIX + "work_order_submission SET exception_expire_at=? WHERE id=11", now.plusDays(1));
            case "TYPE" -> db.update("UPDATE " + PREFIX + "work_order_submission SET submission_type='EXPLAIN' WHERE id=11");
            case "CURRENT" -> db.update("UPDATE " + PREFIX + "work_order SET current_submission_id=null WHERE id=8");
            case "CLOSE" -> db.update("UPDATE " + PREFIX + "work_order SET close_type='EXPLAINED' WHERE id=8");
            case "OPEN" -> db.update("UPDATE " + PREFIX + "work_order SET order_status='IN_PROGRESS' WHERE id=8");
            case "DELETED" -> db.update("UPDATE " + PREFIX + "work_order_submission SET deleted=1 WHERE id=11");

            case "NULL_EXPIRY" -> db.update("UPDATE " + PREFIX + "work_order_submission SET exception_expire_at=null WHERE id=11");
            case "ORDER_BINDING" -> db.update("UPDATE " + PREFIX + "work_order_submission SET work_order_id=9 WHERE id=11");
            case "ORDER_DELETED" -> db.update("UPDATE " + PREFIX + "work_order SET deleted=1 WHERE id=8");

            default -> throw new IllegalArgumentException(change);
        }
        var scan = spy(node.expiryScan); doReturn(candidates).when(scan).reserve(now);
        ReflectionTestUtils.setField(node.target, "exceptionExpiryScanService", scan);
        node.target.expireExceptions(now);
        assertThat(count("work_order")).isEqualTo(1); assertThat(count("work_order_action")).isZero();
        assertThat(count("work_order_notify")).isZero(); assertThat(count("work_order_issue")).isEqualTo(2);
    }

    @Test void expiryScanningAdvancesPastCompletedFirstPage() {
        var now = expiredExceptionFixture(); var node = new Node(true);
        for (long id = 100; id < 122; id++) {
            db.update("INSERT INTO " + PREFIX + "work_order(id,order_name,order_status,close_type,current_submission_id,lock_version) VALUES(?,'分页例外','PROCESSED','EXCEPTION_ACCEPTED',?,0)", id, id);
            db.update("INSERT INTO " + PREFIX + "work_order_submission(id,work_order_id,revision,submission_type,payload_hash,submitted_by,submitted_at,status,request_key,exception_expire_at) VALUES(?,?,1,'EXCEPTION',repeat('c',64),4,now(),'ACTIVE',?,?)", id, id, "expiry-page-" + id, now.minusDays(1));
        }
        node.target.expireExceptions(now);
        assertThat(count("work_order_action")).isEqualTo(20);
        node.target.expireExceptions(now);
        assertThat(count("work_order_action")).isEqualTo(23);
        node.target.expireExceptions(now);
        assertThat(count("work_order_action")).isEqualTo(23);
        assertThat(db.queryForObject("SELECT count(*) FROM " + PREFIX + "work_order WHERE source_type='EXCEPTION_EXPIRED'", Integer.class)).isEqualTo(23);
        verifyNoInteractions(node.notifications);
    }

    @Test void expiryScanningSkipsInvalidFirstPageWithoutTenantContext() {
        var now = expiredExceptionFixture(); var node = new Node(true);
        // 比有效提交更早的候选缺少合法关闭父单，不应占据 LIMIT 20。
        db.update("UPDATE " + PREFIX + "work_order_submission SET id=200 WHERE id=11");
        db.update("UPDATE " + PREFIX + "work_order SET current_submission_id=200 WHERE id=8");
        for (long id = 100; id < 121; id++) {
            db.update("INSERT INTO " + PREFIX + "work_order_submission(id,work_order_id,revision,submission_type,payload_hash,submitted_by,submitted_at,status,request_key,exception_expire_at) VALUES(?,999,1,'EXCEPTION',repeat('c',64),4,now(),'ACTIVE',?,?)", id, "invalid-expiry-" + id, now.minusDays(1));
        }
        TenantContextHolder.clear();
        node.target.expireExceptions(now);
        assertThat(count("work_order_action")).isEqualTo(1);
        TenantContextHolder.clear();
        node.target.expireExceptions(now);
        assertThat(count("work_order_action")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT request_id FROM " + PREFIX + "work_order_action", String.class)).isEqualTo("expire-200");
    }

    @ParameterizedTest @ValueSource(strings = {"EXACT_DEADLINE", "UNASSIGNED", "LONG_NAME"})
    void expiredFollowUpPreservesDomainBoundaries(String boundary) {
        var now = expiredExceptionFixture(); var node = new Node(true);
        db.update("UPDATE " + PREFIX + "work_order SET root_order_id=99,task_name='原任务',handling_dept_id=6 WHERE id=8");
        if (boundary.equals("EXACT_DEADLINE")) db.update("UPDATE " + PREFIX + "work_order_submission SET exception_expire_at=? WHERE id=11", now);
        if (boundary.equals("UNASSIGNED")) db.update("UPDATE " + PREFIX + "work_order SET owner_user_id=null WHERE id=8");
        if (boundary.equals("LONG_NAME")) db.update("UPDATE " + PREFIX + "work_order SET order_name=repeat('名',256) WHERE id=8");
        node.target.expireExceptions(now);
        var child = db.queryForMap("SELECT * FROM " + PREFIX + "work_order WHERE source_type='EXCEPTION_EXPIRED'");
        assertThat(child.get("root_order_id")).isEqualTo(99L); assertThat(child.get("task_id")).isEqualTo(3L);
        assertThat(child.get("task_name")).isEqualTo("原任务"); assertThat(child.get("handling_dept_id")).isEqualTo(6L);
        assertThat(child.get("issue_count")).isEqualTo(2L); assertThat(child.get("order_status")).isEqualTo("PENDING");
        assertThat(child.get("lock_version")).isEqualTo(0); assertThat(child.get("close_type")).isNull();
        assertThat((String) child.get("order_name")).endsWith("-例外到期复核").hasSizeLessThanOrEqualTo(256);
        if (boundary.equals("UNASSIGNED")) {
            assertThat(child.get("owner_user_id")).isNull(); assertThat(child.get("responsibility_status")).isEqualTo("UNASSIGNED");
            assertThat(count("work_order_notify")).isZero();
        } else {
            assertExpiredFollowUp(); assertThat(child.get("responsibility_status")).isEqualTo("ASSIGNED");
        }
        verifyNoInteractions(node.notifications);
    }

    @Test void partialProblemCopyRollsBackAllExpiryWrites() {
        var now = expiredExceptionFixture(); var node = new Node(true);
        db.execute("CREATE FUNCTION expiry_partial() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.issue_id=31 THEN RETURN NULL; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER expiry_partial BEFORE INSERT ON " + PREFIX + "work_order_issue FOR EACH ROW EXECUTE FUNCTION expiry_partial()");
        assertThatThrownBy(() -> node.target.expireExceptions(now)).isInstanceOf(RuntimeException.class);
        assertThat(count("work_order")).isEqualTo(1); assertThat(count("work_order_action")).isZero();
        assertThat(count("work_order_issue")).isEqualTo(2); assertThat(count("work_order_notify")).isZero();
        db.execute("DROP TRIGGER expiry_partial ON " + PREFIX + "work_order_issue");
        elapseExpiryBackoff();
        node.target.expireExceptions(now); assertExpiredFollowUp();
    }

    @Test void expiryScanRefusesCallerTransaction() {
        var now = expiredExceptionFixture(); var node = new Node(true);
        new TransactionTemplate(node.transactions).executeWithoutResult(tx ->
                assertThatThrownBy(() -> node.target.expireExceptions(now)).isInstanceOf(IllegalStateException.class));
        assertThat(count("work_order")).isEqualTo(1); assertThat(count("work_order_action")).isZero();
    }

    @RepeatedTest(3) void overlappingDeleteAndConfirmedSubmissionCannotBothCommit() throws Exception {
        db.update("UPDATE " + PREFIX + "work_order SET order_status='IN_PROGRESS',current_submission_id=null WHERE id=8");
        db.update("DELETE FROM " + PREFIX + "work_order_submission WHERE work_order_id=8");
        db.update("INSERT INTO " + PREFIX + "issue_data(id) VALUES(30)");
        db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(8,30)");
        var deleting = new Node(true); var submitting = new Node(true);
        var counted = new CountDownLatch(1); var release = new CountDownLatch(1);
        var actionSpy = spy(deleting.actions);
        doAnswer(call -> {
            long count = deleting.actions.countByWorkOrderId(8L);
            counted.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Delete release timeout");
            return count;
        }).when(actionSpy).countByWorkOrderId(8L);
        ReflectionTestUtils.setField(deleting.target, "actionMapper", actionSpy);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Throwable> deletion = pool.submit(() -> {
                TenantContextHolder.clear();
                try { deleting.service.deleteWorkOrder(8L); return null; }
                catch (Throwable failure) { return failure; } finally { TenantContextHolder.clear(); }
            });
            Future<Throwable> submission = null;
            try {
                assertThat(counted.await(3, TimeUnit.SECONDS)).isTrue();
                submission = pool.submit(() -> {
                    TenantContextHolder.clear();
                    try {
                        var req = command("delete-race-submit", 1); req.setSubmissionType("EXPLAIN");
                        req.setContent("核验说明"); req.setIssueIds(List.of(30L));
                        var accepted = submitting.service.submitResolution(req);
                        when(submitting.guard.requireUserId()).thenReturn(9L);
                        var confirm = command("delete-race-confirm", 2); confirm.setConfirmAction("ACCEPT_EXPLAIN");
                        confirm.setSubmissionId(accepted.getCurrentSubmissionId()); submitting.service.confirm(confirm);
                        return null;
                    } catch (Throwable failure) { return failure; } finally { TenantContextHolder.clear(); }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean locked = false;
                while (!submission.isDone() && !locked && System.nanoTime() < deadline) {
                    locked = db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE pid<>pg_backend_pid() "
                            + "AND datname=current_database() AND state='active' AND wait_event_type='Lock' "
                            + "AND query LIKE '%dh_governance_quality_work_order%'", Integer.class) > 0;
                    if (!locked && !submission.isDone()) Thread.sleep(10);
                }
                assertThat(submission.isDone() || locked).as("submission committed or reached the real row lock").isTrue();
            } finally { release.countDown(); }
            Throwable deleteFailure = deletion.get(8, TimeUnit.SECONDS);
            Throwable submitFailure = submission.get(8, TimeUnit.SECONDS);
            assertThat(deleteFailure == null && submitFailure == null).as("delete and confirmed submission must not both commit").isFalse();
            assertThat(deleteFailure).isNull();
            assertThat(submitFailure).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        }
        assertThat(count("work_order_action")).isZero();
        assertThat(db.queryForObject("SELECT deleted FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT deleted FROM " + PREFIX + "work_order_issue WHERE work_order_id=8", Integer.class)).isEqualTo(1);
    }

    @Test void zeroRowDeleteCannotRemoveOriginalProblemRelations() {
        db.update("UPDATE " + PREFIX + "work_order SET order_status='IN_PROGRESS',current_submission_id=null WHERE id=8");
        db.update("DELETE FROM " + PREFIX + "work_order_submission WHERE work_order_id=8");
        db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(8,30)");
        db.execute("CREATE FUNCTION ignore_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.deleted=1 THEN RETURN NULL; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER ignore_delete BEFORE UPDATE ON " + PREFIX + "work_order FOR EACH ROW EXECUTE FUNCTION ignore_delete()");
        var deleting = new Node(true);
        assertThatThrownBy(() -> deleting.service.deleteWorkOrder(8L)).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode());
        assertThat(db.queryForObject("SELECT deleted FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT deleted FROM " + PREFIX + "work_order_issue WHERE work_order_id=8", Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"PERMISSION", "OBJECT_SCOPE"})
    void forbiddenDeleteCannotModifyOrderOrRelations(String denial) {
        db.update("UPDATE " + PREFIX + "work_order SET current_submission_id=null WHERE id=8");
        db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(8,30)");
        var deleting = new Node(true);
        if ("PERMISSION".equals(denial)) {
            when(deleting.guard.has(4L, "governance:quality-work-order:delete")).thenReturn(false);
        } else {
            doThrow(cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception(
                    cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_NOT_EXISTS))
                    .when(deleting.guard).assertVisible(any(), any());
        }
        assertThatThrownBy(() -> deleting.service.deleteWorkOrder(8L)).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_NOT_EXISTS.getCode());
        assertThat(db.queryForObject("SELECT deleted FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT deleted FROM " + PREFIX + "work_order_issue WHERE work_order_id=8", Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"SUBMISSION", "ACTION", "PROCESSED"})
    void protectedDeleteKeepsHistoricalOrderEvenWithoutCurrentSubmissionPointer(String history) {
        db.update("UPDATE " + PREFIX + "work_order SET current_submission_id=null WHERE id=8");
        if (!"SUBMISSION".equals(history)) db.update("DELETE FROM " + PREFIX + "work_order_submission WHERE work_order_id=8");
        if ("ACTION".equals(history)) {
            first.actions.insert(QualityWorkOrderActionDO.builder().workOrderId(8L).requestId("historical-action")
                    .actionType("WITHDRAW").actorUserId(4L).payloadHash("b".repeat(64)).build());
        } else if ("PROCESSED".equals(history)) {
            db.update("UPDATE " + PREFIX + "work_order SET order_status='PROCESSED' WHERE id=8");
        }
        assertThatThrownBy(() -> first.service.deleteWorkOrder(8L)).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_HISTORY_READONLY.getCode());
        assertThat(db.queryForObject("SELECT deleted FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"ASSIGNED", "UNASSIGNED"})
    void successfulDeletePreservesLogicalRowsAndDoesNotRequireProcessPermission(String responsibility) {
        db.update("UPDATE " + PREFIX + "work_order SET current_submission_id=null,order_status='PENDING',responsibility_status=? WHERE id=8", responsibility);
        db.update("DELETE FROM " + PREFIX + "work_order_submission WHERE work_order_id=8");
        db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(8,30)");
        if ("UNASSIGNED".equals(responsibility)) db.update("UPDATE " + PREFIX + "work_order SET owner_user_id=null WHERE id=8");
        var deleting = new Node(true);
        when(deleting.guard.has(4L, QualityWorkOrderGuard.PERM_PROCESS)).thenReturn(false);
        deleting.service.deleteWorkOrder(8L);
        verify(deleting.guard).has(4L, QualityWorkOrderGuard.PERM_DELETE);
        verify(deleting.guard).assertVisible(any(), eq(4L));
        verify(deleting.guard, never()).assertHandle(any(), any());
        assertThat(count("work_order")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT deleted FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT deleted FROM " + PREFIX + "work_order_issue WHERE work_order_id=8", Integer.class)).isEqualTo(1);
        assertThat(count("work_order_action")).isZero();
        assertThat(count("work_order_notify")).isZero();
        assertThatThrownBy(() -> second.service.deleteWorkOrder(8L)).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_NOT_EXISTS.getCode());
    }

    @ParameterizedTest @ValueSource(strings = {"ERROR", "ZERO_ROW"})
    void relationDeleteFailureRollsBackLogicalOrderDeletion(String mode) {
        db.update("UPDATE " + PREFIX + "work_order SET current_submission_id=null WHERE id=8");
        db.update("DELETE FROM " + PREFIX + "work_order_submission WHERE work_order_id=8");
        db.update("INSERT INTO " + PREFIX + "work_order_issue(work_order_id,issue_id) VALUES(8,30)");
        db.execute("CREATE FUNCTION reject_relation_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + ("ERROR".equals(mode) ? "RAISE EXCEPTION 'reject_relation_delete';" : "RETURN NULL;") + " END $$");
        db.execute("CREATE TRIGGER reject_relation_delete BEFORE UPDATE ON " + PREFIX + "work_order_issue FOR EACH ROW EXECUTE FUNCTION reject_relation_delete()");
        var deleting = new Node(true);
        if ("ERROR".equals(mode)) {
            assertThatThrownBy(() -> deleting.service.deleteWorkOrder(8L)).isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("reject_relation_delete");
        } else {
            assertThatThrownBy(() -> deleting.service.deleteWorkOrder(8L)).extracting("code")
                    .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode());
        }
        assertThat(db.queryForObject("SELECT deleted FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT deleted FROM " + PREFIX + "work_order_issue WHERE work_order_id=8", Integer.class)).isZero();
    }



    @Test void pausedDeleteCannotRemoveAnotherNodesNewlySubmittedOrder() throws Exception {
        db.update("UPDATE " + PREFIX + "work_order SET current_submission_id=null,order_status='IN_PROGRESS' WHERE id=8");
        db.update("DELETE FROM " + PREFIX + "work_order_submission WHERE work_order_id=8");
        var deleting = new Node(true);
        var authorized = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            authorized.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Delete authorization release timeout");
            return null;
        }).when(deleting.guard).assertVisible(any(), any());
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Throwable> deletion = pool.submit(() -> {
                TenantContextHolder.clear();
                try { deleting.service.deleteWorkOrder(8L); return null; }
                catch (Throwable failure) { return failure; } finally { TenantContextHolder.clear(); }
            });
            try {
                assertThat(authorized.await(3, TimeUnit.SECONDS)).isTrue();
                var req = command("submission-before-delete", 1); req.setSubmissionType("EXPLAIN"); req.setContent("保留本次说明");
                second.service.submitResolution(req);
            } finally { release.countDown(); }
            assertThat(deletion.get(8, TimeUnit.SECONDS)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        }
        assertThat(status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(count("work_order_action")).isEqualTo(1);
        assertThat(count("work_order_submission")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT deleted FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isZero();
    }

    @Test void deleteRevalidatesAuthorizationSnapshotEvenWhenVersionDidNotAdvance() throws Exception {
        db.update("UPDATE " + PREFIX + "work_order SET current_submission_id=null WHERE id=8");
        db.update("DELETE FROM " + PREFIX + "work_order_submission WHERE work_order_id=8");
        var deleting = new Node(true);
        doAnswer(call -> {
            db.update("UPDATE " + PREFIX + "work_order SET owner_user_id=99 WHERE id=8");
            return null;
        }).when(deleting.guard).assertVisible(any(), any());
        assertThatThrownBy(() -> deleting.service.deleteWorkOrder(8L)).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode());
        assertThat(db.queryForObject("SELECT deleted FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isZero();
    }

    @Test void migrationReplayRetainsPreviousAttemptAndNextAttemptUsesNewKey() throws Exception {
        var initial = first.service.recheck(command("before-upgrade", 1));
        db.update("UPDATE " + PREFIX + "task_instance SET instance_status='FAILED' WHERE id=?", initial.getCurrentSubmission().getInstanceId());
        second.service.evaluateRechecks();
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(db, schema);
        first.service.recheck(command("after-upgrade", 3));
        assertThat(first.rechecks.selectBySubmission(11L)).extracting("attemptNo").containsExactly(2, 1);
        assertThat(first.rechecks.selectBySubmission(11L).getFirst().getRequestKey()).isEqualTo("wo:8:sub:11:attempt:2");
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(db, schema);
        assertThat(count("work_order_recheck")).isEqualTo(2);
    }

    @Test void receiptsAndRunningScanAreSharedAcrossNodesWithoutTenantContext() {
        first.service.recheck(command("global-request", 1));
        TenantContextHolder.clear();
        assertThat(second.rechecks.selectRunning(20)).hasSize(1);
        assertThat(second.rechecks.selectBySubmission(11L)).hasSize(1);
        second.service.recheck(command("global-request", 1));
        verify(second.executor, never()).dispatchGate(any());
        assertThat(count("work_order_recheck")).isEqualTo(1);
    }

    @Test void completionAfterWithdrawalCannotChangeCurrentOrder() {
        first.service.recheck(command("before-withdraw", 1));
        first.service.withdraw(command("withdraw", 2));
        db.update("UPDATE " + PREFIX + "task_instance SET instance_status='FAILED'");
        second.service.evaluateRechecks();
        assertThat(status()).isEqualTo("IN_PROGRESS");
        assertThat(first.rechecks.selectBySubmission(11L).getFirst().getStatus()).isEqualTo("OBSOLETE");
        assertThat(db.queryForObject("SELECT lock_version FROM " + PREFIX + "work_order WHERE id=8", Integer.class)).isEqualTo(3);
    }

    @Test void evidenceMigrationReplaysWithoutRewritingExistingReceipts() throws Exception {

        first.service.recheck(command("evidence-replay", 1));
        db.update("UPDATE " + PREFIX + "work_order_recheck SET evidence_json=?", "{\"schemaVersion\":1,\"fixture\":true}");
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(db, schema);
        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.upgrade(db, schema);
        assertThat(first.rechecks.selectBySubmission(11L).getFirst().getEvidenceJson())
                .isEqualTo("{\"schemaVersion\":1,\"fixture\":true}");
        assertThat(db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? "
                + "AND table_name=? AND column_name='read_evidence'", Integer.class, schema, PREFIX + "audit_log")).isEqualTo(1);
    }

    @Test void actualRuleQueryPersistsReadEvidenceWithItsAudit() throws Exception {
        db.execute("CREATE TABLE orders(amount integer)");
        db.update("INSERT INTO orders VALUES(5),(20)");
        var executor = QualityRuntimeFixture.executor();
        ReflectionTestUtils.setField(executor, "auditLogMapper", first.audits);
        ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
        var connection = new MasterDatabaseResolver.ConnectionInfo();
        connection.setDatabaseId(4L); connection.setProductType(ProductTypeEnum.POSTGRESQL);
        connection.setSchema(schema); connection.setUsageType(1);
        connection.setConfigurationRevision("2026-09-23T10:00:00.123456");
        var object = QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L)
                .datasourceType("THIRD_PARTY").tableName("orders").build();
        var rule = QualityRuleDO.builder().id(2L).ruleName("金额下限").ruleType("RANGE")
                .checkLevel("FIELD").status(0).strategyKey("NUMERIC_RANGE_VALIDATION")
                .paramSchema("{\"column\":\"amount\",\"minValue\":10}").collectIssueDetail(0).build();
        LocalDateTime before = LocalDateTime.now();
        QualityAuditLogDO audit;
        try (var jdbc = source(schema).getConnection()) {
            audit = QualityRuntimeFixture.executeRule(executor, 20L,
                    QualityTaskDO.builder().id(3L).build(), jdbc, connection, object, rule);
        }
        assertThat(audit).isNotNull();
        var stored = first.audits.selectById(audit.getId());
        assertThat(stored.getResultType()).isEqualTo(QualityAuditLogDO.RESULT_FAIL);
        assertThat(stored.getTotalCount()).isEqualTo(2);
        assertThat(stored.getFailCount()).isEqualTo(1);
        var read = JsonUtils.parseObject(stored.getReadEvidence(), QualityReadEvidence.class);
        assertThat(read.startedTime()).isAfterOrEqualTo(before);
        assertThat(read.finishedTime()).isAfterOrEqualTo(read.startedTime());
        assertThat(read.getReadMode()).isEqualTo("SOURCE_DIRECT");
        assertThat(read.getCoverage()).isEqualTo("FULL_PREDICATE");
        assertThat(read.getDatabaseSchema()).isEqualTo(schema);
        assertThat(read.getConfigurationRevision()).isEqualTo("2026-09-23T10:00:00.123456");
        assertThat(read.getSqlHash()).isEqualTo(cn.hutool.crypto.digest.DigestUtil.sha256Hex(stored.getResultSql()));
    }

    @Test void comparisonDecisionPersistsAndOnlyCurrentReceiptCanConfirm() {
        long instance = prepareComparableAttempt();
        second.service.evaluateRechecks();
        var receipt = first.rechecks.selectByInstance(instance);
        assertThat(receipt.getStatus()).isEqualTo("PASS");
        assertThat(receipt.getComparableFlag()).isEqualTo(1);
        var decision = JsonUtils.parseObject(receipt.getEvidenceJson(), QualityRecheckEvidence.Decision.class);
        assertThat(decision.workOrderId()).isEqualTo(8L);
        assertThat(decision.submissionId()).isEqualTo(11L);
        assertThat(decision.instanceId()).isEqualTo(instance);
        assertThat(decision.schemaVersion()).isEqualTo(3);
        assertThat(decision.binding().attemptId()).isEqualTo(receipt.getId());
        assertThat(decision.binding().attemptNo()).isEqualTo(1);
        assertThat(decision.binding().issueIds()).containsExactly(30L);
        assertThat(decision.checks()).hasSize(1);
        assertThat(decision.checks().getFirst()).containsEntry("reasonCode", "COMPARABLE");
        assertThat(status()).isEqualTo("PENDING_CONFIRMATION");
        when(first.guard.requireUserId()).thenReturn(9L);
        var command = command("confirm", 3);
        command.setConfirmAction("VERIFY_PASS");
        var response = first.service.confirm(command);
        assertThat(response.getOrderStatus()).isEqualTo("PROCESSED");
        assertThat(response.getCurrentSubmission().getRechecks().getFirst().getEvidenceJson()).isEqualTo(receipt.getEvidenceJson());
        assertThat(db.queryForObject("SELECT close_type FROM " + PREFIX + "work_order WHERE id=8", String.class))
                .isEqualTo("VERIFIED_PASS");
    }

    @Test void unchangedOrganizationPassWithinToleranceCanStillConfirmAcrossNodes() {
        long instance = prepareComparableAttempt();
        var definition = JsonUtils.parseObject(db.queryForObject(
                "SELECT definition_snapshot FROM " + PREFIX + "task_instance WHERE id=?", String.class, instance),
                QualityTaskExecutor.GateDefinition.class);
        definition.getTask().setOrgField("org_code");
        definition.getRules().getFirst().setParamSchema(
                "{\"column\":\"amount\",\"minValue\":10,\"toleranceType\":\"PASS_RATE\",\"passRateLimit\":95}");
        db.update("UPDATE " + PREFIX + "task_instance SET definition_snapshot=? WHERE id IN (900,?)",
                JsonUtils.toJsonString(definition), instance);
        var issue = first.issues.selectById(30L);
        issue.setStatScope("ORG"); issue.setOrgId(9L); issue.setRegionCode("9");
        String keyHash = cn.hutool.crypto.digest.DigestUtil.sha256Hex("9");
        for (long id : List.of(900L, instance)) {
            var audit = first.audits.selectListByInstanceId(id).getFirst();
            var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            var scope = new QualityReadEvidence.OrgScope();
            scope.setSchemaVersion(1); scope.setComplete(true); scope.setField("org_code");
            scope.setSqlHash(cn.hutool.crypto.digest.DigestUtil.sha256Hex("fixture grouped SQL"));
            scope.setStartedAt(read.finishedTime().plusSeconds(1).toString());
            scope.setFinishedAt(read.finishedTime().plusSeconds(2).toString());
            long fail = id == 900L ? 10L : 1L;
            scope.setGroups(List.of(new QualityReadEvidence.OrgCount(keyHash, 100L, fail,
                    id == 900L ? "FAIL" : "PASS")));
            read.setOrgScope(scope);
            db.update("UPDATE " + PREFIX + "audit_log SET read_evidence=?,fail_count=? WHERE id=?",
                    JsonUtils.toJsonString(read), fail, audit.getId());
        }
        second.service.evaluateRechecks();
        var receipt = first.rechecks.selectByInstance(instance);
        assertThat(receipt.getStatus()).isEqualTo("PASS");
        var decision = JsonUtils.parseObject(receipt.getEvidenceJson(), QualityRecheckEvidence.Decision.class);
        assertThat(decision.checks().getFirst()).containsEntry("statScope", "ORG").containsEntry("resultType", "PASS");
        assertThat(((Number) decision.checks().getFirst().get("fail")).longValue()).isEqualTo(1L);
        when(first.guard.requireUserId()).thenReturn(9L);
        var command = command("confirm-org-tolerance", 3);
        command.setConfirmAction("VERIFY_PASS");
        assertThat(first.service.confirm(command).getCloseType()).isEqualTo(QualityWorkOrderDO.CLOSE_VERIFIED_PASS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"attempt", "submission", "scope", "object", "rule", "baseline"})
    void persistedDecisionRejectsChangedAttemptSubmissionOrLiveIssueScope(String changed) {
        long instance = prepareComparableAttempt();
        second.service.evaluateRechecks();
        assertThat(first.rechecks.selectByInstance(instance).getStatus()).isEqualTo("PASS");
        switch (changed) {
            case "attempt" -> db.update("UPDATE " + PREFIX + "work_order_recheck SET request_key='changed-request' WHERE instance_id=?", instance);
            case "submission" -> db.update("UPDATE " + PREFIX + "work_order_submission SET content='changed resolution' WHERE id=11");
            case "scope" -> when(first.relations.selectListByWorkOrderId(8L)).thenReturn(List.of(
                    QualityWorkOrderIssueDO.builder().issueId(30L).build(), QualityWorkOrderIssueDO.builder().issueId(31L).build()));
            case "object" -> first.issues.selectById(30L).setObjectId(99L);
            case "rule" -> first.issues.selectById(30L).setRuleId(99L);
            case "baseline" -> first.issues.selectById(30L).setInstanceId(901L);
            default -> throw new IllegalArgumentException(changed);
        }
        when(first.guard.requireUserId()).thenReturn(9L);
        var command = command("reject-changed-" + changed, 3);
        command.setConfirmAction("VERIFY_PASS");
        assertThatThrownBy(() -> first.service.confirm(command)).extracting("code").isEqualTo(
                cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_RECHECK_NOT_PASS.getCode());
        assertThat(status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(count("work_order_action")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"current-result", "current-count", "current-read", "current-sql",
            "current-definition", "current-status", "current-missing", "current-audit-missing",
            "baseline-definition", "baseline-read", "baseline-audit-missing", "duplicate-audit"})
    void persistedPassCannotCloseAfterUnderlyingExecutionEvidenceChanges(String changed) {
        long instance = prepareComparableAttempt();
        second.service.evaluateRechecks();
        assertThat(first.rechecks.selectByInstance(instance).getStatus()).isEqualTo("PASS");
        switch (changed) {
            case "current-result" -> db.update("UPDATE " + PREFIX + "audit_log SET result_type='FAIL' WHERE instance_id=?", instance);
            case "current-count" -> db.update("UPDATE " + PREFIX + "audit_log SET total_count=101 WHERE instance_id=?", instance);
            case "current-read" -> db.update("UPDATE " + PREFIX + "audit_log SET read_evidence=NULL WHERE instance_id=?", instance);
            case "current-sql" -> db.update("UPDATE " + PREFIX + "audit_log SET result_sql='SELECT 1' WHERE instance_id=?", instance);
            case "current-definition" -> db.update("UPDATE " + PREFIX + "task_instance SET definition_snapshot='{}' WHERE id=?", instance);
            case "current-status" -> db.update("UPDATE " + PREFIX + "task_instance SET instance_status='UNKNOWN' WHERE id=?", instance);
            case "current-missing" -> db.update("DELETE FROM " + PREFIX + "task_instance WHERE id=?", instance);
            case "current-audit-missing" -> db.update("DELETE FROM " + PREFIX + "audit_log WHERE instance_id=?", instance);
            case "baseline-definition" -> db.update("UPDATE " + PREFIX + "task_instance SET definition_snapshot='{}' WHERE id=900");
            case "baseline-read" -> db.update("UPDATE " + PREFIX + "audit_log SET read_evidence=NULL WHERE instance_id=900");
            case "baseline-audit-missing" -> db.update("DELETE FROM " + PREFIX + "audit_log WHERE instance_id=900");
            case "duplicate-audit" -> {
                var duplicate = first.audits.selectListByInstanceId(instance).getFirst();
                duplicate.setId(null);
                duplicate.setDeleted(null); // 使用平台 smallint 默认值，不把读取后的 Boolean 作为 INSERT 参数。
                first.audits.insert(duplicate);
            }
            default -> throw new IllegalArgumentException(changed);
        }
        when(first.guard.requireUserId()).thenReturn(9L);
        var command = command("reject-execution-" + changed, 3);
        command.setConfirmAction("VERIFY_PASS");
        assertThatThrownBy(() -> first.service.confirm(command)).extracting("code").isEqualTo(
                cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_RECHECK_NOT_PASS.getCode());
        assertThat(status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(count("work_order_action")).isEqualTo(1);
        assertThat(first.rechecks.selectByInstance(instance).getStatus()).isEqualTo("PASS");
    }

    @Test void legacyPassWithoutDecisionCannotBeConfirmed() {
        var run = first.service.recheck(command("legacy", 1));
        long instance = run.getCurrentSubmission().getInstanceId();
        db.update("UPDATE " + PREFIX + "work_order SET order_status='PENDING_CONFIRMATION',confirmation_pending_type='RECHECK'");
        db.update("UPDATE " + PREFIX + "work_order_submission SET recheck_status='PASS',comparable_flag=1");
        db.update("UPDATE " + PREFIX + "work_order_recheck SET status='PASS',comparable_flag=1 WHERE instance_id=?", instance);
        when(first.guard.requireUserId()).thenReturn(9L);
        var command = command("confirm-legacy", 2);
        command.setConfirmAction("VERIFY_PASS");
        assertThatThrownBy(() -> first.service.confirm(command))
                .extracting("code").isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants
                        .QUALITY_WORK_ORDER_RECHECK_NOT_PASS.getCode());
        assertThat(status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(count("work_order_action")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"work_order_recheck", "work_order_submission"})
    void zeroRowRecheckAdmissionRollsBackRunBindingAndDoesNotDispatch(String target) {
        db.execute("CREATE FUNCTION fixture_ignore_admission() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NULL; END'");
        String event = "work_order_recheck".equals(target) ? "INSERT" : "UPDATE";
        db.execute("CREATE TRIGGER fixture_ignore_admission BEFORE " + event + " ON " + PREFIX + target
                + " FOR EACH ROW EXECUTE FUNCTION fixture_ignore_admission()");
        assertThatThrownBy(() -> first.service.recheck(command("reject-admission-" + target, 1))).extracting("code")
                .isEqualTo(cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode());
        assertThat(status()).isEqualTo("PENDING_RECHECK");
        assertThat(count("task_instance")).isZero();
        assertThat(count("work_order_recheck")).isZero();
        assertThat(count("work_order_action")).isZero();
        assertThat(db.queryForObject("SELECT instance_id FROM " + PREFIX + "work_order_submission WHERE id=11", Long.class)).isNull();
        verify(first.executor, never()).dispatchGate(any());
    }

    @Test void failedOrderTransitionRollsBackDecisionAndSubmission() {
        long instance = prepareComparableAttempt();
        db.execute("ALTER TABLE " + PREFIX + "work_order ADD CONSTRAINT fixture_transition_reject CHECK(order_status <> 'PENDING_CONFIRMATION')");
        assertThatThrownBy(() -> second.service.evaluateRechecks()).isInstanceOf(RuntimeException.class);
        var receipt = first.rechecks.selectByInstance(instance);
        assertThat(receipt.getStatus()).isEqualTo("RUNNING");
        assertThat(receipt.getEvidenceJson()).isNull();
        assertThat(status()).isEqualTo("RECHECKING");
        assertThat(db.queryForObject("SELECT recheck_status FROM " + PREFIX + "work_order_submission WHERE id=11", String.class))
                .isEqualTo("RUNNING");
    }

    @ParameterizedTest @ValueSource(strings = {"work_order_recheck", "work_order_submission", "work_order"})
    void zeroRowWriteRollsBackEntirePersistentDecision(String target) {
        long instance = prepareComparableAttempt();
        db.execute("CREATE FUNCTION fixture_ignore_write() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NULL; END'");
        db.execute("CREATE TRIGGER fixture_ignore BEFORE UPDATE ON " + PREFIX + target
                + " FOR EACH ROW EXECUTE FUNCTION fixture_ignore_write()");
        assertThatThrownBy(() -> second.service.evaluateRechecks()).extracting("code").isEqualTo(
                cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_WORK_ORDER_VERSION_CONFLICT.getCode());
        var receipt = first.rechecks.selectByInstance(instance);
        assertThat(receipt.getStatus()).isEqualTo("RUNNING");
        assertThat(receipt.getEvidenceJson()).isNull();
        assertThat(status()).isEqualTo("RECHECKING");
        assertThat(db.queryForObject("SELECT recheck_status FROM " + PREFIX + "work_order_submission WHERE id=11", String.class))
                .isEqualTo("RUNNING");
    }

    private long prepareComparableAttempt() {
        var task = QualityTaskDO.builder().id(3L).build();
        var object = QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L)
                .datasourceType("THIRD_PARTY").tableName("orders").build();
        var rule = QualityRuleDO.builder().id(2L).status(0).ruleType("RANGE").checkLevel("FIELD")
                .strategyKey("NUMERIC_RANGE_VALIDATION").paramSchema("{\"column\":\"amount\",\"minValue\":10}").build();
        var definition = new QualityTaskExecutor.GateDefinition();
        definition.setTask(task); definition.setObjects(List.of(object)); definition.setRules(List.of(rule));
        String frozen = JsonUtils.toJsonString(definition);
        when(first.executor.snapshotTaskDefinition(any())).thenReturn(frozen);
        long current = first.service.recheck(command("comparable", 1)).getCurrentSubmission().getInstanceId();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        db.update("INSERT INTO " + PREFIX + "task_instance(id,task_id,instance_status,start_time,end_time,definition_snapshot) VALUES(900,3,'SUCCESS',?,?,?)", now.minusHours(3), now.minusHours(2), frozen);
        db.update("UPDATE " + PREFIX + "task_instance SET instance_status='SUCCESS',start_time=?,end_time=? WHERE id=?",
                now.minusMinutes(1), now, current);
        var baselineAudit = decisionAudit(900L, "FAIL", 10, now.minusHours(3).plusSeconds(1), object);
        var currentAudit = decisionAudit(current, "PASS", 0, now.minusSeconds(59), object);
        first.audits.insert(baselineAudit); first.audits.insert(currentAudit);
        var issue = QualityIssueDataDO.builder().id(30L).taskId(3L).instanceId(900L).auditLogId(baselineAudit.getId())
                .ruleId(2L).objectId(3L).tableName("orders").checkField("amount").statScope("ALL").issueCount(10L).build();
        for (var node : List.of(first, second)) {
            when(node.issues.selectById(30L)).thenReturn(issue);
            when(node.relations.selectListByWorkOrderId(8L)).thenReturn(List.of(QualityWorkOrderIssueDO.builder().issueId(30L).build()));
        }
        return current;
    }

    private QualityAuditLogDO decisionAudit(long instance, String result, long fail, LocalDateTime time, QualityCheckObjectDO object) {
        var audit = QualityAuditLogDO.builder().instanceId(instance).taskId(3L).ruleId(2L).checkObjectId(3L)
                .tableName("orders").checkField("amount").resultType(result).totalCount(100L).failCount(fail)
                .checkTime(time).resultSql("SELECT COUNT(*), COUNT(*) FILTER (WHERE amount < 10) FROM orders").build();
        var connection = new MasterDatabaseResolver.ConnectionInfo();
        connection.setDatabaseId(4L); connection.setProductType(ProductTypeEnum.POSTGRESQL);
        connection.setConfigurationRevision("revision"); connection.setUsageType(1); connection.setSchema(schema);
        var read = QualityReadEvidence.capture(connection, object,
                audit.getResultSql(), true, time.plusSeconds(1), time.plusSeconds(2));
        read.setSchemaVersion(QualityReadEvidence.RELATION_SCHEMA_VERSION);
        read.setRelationFingerprint("a".repeat(64)); // 此夹具验证工单事务；关系解析由组件真实 PG 专测覆盖。
        audit.setReadEvidence(JsonUtils.toJsonString(read));
        return audit;
    }

    @RepeatedTest(3) void actualOrganizationReadPassesOnlyItsOwnScopeAndKeepsLocationFilter() throws Exception {
        db.execute("CREATE TABLE org_orders(org_code integer,amount integer)");
        db.update("INSERT INTO org_orders VALUES(9,5),(9,20),(10,5)");
        var beforeStart = LocalDateTime.now();
        var original = runOrgAudit(901L, "org_code", 1);
        var beforeEnd = LocalDateTime.now();
        var inserted = org.mockito.ArgumentCaptor.forClass(QualityIssueDataDO.class);
        verify(first.issues, times(3)).insert(inserted.capture()); // ALL 和两个机构均来自本次查询。
        var otherIssue = inserted.getAllValues().stream().filter(issue -> Long.valueOf(10).equals(issue.getOrgId()))
                .findFirst().orElseThrow();
        assertThat(db.queryForList(otherIssue.getLocationSql())).hasSize(1)
                .allSatisfy(row -> assertThat(row.get("org_code")).isEqualTo(10));
        var ownIssue = inserted.getAllValues().stream().filter(issue -> Long.valueOf(9).equals(issue.getOrgId()))
                .findFirst().orElseThrow();
        ownIssue.setId(30L);
        LocalDateTime submitted = LocalDateTime.now();
        db.update("UPDATE org_orders SET amount=20 WHERE org_code=9");
        var afterStart = LocalDateTime.now();
        var current = runOrgAudit(902L, "org_code", 0); // 关闭问题明细也必须记录通过机构。
        var afterEnd = LocalDateTime.now();
        assertThat(current.getResultType()).isEqualTo("FAIL"); // 另一个机构仍未修复。
        var read = JsonUtils.parseObject(current.getReadEvidence(), QualityReadEvidence.class);
        assertThat(read.getOrgScope().getComplete()).isTrue();
        assertThat(read.getOrgScope().getGroups()).hasSize(2);
        assertThat(read.getOrgScope().getGroups().getFirst().keyHash()).matches("[0-9a-f]{64}");
        var originalRun = QualityTaskInstanceDO.builder().id(901L).taskId(3L).instanceStatus("SUCCESS")
                .startTime(beforeStart).endTime(beforeEnd).definitionSnapshot(orgDefinition()).build();
        var currentRun = QualityTaskInstanceDO.builder().id(902L).taskId(3L).instanceStatus("SUCCESS")
                .startTime(afterStart).endTime(afterEnd).definitionSnapshot(orgDefinition()).build();
        assertReadTimeBounds(original, originalRun);
        assertReadTimeBounds(current, currentRun);
        var result = QualityRecheckEvidence.compare(QualityWorkOrderDO.builder().id(8L).taskId(3L).build(),
                ownIssue, originalRun, original, currentRun, current, submitted);
        assertThat(result.comparable()).as("%s", result.reason()).isTrue();
        assertThat(result.resultType()).isEqualTo("PASS");
        assertThat(result.evidence()).containsEntry("statScope", "ORG").containsEntry("total", 2L).containsEntry("fail", 0L);
    }

    @Test void organizationReadErrorAndUnsafeFieldCannotProduceCompleteEvidence() throws Exception {
        db.execute("CREATE TABLE org_orders(org_code integer,amount integer)");
        db.update("INSERT INTO org_orders VALUES(9,5)");
        var error = runOrgAudit(901L, "missing_column", 0);
        assertThat(error.getResultType()).isEqualTo("FAIL");
        var read = JsonUtils.parseObject(error.getReadEvidence(), QualityReadEvidence.class);
        assertThat(read.getOrgScope().getComplete()).isFalse();
        assertThat(read.getOrgScope().getReasonCode()).isEqualTo("ORG_READ_FAILED");
        assertThat(read.getOrgScope().getGroups()).isEmpty();
        var unsafe = runOrgAudit(902L, "org_code; DROP TABLE org_orders", 0);
        read = JsonUtils.parseObject(unsafe.getReadEvidence(), QualityReadEvidence.class);
        assertThat(read.getOrgScope().getComplete()).isFalse();
        assertThat(read.getOrgScope().getReasonCode()).isEqualTo("ORG_FIELD_UNSUPPORTED");
        assertThat(db.queryForObject("SELECT count(*) FROM org_orders", Integer.class)).isEqualTo(1);
    }

    @Test void organizationEvidenceIsBoundedAndOverflowIsNotClaimedComplete() throws Exception {
        db.execute("CREATE TABLE org_orders(org_code integer,amount integer)");
        db.update("INSERT INTO org_orders SELECT value,20 FROM generate_series(1,1025) value");
        // 使用不带范围过滤的参数，以覆盖真正超过上限的分组。
        var audit = runOrgAudit(901L, "org_code", 0, "{\"column\":\"amount\",\"minValue\":10}");
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(audit.getResultType()).isEqualTo("PASS");
        assertThat(read.getOrgScope().getComplete()).isFalse();
        assertThat(read.getOrgScope().getReasonCode()).isEqualTo("ORG_LIMIT_EXCEEDED");
        assertThat(read.getOrgScope().getGroups()).hasSize(1024);
        db.update("DELETE FROM org_orders WHERE org_code=1025");
        audit = runOrgAudit(902L, "org_code", 0, "{\"column\":\"amount\",\"minValue\":10}");
        read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(read.getOrgScope().getComplete()).isTrue();
        assertThat(read.getOrgScope().getGroups()).hasSize(1024);
    }

    @Test void organizationToleranceIsCalculatedFromItsOwnPopulation() throws Exception {
        db.execute("CREATE TABLE org_orders(org_code integer,amount integer)");
        db.update("INSERT INTO org_orders VALUES(9,5)");
        db.update("INSERT INTO org_orders SELECT 10,20 FROM generate_series(1,9)");
        var audit = runOrgAudit(901L, "org_code", 0,
                "{\"column\":\"amount\",\"minValue\":10,\"toleranceType\":\"PASS_RATE\",\"passRateLimit\":90}");
        assertThat(audit.getResultType()).isEqualTo("PASS"); // 全表 90%。
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(read.getOrgScope().getGroups()).filteredOn(group ->
                        group.keyHash().equals(cn.hutool.crypto.digest.DigestUtil.sha256Hex("9")))
                .singleElement().satisfies(group -> {
                    assertThat(group.total()).isEqualTo(1);
                    assertThat(group.fail()).isEqualTo(1);
                    assertThat(group.resultType()).isEqualTo("FAIL"); // 本机构 0%，不能借全表通过。
                });
    }

    @Test void largeOrganizationKeyDoesNotAbortOtherGroupsOrLoseLeadingZeros() throws Exception {
        db.execute("CREATE TABLE org_orders(org_code varchar(64),amount integer)");
        db.update("INSERT INTO org_orders VALUES('9223372036854775808',5),('0009',5),('9',5)");
        var audit = runOrgAudit(901L, "org_code", 1, "{\"column\":\"amount\",\"minValue\":10}");
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(read.getOrgScope().getComplete()).isTrue();
        assertThat(read.getOrgScope().getGroups()).extracting(QualityReadEvidence.OrgCount::keyHash).doesNotHaveDuplicates();
        var inserted = org.mockito.ArgumentCaptor.forClass(QualityIssueDataDO.class);
        verify(first.issues, times(4)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).filteredOn(issue -> "ORG".equals(issue.getStatScope()))
                .extracting(QualityIssueDataDO::getRegionCode).containsExactlyInAnyOrder("9223372036854775808", "0009", "9");
    }

    private static final String ORG_PARAMS = "{\"column\":\"amount\",\"minValue\":10,\"where\":\"org_code=9 OR org_code=10\"}";

    private static final String KEY_PARAMS = "{\"column\":\"amount\",\"minValue\":10,\"pkColumn\":\"id\"}";
    private static final String TUPLE_PARAMS = "{\"column\":\"amount\",\"minValue\":10,\"pkColumns\":[\"source_id\",\"id\"]}";

    @ParameterizedTest @ValueSource(strings = {"smallint", "integer", "bigint", "text", "varchar(64)", "uuid"})
    void compositePopulationCannotBeReplacedByIndependentColumnSets(String type) throws Exception {
        db.execute("CREATE TABLE org_orders(source_id text,id " + type + ",amount integer,PRIMARY KEY(source_id,id))");
        String one = type.equals("uuid") ? "00000000-0000-0000-0000-000000000001" : "1";
        String two = type.equals("uuid") ? "00000000-0000-0000-0000-000000000002" : "2";
        db.update("INSERT INTO org_orders VALUES ('A',CAST(? AS " + type + "),1),('B',CAST(? AS " + type + "),1)", one, two);
        LocalDateTime started = LocalDateTime.now();
        var original = runOrgAudit(901L, null, 1, TUPLE_PARAMS);
        LocalDateTime submitted = LocalDateTime.now();
        var captured = org.mockito.ArgumentCaptor.forClass(QualityIssueDataDO.class);
        verify(first.issues).insert(captured.capture()); var issue = captured.getValue(); issue.setId(55L);
        assertThat(issue.getPkValue()).isNull(); // 不误用单列 CSV 协议表示元组。
        db.execute("UPDATE org_orders SET amount=20");
        var current = runOrgAudit(902L, null, 0, TUPLE_PARAMS);
        var keys = JsonUtils.parseObject(current.getReadEvidence(), QualityReadEvidence.class).getKeyScope();
        assertThat(JsonUtils.parseObject(current.getReadEvidence(), QualityReadEvidence.class).getSchemaVersion())
                .isEqualTo(QualityReadEvidence.COMPOSITE_SCHEMA_VERSION).isNotEqualTo(1);
        assertThat(keys.getComplete()).isTrue(); assertThat(keys.getColumn()).isNull();
        assertThat(keys.getColumns()).containsExactly("source_id", "id");
        assertThat(keys.getJdbcType()).isEqualTo(java.sql.Types.STRUCT);
        assertThat(keys.getAlgorithm()).isEqualTo(AuditKeySnapshot.COMPOSITE_ALGORITHM);
        var comparison = compareKeyAudits(issue, original, current, started, submitted, TUPLE_PARAMS);
        assertThat(comparison.comparable()).as(comparison.reason()).isTrue(); assertThat(comparison.resultType()).isEqualTo("PASS");
        // 两列各自的取值集合不变，只有配对关系改变。
        db.update("UPDATE org_orders SET id=CASE source_id WHEN 'A' THEN CAST(? AS " + type + ") ELSE CAST(? AS " + type + ") END", two, one);
        var replaced = runOrgAudit(903L, null, 0, TUPLE_PARAMS);
        assertThat(replaced.getTotalCount()).isEqualTo(current.getTotalCount());
        assertThat(compareKeyAudits(issue, original, replaced, started, submitted, TUPLE_PARAMS).evidence())
                .containsEntry("reasonCode", "ROW_POPULATION_CHANGED");
    }

    @Test void compositeOrganizationProofRejectsMovementWithoutChangingGlobalTupleSet() throws Exception {
        db.execute("CREATE TABLE org_orders(source_id text,id bigint,org_code integer,amount integer,PRIMARY KEY(source_id,id))");
        db.execute("INSERT INTO org_orders VALUES ('A',1,9,1),('B',1,10,1)");
        LocalDateTime started = LocalDateTime.now();
        var original = runOrgAudit(901L, "org_code", 1, TUPLE_PARAMS);
        LocalDateTime submitted = LocalDateTime.now();
        var captured = org.mockito.ArgumentCaptor.forClass(QualityIssueDataDO.class);
        verify(first.issues, times(3)).insert(captured.capture());
        var issue = captured.getAllValues().stream().filter(row -> "9".equals(row.getRegionCode())).findFirst().orElseThrow();
        issue.setId(56L);
        db.execute("UPDATE org_orders SET amount=20 WHERE org_code=9");
        var current = runOrgAudit(902L, "org_code", 0, TUPLE_PARAMS);
        assertThat(current.getResultType()).isEqualTo("FAIL");
        var comparison = compareKeyAudits(issue, original, current, started, submitted, TUPLE_PARAMS);
        assertThat(comparison.comparable()).as(comparison.reason()).isTrue(); assertThat(comparison.resultType()).isEqualTo("PASS");
        db.execute("UPDATE org_orders SET org_code=CASE org_code WHEN 9 THEN 10 ELSE 9 END,amount=20");
        var moved = runOrgAudit(903L, "org_code", 0, TUPLE_PARAMS);
        assertThat(JsonUtils.parseObject(moved.getReadEvidence(), QualityReadEvidence.class).getKeyScope().getKeyHash())
                .isEqualTo(JsonUtils.parseObject(current.getReadEvidence(), QualityReadEvidence.class).getKeyScope().getKeyHash());
        assertThat(compareKeyAudits(issue, original, moved, started, submitted, TUPLE_PARAMS).evidence())
                .containsEntry("reasonCode", "ROW_POPULATION_CHANGED");
    }

    @Test void compositeFramesAvoidConcatenationAmbiguityAndBindTypes() throws Exception {
        db.execute("CREATE TABLE org_orders(source_id text,id text,amount integer)");
        db.execute("INSERT INTO org_orders VALUES ('1','23',20)");
        var before = runOrgAudit(901L, null, 0, TUPLE_PARAMS);
        db.execute("UPDATE org_orders SET source_id='12',id='3'");
        var after = runOrgAudit(902L, null, 0, TUPLE_PARAMS);
        String hash = JsonUtils.parseObject(after.getReadEvidence(), QualityReadEvidence.class).getKeyScope().getKeyHash();
        assertThat(hash).isNotEqualTo(JsonUtils.parseObject(before.getReadEvidence(), QualityReadEvidence.class).getKeyScope().getKeyHash());
        db.execute("ALTER TABLE org_orders ALTER COLUMN id TYPE varchar(64)");
        var altered = runOrgAudit(903L, null, 0, TUPLE_PARAMS);
        assertThat(JsonUtils.parseObject(altered.getReadEvidence(), QualityReadEvidence.class).getKeyScope().getKeyHash()).isNotEqualTo(hash);
    }

    @Test void entireCompositeTupleHasOneByteBudgetAndOversizeIsNotTransferred() throws Exception {
        db.execute("CREATE TABLE org_orders(source_id text,id text,amount integer)");
        db.update("INSERT INTO org_orders VALUES (?, ?,1)", "a".repeat(118), "界".repeat(39) + "b"); // 4 + 2*8 + 236 = 256
        var audit = runOrgAudit(901L, null, 0, TUPLE_PARAMS);
        assertThat(JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class).getKeyScope().getComplete()).isTrue();
        db.execute("UPDATE org_orders SET source_id=source_id || 'x'");
        audit = runOrgAudit(902L, null, 0, TUPLE_PARAMS);
        var keys = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class).getKeyScope();
        assertThat(keys.getReasonCode()).isEqualTo("KEY_NULL_OR_TOO_LONG"); assertThat(keys.getKeyHash()).isNull();
        assertThat(audit.getTotalCount()).isEqualTo(1);
        try (var connection = source(schema).getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery(audit.getResultSql())) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getBytes(1)).isNull();
        }
    }

    @Test void compositeNullAndDuplicateTuplesInvalidateOnlyTheirOwnGroup() throws Exception {
        db.execute("CREATE TABLE org_orders(source_id text,id bigint,org_code integer,amount integer)");
        db.execute("INSERT INTO org_orders VALUES ('A',1,9,1),('A',1,9,1),(NULL,2,9,1),('B',1,10,20)");
        var audit = runOrgAudit(901L, "org_code", 0, TUPLE_PARAMS);
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(read.getKeyScope().getComplete()).isFalse();
        assertThat(read.getOrgScope().getGroups()).allSatisfy(group ->
                assertThat(group.keyScope().getComplete()).isEqualTo(group.total() == 1));
    }

    @Test void fourPartCompositeKeysSupportMixedValuesAndUncommittedCallerData() throws Exception {
        db.execute("CREATE TABLE org_orders(source_id text,id bigint,version smallint,token uuid,amount integer)");
        var context = new cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.POSTGRESQL); context.setTableName("org_orders");
        context.setColumn("amount"); context.setConfig(Map.of("minValue", 10));
        try (var connection = source(schema).getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO org_orders VALUES ('0009',-9223372036854775808,1,'ffffffff-ffff-ffff-ffff-ffffffffffff',1)");
            }
            var result = AuditKeySnapshot.readComposite(connection, new AuditSqlRegistry().getGenerator("NUMERIC_RANGE_VALIDATION"),
                    context, List.of("source_id", "id", "version", "token"), 10, 5);
            assertThat(result.complete()).isTrue(); assertThat(result.fail()).isEqualTo(1);
            assertThat(connection.getAutoCommit()).isFalse();
            assertThat(db.queryForObject("SELECT count(*) FROM org_orders", Integer.class)).isZero();
            connection.rollback();
        }
        assertThat(db.queryForObject("SELECT count(*) FROM org_orders", Integer.class)).isZero();
    }

    @Test void compositeGlobalRowBudgetNeverReturnsPrefixProof() throws Exception {
        db.execute("CREATE TABLE org_orders(source_id integer,id bigint,org_code integer,amount integer)");
        db.execute("INSERT INTO org_orders SELECT n%2,n,n%2,20 FROM generate_series(1,100000) n");
        var audit = runOrgAudit(901L, "org_code", 0, TUPLE_PARAMS);
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(read.getKeyScope().getComplete()).isTrue();
        assertThat(read.getOrgScope().getGroups()).allSatisfy(group -> assertThat(group.keyScope().getComplete()).isTrue());
        db.execute("INSERT INTO org_orders VALUES (1,100001,1,1)");
        audit = runOrgAudit(902L, "org_code", 0, TUPLE_PARAMS);
        read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(audit.getTotalCount()).isEqualTo(100001); assertThat(audit.getFailCount()).isEqualTo(1);
        assertThat(read.getKeyScope().getReasonCode()).isEqualTo("KEY_LIMIT_EXCEEDED");
        assertThat(read.getOrgScope().getKeyCaptureReason()).isEqualTo("KEY_LIMIT_EXCEEDED");
        assertThat(read.getOrgScope().getGroups()).allSatisfy(group -> assertThat(group.keyScope()).isNull());
    }

    @Test void unsupportedCompositeComponentKeepsCountsButNotProof() throws Exception {
        db.execute("CREATE TABLE org_orders(source_id text,id numeric,org_code integer,amount integer)");
        db.execute("INSERT INTO org_orders VALUES ('A',1.25,9,1)");
        var audit = runOrgAudit(901L, "org_code", 0, TUPLE_PARAMS);
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        for (var keys : List.of(read.getKeyScope(), read.getOrgScope().getGroups().getFirst().keyScope())) {
            assertThat(keys.getComplete()).isFalse(); assertThat(keys.getReasonCode()).isEqualTo("KEY_TYPE_UNSUPPORTED");
            assertThat(keys.getKeyHash()).isNull();
        }
        assertThat(audit.getFailCount()).isEqualTo(1);
    }

    @Test void invalidLegacyCompositeConfigurationCannotExecuteAnIdentifierOrPassRecheck() throws Exception {
        db.execute("CREATE TABLE org_orders(source_id text,id bigint,amount integer)");
        db.execute("INSERT INTO org_orders VALUES ('A',1,1)");
        String parameters = "{\"column\":\"amount\",\"minValue\":10,\"pkColumns\":[\"source_id\",\"id); DROP TABLE org_orders; --\"]}";
        LocalDateTime started = LocalDateTime.now();
        var original = runOrgAudit(901L, null, 1, parameters);
        LocalDateTime submitted = LocalDateTime.now();
        var captured = org.mockito.ArgumentCaptor.forClass(QualityIssueDataDO.class);
        verify(first.issues).insert(captured.capture()); var issue = captured.getValue(); issue.setId(57L);
        db.execute("UPDATE org_orders SET amount=20");
        var current = runOrgAudit(902L, null, 0, parameters);
        assertThat(current.getResultType()).isEqualTo("PASS"); // 规则统计通过，不等于复检凭证通过。
        assertThat(JsonUtils.parseObject(current.getReadEvidence(), QualityReadEvidence.class).getKeyScope()).isNull();
        assertThat(compareKeyAudits(issue, original, current, started, submitted, parameters).evidence())
                .containsEntry("reasonCode", "ROW_COVERAGE_UNPROVEN");
        assertThat(db.queryForObject("SELECT count(*) FROM org_orders", Integer.class)).isEqualTo(1);
    }

    @Test void actualKeyPopulationMustMatchEvenWhenRowCountsMatch() throws Exception {
        db.execute("CREATE TABLE org_orders(id bigint PRIMARY KEY, amount integer)");
        db.execute("INSERT INTO org_orders VALUES (-9223372036854775808,1),(9007199254740993,20),(9223372036854775807,1)");
        LocalDateTime started = LocalDateTime.now();
        var original = runOrgAudit(901L, null, 1, KEY_PARAMS);
        LocalDateTime submitted = LocalDateTime.now();
        var inserted = org.mockito.ArgumentCaptor.forClass(QualityIssueDataDO.class);
        verify(first.issues).insert(inserted.capture());
        var issue = inserted.getValue(); issue.setId(50L);
        assertThat(original.getFailCount()).isEqualTo(2);
        db.execute("UPDATE org_orders SET amount=20");
        var current = runOrgAudit(902L, null, 0, KEY_PARAMS);
        var read = JsonUtils.parseObject(current.getReadEvidence(), QualityReadEvidence.class);
        assertThat(read.getKeyScope().getComplete()).isTrue();
        assertThat(read.getKeyScope().getKeyCount()).isEqualTo(3);
        assertThat(read.getKeyScope().getJdbcType()).isEqualTo(java.sql.Types.BIGINT);
        assertThat(read.getKeyScope().getSqlHash()).isEqualTo(read.getSqlHash());
        assertThat(current.getResultSql()).contains("GROUP BY id", "pg_catalog.pg_typeof");
        assertThat(compareKeyAudits(issue, original, current, started, submitted).comparable()).isTrue();
        db.execute("DELETE FROM org_orders WHERE id=-9223372036854775808");
        db.execute("INSERT INTO org_orders VALUES (42,20)");
        var replaced = runOrgAudit(903L, null, 0, KEY_PARAMS);
        assertThat(replaced.getTotalCount()).isEqualTo(original.getTotalCount());
        assertThat(replaced.getResultType()).isEqualTo("PASS");
        assertThat(compareKeyAudits(issue, original, replaced, started, submitted).evidence())
                .containsEntry("reasonCode", "ROW_POPULATION_CHANGED");
    }

    @Test void nullAndDuplicateConfiguredKeysCannotCreateCoverageProof() throws Exception {
        db.execute("CREATE TABLE org_orders(id integer, amount integer)");
        db.execute("INSERT INTO org_orders VALUES (1,1),(1,20),(NULL,1)");
        var audit = runOrgAudit(901L, null, 0, KEY_PARAMS);
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(audit.getTotalCount()).isEqualTo(3); assertThat(audit.getFailCount()).isEqualTo(2);
        assertThat(read.getKeyScope().getComplete()).isFalse();
        assertThat(read.getKeyScope().getKeyHash()).isNull();
        assertThat(read.getKeyScope().getReasonCode()).isEqualTo("KEY_NOT_UNIQUE_OR_NULL");
    }

    @Test void unsupportedLargeTextKeysAreNotTransferredOrTreatedAsNumbers() throws Exception {
        db.execute("CREATE TABLE org_orders(id text, amount integer)");
        db.execute("INSERT INTO org_orders VALUES ('0009',1),('9',20),(repeat('secret-key-',100000),1)");
        var audit = runOrgAudit(901L, null, 0, KEY_PARAMS);
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(audit.getTotalCount()).isEqualTo(3); assertThat(audit.getFailCount()).isEqualTo(2);
        assertThat(read.getKeyScope().getComplete()).isFalse();
        assertThat(read.getKeyScope().getReasonCode()).isEqualTo("KEY_NULL_OR_TOO_LONG");
        assertThat(audit.getReadEvidence()).doesNotContain("secret-key-", "\"0009\"");
        try (var jdbc = source(schema).getConnection(); var query = jdbc.createStatement();
             var rows = query.executeQuery(audit.getResultSql())) {
            int bounded = 0, rejected = 0;
            while (rows.next()) {
                byte[] key = rows.getBytes(1);
                if (key == null) rejected++;
                else { assertThat(key.length).isLessThanOrEqualTo(AuditKeySnapshot.MAX_KEY_BYTES); bounded++; }
            }
            assertThat(bounded).isEqualTo(2); assertThat(rejected).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"text", "varchar(256)"})
    void textKeysPreserveZerosCaseUnicodeAndPopulation(String type) throws Exception {
        db.execute("CREATE TABLE org_orders(id " + type + " PRIMARY KEY, amount integer)");
        for (String key : List.of("", "0009", "9", "A", "a", "é", "e\u0301", "键🔑", "x,y", "x|y")) {
            db.update("INSERT INTO org_orders VALUES (?,1)", key);
        }
        LocalDateTime started = LocalDateTime.now();
        var original = runOrgAudit(901L, null, 1, KEY_PARAMS);
        LocalDateTime submitted = LocalDateTime.now();
        var captured = org.mockito.ArgumentCaptor.forClass(QualityIssueDataDO.class);
        verify(first.issues).insert(captured.capture()); var issue = captured.getValue(); issue.setId(50L);
        db.execute("UPDATE org_orders SET amount=20");
        var current = runOrgAudit(902L, null, 0, KEY_PARAMS);
        var keyScope = JsonUtils.parseObject(current.getReadEvidence(), QualityReadEvidence.class).getKeyScope();
        assertThat(keyScope.getComplete()).isTrue(); assertThat(keyScope.getKeyCount()).isEqualTo(10);
        assertThat(keyScope.getAlgorithm()).isEqualTo(AuditKeySnapshot.TYPED_ALGORITHM);
        assertThat(keyScope.getJdbcType()).isEqualTo(type.equals("text") ? java.sql.Types.LONGVARCHAR : java.sql.Types.VARCHAR);
        var comparison = compareKeyAudits(issue, original, current, started, submitted);
        assertThat(comparison.comparable()).as(comparison.reason()).isTrue();
        assertThat(comparison.resultType()).isEqualTo("PASS");
        // 每次只替换一个键且总数相同；数字化/大小写折叠/Unicode 归一化均会错误放行。
        int instance = 903;
        for (String[] replacement : List.of(new String[]{"0009", "09"}, new String[]{"A", "aA"},
                new String[]{"é", "e\u0301\u0301"}, new String[]{"x,y", "x|z"})) {
            db.update("UPDATE org_orders SET id=? WHERE id=?", replacement[1], replacement[0]);
            var replaced = runOrgAudit(instance++, null, 0, KEY_PARAMS);
            assertThat(replaced.getTotalCount()).isEqualTo(original.getTotalCount());
            assertThat(compareKeyAudits(issue, original, replaced, started, submitted).evidence())
                    .containsEntry("reasonCode", "ROW_POPULATION_CHANGED");
            db.update("UPDATE org_orders SET id=? WHERE id=?", replacement[0], replacement[1]);
        }
    }

    @Test void typedByteLimitIsUtf8NotCharactersAndNeverTruncates() throws Exception {
        db.execute("CREATE TABLE org_orders(id text, amount integer)");
        db.update("INSERT INTO org_orders VALUES (?,1),(?,20)", "界".repeat(85) + "a", "x".repeat(256));
        var audit = runOrgAudit(901L, null, 0, KEY_PARAMS);
        assertThat(JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class).getKeyScope().getComplete()).isTrue();
        db.update("INSERT INTO org_orders VALUES (?,1)", "界".repeat(86));
        audit = runOrgAudit(902L, null, 0, KEY_PARAMS);
        var scope = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class).getKeyScope();
        assertThat(audit.getTotalCount()).isEqualTo(3); assertThat(audit.getFailCount()).isEqualTo(2);
        assertThat(scope.getComplete()).isFalse(); assertThat(scope.getKeyHash()).isNull();
        assertThat(scope.getReasonCode()).isEqualTo("KEY_NULL_OR_TOO_LONG");
    }

    @Test void typedNullAndDuplicateKeysDoNotAffectOtherOrganizationsProof() throws Exception {
        db.execute("CREATE TABLE org_orders(id text, org_code integer, amount integer)");
        db.execute("INSERT INTO org_orders VALUES ('a',9,1),('a',9,20),(NULL,9,1),('a',10,20)");
        var audit = runOrgAudit(901L, "org_code", 0, KEY_PARAMS);
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(read.getKeyScope().getComplete()).isFalse();
        assertThat(read.getOrgScope().getComplete()).isTrue();
        var bad = read.getOrgScope().getGroups().stream().filter(group -> group.total() == 3).findFirst().orElseThrow();
        var good = read.getOrgScope().getGroups().stream().filter(group -> group.total() == 1).findFirst().orElseThrow();
        assertThat(bad.keyScope().getComplete()).isFalse(); assertThat(bad.keyScope().getKeyHash()).isNull();
        assertThat(good.keyScope().getComplete()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {"numeric", "char(5)", "bytea"})
    void otherPostgresTypesRemainUnsupportedInsteadOfBeingStringified(String type) throws Exception {
        db.execute("CREATE TABLE org_orders(id " + type + ",org_code integer,amount integer)");
        db.execute("INSERT INTO org_orders VALUES ('42',9,1)");
        var audit = runOrgAudit(901L, "org_code", 0, KEY_PARAMS);
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        for (var key : List.of(read.getKeyScope(), read.getOrgScope().getGroups().getFirst().keyScope())) {
            assertThat(key.getComplete()).isFalse(); assertThat(key.getReasonCode()).isEqualTo("KEY_TYPE_UNSUPPORTED");
            assertThat(key.getKeyHash()).isNull();
        }
        assertThat(audit.getTotalCount()).isEqualTo(1); assertThat(audit.getFailCount()).isEqualTo(1);
    }

    @Test void typedGlobalRowBudgetStillAppliesAcrossAllOrganizations() throws Exception {
        db.execute("CREATE TABLE org_orders(id text PRIMARY KEY,org_code integer,amount integer)");
        db.execute("INSERT INTO org_orders SELECT 'key-'||n,n%2,20 FROM generate_series(1,100000) n");
        var audit = runOrgAudit(901L, "org_code", 0, KEY_PARAMS);
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(read.getKeyScope().getComplete()).isTrue(); assertThat(read.getKeyScope().getKeyCount()).isEqualTo(100000);
        assertThat(read.getOrgScope().getGroups()).allSatisfy(group -> {
            assertThat(group.keyScope().getComplete()).isTrue(); assertThat(group.total()).isEqualTo(50000);
        });
        db.execute("INSERT INTO org_orders VALUES ('overflow',1,1)");
        audit = runOrgAudit(902L, "org_code", 0, KEY_PARAMS);
        read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(audit.getTotalCount()).isEqualTo(100001); assertThat(audit.getFailCount()).isEqualTo(1);
        assertThat(read.getKeyScope().getReasonCode()).isEqualTo("KEY_LIMIT_EXCEEDED");
        assertThat(read.getOrgScope().getKeyCaptureReason()).isEqualTo("KEY_LIMIT_EXCEEDED");
        assertThat(read.getOrgScope().getGroups()).allSatisfy(group -> assertThat(group.keyScope()).isNull());
    }

    @ParameterizedTest @ValueSource(strings = {"text", "varchar(256)", "uuid"})
    void typedOrganizationKeysRejectTransfersWithUnchangedWholePopulation(String type) throws Exception {
        db.execute("CREATE TABLE org_orders(id " + type + " PRIMARY KEY, org_code integer, amount integer)");
        db.execute("INSERT INTO org_orders VALUES ('00000000-0000-0000-0000-000000000001',9,1),"
                + "('00000000-0000-0000-0000-000000000002',10,1)");
        LocalDateTime started = LocalDateTime.now();
        var original = runOrgAudit(901L, "org_code", 1, KEY_PARAMS);
        LocalDateTime submitted = LocalDateTime.now();
        var captured = org.mockito.ArgumentCaptor.forClass(QualityIssueDataDO.class);
        verify(first.issues, times(3)).insert(captured.capture());
        var issue = captured.getAllValues().stream().filter(row -> "9".equals(row.getRegionCode())).findFirst().orElseThrow();
        issue.setId(51L);
        db.execute("UPDATE org_orders SET amount=20 WHERE org_code=9");
        var current = runOrgAudit(902L, "org_code", 0, KEY_PARAMS);
        var comparison = compareKeyAudits(issue, original, current, started, submitted);
        assertThat(comparison.comparable()).as(comparison.reason()).isTrue(); assertThat(comparison.resultType()).isEqualTo("PASS");
        assertThat(current.getResultType()).isEqualTo("FAIL");
        db.execute("UPDATE org_orders SET org_code=CASE org_code WHEN 9 THEN 10 ELSE 9 END,amount=20");
        var moved = runOrgAudit(903L, "org_code", 0, KEY_PARAMS);
        assertThat(JsonUtils.parseObject(moved.getReadEvidence(), QualityReadEvidence.class).getKeyScope().getKeyHash())
                .isEqualTo(JsonUtils.parseObject(current.getReadEvidence(), QualityReadEvidence.class).getKeyScope().getKeyHash());
        assertThat(compareKeyAudits(issue, original, moved, started, submitted).evidence())
                .containsEntry("reasonCode", "ROW_POPULATION_CHANGED");
    }

    @Test void uuidIdentityUsesCanonicalBytesAndChangedUuidCannotPass() throws Exception {
        db.execute("CREATE TABLE org_orders(id uuid PRIMARY KEY, amount integer)");
        db.execute("INSERT INTO org_orders VALUES ('ABCDEF00-0000-0000-0000-000000000001',1)");
        LocalDateTime started = LocalDateTime.now();
        var original = runOrgAudit(901L, null, 1, KEY_PARAMS);
        LocalDateTime submitted = LocalDateTime.now();
        var captured = org.mockito.ArgumentCaptor.forClass(QualityIssueDataDO.class);
        verify(first.issues).insert(captured.capture()); var issue = captured.getValue(); issue.setId(52L);
        db.execute("UPDATE org_orders SET id='abcdef00000000000000000000000001',amount=20");
        var current = runOrgAudit(902L, null, 0, KEY_PARAMS);
        assertThat(compareKeyAudits(issue, original, current, started, submitted).comparable()).isTrue();
        db.execute("UPDATE org_orders SET id='abcdef00-0000-0000-0000-000000000002'");
        var replaced = runOrgAudit(903L, null, 0, KEY_PARAMS);
        assertThat(compareKeyAudits(issue, original, replaced, started, submitted).evidence())
                .containsEntry("reasonCode", "ROW_POPULATION_CHANGED");
    }

    @Test void keyLimitBoundaryKeepsOrdinaryFullCountsButNeverPartialCoverage() throws Exception {
        db.execute("CREATE TABLE org_orders(id integer PRIMARY KEY, amount integer)");
        db.execute("INSERT INTO org_orders SELECT n,20 FROM generate_series(1,100000) n");
        var audit = runOrgAudit(901L, null, 0, KEY_PARAMS);
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(read.getKeyScope().getComplete()).isTrue();
        assertThat(audit.getTotalCount()).isEqualTo(100000);
        db.execute("INSERT INTO org_orders VALUES(100001,1)");
        audit = runOrgAudit(902L, null, 0, KEY_PARAMS);
        read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(audit.getTotalCount()).isEqualTo(100001); assertThat(audit.getFailCount()).isEqualTo(1);
        assertThat(read.getKeyScope().getComplete()).isFalse();
        assertThat(read.getKeyScope().getReasonCode()).isEqualTo("KEY_LIMIT_EXCEEDED");
        assertThat(read.getKeyScope().getKeyHash()).isNull();
        assertThat(audit.getResultSql()).doesNotContain("GROUP BY id");
    }

    @Test void keySnapshotDoesNotCommitOrRollbackCallerTransaction() throws Exception {
        db.execute("CREATE TABLE org_orders(id smallint PRIMARY KEY, amount integer)");
        var context = new cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.POSTGRESQL); context.setTableName("org_orders");
        context.setColumn("amount"); context.setConfig(Map.of("minValue", 10));
        try (var jdbc = source(schema).getConnection()) {
            jdbc.setAutoCommit(false);
            try (var write = jdbc.createStatement()) { write.executeUpdate("INSERT INTO org_orders VALUES(1,1)"); }
            var snapshot = cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.read(jdbc,
                    new AuditSqlRegistry().getGenerator("NUMERIC_RANGE_VALIDATION"), context, "id", 10, 5);
            assertThat(snapshot.complete()).isTrue(); assertThat(snapshot.jdbcType()).isEqualTo(java.sql.Types.SMALLINT);
            assertThat(snapshot.total()).isEqualTo(1); assertThat(jdbc.getAutoCommit()).isFalse();
            assertThat(db.queryForObject("SELECT count(*) FROM org_orders", Integer.class)).isZero();
            jdbc.rollback();
        }
        assertThat(db.queryForObject("SELECT count(*) FROM org_orders", Integer.class)).isZero();
    }

    private QualityRecheckEvidence.Comparison compareKeyAudits(QualityIssueDataDO issue, QualityAuditLogDO original,
            QualityAuditLogDO current, LocalDateTime started, LocalDateTime submitted) {
        return compareKeyAudits(issue, original, current, started, submitted, KEY_PARAMS);
    }

    private QualityRecheckEvidence.Comparison compareKeyAudits(QualityIssueDataDO issue, QualityAuditLogDO original,
            QualityAuditLogDO current, LocalDateTime started, LocalDateTime submitted, String parameters) {
        var definition = new QualityTaskExecutor.GateDefinition();
        definition.setTask(QualityTaskDO.builder().id(3L)
                .orgField("ORG".equals(issue.getStatScope()) ? "org_code" : null).build());
        definition.setRules(List.of(orgRule().paramSchema(parameters).build()));
        definition.setObjects(List.of(orgObject()));
        String frozen = JsonUtils.toJsonString(definition);
        var baseline = QualityTaskInstanceDO.builder().id(original.getInstanceId()).taskId(3L).instanceStatus("SUCCESS")
                .startTime(started).endTime(submitted).definitionSnapshot(frozen).build();
        var recheck = QualityTaskInstanceDO.builder().id(current.getInstanceId()).taskId(3L).instanceStatus("SUCCESS")
                .startTime(submitted).endTime(LocalDateTime.now()).definitionSnapshot(frozen).build();
        return QualityRecheckEvidence.compare(QualityWorkOrderDO.builder().id(8L).taskId(3L).build(),
                issue, baseline, original, recheck, current, submitted);
    }

    @RepeatedTest(3) void actualScopedKeyReadRejectsTransfersEvenWhenWholeTableKeysAndGroupCountsStaySame() throws Exception {
        db.execute("CREATE TABLE org_orders(id bigint PRIMARY KEY, org_code text, amount integer)");
        db.execute("INSERT INTO org_orders VALUES (1,'0009',1),(2,'0009',1),(3,'9',1),(4,'9',1)");
        LocalDateTime started = LocalDateTime.now();
        var original = runOrgAudit(901L, "org_code", 1, KEY_PARAMS);
        LocalDateTime submitted = LocalDateTime.now();
        var inserted = org.mockito.ArgumentCaptor.forClass(QualityIssueDataDO.class);
        verify(first.issues, times(3)).insert(inserted.capture());
        var issue = inserted.getAllValues().stream().filter(row -> "0009".equals(row.getRegionCode())).findFirst().orElseThrow();
        issue.setId(51L);
        db.execute("UPDATE org_orders SET amount=20 WHERE org_code='0009'");
        var current = runOrgAudit(902L, "org_code", 0, KEY_PARAMS);
        assertThat(current.getResultType()).isEqualTo("FAIL"); // 另一机构仍失败。
        var comparison = compareKeyAudits(issue, original, current, started, submitted);
        assertThat(comparison.comparable()).as(comparison.reason()).isTrue();
        assertThat(comparison.resultType()).isEqualTo("PASS");
        var read = JsonUtils.parseObject(current.getReadEvidence(), QualityReadEvidence.class);
        var scope = read.getOrgScope();
        assertThat(scope.getComplete()).isTrue(); assertThat(scope.getGroups()).hasSize(2);
        for (var group : scope.getGroups()) {
            assertThat(group.keyScope().getComplete()).isTrue();
            assertThat(group.keyScope().getGroupKeyHash()).isEqualTo(group.keyHash());
            assertThat(group.keyScope().getSqlHash()).isEqualTo(scope.getSqlHash());
            assertThat(group.keyScope().getTotal()).isEqualTo(group.total());
            assertThat(group.keyScope().getFail()).isEqualTo(group.fail());
        }
        // 校验机构原值的 JSON 字符串；随机 schema UUID / 哈希偶含 0009 并不是泄露。
        assertThat(current.getReadEvidence()).doesNotContain("\"0009\"");
        db.execute("UPDATE org_orders SET org_code=CASE id WHEN 2 THEN '9' WHEN 3 THEN '0009' ELSE org_code END, amount=20");
        var moved = runOrgAudit(903L, "org_code", 0, KEY_PARAMS);
        var movedRead = JsonUtils.parseObject(moved.getReadEvidence(), QualityReadEvidence.class);
        assertThat(movedRead.getKeyScope().getKeyHash()).isEqualTo(read.getKeyScope().getKeyHash());
        assertThat(movedRead.getOrgScope().getGroups()).extracting(QualityReadEvidence.OrgCount::total).containsOnly(2L);
        assertThat(compareKeyAudits(issue, original, moved, started, submitted).evidence())
                .containsEntry("reasonCode", "ROW_POPULATION_CHANGED");
    }

    @Test void groupedKeyLimitFallsBackToFullOrgCountsWithoutPartialKeyEvidence() throws Exception {
        db.execute("CREATE TABLE org_orders(id integer PRIMARY KEY, org_code integer, amount integer)");
        db.execute("INSERT INTO org_orders SELECT n,n%2,20 FROM generate_series(1,100000) n");
        var audit = runOrgAudit(901L, "org_code", 0, KEY_PARAMS);
        var scope = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class).getOrgScope();
        assertThat(scope.getGroups()).hasSize(2);
        assertThat(scope.getGroups()).allSatisfy(group -> {
            assertThat(group.total()).isEqualTo(50000); assertThat(group.keyScope().getComplete()).isTrue();
        });
        db.execute("INSERT INTO org_orders VALUES(100001,1,1)");
        audit = runOrgAudit(902L, "org_code", 0, KEY_PARAMS);
        scope = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class).getOrgScope();
        assertThat(scope.getComplete()).isTrue(); // 普通统计完整，但主键扫描超限。
        assertThat(scope.getKeyCaptureReason()).isEqualTo("KEY_LIMIT_EXCEEDED");
        assertThat(scope.getGroups()).allSatisfy(group -> assertThat(group.keyScope()).isNull());
        assertThat(scope.getGroups().stream().mapToLong(QualityReadEvidence.OrgCount::total).sum()).isEqualTo(100001);
        assertThat(scope.getGroups().stream().mapToLong(QualityReadEvidence.OrgCount::fail).sum()).isEqualTo(1);
    }

    @Test void groupedEvidenceRespectsGroupLimitAndRejectsNullIdentity() throws Exception {
        db.execute("CREATE TABLE org_orders(id integer PRIMARY KEY, org_code integer, amount integer)");
        db.execute("INSERT INTO org_orders SELECT n,n,20 FROM generate_series(1,1024) n");
        var audit = runOrgAudit(901L, "org_code", 0, KEY_PARAMS);
        var scope = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class).getOrgScope();
        assertThat(scope.getGroups()).hasSize(1024);
        assertThat(scope.getGroups()).allSatisfy(group -> assertThat(group.keyScope().getComplete()).isTrue());
        db.execute("INSERT INTO org_orders VALUES(1025,1025,1)");
        audit = runOrgAudit(902L, "org_code", 0, KEY_PARAMS);
        scope = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class).getOrgScope();
        assertThat(scope.getComplete()).isFalse(); assertThat(scope.getKeyCaptureReason()).isEqualTo("GROUP_LIMIT_EXCEEDED");
        assertThat(scope.getGroups()).allSatisfy(group -> assertThat(group.keyScope()).isNull());
        db.execute("UPDATE org_orders SET org_code=NULL WHERE id=1025");
        audit = runOrgAudit(903L, "org_code", 0, KEY_PARAMS);
        scope = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class).getOrgScope();
        assertThat(scope.getComplete()).isFalse(); assertThat(scope.getKeyCaptureReason()).isEqualTo("GROUP_KEY_UNSUPPORTED");
    }

    @Test void duplicateAndNullKeysInvalidateTheirGroupWithoutBorrowingAnotherGroup() throws Exception {
        db.execute("CREATE TABLE org_orders(id bigint, org_code text, amount integer)");
        db.execute("INSERT INTO org_orders VALUES (1,'0009',1),(1,'0009',1),(NULL,'0009',1),(3,'9',20)");
        var audit = runOrgAudit(901L, "org_code", 0, KEY_PARAMS);
        var scope = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class).getOrgScope();
        assertThat(scope.getComplete()).isTrue();
        var invalid = scope.getGroups().stream().filter(group -> group.total() == 3).findFirst().orElseThrow();
        var valid = scope.getGroups().stream().filter(group -> group.total() == 1).findFirst().orElseThrow();
        assertThat(invalid.keyScope().getComplete()).isFalse();
        assertThat(invalid.keyScope().getReasonCode()).isEqualTo("KEY_NOT_UNIQUE_OR_NULL");
        assertThat(valid.keyScope().getComplete()).isTrue();
    }

    @Test void groupedSnapshotDoesNotCommitCallerTransactionOrTransferLongGroupValues() throws Exception {
        db.execute("CREATE TABLE org_orders(id smallint PRIMARY KEY, org_code text, amount integer)");
        var context = new cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.POSTGRESQL); context.setTableName("org_orders");
        context.setColumn("amount"); context.setConfig(Map.of("minValue", 10));
        try (var jdbc = source(schema).getConnection()) {
            jdbc.setAutoCommit(false);
            try (var write = jdbc.createStatement()) { write.executeUpdate("INSERT INTO org_orders VALUES(1,repeat('x',100000),1)"); }
            var snapshot = cn.iocoder.yudao.framework.quality.core.audit.AuditKeySnapshot.readGrouped(jdbc,
                    new AuditSqlRegistry().getGenerator("NUMERIC_RANGE_VALIDATION"), context, "id", "org_code", 10, 2, 5);
            assertThat(snapshot.complete()).isFalse(); assertThat(snapshot.reasonCode()).isEqualTo("GROUP_KEY_UNSUPPORTED");
            try (var query = jdbc.createStatement(); var rows = query.executeQuery(snapshot.sql())) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getObject(5)).isNull();
            }
            assertThat(jdbc.getAutoCommit()).isFalse();
            assertThat(db.queryForObject("SELECT count(*) FROM org_orders", Integer.class)).isZero();
            jdbc.rollback();
        }
        assertThat(db.queryForObject("SELECT count(*) FROM org_orders", Integer.class)).isZero();
    }

    @Test void groupedReadFailureKeepsWholeTableAuditButNeverHalfOrgProof() throws Exception {
        db.execute("CREATE TABLE org_orders(id integer PRIMARY KEY, amount integer)");
        db.execute("INSERT INTO org_orders VALUES(1,1),(2,20)");
        var audit = runOrgAudit(901L, "missing_org_column", 0, KEY_PARAMS);
        assertThat(audit.getResultType()).isEqualTo("FAIL"); assertThat(audit.getTotalCount()).isEqualTo(2);
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(read.getKeyScope().getComplete()).isTrue();
        assertThat(read.getOrgScope().getComplete()).isFalse();
        assertThat(read.getOrgScope().getKeyCaptureReason()).isEqualTo("KEY_READ_FAILED");
        assertThat(read.getOrgScope().getReasonCode()).isEqualTo("ORG_READ_FAILED");
        assertThat(read.getOrgScope().getGroups()).isEmpty();
    }

    @Test void groupedUuidKeysHaveTypedProofWithoutPretendingToBeIntegerKeys() throws Exception {
        db.execute("CREATE TABLE org_orders(id uuid PRIMARY KEY, org_code integer, amount integer)");
        db.execute("INSERT INTO org_orders VALUES ('00000000-0000-0000-0000-000000000001',9,1),"
                + "('00000000-0000-0000-0000-000000000002',9,20)");
        var audit = runOrgAudit(901L, "org_code", 0, KEY_PARAMS);
        assertThat(audit.getTotalCount()).isEqualTo(2); assertThat(audit.getFailCount()).isEqualTo(1);
        var scope = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class).getOrgScope();
        assertThat(scope.getComplete()).isTrue(); assertThat(scope.getGroups()).hasSize(1);
        var group = scope.getGroups().getFirst();
        assertThat(group.total()).isEqualTo(2); assertThat(group.fail()).isEqualTo(1);
        assertThat(group.keyScope().getComplete()).isTrue();
        assertThat(group.keyScope().getAlgorithm()).isEqualTo(AuditKeySnapshot.TYPED_ALGORITHM);
        assertThat(group.keyScope().getJdbcType()).isEqualTo(java.sql.Types.OTHER);
        assertThat(group.keyScope().getReasonCode()).isNull();
        assertThat(group.keyScope().getKeyHash()).matches("[0-9a-f]{64}");
    }

    private void assertReadTimeBounds(QualityAuditLogDO audit, QualityTaskInstanceDO run) {
        var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
        assertThat(audit.getCheckTime()).as("audit check time within instance").isAfterOrEqualTo(run.getStartTime());
        assertThat(read.startedTime().plusNanos(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS))
                .as("actual read after audit check at PostgreSQL timestamp(6) precision").isAfterOrEqualTo(audit.getCheckTime());
        assertThat(read.finishedTime()).as("actual read within instance end").isBeforeOrEqualTo(run.getEndTime());
    }

    private QualityAuditLogDO runOrgAudit(long instance, String field, int collect) throws Exception {
        return runOrgAudit(instance, field, collect, ORG_PARAMS);
    }

    private QualityAuditLogDO runOrgAudit(long instance, String field, int collect, String params) throws Exception {
        var executor = QualityRuntimeFixture.executor();
        ReflectionTestUtils.setField(executor, "auditLogMapper", first.audits);
        ReflectionTestUtils.setField(executor, "issueDataMapper", first.issues);
        ReflectionTestUtils.setField(executor, "auditSqlRegistry", new AuditSqlRegistry());
        var conn = new MasterDatabaseResolver.ConnectionInfo();
        conn.setDatabaseId(4L); conn.setSchema(schema); conn.setUsageType(1); conn.setConfigurationRevision("revision");
        conn.setProductType(ProductTypeEnum.POSTGRESQL);
        QualityAuditLogDO audit;
        try (var jdbc = source(schema).getConnection()) {
            audit = QualityRuntimeFixture.executeRule(executor, instance,
                    QualityTaskDO.builder().id(3L).orgField(field).build(), jdbc, conn, orgObject(),
                    orgRule().collectIssueDetail(collect).paramSchema(params).build());
        }
        assertThat(audit).isNotNull();
        return first.audits.selectById(audit.getId());
    }

    private QualityCheckObjectDO orgObject() {
        return QualityCheckObjectDO.builder().id(3L).datasourceConnId(4L).datasourceType("THIRD_PARTY")
                .tableName("org_orders").build();
    }

    private QualityRuleDO.QualityRuleDOBuilder orgRule() {
        return QualityRuleDO.builder().id(2L).status(0).ruleName("金额下限").ruleType("RANGE").checkLevel("FIELD")
                .strategyKey("NUMERIC_RANGE_VALIDATION").paramSchema(ORG_PARAMS);
    }

    private String orgDefinition() {
        var definition = new QualityTaskExecutor.GateDefinition();
        definition.setTask(QualityTaskDO.builder().id(3L).orgField("org_code").build());
        definition.setRules(List.of(orgRule().build())); definition.setObjects(List.of(orgObject()));
        return JsonUtils.toJsonString(definition);
    }

    private Callable<Long> concurrent(Node node, String request, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            TenantContextHolder.clear();
            try {
                ready.countDown();
                if (!start.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                return node.service.recheck(command(request, 1)).getCurrentSubmission().getInstanceId();
            } finally { TenantContextHolder.clear(); }
        };
    }

    private int count(String suffix) { return db.queryForObject("SELECT count(*) FROM " + PREFIX + suffix, Integer.class); }
    private String status() { return db.queryForObject("SELECT order_status FROM " + PREFIX + "work_order WHERE id=8", String.class); }
    private QualityWorkOrderCommandReqVO command(String request, int version) {
        var command = new QualityWorkOrderCommandReqVO();
        command.setId(8L); command.setSubmissionId(11L); command.setRequestId(request); command.setExpectedVersion(version);
        return command;
    }

    private DriverManagerDataSource source(String selectedSchema) {
        var source = new DriverManagerDataSource(url, System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
        var properties = new Properties();
        properties.setProperty("connectTimeout", "5"); properties.setProperty("socketTimeout", "15");
        properties.setProperty("options", "-c statement_timeout=10000 -c lock_timeout=5000");
        if (selectedSchema != null) properties.setProperty("currentSchema", selectedSchema);
        source.setConnectionProperties(properties);
        return source;
    }

    private static Path repoFile(String relative) {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve(relative))) current = current.getParent();
        if (current == null) throw new IllegalStateException("Missing repository file: " + relative);
        return current.resolve(relative);
    }

    private class Node {
        final QualityTaskExecutor executor = mock(QualityTaskExecutor.class);
        final QualityWorkOrderService service;
        final QualityWorkOrderServiceImpl target;
        final QualityWorkOrderActionMapper actions;
        final QualityWorkOrderRecheckMapper rechecks;
        final QualityAuditLogMapper audits;
        final QualityWorkOrderGuard guard = mock(QualityWorkOrderGuard.class);
        final QualityIssueDataMapper issues = mock(QualityIssueDataMapper.class);
        final QualityWorkOrderIssueMapper relations = mock(QualityWorkOrderIssueMapper.class);
        final QualityRepairOrderMapper repairs = mock(QualityRepairOrderMapper.class);
        final NotifyMessageSendApi notifications = mock(NotifyMessageSendApi.class);
        final DataSourceTransactionManager transactions;
        final QualityExceptionExpiryScanService expiryScan = new QualityExceptionExpiryScanService();
        final QualityExceptionExpiryCursorMapper expiryCursors;

        Node() { this(false); }

        Node(boolean realIssues) {
            var source = platformSource;
            var config = new MybatisConfiguration();
            config.setMapUnderscoreToCamelCase(true);
            config.setLocalCacheScope(LocalCacheScope.STATEMENT);
            config.setDefaultStatementTimeout(10);
            config.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
            GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
            var interceptors = new MybatisPlusInterceptor();
            config.addInterceptor(interceptors);
            config.setEnvironment(new Environment("quality-recheck", new SpringManagedTransactionFactory(), source));
            var mappers = Map.of("workOrderMapper", QualityWorkOrderMapper.class,
                    "submissionMapper", QualityWorkOrderSubmissionMapper.class, "actionMapper", QualityWorkOrderActionMapper.class,
                    "recheckMapper", QualityWorkOrderRecheckMapper.class, "instanceMapper", QualityTaskInstanceMapper.class,
                    "auditLogMapper", QualityAuditLogMapper.class, "notifyMapper", QualityWorkOrderNotifyMapper.class,
                    "workOrderIssueMapper", QualityWorkOrderIssueMapper.class, "issueDataMapper", QualityIssueDataMapper.class,
                    "expiryFailureMapper", cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityExceptionExpiryFailureMapper.class);
            mappers.values().forEach(config::addMapper);
            config.addMapper(QualityExceptionExpiryCursorMapper.class);
            var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
            rechecks = sessions.getMapper(QualityWorkOrderRecheckMapper.class);
            audits = sessions.getMapper(QualityAuditLogMapper.class);
            target = new QualityWorkOrderServiceImpl();
            actions = sessions.getMapper(QualityWorkOrderActionMapper.class);
            mappers.forEach((field, type) -> ReflectionTestUtils.setField(target, field, sessions.getMapper(type)));
            var runService = new QualityRunService();
            var tasks = mock(QualityTaskMapper.class);
            when(tasks.selectById(3L)).thenReturn(QualityTaskDO.builder().id(3L).taskName("冻结检查").build());
            when(executor.snapshotTaskDefinition(any())).thenReturn("{}");
            ReflectionTestUtils.setField(runService, "instanceMapper", sessions.getMapper(QualityTaskInstanceMapper.class));
            ReflectionTestUtils.setField(runService, "taskMapper", tasks);
            ReflectionTestUtils.setField(runService, "executor", executor);
            ReflectionTestUtils.setField(target, "qualityRunService", runService);
            when(guard.requireUserId()).thenReturn(4L);
            when(guard.has(any(), any())).thenReturn(true);
            ReflectionTestUtils.setField(target, "guard", guard);
            var users = mock(AdminUserApi.class);
            when(users.getUserList(any())).thenReturn(CommonResult.success(List.of()));
            ReflectionTestUtils.setField(target, "adminUserApi", users);
            if (!realIssues) {
                ReflectionTestUtils.setField(target, "workOrderIssueMapper", relations);
                ReflectionTestUtils.setField(target, "issueDataMapper", issues);
            }
            when(notifications.sendSingleMessageToAdmin(any())).thenReturn(CommonResult.success(100L));
            ReflectionTestUtils.setField(target, "notifyMessageSendApi", notifications);
            ReflectionTestUtils.setField(target, "taskMapper", tasks);
            ReflectionTestUtils.setField(target, "repairOrderMapper", repairs);
            var proxy = new ProxyFactory(target);
            transactions = new DataSourceTransactionManager(source);
            ReflectionTestUtils.setField(target, "transactionManager", transactions);
            expiryCursors = sessions.getMapper(QualityExceptionExpiryCursorMapper.class);
            ReflectionTestUtils.setField(expiryScan, "cursorMapper", expiryCursors);
            ReflectionTestUtils.setField(expiryScan, "submissionMapper", sessions.getMapper(QualityWorkOrderSubmissionMapper.class));
            ReflectionTestUtils.setField(expiryScan, "transactionManager", transactions);
            ReflectionTestUtils.setField(target, "exceptionExpiryScanService", expiryScan);
            proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            service = (QualityWorkOrderService) proxy.getProxy();
        }
    }
}
