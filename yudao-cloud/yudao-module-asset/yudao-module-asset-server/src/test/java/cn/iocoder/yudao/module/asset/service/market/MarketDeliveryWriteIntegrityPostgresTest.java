package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDeliveryOperationDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.AssetGrantSourceMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.*;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.MARKET_APPLY_STATUS_DENY;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** 五张真实表、真实租户插件/业务代理事务；外部RPC、普通审计及协调器领取为替身。 */
@Timeout(30)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketDeliveryWriteIntegrityPostgresTest extends SharingPostgresSupport {
    private MarketApplyService service;
    private MarketDeliveryService delivery;
    private MarketResourceApplyMapper applications;
    private MarketDeliveryOperationMapper operations;
    private DataSourceTransactionManager transactions;
    private final MarketDeliveryCoordinator coordinator = mock(MarketDeliveryCoordinator.class);
    private final NotifyMessageSendApi notifications = mock(NotifyMessageSendApi.class);
    private final MarketAuditRecorder audit = mock(MarketAuditRecorder.class);

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
        config.setEnvironment(new Environment("delivery-integrity", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        config.addInterceptor(interceptor);
        config.addMapper(MarketResourceApplyMapper.class);
        config.addMapper(MarketCheckProcessMapper.class);
        config.addMapper(AssetGrantSourceMapper.class);
        config.addMapper(MarketDeliveryOperationMapper.class);
        config.addMapper(MarketApprovalEvidenceMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        applications = session.getMapper(MarketResourceApplyMapper.class);
        operations = session.getMapper(MarketDeliveryOperationMapper.class);
        delivery = new MarketDeliveryService();
        ReflectionTestUtils.setField(delivery, "applyMapper", applications);
        ReflectionTestUtils.setField(delivery, "grantSourceMapper", session.getMapper(AssetGrantSourceMapper.class));
        ReflectionTestUtils.setField(delivery, "operationMapper", operations);
        ReflectionTestUtils.setField(delivery, "approvalEvidenceMapper", session.getMapper(MarketApprovalEvidenceMapper.class));
        ReflectionTestUtils.setField(delivery, "coordinator", coordinator);
        when(coordinator.claim( anyLong(), anyString())).thenReturn(Optional.empty());
        var assets = mock(DataAssetMapper.class);
        when(assets.selectById(5L)).thenReturn(DataAssetDO.builder().id(5L)
                .status(DataAssetDO.STATUS_PUBLISHED).assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .publishedVersion("v1").publishedSnapshotId(11L).publishedCatalogVersionId(12L)
                .datasourceId(4L).schemaName("public").tableName("citizen").techLeaderUserId(11L).build());
        // 锁内复验与入口读取同一替身资产；需要真实并发语义的场景见 MarketProviderDecisionSerializationPostgresTest。
        when(assets.selectForMatching( anyLong())).thenAnswer(call -> assets.selectById(call.<Long>getArgument(0)));
        var permissions = mock(PermissionApi.class);
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
        var login = new LoginUser();
        login.setId(11L);
        login.setTenantId(1L);
        login.setInfo(Map.of());
        SecurityFrameworkUtils.setLoginUser(login, new MockHttpServletRequest());
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    static Stream<Arguments> routes() {
        return Stream.of("APPROVE", "SUSPEND", "RESUME", "REVOKE")
                .flatMap(route -> Stream.of(false, true).map(local -> Arguments.of(route, local)));
    }

    static Stream<Arguments> failures() {
        return Stream.of("APPROVE", "SUSPEND", "RESUME", "REVOKE")
                .flatMap(route -> Stream.of(false, true).flatMap(local ->
                        (local ? Stream.of("SOURCE", "APPLICATION") : Stream.of("SOURCE", "OPERATION", "APPLICATION"))
                                .map(stage -> Arguments.of(route, local, stage))));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void zeroRowWriteRollsBackCallerAndAllowsExplicitRetry(String route, boolean local, String stage) throws Exception {
        initialize(route, local);
        Stored before = stored();
        long revision = nextRevision();
        String table = switch (stage) {
            case "SOURCE" -> "dh_asset_grant_source";
            case "OPERATION" -> "dh_market_delivery_operation";
            case "APPLICATION" -> "dh_market_resource_apply";
            default -> throw new IllegalArgumentException(stage);
        };
        // 只阻断交付回填，不影响业务入口前面的状态CAS，以证明后续失败能回滚前序写入。
        String event = stage.equals("APPLICATION")
                ? "UPDATE ON " + table + " FOR EACH ROW WHEN (NEW.source_revision IS DISTINCT FROM OLD.source_revision)"
                : "INSERT OR UPDATE ON " + table + " FOR EACH ROW";
        sql("CREATE FUNCTION skip_delivery_write() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;"
                + "CREATE TRIGGER skip_delivery_write BEFORE " + event + " EXECUTE FUNCTION skip_delivery_write()");
        deny(() -> mutate(route));
        assertThat(stored()).isEqualTo(before);
        // 零行写入整体回滚；普通审计同事务回滚，不在此断言替身零交互。
        verifyNoInteractions(coordinator, notifications);
        sql("DROP TRIGGER skip_delivery_write ON " + table + "; DROP FUNCTION skip_delivery_write()");
        mutate(route); // 确定回滚后由调用者显式重试，不是组件内部隐式重投未知结果。
        assertCommitted(route, local, revision, before);
    }

    @ParameterizedTest
    @MethodSource("routes")
    void completeIntentIsVisibleOnlyAfterCommit(String route, boolean local) throws Exception {
        initialize(route, local);
        Stored before = stored();
        long revision = nextRevision();
        when(coordinator.claim( anyLong(), anyString())).thenAnswer(call -> {
            assertCommitted(route, false, revision, before);
            assertThat(operations.selectById((Long) call.getArgument(0)).getSourceRevision()).isEqualTo(revision);
            try (var other = open(); var statement = other.createStatement()) {
                statement.executeQuery("SELECT id FROM dh_market_resource_apply WHERE id=7 FOR UPDATE NOWAIT").close();
            }
            return Optional.empty();
        });
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            mutate(route);
            assertThatCode(() -> assertThat(stored()).isEqualTo(before)).doesNotThrowAnyException();
            verifyNoInteractions(coordinator, notifications);
        });
        assertCommitted(route, local, revision, before);
        if (local) verifyNoInteractions(coordinator);
        else verify(coordinator).claim( anyLong(), anyString());
    }

    @ParameterizedTest
    @MethodSource("routes")
    void outerRollbackDiscardsWholeIntent(String route, boolean local) throws Exception {
        initialize(route, local);
        Stored before = stored();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            mutate(route);
            tx.setRollbackOnly();
        });
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(coordinator, notifications);
        // 普通审计是尽力记录替身；不把本例表内回滚外推为其强持久性证明。
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidOperationIdentityAfterRealInsertRollsBackApproval(Long invalidId) throws Exception {
        Stored before = stored();
        var faulty = mock(MarketDeliveryOperationMapper.class, delegatesTo(operations));
        doAnswer(call -> {
            MarketDeliveryOperationDO operation = call.getArgument(0);
            int count = operations.insertFrozen( operation);
            operation.setId(invalidId); // 真实INSERT后模拟主键映射/回填故障，数据库中仍有实际行。
            return count;
        }).when(faulty).insertFrozen( any());
        ReflectionTestUtils.setField(delivery, "operationMapper", faulty);
        deny(() -> mutate("APPROVE"));
        assertThat(stored()).isEqualTo(before);
        // 交付写入失败整体回滚；普通审计同事务回滚，不在此断言替身零交互。
        verifyNoInteractions(coordinator, notifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOURCE", "OPERATION", "APPLICATION", "COMMIT"})
    void constraintFailureCannotCommitPartialApproval(String stage) throws Exception {
        Stored before = stored();
        switch (stage) {
            case "SOURCE" -> sql("ALTER TABLE dh_asset_grant_source ADD CHECK(revision<2)");
            case "OPERATION" -> sql("ALTER TABLE dh_market_delivery_operation ADD CHECK(source_revision<2)");
            case "APPLICATION" -> sql("ALTER TABLE dh_market_resource_apply ADD CHECK(source_revision<2)");
            case "COMMIT" -> sql("""
                    CREATE FUNCTION fail_delivery_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'test delivery commit failure'; END $$;
                    CREATE CONSTRAINT TRIGGER fail_delivery_commit AFTER UPDATE ON dh_market_resource_apply
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_delivery_commit();
                    """);
            default -> throw new IllegalArgumentException(stage);
        }
        assertThatThrownBy(() -> mutate("APPROVE")).isInstanceOf(RuntimeException.class);
        assertThat(stored()).isEqualTo(before);
        verifyNoInteractions(coordinator, notifications);
        if (!stage.equals("COMMIT")) verifyNoInteractions(audit);
    }

    private void initialize(String route, boolean local) throws Exception {
        if (local) sql("UPDATE dh_market_resource_apply SET app_id=NULL,api_id=NULL WHERE id=7");
        if (!route.equals("APPROVE")) mutate("APPROVE");
        if (route.equals("RESUME")) mutate("SUSPEND");
        clearInvocations(coordinator, notifications, audit);
    }

    private void mutate(String route) {
        switch (route) {
            case "APPROVE" -> {
                var request = new MarketCheckReqVO();
                request.setId(7L);
                request.setStatus(2);
                request.setCheckNote("original approval");
                service.checkApply(request);
            }
            case "SUSPEND" -> service.pauseApply(7L, "test pause");
            case "RESUME" -> service.resumeApply(7L);
            case "REVOKE" -> service.revokeApply(7L, "test revoke");
            default -> throw new IllegalArgumentException(route);
        }
    }

    private long nextRevision() { return Math.max(1, applications.selectById(7L).getSourceRevision()) + 1; }

    private void assertCommitted(String route, boolean local, long revision, Stored before) throws Exception {
        String action = route.equals("APPROVE") || route.equals("RESUME") ? "GRANT" : route;
        String sourceStatus = switch (action) { case "GRANT" -> "ACTIVE"; case "SUSPEND" -> "SUSPENDED"; default -> "REVOKED"; };
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=7 AND source_revision="
                + revision + " AND source_grant_id='apply:7:r1' AND delivery_status=" + (local ? 0 : 1))).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source WHERE apply_id=7 AND revision="
                + revision + " AND source_key='asset:apply:7' AND source_status='" + sourceStatus + "'"))
                .isEqualTo(1);
        if (local) {
            assertThat(stored().operations()).isEqualTo(before.operations());
            assertThat(stored().evidence()).isEqualTo(before.evidence());
        } else {
            assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation WHERE apply_id=7"
                    + " AND source_revision=" + revision + " AND status='PENDING' AND resolution_status='UNCONFIRMED'"
                    + " AND operation_id='asset:asset:apply:7:r" + revision + ":" + action + "'"
                    + " AND payload_json->>'action'='" + action + "' AND payload_hash IS NOT NULL")).isEqualTo(1);
            assertThat(scalar("SELECT count(*) FROM dh_market_audit_event WHERE event_type='GRANT_APPROVAL_EVIDENCE'"))
                    .isEqualTo(1);
            if (!route.equals("APPROVE")) assertThat(stored().evidence()).isEqualTo(before.evidence());
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=7"))
                .isEqualTo(route.equals("APPROVE") ? 1 : route.equals("RESUME") ? 3 : 2);
    }

    private record Stored(String application, String processes, String sources, String operations, String evidence) { }

    private Stored stored() throws Exception {
        return new Stored(table("dh_market_resource_apply"), table("dh_market_check_process"),
                table("dh_asset_grant_source"), table("dh_market_delivery_operation"), table("dh_market_audit_event"));
    }

    private String table(String table) throws Exception {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY id),'[]'::jsonb)::text FROM " + table + " t")) {
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

    private static void deny(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(MARKET_APPLY_STATUS_DENY.getCode()));
    }
}
