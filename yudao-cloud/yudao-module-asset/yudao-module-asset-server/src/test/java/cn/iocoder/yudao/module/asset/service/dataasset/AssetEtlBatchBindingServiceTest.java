package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetEtlBatchBindingReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.*;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.*;
import cn.iocoder.yudao.module.asset.service.security.AssetAccessGuard;
import cn.iocoder.yudao.module.dataforge.api.etl.EtlTargetBatchApi;
import cn.iocoder.yudao.module.dataforge.api.etl.dto.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssetEtlBatchBindingServiceTest {
    private final AssetEtlBatchBindingService service = new AssetEtlBatchBindingService();
    private final DataAssetMapper assets = mock(DataAssetMapper.class);
    private final AssetEtlBatchBindingMapper bindings = mock(AssetEtlBatchBindingMapper.class);
    private final AssetCatalogService catalog = mock(AssetCatalogService.class);
    private final AssetAccessGuard guard = mock(AssetAccessGuard.class);
    private final EtlTargetBatchApi api = mock(EtlTargetBatchApi.class);
    private final AssetEtlBatchBindingWriter writer = mock(AssetEtlBatchBindingWriter.class);
    private final DataAssetDO asset = DataAssetDO.builder().id(10L).assetType(1).datasourceId(20L).schemaName("dwd").tableName("orders").build();
    private EtlTargetBatchDescribeRespDTO description;
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "assets", assets); ReflectionTestUtils.setField(service, "bindings", bindings);
        ReflectionTestUtils.setField(service, "catalog", catalog); ReflectionTestUtils.setField(service, "guard", guard);
        ReflectionTestUtils.setField(service, "batches", api); ReflectionTestUtils.setField(service, "writer", writer);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L).setTenantId(7L), new MockHttpServletRequest());
        when(assets.selectById(10L)).thenReturn(asset);
        description = new EtlTargetBatchDescribeRespDTO(); description.setBatchId(30L); description.setRecordId(40L);
        description.setDatabaseId(20L); description.setSchemaName("dwd"); description.setTableName("orders");
        description.setPayloadHash("a".repeat(64)); description.setAvailable(true);
        when(api.describe(any())).thenAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return CommonResult.success(description); });
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void bindsOnlyExplicitAvailableBatchWithExactAssetIdentityBeforeLocalTransaction() {
        var req = request(); when(writer.append(eq(asset), isNull(), any())).thenReturn(0);
        assertEquals(0, service.bind(req));
        var capture = ArgumentCaptor.forClass(AssetEtlBatchBindingDO.class); verify(writer).append(eq(asset), isNull(), capture.capture());
        assertEquals(30L, capture.getValue().getBatchId()); assertEquals(99L, capture.getValue().getActorId());
        verify(catalog).assertCanMaintain(asset);
        description.setAvailable(false); assertThrows(RuntimeException.class, () -> service.bind(req));
        description.setAvailable(true); description.setSchemaName("another"); assertThrows(RuntimeException.class, () -> service.bind(req));
        verifyNoMoreInteractions(writer);
    }
    @Test void assetGuardAndSourceRpcBothRemainMandatory() {
        var binding = binding(); when(bindings.latest(10L)).thenReturn(binding);
        doThrow(new IllegalStateException("asset denied")).when(guard).assertPublishedReadable(asset, 99L);
        assertThrows(IllegalStateException.class, () -> service.get(10L));
        assertThrows(IllegalStateException.class, () -> service.rows(10L, 0, 0L, 20)); verifyNoInteractions(api);
        doNothing().when(guard).assertPublishedReadable(asset, 99L);
        when(api.read(any())).thenThrow(new IllegalStateException("source permission revoked"));
        assertThrows(IllegalStateException.class, () -> service.rows(10L, 0, 0L, 20));
        var request = ArgumentCaptor.forClass(EtlTargetBatchReadReqDTO.class); verify(api).read(request.capture());
        assertEquals(99L, request.getValue().getExecutionUserId()); assertEquals(30L, request.getValue().getBatchId());
        assertEquals("a".repeat(64), request.getValue().getExpectedPayloadHash()); assertEquals(20L, request.getValue().getExpectedDatabaseId());
        assertEquals("dwd", request.getValue().getExpectedSchemaName()); assertEquals("orders", request.getValue().getExpectedTableName());
        assertThrows(RuntimeException.class, () -> service.rows(10L, 1, 0L, 20)); verifyNoMoreInteractions(api);
    }
    @Test void wrongBatchAcknowledgementAndChangedPayloadAreRejected() {
        description.setBatchId(31L); assertThrows(RuntimeException.class, () -> service.bind(request())); verifyNoInteractions(writer);
        description.setBatchId(30L); when(bindings.latest(10L)).thenReturn(binding()); description.setPayloadHash("b".repeat(64));
        assertThrows(RuntimeException.class, () -> service.get(10L));
        description.setPayloadHash("a".repeat(64)); description.setTableName("wrong_target");
        assertThrows(RuntimeException.class, () -> service.get(10L));
    }
    @Test void historyUsesObjectGuardAndBoundedRevisionCursorWithoutRemoteAdmission() {
        var old = binding(); old.setRevision(0);
        var newer = binding(); newer.setRevision(1); newer.setBatchId(9007199254740993L);
        when(bindings.history(10L, null, 2)).thenReturn(java.util.List.of(newer, old));
        var page = service.history(10L, null, 1);
        assertTrue(page.hasMore()); assertEquals(1, page.nextBeforeRevision());
        assertEquals(9007199254740993L, page.list().getFirst().getBatchId());
        verify(guard).assertPublishedHistoryReadable(asset, 99L); verifyNoInteractions(api);
        when(bindings.history(10L, 1, 2)).thenReturn(java.util.List.of(old));
        var tail = service.history(10L, 1, 1);
        assertFalse(tail.hasMore()); assertNull(tail.nextBeforeRevision());
        doThrow(new IllegalStateException("asset denied")).when(guard).assertPublishedHistoryReadable(asset, 99L);
        assertThrows(IllegalStateException.class, () -> service.history(10L, null, 20));
        verify(bindings, never()).history(10L, null, 21);
        assertThrows(IllegalArgumentException.class, () -> service.history(10L, null, 51));
        assertThrows(IllegalArgumentException.class, () -> service.history(10L, -1, 20));
    }
    @Test void legacyPreviewDelegatesToBoundBatchAndNeverSilentlyAppliesExtraFilters() {
        var req = new cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPreviewReqVO(); req.setId(10L);
        assertNull(service.previewIfBound(req)); when(bindings.latest(10L)).thenReturn(binding());
        var filter = new cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPreviewReqVO.Filter(); filter.setColumn("id"); filter.setOperator("eq"); filter.setValue("1");
        req.setFilters(java.util.List.of(filter)); assertThrows(RuntimeException.class, () -> service.previewIfBound(req)); verifyNoInteractions(api);
        var legacy = new DataAssetServiceImpl(); var bridge = mock(AssetEtlBatchBindingService.class);
        ReflectionTestUtils.setField(legacy, "etlBatchBindings", bridge);
        var response = new cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO(); when(bridge.previewIfBound(req)).thenReturn(response);
        assertSame(response, legacy.previewAsset(req));
    }
    private AssetEtlBatchBindingReqVO request() {
        var req = new AssetEtlBatchBindingReqVO(); req.setAssetId(10L); req.setBatchId(30L); req.setReason("关联经质量准入批次"); return req;
    }
    private AssetEtlBatchBindingDO binding() {
        var b = new AssetEtlBatchBindingDO(); b.setAssetId(10L); b.setBatchId(30L); b.setRecordId(40L); b.setPayloadHash("a".repeat(64));
        b.setDatabaseId(20L); b.setSchemaName("dwd"); b.setTableName("orders"); b.setActorId(88L); b.setRevision(0); return b;
    }
}
