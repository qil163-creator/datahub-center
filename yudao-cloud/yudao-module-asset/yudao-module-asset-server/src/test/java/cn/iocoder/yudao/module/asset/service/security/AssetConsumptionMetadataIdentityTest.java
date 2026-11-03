package cn.iocoder.yudao.module.asset.service.security;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPreviewReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataquery.vo.DataQueryExecuteReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketResourceApplyMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetEtlBatchBindingService;
import cn.iocoder.yudao.module.asset.service.dataasset.DataAssetServiceImpl;
import cn.iocoder.yudao.module.asset.service.dataquery.DataQueryServiceImpl;
import cn.iocoder.yudao.module.asset.service.market.MarketAccessServiceImpl;
import cn.iocoder.yudao.module.asset.service.monitor.AssetMonitorService;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.RestrictedSelectRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableRespDTO;
import cn.iocoder.yudao.module.governance.api.security.SafetySecurityApi;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_TECH_UNAVAILABLE;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实领域 Guard 与消费编排，证明旧身份不能借同名新对象或既有申请继续读数。 */
class AssetConsumptionMetadataIdentityTest {

    AssetIdentityFixture fixture;
    private MockedStatic<SecurityFrameworkUtils> security;

    @BeforeEach
    void setUp() {
        fixture = new AssetIdentityFixture();
        security = mockStatic(SecurityFrameworkUtils.class);
        security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(9L);
    }

    @AfterEach
    void clear() { security.close(); }

    @Test
    void unchangedIdentityAndNewUnfrozenColumnsAllowBothPreviewPaths() {
        var extra = AssetIdentityFixture.column(101L, "new_field");
        when(fixture.metadata.getColumnList(10L)).thenReturn(CommonResult.success(List.of(fixture.column, extra)));
        assertThat(fixture.catalog.previewAsset(request()).getRows()).containsExactly(Map.of("Id", 1));
        assertThat(fixture.market.previewAuthorizedAsset(5L, 20).getRows()).containsExactly(Map.of("Id", 1));
        verify(fixture.database, times(2)).previewTable(any());
    }

    @Test
    void confirmedDeletedTableBlocksOldAssetAndOldApplicationBeforeDataRead() {
        fixture.table.setObjectStatus(3);
        rejected(() -> fixture.catalog.previewAsset(request()));
        rejected(() -> fixture.market.previewAuthorizedAsset(5L, 20));
        rejected(() -> fixture.market.requireQuerySnapshot(9L, 7L));
        verifyNoInteractions(fixture.database);
    }

    @Test
    void sameNameNewTableIdentityDoesNotReplaceFrozenAsset() {
        fixture.table.setId(20L);
        fixture.table.setResourceUrn("urn:table:20");
        rejected(() -> fixture.catalog.previewAsset(request()));
        rejected(() -> fixture.market.requireQuerySnapshot(9L, 7L));
        verifyNoInteractions(fixture.database);
    }

    @Test
    void sameNameRecreatedFieldCannotBeReadUsingOldGrantOrOwnerBypass() {
        when(fixture.metadata.getColumnList(10L)).thenReturn(CommonResult.success(
                List.of(AssetIdentityFixture.column(200L, "Id"))));
        rejected(() -> fixture.catalog.previewAsset(request()));
        rejected(() -> fixture.market.previewAuthorizedAsset(5L, 20));
        fixture.asset.setTechLeaderUserId(9L);
        rejected(() -> fixture.access.assertPublishedReadable(fixture.asset, 9L));
        verifyNoInteractions(fixture.database);
    }

    @Test
    void pendingOrChangedFrozenFieldIsNotActiveIdentity() {
        fixture.column.setObjectStatus(2);
        rejected(() -> fixture.identities.assertCurrent(fixture.asset));
        fixture.column.setObjectStatus(1);
        fixture.column.setResourceUrn("urn:changed");
        rejected(() -> fixture.identities.assertCurrent(fixture.asset));
        fixture.column.setResourceUrn("urn:column:100");
        fixture.column.setColumnName("renamed");
        rejected(() -> fixture.identities.assertCurrent(fixture.asset));
    }

    @Test
    void missingOrForeignFrozenIdentityNeverFallsBackToName() {
        var frozen = AssetIdentityFixture.column(null, "Id");
        when(fixture.metadata.getColumnListBySnapshot(11L)).thenReturn(CommonResult.success(List.of(frozen)));
        rejected(() -> fixture.identities.assertCurrent(fixture.asset));
        frozen.setId(100L); frozen.setResourceUrn(null);
        rejected(() -> fixture.identities.assertCurrent(fixture.asset));
        frozen.setResourceUrn("urn:column:100"); frozen.setTableId(20L);
        rejected(() -> fixture.identities.assertCurrent(fixture.asset));
        when(fixture.metadata.getColumnListBySnapshot(11L)).thenReturn(CommonResult.success(List.of()));
        rejected(() -> fixture.identities.assertCurrent(fixture.asset));
        fixture.asset.setPublishedSnapshotId(null);
        rejected(() -> fixture.catalog.previewAsset(request()));
        verifyNoInteractions(fixture.database);
    }

