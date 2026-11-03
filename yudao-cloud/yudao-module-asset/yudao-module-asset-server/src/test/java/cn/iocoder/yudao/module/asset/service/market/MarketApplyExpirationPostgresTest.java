package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
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
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
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
import org.mockito.AdditionalAnswers;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 到期扫描与逐申请REQUIRES_NEW真实事务；仅外部协调器/通知/资产API为替身。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketApplyExpirationPostgresTest extends SharingPostgresSupport {
    private MarketApplyService service;
    private MarketResourceApplyMapper applications;
    private MarketResourceApplyMapper scans;
    private DataSourceTransactionManager transactions;
    private final PermissionApi permissions = mock(PermissionApi.class);
    private final MarketDeliveryCoordinator coordinator = mock(MarketDeliveryCoordinator.class);
    private final NotifyMessageSendApi notifications = mock(NotifyMessageSendApi.class);
    private final DataAssetMapper assets = mock(DataAssetMapper.class);
    private final MarketAuditRecorder auditRecorder = mock(MarketAuditRecorder.class);

    @BeforeEach
    void prepare() throws Exception {
        fixture("market-freeze-fixture.sql");
        migrate("0042_asset_grant-source-delivery.sql");
        fixture("market-approval-fixture.sql");
        migrate("0066_asset_delivery-review-evidence.sql");
        migrate("0067_asset_grant-approval-evidence.sql");
        sql("ALTER TABLE dh_market_resource_apply ALTER COLUMN snapshot TYPE jsonb USING snapshot::jsonb");
        var source = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=10", "sharing_test", "");
        transactions = new DataSourceTransactionManager(source);
        var config = singleTenantConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("apply-expiration", new SpringManagedTransactionFactory(), source));
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
        when(assets.selectById(5L)).thenReturn(DataAssetDO.builder().id(5L)
                .status(DataAssetDO.STATUS_PUBLISHED).assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .publishedVersion("v1").publishedSnapshotId(11L).publishedCatalogVersionId(12L)
                .datasourceId(4L).schemaName("public").tableName("citizen").techLeaderUserId(11L)
                .businessLeaderUserId(12L).ownerDeptId(100L).build());
        // 锁内复验与入口读取同一替身资产；需要真实并发语义的场景见 MarketProviderDecisionSerializationPostgresTest。
        when(assets.selectForMatching( anyLong())).thenAnswer(call -> assets.selectById(call.<Long>getArgument(0)));
        when(permissions.hasAnyPermissions(anyLong(), any(String[].class))).thenReturn(CommonResult.success(true));
        var guard = new MarketObjectGuard();
        ReflectionTestUtils.setField(guard, "permissionApi", permissions);
        ReflectionTestUtils.setField(guard, "assetMapper", assets);
        var target = new MarketApplyServiceImpl();
        scans = mock(MarketResourceApplyMapper.class, AdditionalAnswers.delegatesTo(applications));
        ReflectionTestUtils.setField(target, "applyMapper", scans);
        ReflectionTestUtils.setField(target, "transactionManager", transactions);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "deliveryService", delivery);
        ReflectionTestUtils.setField(target, "assetMonitorService", mock(AssetMonitorService.class));
        ReflectionTestUtils.setField(target, "auditRecorder", auditRecorder);
        var notify = new MarketNotifyHelper();
        ReflectionTestUtils.setField(notify, "notifyMessageSendApi", notifications);
        ReflectionTestUtils.setField(target, "notifyHelper", notify);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (MarketApplyService) proxy.getProxy();
        TenantContextHolder.clear();
        authenticate(11L, null);
        sql("UPDATE dh_market_resource_apply SET apply_end_time='2000-01-01' WHERE id=7");
        var approval = new MarketCheckReqVO();
        approval.setId(7L);
        approval.setStatus(2);
        approval.setCheckNote("original approval");
        service.checkApply(approval); // 真实生成原批准、来源、首次GRANT，后续不得补造或改写批准
        SecurityContextHolder.clearContext(); // 作业只需可信租户，不冒用网页登录操作者
        clearInvocations(permissions, assets, coordinator, notifications, scans);
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(longs = {7, 8, 9})
    void skippedProcessIsAFailedItemNotACompletedExpiry(long failedId) throws Exception {
        batch(9);
        Stored before = owned(failedId);
        sql("""
                CREATE FUNCTION skip_expire_process() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.resource_apply_id=%d THEN RETURN NULL; END IF; RETURN NEW; END $$;
                CREATE TRIGGER skip_expire_process BEFORE INSERT ON dh_market_check_process
                FOR EACH ROW EXECUTE FUNCTION skip_expire_process();
                """.formatted(failedId));
        partialFailure(2, 1);
        assertThat(owned(failedId)).isEqualTo(before);
        for (long id : List.of(7L, 8L, 9L)) if (id != failedId) assertExpired(id, 3L);
        verify(coordinator, times(2)).claim( anyLong(), anyString());
        verify(notifications, times(2)).sendSingleMessageToAdmin(any());
        sql("DROP TRIGGER skip_expire_process ON dh_market_check_process");
        assertThat(service.expireDueApplies()).isEqualTo(1);
        assertThat(service.expireDueApplies()).isZero();
        for (long id : List.of(7L, 8L, 9L)) assertExpired(id, 3L);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE action='EXPIRE'")).isEqualTo(3);
        verify(coordinator, times(3)).claim( anyLong(), anyString());
        verify(notifications, times(3)).sendSingleMessageToAdmin(any());
    }

    @Test
    void allSkippedProcessesRemainRetryableAndHaveNoSuccessSideEffects() throws Exception {
        batch(9);
        List<Stored> before = List.of(owned(7), owned(8), owned(9));
        sql("""
                CREATE FUNCTION skip_all_expiry() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                CREATE TRIGGER skip_all_expiry BEFORE INSERT ON dh_market_check_process
                FOR EACH ROW EXECUTE FUNCTION skip_all_expiry();
                """);
        partialFailure(0, 3);
        assertThat(List.of(owned(7), owned(8), owned(9))).isEqualTo(before);
        verifyNoInteractions(coordinator, notifications, assets, permissions);
    }

    @ParameterizedTest
    @CsvSource({"7,PROCESS", "8,PROCESS", "9,PROCESS", "7,SOURCE", "8,SOURCE", "9,SOURCE",
            "7,OPERATION", "8,OPERATION", "9,OPERATION", "7,COMMIT", "8,COMMIT", "9,COMMIT"})
    void oneItemFailureRollsBackItsFiveTablesAndDoesNotUndoOtherCommits(long failedId, String stage) throws Exception {
        batch(9);
        Stored before = owned(failedId);
        switch (stage) {
            case "PROCESS" -> sql("ALTER TABLE dh_market_check_process ADD CHECK(action<>'EXPIRE' OR resource_apply_id<>"
                    + failedId + ") NOT VALID");
            case "SOURCE" -> sql("ALTER TABLE dh_asset_grant_source ADD CHECK(source_status<>'REVOKED' OR apply_id<>"
                    + failedId + ") NOT VALID");
            case "OPERATION" -> sql("ALTER TABLE dh_market_delivery_operation ADD CHECK(payload_json->>'action'<>'REVOKE' OR apply_id<>"
                    + failedId + ") NOT VALID");
            case "COMMIT" -> sql("""
                    CREATE FUNCTION reject_expiry_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN IF NEW.id=%d THEN RAISE EXCEPTION 'test expiry commit failure'; END IF; RETURN NEW; END $$;
                    CREATE CONSTRAINT TRIGGER reject_expiry_commit AFTER UPDATE ON dh_market_resource_apply
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_expiry_commit();
                    """.formatted(failedId));
            default -> throw new IllegalArgumentException(stage);
        }
        partialFailure(2, 1);
        assertThat(owned(failedId)).isEqualTo(before);
        for (long id : List.of(7L, 8L, 9L)) if (id != failedId) assertExpired(id, 3L);
        verify(coordinator, times(2)).claim( anyLong(), anyString());
        verify(notifications, times(2)).sendSingleMessageToAdmin(any());
    }

    @Test
    void successfulExpiryIsVisibleAndUnlockedBeforeNotifications() throws Exception {
        String approval = owned(7).evidence();
        String content = unchangedContent();
        when(coordinator.claim( anyLong(), anyString())).thenAnswer(call -> {
            assertExpired(7L, 3L);
            return Optional.empty();
        });
        when(notifications.sendSingleMessageToAdmin(any())).thenAnswer(call -> {
            try (var other = open(); var statement = other.createStatement();
                 var row = statement.executeQuery("SELECT status FROM dh_market_resource_apply WHERE id=7 FOR UPDATE NOWAIT")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getInt(1)).isEqualTo(5);
            }
            assertExpired(7L, 3L);
            return null;
        });
        assertThat(service.expireDueApplies()).isEqualTo(1);
        assertExpired(7L, 3L);
        assertThat(owned(7).evidence()).isEqualTo(approval);
        assertThat(unchangedContent()).isEqualTo(content);
        var notification = ArgumentCaptor.forClass(NotifySendSingleToUserReqDTO.class);
        verify(notifications).sendSingleMessageToAdmin(notification.capture());
        assertThat(notification.getValue().getTemplateCode()).isEqualTo(MarketNotifyHelper.APPLY_EXPIRED);
        assertThat(notification.getValue().getUserId()).isEqualTo(9L);
        verifyNoInteractions(permissions);
        assertThat(scalar("SELECT operator_user_id FROM dh_market_check_process WHERE action='EXPIRE'")).isEqualTo(9L);
        clearInvocations(coordinator, notifications, assets);
        Stored after = owned(7);
        assertThat(service.expireDueApplies()).isZero();
        assertThat(owned(7)).isEqualTo(after);
        verifyNoInteractions(coordinator, notifications, assets);
    }

    @Test
    void perItemRequiresNewCommitSurvivesAnUnrelatedOuterRollback() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(service.expireDueApplies()).isEqualTo(1);
            assertThatCode(() -> assertExpired(7L, 3L)).doesNotThrowAnyException();
            verify(notifications).sendSingleMessageToAdmin(any());
            tx.setRollbackOnly();
        });
        assertExpired(7L, 3L);
        assertThat(service.expireDueApplies()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=6", "source_revision=9", "if_auth=0", "paused=1", "deleted=1",
            "apply_end_time='2999-01-01'", "apply_end_time=NULL"})
    void changeAfterScanIsDiscardedWithoutCompletionOrFailure(String change) throws Exception {
        var winner = new AtomicReference<Stored>();
        doAnswer(call -> {
            var observed = applications.selectExpiredDue( 100);
            sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=7");
            winner.set(owned(7));
            return observed;
        }).when(scans).selectExpiredDue( 100);
        assertThat(service.expireDueApplies()).isZero();
        assertThat(owned(7)).isEqualTo(winner.get());
        verifyNoInteractions(coordinator, notifications, assets);
    }

    @Test
    void zeroRowCasIsDiscardedWithoutCreatingAProcess() throws Exception {
        Stored before = owned(7);
        sql("""
                CREATE FUNCTION skip_expiry_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                CREATE TRIGGER skip_expiry_update BEFORE UPDATE ON dh_market_resource_apply
                FOR EACH ROW EXECUTE FUNCTION skip_expiry_update();
                """);
        assertThat(service.expireDueApplies()).isZero();
        assertThat(owned(7)).isEqualTo(before);
        verifyNoInteractions(coordinator, notifications, assets);
    }

    @Test
    void twoScannersCommitEachDueApplicationOnce() throws Exception {
        batch(9);
        var bothRead = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            var observed = applications.selectExpiredDue( 100);
            bothRead.countDown();
            assertThat(release.await(8, TimeUnit.SECONDS)).isTrue();
            return observed;
        }).when(scans).selectExpiredDue( 100);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(this::expireFromWorker);
            var second = workers.submit(this::expireFromWorker);
            try { assertThat(bothRead.await(8, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(3);
        }
        for (long id : List.of(7L, 8L, 9L)) assertExpired(id, 3L);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE action='EXPIRE'")).isEqualTo(3);
        verify(coordinator, times(3)).claim( anyLong(), anyString());
        verify(notifications, times(3)).sendSingleMessageToAdmin(any());
    }



    @ParameterizedTest
    @ValueSource(strings = {"status=1", "status=6", "apply_end_time=NULL", "apply_end_time='2999-01-01'", "deleted=1"})
    void scanDoesNotExpireIneligibleApplications(String change) throws Exception {
        sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=7");
        Stored before = owned(7);
        assertThat(service.expireDueApplies()).isZero();
        assertThat(owned(7)).isEqualTo(before);
        verifyNoInteractions(coordinator, notifications, assets);
    }

    @Test
    void scanRetainsItsHundredItemLimitAndLaterRunDrainsTheRemainder() throws Exception {
        batch(107);
        assertThat(service.expireDueApplies()).isEqualTo(100);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE status=5")).isEqualTo(100);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE status=2")).isEqualTo(1);
        assertThat(service.expireDueApplies()).isEqualTo(1);
        assertThat(service.expireDueApplies()).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE action='EXPIRE'")).isEqualTo(101);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE payload_json->>'action'='REVOKE'"))
                .isEqualTo(101);
    }

    @Test
    void notificationAndImmediateClaimFailureDoNotUndoAnExpiredCommit() throws Exception {
        when(coordinator.claim( anyLong(), anyString())).thenThrow(new IllegalStateException("test offline"));
        when(notifications.sendSingleMessageToAdmin(any())).thenThrow(new IllegalStateException("test notify offline"));
        assertThat(service.expireDueApplies()).isEqualTo(1);
        assertExpired(7L, 3L);
    }

    @Test
    void sourceRevisionOverflowIsAFailedItemAndNeverMarksExpirySuccessful() throws Exception {
        sql("UPDATE dh_market_resource_apply SET source_revision=9223372036854775807 WHERE id=7");
        sql("UPDATE dh_asset_grant_source SET revision=9223372036854775807 WHERE apply_id=7");
        Stored before = owned(7);
        partialFailure(0, 1);
        assertThat(owned(7)).isEqualTo(before);
        verifyNoInteractions(coordinator, notifications, assets);
    }

    private void batch(int last) throws Exception {
        sql("""
                INSERT INTO dh_market_resource_apply(id,asset_id,apply_user_id,status,if_auth,paused,revision,
                  source_revision,app_id,api_id,snapshot,delivery_status,apply_end_time)
                SELECT n,asset_id,apply_user_id+n,status,if_auth,paused,revision,source_revision,
                  app_id,api_id,snapshot,delivery_status,apply_end_time
                FROM dh_market_resource_apply CROSS JOIN generate_series(8,%d) n WHERE id=7
                """.formatted(last));
        // 克隆行模拟有申请版本但来源缺失的历史到期记录；只有7号有本例真实创建的原批准凭证。
    }

    private void partialFailure(int completed, int failed) {
        assertThatThrownBy(service::expireDueApplies).isInstanceOf(IllegalStateException.class)
                .hasMessage("到期处理部分失败：完成 " + completed + " 条，失败 " + failed + " 条").hasNoCause();
    }

    private int expireFromWorker() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
        try { return service.expireDueApplies(); }
        finally { clearContexts(); }
    }

    private void assertExpired(long id, long revision) throws Exception {
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=" + id
                + " AND status=5 AND if_auth=0 AND source_revision=" + revision + " AND delivery_status=1")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source WHERE apply_id=" + id + " AND revision=" + revision
                + " AND source_status='REVOKED' AND revoked=1")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE apply_id=" + id
                + " AND source_revision=" + revision + " AND status='PENDING' AND resolution_status='UNCONFIRMED'"
                + " AND payload_json->>'action'='REVOKE'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=" + id
                + " AND action='EXPIRE' AND from_status=2 AND to_status=5"
                + " AND note='使用期限到期自动失效'")).isEqualTo(1);
        // 到期补普通审计事件：作业无登录上下文，与过程记录一致使用申请人作为操作者。
        verify(auditRecorder).recordAs("APPLY_EXPIRE", "APPLY", id, 2, 5, "使用期限到期自动失效", "OK",
                applications.selectById(id).getApplyUserId(), null);
    }

    private void authenticate(long userId, Long department) {
        var login = new LoginUser();
        login.setId(userId);
        login.setTenantId(TenantContextHolder.getTenantId());
        login.setInfo(department == null ? Map.of() : Map.of(LoginUser.INFO_KEY_DEPT_ID, department.toString()));
        SecurityFrameworkUtils.setLoginUser(login, new MockHttpServletRequest());
    }

    private record Stored(String application, String processes, String sources, String operations, String evidence) { }

    private Stored owned(long id) throws Exception {
        return new Stored(table("dh_market_resource_apply", "id=" + id),
                table("dh_market_check_process", "resource_apply_id=" + id),
                table("dh_asset_grant_source", "apply_id=" + id),
                table("dh_market_delivery_operation", "apply_id=" + id),
                table("dh_market_audit_event", "object_id=" + id));
    }

    private String table(String name, String condition) throws Exception {
        return row("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM " + name + " t WHERE " + condition);
    }

    private String unchangedContent() throws Exception {
        return row("SELECT (to_jsonb(t)-ARRAY['status','if_auth','source_revision','source_grant_id','delivery_status',"
                + "'updater','update_time'])::text FROM dh_market_resource_apply t WHERE id=7");
    }

    private String row(String query) throws Exception {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(query)) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private void fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/" + name)) {
            if (input == null) throw new IllegalStateException("Missing fixture " + name);
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
