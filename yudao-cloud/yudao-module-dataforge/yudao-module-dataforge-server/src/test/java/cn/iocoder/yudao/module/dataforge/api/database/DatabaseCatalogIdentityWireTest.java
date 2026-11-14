package cn.iocoder.yudao.module.dataforge.api.database;

import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryColumnsReqDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryTableRespDTO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.RestrictedSelectRespDTO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseCatalogIdentityWireTest {
    @Test void queryExecutionCarriesDorisGenerationAsAnOpaqueString() throws Exception {
        var request = new cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryReqDTO();
        request.setDatabaseId(7L); request.setSql("SELECT id FROM orders");
        request.setExpectedObjectId("a".repeat(64));
        var jackson2 = new com.fasterxml.jackson.databind.ObjectMapper();
        var jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();
        assertEquals(request, jackson2.readValue(jackson2.writeValueAsBytes(request), request.getClass()));
        assertEquals(request, jackson3.readValue(jackson3.writeValueAsBytes(request), request.getClass()));
    }
    @Test void nativeObjectIdsRemainDecimalStringsBeyondJavascriptSafeIntegerRange() throws Exception {
        String id = "9007199254740993";
        var query = new RestrictedSelectRespDTO(); query.setRelationObjectId(id);
        var table = new DatabaseQueryTableRespDTO(); table.setRelationObjectId(id);
        var columns = new DatabaseQueryColumnsReqDTO(); columns.setExpectedObjectId(id);
        var jackson2 = new com.fasterxml.jackson.databind.ObjectMapper();
        var jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();
        for (Object dto : new Object[]{query, table, columns}) {
            String property = dto == columns ? "expectedObjectId" : "relationObjectId";
            byte[] v2 = jackson2.writeValueAsBytes(dto), v3 = jackson3.writeValueAsBytes(dto);
            assertTrue(jackson2.readTree(v2).get(property).isTextual());
            assertTrue(jackson3.readTree(v3).get(property).isString());
            assertEquals(dto, jackson2.readValue(v2, dto.getClass()));
            assertEquals(dto, jackson3.readValue(v3, dto.getClass()));
        }
    }

    @Test void existingProductsAndOlderPayloadsRetainAnAbsentOptionalObjectId() throws Exception {
        var jackson2 = new com.fasterxml.jackson.databind.ObjectMapper();
        var jackson3 = tools.jackson.databind.json.JsonMapper.builder().build();
        for (var type : new Class<?>[]{RestrictedSelectRespDTO.class, DatabaseQueryTableRespDTO.class, DatabaseQueryColumnsReqDTO.class}) {
            Object v2 = jackson2.readValue("{\"schemaName\":\"public\",\"tableName\":\"orders\"}", type);
            Object v3 = jackson3.readValue("{\"schemaName\":\"public\",\"tableName\":\"orders\"}", type);
            assertEquals(v2, v3);
            String property = type == DatabaseQueryColumnsReqDTO.class ? "expectedObjectId" : "relationObjectId";
            assertTrue(jackson2.valueToTree(v2).get(property).isNull());
        }
    }
}
