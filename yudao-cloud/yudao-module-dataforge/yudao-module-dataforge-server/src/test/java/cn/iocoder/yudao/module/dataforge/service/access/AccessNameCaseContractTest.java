package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.AccessTypeMappingPreviewReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessPreviewReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import jakarta.validation.ConstraintViolation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_PREVIEW_PARAM_MISSING;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_MAPPING_CONFIG_INVALID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 表名/字段名转换方式的 HTTP 契约、保存回显与类型预览目标授权；不连接真实数据库。 */
class AccessNameCaseContractTest {
    private final DataAccessServiceImpl service = new DataAccessServiceImpl();
    private final DataAccessMapper definitions = mock(DataAccessMapper.class);
    private final DatabaseQuerySupport permissions = mock(DatabaseQuerySupport.class);
    private final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
    private final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
    private final AtomicReference<DataAccessDO> stored = new AtomicReference<>();

    @BeforeEach void setup() {
        lenient().when(permissions.normalizeSelect(anyLong(), anyString())).thenAnswer(call -> call.getArgument(1));
        var databases = mock(DatabaseMapper.class);
        var source = DatabaseDO.builder().id(1L).name("source").databaseSchema("main").build();
        var target = DatabaseDO.builder().id(2L).name("target").databaseSchema("main").build();
        when(databases.selectById(1L)).thenReturn(source); when(databases.selectById(2L)).thenReturn(target);
        when(databases.selectByIds(anyCollection())).thenReturn(List.of(source, target));
        when(definitions.insert(any(DataAccessDO.class))).thenAnswer(invocation -> {
            DataAccessDO definition = invocation.getArgument(0); definition.setId(9007199254740993L); stored.set(definition); return 1;
        });
        when(definitions.selectById(anyLong())).thenAnswer(invocation -> stored.get());
        when(runtime.products()).thenReturn(DbSwitchProductRegistry.discover());
        when(resolver.resolve(anyLong(), any())).thenAnswer(invocation -> {
            var info = new AccessDatabaseResolver.ConnectionInfo();
            info.setDatabaseId(invocation.getArgument(0)); info.setSchema("main");
            info.setProductType(Long.valueOf(1).equals(invocation.getArgument(0)) ? ProductTypeEnum.SQLITE3 : ProductTypeEnum.POSTGRESQL);
            return info;
        });
        ReflectionTestUtils.setField(service, "dataAccessMapper", definitions);
        ReflectionTestUtils.setField(service, "databaseMapper", databases);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "databaseQuerySupport", permissions);
        ReflectionTestUtils.setField(service, "executionAccess", mock(ExecutionAccessService.class));
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(service, "deptApi", mock(cn.iocoder.yudao.module.system.api.dept.DeptApi.class));
        ReflectionTestUtils.setField(service, "adminUserApi", mock(cn.iocoder.yudao.module.system.api.user.AdminUserApi.class));
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void greenplumDistributionSurvivesSaveDetailAndRuntimeWithPhysicalNames() throws Exception {
        when(resolver.resolveProductType(any())).thenReturn(ProductTypeEnum.GREENPLUM);
        var request = saveRequest(); request.setTargetDistributedKeys(List.of("TenantId", "RecordId"));
        Long id = service.createDataAccess(request);
        var definition = stored.get().getDataAccessJson();
        assertEquals(request.getTargetDistributedKeys(), definition.getTarget().getDistributedKeys());
        var runtimeTarget = cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigMapper
                .toRuntime(definition, 2L).getTarget();
        assertEquals(request.getTargetDistributedKeys(), runtimeTarget.getDistributedKeys());
        var response = service.getDataAccess(id);
        var json = new tools.jackson.databind.ObjectMapper().valueToTree(response);
        assertEquals("TenantId", json.get("targetDistributedKeys").get(0).asText());
        assertEquals("RecordId", json.get("targetDistributedKeys").get(1).asText());
        service.createDataAccess(saveRequest());
        assertTrue(service.getDataAccess(id).getTargetDistributedKeys().isEmpty());
    }

    @Test void invalidDistributionIsRejectedBeforeSavingOrConnecting() throws Exception {
        when(resolver.resolveProductType(any())).thenReturn(ProductTypeEnum.POSTGRESQL);
        var request = saveRequest(); request.setTargetDistributedKeys(List.of("id"));
        assertThrows(ServiceException.class, () -> service.createDataAccess(request));
        when(resolver.resolveProductType(any())).thenReturn(ProductTypeEnum.GREENPLUM);
        request.setTargetDataSync(true); request.setChangeDataSyncType("PK_ORDER_COMPARE");
        assertThrows(ServiceException.class, () -> service.createDataAccess(request));
        request.setTargetDataSync(false); request.setTargetDistributedKeys(List.of("id", "id"));
        assertThrows(ServiceException.class, () -> service.createDataAccess(request));
        request.setTargetDistributedKeys(List.of("id")); request.setEngineType(3);
        assertThrows(ServiceException.class, () -> service.createDataAccess(request));
        verify(definitions, never()).insert(any(DataAccessDO.class));
        verify(runtime, never()).openConnection(any(), any(), any(), any(), any());
    }

