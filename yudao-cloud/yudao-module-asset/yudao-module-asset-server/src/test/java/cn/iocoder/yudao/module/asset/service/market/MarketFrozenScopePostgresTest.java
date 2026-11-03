package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplySaveReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 真实 PostgreSQL + 实际 MyBatis Mapper + Spring 事务代理，证明失败不会留下半提交申请/审批。 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class MarketFrozenScopePostgresTest extends SharingPostgresSupport {
    private MarketApplyService service;
    private MarketResourceApplyMapper applyMapper;
    private AssetCatalogService catalogService;
    private MarketDeliveryService delivery;
    private DataAssetDO asset;

    @BeforeEach
    void configureService() throws Exception {
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();
        try (var input = getClass().getResourceAsStream("/sql/market-freeze-fixture.sql")) {
            if (input == null) throw new IllegalStateException("Missing market fixture");
            sql(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        // 初次创建已复用平台序列和0050在途唯一约束，旧最小夹具也必须具备这些实际前置。
        sql("""
                ALTER TABLE dh_market_resource_apply ALTER COLUMN id SET DEFAULT nextval('dh_market_resource_apply_seq');
                ALTER TABLE dh_market_resource_apply ALTER COLUMN snapshot TYPE jsonb USING snapshot::jsonb;
                """);
        migrate("0042_asset_grant-source-delivery.sql");
        migrate("0047_asset_renewal-request-evidence.sql");
        migrate("0050_asset_market-active-application-uniqueness.sql");
        var datasource = new UnpooledDataSource("org.postgresql.Driver",
                jdbcUrl + "?currentSchema=datahub-center", "sharing_test", "");
        var config = singleTenantConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("freeze-test", new SpringManagedTransactionFactory(), datasource));
        config.addMapper(MarketResourceApplyMapper.class);
        config.addMapper(MarketCheckProcessMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        applyMapper = session.getMapper(MarketResourceApplyMapper.class);
        var target = new MarketApplyServiceImpl();
        ReflectionTestUtils.setField(target, "applyMapper", applyMapper);
        ReflectionTestUtils.setField(target, "processMapper", session.getMapper(MarketCheckProcessMapper.class));
        var assets = mock(DataAssetMapper.class);
        asset = DataAssetDO.builder().id(5L).status(DataAssetDO.STATUS_PUBLISHED)
                .assetType(DataAssetDO.TYPE_DATABASE_TABLE).publishedVersion("v1")
                .publishedSnapshotId(11L).publishedCatalogVersionId(12L)
                .datasourceId(4L).schemaName("public").tableName("citizen").build();
        when(assets.selectById(5L)).thenReturn(asset);
        when(assets.selectForMatching(5L)).thenReturn(asset);
        ReflectionTestUtils.setField(target, "assetMapper", assets);
        var guard = mock(MarketObjectGuard.class);
        when(guard.requireUserId()).thenReturn(9L);
        ReflectionTestUtils.setField(target, "objectGuard", guard);
        catalogService = mock(AssetCatalogService.class);
        when(catalogService.listColumns(5L, 11L)).thenReturn(List.of(column("Id")));
        org.mockito.Mockito.when(catalogService.requireCompletePublishedAsset(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> call.getArgument(0));
        ReflectionTestUtils.setField(target, "catalogService", catalogService);
        delivery = mock(MarketDeliveryService.class);
        ReflectionTestUtils.setField(target, "deliveryService", delivery);
        ReflectionTestUtils.setField(target, "displayHelper", new MarketDisplayHelper());
        ReflectionTestUtils.setField(target, "workingDayService", mock(MarketWorkingDayService.class));
        ReflectionTestUtils.setField(target, "notifyHelper", mock(MarketNotifyHelper.class));
        ReflectionTestUtils.setField(target, "auditRecorder", mock(MarketAuditRecorder.class));
        ReflectionTestUtils.setField(target, "assetMonitorService", mock(AssetMonitorService.class));
        var users = mock(AdminUserApi.class);
        when(users.getUser(9L)).thenReturn(CommonResult.success(null));
        ReflectionTestUtils.setField(target, "adminUserApi", users);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(datasource),
                new AnnotationTransactionAttributeSource()));
        service = (MarketApplyService) proxy.getProxy();
    }

    @AfterEach
    void clearTenant() {
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();
    }

    @Test
    void metadataFailureLeavesNoApplicationOrProcess() throws Exception {
        when(catalogService.listColumns(5L, 11L)).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(() -> service.createApply(request())).isInstanceOf(ServiceException.class)
                .extracting(ex -> ((ServiceException) ex).getCode()).isEqualTo(1_500_026_025);
        assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
        verifyNoInteractions(delivery);
    }

    @Test
    void invalidSelectionsRollBackInsteadOfPersistingPartialOrAllScope() throws Exception {
        for (String selection : List.of("Id,missing", ",,,", "*")) {
            var request = request();
            request.setFieldScope(selection);
            assertThatThrownBy(() -> service.createApply(request)).isInstanceOf(ServiceException.class);
            assertThat(scalar("SELECT count(*) FROM dh_market_resource_apply")).isZero();
        }
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isZero();
    }

    @Test
    void blankFormScopePersistsExplicitColumnsAndApprovalDoesNotAddLaterColumns() throws Exception {
        Long id = service.createApply(request());
        String snapshot = applyMapper.selectById(id).getSnapshot();
        when(catalogService.listColumns(5L, 11L)).thenReturn(List.of(column("Id"), column("secret")));
        service.checkApply(approval(id));
        var approved = applyMapper.selectById(id);
        assertThat(approved.getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_APPROVED);
        assertThat(approved.getIfAuth()).isEqualTo(MarketResourceApplyDO.AUTH_YES);
        assertThat(approved.getSnapshot()).isEqualTo(snapshot);
        assertThat(MarketFieldScope.requireMatchesAsset(approved, asset).getColumns()).containsExactly("Id");
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(2);
        verify(catalogService).listColumns(5L, 11L);
        verify(delivery).enqueueApprovedGrant(any(), any(), any(), any());
    }

    @Test
    void historicalMissingProofCannotApproveEnableOrResume() throws Exception {
        Long id = service.createApply(request());
        sql("UPDATE dh_market_resource_apply SET snapshot='{}'");
        assertThatThrownBy(() -> service.checkApply(approval(id))).isInstanceOf(ServiceException.class);
        assertThat(applyMapper.selectById(id).getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_PENDING);
        sql("UPDATE dh_market_resource_apply SET status=2,if_auth=0,paused=1");
        assertThatThrownBy(() -> service.authApply(id, 1)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.resumeApply(id)).isInstanceOf(ServiceException.class);
        var row = applyMapper.selectById(id);
        assertThat(row.getIfAuth()).isZero();
        assertThat(row.getPaused()).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
        verifyNoInteractions(delivery);
    }

    @Test
    void changedTableBindingDoesNotChangePersistedPendingState() {
        Long id = service.createApply(request());
        asset.setSchemaName("private");
        assertThatThrownBy(() -> service.checkApply(approval(id))).isInstanceOf(ServiceException.class);
        assertThat(applyMapper.selectById(id).getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_PENDING);
        verifyNoInteractions(delivery);
    }

    @Test
    void deliveryEnqueueFailureRollsBackApprovalAndProcessTogether() throws Exception {
        Long id = service.createApply(request());
        doThrow(new IllegalStateException("enqueue failure")).when(delivery).enqueueApprovedGrant(any(), any(), any(), any());
        assertThatThrownBy(() -> service.checkApply(approval(id))).isInstanceOf(IllegalStateException.class);
        var row = applyMapper.selectById(id);
        assertThat(row.getStatus()).isEqualTo(MarketResourceApplyDO.STATUS_PENDING);
        assertThat(row.getIfAuth()).isZero();
        assertThat(scalar("SELECT count(*) FROM dh_market_check_process")).isEqualTo(1);
    }

    private static MarketApplySaveReqVO request() {
        var request = new MarketApplySaveReqVO();
        request.setAssetId(5L);
        request.setTitle("冻结字段测试");
        return request;
    }

    private static MarketCheckReqVO approval(Long id) {
        var request = new MarketCheckReqVO();
        request.setId(id);
        request.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        return request;
    }

    private static MetadataColumnRespDTO column(String name) {
        var column = new MetadataColumnRespDTO();
        column.setColumnName(name);
        return column;
    }
}
