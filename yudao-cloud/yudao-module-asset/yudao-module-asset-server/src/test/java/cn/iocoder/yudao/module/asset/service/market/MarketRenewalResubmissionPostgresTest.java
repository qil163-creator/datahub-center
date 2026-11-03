package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.handler.DefaultDBFieldHandler;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplySaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceEndpointDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceEndpointMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.supply.api.app.SupplyAppApi;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyAppRespDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiBindingRespDTO;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.incrementer.PostgreKeyGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 续期补正：真实PG JSONB、单租户Mapper、Spring代理事务及失败回滚。
 * 每例沿用SharingPostgresSupport创建/清理独立数据库，仅接受显式本机测试URL和sharing_test角色。
 * 申请/过程Mapper与数据库是真实实现；DataAsset、发布元数据、API/应用准入、日历、审计、通知和交付是替身。
 * 审计替身的调用不代表持久审计已提交；交付替身不证明Supply来源聚合、远端授权或跨服务一致性。
 * 回滚断言覆盖持久业务行，不要求PostgreSQL序列值回滚，也不验证历史迁移重放。
 */
@Timeout(30)
@Execution(ExecutionMode.SAME_THREAD)
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketRenewalResubmissionPostgresTest extends SharingPostgresSupport {

    private static final long USER = 9L;
    private static final long PARENT = 7L;
    private static final long CHILD = 99L;
    private static final long OTHER_CHILD = 199L;
    private static final long ASSET = 5L;
    private static final long API = 2L;
    private static final long APP = 3L;
    private static final long PUBLISHED_SNAPSHOT = 11L;
    private static final long CATALOG_VERSION = 12L;
    private static final LocalDateTime OLD_RECEIVED = LocalDateTime.of(2020, 1, 1, 0, 0);
    private static final List<String> FROZEN_COLUMNS = List.of("Id", "physical,column", "Case Sensitive");
    private static final String PURPOSE = "统计分析 ' ); SELECT 1; --\n原用途";
    private static final String DUTY = "履职义务\n\"不得转交\"";
    private static final String BASIS = "测试法定依据";

    private MarketApplyService service;
    private MarketResourceApplyMapper applications;
    private DataSourceTransactionManager transactions;
    private DataAssetDO asset;
    private final DataAssetMapper assets = mock(DataAssetMapper.class);
    private final AssetCatalogService catalog = mock(AssetCatalogService.class);
    private final MarketObjectGuard guard = mock(MarketObjectGuard.class);
    private final MarketSupplyApiGuard apiGuard = mock(MarketSupplyApiGuard.class);
    private final MarketResourceEndpointMapper endpoints = mock(MarketResourceEndpointMapper.class);
    private final SupplyAppApi apps = mock(SupplyAppApi.class);
    private final MarketAuditRecorder audit = mock(MarketAuditRecorder.class);
    private final MarketNotifyHelper notifications = mock(MarketNotifyHelper.class);
    private final MarketDeliveryService delivery = mock(MarketDeliveryService.class);
    private final AssetMonitorService monitor = mock(AssetMonitorService.class);

    @BeforeEach
    void prepareRealMapperAndTransactionalProxy() throws Exception {
        try (var input = getClass().getResourceAsStream("/sql/market-freeze-fixture.sql")) {
            if (input == null) throw new IllegalStateException("Missing market fixture");
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        // 仅复用现有迁移建立测试前置状态，不交付新SQL，不宣称本类证明迁移完整性。
        migrate("0042_asset_grant-source-delivery.sql");
        migrate("0047_asset_renewal-request-evidence.sql");
        migrate("0050_asset_market-active-application-uniqueness.sql");
        sql("""
                ALTER TABLE dh_market_check_process ADD tenant_id bigint NOT NULL DEFAULT 1;
                ALTER TABLE dh_market_resource_apply ALTER COLUMN snapshot TYPE jsonb USING snapshot::jsonb;
                """);
        seedApplication(PARENT, false);
        seedApplication(CHILD, true);
        seedApplication(OTHER_CHILD, true);

        var source = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center&socketTimeout=10", "sharing_test", "");
        transactions = new DataSourceTransactionManager(source);
        var config = singleTenantConfiguration();
        var global = new GlobalConfig().setMetaObjectHandler(new DefaultDBFieldHandler());
        global.setDbConfig(new GlobalConfig.DbConfig().setIdType(IdType.INPUT)
                .setKeyGenerators(List.of(new PostgreKeyGenerator())));
        GlobalConfigUtils.setGlobalConfig(config, global);
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("renewal-resubmission", new SpringManagedTransactionFactory(), source));
        var interceptor = new MybatisPlusInterceptor();

        config.addInterceptor(interceptor);
        config.addMapper(MarketResourceApplyMapper.class);
        config.addMapper(MarketCheckProcessMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        applications = session.getMapper(MarketResourceApplyMapper.class);

        asset = publishedAsset();
        when(assets.selectById(ASSET)).thenReturn(asset);
        when(assets.selectForMatching(ASSET)).thenReturn(asset);
        when(guard.requireUserId()).thenReturn(USER);
        when(endpoints.selectByAssetAndApi(ASSET, API)).thenReturn(new MarketResourceEndpointDO());
        var app = new SupplyAppRespDTO();
        app.setId(APP);
        when(apps.listAppsByCreator(USER)).thenReturn(CommonResult.success(List.of(app)));
        when(apiGuard.requireQueryableTableApi(API)).thenReturn(publishedApi());
        when(catalog.listColumns(ASSET, PUBLISHED_SNAPSHOT)).thenAnswer(call -> {
            // 不以@Transactional注解存在替代运行证据；读取发生在同一真实数据源的代理事务内。
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.hasResource(source)).isTrue();
            assertThat(TenantContextHolder.getTenantId()).isNull();
            return availableColumns();
        });
        var days = mock(MarketWorkingDayService.class);
        when(days.plusWorkingDays(any(LocalDateTime.class), anyInt())).thenAnswer(call ->
                ((LocalDateTime) call.getArgument(0)).plusDays((Integer) call.getArgument(1)));

        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applications);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        ReflectionTestUtils.setField(target, "transactionManager", transactions);
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        org.mockito.Mockito.when(catalog.requireCompletePublishedAsset(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(target, "catalogService", catalog);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        ReflectionTestUtils.setField(target, "supplyApiGuard", apiGuard);
        ReflectionTestUtils.setField(target, "endpointMapper", endpoints);
        ReflectionTestUtils.setField(target, "supplyAppApi", apps);
        ReflectionTestUtils.setField(target, "workingDayService", days);
        ReflectionTestUtils.setField(target, "auditRecorder", audit);
        ReflectionTestUtils.setField(target, "notifyHelper", notifications);
        ReflectionTestUtils.setField(target, "deliveryService", delivery);
        ReflectionTestUtils.setField(target, "assetMonitorService", monitor);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (MarketApplyService) proxy.getProxy();
        assertThat(AopUtils.isAopProxy(service)).isTrue();
        login();
    }

    @AfterEach
    void clearCallerContext() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void resubmissionPersistsNextJsonbRevisionWithoutAddingAvailablePublicationColumns() throws Exception {
        String parentBefore = storedApplication(PARENT);
        String otherBefore = storedApplication(OTHER_CHILD);
        Map<String, Object> expected = frozenSnapshot();
        expected.put("revision", "3");

        service.resubmitApply(CHILD, 2);

        assertResubmitted(3, expected);
        assertThat(storedApplication(PARENT)).isEqualTo(parentBefore);
        assertThat(storedApplication(OTHER_CHILD)).isEqualTo(otherBefore);
        verify(catalog).listColumns(ASSET, PUBLISHED_SNAPSHOT);
        verify(audit).record("RESUBMIT", "APPLY", CHILD, 8, 1, null, "OK");
        assertNoApprovalOrAuthorizationSideEffects();
        deny(() -> service.resubmitApply(CHILD, 3), MARKET_APPLY_STATUS_DENY.getCode());
        assertThat(childProcessCount("RESUBMIT")).isEqualTo(1);
        verifyNoMoreInteractions(audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"assetId", "apiId", "appId", "purpose", "dutyMatter", "legalBasis",
            "fieldScope", "applyUseType", "applyUseTimes"})
    void supplementCannotModifyInheritedPurposeObligationsScopeOrBinding(String field) throws Exception {
        String before = graph();
        MarketApplySaveReqVO request = BeanUtils.toBean(applications.selectById(CHILD), MarketApplySaveReqVO.class);
        request.setExpectedRevision(2);
        switch (field) {
            case "assetId" -> request.setAssetId(6L);
            case "apiId" -> request.setApiId(22L);
            case "appId" -> request.setAppId(33L);
            case "purpose" -> request.setPurpose("不同用途");
            case "dutyMatter" -> request.setDutyMatter("");
            case "legalBasis" -> request.setLegalBasis("不同依据");
            case "fieldScope" -> request.setFieldScope("Id,NewColumn");
            case "applyUseType" -> request.setApplyUseType(MarketResourceApplyDO.USE_LIMITED);
            default -> request.setApplyUseTimes(100);
        }

        deny(() -> service.updateApply(request), MARKET_APPLY_RENEWAL_CONFLICT.getCode());

        assertThat(graph()).isEqualTo(before);
        verifyNoInteractions(assets, catalog, apiGuard, endpoints, apps, audit);
        assertNoApprovalOrAuthorizationSideEffects();
    }

    @Test
    void allowedSupplementEditKeepsInheritedValuesAndResubmitsOnlyTheOldFrozenScope() throws Exception {
        String frozenBefore = storedSnapshot(CHILD);
        MarketApplySaveReqVO request = new MarketApplySaveReqVO();
        request.setId(CHILD);
        request.setAssetId(ASSET);
        request.setTitle("补充材料");
        request.setApplyNote("仅补充附件说明");
        request.setExpectedRevision(2);
        // API、应用、用途、义务和范围被省略，不允许利用可空字段移除继承绑定。
        service.updateApply(request);

        MarketResourceApplyDO edited = applications.selectById(CHILD);
        assertThat(edited.getRevision()).isEqualTo(3);
        assertInheritedContent(edited);
        assertThat(edited.getApplyNote()).isEqualTo("仅补充附件说明");
        assertThat(storedSnapshot(CHILD)).isEqualTo(frozenBefore);
        assertThat(childProcessCount("RESUBMIT")).isZero();

        service.resubmitApply(CHILD, 3);

        var expected = frozenSnapshot();
        expected.put("revision", "4");
        assertResubmitted(4, expected);
        assertThat(applications.selectById(CHILD).getApplyNote()).isEqualTo("仅补充附件说明");
        assertNoApprovalOrAuthorizationSideEffects();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSE", "REVOKE", "EXPIRE"})
    void parentLifecycleChangeDoesNotCascadeToSupplementOrPreventIndependentResubmission(String action) throws Exception {
        String childBefore = storedApplication(CHILD);

        stopParent(action);

        assertThat(storedApplication(CHILD)).isEqualTo(childBefore);
        String stoppedParent = storedApplication(PARENT);
        clearInvocations(audit, delivery, notifications);

        service.resubmitApply(CHILD, 2);

        var expected = frozenSnapshot();
        expected.put("revision", "3");
        assertResubmitted(3, expected);
        assertThat(storedApplication(PARENT)).isEqualTo(stoppedParent);
        assertNoApprovalOrAuthorizationSideEffects();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSE", "REVOKE", "EXPIRE"})
    void independentlyApprovedRenewalIsNotRevokedWhenParentLaterStops(String action) throws Exception {
        service.resubmitApply(CHILD, 2);
        MarketCheckReqVO request = new MarketCheckReqVO();
        request.setId(CHILD);
        request.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        request.setExpectedRevision(3);
        request.setCheckNote("续期独立审批");
        service.checkApply(request);
        verify(delivery).enqueueApprovedGrant(argThat(apply -> apply != null && apply.getId().equals(CHILD)),
                eq(USER), eq(0L), eq("续期独立审批")); // Mockito 的 Long 缺省值为 0，不代表真实部门授权。
        assertThat(applications.selectById(CHILD).getIfAuth()).isEqualTo(MarketResourceApplyDO.AUTH_YES);
        assertThat(childProcessCount("APPROVE")).isEqualTo(1);
        String approvedChild = storedApplication(CHILD);
        clearInvocations(audit, delivery, notifications, monitor);

        stopParent(action);

        assertThat(storedApplication(CHILD)).isEqualTo(approvedChild);
        assertThat(childProcessCount("APPROVE")).isEqualTo(1);
        assertThat(childProcessCount("REVOKE")).isZero();
        assertThat(childProcessCount("PAUSE")).isZero();
        assertThat(childProcessCount("EXPIRE")).isZero();
        var identities = new MarketDeliveryService();
        assertThat(identities.sourceKey(applications.selectById(PARENT))).isEqualTo("asset:apply:7");
        assertThat(identities.sourceKey(applications.selectById(CHILD))).isEqualTo("asset:apply:99");
        // 本例批准/交付替身不写真实来源，断言只证明独立申请身份和本地不级联。
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isZero();
    }



    @ParameterizedTest
    @ValueSource(strings = {"NEW_PUBLICATION", "MISSING_FROZEN_COLUMN"})
    void changedPublicationOrMissingPhysicalColumnCannotBeRefrozenToHideInvalidEvidence(String change) throws Exception {
        String before = graph();
        if ("NEW_PUBLICATION".equals(change)) {
            asset.setPublishedVersion("v2");
            asset.setPublishedSnapshotId(41L);
            asset.setPublishedCatalogVersionId(42L);
        } else {
            // 避免重设替身时提前调用其“必须处于真实事务”的 Answer。
            doReturn(List.of(column("NewColumn"))).when(catalog).listColumns(ASSET, PUBLISHED_SNAPSHOT);
        }

        deny(() -> service.resubmitApply(CHILD, 2), MARKET_APPLY_SCOPE_UNCONFIRMED.getCode());

        assertThat(graph()).isEqualTo(before);
        verifyNoInteractions(audit, apiGuard, endpoints, apps);
        if ("NEW_PUBLICATION".equals(change)) verify(catalog, never()).listColumns(anyLong(), anyLong());
        assertNoApprovalOrAuthorizationSideEffects();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAS_ZERO", "PROCESS_ZERO", "PROCESS_CONSTRAINT", "AUDIT_FAILURE", "COMMIT_FAILURE"})
    void failuresRollbackRenewalJsonbRevisionSlaAndProcessWithoutApprovalOrAuthorization(String failure) throws Exception {
        installFailure(failure);
        String before = graph();

        if ("CAS_ZERO".equals(failure) || "PROCESS_ZERO".equals(failure)) {
            deny(() -> service.resubmitApply(CHILD, 2), MARKET_APPLY_STATUS_DENY.getCode());
        } else {
            assertThatThrownBy(() -> service.resubmitApply(CHILD, 2)).isInstanceOf(RuntimeException.class)
                    .isNotInstanceOf(ServiceException.class);
        }

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(graph()).isEqualTo(before);
        assertThat(childProcessCount("RESUBMIT")).isZero();
        assertNoApprovalOrAuthorizationSideEffects();
        if (!"AUDIT_FAILURE".equals(failure) && !"COMMIT_FAILURE".equals(failure)) verifyNoInteractions(audit);
    }

    @Test
    void outerTransactionRollbackRemovesNewSnapshotRevisionAndResubmissionProcess() throws Exception {
        String before = graph();

        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            service.resubmitApply(CHILD, 2);
            assertThat(applications.selectById(CHILD).getRevision()).isEqualTo(3);
            assertThat(applications.selectById(CHILD).getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_PENDING);
            tx.setRollbackOnly();
        });

        assertThat(graph()).isEqualTo(before);
        assertThat(childProcessCount("RESUBMIT")).isZero();
        assertNoApprovalOrAuthorizationSideEffects();
    }

    @Test
    void staleSeenRevisionIsRejectedBeforeMetadataReadOrDatabaseMutation() throws Exception {
        String before = graph();

        deny(() -> service.resubmitApply(CHILD, 1), MARKET_APPLY_REVISION_CONFLICT.getCode());

        assertThat(graph()).isEqualTo(before);
        verifyNoInteractions(assets, catalog, apiGuard, endpoints, apps, audit);
        assertNoApprovalOrAuthorizationSideEffects();
    }

    private void assertResubmitted(int revision, Map<String, Object> expectedSnapshot) throws Exception {
        MarketResourceApplyDO current = applications.selectById(CHILD);
        assertThat(current.getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_PENDING);
        assertThat(current.getRevision()).isEqualTo(revision);
        assertInheritedContent(current);
        assertThat(current.getCatalogVersionId()).isEqualTo(CATALOG_VERSION);
        assertThat(current.getReceivedTime()).isAfter(OLD_RECEIVED);
        // 日历是明确的自然日替身，验证SLA被重算/持久化，不证明真实工作日算法。
        assertThat(current.getReplyDeadline()).isEqualTo(current.getReceivedTime().plusDays(1));
        assertThat(current.getDeliveryDeadline()).isEqualTo(current.getReceivedTime().plusDays(21));
        assertThat(current.getIfAuth()).isEqualTo(MarketResourceApplyDO.AUTH_NO);
        assertThat(current.getCheckUserId()).isNull();
        assertThat(current.getCheckTime()).isNull();
        assertThat(current.getCheckNote()).isNull();
        assertThat(current.getSourceRevision()).isZero();
        assertThat(current.getSourceGrantId()).isNull();
        assertThat(current.getDeliveryStatus()).isEqualTo(MarketDeliveryService.DELIVERY_NONE);
        assertThat(snapshot(CHILD)).isEqualTo(expectedSnapshot);
        assertThat(snapshot(CHILD).get("frozenColumns")).isEqualTo(FROZEN_COLUMNS);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply WHERE id=99"
                + " AND jsonb_typeof(snapshot)='object'"
                + " AND NOT (snapshot->'frozenColumns' @> '[\"NewColumn\"]'::jsonb)")).isEqualTo(1);
        assertThat(childProcessCount("RESUBMIT")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=99"
                + " AND action='RESUBMIT' AND from_status=8 AND to_status=1 AND operator_user_id=9")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process WHERE resource_apply_id=199")).isZero();
    }

    private static void assertInheritedContent(MarketResourceApplyDO current) {
        assertThat(current.getAssetId()).isEqualTo(ASSET);
        assertThat(current.getApiId()).isEqualTo(API);
        assertThat(current.getAppId()).isEqualTo(APP);
        assertThat(current.getPurpose()).isEqualTo(PURPOSE);
        assertThat(current.getDutyMatter()).isEqualTo(DUTY);
        assertThat(current.getLegalBasis()).isEqualTo(BASIS);
        assertThat(current.getFieldScope()).isEmpty();
        assertThat(current.getApplyUseType()).isEqualTo(MarketResourceApplyDO.USE_UNLIMITED);
        assertThat(current.getApplyUseTimes()).isZero();
        assertThat(current.getRenewalOfApplyId()).isEqualTo(PARENT);
    }

    private void assertNoApprovalOrAuthorizationSideEffects() throws Exception {
        assertThat(childProcessCount("APPROVE")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_asset_grant_source")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_delivery_operation")).isZero();
        verifyNoInteractions(delivery, notifications, monitor);
    }

    private void stopParent(String action) throws Exception {
        switch (action) {
            case "PAUSE" -> service.pauseApply(PARENT, "test-only parent pause", 4L);
            case "REVOKE" -> service.revokeApply(PARENT, "test-only parent revoke", 4L);
            case "EXPIRE" -> {
                sql("UPDATE dh_market_resource_apply SET apply_end_time="
                        + "(clock_timestamp() AT TIME ZONE 'Asia/Shanghai') - interval '1 second' WHERE id=7");
                assertThat(service.expireDueApplies()).isEqualTo(1);
            }
            default -> throw new IllegalArgumentException(action);
        }
        var stopped = applications.selectById(PARENT);
        if ("PAUSE".equals(action)) {
            assertThat(stopped.getPaused()).isEqualTo(1);
            verify(delivery).enqueueSuspend(argThat(apply -> apply != null && apply.getId().equals(PARENT)));
        } else {
            assertThat(stopped.getStatus()).isEqualTo("REVOKE".equals(action)
                    ? MarketResourceApplyDO.STATUS_REVOKED : MarketResourceApplyDO.STATUS_EXPIRED);
            assertThat(stopped.getIfAuth()).isEqualTo(MarketResourceApplyDO.AUTH_NO);
            verify(delivery).enqueueRevoke(argThat(apply -> apply != null && apply.getId().equals(PARENT)));
        }
        verifyNoMoreInteractions(delivery);
    }

    private void installFailure(String failure) throws Exception {
        switch (failure) {
            case "CAS_ZERO" -> sql("""
                    CREATE FUNCTION skip_renewal_cas() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RETURN NULL; END $$;
                    CREATE TRIGGER skip_renewal_cas BEFORE UPDATE ON dh_market_resource_apply
                    FOR EACH ROW WHEN (OLD.id=99) EXECUTE FUNCTION skip_renewal_cas();
                    """);
            case "PROCESS_ZERO" -> sql("""
                    CREATE FUNCTION skip_renewal_process() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RETURN NULL; END $$;
                    CREATE TRIGGER skip_renewal_process BEFORE INSERT ON dh_market_check_process
                    FOR EACH ROW WHEN (NEW.resource_apply_id=99) EXECUTE FUNCTION skip_renewal_process();
                    """);
            case "PROCESS_CONSTRAINT" -> sql("ALTER TABLE dh_market_check_process"
                    + " ADD CONSTRAINT reject_renewal_resubmit CHECK(action<>'RESUBMIT')");
            case "AUDIT_FAILURE" -> doThrow(new IllegalStateException("test-only renewal audit unavailable"))
                    .when(audit).record("RESUBMIT", "APPLY", CHILD, 8, 1, null, "OK");
            case "COMMIT_FAILURE" -> sql("""
                    CREATE FUNCTION reject_renewal_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN RAISE EXCEPTION 'test-only renewal commit failure'; END $$;
                    CREATE CONSTRAINT TRIGGER reject_renewal_commit AFTER UPDATE ON dh_market_resource_apply
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.id=99)
                    EXECUTE FUNCTION reject_renewal_commit();
                    """);
            default -> throw new IllegalArgumentException(failure);
        }
    }

    private void seedApplication(long id, boolean child) throws Exception {
        // 标识符集合仅由本方法的固定测试字段构成，所有业务文本/JSON均使用绑定参数。
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", id);
        fields.put("apply_no", "TEST-" + id);
        fields.put("asset_id", ASSET);
        fields.put("asset_type", DataAssetDO.TYPE_DATABASE_TABLE);
        fields.put("api_id", API);
        fields.put("app_id", APP);
        fields.put("apply_user_id", id == OTHER_CHILD ? USER + 1 : USER);
        fields.put("title", "续期补正测试");
        fields.put("purpose", PURPOSE);
        fields.put("duty_matter", DUTY);
        fields.put("legal_basis", BASIS);
        fields.put("field_scope", "");
        fields.put("revision", 2);
        fields.put("snapshot", JsonUtils.toJsonString(frozenSnapshot()));
        fields.put("catalog_version_id", CATALOG_VERSION);
        fields.put("status", child ? MarketResourceApplyDO.STATUS_SUPPLEMENT : MarketResourceApplyDO.STATUS_APPROVED);
        fields.put("if_auth", child ? MarketResourceApplyDO.AUTH_NO : MarketResourceApplyDO.AUTH_YES);
        fields.put("paused", 0);
        fields.put("source_revision", child ? 0L : 4L);
        fields.put("delivery_status", MarketDeliveryService.DELIVERY_NONE);
        fields.put("apply_use_type", MarketResourceApplyDO.USE_UNLIMITED);
        fields.put("apply_use_times", 0);
        fields.put("apply_start_time", OLD_RECEIVED);
        fields.put("apply_end_time", LocalDateTime.of(child ? 2041 : 2040, 1, 1, 0, 0));
        fields.put("received_time", OLD_RECEIVED);
        fields.put("reply_deadline", OLD_RECEIVED.plusDays(1));
        fields.put("delivery_deadline", OLD_RECEIVED.plusDays(21));
        fields.put("supplement_note", child ? "请补充材料" : null);
        fields.put("renewal_of_apply_id", child ? (id == OTHER_CHILD ? PARENT + 1 : PARENT) : null);
        fields.put("renewal_operation_id", child ? "test-renewal-" + id : null);
        fields.put("renewal_requested_end_time", child ? LocalDateTime.of(2041, 1, 1, 0, 0) : null);
        String values = fields.keySet().stream().map(key -> "snapshot".equals(key) ? "CAST(? AS jsonb)" : "?")
                .collect(Collectors.joining(","));
        try (var insert = connection.prepareStatement("INSERT INTO dh_market_resource_apply ("
                + String.join(",", fields.keySet()) + ") VALUES (" + values + ")")) {
            int position = 1;
            for (Object value : fields.values()) insert.setObject(position++, value);
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
    }

    private static Map<String, Object> frozenSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("assetId", String.valueOf(ASSET));
        snapshot.put("assetVersion", "v1");
        snapshot.put("catalogVersionId", String.valueOf(CATALOG_VERSION));
        snapshot.put("publishedSnapshotId", String.valueOf(PUBLISHED_SNAPSHOT));
        snapshot.put("databaseId", "4");
        snapshot.put("schemaName", "public");
        snapshot.put("tableName", "citizen");
        snapshot.put("apiId", String.valueOf(API));
        snapshot.put("appId", String.valueOf(APP));
        snapshot.put("fieldScope", "");
        snapshot.put("dutyMatter", DUTY);
        snapshot.put("revision", "2");
        snapshot.put("frozenColumns", FROZEN_COLUMNS);
        snapshot.put("approvedContractHash", "test-renewal-contract");
        snapshot.put("apiVersion", "v1");
        snapshot.put("activeReleaseId", "21");
        return snapshot;
    }

    private static DataAssetDO publishedAsset() {
        var current = new DataAssetDO();
        current.setId(ASSET);
        current.setStatus(DataAssetDO.STATUS_PUBLISHED);
        current.setAssetType(DataAssetDO.TYPE_DATABASE_TABLE);
        current.setDatasourceId(4L);
        current.setSchemaName("public");
        current.setTableName("citizen");
        current.setPublishedVersion("v1");
        current.setPublishedCatalogVersionId(CATALOG_VERSION);
        current.setPublishedSnapshotId(PUBLISHED_SNAPSHOT);
        current.setShareAttr(DataAssetDO.SHARE_UNCONDITIONAL);
        return current;
    }

    private static SupplyApiBindingRespDTO publishedApi() {
        var binding = new SupplyApiBindingRespDTO();
        binding.setId(API);
        binding.setStatus(1);
        binding.setApiType("QUERY");
        binding.setConfigType("TABLE");
        binding.setDatabaseId(4L);
        binding.setSchemaName("public");
        binding.setTableName("citizen");
        binding.setFieldMappingVerified(true);
        binding.setActiveReleaseId(21L);
        binding.setContractHash("test-renewal-contract");
        binding.setVersion("v1");
        binding.setOutputFieldMapping(Map.of("id", "Id", "comma", "physical,column", "case", "Case Sensitive"));
        return binding;
    }

    private static List<MetadataColumnRespDTO> availableColumns() {
        return List.of(column("Id"), column("physical,column"), column("Case Sensitive"), column("NewColumn"));
    }

    private static MetadataColumnRespDTO column(String name) {
        var value = new MetadataColumnRespDTO();
        value.setColumnName(name);
        return value;
    }

    private void login() {
        TenantContextHolder.clear();
        var user = new LoginUser();
        user.setId(USER);
        SecurityFrameworkUtils.setLoginUser(user, new MockHttpServletRequest());
    }

    private long childProcessCount(String action) throws Exception {
        try (var statement = connection.prepareStatement("SELECT count(*) FROM dh_market_check_process"
                + " WHERE resource_apply_id=? AND action=?")) {
            statement.setLong(1, CHILD);
            statement.setString(2, action);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private String storedApplication(long id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT to_jsonb(a)::text FROM dh_market_resource_apply a WHERE id=?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private String storedSnapshot(long id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT snapshot::text FROM dh_market_resource_apply WHERE id=?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshot(long id) throws Exception {
        return JsonUtils.parseObject(storedSnapshot(id), Map.class);
    }

    private String graph() throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT jsonb_build_array(
                  (SELECT COALESCE(jsonb_agg(to_jsonb(a) ORDER BY id),'[]'::jsonb) FROM dh_market_resource_apply a),
                  (SELECT COALESCE(jsonb_agg(to_jsonb(p) ORDER BY id),'[]'::jsonb) FROM dh_market_check_process p),
                  (SELECT COALESCE(jsonb_agg(to_jsonb(s) ORDER BY id),'[]'::jsonb) FROM dh_asset_grant_source s),
                  (SELECT COALESCE(jsonb_agg(to_jsonb(o) ORDER BY id),'[]'::jsonb) FROM dh_market_delivery_operation o)
                )::text
                """)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static void deny(ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(code));
    }
}
