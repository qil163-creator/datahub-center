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
import org.junit.jupiter.params.provider.NullSource;
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
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/** 收回授权的真实过程/来源/终态交付事务；外部发送与资产/权限RPC为替身。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketApplyRevocationPostgresTest extends SharingPostgresSupport {
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
        config.setEnvironment(new Environment("apply-revocation", new SpringManagedTransactionFactory(), source));
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

    private static final String NOTE = "收回依据 ' ; -- {0}\n\"scope\"";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void skippedProcessMustNotCommitRevokeOrAnySideEffect(boolean suspended) throws Exception {
        if (suspended) service.authApply(7L, 0);
        clearInvocations(coordinator, notifications);
        Stored before = stored();
        sql("""
                CREATE FUNCTION skip_revoke_process() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                CREATE TRIGGER skip_revoke_process BEFORE INSERT ON dh_market_check_process
                FOR EACH ROW EXECUTE FUNCTION skip_revoke_process();
                """);
        deny(() -> service.revokeApply(7L, NOTE), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(before);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", NOTE})
    void terminalRevokeCommitsBeforeNotificationAndPreservesOriginalApproval(String note) throws Exception {
        Stored before = stored();
        String content = unchangedContent();
        when(coordinator.claim( anyLong(), anyString())).thenAnswer(call -> {
            assertRevoked(7L, 3L, note);
            assertThat(stored().evidence()).isEqualTo(before.evidence());
            return Optional.empty();
        });
        when(notifications.sendSingleMessageToAdmin(any())).thenAnswer(call -> {
            assertRevoked(7L, 3L, note);
            return null;
        });
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.revokeApply(7L, note);
            assertThat(applications.selectById(7L).getStatus()).isEqualTo(6);
            assertThatCode(() -> assertThat(stored()).isEqualTo(before)).doesNotThrowAnyException();
            verifyNoInteractions(coordinator, notifications);
        });
        assertRevoked(7L, 3L, note);
        assertThat(unchangedContent()).isEqualTo(content);
        assertThat(stored().evidence()).isEqualTo(before.evidence());
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(2);
        var message = ArgumentCaptor.forClass(NotifySendSingleToUserReqDTO.class);
        verify(notifications).sendSingleMessageToAdmin(message.capture());
        assertThat(message.getValue().getTemplateCode()).isEqualTo(MarketNotifyHelper.APPLY_REVOKED);
        assertThat(message.getValue().getTemplateParams()).containsEntry("result", "收回")
                .containsEntry("checkNote", note == null || note.isBlank() ? "-" : note);
        assertThat(message.getValue().getUserId()).isEqualTo(9L);
        verify(coordinator).claim( anyLong(), anyString());
        Stored committed = stored();
        clearInvocations(coordinator, notifications);
        deny(() -> service.revokeApply(7L, "repeat"), MARKET_APPLY_STATUS_DENY.getCode());
        deny(() -> service.authApply(7L, 1), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(committed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAS_ZERO", "PROCESS_CHECK", "SOURCE_CHECK", "OPERATION_CHECK", "COMMIT"})
    void failureRollsBackStatusNoteSourceVersionAndFrozenRevoke(String failure) throws Exception {
        Stored before = stored();
        switch (failure) {
            case "CAS_ZERO" -> sql("""
                    CREATE FUNCTION skip_revoke_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                    CREATE TRIGGER skip_revoke_update BEFORE UPDATE ON dh_market_resource_apply
                    FOR EACH ROW EXECUTE FUNCTION skip_revoke_update();
                    """);
            case "PROCESS_CHECK" -> sql("ALTER TABLE dh_market_check_process ADD CHECK(action<>'REVOKE')");
            case "SOURCE_CHECK" -> sql("ALTER TABLE dh_asset_grant_source ADD CHECK(source_status<>'REVOKED')");
            case "OPERATION_CHECK" -> sql("ALTER TABLE dh_market_delivery_operation ADD CHECK(payload_json->>'action'<>'REVOKE')");
            case "COMMIT" -> sql("""
                    CREATE FUNCTION reject_revoke_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'test revoke commit failure'; END $$;
                    CREATE CONSTRAINT TRIGGER reject_revoke_commit AFTER UPDATE ON dh_market_resource_apply
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_revoke_commit();
                    """);
            default -> throw new IllegalArgumentException(failure);
        }
        if ("CAS_ZERO".equals(failure)) deny(() -> service.revokeApply(7L, NOTE), MARKET_APPLY_STATUS_DENY.getCode());
        else assertThatThrownBy(() -> service.revokeApply(7L, NOTE)).isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(ServiceException.class);
        assertUnchanged(before);
    }

    @Test
    void outerRollbackRestoresTheEntireOwnedState() throws Exception {
        Stored before = stored();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.revokeApply(7L, NOTE);
            assertThat(applications.selectById(7L).getStatus()).isEqualTo(6);
            tx.setRollbackOnly();
        });
        assertUnchanged(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status=5", "source_revision=9", "if_auth=0", "paused=1", "deleted=1"})
    void staleRevokeDoesNotOverwriteConcurrentLifecycleChanges(String change) throws Exception {
        var winner = new AtomicReference<Stored>();
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH)).thenAnswer(call -> {
            sql("UPDATE dh_market_resource_apply SET " + change + " WHERE id=7");
            winner.set(stored());
            return CommonResult.success(true);
        });
        deny(() -> service.revokeApply(7L, NOTE), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(winner.get());
    }

    @Test
    void concurrentRevokersCommitOnlyWinningNoteProcessAndSourceVersion() throws Exception {
        var bothRead = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH)).thenAnswer(call -> {
            bothRead.countDown();
            assertThat(release.await(8, TimeUnit.SECONDS)).isTrue();
            return CommonResult.success(true);
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> outcome("first"));
            var second = workers.submit(() -> outcome("second"));
            try { assertThat(bothRead.await(8, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            var outcomes = List.of(first.get(12, TimeUnit.SECONDS), second.get(12, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(ServiceException.class::isInstance).map(ServiceException.class::cast)
                    .map(ServiceException::getCode)).containsExactly(MARKET_APPLY_STATUS_DENY.getCode());
        }
        String winnerNote = applications.selectById(7L).getCheckNote();
        assertThat(winnerNote).isIn("first", "second");
        assertRevoked(7L, 3L, winnerNote);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(2);
        var message = ArgumentCaptor.forClass(NotifySendSingleToUserReqDTO.class);
        verify(notifications).sendSingleMessageToAdmin(message.capture());
        assertThat(message.getValue().getTemplateParams()).containsEntry("checkNote", winnerNote);
        verify(coordinator).claim( anyLong(), anyString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 1, 3, 4, 5, 6, 8})
    void onlyAnApprovedApplicationCanBeRevoked(Integer status) throws Exception {
        sql("UPDATE dh_market_resource_apply SET status=" + (status == null ? "NULL" : status) + " WHERE id=7");
        Stored before = stored();
        deny(() -> service.revokeApply(7L, NOTE), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(before);
    }



    @ParameterizedTest
    @ValueSource(strings = {"LOGIN", "PERMISSION", "PROVIDER", "ASSET"})
    void realGuardRejectsMissingIdentityPermissionOrProvider(String missing) throws Exception {
        switch (missing) {
            case "LOGIN" -> SecurityContextHolder.clearContext();
            case "PERMISSION" -> when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH))
                    .thenReturn(CommonResult.success(false));
            case "PROVIDER" -> authenticate(9L, null);
            case "ASSET" -> when(assets.selectById(5L)).thenReturn(null);
            default -> throw new IllegalArgumentException(missing);
        }
        Stored before = stored();
        deny(() -> service.revokeApply(7L, NOTE), MARKET_APPLY_FORBIDDEN.getCode());
        assertUnchanged(before);
    }

    @ParameterizedTest
    @CsvSource(value = {"11,NULL", "12,NULL", "19,100"}, nullValues = "NULL")
    void originalProviderRolesCanRevokeWithoutActingAsApplicant(long user, Long department) throws Exception {
        authenticate(user, department);
        service.revokeApply(7L, NOTE);
        assertRevoked(7L, 3L, NOTE);
        assertThat(scalar("SELECT operator_user_id FROM dh_market_check_process WHERE action='REVOKE'")).isEqualTo(user);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUSPENDED", "PAUSED", "EXPIRED_TIME", "BAD_SNAPSHOT", "MISSING_SOURCE", "OVERFLOW"})
    void terminalRevokeKeepsLegacyAndSourceVersionBoundaries(String situation) throws Exception {
        long expectedRevision = 3L;
        switch (situation) {
            case "SUSPENDED" -> { service.authApply(7L, 0); expectedRevision = 4L; }
            case "PAUSED" -> sql("UPDATE dh_market_resource_apply SET paused=1 WHERE id=7");
            case "EXPIRED_TIME" -> sql("UPDATE dh_market_resource_apply SET apply_end_time='2000-01-01' WHERE id=7");
            case "BAD_SNAPSHOT" -> sql("UPDATE dh_market_resource_apply SET snapshot='{}'::jsonb WHERE id=7");
            case "MISSING_SOURCE" -> sql("DELETE FROM dh_asset_grant_source WHERE apply_id=7"); // 仅隔离库
            case "OVERFLOW" -> {
                sql("UPDATE dh_market_resource_apply SET source_revision=9223372036854775807 WHERE id=7");
                sql("UPDATE dh_asset_grant_source SET revision=9223372036854775807 WHERE apply_id=7");
            }
            default -> throw new IllegalArgumentException(situation);
        }
        clearInvocations(coordinator, notifications);
        Stored before = stored();
        if ("OVERFLOW".equals(situation)) {
            assertThatThrownBy(() -> service.revokeApply(7L, NOTE)).isInstanceOf(ArithmeticException.class);
            assertUnchanged(before);
        } else {
            service.revokeApply(7L, NOTE);
            assertRevoked(7L, expectedRevision, NOTE);
            assertThat(stored().evidence()).isEqualTo(before.evidence());
        }
    }

    @Test
    void portalOnlyRevocationDoesNotInventRemoteOperationOrApproval() throws Exception {
        sql("""
                INSERT INTO dh_market_resource_apply(id,asset_id,apply_user_id,status,if_auth,paused,revision,
                  source_revision,snapshot) SELECT 8,asset_id,apply_user_id,2,1,0,1,0,snapshot
                  FROM dh_market_resource_apply WHERE id=7
                """);
        Stored before = stored();
        service.revokeApply(8L, NOTE);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=8 AND status=6 AND if_auth=0"
                + " AND source_revision=2 AND delivery_status=0")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source WHERE apply_id=8 AND revision=2"
                + " AND source_status='REVOKED' AND revoked=1")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=8 AND action='REVOKE'"))
                .isEqualTo(1);
        assertThat(stored().operations()).isEqualTo(before.operations());
        assertThat(stored().evidence()).isEqualTo(before.evidence());
        verifyNoInteractions(coordinator);
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    @Test
    void postCommitTransportFailuresKeepDurableRevokePending() throws Exception {
        when(coordinator.claim( anyLong(), anyString())).thenThrow(new IllegalStateException("test offline"));
        when(notifications.sendSingleMessageToAdmin(any())).thenThrow(new IllegalStateException("test notify offline"));
        service.revokeApply(7L, NOTE);
        assertRevoked(7L, 3L, NOTE);
        verify(coordinator).claim( anyLong(), anyString());
        verify(notifications).sendSingleMessageToAdmin(any());
    }

    private void assertRevoked(long id, long revision, String note) throws Exception {
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=" + id
                + " AND status=6 AND if_auth=0 AND source_revision=" + revision + " AND delivery_status=1")).isEqualTo(1);
        assertThat(applications.selectById(id).getCheckNote()).isEqualTo(note);
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source WHERE apply_id=" + id
                + " AND source_key='asset:apply:" + id + "' AND revision=" + revision
                + " AND source_status='REVOKED' AND revoked=1")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE apply_id=" + id
                + " AND source_revision=" + revision + " AND status='PENDING' AND resolution_status='UNCONFIRMED'"
                + " AND payload_json->>'action'='REVOKE' AND operation_id='asset:asset:apply:" + id
                + ":r" + revision + ":REVOKE'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=" + id
                + " AND action='REVOKE' AND from_status=2 AND to_status=6")).isEqualTo(1);
        // 收回补普通审计事件：与状态变更同事务，前后状态与过程记录一致。
        verify(auditRecorder).record(eq("APPLY_REVOKE"), eq("APPLY"), eq(id), eq(2), eq(6),
                nullable(String.class), eq("OK"));
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT note FROM dh_market_check_process WHERE resource_apply_id=" + id
                     + " AND action='REVOKE'")) {
            assertThat(row.next()).isTrue();
            assertThat(row.getString(1)).isEqualTo(note);
        }
    }

    private Object outcome(String note) {
        TenantContextHolder.clear();
        authenticate(11L, null);
        try { service.revokeApply(7L, note); return "OK"; }
        catch (RuntimeException failure) { return failure; }
        finally { clearContexts(); }
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
        return row("SELECT (to_jsonb(t)-ARRAY['status','if_auth','check_note','source_revision','source_grant_id',"
                + "'delivery_status','updater','update_time'])::text FROM dh_market_resource_apply t WHERE id=7");
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
