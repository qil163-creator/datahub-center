package cn.iocoder.yudao.module.dataforge.api.database;

import cn.iocoder.yudao.framework.dbswitch.core.service.GuardedTableMutationExecutor;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseSimpleRespVO;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseListProductContractTest {
    private DatabaseApiImpl api;
    private DatabaseService databases;

    @BeforeEach
    void setUp() {
        api = new DatabaseApiImpl();
        databases = mock(DatabaseService.class);
        ReflectionTestUtils.setField(api, "databaseService", databases);
    }

    @Test
    void postgresDisplayNameDoesNotBreakMaintenanceCapability() {
        var source = source("POSTGRESQL", "PostgreSQL");
        when(databases.getSimpleDatabaseList(2)).thenReturn(List.of(source));

        var response = api.getDatabaseList(2).getCheckedData().getFirst();

        assertEquals("POSTGRESQL", response.getProductType());
        assertTrue(GuardedTableMutationExecutor.supports(ProductTypeEnum.valueOf(response.getProductType())));
        assertEquals(20L, response.getId());
        assertEquals("人员库", response.getName());
        assertEquals("public", response.getSchema());
        assertNull(response.getPassword());
        assertNull(response.getUsername());
        assertNull(response.getJdbcUrl());
        verify(databases).getSimpleDatabaseList(2);
    }

    @Test
    void renamedTypeCannotOverrideRegistryProductIdentity() {
        when(databases.getSimpleDatabaseList(null)).thenReturn(List.of(source("MYSQL", "PostgreSQL")));

        var response = api.getDatabaseList(null).getCheckedData().getFirst();

        assertEquals("MYSQL", response.getProductType());
        assertFalse(GuardedTableMutationExecutor.supports(ProductTypeEnum.valueOf(response.getProductType())));
    }

    @Test
    void unresolvedProductDoesNotAcquireCapabilityFromDisplayName() {
        when(databases.getSimpleDatabaseList(null)).thenReturn(List.of(source(null, "PostgreSQL")));

        assertNull(api.getDatabaseList(null).getCheckedData().getFirst().getProductType());
    }

    @Test
    void listPreservesExactTemplateIdentityAlongsideRegistryProduct() {
        var sid = source("ORACLE", "Oracle SID");
        sid.setDatabaseType(3L);
        sid.setCategoryCode("RELATIONAL");
        var service = source("ORACLE", "Oracle Service");
        service.setId(21L);
        service.setDatabaseType(9007199254740995L);
        service.setCategoryCode("RELATIONAL");
        when(databases.getSimpleDatabaseList(null)).thenReturn(List.of(sid, service));

        var list = api.getDatabaseList(null).getCheckedData();

        assertEquals(List.of(3L, 9007199254740995L), list.stream().map(item -> item.getDatabaseType()).toList());
        assertEquals(List.of("Oracle SID", "Oracle Service"), list.stream().map(item -> item.getDatabaseTypeName()).toList());
        assertTrue(list.stream().allMatch(item -> "RELATIONAL".equals(item.getCategoryCode())
                && "ORACLE".equals(item.getProductType())));
        assertTrue(list.stream().allMatch(item -> item.getPassword() == null
                && item.getUsername() == null && item.getJdbcUrl() == null));
    }

    private static DatabaseSimpleRespVO source(String productId, String typeName) {
        var source = new DatabaseSimpleRespVO();
        source.setId(20L);
        source.setName("人员库");
        source.setDatabaseSchema("public");
        source.setProductId(productId);
        source.setDatabaseTypeName(typeName);
        return source;
    }
}
