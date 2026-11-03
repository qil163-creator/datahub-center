package cn.iocoder.yudao.module.supply.service.dataapi;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupplyDatabaseResolverTest {

    @Test
    void listKeepsExactTemplatesAndDoesNotExposeConnectionCredentials() {
        var sid = source(11L, 3L, "Oracle SID");
        var service = source(9007199254740993L, 9007199254740995L, "Oracle Service");
        var unresolved = new DatabaseConnectionRespDTO();
        unresolved.setId(13L);
        unresolved.setDatabaseType(99L);
        unresolved.setName("Missing type connection");
        var api = mock(DatabaseApi.class);
        when(api.getDatabaseList(null)).thenReturn(CommonResult.success(List.of(sid, service, unresolved)));
        var resolver = new SupplyDatabaseResolver();
        ReflectionTestUtils.setField(resolver, "databaseApi", api);

        var list = resolver.getDatabaseList();

        assertEquals(3, list.size());
        assertEquals(List.of(3L, 9007199254740995L, 99L), list.stream().map(item -> item.getDatabaseType()).toList());
        assertEquals(9007199254740993L, list.get(1).getId());
        assertEquals("Oracle SID", list.getFirst().getDatabaseTypeName());
        assertEquals("Oracle Service", list.get(1).getDatabaseTypeName());
        assertEquals("RELATIONAL", list.get(1).getCategoryCode());
        assertEquals("ORACLE", list.get(1).getProductType());
        assertEquals("/connection-icons/oracle.svg", list.get(1).getIconPath());
        assertNull(list.get(2).getDatabaseTypeName());
        assertNull(list.get(2).getCategoryCode());
        assertNull(list.get(2).getProductType());
        String json = JsonUtils.toJsonString(list);
        assertFalse(json.contains("fixture-password"));
        assertFalse(json.contains("jdbc:oracle"));
        assertFalse(json.contains("username"));
        verify(api).getDatabaseList(null);
        verifyNoMoreInteractions(api);
    }

    @Test
    void emptyProviderResultRemainsAnEmptyList() {
        var api = mock(DatabaseApi.class);
        when(api.getDatabaseList(null)).thenReturn(CommonResult.success(null));
        var resolver = new SupplyDatabaseResolver();
        ReflectionTestUtils.setField(resolver, "databaseApi", api);

        assertTrue(resolver.getDatabaseList().isEmpty());
    }

    private DatabaseConnectionRespDTO source(Long id, Long databaseType, String typeName) {
        var source = new DatabaseConnectionRespDTO();
        source.setId(id);
        source.setName(typeName + " connection");
        source.setDatabaseType(databaseType);
        source.setDatabaseTypeName(typeName);
        source.setCategoryCode("RELATIONAL");
        source.setProductType("ORACLE");
        source.setIconPath("/connection-icons/oracle.svg");
        source.setJdbcUrl("jdbc:oracle:thin:@fixture.invalid:1521:test");
        source.setUsername("fixture-user");
        source.setPassword("fixture-password");
        return source;
    }
}
