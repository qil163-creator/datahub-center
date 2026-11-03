package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyReviewReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderNotifyRetryReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderNotifyDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderNotifyReviewDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderNotifyMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderNotifyReviewMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.DriverManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Isolated real-PostgreSQL tests for manual UNKNOWN review and explicit retry authorization. */
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class QualityWorkOrderNotifyReviewPostgreSqlTest {

    private static final String NOTIFY = "dh_governance_quality_work_order_notify";
    private static final String REVIEW = "dh_governance_quality_work_order_notify_review";


    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private QualityWorkOrderService service;
    private QualityWorkOrderNotifyMapper notifyMapper;
    private QualityWorkOrderNotifyReviewMapper reviewMapper;
    private QualityWorkOrderMapper workOrderMapper;
    private QualityWorkOrderGuard guard;
    private String schema;

    @BeforeEach
    void setUp() throws Exception {
        schema = "notify_review_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"), System.getProperty("datahub.test.postgres.user", "dataforge_test"), "")) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(System.getProperty("datahub.test.postgres.url"));
        hikari.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test"));
        hikari.setPassword("");
        hikari.setMaximumPoolSize(8);
        hikari.setConnectionInitSql("SET search_path TO " + schema + ",public");
        dataSource = new HikariDataSource(hikari);
        jdbc = new JdbcTemplate(dataSource);

        cn.iocoder.yudao.module.governance.support.SingleTenantPostgreSqlFixture.copyCurrentTables(jdbc, schema, List.of("dh_governance_quality_work_order", NOTIFY, REVIEW));
        jdbc.update("INSERT INTO dh_governance_quality_work_order(id,order_name,order_status,owner_user_id,creator_user_id,responsibility_status) VALUES(8,'notice review','IN_PROGRESS',4,4,'ASSIGNED')");

        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        GlobalConfigUtils.getGlobalConfig(config).setMetaObjectHandler(new DefaultDBFieldHandler());
        var interceptors = new MybatisPlusInterceptor();
        config.addInterceptor(interceptors);
        config.setEnvironment(new Environment("notify-review-test", new SpringManagedTransactionFactory(), dataSource));
        for (Class<?> mapper : List.of(QualityWorkOrderMapper.class, QualityWorkOrderNotifyMapper.class,
                QualityWorkOrderNotifyReviewMapper.class)) config.addMapper(mapper);
        var sessions = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        notifyMapper = sessions.getMapper(QualityWorkOrderNotifyMapper.class);
        reviewMapper = sessions.getMapper(QualityWorkOrderNotifyReviewMapper.class);

        var target = new QualityWorkOrderServiceImpl();
        workOrderMapper = sessions.getMapper(QualityWorkOrderMapper.class);
        ReflectionTestUtils.setField(target, "workOrderMapper", workOrderMapper);
        ReflectionTestUtils.setField(target, "notifyMapper", notifyMapper);
        ReflectionTestUtils.setField(target, "notifyReviewMapper", reviewMapper);
        guard = mock(QualityWorkOrderGuard.class);
        when(guard.requireUserId()).thenReturn(4L);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(guard).assertHandle(any(), any());
        ReflectionTestUtils.setField(target, "guard", guard);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        ReflectionTestUtils.setField(target, "transactionManager", transactionManager);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        service = (QualityWorkOrderService) proxy.getProxy();
        TenantContextHolder.clear();
    }

    @Test
    void unknownNeedsManualDecisionThenSeparateVersionBoundRetryAuthorization() {
        QualityWorkOrderNotifyDO notify = insertUnknown();
        QualityWorkOrderNotifyReviewReqVO decision = reviewRequest(notify.getId(), "check-1", "NOT_DELIVERED", 0);
        var first = service.reviewNotify(decision);
        assertThat(first.getSendStatus()).isEqualTo("UNKNOWN");
        assertThat(first.getResolutionStatus()).isEqualTo("MANUAL_NOT_DELIVERED");
        assertThat(first.getResolutionVersion()).isEqualTo(1);
        assertThat(notifyMapper.claimRetryable( 10, 300)).isEmpty();

        QualityWorkOrderNotifyRetryReqVO retry = retryRequest(notify.getId(), first.getReviews().get(0).getId(), "retry-1", 1);
        var authorized = service.authorizeNotifyRetry(retry);
        assertThat(authorized.getSendStatus()).isEqualTo("FAILED");
        assertThat(authorized.getResolutionStatus()).isEqualTo("RETRY_AUTHORIZED");
        assertThat(authorized.getReviews()).hasSize(2);
        assertThat(service.authorizeNotifyRetry(retry).getReviews()).hasSize(2); // lost-response replay
        assertThat(notifyMapper.claimRetryable( 10, 300)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT resolution_status FROM " + NOTIFY + " WHERE id=?", String.class,
                notify.getId())).isEqualTo("UNRESOLVED");
        jdbc.update("UPDATE " + NOTIFY + " SET lease_until=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')-interval '1 second' WHERE id=?",
                notify.getId());
        assertThat(notifyMapper.markExpiredClaimsUnknown()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT send_status FROM " + NOTIFY + " WHERE id=?", String.class,
                notify.getId())).isEqualTo("UNKNOWN");
        var secondReview = service.reviewNotify(reviewRequest(notify.getId(), "check-2", "NOT_DELIVERED", 2));
        assertThat(secondReview.getResolutionVersion()).isEqualTo(3);
        assertThat(notifyMapper.claimRetryable( 10, 300)).isEmpty();
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        if (dataSource != null) dataSource.close();
        if (schema != null && schema.matches("notify_review_[a-f0-9]{32}")) {
            try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"), System.getProperty("datahub.test.postgres.user", "dataforge_test"), "")) {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to clean the isolated notification test schema", ex);
            }
        }
    }

    @Test
    void deliveredCannotRetryAndStaleDecisionCannotTouchRow() {
        QualityWorkOrderNotifyDO notify = insertUnknown();
        var delivered = service.reviewNotify(reviewRequest(notify.getId(), "check-delivered", "DELIVERED", 0));
        assertThat(delivered.getSendStatus()).isEqualTo("UNKNOWN");
        assertThat(delivered.getResolutionStatus()).isEqualTo("MANUAL_DELIVERED");
        assertThatThrownBy(() -> service.authorizeNotifyRetry(retryRequest(notify.getId(),
                delivered.getReviews().get(0).getId(), "retry-delivered", 1))).isInstanceOf(RuntimeException.class);

        TenantContextHolder.clear();
        var foreign = reviewRequest(notify.getId(), "stale-delivered", "NOT_DELIVERED", 1);
        assertThatThrownBy(() -> service.reviewNotify(foreign)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void manualReviewAndRetryAuthorizationWorkWithoutTenantContext() {
        QualityWorkOrderNotifyDO notify = insertUnknown();
        TenantContextHolder.clear();
        var reviewed = service.reviewNotify(reviewRequest(notify.getId(), "global-review", "NOT_DELIVERED", 0));
        var receipt = service.authorizeNotifyRetry(retryRequest(notify.getId(),
                reviewed.getReviews().getLast().getId(), "global-retry", 1));
        assertThat(receipt.getSendStatus()).isEqualTo("FAILED");
        assertThat(receipt.getResolutionStatus()).isEqualTo("RETRY_AUTHORIZED");
        assertThat(jdbc.queryForObject("SELECT manual_retry_count FROM " + NOTIFY + " WHERE id=?", Integer.class, notify.getId())).isEqualTo(1);
    }

    @Test
    void manualRetryLimitIsEightAndUnknownRequiresAnotherHumanReviewAfterEachAttempt() {
        QualityWorkOrderNotifyDO notify = insertUnknown();
        int version = 0;
        for (int attempt = 1; attempt <= 8; attempt++) {
            var reviewed = service.reviewNotify(reviewRequest(notify.getId(), "cap-check-" + attempt,
                    "NOT_DELIVERED", version));
            version = reviewed.getResolutionVersion();
            var authorized = service.authorizeNotifyRetry(retryRequest(notify.getId(),
                    reviewed.getReviews().get(reviewed.getReviews().size() - 1).getId(), "cap-retry-" + attempt, version));
            assertThat(authorized.getManualRetryCount()).isEqualTo(attempt);
            assertThat(notifyMapper.claimRetryable( 10, 300)).hasSize(1);
            jdbc.update("UPDATE " + NOTIFY + " SET lease_until=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')-interval '1 second' WHERE id=?",
                    notify.getId());
            assertThat(notifyMapper.markExpiredClaimsUnknown()).isEqualTo(1);
            version++;
        }
        var finalReview = service.reviewNotify(reviewRequest(notify.getId(), "cap-check-9", "NOT_DELIVERED", version));
        assertThatThrownBy(() -> service.authorizeNotifyRetry(retryRequest(notify.getId(),
                finalReview.getReviews().get(finalReview.getReviews().size() - 1).getId(), "cap-retry-9",
                finalReview.getResolutionVersion()))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT manual_retry_count FROM " + NOTIFY + " WHERE id=?", Integer.class,
                notify.getId())).isEqualTo(8);
    }

    @Test
    void concurrentSameRequestReplaysOneAuditAndRollbackKeepsUnknown() throws Exception {
        QualityWorkOrderNotifyDO notify = insertUnknown();
        var request = reviewRequest(notify.getId(), "concurrent-review", "NOT_DELIVERED", 0);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> {
                TenantContextHolder.clear();
                try { await(start); return service.reviewNotify(request); }
                finally { TenantContextHolder.clear(); }
            });
            Future<?> b = pool.submit(() -> {
                TenantContextHolder.clear();
                try { await(start); return service.reviewNotify(request); }
                finally { TenantContextHolder.clear(); }
            });
            start.countDown();
            a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + REVIEW + " WHERE request_id='concurrent-review'", Integer.class))
                .isEqualTo(1);

        QualityWorkOrderNotifyDO rollbackRow = insertUnknown();
        QualityWorkOrderNotifyReviewMapper failingReviews = mock(QualityWorkOrderNotifyReviewMapper.class);
        when(failingReviews.selectByRequestId( any())).thenReturn(null);
        when(failingReviews.insert(any(QualityWorkOrderNotifyReviewDO.class)))
                .thenThrow(new IllegalStateException("force rollback"));
        // A separate service proxy reuses the real mapper transaction and fails after the CAS update.
        var rollbackTarget = new QualityWorkOrderServiceImpl();
        ReflectionTestUtils.setField(rollbackTarget, "workOrderMapper", workOrderMapper);
        ReflectionTestUtils.setField(rollbackTarget, "notifyMapper", notifyMapper);
        ReflectionTestUtils.setField(rollbackTarget, "notifyReviewMapper", failingReviews);
        ReflectionTestUtils.setField(rollbackTarget, "guard", guard);
        var tx = new DataSourceTransactionManager(dataSource);
        ReflectionTestUtils.setField(rollbackTarget, "transactionManager", tx);
        var proxy = new ProxyFactory(rollbackTarget);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        QualityWorkOrderService rollbackService = (QualityWorkOrderService) proxy.getProxy();
        TenantContextHolder.clear();
        assertThatThrownBy(() -> rollbackService.reviewNotify(reviewRequest(rollbackRow.getId(), "rollback", "NOT_DELIVERED", 0)))
                .isInstanceOf(RuntimeException.class);
        org.mockito.Mockito.verify(failingReviews).insert(any(QualityWorkOrderNotifyReviewDO.class));
        assertThat(jdbc.queryForObject("SELECT send_status FROM " + NOTIFY + " WHERE id=?", String.class, rollbackRow.getId()))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT resolution_version FROM " + NOTIFY + " WHERE id=?", Integer.class, rollbackRow.getId()))
                .isZero();
    }

    private QualityWorkOrderNotifyDO insertUnknown() {
        QualityWorkOrderNotifyDO row = QualityWorkOrderNotifyDO.builder().workOrderId(8L).eventType("ASSIGN")
                .receiverUserId(4L).timeSlice(UUID.randomUUID().toString().replace("-", "").substring(0, 32)).payloadSummary("test")
                .sendStatus(QualityWorkOrderNotifyDO.STATUS_UNKNOWN).retryCount(0).build();

        assertThat(notifyMapper.insert(row)).isEqualTo(1);
        return row;
    }

    private static QualityWorkOrderNotifyReviewReqVO reviewRequest(Long id, String requestId, String outcome, int version) {
        var request = new QualityWorkOrderNotifyReviewReqVO();
        request.setId(id); request.setExpectedResolutionVersion(version); request.setRequestId(requestId);
        request.setOutcome(outcome); request.setReason("isolated test manual verification");
        if ("DELIVERED".equals(outcome)) request.setOperatorMessageId(17L);
        return request;
    }

    private static QualityWorkOrderNotifyRetryReqVO retryRequest(Long id, Long decisionId, String requestId, int version) {
        var request = new QualityWorkOrderNotifyRetryReqVO();
        request.setId(id); request.setDecisionId(decisionId); request.setExpectedResolutionVersion(version);
        request.setRequestId(requestId); request.setReason("isolated test explicit retry authorization");
        return request;
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
    }

    private static Path repoFile(String relative) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Could not locate repository SQL directory");
        return root.resolve(relative);
    }
}
