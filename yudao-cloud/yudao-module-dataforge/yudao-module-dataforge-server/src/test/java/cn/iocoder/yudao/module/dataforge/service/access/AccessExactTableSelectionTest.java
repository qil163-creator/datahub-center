package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessNameMapping;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigMapper;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_PREVIEW_PARAM_MISSING;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Legacy CSV remains fail-closed; new literal names are authorized and frozen without CSV conversion. */
class AccessExactTableSelectionTest {
    static Stream<Arguments> invalidNames() {
        return Arrays.asList(null, "", " ", "a,b", "report?", "a*b", " leading", "trailing ",
                "nul\0name", "\u00a0name", "name\u3000").stream().map(value -> Arguments.of((Object) value));
    }

    static Stream<Arguments> invalidTypedNames() {
        return Arrays.asList(null, "", "nul\0name", "x".repeat(1025)).stream().map(value -> Arguments.of((Object) value));
    }

    @ParameterizedTest @MethodSource("invalidNames")
    void rawNamesAreRejectedWithoutTrimmingSplittingOrFiltering(String name) {
        assertThrows(IllegalArgumentException.class, () -> AccessExactTableSelection.requireName(name));
        assertThrows(IllegalArgumentException.class, () -> AccessExactTableSelection.encode(Arrays.asList(name)));
    }

    @Test void literalNamesRoundTripAndFrozenListIsIndependent() {
        var names = new ArrayList<>(List.of("用户%表", "quoted\"table", "inside space", "emoji😀"));
        var frozen = AccessExactTableSelection.requireNames(names);
        assertEquals(names, AccessExactTableSelection.decodeCsv(AccessExactTableSelection.encode(names)));
        names.clear(); assertEquals(4, frozen.size());
        assertThrows(UnsupportedOperationException.class, () -> frozen.add("new"));
        assertThrows(IllegalArgumentException.class, () -> AccessExactTableSelection.requireNames(null));
        assertEquals(List.of(), AccessExactTableSelection.decodeCsv(null));
        assertEquals(List.of(), AccessExactTableSelection.decodeCsv(""));
    }

    @Test void legacyCsvCannotDropEmptyItemsOrExpandPatterns() {
        for (String csv : List.of("safe,", ",safe", "safe,,other", " safe", "safe ,other", "*", "report?", "safe,nul\0name")) {
            assertThrows(IllegalArgumentException.class, () -> AccessExactTableSelection.decodeCsv(csv), csv);
        }
    }

    @ParameterizedTest @MethodSource("invalidTypedNames")
    void tableSaveRejectsRawInvalidSelectionBeforeDatabaseLookups(String name) {
        for (int mode : List.of(0, 1)) {
            var service = new DataAccessServiceImpl();
            var databases = mock(DatabaseMapper.class); var tasks = mock(DataAccessMapper.class);
            ReflectionTestUtils.setField(service, "databaseMapper", databases);
            ReflectionTestUtils.setField(service, "dataAccessMapper", tasks);
            var request = saveRequest(); request.setIncludeOrExclude(mode);
            request.setSourceSelectedTables(Arrays.asList(name));
            var error = assertThrows(ServiceException.class, () -> service.createDataAccess(request));
            assertEquals(DATA_ACCESS_PREVIEW_PARAM_MISSING.getCode(), error.getCode());
            verifyNoInteractions(databases, tasks);
        }
    }

    @Test void tableSavePreservesLiteralListAndMissingSelectionStillMeansAll() {
        var service = new DataAccessServiceImpl(); var databases = mock(DatabaseMapper.class);
        ReflectionTestUtils.setField(service, "databaseMapper", databases);
        when(databases.selectById(10L)).thenReturn(DatabaseDO.builder().id(10L).build());
        when(databases.selectById(20L)).thenReturn(DatabaseDO.builder().id(20L).build());
        var request = saveRequest(); request.setSourceSelectedTables(List.of("用户%表", "inside space"));
        DataAccessDO definition = ReflectionTestUtils.invokeMethod(service, "buildDataAccess", request);
        assertEquals(List.of("用户%表", "inside space"), definition.getDataAccessJson().getSource().getFirst().getSourceTableNames());
        assertEquals("", definition.getDataAccessJson().getSource().getFirst().getSourceIncludes());
        request.setSourceSelectedTables(null);
        definition = ReflectionTestUtils.invokeMethod(service, "buildDataAccess", request);
        assertEquals("", definition.getDataAccessJson().getSource().getFirst().getSourceIncludes());
        assertEquals(List.of(), definition.getDataAccessJson().getSource().getFirst().getSourceTableNames());
    }

