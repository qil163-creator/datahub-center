package cn.iocoder.yudao.module.asset.service.dataasset;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetRegistrationNamePreviewReqVO;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableRespDTO;
import cn.iocoder.yudao.module.modeling.api.registration.RegistrationContextApi;
import cn.iocoder.yudao.module.modeling.api.registration.dto.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssetRegistrationContextServiceTest {
    @Test void previewUsesProviderTableInsteadOfClientNamingSource() {
        var service=new AssetRegistrationContextService();var metadata=mock(MetadataApi.class);var provider=mock(RegistrationContextApi.class);
        ReflectionTestUtils.setField(service,"metadataApi",metadata);ReflectionTestUtils.setField(service,"contextApi",provider);
        ReflectionTestUtils.setField(service,"catalog",mock(AssetCatalogService.class));
        var table=new MetadataTableRespDTO();table.setId(7L);table.setPublishStatus(1);table.setTableName("ACTUAL_TABLE");table.setSourceComment("真实来源注释");
        when(metadata.getTable(7L)).thenReturn(CommonResult.success(table));when(provider.preview(any())).thenReturn(CommonResult.success(new RegistrationNameRespDTO()));
        var input=new AssetRegistrationNamePreviewReqVO();input.setCandidateId(7L);input.setDataDomainId(10L);input.setTableType("ODS");input.setNamingCase("UPPER");
        service.preview(input);var request=ArgumentCaptor.forClass(RegistrationNameReqDTO.class);verify(provider).preview(request.capture());
        assertEquals("ACTUAL_TABLE",request.getValue().getSourceTableName());assertEquals("真实来源注释",request.getValue().getSourceTableComment());
        table.setPublishStatus(0);assertThrows(RuntimeException.class,()->service.preview(input));verify(provider,times(1)).preview(any());
    }
}
