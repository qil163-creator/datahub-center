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

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实授权/过程/来源/交付及正式批准凭证，真实Guard；资产、权限RPC和外部发送API为替身。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketApplySuspensionPostgresTest extends SharingPostgresSupport {
    private MarketApplyService service;
    private MarketResourceApplyMapper applications;
    private DataSourceTransactionManager transactions;
    private final PermissionApi permissions = mock(PermissionApi.class);
    private final MarketDeliveryCoordinator coordinator = mock(MarketDeliveryCoordinator.class);
    private final NotifyMessageSendApi notifications = mock(NotifyMessageSendApi.class);
    private final MarketAuditRecorder audit = mock(MarketAuditRecorder.class);
    private final DataAssetMapper assets = mock(DataAssetMapper.class);

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
        config.setEnvironment(new Environment("apply-suspension", new SpringManagedTransactionFactory(), source));
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
        ReflectionTestUtils.setField(target, "auditRecorder", audit);
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
        clearInvocations(permissions, assets, coordinator, notifications, audit);
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }


    @ParameterizedTest
    @CsvSource({"false,0", "false,1", "true,0", "true,1"})
    void skippedProcessCannotCommitPauseOrResume(boolean resume, int auth) throws Exception {
        initialize(resume, auth);
        Stored before = stored();
        sql("""
                CREATE FUNCTION skip_suspension_process() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                CREATE TRIGGER skip_suspension_process BEFORE INSERT ON dh_market_check_process
                FOR EACH ROW EXECUTE FUNCTION skip_suspension_process();
                """);
        deny(() -> mutate(resume, "test pause"), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(before);
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nullStateIsBusinessDenial(boolean resume) throws Exception {
        initialize(resume, 1);
        sql("UPDATE dh_market_resource_apply SET status=NULL WHERE id=7");
        Stored before = stored();
        deny(() -> mutate(resume, null), MARKET_APPLY_STATUS_DENY.getCode());
        assertUnchanged(before);
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @CsvSource({"false,0", "false,1", "true,0", "true,1"})
    void successfulChangePreservesTheOtherSwitchAndOriginalApproval(boolean resume, int auth) throws Exception {
        initialize(resume, auth);
        Stored before = stored();
        String content = unchangedContent();
        long nextRevision = applications.selectById(7L).getSourceRevision() + 1;
        long processes = scalar("SELECT count(*) FROM dh_market_check_process");
        long operations = scalar("SELECT count(*) FROM dh_market_delivery_operation");
        String action = resume ? "RESUME" : "PAUSE";
        String note = "暂停-'quote'";
        when(coordinator.claim( anyLong(), anyString())).thenAnswer(call -> {
            assertCurrent(auth, resume ? 0 : 1, nextRevision);
            assertThat(stored().evidence()).isEqualTo(before.evidence());
            try (var other = open(); var statement = other.createStatement()) {
                statement.executeQuery("SELECT id FROM dh_market_resource_apply WHERE id=7 FOR UPDATE NOWAIT").close();
            }
            return Optional.empty();
        });
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            mutate(resume, note);
            assertThat(applications.selectById(7L).getPaused()).isEqualTo(resume ? 0 : 1);
            assertThatCode(() -> assertThat(stored()).isEqualTo(before)).doesNotThrowAnyException();
            verifyNoInteractions(coordinator, notifications);
        });
        assertCurrent(auth, resume ? 0 : 1, nextRevision);
        assertThat(unchangedContent()).isEqualTo(content);
        assertThat(stored().evidence()).isEqualTo(before.evidence());
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(processes + 1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(operations + 1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=7"
                + " AND action='" + action + "' AND from_status=2 AND to_status=2 AND operator_user_id=11"))
                .isEqualTo(1);
        assertThat(row("SELECT note FROM dh_market_check_process WHERE action='" + action + "' ORDER BY id DESC LIMIT 1"))
                .isEqualTo(resume ? "恢复" : note);
        verify(audit).record(action, "APPLY", 7L, 2, 2, resume ? null : note, "OK");
        verify(coordinator).claim( anyLong(), anyString());
        verifyNoInteractions(notifications); // 暂停/恢复原来没有通知，本轮不新增。
        Stored committed = stored();
        clearInvocations(coordinator, audit);
        deny(() -> mutate(resume, note), MARKET_APPLY_STATUS_DENY.getCode()); // 原同目标请求拒绝，不改为幂等空操作。
        assertUnchanged(committed);
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @CsvSource({"false,CAS_ZERO", "true,CAS_ZERO", "false,PROCESS", "true,PROCESS", "false,SOURCE", "true,SOURCE",
            "false,OPERATION", "true,OPERATION", "false,COMMIT", "true,COMMIT"})
    void eachWriteAndCommitFailureRollsBackAllFiveTables(boolean resume, String stage) throws Exception {
        initialize(resume, 1);
        Stored before = stored();
        long revision = applications.selectById(7L).getSourceRevision();
        switch (stage) {
            case "CAS_ZERO" -> sql("""
                    CREATE FUNCTION skip_pause_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
                    CREATE TRIGGER skip_pause_update BEFORE UPDATE ON dh_market_resource_apply
                    FOR EACH ROW EXECUTE FUNCTION skip_pause_update();
                    """);
            case "PROCESS" -> sql("ALTER TABLE dh_market_check_process ADD CHECK(action NOT IN ('PAUSE','RESUME')) NOT VALID");
            case "SOURCE" -> sql("ALTER TABLE dh_asset_grant_source ADD CHECK(revision<=" + revision + ")");
            case "OPERATION" -> sql("ALTER TABLE dh_market_delivery_operation ADD CHECK(source_revision<=" + revision + ")");
            case "COMMIT" -> sql("""
                    CREATE FUNCTION reject_pause_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'test suspension commit failure'; END $$;
                    CREATE CONSTRAINT TRIGGER reject_pause_commit AFTER UPDATE ON dh_market_resource_apply
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_pause_commit();
                    """);
            default -> throw new IllegalArgumentException(stage);
        }
        if (stage.equals("CAS_ZERO")) deny(() -> mutate(resume, null), MARKET_APPLY_STATUS_DENY.getCode());
        else assertThatThrownBy(() -> mutate(resume, null)).isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(ServiceException.class);
        assertUnchanged(before);
        if (!stage.equals("COMMIT")) verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerRollbackDoesNotPublishTheChange(boolean resume) throws Exception {
        initialize(resume, 1);
        Stored before = stored();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            mutate(resume, null);
            assertThat(applications.selectById(7L).getPaused()).isEqualTo(resume ? 0 : 1);
            tx.setRollbackOnly();
        });
        assertUnchanged(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentSameTargetOnlyCommitsOneNewVersion(boolean resume) throws Exception {
        initialize(resume, 1);
        long revision = applications.selectById(7L).getSourceRevision() + 1;
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
            var first = workers.submit(() -> outcome(resume));
            var second = workers.submit(() -> outcome(resume));
            try { assertThat(bothRead.await(8, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            var outcomes = List.of(first.get(12, TimeUnit.SECONDS), second.get(12, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("OK"::equals).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(ServiceException.class::isInstance).map(ServiceException.class::cast)
                    .map(ServiceException::getCode)).containsExactly(MARKET_APPLY_STATUS_DENY.getCode());
        }
        assertCurrent(1, resume ? 0 : 1, revision);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(processes + 1);
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isEqualTo(operations + 1);
        verify(coordinator).claim( anyLong(), anyString());
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiredApprovalMayBePausedButCannotBeResumed(boolean resume) throws Exception {
        initialize(resume, 1);
        sql("UPDATE dh_market_resource_apply SET apply_end_time='2000-01-01' WHERE id=7");
        Stored before = stored();
        if (resume) {
            deny(() -> mutate(true, null), MARKET_APPLY_STATUS_DENY.getCode());
            assertUnchanged(before);
            verifyNoInteractions(audit);
        } else {
            mutate(false, null);
            assertCurrent(1, 1, 3L);
            assertThat(stored().evidence()).isEqualTo(before.evidence());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidFrozenSnapshotDoesNotPreventStoppingButPreventsRestoring(boolean resume) throws Exception {
        initialize(resume, 1);
        sql("UPDATE dh_market_resource_apply SET snapshot='{}'::jsonb WHERE id=7");
        Stored before = stored();
        if (resume) {
            deny(() -> mutate(true, null), MARKET_APPLY_SCOPE_UNCONFIRMED.getCode());
            assertUnchanged(before);
            verifyNoInteractions(audit);
        } else {
            mutate(false, null);
            assertCurrent(1, 1, 3L);
            assertThat(stored().evidence()).isEqualTo(before.evidence());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,LOGIN", "true,LOGIN", "false,PERMISSION", "true,PERMISSION", "false,PROVIDER", "true,PROVIDER"})
    void realGuardStillRequiresAnAuthorizedProvider(boolean resume, String missing) throws Exception {
        initialize(resume, 1);
        if (missing.equals("LOGIN")) SecurityContextHolder.clearContext();
        if (missing.equals("PERMISSION")) when(permissions.hasAnyPermissions(11L, MarketObjectGuard.PERM_APPLY_AUTH))
                .thenReturn(CommonResult.success(false));
        if (missing.equals("PROVIDER")) authenticate(9L, null);
        Stored before = stored();
        deny(() -> mutate(resume, null), MARKET_APPLY_FORBIDDEN.getCode());
        assertUnchanged(before);
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @CsvSource({"false,12,0", "true,12,0", "false,13,100", "true,13,100"})
    void existingBusinessLeaderAndDepartmentProviderRolesRemainAllowed(boolean resume, long actor, long dept) throws Exception {
        initialize(resume, 1);
        long revision = applications.selectById(7L).getSourceRevision() + 1;
        authenticate(actor, dept == 0 ? null : dept);
        mutate(resume, null);
        assertCurrent(1, resume ? 0 : 1, revision);
        assertThat(scalar("SELECT operator_user_id FROM dh_market_check_process ORDER BY id DESC LIMIT 1")).isEqualTo(actor);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void versionOverflowRollsBackPauseAndProcess(boolean resume) throws Exception {
        initialize(resume, 1);
        sql("UPDATE dh_market_resource_apply SET source_revision=9223372036854775807 WHERE id=7");
        sql("UPDATE dh_asset_grant_source SET revision=9223372036854775807 WHERE apply_id=7");
        Stored before = stored();
        assertThatThrownBy(() -> mutate(resume, null)).isInstanceOf(ArithmeticException.class);
        assertUnchanged(before);
        verifyNoInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void postCommitClaimFailureKeepsDurablePendingIntent(boolean resume) throws Exception {
        initialize(resume, 1);
        long revision = applications.selectById(7L).getSourceRevision() + 1;
        when(coordinator.claim( anyLong(), anyString())).thenThrow(new IllegalStateException("test offline"));
        mutate(resume, null);
        assertCurrent(1, resume ? 0 : 1, revision);
        verifyNoInteractions(notifications);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noApiApplicationChangesOnlyLocalSourceWithoutInventingApproval(boolean resume) throws Exception {
        sql("""
                INSERT INTO dh_market_resource_apply(id,asset_id,apply_user_id,status,if_auth,paused,revision,
                  source_revision,snapshot) SELECT 8,asset_id,apply_user_id,2,1,%d,1,0,snapshot
                  FROM dh_market_resource_apply WHERE id=7
                """.formatted(resume ? 1 : 0));
        Stored before = stored();
        if (resume) service.resumeApply(8L); else service.pauseApply(8L, null);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=8 AND paused=" + (resume ? 0 : 1)
                + " AND if_auth=1 AND source_revision=2 AND delivery_status=0")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source WHERE apply_id=8 AND revision=2 AND source_status='"
                + (resume ? "ACTIVE" : "SUSPENDED") + "'")).isEqualTo(1);
        assertThat(stored().operations()).isEqualTo(before.operations());
        assertThat(stored().evidence()).isEqualTo(before.evidence());
        verifyNoInteractions(coordinator, notifications);
    }

    private Object outcome(boolean resume) {
        TenantContextHolder.clear();
        authenticate(11L, null);
        try { mutate(resume, "concurrent"); return "OK"; }
        catch (RuntimeException failure) { return failure; }
        finally { clearContexts(); }
    }

    private void initialize(boolean resume, int auth) {
        if (auth == 0) service.authApply(7L, 0);
        if (resume) service.pauseApply(7L, "initial pause");
        clearInvocations(permissions, assets, coordinator, notifications, audit);
    }

    private void mutate(boolean resume, String note) {
        if (resume) service.resumeApply(7L);
        else service.pauseApply(7L, note);
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
        return row("SELECT (to_jsonb(t)-ARRAY['paused','source_revision','source_grant_id','delivery_status','updater','update_time'])::text"
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