    @Test
    void physicalBindingMismatchAndRpcFailureFailClosedWithoutDataRead() {
        fixture.table.setSchemaName("other");
        rejected(() -> fixture.catalog.previewAsset(request()));
        fixture.table.setSchemaName("public");
        var failure = new IllegalStateException("test metadata unavailable");
        when(fixture.metadata.getColumnListBySnapshot(11L)).thenThrow(failure);
        assertThatThrownBy(() -> fixture.catalog.previewAsset(request())).isSameAs(failure);
        assertThatThrownBy(() -> fixture.market.previewAuthorizedAsset(5L, 20)).isSameAs(failure);
        verifyNoInteractions(fixture.database);
    }

    @Test
    void fieldReplacementDuringPreviewDiscardsRowsAtFinalCheck() {
        when(fixture.database.previewTable(any())).thenAnswer(call -> {
            when(fixture.metadata.getColumnList(10L)).thenReturn(CommonResult.success(
                    List.of(AssetIdentityFixture.column(200L, "Id"))));
            return CommonResult.success(AssetIdentityFixture.preview());
        });
        assertThatThrownBy(() -> fixture.market.previewAuthorizedAsset(5L, 20)).isInstanceOf(ServiceException.class);
        verify(fixture.database).previewTable(any());
    }

    @Test
    void batchDeduplicatesWithinCallAndNeverCachesPositiveIdentityAcrossCalls() {
        fixture.identities.assertCurrent(List.of(fixture.asset, fixture.asset));
        verify(fixture.metadata).getTable(10L);
        verify(fixture.metadata).getColumnListBySnapshot(11L);
        verify(fixture.metadata).getColumnList(10L);
        fixture.table.setObjectStatus(3);
        rejected(() -> fixture.identities.assertCurrent(List.of(fixture.asset)));
        verify(fixture.metadata, times(2)).getTable(10L);
    }

    @Test
    void oldAndNewBindingsKeepPermissionIntersectionAndBlockSqlDataRead() {
        var query = query();
        fixture.asset.setTechLeaderUserId(9L);
        fixture.table.setObjectStatus(3);
        var fresh = DataAssetDO.builder().id(6L).assetType(1).metaTableId(20L)
                .resourceUrn("urn:table:20").publishedSnapshotId(21L).datasourceId(4L)
                .dbName("catalog").schemaName("public").tableName("citizen").techLeaderUserId(9L).build();
        when(fixture.assets.selectExactTableBindings(4L, "public", "citizen"))
                .thenReturn(List.of(fixture.asset, fresh));
        var request = new DataQueryExecuteReqVO(); request.setDatasourceId(4L); request.setSql("SELECT * FROM public.citizen");
        rejected(() -> query.executeQuery(request));
        verify(fixture.database, never()).executeQuery(any());
    }

    @Test
    void historyAuthorizationRemainsAvailableForRetiredTechnicalIdentity() {
        fixture.table.setObjectStatus(3);
        fixture.asset.setTechLeaderUserId(9L);
        assertThatCode(() -> fixture.access.assertPublishedHistoryReadable(fixture.asset, 9L)).doesNotThrowAnyException();
        verifyNoInteractions(fixture.metadata);
    }

    private DataQueryServiceImpl query() {
        var query = new DataQueryServiceImpl();
        ReflectionTestUtils.setField(query, "assetAccessGuard", fixture.access);
        ReflectionTestUtils.setField(query, "marketAccessService", fixture.market);
        ReflectionTestUtils.setField(query, "databaseApi", fixture.database);

        ReflectionTestUtils.setField(query, "safetySecurityApi", mock(SafetySecurityApi.class));
        var plan = new RestrictedSelectRespDTO(); plan.setSchemaName("public"); plan.setTableName("citizen");
        plan.setNormalizedSql("SELECT * FROM \"public\".\"citizen\""); plan.setWildcard(true);
        when(fixture.database.parseRestrictedSelect(any())).thenReturn(CommonResult.success(plan));
        return query;
    }

    private static DataAssetPreviewReqVO request() {
        var request = new DataAssetPreviewReqVO(); request.setId(5L); return request;
    }

    private static void rejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOf(ServiceException.class)
                .extracting(failure -> ((ServiceException) failure).getCode())
                .isEqualTo(DATA_ASSET_TECH_UNAVAILABLE.getCode());
    }
}

