package cn.iocoder.yudao.module.supply.api.app;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.supply.api.dataapi.SupplyDataApiApiImpl;
import cn.iocoder.yudao.module.supply.dal.dataobject.dataapi.DataApiDO;
import cn.iocoder.yudao.module.supply.service.dataapi.DataApiService;
import cn.iocoder.yudao.module.supply.service.dataapi.SupplyApiBindingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SupplyApiBindingContractTest {
    @Test
    void bindingExposesPublishedContractEvidenceRatherThanOnlyVersionLabel() {
        var service = mock(DataApiService.class);
        var api = new DataApiDO();
        api.setId(7L);
        api.setActiveReleaseId(70L);
        api.setContractHash("approved-contract");
        when(service.getPublishedDataApiEntity(7L)).thenReturn(api);
        var endpoint = new SupplyDataApiApiImpl();
        ReflectionTestUtils.setField(endpoint, "dataApiService", service);
        ReflectionTestUtils.setField(endpoint, "bindingService", new SupplyApiBindingService());
        var json = JsonUtils.toJsonString(endpoint.getApiBinding(7L).getCheckedData());
        assertThat(json).contains("\"contractHash\":\"approved-contract\"");
        verify(service).getPublishedDataApiEntity(7L);
        verify(service, never()).getDataApiEntity(anyLong());
    }
}
