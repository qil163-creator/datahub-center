package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessPreviewReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.framework.mybatis.core.type.AccessTaskConfigTypeHandler;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessNameMapping;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTableMapping;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigCodec;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigMapper;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_PREVIEW_PARAM_MISSING;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Public orchestration and persisted-config boundaries; no native database certification. */
class AccessSqlMappingContractTest {
    private static final String SQL = "SELECT amount, note FROM fixture_source";
    private final DataAccessServiceImpl service = new DataAccessServiceImpl();
    private final DataAccessMapper tasks = mock(DataAccessMapper.class);
    private final DatabaseMapper databases = mock(DatabaseMapper.class);
    private final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
    private final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
    private final MetaDataByJdbcServiceImpl metadata = mock(MetaDataByJdbcServiceImpl.class);
    private final DatabaseQuerySupport permissions = mock(DatabaseQuerySupport.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);

    @BeforeEach void setup() {
        lenient().when(permissions.normalizeSelect(anyLong(), anyString())).thenAnswer(call -> call.getArgument(1));
        ReflectionTestUtils.setField(service, "dataAccessMapper", tasks);
        ReflectionTestUtils.setField(service, "databaseMapper", databases);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(service, "databaseQuerySupport", permissions);
        ReflectionTestUtils.setField(service, "executionAccess", access);
        when(databases.selectById(1L)).thenReturn(DatabaseDO.builder().id(1L).name("fixture source").build());
        when(databases.selectById(2L)).thenReturn(DatabaseDO.builder().id(2L).name("fixture target").build());
        when(tasks.insert(any(DataAccessDO.class))).thenAnswer(invocation -> {
            DataAccessDO saved = invocation.getArgument(0); saved.setId(4L); return 1;
        });
        var connection = new AccessDatabaseResolver.ConnectionInfo();
        connection.setDatabaseId(1L); connection.setSchema("fixture_scope");
        connection.setProductType(ProductTypeEnum.POSTGRESQL); connection.setJdbcUrl("fixture-connection");
        when(resolver.resolve(eq(1L), any())).thenReturn(connection);
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(metadata);
        when(metadata.querySqlColumnMeta("fixture-connection", null, null, SQL)).thenReturn(columns());
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
        TenantContextHolder.setTenantId(33L);
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext(); TenantContextHolder.clear();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sqlSinglePreviewIsRejectedBeforeExternalMetadataEvenWithADeletingOverride(boolean deletingOverride) {
        var request = preview("SINGLE");
        if (deletingOverride) request.setConfigMap(deletingOverride());
        var failure = assertThrows(ServiceException.class, () -> service.previewColumnMap(request));
        assertEquals(DATA_ACCESS_PREVIEW_PARAM_MISSING.getCode(), failure.getCode());
        verifyNoInteractions(metadata, runtime, resolver, permissions);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sqlSingleSaveIsRejectedBeforeDatasourceReadsOrTaskPersistence(boolean deletingOverride) {
        var request = save("SINGLE");
        if (deletingOverride) request.setConfigMap(deletingOverride());
        var failure = assertThrows(ServiceException.class, () -> service.createDataAccess(request));
        assertEquals(DATA_ACCESS_PREVIEW_PARAM_MISSING.getCode(), failure.getCode());
        verifyNoInteractions(databases, tasks, metadata, runtime);
    }

    @Test void oldSqlSingleDefinitionsCannotBeWrittenDecodedMappedOrFrozenForExecution() throws Exception {
        var definition = definition("SINGLE");
        definition.getSource().getFirst().setConfigMap(deletingOverride());
        String raw = rawJson(definition);
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.write(definition));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.copy(definition));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(raw));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigMapper.toRuntime(definition, 2L));
        var saved = new DataAccessDO(); saved.setDataAccessJson(definition);
        assertThrows(IllegalArgumentException.class, () -> AccessRunSnapshot.freeze(saved));
        assertThrows(IllegalArgumentException.class, () -> AccessRunSnapshot.restore("{\"dataAccessJson\":" + raw + "}"));
        assertEquals("SINGLE", definition.getSource().getFirst().getMapperType());
        assertEquals("", definition.getSource().getFirst().getConfigMap().get(SQL).getRegexColumnMapper().getFirst().getToValue());
    }

    @ParameterizedTest @NullAndEmptySource
    void persistedSqlAllCannotDropAProjectedAliasAtTheCodecOrRuntimeBoundary(String replacement) throws Exception {
        var definition = definition("ALL");
        definition.getSource().getFirst().setRegexColumnMapper(List.of(new AccessNameMapping("^amount$", replacement)));
        String raw = rawJson(definition);
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.write(definition));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(raw));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigMapper.toRuntime(definition, 2L));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"ALL", " \t"})
    void legalSqlApiDefaultsAndAllPreserveTheSameGlobalAliasesThroughSaveFreezeAndExecution(String mode) {
        var request = save(mode); request.setConfigMap(deletingOverride());
        assertEquals(4L, service.createDataAccess(request));
        var captured = ArgumentCaptor.forClass(DataAccessDO.class); verify(tasks).insert(captured.capture());
        var saved = captured.getValue();
        assertEquals("ALL", saved.getDataAccessJson().getSource().getFirst().getMapperType());
        var restored = AccessRunSnapshot.restore(AccessRunSnapshot.freeze(saved));
        var execution = AccessTaskConfigMapper.toRuntime(restored.getDataAccessJson(), 2L);
        var mapped = MigrationColumnMappings.resolve(execution.getSource().getFirst(), execution.getTarget(),
                SQL, columns(), List.of());
        assertEquals(List.of("quantity", "note"), mapped.columns().stream().map(ColumnDescription::getFieldName).toList());
        assertEquals(List.of("quantity"), mapped.primaryKeys());
        var preview = preview(mode); preview.setConfigMap(deletingOverride());
        assertEquals(List.of("quantity", "note"), service.previewColumnMap(preview).stream().map(row -> row.getTargetName()).toList());
        verify(permissions).normalizeSelect(1L, SQL);
        assertEquals(mode, request.getMapperType());
        assertEquals("", request.getConfigMap().get(SQL).getRegexColumnMapper().getFirst().getToValue());
    }

    @Test void aPersistedSqlDefinitionWithoutAMapperFieldKeepsItsExistingAllDefault() {
        String raw = "{\"schemaVersion\":1,\"source\":[{\"sourceType\":2,\"sourceSql\":\"SELECT amount FROM fixture_source\"}],"
                + "\"target\":{\"targetTable\":\"fixture_target\"}}";
        var restored = AccessTaskConfigCodec.read(raw);
        assertEquals("ALL", restored.getSource().getFirst().getMapperType());
        assertEquals("ALL", AccessTaskConfigMapper.toRuntime(restored, 2L).getSource().getFirst().getMapperType());
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" \t"})
    void explicitNullOrBlankPersistedModesRetainStrictValidation(String mode) throws Exception {
        String raw = rawJson(definition(mode));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(raw));
    }

    @Test void tableSingleConfigurationsRemainSupportedThroughTheSameCodecAndRuntimeAdapter() {
        var definition = new AccessTaskConfig();
        var source = new AccessTaskConfig.Source(); source.setMapperType("SINGLE"); source.setSourceIncludes("alpha");
        var mapping = new AccessTableMapping(); mapping.setRegexColumnMapper(List.of(new AccessNameMapping("^amount$", "table_quantity")));
        source.setConfigMap(Map.of("alpha", mapping)); definition.setSource(List.of(source));
        var execution = AccessTaskConfigMapper.toRuntime(AccessTaskConfigCodec.copy(definition), 2L);
        var mapped = MigrationColumnMappings.resolve(execution.getSource().getFirst(), execution.getTarget(), "alpha", columns(), List.of());
        assertEquals(List.of("table_quantity", "note"), mapped.columns().stream().map(ColumnDescription::getFieldName).toList());
    }

    @Test void sqlSingleCannotReachJsonbParameterBinding() {
        var statement = mock(PreparedStatement.class);
        assertThrows(SQLException.class, () -> new AccessTaskConfigTypeHandler().setNonNullParameter(
                statement, 1, definition("SINGLE"), JdbcType.OTHER));
        verifyNoInteractions(statement);
    }

    private static DataAccessSaveReqVO save(String mode) {
        var request = new DataAccessSaveReqVO(); request.setTaskName("fixture SQL mapping");
        request.setEngineType(1); request.setTaskType(2); request.setAccessMode(2); request.setSourceType(2);
        request.setSourceDatabaseId(1L); request.setTargetDatabaseId(2L); request.setSourceSql(SQL); request.setTargetTable("fixture_target");
        request.setMapperType(mode); request.setColumnNameMapper(global()); request.setSourcePrimaryKeys(List.of("amount")); return request;
    }

    private static DataAccessPreviewReqVO preview(String mode) {
        var request = new DataAccessPreviewReqVO(); request.setSourceDatabaseId(1L); request.setSourceType(2);
        request.setSourceSql(SQL); request.setMapperType(mode); request.setColumnNameMapper(global()); return request;
    }

    private static AccessTaskConfig definition(String mode) {
        var config = new AccessTaskConfig(); var source = new AccessTaskConfig.Source();
        source.setSourceType(2); source.setSourceSql(SQL); source.setMapperType(mode); source.setRegexColumnMapper(global());
        source.setSourcePrimaryKeys(List.of("amount")); config.setSource(List.of(source)); config.getTarget().setTargetTable("fixture_target"); return config;
    }

    private static List<AccessNameMapping> global() { return List.of(new AccessNameMapping("^amount$", "quantity")); }

    private static Map<String, AccessTableMapping> deletingOverride() {
        var mapping = new AccessTableMapping(); mapping.setRegexColumnMapper(List.of(new AccessNameMapping("^amount$", "")));
        return Map.of(SQL, mapping);
    }

    private static List<ColumnDescription> columns() {
        return List.of(column("amount"), column("note"));
    }

    private static ColumnDescription column(String name) {
        var column = new ColumnDescription(); column.setFieldName(name); column.setLabelName(name); return column;
    }

    private static String rawJson(AccessTaskConfig definition) throws Exception {
        // Represents a pre-existing persisted definition; deliberately bypasses the validating writer.
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(definition);
    }
}
