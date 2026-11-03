package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.master.MasterDataController;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDataChangeDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.MASTER_MODEL_NOT_RELEASED;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.MASTER_MODEL_PUBLISH_UNCERTAIN;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterMaintenancePageTest {
    private MasterModelServiceImpl service;
    private MasterModelMapper models;
    private MasterDataChangeMapper changes;
    private MasterColumnMapper columns;
    private DatabaseApi databases;
    private MasterModelDO model;

    @BeforeEach
    void setUp() {
        service = new MasterModelServiceImpl();
        models = mock(MasterModelMapper.class);
        changes = mock(MasterDataChangeMapper.class);
        columns = mock(MasterColumnMapper.class);
        databases = mock(DatabaseApi.class);
        ReflectionTestUtils.setField(service, "modelMapper", models);
        ReflectionTestUtils.setField(service, "dataChangeMapper", changes);
        ReflectionTestUtils.setField(service, "columnMapper", columns);
        ReflectionTestUtils.setField(service, "domainMapper", mock(MasterDomainMapper.class));
        ReflectionTestUtils.setField(service, "codeRuleMapper", mock(MasterCodeRuleMapper.class));
        ReflectionTestUtils.setField(service, "adminUserApi", mock(AdminUserApi.class));
        ReflectionTestUtils.setField(service, "databaseApi", databases);
        model = MasterModelDO.builder().id(9007199254740993L).databaseId(20L).tableName("river_section")
                .tableCn("河段").status(MasterModelDO.STATUS_RELEASED).publishState(MasterModelDO.PUBLISH_READY).build();
        when(models.selectById(model.getId())).thenReturn(model);
        var source = new DatabaseConnectionRespDTO();
        source.setId(20L); source.setName("河段数据源"); source.setProductType("POSTGRESQL");
        source.setHost("database.example.test:5432"); source.setJdbcUrl("fixture-private-jdbc");
        source.setUsername("fixture-private-user"); source.setPassword("fixture-private-password");
        when(databases.getDatabaseList(null)).thenReturn(CommonResult.success(List.of(source)));
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L), new MockHttpServletRequest());
    }

    @AfterEach
    void cleanup() { SecurityContextHolder.clearContext(); }

    @Test
    void pageUsesMaintenanceScopeAndBatchedStatisticsWithoutExposingConnectionCredentials() {
        var req = new MasterModelPageReqVO();
        when(models.selectMaintenancePage(req)).thenReturn(new PageResult<>(List.of(model), 1L));
        when(columns.selectMaps(any())).thenReturn(List.of(Map.of("master_model_id", model.getId(), "field_count", 12L)));
        var applied = new MasterDataChangeDO(); applied.setModelId(model.getId());
        applied.setAppliedAt(LocalDateTime.of(2026, 10, 16, 12, 30));
        when(changes.selectLatestAppliedByModels(List.of(model.getId()))).thenReturn(List.of(applied));

        var result = service.getMaintenanceModelPage(req).getList().getFirst();

        assertEquals(12L, result.getFieldCount());
        assertEquals(applied.getAppliedAt(), result.getLastMaintenanceTime());
        assertEquals("POSTGRESQL", result.getDatabaseType());
        assertEquals("database.example.test:5432", result.getDatabaseHost());
        assertFalse(JsonUtils.toJsonString(result).contains("fixture-private"));
        assertNull(result.getColumns());
        verify(models, never()).selectPage(any(MasterModelPageReqVO.class));
        verify(changes).selectLatestAppliedByModels(List.of(model.getId()));
        verify(databases, never()).getDatabaseConnection(anyLong());
    }

    @Test
    void emptyPageDoesNotRunMetadataOrMaintenanceQueries() {
        var req = new MasterModelPageReqVO();
        when(models.selectMaintenancePage(req)).thenReturn(new PageResult<>(List.of(), 0L));
        assertTrue(service.getMaintenanceModelPage(req).getList().isEmpty());
        verifyNoInteractions(changes, columns, databases);
    }

    @Test
    void detailIncludesFieldCountAndLeavesNeverMaintainedTimeUnknown() {
        var field = new MasterColumnDO(); field.setName("id"); field.setPk(1); field.setFieldType(5);
        when(columns.selectListByModelId(model.getId())).thenReturn(List.of(field));
        var result = service.getMaintenanceModel(model.getId());
        assertEquals(1L, result.getFieldCount());
        assertEquals("id", result.getColumns().getFirst().getName());
        assertNull(result.getLastMaintenanceTime());
        assertEquals("POSTGRESQL", result.getDatabaseType());
    }

    @Test
    void draftAndOfflineModelsCannotBeReadOrCountedThroughMaintenanceEndpoints() {
        for (int status : List.of(MasterModelDO.STATUS_UNRELEASED, MasterModelDO.STATUS_OFFLINE)) {
            model.setStatus(status);
            assertServiceException(() -> service.getMaintenanceModel(model.getId()), MASTER_MODEL_NOT_RELEASED);
            assertServiceException(() -> service.getMaintenanceDataCount(model.getId()), MASTER_MODEL_NOT_RELEASED);
        }
        verifyNoInteractions(columns, changes, databases);
    }

    @Test
    void uncertainPublishCannotEnterTheMaintenanceDataFlow() {
        model.setPublishState(MasterModelDO.PUBLISH_UNKNOWN);
        assertServiceException(() -> service.getMaintenanceModel(model.getId()), MASTER_MODEL_PUBLISH_UNCERTAIN);
        assertServiceException(() -> service.getMaintenanceDataCount(model.getId()), MASTER_MODEL_PUBLISH_UNCERTAIN);
        verifyNoInteractions(columns, changes, databases);
    }

    @Test
    void countWithoutActorFailsBeforeModelOrDatasourceLookup() {
        SecurityContextHolder.clearContext();
        assertThrows(IllegalStateException.class, () -> service.getMaintenanceDataCount(model.getId()));
        verifyNoInteractions(models, databases);
    }

    @Test
    void actualMethodSecurityAllowsMaintenanceReaderWithoutModelAdministrationPermission() {
        var contract = mock(MasterModelService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("modelService", contract);
            context.getBeanFactory().registerSingleton("masterDataService", mock(MasterDataService.class));
            context.getBeanFactory().registerSingleton("changeService", mock(MasterDataChangeService.class));
            context.register(SecurityConfig.class); context.refresh();
            var security = context.getBean(SecurityFrameworkService.class);
            var controller = context.getBean(MasterDataController.class);
            var req = new MasterModelPageReqVO();
            assertThrows(AccessDeniedException.class, () -> controller.getMaintenanceModelPage(req));
            assertThrows(AccessDeniedException.class, () -> controller.getMaintenanceModel(1L));
            assertThrows(AccessDeniedException.class, () -> controller.getMaintenanceDataCount(1L));
            verifyNoInteractions(contract);
            when(security.hasPermission("governance:master-data:query")).thenReturn(true);
            assertNotNull(controller.getMaintenanceModelPage(req));
            assertNotNull(controller.getMaintenanceModel(1L));
            assertNotNull(controller.getMaintenanceDataCount(1L));
            verify(contract).getMaintenanceModelPage(req);
            verify(contract).getMaintenanceModel(1L);
            verify(contract).getMaintenanceDataCount(1L);
            verify(security, never()).hasPermission("governance:master-model:query");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean MasterDataController controller() { return new MasterDataController(); }
        @Bean("ss") SecurityFrameworkService security() { return mock(SecurityFrameworkService.class); }
    }
}
