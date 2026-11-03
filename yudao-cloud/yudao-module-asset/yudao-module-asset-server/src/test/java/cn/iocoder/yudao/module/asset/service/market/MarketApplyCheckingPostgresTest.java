package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.config.TenantProperties;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.db.TenantDatabaseInterceptor;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetGrantSourceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.*;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实审批/过程/来源/批准凭证/交付事务与提交后通知；外部协调器不执行远端写入。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketApplyCheckingPostgresTest extends SharingPostgresSupport {
    private static final String NOTE = "本次审核 ' ; -- {0}\n\"依据\"";
    private MarketApplyService service;
    private MarketResourceApplyMapper applications;
    private DataSourceTransactionManager transactions;
    private final MarketObjectGuard guard = mock(MarketObjectGuard.class);
    private final MarketAuditRecorder audit = mock(MarketAuditRecorder.class);
    private final AssetMonitorService monitor = mock(AssetMonitorService.class);
    private final MarketDeliveryCoordinator coordinator = mock(MarketDeliveryCoordinator.class);
    private final NotifyMessageSendApi notifications = mock(NotifyMessageSendApi.class);

    @BeforeEach
    void prepare() throws Exception {
        fixture("market-freeze-fixture.sql");
        migrate("0042_asset_grant-source-delivery.sql");
        fixture("market-approval-fixture.sql");
        migrate("0066_asset_delivery-review-evidence.sql");
        migrate("0067_asset_grant-approval-evidence.sql");
        sql("""
                ALTER TABLE dh_market_resource_apply ALTER COLUMN snapshot TYPE jsonb USING snapshot::jsonb;
                UPDATE dh_market_resource_apply SET check_note='previous opinion',check_user_id=99,
                  check_time='2020-01-01' WHERE id=7;
                """);
        var source = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=10", "sharing_test", "");
        transactions = new DataSourceTransactionManager(source);
        var config = singleTenantConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("apply-checking", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        config.addInterceptor(interceptor);
        config.addMapper(MarketResourceApplyMapper.class);
        config.addMapper(MarketCheckProcessMapper.class);
        config.addMapper(AssetGrantSourceMapper.class);
        config.addMapper(MarketDeliveryOperationMapper.class);
        config.addMapper(MarketApprovalEvidenceMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        applications = session.getMapper(MarketResourceApplyMapper.class);
        var delivery = new MarketDeliveryService();
        ReflectionTestUtils.setField(delivery, "applyMapper", applications);
        ReflectionTestUtils.setField(delivery, "grantSourceMapper", session.getMapper(AssetGrantSourceMapper.class));
        ReflectionTestUtils.setField(delivery, "operationMapper", session.getMapper(MarketDeliveryOperationMapper.class));
        ReflectionTestUtils.setField(delivery, "approvalEvidenceMapper", session.getMapper(MarketApprovalEvidenceMapper.class));
        ReflectionTestUtils.setField(delivery, "coordinator", coordinator);
        when(coordinator.claim( anyLong(), anyString())).thenReturn(Optional.empty());
        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applications);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        var assets = mock(DataAssetMapper.class);
        when(assets.selectById(5L)).thenReturn(DataAssetDO.builder().id(5L)
                .status(DataAssetDO.STATUS_PUBLISHED).assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .publishedVersion("v1").publishedSnapshotId(11L).publishedCatalogVersionId(12L)
                .datasourceId(4L).schemaName("public").tableName("citizen").build());
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        when(guard.requireUserId()).thenReturn(11L);
        when(guard.currentDeptId()).thenReturn(100L);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "deliveryService", delivery);
        ReflectionTestUtils.setField(target, "assetMonitorService", monitor);
        ReflectionTestUtils.setField(target, "auditRecorder", audit);
        var notify = new MarketNotifyHelper();
        ReflectionTestUtils.setField(notify, "notifyMessageSendApi", notifications);
        ReflectionTestUtils.setField(target, "notifyHelper", notify);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (MarketApplyService) proxy.getProxy();
        TenantContextHolder.clear();
    }

    @AfterEach
    void clearTenant() { TenantContextHolder.clear(); }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void skippedProcessCannotCommitEitherDecisionOrItsSideEffects(int status) throws Exception {
        String before = storedApplication();
        sql("""
                CREATE FUNCTION skip_check_process() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                CREATE TRIGGER skip_check_process BEFORE INSERT ON dh_market_check_process
                FOR EACH ROW EXECUTE FUNCTION skip_check_process();
                """);
        deny(() -> service.checkApply(request(status)), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(before);
        verifyNoInteractions(audit, monitor);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void successfulNotificationUsesTheCurrentPersistedOpinion(int status) {
        service.checkApply(request(status));
        assertThat(applications.selectById(7L).getCheckNote()).isEqualTo(NOTE);
        var notification = ArgumentCaptor.forClass(NotifySendSingleToUserReqDTO.class);
        verify(notifications).sendSingleMessageToAdmin(notification.capture());
        assertThat(notification.getValue().getTemplateParams()).containsEntry("checkNote", NOTE)
                .containsEntry("result", status == 2 ? "通过" : "驳回");
        assertThat(notification.getValue().getUserId()).isEqualTo(9L);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void allDurableWritesCommitBeforeCoordinatorOrNotification(int status) throws Exception {
        when(coordinator.claim( anyLong(), anyString())).thenAnswer(call -> {
            assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND status=2")).isEqualTo(1);
            assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='GRANT_APPROVAL_EVIDENCE'"))
                    .isEqualTo(1);
            return Optional.empty();
        });
        when(notifications.sendSingleMessageToAdmin(any())).thenAnswer(call -> {
            assertThat(scalar("SELECT status FROM dh_market_resource_apply WHERE id=7")).isEqualTo(status);
            assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
            return null;
        });
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.checkApply(request(status));
            var checked = applications.selectById(7L);
            assertThat(checked.getStatus()).isEqualTo(status);
            assertThat(checked.getIfAuth()).isEqualTo(status == 2 ? 1 : 0);
            assertThat(checked.getCheckUserId()).isEqualTo(11L);
            assertThat(checked.getCheckNote()).isEqualTo(NOTE);
            assertThat(checked.getCheckTime()).isAfter(java.time.LocalDateTime.of(2020, 1, 1, 0, 0));
            assertThat(checked.getRevision()).isEqualTo(1);
            verifyNoInteractions(coordinator, notifications);
        });
        assertDecisionCounts(status);
        if (status == 2) {
            verify(coordinator).claim( anyLong(), anyString());
            assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE actor_user_id=11 AND dept_id=100"
                    + " AND event_type='GRANT_APPROVAL_EVIDENCE'")).isEqualTo(1);
        } else verifyNoInteractions(coordinator, monitor);
        verify(notifications).sendSingleMessageToAdmin(any());
        verify(audit).record(status == 2 ? "APPROVE" : "REJECT", "APPLY", 7L, 1, status, NOTE, "OK");
        deny(() -> service.checkApply(request(status)), MARKET_APPLY_STATUS_DENY.getCode());
        assertDecisionCounts(status);
        verifyNoMoreInteractions(coordinator, notifications, audit);
    }

    @ParameterizedTest
    @CsvSource({"2,CAS_ZERO", "3,CAS_ZERO", "2,PROCESS_CHECK", "3,PROCESS_CHECK",
            "2,COMMIT", "3,COMMIT", "2,SOURCE", "2,EVIDENCE", "2,OPERATION", "2,EVIDENCE_ZERO"})
    void databaseFailuresRollbackDecisionAndEveryDurableSideEffect(int status, String failure) throws Exception {
        String before = storedApplication();
        switch (failure) {
            case "CAS_ZERO" -> sql("""
                    CREATE FUNCTION skip_check_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                    CREATE TRIGGER skip_check_update BEFORE UPDATE ON dh_market_resource_apply
                    FOR EACH ROW EXECUTE FUNCTION skip_check_update();
                    """);
            case "PROCESS_CHECK" -> sql("ALTER TABLE dh_market_check_process ADD CHECK(action NOT IN ('APPROVE','REJECT'))");
            case "SOURCE" -> sql("ALTER TABLE dh_asset_grant_source ADD CHECK(source_status<>'ACTIVE')");
            case "EVIDENCE" -> sql("ALTER TABLE dh_market_audit_event ADD CHECK(event_type<>'GRANT_APPROVAL_EVIDENCE')");
            case "OPERATION" -> sql("ALTER TABLE dh_market_delivery_operation ADD CHECK(op_type<>'GRANT')");
            case "EVIDENCE_ZERO" -> sql("""
                    CREATE FUNCTION skip_check_evidence() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                    CREATE TRIGGER skip_check_evidence BEFORE INSERT ON dh_market_audit_event
                    FOR EACH ROW EXECUTE FUNCTION skip_check_evidence();
                    """);
            case "COMMIT" -> sql("""
                    CREATE FUNCTION reject_check_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'test checking commit failure'; END $$;
                    CREATE CONSTRAINT TRIGGER reject_check_commit AFTER UPDATE ON dh_market_resource_apply
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_check_commit();
                    """);
            default -> throw new IllegalArgumentException(failure);
        }
        if ("CAS_ZERO".equals(failure) || "EVIDENCE_ZERO".equals(failure)) {
            deny(() -> service.checkApply(request(status)), MARKET_APPLY_STATUS_DENY.getCode());
        } else assertThatThrownBy(() -> service.checkApply(request(status))).isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(ServiceException.class);
        assertUnchanged(before);
        if (!"COMMIT".equals(failure)) verifyNoInteractions(audit, monitor);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void outerRollbackCancelsAfterCommitEffectsAndAllWrites(int status) throws Exception {
        String before = storedApplication();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.checkApply(request(status));
            assertThat(applications.selectById(7L).getStatus()).isEqualTo(status);
            tx.setRollbackOnly();
        });
        assertUnchanged(before);
    }

    @ParameterizedTest
    @CsvSource({"2,status=4", "3,status=4", "2,revision=2", "3,revision=2", "2,asset_id=6", "3,asset_id=6",
            "2,apply_user_id=12", "3,apply_user_id=12", "2,deleted=1", "3,deleted=1"})
    void permissionReadRacesCannotOverwriteTheWinningApplication(int status, String change) throws Exception {
        var winner = new AtomicReference<String>();
        doAnswer(call -> {
            sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=7");
            winner.set(storedApplication());
            return null;
        }).when(guard).assertCanCheckApply(any(), any());
        deny(() -> service.checkApply(request(status)), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(winner.get());
        verifyNoInteractions(audit, monitor);
    }

    @ParameterizedTest
    @CsvSource({"2,2", "3,3", "2,3"})
    void concurrentDecisionsLeaveExactlyOneCoherentOutcome(int firstStatus, int secondStatus) throws Exception {
        var bothRead = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            bothRead.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(guard).assertCanCheckApply(any(), any());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> checkOutcome(firstStatus));
            var second = workers.submit(() -> checkOutcome(secondStatus));
            try { assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            var outcomes = List.of(first.get(12, TimeUnit.SECONDS), second.get(12, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
            var conflict = outcomes.stream().filter(ServiceException.class::isInstance).findFirst().orElseThrow();
            assertThat(((ServiceException) conflict).getCode()).isEqualTo(MARKET_APPLY_STATUS_DENY.getCode());
        }
        int winner = applications.selectById(7L).getStatus();
        assertDecisionCounts(winner);
        verify(notifications).sendSingleMessageToAdmin(any());
        if (winner == 2) verify(coordinator).claim( anyLong(), anyString());
        else verifyNoInteractions(coordinator, monitor);
        verify(audit).record(winner == 2 ? "APPROVE" : "REJECT", "APPLY", 7L, 1, winner, NOTE, "OK");
    }



    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void afterCommitTransportFailureDoesNotUndoOrDuplicateTheDecision(int status) throws Exception {
        when(coordinator.claim( anyLong(), anyString())).thenThrow(new IllegalStateException("test unavailable"));
        when(notifications.sendSingleMessageToAdmin(any())).thenThrow(new IllegalStateException("test unavailable"));
        service.checkApply(request(status));
        assertDecisionCounts(status);
        deny(() -> service.checkApply(request(status)), MARKET_APPLY_STATUS_DENY.getCode());
        assertDecisionCounts(status);
        verify(notifications).sendSingleMessageToAdmin(any());
        if (status == 2) verify(coordinator).claim( anyLong(), anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void nullOpinionDoesNotReusePreviousOpinion(int status) {
        var request = request(status);
        request.setCheckNote(null);
        service.checkApply(request);
        assertThat(applications.selectById(7L).getCheckNote()).isNull();
        var notification = ArgumentCaptor.forClass(NotifySendSingleToUserReqDTO.class);
        verify(notifications).sendSingleMessageToAdmin(notification.capture());
        assertThat(notification.getValue().getTemplateParams()).containsEntry("checkNote", "-");
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void nonApiDecisionKeepsLocalSourcePolicyWithoutInventingRemoteDelivery(int status) throws Exception {
        sql("UPDATE dh_market_resource_apply SET app_id=NULL,api_id=NULL WHERE id=7");
        service.checkApply(request(status));
        assertThat(applications.selectById(7L).getStatus()).isEqualTo(status);
        assertThat(applications.selectById(7L).getIfAuth()).isEqualTo(status == 2 ? 1 : 0);
        assertThat(applications.selectById(7L).getDeliveryStatus()).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source")).isEqualTo(status == 2 ? 1 : 0);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_audit_event")).isZero();
        verifyNoInteractions(coordinator);
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    private Object checkOutcome(int status) {
        TenantContextHolder.clear();
        try { service.checkApply(request(status)); return "OK"; }
        catch (RuntimeException ex) { return ex; }
        finally { TenantContextHolder.clear(); }
    }

    private void assertDecisionCounts(int status) throws Exception {
        String action = status == 2 ? "APPROVE" : "REJECT";
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE action='" + action
                + "' AND resource_apply_id=7 AND from_status=1 AND to_status=" + status
                + " AND operator_user_id=11")).isEqualTo(1);
        for (String table : List.of("dh_asset_grant_source", "dh_market_delivery_operation", "dh_market_audit_event")) {
            assertThat(scalar("SELECT count(*) FROM " + table)).as(table).isEqualTo(status == 2 ? 1 : 0);
        }
        if (status == 2) assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation"
                + " WHERE status='PENDING' AND resolution_status='UNCONFIRMED' AND op_type='GRANT'"
                + " AND apply_id=7")).isEqualTo(1);
    }

    private MarketCheckReqVO request(int status) {
        var request = new MarketCheckReqVO();
        request.setId(7L); request.setStatus(status); request.setCheckNote(NOTE);
        return request;
    }

    private void fixture(String file) throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/" + file)) {
            assertThat(input).isNotNull();
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private String storedApplication() throws Exception {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT row_to_json(t)::text FROM dh_market_resource_apply t WHERE id=7")) {
            assertThat(row.next()).isTrue(); return row.getString(1);
        }
    }

    private void assertUnchanged(String before) throws Exception {
        assertThat(storedApplication()).isEqualTo(before);
        for (String table : List.of("dh_market_check_process", "dh_asset_grant_source",
                "dh_market_delivery_operation", "dh_market_audit_event")) {
            assertThat(scalar("SELECT count(*) FROM " + table)).as(table).isZero();
        }
        verifyNoInteractions(coordinator, notifications);
    }

    private static void deny(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(code));
    }
}
