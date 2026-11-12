package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDepartmentAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketDepartmentAssetRow;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 部门可见性、固定申请绑定和真实个人交付隔离，不使用伪造的本地授权。 */
class MarketDepartmentAssetServiceTest extends BaseMockitoUnitTest {
    @InjectMocks private MarketDepartmentAssetServiceImpl service;
    @Mock private MarketDepartmentScope departmentScope;
    @Mock private MarketDepartmentAssetMapper departmentAssetMapper;
    @Mock private MarketCategoryService categoryService;
    @Mock private DataAssetMapper assetMapper;
    @Mock private AssetCatalogService catalogService;
    @Mock private MarketMyAssetService myAssetService;
    @Mock private MarketObjectGuard objectGuard;
    @Mock private MarketSnapshotMaskSupport snapshotMasks;
    private final MarketDepartmentScope.Scope scope = new MarketDepartmentScope.Scope(9L, 100L, List.of(9L, 12L));

    @BeforeEach
    void scope() { when(departmentScope.current()).thenReturn(scope); }

    @Test
    void pageIncludesEveryApplicationStateAndAppliesLiveDepartmentBeforePagination() {
        var req = new MarketDepartmentAssetPageReqVO(); req.setPageNo(2); req.setPageSize(8);
        var rows = List.of(0, 1, 2, 3, 4, 5, 6, 8).stream().map(status -> {
            var row = nativeRow(status + 1L, 12L); row.setStatus(status); return row;
        }).toList();
        when(departmentAssetMapper.count(req, 100L, scope.userIds(), null)).thenReturn(16L);
        when(departmentAssetMapper.selectPage(req, 100L, scope.userIds(), null, 8L)).thenReturn(rows);
        var page = service.getPage(req);
        assertThat(page.getTotal()).isEqualTo(16);
        assertThat(page.getList()).extracting(MarketDepartmentAssetRespVO::getStatus).containsExactly(0, 1, 2, 3, 4, 5, 6, 8);
        assertThat(page.getList()).extracting(MarketDepartmentAssetRespVO::getStatusLabel)
                .containsExactly("草稿", "待授权", "已通过", "已驳回", "已撤销", "已过期", "已收回", "待补正");
        verifyNoInteractions(assetMapper, catalogService, myAssetService);
    }

    @Test
    void categoryFilterOnlyNarrowsAndMissingCategoryNeverCreatesAnEmptySqlInClause() {
        var req = new MarketDepartmentAssetPageReqVO(); req.setMarketCategoryId(88L);
        when(categoryService.descendants(88L)).thenReturn(Set.of());
        assertThat(service.getPage(req).getTotal()).isZero();
        verifyNoInteractions(departmentAssetMapper, myAssetService, assetMapper);
    }

