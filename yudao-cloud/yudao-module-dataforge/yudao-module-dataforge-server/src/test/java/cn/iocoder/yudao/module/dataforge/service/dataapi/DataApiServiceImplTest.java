package cn.iocoder.yudao.module.dataforge.service.dataapi;

import cn.iocoder.yudao.module.dataforge.controller.admin.dataapi.vo.DataApiSaveReqVO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataApiServiceImplTest {
    private final DataApiClient client = mock(DataApiClient.class);
    private final DataApiServiceImpl service = new DataApiServiceImpl();

    private DataApiSaveReqVO request(boolean paged) {
        ReflectionTestUtils.setField(service, "dataApiClient", client);
        var api = new DataApiSaveReqVO(); api.setApiName("独立接口"); api.setRequestMethod("GET");
        api.setRequestUrl("https://example.invalid/data"); api.setIfPage(paged ? 1 : 0);
        api.setPageKey("page"); api.setPageSizeKey("size");
        return api;
    }

    @Test void pagedTrialDelegatesFirstPageWithoutCreatingResource() {
        var api = request(true);
        when(client.executeApi(api, 1, 20)).thenReturn("{\"data\":[]}");
        assertEquals("{\"data\":[]}", service.testDataApi(api));
        verify(client).executeApi(api, 1, 20); verifyNoMoreInteractions(client);
    }

    @Test void fetchRetainsRowsAndInfersEditableFields() {
        var api = request(false); var rows = List.<Map<String, Object>>of(Map.of("id", "9007199254740993"));
        when(client.fetchRows(api, null, 20)).thenReturn(rows);
        var result = service.fetchData(api);
        assertEquals(rows, result.getRows()); assertEquals("id", result.getColumns().getFirst().getFieldName());
        assertEquals(Types.VARCHAR, result.getColumns().getFirst().getFieldType());
        assertEquals(false, result.getColumns().getFirst().getPk());
        verify(client).fetchRows(api, null, 20); verifyNoMoreInteractions(client);
    }

    @Test void emptyResultDoesNotInventResponseFields() {
        var api = request(true); when(client.fetchRows(api, 1, 20)).thenReturn(List.of());
        var result = service.fetchData(api); assertTrue(result.getRows().isEmpty()); assertTrue(result.getColumns().isEmpty());
        verify(client).fetchRows(api, 1, 20);
    }
}
