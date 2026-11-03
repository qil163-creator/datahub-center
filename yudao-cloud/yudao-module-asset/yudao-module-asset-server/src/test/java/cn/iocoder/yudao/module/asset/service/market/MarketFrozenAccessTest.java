package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MarketFrozenAccessTest {
    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                MarketResourceApplyDO.class);
    }

    private MarketAccessServiceImpl service;
    private MarketResourceApplyDO apply;
    private DataAssetDO asset;
    private DatabaseApi database;

    @BeforeEach
    void setUp() {
        service = new MarketAccessServiceImpl();
        ReflectionTestUtils.setField(service, "metadataIdentityGuard",
                mock(cn.iocoder.yudao.module.asset.service.security.AssetMetadataIdentityGuard.class));
        apply = MarketResourceApplyDO.builder().id(7L).assetId(5L).applyUserId(9L)
                .revision(1).sourceRevision(2L)
                .status(MarketResourceApplyDO.STATUS_APPROVED).ifAuth(1).paused(0)
                .firstUsedTime(LocalDateTime.now()).fieldScope("").snapshot("""
                    {"assetId":"5","assetVersion":"v1","catalogVersionId":"12","publishedSnapshotId":"11",
                     "databaseId":"4","schemaName":"public","tableName":"citizen","frozenColumns":["Id"]}
                    """).build();
        asset = DataAssetDO.builder().id(5L).assetType(DataAssetDO.TYPE_DATABASE_TABLE)
                .status(DataAssetDO.STATUS_PUBLISHED)
                .datasourceId(4L).schemaName("public").tableName("citizen")
                .publishedSnapshotId(11L).publishedCatalogVersionId(12L).publishedVersion("v1").build();
        var applies = mock(MarketResourceApplyMapper.class);
        when(applies.databaseNow()).thenAnswer(invocation -> LocalDateTime.now());
        when(applies.selectForAccess( 7L, 9L)).thenReturn(apply);
        when(applies.selectQuerySources( eq(5L), eq(9L), any(), eq(257))).thenReturn(List.of(apply));
        when(applies.selectEffective(eq(9L), eq(5L), any())).thenReturn(apply);
        when(applies.selectApprovedAuthorizedByUser(9L)).thenReturn(List.of(apply));
        ReflectionTestUtils.setField(service, "applyMapper", applies);
        var assets = mock(DataAssetMapper.class);
        when(assets.selectById(5L)).thenReturn(asset);
        when(assets.selectTablesByDatasourceId(4L)).thenReturn(List.of(asset));
        ReflectionTestUtils.setField(service, "assetMapper", assets);
        var display = mock(MarketDisplayHelper.class);
        when(display.assets(any())).thenReturn(Map.of(5L, asset));
        ReflectionTestUtils.setField(service, "displayHelper", display);
        database = mock(DatabaseApi.class);
        ReflectionTestUtils.setField(service, "databaseApi", database);
        ReflectionTestUtils.setField(service, "assetMonitorService", mock(AssetMonitorService.class));
        var transactions = mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(invocation ->
                new org.springframework.transaction.support.SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();
    }

    @AfterEach
    void clearContext() {
        cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder.clear();
    }

    @Test
    void stoppingNewApplicationsKeepsExistingFrozenPreviewAndQueryAuthorization() {
        asset.setStatus(DataAssetDO.STATUS_UNPUBLISHED);
        when(database.previewTable(any())).thenReturn(previewResult());
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            assertThat(service.requireQuerySnapshot(9L, 7L).fieldScope().getColumns()).containsExactly("Id");
            assertThat(service.previewAuthorizedAsset(5L, 20).getRows()).containsExactly(Map.of("Id", 1));
        }
        verify(database).previewTable(any());
    }

    @Test
    void stoppedSupplyRetirementAndMissingPublicationCannotUseExistingGrant() {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            asset.setStatus(DataAssetDO.STATUS_OFFLINE);
            assertThatThrownBy(() -> service.requireQuerySnapshot(9L, 7L)).isInstanceOf(ServiceException.class);
            asset.setStatus(DataAssetDO.STATUS_UNPUBLISHED);
            asset.setRetirementRequested(true);
            assertThatThrownBy(() -> service.requireQuerySnapshot(9L, 7L)).isInstanceOf(ServiceException.class);
            asset.setRetirementRequested(false);
            asset.setPublishedCatalogVersionId(null);
            assertThatThrownBy(() -> service.requireQuerySnapshot(9L, 7L)).isInstanceOf(ServiceException.class);
        }
        verifyNoInteractions(database);
    }

    @Test
    void unpublishedExistingGrantStillRejectsExpiryAndPhysicalBindingChanges() {
        asset.setStatus(DataAssetDO.STATUS_UNPUBLISHED);
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            apply.setApplyEndTime(LocalDateTime.now().minusSeconds(1));
            assertThatThrownBy(() -> service.requireQuerySnapshot(9L, 7L)).isInstanceOf(ServiceException.class);
            apply.setApplyEndTime(null);
            asset.setTableName("other_table");
            assertThatThrownBy(() -> service.requireQuerySnapshot(9L, 7L)).isInstanceOf(ServiceException.class);
        }
        verifyNoInteractions(database);
    }

    @Test
    void missingScopeNeverBecomesAnUnrestrictedPreviewOrQuery() {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            for (String snapshot : List.of("{}", "{\"frozenColumns\":[]}", "invalid")) {
                apply.setSnapshot(snapshot);
                assertThatThrownBy(() -> service.previewAuthorizedAsset(5L, 20)).isInstanceOf(ServiceException.class);
                assertThatThrownBy(() -> service.findEffectiveApplyForTable(9L, 4L, "citizen"))
                        .isInstanceOf(ServiceException.class);
            }
        }
        verifyNoInteractions(database);
    }

    @Test
    void changedPhysicalBindingFailsBeforeDatabaseRpc() {
        asset.setTableName("private_table");
        assertThatThrownBy(() -> service.findEffectiveApply(9L, 5L)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(database);
    }

    @Test
    void invalidEvidenceIsOmittedFromAuthorizedTableList() {
        assertThat(service.listAuthorizedTableNames(9L, 4L)).containsExactly("citizen");
        apply.setSnapshot("{}");
        assertThat(service.listAuthorizedTableNames(9L, 4L)).isEmpty();
    }

    @Test
    void validPreviewConstrainsPhysicalColumnsBeforeRpcAndDiscardsLabelCollisions() {
        var preview = new DatabasePreviewRespDTO();
        preview.setColumns(List.of(new DatabasePreviewRespDTO.Column("Id", "编号", "bigint"),
                new DatabasePreviewRespDTO.Column("secret", "Id", "varchar")));
        preview.setRows(List.of(Map.of("Id", 1, "secret", "denied")));
        when(database.previewTable(any())).thenReturn(CommonResult.success(preview));
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            assertThat(service.previewAuthorizedAsset(5L, 20).getRows()).containsExactly(Map.of("Id", 1));
        }
        var request = ArgumentCaptor.forClass(DatabasePreviewReqDTO.class);
        verify(database).previewTable(request.capture());
        assertThat(request.getValue().getColumns()).containsExactly("Id");
        assertThat(request.getValue().getSchemaName()).isEqualTo("public");
    }

    @Test
    void revocationDuringPreviewMustDiscardTheResult() {
        when(database.previewTable(any())).thenAnswer(invocation -> {
            apply.setStatus(MarketResourceApplyDO.STATUS_REVOKED);
            return previewResult();
        });
        assertInFlightResultRejected();
    }

    @Test
    void expiryDuringPreviewMustDiscardTheResult() {
        when(database.previewTable(any())).thenAnswer(invocation -> {
            apply.setApplyEndTime(LocalDateTime.now().minusSeconds(1));
            return previewResult();
        });
        assertInFlightResultRejected();
    }

    @Test
    void suspensionThenResumeDuringPreviewCannotResurrectAnOldResult() {
        apply.setSourceRevision(2L);
        when(database.previewTable(any())).thenAnswer(invocation -> {
            apply.setSourceRevision(4L);
            return previewResult();
        });
        assertInFlightResultRejected();
    }

    @Test
    void databaseFailureDuringFinalCheckCannotReleaseTheResult() {
        var mapper = (MarketResourceApplyMapper) ReflectionTestUtils.getField(service, "applyMapper");
        when(database.previewTable(any())).thenAnswer(invocation -> {
            when(mapper.selectForAccess( 7L, 9L))
                    .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
            return previewResult();
        });
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            assertThatThrownBy(() -> service.previewAuthorizedAsset(5L, 20))
                    .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        }
        verify(database).previewTable(any());
    }

    @Test
    void missingSourceVersionFailsBeforeExternalRead() {
        apply.setSourceRevision(null);
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            assertThatThrownBy(() -> service.previewAuthorizedAsset(5L, 20))
                    .isInstanceOf(ServiceException.class);
        }
        verifyNoInteractions(database);
    }

    @Test
    void databaseClockDriftAndExactEndBoundaryCannotUseNodeTime() {
        var mapper = (MarketResourceApplyMapper) ReflectionTestUtils.getField(service, "applyMapper");
        LocalDateTime boundary = LocalDateTime.of(2040, 1, 1, 0, 0);
        apply.setApplyStartTime(boundary.minusHours(1));
        apply.setApplyEndTime(boundary);
        when(mapper.databaseNow()).thenReturn(boundary.minusNanos(1000), boundary);
        assertThat(service.isEffective(apply)).isTrue();
        assertThat(service.isEffective(apply)).isFalse();
    }

    private void assertInFlightResultRejected() {
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            assertThatThrownBy(() -> service.previewAuthorizedAsset(5L, 20))
                    .isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode()).isEqualTo(1_500_026_028);
        }
        verify(database).previewTable(any());
    }

    private static CommonResult<DatabasePreviewRespDTO> previewResult() {
        var preview = new DatabasePreviewRespDTO();
        preview.setColumns(List.of(new DatabasePreviewRespDTO.Column("Id", "编号", "bigint")));
        preview.setRows(List.of(Map.of("Id", 1)));
        return CommonResult.success(preview);
    }

    @Test
    void catalogPreviewAlsoRequiresFrozenEvidenceBeforeRpc() {
        var catalog = catalogPreviewService();
        var preview = new DatabasePreviewRespDTO();
        preview.setColumns(List.of(new DatabasePreviewRespDTO.Column("Id", "编号", "bigint")));
        preview.setRows(List.of(Map.of("Id", 1)));
        when(database.previewTable(any())).thenReturn(CommonResult.success(preview));
        var request = new cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPreviewReqVO();
        request.setId(5L);
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            assertThat(catalog.previewAsset(request).getRows()).containsExactly(Map.of("Id", 1));
            apply.setSnapshot("{}");
            assertThatThrownBy(() -> catalog.previewAsset(request)).isInstanceOf(ServiceException.class);
        }
        var capture = ArgumentCaptor.forClass(DatabasePreviewReqDTO.class);
        verify(database).previewTable(capture.capture());
        assertThat(capture.getValue().getColumns()).containsExactly("Id");
    }

    @Test
    void catalogPreviewMustDiscardAResultRevokedDuringItsDataRpc() {
        var catalog = catalogPreviewService();
        when(database.previewTable(any())).thenAnswer(call -> {
            apply.setIfAuth(0);
            return previewResult();
        });
        var request = new cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPreviewReqVO();
        request.setId(5L);
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            assertThatThrownBy(() -> catalog.previewAsset(request)).isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode()).isEqualTo(1_500_026_028);
        }
        verify(database).previewTable(any());
    }

    @Test
    void authoritativeClockMovingBackwardsDoesNotReleaseOldResult() {
        var mapper = (MarketResourceApplyMapper) ReflectionTestUtils.getField(service, "applyMapper");
        LocalDateTime now = LocalDateTime.of(2040, 1, 1, 0, 0);
        when(mapper.databaseNow()).thenReturn(now, now.minusSeconds(1));
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
            var snapshot = service.requireQuerySnapshot(9L, 7L);
            assertThatThrownBy(() -> service.assertStillCurrent(snapshot)).isInstanceOf(ServiceException.class)
                    .extracting(ex -> ((ServiceException) ex).getCode()).isEqualTo(1_500_026_028);
        }
    }

    private cn.iocoder.yudao.module.asset.service.dataasset.DataAssetServiceImpl catalogPreviewService() {
        var catalog = new cn.iocoder.yudao.module.asset.service.dataasset.DataAssetServiceImpl();
        var assets = mock(DataAssetMapper.class);
        when(assets.selectById(5L)).thenReturn(asset);
        ReflectionTestUtils.setField(catalog, "assetMapper", assets);
        ReflectionTestUtils.setField(catalog, "catalogService",
                mock(cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService.class));
        ReflectionTestUtils.setField(catalog, "assetAccessGuard",
                mock(cn.iocoder.yudao.module.asset.service.security.AssetAccessGuard.class));
        ReflectionTestUtils.setField(catalog, "marketAccessService", service);
        ReflectionTestUtils.setField(catalog, "etlBatchBindings", mock(cn.iocoder.yudao.module.asset.service.dataasset.AssetEtlBatchBindingService.class));
        ReflectionTestUtils.setField(catalog, "databaseApi", database);
        ReflectionTestUtils.setField(catalog, "monitorService", mock(AssetMonitorService.class));
        return catalog;
    }
}
