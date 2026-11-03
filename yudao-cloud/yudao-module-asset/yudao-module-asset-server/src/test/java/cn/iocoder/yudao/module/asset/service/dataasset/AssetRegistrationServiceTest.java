package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.*;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataFileRespDTO;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApi;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyPublishedApiDocumentRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssetRegistrationServiceTest {
    private final AssetRegistrationService service = new AssetRegistrationService();
    private final DataAssetService assetService = mock(DataAssetService.class);
    private final DataAssetMapper assets = mock(DataAssetMapper.class);
    private final MetadataApi metadata = mock(MetadataApi.class);
    private final SupplyDataApiApi supply = mock(SupplyDataApiApi.class);
    private final AssetRegistrationWriter writer = mock(AssetRegistrationWriter.class);
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "assetService", assetService);
        ReflectionTestUtils.setField(service, "assetMapper", assets);
        ReflectionTestUtils.setField(service, "metadataApi", metadata);
        ReflectionTestUtils.setField(service, "supplyApi", supply);
        ReflectionTestUtils.setField(service, "writer", writer);
        ReflectionTestUtils.setField(service, "config", mock(AssetRegistrationConfig.class));
        var options = new DataAssetFacetRespVO(); options.setCategories(List.of()); options.setSubjectDomains(List.of());
        when(assetService.getAssetFacets()).thenReturn(options);
    }
    @Test void realFileRegistrationRetainsLosslessIdentityAndDoesNotInventTableOrDelivery() {
        long id = 9007199254740993L;
        when(metadata.getCurrentFile(id)).thenReturn(CommonResult.success(file(id)));
        when(writer.persist(anyList())).thenReturn(List.of(11L));
        assertThat(service.register(request(3, id))).containsExactly(11L);
        @SuppressWarnings("unchecked") var captor = ArgumentCaptor.forClass(List.class);
        verify(writer).persist(captor.capture());
        var prepared = (AssetRegistrationWriter.Prepared) captor.getValue().getFirst();
        assertThat(prepared.asset().getMetaTableId()).isNull();
        assertThat(prepared.asset().getTableName()).isNull();
        assertThat(prepared.asset().getTechAvailable()).isZero();
        assertThat(prepared.asset().getResourceUrn()).endsWith(String.valueOf(id));
        assertThat(prepared.source().getSourceId()).isEqualTo(id);
        assertThat(prepared.source().getSourceName()).isEqualTo("rainfall.csv");
        assertThat(prepared.source().getFileType()).isEqualTo("CSV");
        verifyNoInteractions(supply);
    }
    @Test void candidateDisappearanceFailsBeforeAnyLocalWrite() {
        when(metadata.getCurrentFile(1L)).thenReturn(CommonResult.success(null));
        assertThatThrownBy(() -> service.register(request(3, 1L))).isInstanceOf(ServiceException.class);
        verifyNoInteractions(writer);
    }
    @Test void untrustedCategoryOrSubjectCannotBeSubmittedByGuessingIds() {
        var req = request(3, 1L); req.getItems().getFirst().setCategoryId(999L);
        assertThatThrownBy(() -> service.register(req)).isInstanceOf(ServiceException.class);
        req.getItems().getFirst().setCategoryId(null); req.getItems().getFirst().setSubjectDomainId(999L);
        assertThatThrownBy(() -> service.register(req)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(metadata, supply, writer);
    }
    @Test void apiMustResolveCurrentPublishedContractAndNeverReceivesClientPath() {
        var document = new SupplyPublishedApiDocumentRespDTO(); document.setId(2L); document.setName("真实接口"); document.setPath("/rainfall"); document.setActiveReleaseId(20L); document.setContractHash("verified");
        when(supply.getPublishedApiDocument(2L)).thenReturn(CommonResult.success(document));
        when(writer.persist(anyList())).thenReturn(List.of(12L));
        assertThat(service.register(request(2, 2L))).containsExactly(12L);
        verify(supply).getPublishedApiDocument(2L);
        document.setActiveReleaseId(null);
        assertThatThrownBy(() -> service.register(request(2, 2L))).isInstanceOf(ServiceException.class);
        verify(writer, times(1)).persist(anyList());
    }
    @Test void duplicateInputAndConcurrentDatabaseConflictAreBusinessFailures() {
        var req = request(3, 1L); req.setItems(List.of(req.getItems().getFirst(), req.getItems().getFirst()));
        when(metadata.getCurrentFile(1L)).thenReturn(CommonResult.success(file(1L)));
        assertThatThrownBy(() -> service.register(req)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(writer);
        when(writer.persist(anyList())).thenThrow(new DuplicateKeyException("concurrent"));
        assertThatThrownBy(() -> service.register(request(3, 1L))).isInstanceOf(ServiceException.class);
    }
    @Test void authenticatedScopeFailureStopsCandidateRpc() {
        when(assetService.getAssetFacets()).thenThrow(new ServiceException(1, "scope"));
        var req = new AssetRegistrationCandidatePageReqVO(); req.setAssetType(3);
        assertThatThrownBy(() -> service.candidates(req)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(metadata, supply, assets);
    }
    @Test void candidatePageMarksAlreadyRegisteredProviderObjects() {
        var req = new AssetRegistrationCandidatePageReqVO(); req.setAssetType(3); req.setPageNo(1); req.setPageSize(10);
        when(metadata.getCurrentFilePage(1, 10, null)).thenReturn(CommonResult.success(new PageResult<>(List.of(file(1L)), 1L)));
        when(assets.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(DataAssetDO.builder().resourceUrn("urn:datahub:governance:file:1").build()));
        var result = service.candidates(req);
        assertThat(result.getList().getFirst().getRegistered()).isTrue();
        assertThat(result.getList().getFirst().getFileName()).isEqualTo("rainfall.csv");
    }
    private static MetadataFileRespDTO file(long id) {
        var file = new MetadataFileRespDTO(); file.setId(id); file.setName("rainfall.csv"); file.setPath("/exports/rainfall.csv"); file.setFileType("CSV"); file.setSize(100L); file.setBizRevision(2); return file;
    }
    private static AssetGenericRegisterReqVO request(int type, long id) {
        var req = new AssetGenericRegisterReqVO(); req.setAssetType(type); var item = new AssetGenericRegisterReqVO.Item(); item.setCandidateId(id); item.setAssetName("降雨数据"); req.setItems(List.of(item)); return req;
    }
}
