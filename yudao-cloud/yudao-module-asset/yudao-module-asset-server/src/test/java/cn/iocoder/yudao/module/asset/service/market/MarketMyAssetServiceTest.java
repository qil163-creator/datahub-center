package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.asset.controller.admin.market.MarketMyAssetController;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketResourceApplyDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.*;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApi;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyPublishedApiDocumentRespDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

class MarketMyAssetServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2040, 1, 1, 0, 0);
    private final MarketMyAssetServiceImpl service = new MarketMyAssetServiceImpl();
    private final MarketMyAssetMapper mine = mock(MarketMyAssetMapper.class);
    private final MarketResourceApplyMapper applies = mock(MarketResourceApplyMapper.class);
    private final DataAssetMapper assets = mock(DataAssetMapper.class);
    private final MarketDeliveryService delivery = mock(MarketDeliveryService.class);
    private final MarketAccessService access = mock(MarketAccessService.class);
    private final SupplyDataApiApi supply = mock(SupplyDataApiApi.class);
    private final MarketCheckProcessMapper processes = mock(MarketCheckProcessMapper.class);

    @BeforeEach
    void configure() {
        login(9L);
        Map.ofEntries(Map.entry("myAssetMapper", mine), Map.entry("applyMapper", applies),
                Map.entry("assetMapper", assets), Map.entry("deliveryService", delivery),
                Map.entry("accessService", access), Map.entry("supplyDataApiApi", supply),
                Map.entry("processMapper", processes), Map.entry("categoryService", mock(MarketCategoryService.class)),
                Map.entry("deliveryMapper", mock(MarketDeliveryOperationMapper.class)),
                Map.entry("approvalMapper", mock(MarketApprovalEvidenceMapper.class)))
                .forEach((name, value) -> ReflectionTestUtils.setField(service, name, value));
        when(applies.databaseNow()).thenReturn(NOW);
        when(assets.selectByIds(anyCollection())).thenReturn(List.of(asset()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void eachEndpointUsesIndependentReadPermission() {
        for (var method : MarketMyAssetController.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(PreAuthorize.class).value())
                    .isEqualTo("@ss.hasPermission('asset:market:my-assets:query')");
        }
    }

    @Test
    void unauthenticatedAndForeignOwnerCannotReadOrLoadData() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.get(7L)).isInstanceOf(ServiceException.class);
        login(9L);
        var other = row(7L); other.setApplyUserId(10L);
        when(mine.selectOwned(9L, 7L)).thenReturn(other);
        assertThatThrownBy(() -> service.get(7L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.apiDocument(7L)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(supply, access, processes);
    }

    @Test
    void pageKeepsGrantIdentityAndUsesOneDatabaseClockWithoutRemoteCalls() {
        var one = row(7L); var two = row(8L); two.setPaused(1);
        var req = new MarketMyAssetPageReqVO(); req.setPageNo(2); req.setPageSize(1);
        when(mine.countOwned(9L, req, null)).thenReturn(2L);
        when(mine.selectOwnedPage(9L, req, null, 1L)).thenReturn(List.of(two));
        var page = service.getPage(req);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getList()).singleElement().satisfies(item -> {
            assertThat(item.getApplyId()).isEqualTo(8L);
            assertThat(item.getAccessStatus()).isEqualTo("PAUSED");
            assertThat(item.getEvaluatedAt()).isEqualTo(NOW);
            assertThat(item.getCanPreview()).isFalse();
        });
        verify(applies, times(1)).databaseNow();
        verifyNoInteractions(supply, access);
    }

    @Test
    void allEightStatesRespectExclusiveEndBoundaryAndUnsupportedFile() {
        var available = row(1L);
        var future = row(2L); future.setApplyStartTime(NOW.plusNanos(1));
        var expired = row(3L); expired.setApplyEndTime(NOW);
        var paused = row(4L); paused.setPaused(1);
        var revoked = row(5L); revoked.setStatus(6);
        var pending = row(6L);
        var unknown = row(7L);
        var file = row(8L); file.setDeliveryType(3);
        when(delivery.currentDeliveryResolution(eq(pending), isNull(), isNull())).thenReturn("PENDING");
        when(delivery.currentDeliveryResolution(eq(unknown), isNull(), isNull())).thenReturn("UNKNOWN");
        var req = new MarketMyAssetPageReqVO();
        var rows = List.of(available, future, expired, paused, revoked, pending, unknown, file);
        when(mine.countOwned(9L, req, null)).thenReturn(8L);
        when(mine.selectOwnedPage(9L, req, null, 0L)).thenReturn(rows);
        assertThat(service.getPage(req).getList()).extracting(item -> item.getAccessStatus())
                .containsExactly("AVAILABLE", "NOT_STARTED", "EXPIRED", "PAUSED", "REVOKED", "DELIVERY_PENDING", "DELIVERY_UNKNOWN", "UNAVAILABLE");
    }

    @Test
    void malformedApprovalSnapshotRemainsReadOnlyAndNeverExposesRawSnapshot() {
        var row = row(7L); row.setSnapshot("{\"privateProof\":\"must-not-leak\"}");
        when(mine.selectOwned(9L, 7L)).thenReturn(row);
        var result = service.get(7L);
        assertThat(result.getAccessStatus()).isEqualTo("UNAVAILABLE");
        assertThat(result.getApprovedColumns()).isEmpty();
        assertThat(JsonUtils.toJsonString(result)).doesNotContain("snapshot", "privateProof", "must-not-leak", "databaseId");
    }

    @Test
    void previewRetainsTheRequestedGrantRatherThanSelectingAnotherByAsset() {
        service.preview(7L, 20);
        verify(access).previewAuthorizedApply(7L, 20);
        verify(access, never()).previewAuthorizedAsset(anyLong(), anyInt());
        verifyNoInteractions(mine, supply);
    }

    @Test
    void documentFiltersInputAndOutputToThisGrantAndRechecksAfterRpc() {
        var row = apiRow(); var authorization = authorization(row);
        when(mine.selectOwned(9L, 7L)).thenReturn(row);
        when(delivery.isConfirmedApply(row)).thenReturn(true);
        when(access.requireQuerySnapshot(9L, 7L)).thenReturn(authorization);
        when(supply.getPublishedApiDocument(21L)).thenReturn(CommonResult.success(document()));
        var result = service.apiDocument(7L);
        assertThat(result.getRequestParams()).extracting(item -> item.getName()).containsExactly("Id");
        assertThat(result.getResponseFields()).extracting(item -> item.getName()).containsExactly("publicId");
        assertThat(JsonUtils.toJsonString(result)).doesNotContain("databaseId", "schemaName", "tableName", "contractHash", "outputFieldMapping", "secret");
        var order = inOrder(supply, access);
        order.verify(access).requireQuerySnapshot(9L, 7L);
        order.verify(supply).getPublishedApiDocument(21L);
        order.verify(access).assertStillCurrent(authorization);
    }

    @Test
    void unconfirmedDeliveryCannotFetchApiDocument() {
        var row = apiRow(); when(mine.selectOwned(9L, 7L)).thenReturn(row);
        assertThatThrownBy(() -> service.apiDocument(7L)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(access, supply);
    }

    @Test
    void changedApiReleaseOrPhysicalBindingCannotLeakDocument() {
        var row = apiRow(); var authorization = authorization(row);
        when(mine.selectOwned(9L, 7L)).thenReturn(row);
        when(delivery.isConfirmedApply(row)).thenReturn(true);
        when(access.requireQuerySnapshot(9L, 7L)).thenReturn(authorization);
        var changedRelease = document(); changedRelease.setActiveReleaseId(99L);
        when(supply.getPublishedApiDocument(21L)).thenReturn(CommonResult.success(changedRelease));
        assertThatThrownBy(() -> service.apiDocument(7L)).isInstanceOf(ServiceException.class);
        var changedBinding = document(); changedBinding.setSchemaName("another_schema");
        when(supply.getPublishedApiDocument(21L)).thenReturn(CommonResult.success(changedBinding));
        assertThatThrownBy(() -> service.apiDocument(7L)).isInstanceOf(ServiceException.class);
        verify(access, never()).assertStillCurrent(any());
    }

    @Test
    void revokedDuringDocumentRpcDiscardsTheResponse() {
        var row = apiRow(); var authorization = authorization(row);
        when(mine.selectOwned(9L, 7L)).thenReturn(row);
        when(delivery.isConfirmedApply(row)).thenReturn(true);
        when(access.requireQuerySnapshot(9L, 7L)).thenReturn(authorization);
        when(supply.getPublishedApiDocument(21L)).thenReturn(CommonResult.success(document()));
        doThrow(new ServiceException(MARKET_QUERY_AUTHORIZATION_CHANGED)).when(access).assertStillCurrent(authorization);
        assertThatThrownBy(() -> service.apiDocument(7L)).isInstanceOf(ServiceException.class)
                .extracting("code").isEqualTo(MARKET_QUERY_AUTHORIZATION_CHANGED.getCode());
    }

    private static MarketMyAssetRow row(long id) {
        var result = BeanUtils.toBean(MarketResourceApplyDO.builder().id(id).assetId(5L).applyUserId(9L)
                .status(2).ifAuth(1).paused(0).revision(1).sourceRevision(2L).snapshot("""
                    {"assetId":"5","assetVersion":"v1","catalogVersionId":"12","publishedSnapshotId":"11",
                     "databaseId":"4","schemaName":"public","tableName":"citizen","frozenColumns":["Id"]}
                    """).build(), MarketMyAssetRow.class);
        result.setAssetName("已批准名称"); result.setMarketName("集市名称"); result.setDeliveryType(1);
        result.setFrozenCatalogValid(true); return result;
    }

    private static MarketMyAssetRow apiRow() {
        var row = row(7L); row.setApiId(21L); row.setAppId(22L); row.setDeliveryType(2); return row;
    }

    private static DataAssetDO asset() {
        return DataAssetDO.builder().id(5L).status(1).assetType(1).datasourceId(4L).schemaName("public")
                .tableName("citizen").publishedVersion("v1").publishedCatalogVersionId(12L).publishedSnapshotId(11L).build();
    }

    private static MarketQuerySnapshot authorization(MarketMyAssetRow row) {
        var policy = new MarketQuerySnapshot.Policy(22L, 21L, "contract", "v1", "31", "", "", "");
        var grant = new MarketQuerySnapshot.Grant(7L, 2L, 1, row.getSnapshot(), policy, null, null);
        return new MarketQuerySnapshot(9L, 7L, 5L, List.of(grant), MarketFieldScope.effectiveScope(row), 4L, "public", "citizen", NOW);
    }

    private static SupplyPublishedApiDocumentRespDTO document() {
        var doc = new SupplyPublishedApiDocumentRespDTO(); doc.setId(21L); doc.setName("授权API");
        doc.setPath("/api/approved"); doc.setMethod("GET"); doc.setVersion("v1"); doc.setContractHash("contract");
        doc.setActiveReleaseId(31L); doc.setDatabaseId(4L); doc.setSchemaName("public"); doc.setTableName("citizen");
        doc.setFieldMappingVerified(true); doc.setOutputFieldMapping(Map.of("publicId", "Id", "secret", "Secret"));
        var allowed = new SupplyPublishedApiDocumentRespDTO.RequestParam(); allowed.setName("Id"); allowed.setType("string");
        var hidden = new SupplyPublishedApiDocumentRespDTO.RequestParam(); hidden.setName("Secret");
        doc.setRequestParams(List.of(allowed, hidden));
        var output = new SupplyPublishedApiDocumentRespDTO.ResponseField(); output.setName("publicId");
        var hiddenOutput = new SupplyPublishedApiDocumentRespDTO.ResponseField(); hiddenOutput.setName("secret");
        doc.setResponseFields(List.of(output, hiddenOutput)); return doc;
    }

    private static void login(long id) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(new LoginUser().setId(id), null, List.of()));
    }
}
