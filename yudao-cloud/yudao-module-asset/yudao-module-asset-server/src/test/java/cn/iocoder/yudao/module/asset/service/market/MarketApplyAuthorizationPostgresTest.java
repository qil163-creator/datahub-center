package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
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

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实授权/过程/来源/交付及正式批准凭证，真实Guard；资产、权限RPC和外部发送API为替身。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketApplyAuthorizationPostgresTest extends SharingPostgresSupport {
    private MarketApplyService service;
    private MarketResourceApplyMapper applications;
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
        config.setEnvironment(new Environment("apply-authorization", new SpringManagedTransactionFactory(), source));
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
        ReflectionTestUtils.setField(target, "applyMapper", applications);
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
        var approval = new MarketCheckReqVO();
        approval.setId(7L);
        approval.setStatus(2);
        approval.setCheckNote("original approval");
        service.checkApply(approval); // 真实生成原批准、来源、首次GRANT，后续不得补造或改写批准
        clearInvocations(permissions, assets, coordinator, notifications);
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void skippedProcessCannotCommitAuthorizationOrDelivery(int auth) throws Exception {
        initializeOpposite(auth);
        Stored before = stored();
        sql("""
                CREATE FUNCTION skip_auth_process() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                CREATE TRIGGER skip_auth_process BEFORE INSERT ON dh_market_check_process
                FOR EACH ROW EXECUTE FUNCTION skip_auth_process();
                """);
        deny(() -> service.authApply(7L, auth), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(before);
    }

    @Test
    void nullStateIsBusinessDenial() throws Exception {
        sql("UPDATE dh_market_resource_apply SET status=NULL WHERE id=7");
        Stored before = stored();
        deny(() -> service.authApply(7L, 0), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(before);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "0,1", "1,0", "1,1"})
    void commitAdvancesOneSourceVersionAndPreservesOriginalApproval(int auth, int paused) throws Exception {
        initializeOpposite(auth);
        sql("UPDATE dh_market_resource_apply SET paused=" + paused + " WHERE id=7");
        Stored before = stored();
        long revision = applications.selectById(7L).getSourceRevision() + 1;
        long processCount = scalar("SELECT count(*) FROM dh_market_check_process");
        long operationCount = scalar("SELECT count(*) FROM dh_market_delivery_operation");
        String content = unchangedContent();
        when(coordinator.claim( anyLong(), anyString())).thenAnswer(call -> {
            assertCurrent(auth, paused, revision);
            assertThat(stored().evidence()).isEqualTo(before.evidence());
            return Optional.empty();
        });
        when(notifications.sendSingleMessageToAdmin(any())).thenAnswer(call -> {
            assertCurrent(auth, paused, revision);
            return null;
        });
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.authApply(7L, auth);
            assertThat(applications.selectById(7L).getIfAuth()).isEqualTo(auth);
            assertThatCode(() -> assertThat(stored()).isEqualTo(before)).doesNotThrowAnyException();
            verifyNoInteractions(coordinator, notifications);
        });
        assertCurrent(auth, paused, revision);
        assertThat(unchangedContent()).isEqualTo(content);
        assertThat(stored().evidence()).isEqualTo(before.evidence());
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(processCount + 1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(operationCount + 1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE id=(SELECT max(id) FROM dh_market_check_process)"
                + " AND action='" + (auth == 1 ? "AUTH" : "UNAUTH") + "' AND from_status=2 AND to_status=2"
                + " AND operator_user_id=11 AND resource_apply_id=7")).isEqualTo(1);
        // 开通/停用补普通审计事件：与状态变更同事务。
        verify(auditRecorder).record("APPLY_AUTH", "APPLY", 7L, 2, 2, auth == 1 ? "开通授权" : "取消授权", "OK");
        var message = ArgumentCaptor.forClass(NotifySendSingleToUserReqDTO.class);
        verify(notifications).sendSingleMessageToAdmin(message.capture());
        assertThat(message.getValue().getTemplateParams()).containsEntry("result", auth == 1 ? "开通授权" : "取消授权");
        assertThat(message.getValue().getUserId()).isEqualTo(9L);
        verify(coordinator).claim( anyLong(), anyString());
        Stored committed = stored();
        clearInvocations(coordinator, notifications);
        service.authApply(7L, auth);
        assertUnchanged(committed); // 同目标幂等，不多过程/版本/交付/通知
    }

    @ParameterizedTest
    @CsvSource({"0,CAS_ZERO", "1,CAS_ZERO", "0,PROCESS_CHECK", "1,PROCESS_CHECK",
            "0,SOURCE_CHECK", "1,SOURCE_CHECK", "0,OPERATION_CHECK", "1,OPERATION_CHECK",
            "0,COMMIT", "1,COMMIT"})
    void writeOrCommitFailureRollsBackAllOwnedTables(int auth, String failure) throws Exception {
        initializeOpposite(auth);
        Stored before = stored();
        switch (failure) {
            case "CAS_ZERO" -> sql("""
                    CREATE FUNCTION skip_auth_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                    CREATE TRIGGER skip_auth_update BEFORE UPDATE ON dh_market_resource_apply
                    FOR EACH ROW EXECUTE FUNCTION skip_auth_update();
                    """);
            case "PROCESS_CHECK" -> sql("ALTER TABLE dh_market_check_process ADD CHECK(action NOT IN ('AUTH','UNAUTH')) NOT VALID");
            case "SOURCE_CHECK" -> sql("ALTER TABLE dh_asset_grant_source ADD CHECK(revision<="
                    + applications.selectById(7L).getSourceRevision() + ")");
            case "OPERATION_CHECK" -> sql("ALTER TABLE dh_market_delivery_operation ADD CHECK(source_revision<="
                    + applications.selectById(7L).getSourceRevision() + ")");
            case "COMMIT" -> sql("""
                    CREATE FUNCTION reject_auth_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'test authorization commit failure'; END $$;
                    CREATE CONSTRAINT TRIGGER reject_auth_commit AFTER UPDATE ON dh_market_resource_apply
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_auth_commit();
                    """);
            default -> throw new IllegalArgumentException(failure);
        }
        if ("CAS_ZERO".equals(failure)) deny(() -> service.authApply(7L, auth), MARKET_APPLY_STATUS_DENY.getCode());
        else assertThatThrownBy(() -> service.authApply(7L, auth)).isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(ServiceException.class);
        assertUnchanged(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void outerRollbackDoesNotPublishAuthorizationChange(int auth) throws Exception {
        initializeOpposite(auth);
        Stored before = stored();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.authApply(7L, auth);
            assertThat(applications.selectById(7L).getIfAuth()).isEqualTo(auth);
            tx.setRollbackOnly();
        });
        assertUnchanged(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=5", "source_revision=9", "source_revision=NULL", "if_auth=0", "paused=1", "deleted=1"})
    void oldAuthorizationCommandCannotOverwriteConcurrentLifecycleState(String change) throws Exception {
        if ("source_revision=NULL".equals(change)) {
            sql("ALTER TABLE dh_market_resource_apply ALTER COLUMN source_revision DROP NOT NULL");
        }
        var winner = new AtomicReference<Stored>();
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH)).thenAnswer(call -> {
            sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=7");
            winner.set(stored());
            return CommonResult.success(true);
        });
        deny(() -> service.authApply(7L, 0), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(winner.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void concurrentSameTargetWritesOneProcessAndOneNewVersion(int auth) throws Exception {
        initializeOpposite(auth);
        long nextRevision = applications.selectById(7L).getSourceRevision() + 1;
        long processes = scalar("SELECT count(*) FROM dh_market_check_process");
        long operations = scalar("SELECT count(*) FROM dh_market_delivery_operation");
        var bothRead = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH)).thenAnswer(call -> {
            bothRead.countDown();
            assertThat(release.await(8, TimeUnit.SECONDS)).isTrue();
            return CommonResult.success(true);
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> outcome(auth));
            var second = workers.submit(() -> outcome(auth));
            try { assertThat(bothRead.await(8, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            var outcomes = List.of(first.get(12, TimeUnit.SECONDS), second.get(12, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(ServiceException.class::isInstance).map(ServiceException.class::cast)
                    .map(ServiceException::getCode)).containsExactly(MARKET_APPLY_STATUS_DENY.getCode());
        }
        assertCurrent(auth, 0, nextRevision);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(processes + 1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(operations + 1);
        verify(notifications).sendSingleMessageToAdmin(any());
        verify(coordinator).claim( anyLong(), anyString());
    }



    @ParameterizedTest
    @ValueSource(strings = {"LOGIN", "PERMISSION", "PROVIDER"})
    void realGuardCannotBeBypassedByAnIdempotentRequest(String missing) throws Exception {
        if ("LOGIN".equals(missing)) SecurityContextHolder.clearContext();
        if ("PERMISSION".equals(missing)) when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH))
                .thenReturn(CommonResult.success(false));
        if ("PROVIDER".equals(missing)) authenticate(9L, null); // 申请人不是授权提供方
        Stored before = stored();
        deny(() -> service.authApply(7L, 1), MARKET_APPLY_FORBIDDEN.getCode());
        assertUnchanged(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void postCommitTransportFailureDoesNotUndoDurableIntent(int auth) throws Exception {
        initializeOpposite(auth);
        long nextRevision = applications.selectById(7L).getSourceRevision() + 1;
        when(coordinator.claim( anyLong(), anyString())).thenThrow(new IllegalStateException("test offline"));
        when(notifications.sendSingleMessageToAdmin(any())).thenThrow(new IllegalStateException("test notify offline"));
        service.authApply(7L, auth);
        assertCurrent(auth, 0, nextRevision);
        verify(coordinator).claim( anyLong(), anyString());
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    @Test
    void changedApprovedScopeRollsBackReenable() throws Exception {
        service.authApply(7L, 0);
        sql("UPDATE dh_market_resource_apply SET snapshot=jsonb_set(snapshot,'{frozenColumns}','[\"Id\",\"Phone\"]') WHERE id=7");
        clearInvocations(coordinator, notifications);
        Stored before = stored();
        deny(() -> service.authApply(7L, 1), MARKET_APPLY_SCOPE_UNCONFIRMED.getCode());
        assertUnchanged(before);
    }

    @Test
    void historicalInvalidSnapshotCanStillBeSuspendedWithoutNewApproval() throws Exception {
        sql("UPDATE dh_market_resource_apply SET snapshot='{}'::jsonb WHERE id=7");
        String evidence = stored().evidence();
        service.authApply(7L, 0);
        assertCurrent(0, 0, 3L);
        assertThat(stored().evidence()).isEqualTo(evidence);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void expiredApprovalCanBeDisabledButNotReenabled(int auth) throws Exception {
        initializeOpposite(auth);
        sql("UPDATE dh_market_resource_apply SET apply_end_time='2000-01-01' WHERE id=7");
        Stored before = stored();
        if (auth == 1) {
            deny(() -> service.authApply(7L, auth), MARKET_APPLY_STATUS_DENY.getCode());
            assertUnchanged(before);
        } else {
            service.authApply(7L, auth);
            assertCurrent(0, 0, 3L);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void missingSourceIsRecreatedWithoutRewindingItsApplicationRevision(int auth) throws Exception {
        initializeOpposite(auth);
        long nextRevision = applications.selectById(7L).getSourceRevision() + 1;
        String approval = stored().evidence();
        sql("DELETE FROM dh_asset_grant_source WHERE apply_id=7"); // 仅隔离库，模拟历史来源缺失
        service.authApply(7L, auth);
        assertCurrent(auth, 0, nextRevision);
        assertThat(stored().evidence()).isEqualTo(approval);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void sourceRevisionOverflowRollsBackTheProcessAndAuthorization(int auth) throws Exception {
        initializeOpposite(auth);
        sql("UPDATE dh_market_resource_apply SET source_revision=9223372036854775807 WHERE id=7");
        sql("UPDATE dh_asset_grant_source SET revision=9223372036854775807 WHERE apply_id=7");
        Stored before = stored();
        assertThatThrownBy(() -> service.authApply(7L, auth)).isInstanceOf(ArithmeticException.class);
        assertUnchanged(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void portalOnlyApplicationDoesNotInventRemoteDeliveryOrApproval(int auth) throws Exception {
        sql("""
                INSERT INTO dh_market_resource_apply(id,asset_id,apply_user_id,status,if_auth,paused,revision,
                  source_revision,snapshot) SELECT 8,asset_id,apply_user_id,2,%d,0,1,0,snapshot
                  FROM dh_market_resource_apply WHERE id=7
                """.formatted(1 - auth));
        Stored before = stored();
        service.authApply(8L, auth);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=8 AND if_auth=" + auth
                + " AND source_revision=2 AND delivery_status=0 AND source_grant_id='apply:8:r1'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source WHERE apply_id=8 AND revision=2 AND source_status='"
                + (auth == 1 ? "ACTIVE" : "SUSPENDED") + "'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=8 AND action='"
                + (auth == 1 ? "AUTH" : "UNAUTH") + "'")).isEqualTo(1);
        assertThat(stored().operations()).isEqualTo(before.operations());
        assertThat(stored().evidence()).isEqualTo(before.evidence());
        verifyNoInteractions(coordinator);
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    private void initializeOpposite(int auth) {
        if (auth == 1) service.authApply(7L, 0);
        clearInvocations(permissions, assets, coordinator, notifications);
    }

    private Object outcome(int auth) {
        TenantContextHolder.clear();
        authenticate(11L, null);
        try { service.authApply(7L, auth); return "OK"; }
        catch (RuntimeException failure) { return failure; }
        finally { clearContexts(); }
    }

    private void assertCurrent(int auth, int paused, long revision) throws Exception {
        boolean active = auth == 1 && paused != 1;
        String action = active ? "GRANT" : "SUSPEND";
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND status=2 AND if_auth=" + auth
                + " AND paused=" + paused + " AND source_revision=" + revision + " AND delivery_status=1")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source WHERE apply_id=7 AND revision=" + revision
                + " AND source_key='asset:apply:7' AND source_status='" + (active ? "ACTIVE" : "SUSPENDED") + "'"))
                .isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE apply_id=7 AND source_revision=" + revision
                + " AND operation_id='asset:asset:apply:7:r" + revision + ":" + action + "' AND status='PENDING'"
                + " AND resolution_status='UNCONFIRMED' AND payload_json->>'action'='" + action + "'"))
                .isEqualTo(1);
    }

    private void authenticate(long userId, Long department) {
        var login = new LoginUser();
        login.setId(userId);
        login.setTenantId(TenantContextHolder.getTenantId());
        login.setInfo(department == null ? Map.of() : Map.of(LoginUser.INFO_KEY_DEPT_ID, department.toString()));
        SecurityFrameworkUtils.setLoginUser(login, new MockHttpServletRequest());
    }

    private record Stored(String application, String processes, String sources, String operations, String evidence) { }

    private Stored stored() throws Exception {
        return new Stored(table("dh_market_resource_apply"), table("dh_market_check_process"),
                table("dh_asset_grant_source"), table("dh_market_delivery_operation"), table("dh_market_audit_event"));
    }

    private String table(String table) throws Exception {
        return row("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM " + table + " t");
    }

    private String unchangedContent() throws Exception {
        return row("SELECT (to_jsonb(t)-ARRAY['if_auth','source_revision','source_grant_id','delivery_status','updater','update_time'])::text"
                + " FROM dh_market_resource_apply t WHERE id=7");
    }

    private String row(String query) throws Exception {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(query)) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private void assertUnchanged(Stored before) throws Exception {
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(coordinator, notifications);
    }

    private void fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/" + name)) {
            if (input == null) throw new IllegalStateException("Missing fixture " + name);
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void deny(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(code));
    }
}