    @Test void httpRequestsAcceptOnlyKnownModesAndLeaveThemUnsetForOldClients() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var save = saveRequest();
            var preview = new DataAccessPreviewReqVO(); preview.setSourceDatabaseId(1L);
            var typed = new AccessTypeMappingPreviewReqVO(); typed.setSourceDatabaseId(1L); typed.setTargetDatabaseId(2L);
            assertNull(save.getTargetTableNameCase()); assertNull(preview.getTargetColumnNameCase());
            for (String mode : List.of("NONE", "UPPER", "LOWER", "SNAKE", "CAMEL")) {
                save.setTargetTableNameCase(mode); save.setTargetColumnNameCase(mode);
                preview.setTargetTableNameCase(mode); preview.setTargetColumnNameCase(mode);
                typed.setTargetTableNameCase(mode); typed.setTargetColumnNameCase(mode);
                assertTrue(validator.validate(save).isEmpty()); assertTrue(validator.validate(preview).isEmpty());
                assertTrue(validator.validate(typed).isEmpty());
            }
            var expected = Set.of("targetTableNameCase", "targetColumnNameCase");
            for (String mode : List.of("snake", "KEBAB", "", " UPPER")) {
                save.setTargetTableNameCase(mode); save.setTargetColumnNameCase(mode);
                preview.setTargetTableNameCase(mode); preview.setTargetColumnNameCase(mode);
                typed.setTargetTableNameCase(mode); typed.setTargetColumnNameCase(mode);
                assertEquals(expected, paths(validator.validate(save)), mode);
                assertEquals(expected, paths(validator.validate(preview)), mode);
                assertEquals(expected, paths(validator.validate(typed)), mode);
            }
        }
    }

    @Test void saveStoresExplicitModesAndGetReturnsThemWhileOldClientsKeepTheLegacyFlags() {
        var request = saveRequest(); request.setTargetTableNameCase("SNAKE"); request.setTargetColumnNameCase("CAMEL");
        Long id = service.createDataAccess(request);
        var target = stored.get().getDataAccessJson().getTarget();
        assertEquals("SNAKE", target.getTableNameCase()); assertEquals("CAMEL", target.getColumnNameCase());
        assertFalse(target.isLowercase()); assertFalse(target.isUppercase());
        var response = service.getDataAccess(id);
        assertEquals("SNAKE", response.getTargetTableNameCase()); assertEquals("CAMEL", response.getTargetColumnNameCase());
        assertFalse(response.getTargetLowerCase()); assertFalse(response.getTargetUpperCase());

        var legacy = saveRequest(); legacy.setTargetUpperCase(true);
        service.createDataAccess(legacy);
        var legacyTarget = stored.get().getDataAccessJson().getTarget();
        assertNull(legacyTarget.getTableNameCase()); assertNull(legacyTarget.getColumnNameCase()); assertTrue(legacyTarget.isUppercase());
        var legacyResponse = service.getDataAccess(id);
        assertNull(legacyResponse.getTargetTableNameCase()); assertNull(legacyResponse.getTargetColumnNameCase());
        assertTrue(legacyResponse.getTargetUpperCase()); assertFalse(legacyResponse.getTargetLowerCase());
    }

    @Test void unknownModesOrBothLegacyFlagsAreRejectedBeforeAnyWrite() {
        var unknown = saveRequest(); unknown.setTargetColumnNameCase("kebab");
        assertEquals(DATA_ACCESS_PREVIEW_PARAM_MISSING.getCode(),
                assertThrows(ServiceException.class, () -> service.createDataAccess(unknown)).getCode());
        var both = saveRequest(); both.setTargetLowerCase(true); both.setTargetUpperCase(true);
        both.setTargetTableNameCase("NONE"); both.setTargetColumnNameCase("NONE");
        assertEquals(DATA_ACCESS_PREVIEW_PARAM_MISSING.getCode(),
                assertThrows(ServiceException.class, () -> service.createDataAccess(both)).getCode());
        verify(definitions, never()).insert(any(DataAccessDO.class));
    }

    @Test void typePreviewAuthorizesTheConvertedTargetAndRejectsUnknownModesBeforeConnecting() throws Exception {
        var request = AccessTypeMappingPermissionTest.request();
        request.setTargetTableNameCase("UPPER"); request.setTargetColumnNameCase("SNAKE"); request.setTargetLowerCase(true);
        doThrow(new IllegalStateException("stop after source binding"))
                .when(permissions).requireExactTables(eq(1L), any(), anyString(), eq(List.of("source")));
        assertThrows(RuntimeException.class, () -> service.previewTypeMap(request));
        verify(runtime, never()).openConnection(any(), any(), any(), any(), any());

        reset(permissions);
        var unknown = AccessTypeMappingPermissionTest.request(); unknown.setTargetTableNameCase("kebab");
        assertEquals(DATA_ACCESS_MAPPING_CONFIG_INVALID.getCode(),
                assertThrows(ServiceException.class, () -> service.previewTypeMap(unknown)).getCode());
        verifyNoInteractions(permissions);
        verify(runtime, never()).openConnection(any(), any(), any(), any(), any());
    }

    private static DataAccessSaveReqVO saveRequest() {
        var request = new DataAccessSaveReqVO(); request.setTaskName("name modes"); request.setEngineType(1); request.setTaskType(2);
        request.setSourceType(1); request.setSourceDatabaseId(1L); request.setTargetDatabaseId(2L); request.setAccessMode(2);
        request.setSourceSelectedTables(List.of("orders")); request.setTargetSyncExit(true); request.setMapperType("ALL");
        return request;
    }

    private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet());
    }
}
