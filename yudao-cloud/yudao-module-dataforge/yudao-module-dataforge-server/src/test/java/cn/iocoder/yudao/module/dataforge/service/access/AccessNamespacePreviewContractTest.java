package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.BasicTableMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.AccessTypeMappingPreviewReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessPreviewReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.databasetype.DatabaseTypeDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.databasetype.DatabaseTypeMapper;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTypeMappingAcknowledgement;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 实际数据源解析器到业务预览/保存的契约；元数据和权限为桩，不证明各产品真实读写能力。 */
class AccessNamespacePreviewContractTest {
    private final DataAccessServiceImpl service = new DataAccessServiceImpl();
    private final AccessDatabaseResolver resolver = new AccessDatabaseResolver();
    private final DatabaseMapper databases = mock(DatabaseMapper.class);
    private final DatabaseTypeMapper databaseTypes = mock(DatabaseTypeMapper.class);
    private final DatabaseQuerySupport permissions = mock(DatabaseQuerySupport.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
    private final MetaDataByJdbcServiceImpl metadata = mock(MetaDataByJdbcServiceImpl.class);
    private final DatabaseDO source = DatabaseDO.builder().id(1L).name("source").databaseType(1)
            .databaseName("business").jdbcUrl("fixture-source").usageType(1).build();
    private final DatabaseDO target = DatabaseDO.builder().id(9L).name("target").databaseType(9)
            .databaseName("ceshi").jdbcUrl("fixture-target").usageType(1).build();

    @BeforeEach void setup() {
        lenient().when(permissions.normalizeSelect(anyLong(), anyString())).thenAnswer(call -> call.getArgument(1));
        var products = DbSwitchProductRegistry.discover();
        ReflectionTestUtils.setField(resolver, "products", products);
        ReflectionTestUtils.setField(resolver, "databaseMapper", databases);
        ReflectionTestUtils.setField(resolver, "databaseTypeMapper", databaseTypes);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "databaseMapper", databases);
        ReflectionTestUtils.setField(service, "executionAccess", access);
        ReflectionTestUtils.setField(service, "databaseQuerySupport", permissions);
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        when(databases.selectById(1L)).thenReturn(source);
        when(databases.selectById(9L)).thenReturn(target);
        product(source, ProductTypeEnum.MYSQL);
        product(target, ProductTypeEnum.MYSQL);
        when(runtime.products()).thenReturn(products);
        when(runtime.jdbcMetadata(any())).thenReturn(metadata);
        var table = new TableDescription(); table.setTableName("orders");
        when(metadata.queryTableList(anyString(), isNull(), isNull(), anyString())).thenReturn(List.of(table));
        var column = new ColumnDescription(); column.setFieldName("id");
        when(metadata.queryTableColumnsAndKeys(anyString(), isNull(), isNull(), anyString(), eq("orders")))
                .thenReturn(new BasicTableMetadata(List.of(column), List.of("id")));
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void mysqlEmptySchemaUsesDatabaseForBothNamePreviewsAndTheirPermissions() {
        // 数据库创建契约会将 MySQL 空 schema 保存成空串；请求与故障日志相同。
        source.setDatabaseSchema(""); target.setDatabaseSchema("");
        assertEquals("orders", service.previewTableMap(request()).getFirst().getTargetName());
        assertEquals("id", service.previewColumnMap(request()).getFirst().getTargetName());
        verify(metadata).queryTableList("fixture-source", null, null, "business");
        verify(metadata).queryTableColumnsAndKeys("fixture-source", null, null, "business", "orders");
        verify(permissions, atLeastOnce()).requireExactTables(1L, ProductTypeEnum.MYSQL, "business", List.of("orders"));
        assertEquals("", source.getDatabaseSchema()); assertEquals("", target.getDatabaseSchema());
    }

    @Test void explicitSqlKeepsWorkingWithoutASeparateSourceSchema() {
        product(source, ProductTypeEnum.POSTGRESQL); source.setDatabaseSchema("");
        var request = request(); request.setSourceType(2); request.setSourceSql("SELECT id FROM public.orders");
        var column = new ColumnDescription(); column.setFieldName("id");
        when(metadata.querySqlColumnMeta("fixture-source", null, null, request.getSourceSql())).thenReturn(List.of(column));

        assertEquals("id", service.previewColumnMap(request).getFirst().getTargetName());
        verify(permissions).normalizeSelect(1L, "SELECT id FROM public.orders");
        verify(metadata).querySqlColumnMeta("fixture-source", null, null, request.getSourceSql());
        verify(metadata, never()).queryTableColumnsAndKeys(any(), any(), any(), any(), any());
    }

    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, mode = EnumSource.Mode.EXCLUDE, names = "UNKNOWN")
    void everyKnownSourceProductPreservesItsExplicitNamespace(ProductTypeEnum product) {
        product(source, product); source.setDatabaseSchema("exact_scope");
        assertEquals("orders", service.previewTableMap(request()).getFirst().getTargetName());
        verify(metadata).queryTableList("fixture-source", null, null, "exact_scope");
        verify(permissions, atLeastOnce()).requireExactTables(1L, product, "exact_scope", List.of("orders"));
    }

    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, mode = EnumSource.Mode.EXCLUDE, names = "UNKNOWN")
    void everyKnownTargetProductAssemblesTheExplicitNamespaceWithoutClaimingWriteSupport(ProductTypeEnum product) {
        product(target, product); target.setDatabaseSchema("exact_scope");
        MigrationRequest mapping = ReflectionTestUtils.invokeMethod(service, "namePreviewMapping", request());
        assertNotNull(mapping);
        assertEquals(product, mapping.getTarget().getTargetProductType());
        assertEquals("exact_scope", mapping.getTarget().getTargetSchema());
        verifyNoInteractions(metadata);
    }

    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {
            "MYSQL", "MARIADB", "TIDB", "GBASE8A", "DORIS", "STARROCKS", "CLICKHOUSE", "HIVE", "MONGODB", "TDENGINE"})
    void databaseNamespaceProductsUseTheirDatabaseWithoutDemandingSchema(ProductTypeEnum product) {
        product(source, product); product(target, product);
        assertEquals("orders", service.previewTableMap(withoutTarget()).getFirst().getTargetName());
        verify(metadata).queryTableList("fixture-source", null, null, "business");
        MigrationRequest mapping = ReflectionTestUtils.invokeMethod(service, "namePreviewMapping", request());
        assertNotNull(mapping); assertEquals("ceshi", mapping.getTarget().getTargetSchema());
    }

    @ParameterizedTest @CsvSource({"SQLITE3,main", "INFLUXDB3,iox", "ELASTICSEARCH,es"})
    void productsWithVerifiedDefaultNamespacesNeedNoInventedDatabase(ProductTypeEnum product, String namespace) {
        product(source, product); product(target, product);
        source.setDatabaseName(null); target.setDatabaseName(null);
        assertEquals("orders", service.previewTableMap(withoutTarget()).getFirst().getTargetName());
        verify(metadata).queryTableList("fixture-source", null, null, namespace);
        MigrationRequest mapping = ReflectionTestUtils.invokeMethod(service, "namePreviewMapping", request());
        assertNotNull(mapping); assertEquals(namespace, mapping.getTarget().getTargetSchema());
    }

    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"UNKNOWN", "SQLITE3", "INFLUXDB3", "ELASTICSEARCH"})
    void missingSourceNamespaceIsExplicitBeforePermissionsOrMetadataForAllPreviewKinds(ProductTypeEnum product) {
        product(source, product); source.setDatabaseName(null);
        assertMissingNamespace("源端", () -> service.previewTableMap(request()));
        var allTables = request(); allTables.setSourceSelectedTables(List.of());
        assertMissingNamespace("源端", () -> service.previewTableMap(allTables));
        assertMissingNamespace("源端", () -> service.previewColumnMap(request()));
        assertMissingNamespace("源端", () -> service.previewTypeMap(typeRequest()));
        verifyNoInteractions(permissions, metadata);
    }

    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"UNKNOWN", "SQLITE3", "INFLUXDB3", "ELASTICSEARCH"})
    void missingTargetNamespaceIsExplicitForAllPreviewKinds(ProductTypeEnum product) {
        product(target, product); target.setDatabaseName(null);
        assertMissingNamespace("目标", () -> service.previewTableMap(request()));
        assertMissingNamespace("目标", () -> service.previewColumnMap(request()));
        assertMissingNamespace("目标", () -> service.previewTypeMap(typeRequest()));
        verifyNoInteractions(metadata);
    }

    @ParameterizedTest @CsvSource({"1,源端", "9,目标"})
    void missingProductDefinitionReportsItsActualRole(long databaseId, String role) {
        when(databaseTypes.selectById(databaseId)).thenReturn(null);
        var tableError = assertThrows(ServiceException.class, () -> service.previewTableMap(request()));
        assertEquals(DATA_ACCESS_DATABASE_PRODUCT_MISSING.getCode(), tableError.getCode());
        assertTrue(tableError.getMessage().contains(role));
        var typeError = assertThrows(ServiceException.class, () -> service.previewTypeMap(typeRequest()));
        assertEquals(DATA_ACCESS_DATABASE_PRODUCT_MISSING.getCode(), typeError.getCode());
        assertTrue(typeError.getMessage().contains(role));
        verifyNoInteractions(metadata);
    }

    @Test void invalidMappingIsDistinctFromMissingNamespaceAndDoesNotExposeInput() {
        var request = request(); request.setTargetTableNameCase("sensitive-invalid-value");
        var error = assertThrows(ServiceException.class, () -> service.previewTableMap(request));
        assertEquals(DATA_ACCESS_MAPPING_CONFIG_INVALID.getCode(), error.getCode());
        assertFalse(error.getMessage().contains("sensitive-invalid-value"));
        verifyNoInteractions(metadata);
    }

    @Test void unsupportedTypePairIsDistinctFromMissingNamespace() {
        var error = assertThrows(ServiceException.class, () -> service.previewTypeMap(typeRequest()));
        assertEquals(DATA_ACCESS_TYPE_MAPPING_UNSUPPORTED.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("MYSQL"));
        verifyNoInteractions(metadata, permissions);
    }

    @Test void typePreviewUsesTheSameNamespaceBeforeOpeningSourceConnection() throws Exception {
        product(source, ProductTypeEnum.POSTGRESQL); source.setDatabaseSchema("public");
        doThrow(new IllegalStateException("stop after target authorization"))
                .when(permissions).requireExactTables(1L, ProductTypeEnum.POSTGRESQL, "public", List.of("orders"));
        assertThrows(IllegalStateException.class, () -> service.previewTypeMap(typeRequest()));
        verify(permissions, atLeastOnce()).requireExactTables(1L, ProductTypeEnum.POSTGRESQL, "public", List.of("orders"));
        verify(runtime, never()).openConnection(any(), any(), any(), any(), any());
    }

    @Test void savingAcknowledgementsUsesResolvedNamespaceAndStillRejectsWrongScope() {
        product(source, ProductTypeEnum.POSTGRESQL); source.setDatabaseSchema("public");
        var definitions = mock(DataAccessMapper.class);
        ReflectionTestUtils.setField(service, "dataAccessMapper", definitions);
        var request = new DataAccessSaveReqVO(); request.setTaskName("namespace acknowledgement");
        request.setEngineType(1); request.setTaskType(2); request.setSourceType(1); request.setAccessMode(2);
        request.setSourceDatabaseId(1L); request.setTargetDatabaseId(9L); request.setSourceSelectedTables(List.of("orders"));
        request.setTypeMappingAcknowledgements(List.of(decision("ceshi")));
        assertDoesNotThrow(() -> service.createDataAccess(request));
        verify(definitions).insert(any(DataAccessDO.class));
        request.setTypeMappingAcknowledgements(List.of(decision("wrong_database")));
        assertThrows(ServiceException.class, () -> service.createDataAccess(request));
        verify(definitions, times(1)).insert(any(DataAccessDO.class));
    }

    private void product(DatabaseDO database, ProductTypeEnum product) {
        when(databaseTypes.selectById(database.getDatabaseType().longValue())).thenReturn(
                DatabaseTypeDO.builder().id(database.getDatabaseType().longValue()).typeCode(product.name()).typeName(product.name()).build());
    }

    private static void assertMissingNamespace(String role, org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(ServiceException.class, action);
        assertEquals(DATA_ACCESS_DATABASE_NAMESPACE_MISSING.getCode(), error.getCode());
        assertTrue(error.getMessage().contains(role));
    }

    private static DataAccessPreviewReqVO request() {
        var request = new DataAccessPreviewReqVO(); configure(request); return request;
    }
    private static DataAccessPreviewReqVO withoutTarget() {
        var request = request(); request.setTargetDatabaseId(null); return request;
    }
    private static AccessTypeMappingPreviewReqVO typeRequest() {
        var request = new AccessTypeMappingPreviewReqVO(); configure(request); return request;
    }
    private static void configure(DataAccessPreviewReqVO request) {
        request.setSourceDatabaseId(1L); request.setTargetDatabaseId(9L); request.setSourceType(1);
        request.setSourceSelectedTables(List.of("orders")); request.setIncludeOrExclude(1);
        request.setPreviewTableName("orders"); request.setAccessMode(2); request.setMapperType("ALL");
        request.setTargetTableNameCase("NONE"); request.setTargetColumnNameCase("NONE");
    }
    private static AccessTypeMappingAcknowledgement decision(String namespace) {
        return new AccessTypeMappingAcknowledgement("MYSQL", namespace, "orders", "id", "a".repeat(64), List.of("TEST_LOSS"));
    }
}