    @Test void explicitSelectionUsesOnlyActiveCsvAndDoesNotInspectUnusedCatalog() {
        var fixture = new Fixture(); var task = task(1, "safe,safe,用户%表", "ignored*");
        assertEquals(List.of("safe", "用户%表"), fixture.resolve(task));
        verifyNoInteractions(fixture.resolver, fixture.runtime, fixture.metadata);
    }

    @Test void activeLegacyPatternsAndMalformedCsvFailBeforeAnyConnectionResolution() {
        for (int mode : List.of(0, 1)) {
            for (String csv : List.of("*", "report?", "safe,", " safe", "safe,,other", "nul\0name")) {
                var fixture = new Fixture();
                var task = task(mode, mode == 1 ? csv : "inactive*", mode == 0 ? csv : "inactive?");
                assertThrows(IllegalArgumentException.class, () -> fixture.resolve(task));
                verifyNoInteractions(fixture.resolver, fixture.runtime, fixture.metadata);
            }
        }
    }

    @Test void discoveryPreservesLiteralNamesAndIgnoresUnselectedViews() {
        for (int mode : List.of(0, 1)) {
            var fixture = new Fixture();
            when(fixture.metadata.queryTableList(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(List.of(table("safe"), table("unsafe,table")));
            assertEquals(List.of("safe", "unsafe,table"), fixture.resolve(task(mode, "", "excluded")));
        }
        var fixture = new Fixture(); var view = table("unused?view"); view.setTableType("VIEW");
        when(fixture.metadata.queryTableList(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(table("safe"), table("excluded"), view));
        assertEquals(List.of("safe"), fixture.resolve(task(0, "unused*", "excluded")));
    }

    @Test void emptyAllIsDiscoveredAuthorizedAndFrozenWithoutRestrictingTargetMapping() {
        var fixture = new Fixture(); var task = task(1, "", "unused*");
        var permissions = mock(DatabaseQuerySupport.class);
        ReflectionTestUtils.setField(fixture.executor, "databaseQuerySupport", permissions);
        task.getDataAccessJson().getSource().getFirst().setRegexTableMapper(List.of(new AccessNameMapping("^safe$", "target,*?")));
        when(fixture.metadata.queryTableList(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(table("safe"), table("用户%表")));
        fixture.executor.validateExecutionPermission(task);
        verify(permissions).requireExactTables(10L, ProductTypeEnum.POSTGRESQL, "public", List.of("safe", "用户%表"));
        assertEquals(1, task.getDataAccessJson().getSource().getFirst().getIncludeOrExclude());
        assertEquals(List.of("safe", "用户%表"), task.getDataAccessJson().getSource().getFirst().getSourceTableNames());
        assertEquals("", task.getDataAccessJson().getSource().getFirst().getSourceIncludes());
        assertEquals("", task.getDataAccessJson().getSource().getFirst().getSourceExcludes());
        verify(fixture.migrationAuthorization).requireTargetPermission(7L);
        verify(fixture.migrationAuthorization).requireSourceBinding(any(), eq(7L), eq("safe"));
    }

    @Test void typedPatternCharactersAndCommaAreAuthorizedVerbatimAndFrozenBeforeRuntime() {
        var fixture = new Fixture(); var task = task(1, "", "");
        var names = List.of("a,b", "report?", "a*b", " leading", "trailing\u3000");
        task.getDataAccessJson().getSource().getFirst().setSourceTableNames(names);
        var permissions = mock(DatabaseQuerySupport.class);
        ReflectionTestUtils.setField(fixture.executor, "databaseQuerySupport", permissions);
        fixture.executor.validateExecutionPermission(task);
        verify(permissions).requireExactTables(10L, ProductTypeEnum.POSTGRESQL, "public", names);
        assertEquals(names, task.getDataAccessJson().getSource().getFirst().getSourceTableNames());
        verifyNoInteractions(fixture.metadata);
        var restored = AccessRunSnapshot.restore(AccessRunSnapshot.freeze(task));
        var request = assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(DataAccessExecutor.class, "recoveryRequest", restored));
        assertNotNull(request);
    }

    @Test void exclusionDiscoveryFreezesOnlyActuallyAuthorizedPhysicalNames() {
        var fixture = new Fixture(); var task = task(0, "", "");
        task.getDataAccessJson().getSource().getFirst().setSourceTableNames(List.of("report?"));
        when(fixture.metadata.queryTableList(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(table("report?"), table("report1"), table("a,b"), table(" leading")));
        var permissions = mock(DatabaseQuerySupport.class);
        ReflectionTestUtils.setField(fixture.executor, "databaseQuerySupport", permissions);
        fixture.executor.validateExecutionPermission(task);
        var selected = List.of("report1", "a,b", " leading");
        verify(permissions).requireExactTables(10L, ProductTypeEnum.POSTGRESQL, "public", selected);
        assertEquals(1, task.getDataAccessJson().getSource().getFirst().getIncludeOrExclude());
        assertEquals(selected, task.getDataAccessJson().getSource().getFirst().getSourceTableNames());
    }

    @Test void sourceBindingAlsoRejectsActiveLegacyScopeButSqlKeepsItsContract() {
        var fixture = new Fixture(); var invalid = task(1, "*", "");
        SourceDataSourceProperties source = AccessTaskConfigMapper.toRuntime(invalid.getDataAccessJson(), 20L).getSource().getFirst();
        assertThrows(IllegalArgumentException.class, () -> ReflectionTestUtils.invokeMethod(
                fixture.executor, "fillSourceConnection", source, invalid));
        verifyNoInteractions(fixture.resolver, fixture.runtime, fixture.metadata);
        var sql = task(1, "ignored*,csv", "ignored?");
        sql.getDataAccessJson().getSource().getFirst().setSourceType(2);
        sql.getDataAccessJson().getSource().getFirst().setSourceSql("SELECT '*', '?' FROM \"a,b\"");
        sql.getDataAccessJson().getTarget().setTargetTable("target");
        assertEquals(List.of(), fixture.resolve(sql));
        verifyNoInteractions(fixture.resolver, fixture.runtime, fixture.metadata);
    }

    private static DataAccessSaveReqVO saveRequest() {
        var request = new DataAccessSaveReqVO(); request.setTaskName("exact scope");
        request.setEngineType(1); request.setTaskType(2); request.setSourceType(1);
        request.setSourceDatabaseId(10L); request.setTargetDatabaseId(20L); request.setAccessMode(2);
        request.setIncludeOrExclude(1); request.setSourcePrimaryKeys(List.of()); return request;
    }

    private static DataAccessDO task(int mode, String includes, String excludes) {
        var source = new AccessTaskConfig.Source(); source.setIncludeOrExclude(mode);
        source.setSourceIncludes(includes); source.setSourceExcludes(excludes);
        var config = new AccessTaskConfig(); config.setSource(List.of(source));
        config.getTarget().setWriteMode("CREATE_NEW"); config.getTarget().setSyncExist(false);
        var task = DataAccessDO.builder().engineType(1).sourceType(1).sourceDatabaseId(10L)
                .targetDatabaseId(20L).dataAccessJson(config).build(); task.setCreator("7"); return task;
    }

    private static TableDescription table(String name) {
        var table = new TableDescription(); table.setTableName(name); return table;
    }

    private static class Fixture {
        final DataAccessExecutor executor = new DataAccessExecutor();
        final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
        final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
        final MetaDataByJdbcServiceImpl metadata = mock(MetaDataByJdbcServiceImpl.class);
        final AccessMigrationAuthorization migrationAuthorization = mock(AccessMigrationAuthorization.class);
        Fixture() {
            var connection = new AccessDatabaseResolver.ConnectionInfo(); connection.setProductType(ProductTypeEnum.POSTGRESQL);
            connection.setSchema("public"); connection.setJdbcUrl("jdbc:fixture"); connection.setUsername(""); connection.setPassword("");
            when(resolver.resolve(anyLong(), any())).thenReturn(connection);
            when(runtime.jdbcMetadata(eq(ProductTypeEnum.POSTGRESQL))).thenReturn(metadata);
            ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
            ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
            ReflectionTestUtils.setField(executor, "migrationAuthorization", migrationAuthorization);
        }
        List<String> resolve(DataAccessDO definition) { return ReflectionTestUtils.invokeMethod(executor, "resolveAccessTables", definition); }
    }
}
