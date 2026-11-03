package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.DataApiSaveReqVO;
import cn.iocoder.yudao.module.dataforge.service.dataapi.DataApiClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EtlApiPortAdapterTest {
    @Test void ownConfigurationDrivesPagingColumnsAndHttpClient() {
        var client = mock(DataApiClient.class); var adapter = new EtlApiPortAdapter();
        ReflectionTestUtils.setField(adapter, "dataApiClient", client);
        var config = JSONUtil.parseObj("{\"apiName\":\"本节点接口\",\"requestUrl\":\"https://example.invalid/data\",\"requestMethod\":\"POST\",\"ifPage\":1,\"pageKey\":\"page\",\"pageSizeKey\":\"size\",\"dataColumns\":[{\"fieldName\":\"id\",\"fieldType\":4}]}");
        when(client.fetchRows(any(), eq(3), eq(25))).thenReturn(List.of(Map.of("id", 1)));
        assertTrue(adapter.isPaged(config));
        assertEquals("id", adapter.getColumns(config).getFirst().getName());
        assertEquals(1, adapter.fetchRows(config, 3, 25).size());
        var capture = ArgumentCaptor.forClass(DataApiSaveReqVO.class);
        verify(client).fetchRows(capture.capture(), eq(3), eq(25));
        assertEquals("https://example.invalid/data", capture.getValue().getRequestUrl());
        assertFalse(config.containsKey("id"));
        config.set("ifPage", 0);
        assertTrue(adapter.fetchRows(config, 2, 25).isEmpty());
        verifyNoMoreInteractions(client);
    }

    @Test void invalidRequestCannotReachHttpClient() {
        var client = mock(DataApiClient.class); var adapter = new EtlApiPortAdapter();
        ReflectionTestUtils.setField(adapter, "dataApiClient", client);
        assertThrows(IllegalStateException.class, () -> adapter.fetchRows(JSONUtil.parseObj("{}"), 1, 25));
        verifyNoInteractions(client);
    }
}
