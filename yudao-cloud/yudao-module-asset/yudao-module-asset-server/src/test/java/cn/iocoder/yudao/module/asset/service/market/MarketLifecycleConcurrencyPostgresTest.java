package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetGrantSourceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDeliveryOperationMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 两个独立服务代理/连接，共享真实 PostgreSQL；Guard 处设屏障固定旧读与新提交顺序。 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketLifecycleConcurrencyPostgresTest extends SharingPostgresSupport {
    private SqlSessionTemplate session;
    private DataSourceTransactionManager transactionManager;
    private MarketResourceApplyMapper applyMapper;
    private MarketApplyService service;
    private MarketApplyService competingService;
    private MarketObjectGuard delayedGuard;
    private NotifyMessageSendApi notifications;
    private MarketDeliveryCoordinator coordinator;

    @BeforeEach
    void configure() throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/market-freeze-fixture.sql")) {
            if (input == null) throw new IllegalStateException("Missing market fixture");
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        migrate("0042_asset_grant-source-delivery.sql");
        sql("""
                ALTER TABLE dh_asset_grant_source
                  ADD source_type integer, ADD asset_id bigint, ADD start_time timestamp, ADD end_time timestamp,
                  ADD creator text, ADD updater text, ADD create_time timestamp DEFAULT CURRENT_TIMESTAMP,
                  ADD update_time timestamp DEFAULT CURRENT_TIMESTAMP;
                INSERT INTO dh_market_resource_apply
                  (id,asset_id,apply_user_id,status,if_auth,paused,revision,source_revision,
                   app_id,api_id,snapshot,delivery_status)
                  VALUES (7,5,9,2,1,0,1,10,2,3,
                  '{"assetId":"5","assetVersion":"v1","catalogVersionId":"12","publishedSnapshotId":"11",
                    "databaseId":"4","schemaName":"public","tableName":"citizen","frozenColumns":["Id"],
                    "approvedContractHash":"contract-1","apiVersion":"v1"}',0);
                INSERT INTO dh_asset_grant_source
                  (id,apply_id,asset_id,app_id,api_id,source_type,source_key,source_status,revision,scope_json)
                  SELECT 8,id,asset_id,app_id,api_id,1,'asset:apply:7','ACTIVE',10,snapshot
                  FROM dh_market_resource_apply WHERE id=7;
                """);
        var datasource = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center", "sharing_test", "");
        transactionManager = new DataSourceTransactionManager(datasource);
        var config = singleTenantConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("lifecycle-test", new SpringManagedTransactionFactory(), datasource));
        config.addMapper(MarketResourceApplyMapper.class);
        config.addMapper(MarketCheckProcessMapper.class);
        config.addMapper(AssetGrantSourceMapper.class);
        config.addMapper(MarketDeliveryOperationMapper.class);
        session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        applyMapper = session.getMapper(MarketResourceApplyMapper.class);
        notifications = mock(NotifyMessageSendApi.class);
        coordinator = mock(MarketDeliveryCoordinator.class);
        delayedGuard = guard();
        service = service(delayedGuard);
        competingService = service(guard());
        TenantContextHolder.clear();
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    private MarketObjectGuard guard() {
        var guard = mock(MarketObjectGuard.class);
        when(guard.requireUserId()).thenReturn(9L);
        return guard;
    }

    private MarketApplyService service(MarketObjectGuard guard) {
        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applyMapper);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        ReflectionTestUtils.setField(target, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        var assets = mock(DataAssetMapper.class);
        when(assets.selectById(5L)).thenReturn(DataAssetDO.builder().id(5L)
                .status(DataAssetDO.STATUS_PUBLISHED).assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .publishedVersion("v1").publishedSnapshotId(11L).publishedCatalogVersionId(12L)
                .datasourceId(4L).schemaName("public").tableName("citizen").build());
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        var delivery = new MarketDeliveryService();
        ReflectionTestUtils.setField(delivery, "applyMapper", applyMapper);
        ReflectionTestUtils.setField(delivery, "grantSourceMapper", session.getMapper(AssetGrantSourceMapper.class));
        ReflectionTestUtils.setField(delivery, "operationMapper", session.getMapper(MarketDeliveryOperationMapper.class));
        ReflectionTestUtils.setField(delivery, "coordinator", coordinator);
        ReflectionTestUtils.setField(delivery, "approvalEvidenceMapper",
                mock(cn.iocoder.yudao.module.asset.dal.mysql.market.MarketApprovalEvidenceMapper.class));
        ReflectionTestUtils.setField(target, "deliveryService", delivery);
        var notify = new MarketNotifyHelper();
        ReflectionTestUtils.setField(notify, "notifyMessageSendApi", notifications);
        ReflectionTestUtils.setField(target, "notifyHelper", notify);
        ReflectionTestUtils.setField(target, "auditRecorder", mock(MarketAuditRecorder.class));
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (MarketApplyService) proxy.getProxy();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pause", "resume", "enable", "disable"})
    void staleMutationCannotResurrectARevokedApplication(String mutation) throws Exception {
        prepare(mutation);
        var failure = race(() -> mutate(service, mutation), () -> competingService.revokeApply(7L, "withdrawn"));
        assertConflict(failure);
        assertThat(applyMapper.selectById(7L).getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_REVOKED);
        assertSource("REVOKED", 11, "REVOKE", 1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"resume", "enable"})
    void expiryCannotBeUndoneByAnInFlightActivation(String mutation) throws Exception {
        prepare(mutation);
        sql("UPDATE dh_market_resource_apply SET apply_end_time=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')-interval '1 second'");
        var failure = race(() -> mutate(service, mutation), () -> assertThat(competingService.expireDueApplies()).isEqualTo(1));
        assertConflict(failure);
        assertThat(applyMapper.selectById(7L).getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_EXPIRED);
        assertSource("REVOKED", 11, "REVOKE", 1);
    }

    @Test
    void sourceRevisionRejectsPauseResumeAbaEvenWhenFlagsReturnToOldValues() throws Exception {
        var failure = race(() -> service.pauseApply(7L, "stale"), () -> {
            competingService.pauseApply(7L, "new");
            competingService.resumeApply(7L);
        });
        assertConflict(failure);
        assertThat(applyMapper.selectById(7L).getPaused()).isZero();
        assertSource("ACTIVE", 12, "GRANT", 2);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(2);
    }

    @Test
    void concurrentDuplicatePauseAdvancesSourceOnlyOnce() throws Exception {
        assertConflict(race(() -> service.pauseApply(7L, "same"), () -> competingService.pauseApply(7L, "same")));
        assertSource("SUSPENDED", 11, "SUSPEND", 1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
    }

    @Test
    void twoWritersLeavingTheReadBarrierTogetherProduceOnlyOneTransition() throws Exception {
        var bothRead = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var sharedGuard = guard();
        doAnswer(invocation -> {
            bothRead.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(sharedGuard).assertCanAuthApply(any(), any());
        var first = service(sharedGuard);
        var second = service(sharedGuard);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> pauseWithTenant(first));
            var right = executor.submit(() -> pauseWithTenant(second));
            try {
                assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            var leftFailure = left.get(10, TimeUnit.SECONDS);
            var rightFailure = right.get(10, TimeUnit.SECONDS);
            assertThat((leftFailure == null ? 1 : 0) + (rightFailure == null ? 1 : 0)).isEqualTo(1);
            assertConflict(leftFailure == null ? rightFailure : leftFailure);
        }
        assertSource("SUSPENDED", 11, "SUSPEND", 1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
    }

    private Throwable pauseWithTenant(MarketApplyService target) {
        TenantContextHolder.clear();
        try {
            target.pauseApply(7L, "concurrent");
            return null;
        } catch (RuntimeException ex) {
            return ex;
        } finally {
            TenantContextHolder.clear();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"enable", "disable"})
    void authorizationSwitchCannotOverwriteANewerPause(String mutation) throws Exception {
        prepare(mutation);
        assertConflict(race(() -> mutate(service, mutation), () -> competingService.pauseApply(7L, "new pause")));
        assertThat(applyMapper.selectById(7L).getPaused()).isEqualTo(1);
        assertSource("SUSPENDED", 11, "SUSPEND", 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"resume", "enable"})
    void persistedTerminalSourceCannotBeReopenedEvenIfLegacyApplicationStillSaysApproved(String mutation) throws Exception {
        prepare(mutation);
        sql("UPDATE dh_asset_grant_source SET source_status='REVOKED',revoked=1");
        assertThatThrownBy(() -> mutate(service, mutation)).isInstanceOf(ServiceException.class);
        assertThat(applyMapper.selectById(7L).getSourceRevision()).isEqualTo(10L);
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source WHERE source_status='REVOKED' AND revision=10")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isZero();
    }

    @Test
    void missingSourceRowNeverRewindsThePersistedApplicationRevision() throws Exception {
        sql("DELETE FROM dh_asset_grant_source WHERE id=8");
        service.pauseApply(7L, "missing source");
        assertThat(applyMapper.selectById(7L).getSourceRevision()).isEqualTo(11L);
        assertThat(scalar("SELECT revision FROM dh_asset_grant_source WHERE source_key='asset:apply:7'")).isEqualTo(11L);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE source_revision=11")).isEqualTo(1);
    }

    @Test
    void portalOnlyApplicationUsesTheSameCasWithoutCreatingRemoteDelivery() throws Exception {
        sql("UPDATE dh_market_resource_apply SET app_id=NULL,api_id=NULL");
        assertConflict(race(() -> service.pauseApply(7L, "stale"), () -> competingService.revokeApply(7L, "withdrawn")));
        assertThat(applyMapper.selectById(7L).getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_REVOKED);
        assertThat(applyMapper.selectById(7L).getSourceRevision()).isEqualTo(11L);
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source WHERE source_status='REVOKED' AND revision=11")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isZero();
        verifyNoInteractions(coordinator);
    }



    @Test
    void enablingWhilePausedDoesNotGrantUntilResumed() throws Exception {
        sql("UPDATE dh_market_resource_apply SET if_auth=0,paused=1");
        service.authApply(7L, 1);
        assertSource("SUSPENDED", 11, "SUSPEND", 1);
        service.resumeApply(7L);
        assertSource("ACTIVE", 12, "GRANT", 2);
    }

    @Test
    void resumingWhileUnauthorizedDoesNotGrantUntilEnabled() throws Exception {
        sql("UPDATE dh_market_resource_apply SET if_auth=0,paused=1");
        service.resumeApply(7L);
        assertSource("SUSPENDED", 11, "SUSPEND", 1);
        service.authApply(7L, 1);
        assertSource("ACTIVE", 12, "GRANT", 2);
    }

    @Test
    void repeatedAuthorizationRequestDoesNotCreateAnotherRevisionOrDelivery() throws Exception {
        service.authApply(7L, 1);
        assertThat(applyMapper.selectById(7L).getSourceRevision()).isEqualTo(10L);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verifyNoInteractions(notifications, coordinator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"resume", "enable"})
    void activationAfterDatabaseDeadlineFailsEvenBeforeExpiryJobRuns(String mutation) throws Exception {
        prepare(mutation);
        sql("UPDATE dh_market_resource_apply SET apply_end_time=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')-interval '1 second'");
        assertThatThrownBy(() -> mutate(service, mutation)).isInstanceOf(ServiceException.class);
        assertThat(applyMapper.selectById(7L).getSourceRevision()).isEqualTo(10L);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isZero();
    }

    @Test
    void deliveryInsertFailureRollsBackFlagsSourceAndProcessTogether() throws Exception {
        sql("ALTER TABLE dh_market_delivery_operation ADD CONSTRAINT test_fail_delivery CHECK (source_revision < 11)");
        assertThatThrownBy(() -> service.pauseApply(7L, "rollback")).isInstanceOf(RuntimeException.class);
        var apply = applyMapper.selectById(7L);
        assertThat(apply.getPaused()).isZero();
        assertThat(apply.getSourceRevision()).isEqualTo(10L);
        assertThat(scalar("SELECT revision FROM dh_asset_grant_source WHERE id=8")).isEqualTo(10L);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isZero();
        verifyNoInteractions(notifications, coordinator);
    }

    @ParameterizedTest
    @ValueSource(longs = {7, 8, 9})
    void failedExpiryRollsBackOnlyItsApplicationAndDoesNotBlockTheBatch(long failedId) throws Exception {
        prepareExpiryBatch();
        sql("ALTER TABLE dh_market_delivery_operation ADD CONSTRAINT test_expiry_failure CHECK (apply_id <> "
                + failedId + ")");

        assertThatThrownBy(service::expireDueApplies).isInstanceOf(IllegalStateException.class)
                .hasMessage("到期处理部分失败：完成 2 条，失败 1 条").hasNoCause();
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE status=5 AND if_auth=0")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=" + failedId
                + " AND status=2 AND if_auth=1 AND source_revision=10")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source WHERE apply_id=" + failedId
                + " AND source_status='ACTIVE' AND revision=10")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE op_type='REVOKE' AND status='PENDING'"))
                .isEqualTo(2);
        verify(coordinator, times(2)).claim( any(), any());
        verify(notifications, times(2)).sendSingleMessageToAdmin(any());

        // 故障消除后只恢复失败项；已提交项不再推进版本或重复入队。
        sql("ALTER TABLE dh_market_delivery_operation DROP CONSTRAINT test_expiry_failure");
        assertThat(service.expireDueApplies()).isEqualTo(1);
        assertThat(service.expireDueApplies()).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE status=5 AND source_revision=11"))
                .isEqualTo(3);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(3);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(3);
    }

    @Test
    void allExpiryFailuresAreReportedWithoutLeakingSqlAndRemainRetryable() throws Exception {
        prepareExpiryBatch();
        sql("ALTER TABLE dh_market_delivery_operation ADD CONSTRAINT test_expiry_failure CHECK (apply_id < 7)");
        assertThatThrownBy(service::expireDueApplies).isInstanceOf(IllegalStateException.class)
                .hasMessage("到期处理部分失败：完成 0 条，失败 3 条").hasNoCause();
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE status=2 AND source_revision=10"))
                .isEqualTo(3);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isZero();
        verifyNoInteractions(notifications, coordinator);
    }

    private void prepareExpiryBatch() throws Exception {
        sql("""
                INSERT INTO dh_market_resource_apply
                  (id,asset_id,apply_user_id,status,if_auth,paused,revision,source_revision,
                   app_id,api_id,snapshot,delivery_status)
                SELECT n,asset_id,apply_user_id+n,status,if_auth,paused,revision,source_revision,
                   app_id,api_id,snapshot,delivery_status
                FROM dh_market_resource_apply CROSS JOIN generate_series(8,9) n WHERE id=7;
                INSERT INTO dh_asset_grant_source
                  (id,apply_id,asset_id,app_id,api_id,source_type,source_key,source_status,revision,scope_json)
                SELECT id+10,id,asset_id,app_id,api_id,1,'asset:apply:' || id,'ACTIVE',10,snapshot
                FROM dh_market_resource_apply WHERE id IN (8,9);
                UPDATE dh_market_resource_apply
                  SET apply_end_time=(clock_timestamp() AT TIME ZONE 'Asia/Shanghai')-interval '1 second';
                """);
    }

    @Test
    void notificationRunsOnlyAfterCommitWithoutHoldingApplicationLock() throws Exception {
        var observed = new AtomicReference<String>();
        when(notifications.sendSingleMessageToAdmin(any())).thenAnswer(invocation -> {
            try (var other = open(); var statement = other.createStatement()) {
                try (var result = statement.executeQuery("SELECT status FROM dh_market_resource_apply WHERE id=7 FOR UPDATE NOWAIT")) {
                    result.next();
                    observed.set("status=" + result.getInt(1));
                }
            } catch (java.sql.SQLException ex) {
                observed.set(ex.getSQLState());
            }
            return CommonResult.success(1L);
        });
        service.revokeApply(7L, "withdrawn");
        assertThat(observed.get()).isEqualTo("status=6");
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    @Test
    void outerTransactionRollbackSendsNeitherNotificationNorDelivery() throws Exception {
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.revokeApply(7L, "rolled back");
            verifyNoInteractions(notifications, coordinator);
            status.setRollbackOnly();
        });
        assertThat(applyMapper.selectById(7L).getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_APPROVED);
        assertThat(applyMapper.selectById(7L).getSourceRevision()).isEqualTo(10L);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isZero();
        verifyNoInteractions(notifications, coordinator);
    }

    private void prepare(String mutation) throws Exception {
        if ("resume".equals(mutation)) sql("UPDATE dh_market_resource_apply SET paused=1");
        if ("enable".equals(mutation)) sql("UPDATE dh_market_resource_apply SET if_auth=0");
    }

    private void mutate(MarketApplyService target, String mutation) {
        switch (mutation) {
            case "pause" -> target.pauseApply(7L, "pause");
            case "resume" -> target.resumeApply(7L);
            case "enable" -> target.authApply(7L, 1);
            case "disable" -> target.authApply(7L, 0);
            default -> throw new IllegalArgumentException(mutation);
        }
    }

    private Throwable race(Runnable staleWriter, Runnable winningWriter) throws Exception {
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            read.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(delayedGuard).assertCanAuthApply(any(), any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> {
                TenantContextHolder.clear();
                try {
                    staleWriter.run();
                    return (Throwable) null;
                } catch (RuntimeException ex) {
                    return ex;
                } finally {
                    TenantContextHolder.clear();
                }
            });
            try {
                assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
                winningWriter.run();
            } finally {
                release.countDown();
            }
            return result.get(10, TimeUnit.SECONDS);
        }
    }

    private void assertConflict(Throwable failure) {
        assertThat(failure).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) failure).getCode()).isEqualTo(
                cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_STATUS_DENY.getCode());
    }

    private void assertSource(String status, long revision, String action, long operationCount) throws Exception {
        assertThat(applyMapper.selectById(7L).getSourceRevision()).isEqualTo(revision);
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source WHERE id=8 AND revision=" + revision
                + " AND source_status='" + status + "'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(operationCount);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE source_revision=" + revision
                + " AND payload_json->>'action'='" + action + "'")).isEqualTo(1);
    }
}
