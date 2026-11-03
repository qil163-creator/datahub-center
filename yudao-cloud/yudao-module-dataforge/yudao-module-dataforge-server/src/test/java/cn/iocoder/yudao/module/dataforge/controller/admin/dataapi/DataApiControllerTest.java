package cn.iocoder.yudao.module.dataforge.controller.admin.dataapi;

import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.DataApiFetchRespVO;
import cn.iocoder.yudao.module.dataforge.service.dataapi.DataApiService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DataApiControllerTest {
    private final DataApiService service = mock(DataApiService.class);
    private final DataApiController controller = new DataApiController();
    private static final String CONFIG = "{\"apiName\":\"接口\",\"requestUrl\":\"https://example.invalid/data\",\"requestMethod\":\"GET\"}";

    @Test void retiredManagementEndpointsAreNotRouted() throws Exception {
        ReflectionTestUtils.setField(controller, "dataApiService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String path : new String[]{"get", "page", "list"})
            mvc.perform(get("/dataforge/data-api/" + path)).andExpect(status().isNotFound());
        mvc.perform(post("/dataforge/data-api/create").contentType(MediaType.APPLICATION_JSON).content(CONFIG)).andExpect(status().isNotFound());
        mvc.perform(put("/dataforge/data-api/update").contentType(MediaType.APPLICATION_JSON).content(CONFIG)).andExpect(status().isNotFound());
        mvc.perform(delete("/dataforge/data-api/delete").param("id", "1")).andExpect(status().isNotFound());
        mvc.perform(delete("/dataforge/data-api/delete-list").param("ids", "1")).andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test void sharedTrialAndFetchEndpointsKeepResultContract() throws Exception {
        ReflectionTestUtils.setField(controller, "dataApiService", service);
        when(service.testDataApi(any())).thenReturn("{\"ok\":true}");
        when(service.fetchData(any())).thenReturn(new DataApiFetchRespVO());
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/dataforge/data-api/test").contentType(MediaType.APPLICATION_JSON).content(CONFIG))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").value("{\"ok\":true}"));
        mvc.perform(post("/dataforge/data-api/fetch-data").contentType(MediaType.APPLICATION_JSON).content(CONFIG))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verify(service).testDataApi(any()); verify(service).fetchData(any());
    }
}