/** 只模拟跨模块公开 API；领域 Guard 和两个资产消费编排使用真实实现。 */
final class AssetIdentityFixture {
    final MetadataApi metadata = mock(MetadataApi.class);
    final DatabaseApi database = mock(DatabaseApi.class);
    final DataAssetMapper assets = mock(DataAssetMapper.class);
    final MarketResourceApplyMapper applies = mock(MarketResourceApplyMapper.class);
    final AssetMetadataIdentityGuard identities = new AssetMetadataIdentityGuard();
    final AssetAccessGuard access = new AssetAccessGuard();
    final MarketAccessServiceImpl market = new MarketAccessServiceImpl();
    final DataAssetServiceImpl catalog = new DataAssetServiceImpl();
    final DataAssetDO asset = DataAssetDO.builder().id(5L).assetType(1).metaTableId(10L)
            .resourceUrn("urn:table:10").datasourceId(4L).dbName("catalog").schemaName("public").tableName("citizen")
            .status(DataAssetDO.STATUS_PUBLISHED).publishedSnapshotId(11L).publishedCatalogVersionId(12L)
            .publishedVersion("v1").build();
    final MetadataTableRespDTO table = new MetadataTableRespDTO();
    final MetadataColumnRespDTO column = column(100L, "Id");

    AssetIdentityFixture() {
        table.setId(10L); table.setResourceUrn("urn:table:10"); table.setObjectStatus(1);
        table.setDatasourceId(4L); table.setDbName("catalog"); table.setSchemaName("public"); table.setTableName("citizen");
        when(metadata.getTable(10L)).thenReturn(CommonResult.success(table));
        when(metadata.getColumnList(10L)).thenReturn(CommonResult.success(List.of(column)));
        when(metadata.getColumnListBySnapshot(11L)).thenReturn(CommonResult.success(List.of(column(100L, "Id"))));
        ReflectionTestUtils.setField(identities, "metadataApi", metadata);
        var apply = MarketResourceApplyDO.builder().id(7L).assetId(5L).applyUserId(9L).revision(1).sourceRevision(2L)
                .status(2).ifAuth(1).paused(0).snapshot("""
                {"assetId":"5","assetVersion":"v1","catalogVersionId":"12","publishedSnapshotId":"11",
                 "databaseId":"4","schemaName":"public","tableName":"citizen","frozenColumns":["Id"]}
                """).build();
        when(assets.selectById(5L)).thenReturn(asset);
        when(assets.selectExactTableBindings(4L, "public", "citizen")).thenReturn(List.of(asset));
        when(applies.databaseNow()).thenAnswer(call -> LocalDateTime.now());
        when(applies.selectForAccess(7L, 9L)).thenReturn(apply);
        when(applies.selectEffective(eq(9L), eq(5L), any())).thenReturn(apply);
        when(applies.selectQuerySources(eq(5L), eq(9L), any(), eq(257))).thenReturn(List.of(apply));
        var permissions = mock(PermissionApi.class);
        when(permissions.hasAnyRoles(anyLong(), any())).thenReturn(CommonResult.success(false));
        when(permissions.hasAnyPermissions(anyLong(), any(), any())).thenReturn(CommonResult.success(false));
        ReflectionTestUtils.setField(access, "assetMapper", assets);
        ReflectionTestUtils.setField(access, "applyMapper", applies);
        ReflectionTestUtils.setField(access, "permissionApi", permissions);
        ReflectionTestUtils.setField(access, "metadataIdentityGuard", identities);
        ReflectionTestUtils.setField(market, "assetMapper", assets);
        ReflectionTestUtils.setField(market, "applyMapper", applies);
        ReflectionTestUtils.setField(market, "metadataIdentityGuard", identities);
        ReflectionTestUtils.setField(market, "databaseApi", database);
        ReflectionTestUtils.setField(market, "assetMonitorService", mock(AssetMonitorService.class));
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(market, "transactionManager", transactions);
        ReflectionTestUtils.setField(catalog, "assetMapper", assets);
        ReflectionTestUtils.setField(catalog, "assetAccessGuard", access);
        ReflectionTestUtils.setField(catalog, "marketAccessService", market);
        ReflectionTestUtils.setField(catalog, "catalogService", mock(AssetCatalogService.class));
        ReflectionTestUtils.setField(catalog, "etlBatchBindings", mock(AssetEtlBatchBindingService.class));
        ReflectionTestUtils.setField(catalog, "databaseApi", database);
        ReflectionTestUtils.setField(catalog, "monitorService", mock(AssetMonitorService.class));
        when(database.previewTable(any())).thenReturn(CommonResult.success(preview()));
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                MarketResourceApplyDO.class);
    }

    static MetadataColumnRespDTO column(Long id, String name) {
        var result = new MetadataColumnRespDTO(); result.setId(id); result.setTableId(10L);
        result.setResourceUrn("urn:column:" + id); result.setColumnName(name); result.setObjectStatus(1); return result;
    }

    static DatabasePreviewRespDTO preview() {
        var preview = new DatabasePreviewRespDTO();
        preview.setColumns(List.of(new DatabasePreviewRespDTO.Column("Id", "编号", "bigint")));
        preview.setRows(List.of(Map.of("Id", 1))); return preview;
    }
}
