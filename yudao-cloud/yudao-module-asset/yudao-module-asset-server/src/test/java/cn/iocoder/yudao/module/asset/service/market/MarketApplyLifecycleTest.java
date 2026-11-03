package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCheckProcessDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketCheckProcessMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceEndpointMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyApiBindingRespDTO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 申请生命周期与交付语义：暂停必须是可恢复停用，提交时字段与绑定证据冻结失败即拒绝。
 */
class MarketApplyLifecycleTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MarketResourceApplyDO.class);
    }

    private MarketApplyServiceImpl service;
    private MarketDeliveryService deliveryService;
    private MarketResourceApplyMapper applyMapper;
    private MarketCheckProcessMapper processMapper;
    private DataAssetMapper assetMapper;
    private MarketObjectGuard objectGuard;
    private AssetCatalogService catalogService;
    private MarketSupplyApiGuard supplyApiGuard;

    private static MarketResourceApplyDO approved() {
        MarketResourceApplyDO apply = new MarketResourceApplyDO();
        apply.setId(7L);
        apply.setAssetId(5L);
        apply.setApplyUserId(9L);
        apply.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_YES);
        apply.setPaused(0);
        apply.setRevision(1);
        return apply;
    }

    @BeforeEach
    void setUp() {
        TenantContextHolder.clear();
        service = new MarketApplyServiceImpl();
        deliveryService = mock(MarketDeliveryService.class);
        applyMapper = mock(MarketResourceApplyMapper.class);
        assetMapper = mock(DataAssetMapper.class);
        objectGuard = mock(MarketObjectGuard.class);
        catalogService = mock(AssetCatalogService.class);
        supplyApiGuard = mock(MarketSupplyApiGuard.class);
        ReflectionTestUtils.setField(service, "deliveryService", deliveryService);
        ReflectionTestUtils.setField(service, "applyMapper", applyMapper);
        ReflectionTestUtils.setField(service, "assetMapper", assetMapper);
        ReflectionTestUtils.setField(service, "objectGuard", objectGuard);
        ReflectionTestUtils.setField(service, "catalogService", catalogService);
        ReflectionTestUtils.setField(service, "supplyApiGuard", supplyApiGuard);
        processMapper = mock(MarketCheckProcessMapper.class);
        ReflectionTestUtils.setField(service, "processMapper", processMapper);
        ReflectionTestUtils.setField(service, "endpointMapper", mock(MarketResourceEndpointMapper.class));
        ReflectionTestUtils.setField(service, "notifyHelper", mock(MarketNotifyHelper.class));
        ReflectionTestUtils.setField(service, "auditRecorder", mock(MarketAuditRecorder.class));
        ReflectionTestUtils.setField(service, "workingDayService", mock(MarketWorkingDayService.class));
        when(objectGuard.requireUserId()).thenReturn(9L);
        when(applyMapper.selectById(anyLong())).thenReturn(approved());
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void pauseIsRecoverableSuspensionNotATerminalRevoke() {
        when(applyMapper.update(any(), any())).thenReturn(1);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);
        service.pauseApply(7L, "暂停");
        verify(deliveryService).enqueueSuspend(any());
        verify(deliveryService, never()).enqueueRevoke(any());
    }

    @Test
    void revokeStillDeliversATerminalRevoke() {
        when(applyMapper.update(any(), any())).thenReturn(1);
        when(processMapper.insert(any(MarketCheckProcessDO.class))).thenReturn(1);
        service.revokeApply(7L, "撤销");
        verify(deliveryService).enqueueRevoke(any());
        verify(deliveryService, never()).enqueueSuspend(any());
    }

    @Test
    void submitFailsClosedWhenColumnMetadataIsUnavailable() {
        DataAssetDO asset = new DataAssetDO();
        asset.setId(5L);
        asset.setPublishedSnapshotId(11L);
        when(catalogService.listColumns(anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("metadata service down"));
        MarketResourceApplyDO apply = approved();
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "fillSubmitSnapshot", apply, asset))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_026_025));
        assertThat(apply.getSnapshot()).isNull();
    }

    @Test
    void submitFreezesColumnsAndBindingEvidence() {
        DataAssetDO asset = new DataAssetDO();
        asset.setId(5L);
        asset.setPublishedSnapshotId(11L);
        MetadataColumnRespDTO id = new MetadataColumnRespDTO();
        id.setColumnName("id");
        MetadataColumnRespDTO phone = new MetadataColumnRespDTO();
        phone.setColumnName("phone");
        when(catalogService.listColumns(anyLong(), anyLong())).thenReturn(List.of(id, phone));
        SupplyApiBindingRespDTO binding = new SupplyApiBindingRespDTO();
        binding.setId(3L);
        binding.setDatabaseId(4L);
        binding.setSchemaName("public");
        binding.setTableName("citizen");
        binding.setContractHash("contract-1");
        binding.setVersion("v1");
        binding.setActiveReleaseId(8L);
        binding.setFieldMappingVerified(true);
        binding.setOutputFieldMapping(Map.of("id", "id", "phone", "phone"));
        when(supplyApiGuard.requireQueryableTableApi(anyLong())).thenReturn(binding);
        MarketResourceApplyDO apply = approved();
        apply.setApiId(3L);
        apply.setAppId(2L);

        ReflectionTestUtils.invokeMethod(service, "fillSubmitSnapshot", apply, asset);

        assertThat(apply.getSnapshot()).contains("frozenColumns").contains("\"citizen\"").contains("contract-1");
        assertThat(MarketFieldScope.requireFrozenColumns(apply.getSnapshot()))
                .containsExactlyInAnyOrder("id", "phone");
    }

    @Test
    void submitRejectsBindingWhoseMappingDoesNotCoverApprovedColumns() {
        DataAssetDO asset = new DataAssetDO();
        asset.setId(5L);
        asset.setPublishedSnapshotId(11L);
        MetadataColumnRespDTO id = new MetadataColumnRespDTO();
        id.setColumnName("id");
        when(catalogService.listColumns(anyLong(), anyLong())).thenReturn(List.of(id));
        SupplyApiBindingRespDTO binding = new SupplyApiBindingRespDTO();
        binding.setId(3L);
        binding.setDatabaseId(4L);
        binding.setSchemaName("public");
        binding.setTableName("citizen");
        binding.setContractHash("contract-1");
        binding.setVersion("v1");
        binding.setActiveReleaseId(8L);
        binding.setFieldMappingVerified(true);
        binding.setOutputFieldMapping(Map.of("other", "other"));
        when(supplyApiGuard.requireQueryableTableApi(anyLong())).thenReturn(binding);
        MarketResourceApplyDO apply = approved();
        apply.setApiId(3L);
        apply.setAppId(2L);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "fillSubmitSnapshot", apply, asset))
                .isInstanceOf(ServiceException.class)
                .satisfies(ex -> assertThat(((ServiceException) ex).getCode()).isEqualTo(1_500_026_020));
    }

    @SuppressWarnings("unused")
    private static SFunction<MarketResourceApplyDO, ?> unused() {
        return MarketResourceApplyDO::getId;
    }

    @Test
    void selectedColumnsMustAllResolveAndAliasesMustBeUnambiguous() {
        var asset = new DataAssetDO(); asset.setId(5L); asset.setPublishedSnapshotId(11L);
        var id = new MetadataColumnRespDTO(); id.setColumnName("id"); id.setBusinessName("共同别名");
        var secret = new MetadataColumnRespDTO(); secret.setColumnName("secret"); secret.setBusinessName("共同别名");
        when(catalogService.listColumns(5L, 11L)).thenReturn(List.of(id, secret));
        var apply = approved(); apply.setFieldScope("id,不存在");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "fillSubmitSnapshot", apply, asset))
                .isInstanceOf(ServiceException.class);
        apply.setFieldScope("共同别名");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "fillSubmitSnapshot", apply, asset))
                .isInstanceOf(ServiceException.class);
        when(catalogService.listColumns(5L, 11L)).thenReturn(List.of(id, id));
        apply.setFieldScope("");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "fillSubmitSnapshot", apply, asset))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void approvalAndReenableRequireFrozenEvidenceBeforeAnyStateChange() {
        var apply = approved(); apply.setStatus(MarketResourceApplyDO.STATUS_PENDING);
        when(applyMapper.selectById(7L)).thenReturn(apply);
        var req = new cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO();
        req.setId(7L); req.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        assertThatThrownBy(() -> service.checkApply(req)).isInstanceOf(ServiceException.class);
        verify(applyMapper, never()).update(any(), any());
        apply.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        assertThatThrownBy(() -> service.authApply(7L, 1)).isInstanceOf(ServiceException.class);
        verify(applyMapper, never()).updateById(any(MarketResourceApplyDO.class));
        apply.setPaused(1);
        assertThatThrownBy(() -> service.resumeApply(7L)).isInstanceOf(ServiceException.class);
        verify(deliveryService, never()).enqueueGrant(any());
        verify(deliveryService, never()).enqueueApprovedGrant(any(), any(), any(), any());
    }

    @Test
    void portalSubmissionPinsBindingAndNeverRefreezesOnApprovalOrResume() {
        when(processMapper.insert(any(MarketCheckProcessDO.class)))
                .thenReturn(1);
        var asset = portalAsset();
        when(assetMapper.selectById(5L)).thenReturn(asset);
        var id = new MetadataColumnRespDTO();
        id.setColumnName("Id");
        when(catalogService.listColumns(5L, 11L)).thenReturn(List.of(id));
        var apply = approved();
        ReflectionTestUtils.invokeMethod(service, "fillSubmitSnapshot", apply, asset);
        String frozen = apply.getSnapshot();
        assertThat(MarketFieldScope.requireMatchesAsset(apply, asset).getColumns()).containsExactly("Id");
        // 批准不依赖重新读取元数据，更不能因后来新增字段改写冻结证据。
        when(catalogService.listColumns(5L, 11L)).thenThrow(new IllegalStateException("offline"));
        when(applyMapper.selectById(7L)).thenReturn(apply);
        when(applyMapper.update(any(), any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "assetMonitorService",
                mock(cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService.class));
        apply.setStatus(MarketResourceApplyDO.STATUS_PENDING);
        var req = new cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO();
        req.setId(7L);
        req.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        service.checkApply(req);
        service.authApply(7L, 1);
        apply.setPaused(1);
        service.resumeApply(7L);
        assertThat(apply.getSnapshot()).isEqualTo(frozen);
        // 已开通的同状态授权请求是幂等空操作，仅审批及真正恢复各产生一次发放。
        verify(deliveryService).enqueueApprovedGrant(org.mockito.ArgumentMatchers.eq(apply), any(), any(), any());
        verify(deliveryService).enqueueGrant(apply);
        verify(catalogService).listColumns(5L, 11L);
    }

    @Test
    void changedBindingVersionAndMissingAssetCannotReuseApproval() {
        var original = portalAsset();
        var id = new MetadataColumnRespDTO();
        id.setColumnName("id");
        when(catalogService.listColumns(5L, 11L)).thenReturn(List.of(id));
        var apply = approved();
        ReflectionTestUtils.invokeMethod(service, "fillSubmitSnapshot", apply, original);
        for (String field : List.of("id", "datasourceId", "publishedSnapshotId", "publishedCatalogVersionId")) {
            var changed = portalAsset();
            ReflectionTestUtils.setField(changed, field, 999L);
            assertThatThrownBy(() -> MarketFieldScope.requireMatchesAsset(apply, changed))
                    .as(field).isInstanceOf(ServiceException.class);
        }
        for (String field : List.of("schemaName", "tableName", "publishedVersion")) {
            var changed = portalAsset();
            ReflectionTestUtils.setField(changed, field, "changed");
            assertThatThrownBy(() -> MarketFieldScope.requireMatchesAsset(apply, changed))
                    .as(field).isInstanceOf(ServiceException.class);
        }
        assertThatThrownBy(() -> MarketFieldScope.requireMatchesAsset(apply, null))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void missingBindingIsNotReconstructedFromCurrentAsset() {
        var apply = approved();
        apply.setSnapshot("{\"frozenColumns\":[\"id\"]}");
        assertThatThrownBy(() -> MarketFieldScope.requireMatchesAsset(apply, portalAsset()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void invalidOrEmptyMetadataAndPunctuationSelectionNeverGrantAll() {
        var asset = portalAsset();
        for (List<MetadataColumnRespDTO> columns : List.of(List.<MetadataColumnRespDTO>of(),
                List.of(new MetadataColumnRespDTO()))) {
            when(catalogService.listColumns(5L, 11L)).thenReturn(columns);
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "fillSubmitSnapshot", approved(), asset))
                    .isInstanceOf(ServiceException.class);
        }
        when(catalogService.listColumns(5L, 11L)).thenReturn(null);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "fillSubmitSnapshot", approved(), asset))
                .isInstanceOf(ServiceException.class);
        var id = new MetadataColumnRespDTO();
        id.setColumnName("id");
        when(catalogService.listColumns(5L, 11L)).thenReturn(List.of(id));
        var apply = approved();
        apply.setFieldScope(",,,");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "fillSubmitSnapshot", apply, asset))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void denyingOrDisablingLegacyApprovalDoesNotRequireFrozenProof() {
        when(processMapper.insert(any(MarketCheckProcessDO.class)))
                .thenReturn(1);
        var apply = approved();
        apply.setStatus(MarketResourceApplyDO.STATUS_PENDING);
        when(applyMapper.selectById(7L)).thenReturn(apply);
        when(applyMapper.update(any(), any())).thenReturn(1);
        var req = new cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCheckReqVO();
        req.setId(7L);
        req.setStatus(MarketResourceApplyDO.STATUS_REJECTED);
        service.checkApply(req);
        apply.setStatus(MarketResourceApplyDO.STATUS_APPROVED);
        apply.setIfAuth(MarketResourceApplyDO.AUTH_YES);
        service.authApply(7L, 0);
        verify(deliveryService).enqueueSuspend(apply);
        verify(deliveryService, never()).enqueueGrant(any());
        verify(deliveryService, never()).enqueueApprovedGrant(any(), any(), any(), any());
    }

    private static DataAssetDO portalAsset() {
        return DataAssetDO.builder().id(5L).assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .publishedSnapshotId(11L).publishedCatalogVersionId(12L).publishedVersion("v1")
                .datasourceId(4L).schemaName("public").tableName("citizen").build();
    }
}
