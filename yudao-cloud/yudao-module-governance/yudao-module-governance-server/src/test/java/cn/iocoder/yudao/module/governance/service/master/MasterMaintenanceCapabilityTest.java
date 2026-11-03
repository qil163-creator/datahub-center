package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterCodeRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDomainMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterMaintenanceCapabilityTest {
    private MasterModelServiceImpl service;
    private DatabaseApi databases;

    @BeforeEach
    void setUp() {
        service = new MasterModelServiceImpl();
        var models = mock(MasterModelMapper.class);
        databases = mock(DatabaseApi.class);
        ReflectionTestUtils.setField(service, "modelMapper", models);
        ReflectionTestUtils.setField(service, "domainMapper", mock(MasterDomainMapper.class));
        ReflectionTestUtils.setField(service, "codeRuleMapper", mock(MasterCodeRuleMapper.class));
        ReflectionTestUtils.setField(service, "databaseApi", databases);
        when(models.selectListByStatus(MasterModelDO.STATUS_RELEASED)).thenReturn(List.of(
                MasterModelDO.builder().id(100L).databaseId(20L).status(MasterModelDO.STATUS_RELEASED)
                        .publishState(MasterModelDO.PUBLISH_READY).build()));
    }

    @Test
    void canonicalPostgresEnablesMaintenanceWithoutFetchingCredentials() {
        sources("POSTGRESQL");

        var response = service.getReleasedModelList().getFirst();

        assertTrue(response.getMaintenanceSupported());
        assertNull(response.getMaintenanceUnsupportedReason());
        assertEquals("人员库", response.getDatabaseName());
        assertEquals(12, response.getQueryFilterOperators().size());
        assertTrue(response.getQueryFilterOperators().containsAll(List.of("CONTAINS", "GT", "IS_NULL", "IS_EMPTY")));
        verify(databases).getDatabaseList(null);
        verify(databases, never()).getDatabaseConnection(anyLong());
    }

    @Test
    void unverifiedProductsRemainUnsupportedIncludingPostgresFamily() {
        for (String product : List.of("MYSQL", "OPENGAUSS", "GREENPLUM")) {
            sources(product);

            var response = service.getReleasedModelList().getFirst();

            assertFalse(response.getMaintenanceSupported());
            assertTrue(response.getMaintenanceUnsupportedReason().contains(product));
            assertTrue(response.getMaintenanceUnsupportedReason().contains("PostgreSQL"));
        }
    }

    @Test
    void missingDatasourceIsDistinguishedFromUnsupportedProduct() {
        when(databases.getDatabaseList(null)).thenReturn(CommonResult.success(List.of()));

        var response = service.getReleasedModelList().getFirst();

        assertFalse(response.getMaintenanceSupported());
        assertTrue(response.getMaintenanceUnsupportedReason().contains("不存在或不可见"));
        assertTrue(response.getQueryFilterOperators().isEmpty());
    }

    @Test
    void missingAndInvalidProductIdentityCannotEnableMaintenance() {
        for (String product : new String[] { null, "", "PostgreSQL", "INVALID" }) {
            sources(product);

            var response = service.getReleasedModelList().getFirst();

            assertFalse(response.getMaintenanceSupported());
            assertTrue(response.getMaintenanceUnsupportedReason().contains("产品类型"));
        }
    }

    @Test
    void unavailableSourceListReportsRetryableReasonAndDoesNotExposeErrorDetails() {
        when(databases.getDatabaseList(null)).thenThrow(new IllegalStateException("internal fixture detail"));

        var response = service.getReleasedModelList().getFirst();

        assertFalse(response.getMaintenanceSupported());
        assertTrue(response.getMaintenanceUnsupportedReason().contains("刷新"));
        assertFalse(response.getMaintenanceUnsupportedReason().contains("fixture"));
        assertTrue(response.getQueryFilterOperators().isEmpty());
    }

    @Test
    void absentSourceListReportsUnavailableInsteadOfDeletedDatasource() {
        when(databases.getDatabaseList(null)).thenReturn(CommonResult.success(null));

        var response = service.getReleasedModelList().getFirst();

        assertFalse(response.getMaintenanceSupported());
        assertTrue(response.getMaintenanceUnsupportedReason().contains("刷新"));
    }

    private void sources(String product) {
        var source = new DatabaseConnectionRespDTO();
        source.setId(20L);
        source.setName("人员库");
        source.setProductType(product);
        when(databases.getDatabaseList(null)).thenReturn(CommonResult.success(List.of(source)));
    }
}