    @Test
    void foreignOrStaleMapperResultIsRejectedBeforeMetadataOrAnyGrantCall() {
        var other = nativeRow(7, 13L);
        when(departmentAssetMapper.selectInDepartment(7L, 100L, scope.userIds())).thenReturn(other);
        assertThatThrownBy(() -> service.get(7L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.preview(7L, 20)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.apiDocument(7L)).isInstanceOf(ServiceException.class);
        var source = sourceRow(); source.setApplyDeptId(101L);
        when(departmentAssetMapper.selectInDepartment(source.getId(), 100L, scope.userIds())).thenReturn(source);
        assertThatThrownBy(() -> service.get(source.getId())).isInstanceOf(ServiceException.class);
        verifyNoInteractions(assetMapper, catalogService, myAssetService);
    }

    @Test
    void sourceDetailKeepsIndependentTwentySixteenThreeSixteenSectionsWithoutImportingAnOwnerOrGrant() {
        var row = sourceRow(); selected(row);
        var detail = service.get(row.getId());
        assertThat(detail.getAsset().getId()).isEqualTo(9007199254740993L);
        assertThat(detail.getAsset().getDisplayId()).isEqualTo("48");
        assertThat(detail.getAsset().getSourceMarketId()).isEqualTo("10");
        assertThat(detail.getAsset().getStatus()).isEqualTo(2);
        assertThat(detail.getResource().getSourceRecordId()).isEqualTo("10");
        assertThat(detail.getColumns()).hasSize(20);
        assertThat(detail.getColumns().getFirst().getCreatorName()).isEqualTo("测试维护人");
        assertThat(detail.getPreview().getColumns()).hasSize(16);
        assertThat(detail.getPreview().getRows()).hasSize(3);
        assertThat(detail.getApiDocument().getResponseFields()).hasSize(16);
        assertThat(detail.getCanPreview()).isTrue();
        assertThat(detail.getCanViewApiDocument()).isTrue();
        assertThat(detail.getCanInvoke()).isFalse();
        assertThat(JsonUtils.toJsonString(detail)).doesNotContain("must-not-leak", "sourceDetailSnapshot", "nativeApply", "sourceWorkOrderId", "sourceAssetExternalCode");
        // 资产只用于定位脱敏的物理来源，不引入当前资产内容或授权。
        verify(snapshotMasks).mask(any(), eq(detail.getPreview()), eq(9L));
        verifyAssetOnlyRead();
        verifyNoInteractions(myAssetService, catalogService);
    }

    @Test
    void sourcePreviewAndDocumentOnlyReadTheSelectedApplicationsCopiedBusinessData() {
        var row = sourceRow(); selected(row);
        var preview = service.preview(row.getId(), 1);
        assertThat(preview.getRows()).hasSize(1);
        assertThat(preview.getTotal()).isEqualTo("3");
        assertThat(preview.getTruncated()).isTrue();
        assertThat(service.preview(row.getId(), 20).getRows()).hasSize(3);
        assertThat(service.apiDocument(row.getId()).getPath()).isEqualTo("/documented/path");
        verify(snapshotMasks, times(2)).mask(any(), any(), eq(9L));
        verifyAssetOnlyRead();
        verifyNoInteractions(myAssetService, catalogService);
    }

    @Test
    void sourceMalformedOrMismatchedEvidenceFailsClosedWithoutTryingOtherApplications() {
        var row = sourceRow(); selected(row);
        row.setSourceAssetExternalCode("qdata:market:other");
        assertThatThrownBy(() -> service.get(row.getId())).isInstanceOf(ServiceException.class);
        row.setSourceAssetExternalCode("qdata:market:10"); row.setSourceDetailSnapshot("{broken");
        assertThatThrownBy(() -> service.get(row.getId())).isInstanceOf(ServiceException.class);
        row.setSourceDetailSnapshot("{\"resource\":{\"sourceRecordId\":\"999\"}}");
        assertThatThrownBy(() -> service.preview(row.getId(), 20)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.get(48L)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(myAssetService, assetMapper, catalogService);
    }

    @Test
    void nativeDepartmentPeerCanViewApplicationButCannotBorrowItsLiveDelivery() {
        var row = nativeRow(7, 12L); row.setStatus(2); selected(row);
        assertThat(service.get(7L).getCanPreview()).isFalse();
        assertThat(service.get(7L).getCanViewApiDocument()).isFalse();
        assertThatThrownBy(() -> service.preview(7L, 20)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.apiDocument(7L)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(myAssetService);
    }

    @Test
    void nativeOwnPreviewAndDocumentReuseExactExistingGrantIdentityAndRevalidation() {
        var row = nativeRow(7, 9L); row.setStatus(2); selected(row);
        when(objectGuard.hasPermission("asset:market:my-assets:query")).thenReturn(true);
        var own = new MarketMyAssetDetailRespVO(); own.setCanPreview(true); own.setCanViewApiDocument(true);
        when(myAssetService.get(7L)).thenReturn(own);
        assertThat(service.get(7L).getCanPreview()).isTrue();
        var live = new DatabasePreviewRespDTO(); live.setRows(List.of(Map.of("allowed", "1"))); live.setTruncated(false);
        when(myAssetService.preview(7L, 20)).thenReturn(live);
        assertThat(service.preview(7L, 20).getTotal()).isEqualTo("1");
        var document = new MarketMyAssetApiDocumentRespVO(); document.setName("本人已批准文档");
        document.setResponseFields(List.of(new MarketMyAssetApiDocumentRespVO.ResponseField()));
        when(myAssetService.apiDocument(7L)).thenReturn(document);
        assertThat(service.apiDocument(7L).getName()).isEqualTo("本人已批准文档");
        verify(myAssetService).preview(7L, 20);
        verify(myAssetService).apiDocument(7L);
    }

    @Test
    void nativeOwnReadPermissionCannotBeBypassedThroughTheDepartmentApplicationPage() {
        var row = nativeRow(7, 9L); row.setStatus(2); selected(row);
        assertThat(service.get(7L).getCanPreview()).isFalse();
        assertThat(service.get(7L).getCanViewApiDocument()).isFalse();
        assertThatThrownBy(() -> service.preview(7L, 20)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.apiDocument(7L)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(myAssetService);
    }

    private void selected(MarketDepartmentAssetRow row) {
        when(departmentAssetMapper.selectInDepartment(row.getId(), 100L, scope.userIds())).thenReturn(row);
    }

    private static MarketDepartmentAssetRow nativeRow(long id, Long owner) {
        var row = new MarketDepartmentAssetRow(); row.setId(id); row.setAssetId(16L); row.setApplyUserId(owner);
        row.setAssetName("已发布资产"); row.setMarketName("集市名称"); row.setDeliveryType(1);
        row.setCreateTime(LocalDateTime.of(2026, 10, 22, 22, 25)); row.setStatus(1); return row;
    }

    private static MarketDepartmentAssetRow sourceRow() {
        var row = nativeRow(9007199254740993L, null); row.setApplyDeptId(100L); row.setSourceWorkOrderId(77L);
        row.setSourceApplyId("48"); row.setSourceSystem("QDATA"); row.setSourceMarketId("10");
        row.setSourceAssetExternalCode("qdata:market:10"); row.setStatus(2); row.setIfAuth(0);
        var fields = IntStream.range(0, 20).mapToObj(index -> Map.of(
                "columnName", "FIELD_" + index, "dataType", "VARCHAR", "creatorName", "测试维护人")).toList();
        var previewColumns = IntStream.range(0, 16).mapToObj(index -> Map.of("name", "RIVER_" + index, "typeName", "VARCHAR")).toList();
        var responseFields = IntStream.range(0, 16).mapToObj(index -> Map.of("name", "RIVER_" + index, "type", "String")).toList();
        var document = new LinkedHashMap<String, Object>(); document.put("name", "业务文档"); document.put("path", "/documented/path");
        document.put("responseFields", responseFields); document.put("appSecret", "must-not-leak");
        row.setSourceDetailSnapshot(JsonUtils.toJsonString(Map.of(
                "resource", Map.of("sourceRecordId", "10", "assetName", "来源业务资产", "deliveryTypes", List.of(1, 2), "marketCategoryName", "来源类目"),
                "columns", fields, "preview", Map.of("total", "3", "columns", previewColumns,
                        "rows", List.of(Map.of("RIVER_0", "1"), Map.of("RIVER_0", "2"), Map.of("RIVER_0", "3")), "truncated", false),
                "apiDocument", document, "canInvoke", true, "ticket", Map.of("nativeApply", Map.of("phone", "must-not-leak")))));
        return row;
    }

    /** 快照路径只允许读取资产以定位物理来源。 */
    private void verifyAssetOnlyRead() {
        verify(assetMapper, atMost(3)).selectById(any());
        verifyNoMoreInteractions(assetMapper);
    }
}
