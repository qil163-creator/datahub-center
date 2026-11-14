package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.databind.NumberSerializer;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.query.QueryRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.governance.controller.admin.master.MasterModelController;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelDataCountRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterCodeRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDomainMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

import java.sql.SQLTimeoutException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterModelListTest {
    private MasterModelServiceImpl service;
    private MasterModelMapper models;
    private MasterColumnMapper columns;
    private AdminUserApi users;
    private MasterDatabaseResolver resolver;
    private DbSwitchRuntime runtime;
    private MasterModelDO model;

    @BeforeEach
    void setup() {
        service = new MasterModelServiceImpl();
        models = mock(MasterModelMapper.class);
        columns = mock(MasterColumnMapper.class);
        users = mock(AdminUserApi.class);
        resolver = mock(MasterDatabaseResolver.class);
        runtime = mock(DbSwitchRuntime.class);
        ReflectionTestUtils.setField(service, "modelMapper", models);
        ReflectionTestUtils.setField(service, "columnMapper", columns);
        ReflectionTestUtils.setField(service, "domainMapper", mock(MasterDomainMapper.class));
        ReflectionTestUtils.setField(service, "codeRuleMapper", mock(MasterCodeRuleMapper.class));
        ReflectionTestUtils.setField(service, "adminUserApi", users);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        DatabaseApi databaseApi = mock(DatabaseApi.class);
        when(databaseApi.getDatabaseList(null)).thenReturn(CommonResult.success(List.of()));
        ReflectionTestUtils.setField(service, "databaseApi", databaseApi);
        model = MasterModelDO.builder().id(100L).databaseId(20L).tableName("md_person").tableCn("人员")
                .status(0).publishState(MasterModelDO.PUBLISH_READY).build();
        model.setCreator("9007199254740993");
        when(models.selectById(100L)).thenReturn(model);
        TenantContextHolder.clear();
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L).setTenantId(1L), new MockHttpServletRequest());
    }

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void paginationAggregatesFieldsAndCreatorsWithoutRemoteDataQueries() {
        MasterModelDO legacy = MasterModelDO.builder().id(200L).tableName("md_legacy").build();
        legacy.setCreator("legacy-import");
        MasterModelPageReqVO request = new MasterModelPageReqVO();
        when(models.selectPage(request)).thenReturn(new PageResult<>(List.of(model, legacy), 2L));
        when(columns.selectMaps(any())).thenReturn(List.of(Map.of("master_model_id", 100L, "field_count", 12L)));
        AdminUserRespDTO user = new AdminUserRespDTO();
        user.setId(9007199254740993L); user.setNickname("模型创建人");
        when(users.getUserMap(anyCollection())).thenReturn(Map.of(user.getId(), user));

        var page = service.getModelPage(request);

        assertEquals(12L, page.getList().getFirst().getFieldCount());
        assertEquals("9007199254740993", page.getList().getFirst().getCreator());
        assertEquals("模型创建人", page.getList().getFirst().getCreatorName());
        assertEquals(0L, page.getList().getLast().getFieldCount());
        assertEquals("legacy-import", page.getList().getLast().getCreator());
        assertNull(page.getList().getLast().getCreatorName());
        assertNull(page.getList().getFirst().getColumns());
        verify(columns, times(1)).selectMaps(any());
        verify(users, times(1)).getUserMap(java.util.Set.of(9007199254740993L));
        verifyNoInteractions(resolver, runtime);
    }

    @Test
    void directoryFailureKeepsPageAndAuditIdentifierAvailable() {
        MasterModelPageReqVO request = new MasterModelPageReqVO();
        when(models.selectPage(request)).thenReturn(new PageResult<>(List.of(model), 1L));
        when(columns.selectMaps(any())).thenReturn(List.of());
        when(users.getUserMap(anyCollection())).thenThrow(new IllegalStateException("目录暂时不可用"));
        var response = service.getModelPage(request).getList().getFirst();
        assertEquals("9007199254740993", response.getCreator());
        assertNull(response.getCreatorName());
        assertEquals(0L, response.getFieldCount());
    }

    @Test
    void neverPublishedDraftHasKnownZeroWithoutOpeningDatasource() {
        var response = service.getDataCount(100L);
        assertTrue(response.isAvailable());
        assertEquals(0L, response.getDataCount());
        verifyNoInteractions(resolver, runtime);
    }

    @Test
    void missingUserFailsBeforeAnyLookupOrConnection() {
        SecurityContextHolder.clearContext();
        assertThrows(IllegalStateException.class, () -> service.getDataCount(100L));
        verify(models, never()).selectById(anyLong());
        verifyNoInteractions(resolver, runtime);
    }

    @Test
    void uncertainPublishCannotPretendThatItsDataCountIsZero() {
        model.setPublishState(MasterModelDO.PUBLISH_UNKNOWN);
        var response = service.getDataCount(100L);
        assertFalse(response.isAvailable());
        assertNull(response.getDataCount());
        assertNotNull(response.getMessage());
        verifyNoInteractions(resolver, runtime);
    }

    @Test
    void publishedThenModifiedDraftCountsTheExistingPhysicalTableLosslessly() throws Exception {
        model.setReleaseTime(LocalDateTime.of(2026, 10, 1, 12, 0));
        var operation = stubCount(List.of(List.of(9007199254740993L)));
        var response = service.getDataCount(100L);
        assertTrue(response.isAvailable());
        assertEquals(9007199254740993L, response.getDataCount());
        verify(operation).workerExited();
        verify(operation).awaitTermination();
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL", "DORIS"})
    void supportedProductCountsUseTheBoundedRuntimeAndProductDialect(ProductTypeEnum product) throws Exception {
        model.setStatus(MasterModelDO.STATUS_RELEASED);
        var operation = stubCount(product, List.of(List.of(9007199254740993L)));

        var response = service.getDataCount(100L);

        assertTrue(response.isAvailable());
        assertEquals(9007199254740993L, response.getDataCount());
        verify(operation).workerExited();
        verify(operation).awaitTermination();
    }

    @Test
    void timeoutReturnsUnknownAndDoesNotExposeDatasourceFailureDetails() throws Exception {
        model.setStatus(2);
        var operation = stubCount(List.of(List.of(1L)));
        doThrow(new SQLTimeoutException("fixture-private-url-and-credential")).when(operation).bindWorker();
        var response = service.getDataCount(100L);
        assertFalse(response.isAvailable());
        assertNull(response.getDataCount());
        assertFalse(response.getMessage().contains("fixture-private"));
        verify(operation).workerExited();
    }

    @Test
    void invalidOrOverflowingCountNeverBecomesZeroOrRounded() throws Exception {
        model.setStatus(1);
        stubCount(List.of(List.of("9223372036854775808")));
        var response = service.getDataCount(100L);
        assertFalse(response.isAvailable());
        assertNull(response.getDataCount());
    }

    @Test
    void responseWirePreservesLargeLongAndUnknownNull() throws Exception {
        var module = new SimpleModule().addSerializer(Long.class, NumberSerializer.INSTANCE);
        var mapper = JsonMapper.builder().addModule(module).build();
        var response = new MasterModelDataCountRespVO();
        response.setAvailable(true); response.setDataCount(9007199254740993L);
        var wire = mapper.readTree(mapper.writeValueAsString(response));
        assertTrue(wire.get("dataCount").isString());
        assertEquals("9007199254740993", wire.get("dataCount").asString());
        response.setAvailable(false); response.setDataCount(null);
        assertTrue(mapper.readTree(mapper.writeValueAsString(response)).get("dataCount").isNull());
    }

    @Test
    void actualMethodSecurityRequiresBothModelAndDataReadPermissions() {
        var contract = mock(MasterModelService.class);
        when(contract.getDataCount(100L)).thenReturn(new MasterModelDataCountRespVO());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("modelService", contract);
            context.register(SecurityConfig.class); context.refresh();
            var security = context.getBean(SecurityFrameworkService.class);
            var controller = context.getBean(MasterModelController.class);
            when(security.hasPermission("governance:master-model:query")).thenReturn(true);
            assertThrows(AccessDeniedException.class, () -> controller.getDataCount(100L));
            verifyNoInteractions(contract);
            when(security.hasPermission("governance:master-model:query")).thenReturn(false);
            when(security.hasPermission("governance:master-data:query")).thenReturn(true);
            assertThrows(AccessDeniedException.class, () -> controller.getDataCount(100L));
            verifyNoInteractions(contract);
            when(security.hasPermission("governance:master-model:query")).thenReturn(true);
            assertNotNull(controller.getDataCount(100L).getCheckedData());
            verify(contract).getDataCount(100L);
        }
    }

    private QueryRuntime.Operation stubCount(List<List<Object>> rows) throws Exception {
        return stubCount(ProductTypeEnum.POSTGRESQL, rows);
    }

    private QueryRuntime.Operation stubCount(ProductTypeEnum product, List<List<Object>> rows) throws Exception {
        var connection = new MasterDatabaseResolver.ConnectionInfo();
        connection.setProductType(product); connection.setSchema("master_schema");
        when(resolver.resolve(eq(20L), any())).thenReturn(connection);
        var spec = new QueryRuntime.ConnectionSpec(product, "jdbc:fixture://fixture.invalid/test", "fixture", "", null, null);
        when(resolver.queryConnectionSpec(connection)).thenReturn(spec);
        var queries = mock(QueryRuntime.class);
        var operation = mock(QueryRuntime.Operation.class);
        var session = mock(QueryRuntime.Session.class);
        var cursor = mock(QueryRuntime.Cursor.class);
        when(runtime.queries()).thenReturn(queries);
        when(queries.register(anyString(), anyString(), anyString(), any(), any())).thenReturn(operation);
        when(operation.openSession(eq(spec), any())).thenReturn(session);
        var dialect = DbSwitchProductRegistry.discover().metadata(product);
        when(session.dialect()).thenReturn(dialect);
        String countSql = dialect.getPageModel("SELECT 1 FROM "
                + product.quoteSchemaTableName("master_schema", "md_person")).getPageCountSql();
        when(session.query(anyString(), eq(countSql), eq(List.of()))).thenReturn(cursor);
        when(cursor.fetch(anyString(), any(), eq(1))).thenReturn(new QueryRuntime.Page(List.of(), rows, true));
        return operation;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean("ss") SecurityFrameworkService security() { return mock(SecurityFrameworkService.class); }
        @Bean MasterModelController controller() { return new MasterModelController(); }
    }
}
