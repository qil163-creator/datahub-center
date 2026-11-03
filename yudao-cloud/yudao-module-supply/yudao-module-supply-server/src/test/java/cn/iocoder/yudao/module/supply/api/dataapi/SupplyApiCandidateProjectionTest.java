package cn.iocoder.yudao.module.supply.api.dataapi;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.supply.api.dataapi.dto.SupplyPublishedApiDocumentRespDTO;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.dal.mysql.dataapi.DataApiMapper;
import cn.iocoder.yudao.module.supply.service.dataapi.SupplyPublishedApiDocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupplyApiCandidateProjectionTest {
    @Test void pageUsesPublishedProjectionInsteadOfMutableDraftAndNeverExposesExecutionConfiguration() {
        var mapper = mock(DataApiMapper.class); var documents = mock(SupplyPublishedApiDocumentService.class); var api = new SupplyDataApiApiImpl();
        ReflectionTestUtils.setField(api, "apiMapper", mapper); ReflectionTestUtils.setField(api, "publishedDocumentService", documents);
        when(mapper.selectPage(any(PageParam.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(new PageResult<>(List.of(DataApiDO.builder().id(7L).name("未发布草稿名").build()), 1L));
        var document = new SupplyPublishedApiDocumentRespDTO(); document.setId(7L); document.setName("已发布名称"); document.setPath("/published"); document.setMethod("GET"); document.setActiveReleaseId(70L); document.setContractHash("contract"); document.setTableName("internal_table_marker");
        when(documents.getPublishedApiDocument(7L)).thenReturn(document);
        var result = api.getPublishedCandidatePage(1, 10, null).getCheckedData();
        assertThat(result.getList().getFirst().getName()).isEqualTo("已发布名称");
        assertThat(JsonUtils.toJsonString(result)).doesNotContain("internal_table_marker", "未发布草稿名", "requestParams", "outputFieldMapping");
        verify(documents).getPublishedApiDocument(7L);
    }
}
