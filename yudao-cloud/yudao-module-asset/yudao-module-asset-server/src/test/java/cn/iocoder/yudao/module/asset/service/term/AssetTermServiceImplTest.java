package cn.iocoder.yudao.module.asset.service.term;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.controller.admin.term.vo.AssetTermBindReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.term.AssetTermColumnRelDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.term.AssetTermColumnRelMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.module.governance.api.standardterm.StandardTermApi;
import cn.iocoder.yudao.module.governance.api.standardterm.dto.StandardTermRespDTO;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.List;
import java.util.stream.LongStream;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssetTermServiceImplTest extends BaseMockitoUnitTest {

    @InjectMocks private AssetTermServiceImpl service;
    @Mock private StandardTermApi standardTermApi;
    @Mock private AssetTermColumnRelMapper termColumnRelMapper;
    @Mock private DataAssetMapper dataAssetMapper;
    @Mock private AssetCatalogService catalogService;
    @Mock private MetadataApi metadataApi;
    @Mock private AssetTermBindingWriter bindingWriter;

    @Test
    void selectsGovernanceDefinitionsWithLosslessIds() {
        var term = term(9007199254740993L, (short) 0);
        when(standardTermApi.getEnabledTermList("客户")).thenReturn(CommonResult.success(List.of(term)));
        var result = service.getSimpleTermList("客户").getFirst();
        assertEquals(term.getId(), result.getId());
        assertEquals(term.getDefinition(), result.getDescription());
        assertEquals(0, result.getStatus());
    }

    @Test
    void bindsGovernanceIdOnlyAfterObjectAndColumnValidation() {
        var asset = asset();
        var request = request(9007199254740993L);
        when(dataAssetMapper.selectById(1L)).thenReturn(asset);
        when(standardTermApi.getTermList(List.of(request.getTermId())))
                .thenReturn(CommonResult.success(List.of(term(request.getTermId(), (short) 0))));
        var column = new MetadataColumnRespDTO();
        column.setId(3L);
        column.setColumnName("customer_id");
        when(metadataApi.getColumnList(2L)).thenReturn(CommonResult.success(List.of(column)));

        service.bindColumn(request);

        var order = inOrder(catalogService, standardTermApi, metadataApi, bindingWriter);
        order.verify(catalogService).assertCanMaintain(asset);
        order.verify(standardTermApi).getTermList(List.of(request.getTermId()));
        order.verify(metadataApi).getColumnList(2L);
        order.verify(bindingWriter).bind(asset, request, "customer_id");
    }

    @Test
    void rejectsUnknownAndDisabledGovernanceTermsBeforeWriting() {
        when(dataAssetMapper.selectById(1L)).thenReturn(asset());
        when(standardTermApi.getTermList(List.of(8L))).thenReturn(CommonResult.success(List.of()));
        assertEquals(ASSET_TERM_NOT_EXISTS.getCode(),
                assertThrows(ServiceException.class, () -> service.bindColumn(request(8L))).getCode());
        when(standardTermApi.getTermList(List.of(8L)))
                .thenReturn(CommonResult.success(List.of(term(8L, (short) 1))));
        assertEquals(ASSET_TERM_DISABLED.getCode(),
                assertThrows(ServiceException.class, () -> service.bindColumn(request(8L))).getCode());
        verifyNoInteractions(bindingWriter, metadataApi);
    }

    @Test
    void rejectsFieldFromAnotherAsset() {
        when(dataAssetMapper.selectById(1L)).thenReturn(asset());
        when(standardTermApi.getTermList(List.of(8L)))
                .thenReturn(CommonResult.success(List.of(term(8L, (short) 0))));
        when(metadataApi.getColumnList(2L)).thenReturn(CommonResult.success(List.of()));
        assertEquals(ASSET_TERM_COLUMN_INVALID.getCode(),
                assertThrows(ServiceException.class, () -> service.bindColumn(request(8L))).getCode());
        verifyNoInteractions(bindingWriter);
    }

    @Test
    void objectGuardProtectsUnbindAsWellAsBind() {
        var asset = asset();
        when(dataAssetMapper.selectById(1L)).thenReturn(asset);
        doThrow(new ServiceException(DATA_ASSET_FORBIDDEN)).when(catalogService).assertCanMaintain(asset);
        assertThrows(ServiceException.class, () -> service.bindColumn(request(null)));
        verifyNoInteractions(standardTermApi, metadataApi, bindingWriter);
    }

    @Test
    void unbindsEvenIfGovernanceOrMetadataIsUnavailable() {
        var asset = asset();
        var request = request(null);
        when(dataAssetMapper.selectById(1L)).thenReturn(asset);
        service.bindColumn(request);
        verify(bindingWriter).bind(asset, request, null);
        verifyNoInteractions(standardTermApi, metadataApi);
    }

    @Test
    void lookupFailureDoesNotCreateBinding() {
        when(dataAssetMapper.selectById(1L)).thenReturn(asset());
        when(standardTermApi.getTermList(List.of(8L))).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, () -> service.bindColumn(request(8L)));
        verifyNoInteractions(bindingWriter, metadataApi);
    }

    @Test
    void displaysDisabledTermsAndRetainsMissingReferenceForUnbind() {
        when(termColumnRelMapper.selectListByAssetId(1L)).thenReturn(List.of(
                AssetTermColumnRelDO.builder().metaColumnId(3L).standardTermId(8L).build(),
                AssetTermColumnRelDO.builder().metaColumnId(4L).standardTermId(9L).build()));
        when(standardTermApi.getTermList(List.of(8L, 9L)))
                .thenReturn(CommonResult.success(List.of(term(8L, (short) 1))));
        var result = service.getColumnTermMap(1L);
        assertEquals("客户编号", result.get(3L).getName());
        assertEquals(1, result.get(3L).getStatus());
        assertEquals(9L, result.get(4L).getId());
        assertTrue(result.get(4L).getName().contains("已删除或不可见"));
    }

    @Test
    void unavailableGovernanceDoesNotHideBindingOrBlockAssetDetails() {
        when(termColumnRelMapper.selectListByAssetId(1L)).thenReturn(List.of(
                AssetTermColumnRelDO.builder().metaColumnId(3L).standardTermId(8L).build()));
        when(standardTermApi.getTermList(List.of(8L))).thenThrow(new IllegalStateException("unavailable"));
        var result = service.getColumnTermMap(1L).get(3L);
        assertEquals(8L, result.getId());
        assertTrue(result.getName().contains("暂不可用"));
        assertNull(result.getStatus());
    }

    @Test
    void batchesWideTableLookupsAndSkipsEmptyRemoteRequests() {
        when(termColumnRelMapper.selectListByAssetId(1L)).thenReturn(List.of());
        assertTrue(service.getColumnTermMap(1L).isEmpty());
        verifyNoInteractions(standardTermApi);
        when(termColumnRelMapper.selectListByAssetId(1L)).thenReturn(LongStream.rangeClosed(1, 201)
                .mapToObj(id -> AssetTermColumnRelDO.builder().metaColumnId(id).standardTermId(id).build()).toList());
        when(standardTermApi.getTermList(anyList())).thenAnswer(call -> {
            List<Long> ids = call.getArgument(0);
            assertTrue(ids.size() <= 200);
            return CommonResult.success(ids.stream().map(id -> term(id, (short) 0)).toList());
        });
        assertEquals(201, service.getColumnTermMap(1L).size());
        verify(standardTermApi, times(2)).getTermList(anyList());
    }

    private static DataAssetDO asset() {
        return DataAssetDO.builder().id(1L).metaTableId(2L).build();
    }

    private static AssetTermBindReqVO request(Long termId) {
        var request = new AssetTermBindReqVO();
        request.setAssetId(1L);
        request.setMetaColumnId(3L);
        request.setTermId(termId);
        return request;
    }

    private static StandardTermRespDTO term(Long id, short status) {
        var term = new StandardTermRespDTO();
        term.setId(id);
        term.setName("客户编号");
        term.setDefinition("客户的唯一业务标识");
        term.setStatus(status);
        return term;
    }
}
