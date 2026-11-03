package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.BasicTableMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.util.MigrationColumnMappings;
import cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessPreviewReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.PreviewNameMapperRespVO;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessNameMapping;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTableMapping;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigMapper;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_PREVIEW_PARAM_MISSING;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_ACCESS_MAPPING_CONFIG_INVALID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mock discovery isolates name/permission contracts; it does not certify any real database. */
@ResourceLock(Resources.LOCALE)
class AccessMappingPreviewConsistencyTest {
    private final DataAccessServiceImpl service = new DataAccessServiceImpl();
    private final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
    private final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
    private final MetaDataByJdbcServiceImpl metadata = mock(MetaDataByJdbcServiceImpl.class);
    private final DatabaseQuerySupport permissions = mock(DatabaseQuerySupport.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);

    @BeforeEach void setup() {
        lenient().when(permissions.normalizeSelect(anyLong(), anyString())).thenAnswer(call -> call.getArgument(1));
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(service, "databaseQuerySupport", permissions);
        ReflectionTestUtils.setField(service, "executionAccess", access);
        var connection = new AccessDatabaseResolver.ConnectionInfo();
        connection.setDatabaseId(1L);
        connection.setProductType(ProductTypeEnum.POSTGRESQL);
        connection.setSchema("source_scope");
        connection.setJdbcUrl("fixture-connection");
        when(resolver.resolve(eq(1L), any())).thenReturn(connection);
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(metadata);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
        TenantContextHolder.setTenantId(33L);
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    @Test void ordinaryTableDefaultMappingUsesPhysicalBindingAndTaskQueryPermission() {
        tables("monitor_source");
        var request = request("monitor_source"); request.setTargetDatabaseId(1L);
        assertEquals(List.of("monitor_source"), targets(service.previewTableMap(request)));
        verify(permissions, atLeastOnce()).requireExactTables(1L, ProductTypeEnum.POSTGRESQL, "source_scope",
                List.of("monitor_source"));
        verify(access).requireUserPermission(7L, "dataforge:access:query");
    }

    @Test void revokedTaskQueryPermissionStopsMetadataDiscovery() {
        doThrow(new SecurityException("revoked")).when(access).requireUserPermission(7L, "dataforge:access:query");
        assertThrows(SecurityException.class, () -> service.previewTableMap(request("alpha")));
        verifyNoInteractions(metadata, resolver);
    }



    @Test void singleRulesDoNotLeakBetweenTwoSelectedSourceTables() {
        tables("alpha", "beta");
        var request = request("alpha", "beta");
        request.setMapperType("SINGLE");
        var mappings = new LinkedHashMap<String, AccessTableMapping>();
        mappings.put("alpha", tableMapping("^.*$", "TargetAlpha"));
        mappings.put("beta", tableMapping("^.*$", "TargetBeta"));
        request.setConfigMap(mappings);
        var preview = service.previewTableMap(request);
        assertEquals(List.of("alpha", "beta"), originals(preview));
        assertEquals(List.of("TargetAlpha", "TargetBeta"), targets(preview));
        assertRuntimeNames(request, preview);
        assertEquals("TargetAlpha", mappings.get("alpha").getRegexTableMapper().getFirst().getToValue());
        assertEquals("TargetBeta", mappings.get("beta").getRegexTableMapper().getFirst().getToValue());
    }

    @Test void aSingleTableWithoutItsOwnConfigurationIgnoresGlobalAndOtherTableRules() {
        tables("alpha", "beta");
        var request = request("alpha");
        request.setMapperType("SINGLE");
        request.setTableNameMapper(List.of(new AccessNameMapping("^.*$", "global_wrong")));
        request.setConfigMap(java.util.Map.of("beta", tableMapping("^.*$", "beta_only")));
        var preview = service.previewTableMap(request);
        assertEquals(List.of("alpha"), originals(preview));
        assertEquals(List.of("alpha"), targets(preview));
        assertRuntimeNames(request, preview);
    }

    @Test void allUsesOnlyTheGlobalRulesEvenWhenSingleTableOverridesExist() {
        tables("alpha", "beta");
        var request = request("alpha", "beta");
        request.setTableNameMapper(List.of(new AccessNameMapping("^", "global_")));
        request.setConfigMap(java.util.Map.of("alpha", tableMapping("^.*$", "single_wrong")));
        var preview = service.previewTableMap(request);
        assertEquals(List.of("global_alpha", "global_beta"), targets(preview));
        assertRuntimeNames(request, preview);
    }

    @ParameterizedTest @CsvSource({"false,false,ods_items", "true,false,ods_items", "false,true,ODS_ITEMS"})
    void odsPrefixAndCaseHaveTheSameOrderAsTheActualPhysicalTarget(boolean lower, boolean upper, String expected) {
        tables("items");
        var request = request("items"); request.setAccessMode(1);
        request.setTargetLowerCase(lower); request.setTargetUpperCase(upper);
        var preview = service.previewTableMap(request);
        assertEquals(List.of(expected), targets(preview));
        assertRuntimeNames(request, preview);
    }

    @ParameterizedTest @CsvSource({"true,false,ITEMS,ods_items", "false,true,items,ODS_ITEMS"})
    void tableNamesAreStableUnderATurkishProcessLocale(boolean lower, boolean upper, String original, String expected) {
        Locale previous = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            tables(original);
            var request = request(original); request.setAccessMode(1);
            request.setTargetLowerCase(lower); request.setTargetUpperCase(upper);
            var preview = service.previewTableMap(request);
            assertEquals(List.of(expected), targets(preview));
            assertRuntimeNames(request, preview);
        } finally {
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }

    @Test void noExplicitSelectionPreviewsEveryTableWithoutInventingASelection() {
        tables("alpha", "beta");
        var request = request();
        var preview = service.previewTableMap(request);
        assertEquals(List.of("alpha", "beta"), originals(preview));
        assertRuntimeNames(request, preview);
        assertEquals(List.of(), request.getSourceSelectedTables());
    }

    @Test void exclusionsReferToOriginalSourceNamesBeforeAnyTargetRenaming() {
        tables("alpha", "beta");
        var request = request("beta"); request.setIncludeOrExclude(0);
        request.setTableNameMapper(List.of(new AccessNameMapping("^", "target_")));
        var preview = service.previewTableMap(request);
        assertEquals(List.of("alpha"), originals(preview));
        assertEquals(List.of("target_alpha"), targets(preview));
        assertRuntimeNames(request, preview);
    }

    @Test void anExplicitlySelectedSourceTableMustBeAuthorizedBeforeDiscovery() {
        var request = request("alpha");
        doThrow(new IllegalStateException("fixture denied")).when(permissions).requireExactTables(1L, ProductTypeEnum.POSTGRESQL, "source_scope", List.of("alpha"));
        assertThrows(RuntimeException.class, () -> service.previewTableMap(request));
        verifyNoInteractions(metadata);
    }

    @Test void aDisabledDatasourceMustNotReachNameDiscovery() {
        doThrow(new IllegalStateException("fixture disabled")).when(access).requireDatabase(1L);
        assertThrows(RuntimeException.class, () -> service.previewTableMap(request("alpha")));
        verifyNoInteractions(metadata);
    }

    @Test void anonymousNameDiscoveryMustNotQueryMetadata() {
        SecurityContextHolder.clearContext();
        assertThrows(RuntimeException.class, () -> service.previewTableMap(request("alpha")));
        verifyNoInteractions(metadata);
    }

    @ParameterizedTest @MethodSource("unencodableSelections")
    void selectedPhysicalNamesCannotBecomeEnginePatternsOrCsvFragments(String name, int selectionMode) {
        var request = request(); request.setIncludeOrExclude(selectionMode);
        request.setSourceSelectedTables(java.util.Collections.singletonList(name));
        var error = assertThrows(ServiceException.class, () -> service.previewTableMap(request));
        assertEquals(DATA_ACCESS_PREVIEW_PARAM_MISSING.getCode(), error.getCode());
        verifyNoInteractions(metadata, runtime);
        assertEquals(java.util.Collections.singletonList(name), request.getSourceSelectedTables());
    }

    @ParameterizedTest @MethodSource("unencodableNames")
    void invalidPhysicalColumnSourceIsRejectedBeforeItsPermissionSqlOrMetadataRead(String name) {
        var request = request(); request.setPreviewTableName(name);
        var error = assertThrows(ServiceException.class, () -> service.previewColumnMap(request));
        assertEquals(DATA_ACCESS_PREVIEW_PARAM_MISSING.getCode(), error.getCode());
        verifyNoInteractions(metadata, runtime);
    }

    @Test void aSafeExplicitSelectionDoesNotAdoptUnselectedUnsafeCatalogNames() {
        tables("alpha", "a,b", "report?", "a*b");
        var preview = service.previewTableMap(request("alpha"));
        assertEquals(List.of("alpha"), originals(preview));
        assertEquals(List.of("alpha"), targets(preview));
        verify(permissions, atLeastOnce()).requireExactTables(1L, ProductTypeEnum.POSTGRESQL, "source_scope", List.of("alpha"));
    }

    @Test void allCatalogNamesRemainExactWithoutPolicyFiltering() {
        tables("alpha", "a,b", "report?", "a*b");
        var preview = service.previewTableMap(request());
        assertEquals(List.of("alpha", "a,b", "report?", "a*b"), originals(preview));
        assertEquals(originals(preview), targets(preview));
    }

    @ParameterizedTest @MethodSource("unencodableCatalogSelections")
    void specialNamesInTheFinalDiscoveredSelectionRemainLiteral(String unsafeName, int selectionMode) {
        tables("alpha", "beta", unsafeName);
        var request = selectionMode == 0 ? request("beta") : request();
        request.setIncludeOrExclude(selectionMode);
        var preview = service.previewTableMap(request);
        assertEquals(selectionMode == 0 ? List.of("alpha", unsafeName) : List.of("alpha", "beta", unsafeName), originals(preview));
        verify(metadata).queryTableList("fixture-connection", null, null, "source_scope");
        verifyNoMoreInteractions(metadata);
    }

    @Test void otherQuotedPhysicalNamesRemainExactWithoutInventingPatternMeaning() {
        var names = List.of("百分比%表", "has\"quote", "with space");
        tables(names.toArray(String[]::new));
        var request = request(names.toArray(String[]::new));
        var preview = service.previewTableMap(request);
        assertEquals(names, originals(preview));
        assertEquals(names, targets(preview));
        verify(permissions, atLeastOnce()).requireExactTables(1L, ProductTypeEnum.POSTGRESQL, "source_scope", names);
        assertRuntimeNames(request, preview);
    }

    @Test void sqlColumnPreviewKeepsItsAliasOrderAndGlobalMappingContract() {
        var request = sqlRequest();
        request.setColumnNameMapper(List.of(new AccessNameMapping("amount", "quantity")));
        when(metadata.querySqlColumnMeta("fixture-connection", null, null, request.getSourceSql()))
                .thenReturn(List.of(column("amount"), column("note")));
        var preview = service.previewColumnMap(request);
        assertEquals(List.of("amount", "note"), originals(preview));
        assertEquals(List.of("quantity", "note"), targets(preview));
        verify(permissions).normalizeSelect(1L, request.getSourceSql());
        verify(metadata).querySqlColumnMeta("fixture-connection", null, null, request.getSourceSql());
    }

    @Test void singleColumnPreviewUsesItsOwnRulesAndPreservesNonKeyExclusion() throws Exception {
        var request = request("alpha"); request.setPreviewTableName("alpha"); request.setMapperType("SINGLE");
        request.setTargetLowerCase(true);
        request.setColumnNameMapper(List.of(new AccessNameMapping("id", "global_wrong")));
        var mapping = new AccessTableMapping();
        mapping.setRegexColumnMapper(List.of(new AccessNameMapping("id", "Target_ID"), new AccessNameMapping("obsolete", "")));
        request.setConfigMap(java.util.Map.of("alpha", mapping));
        var schema = new BasicTableMetadata(List.of(column("id"), column("obsolete"), column("payload")), List.of("id"));
        when(metadata.queryTableColumnsAndKeys("fixture-connection", null, null, "source_scope", "alpha"))
                .thenReturn(schema);
        var preview = service.previewColumnMap(request);
        assertEquals(List.of("id", "obsolete", "payload"), originals(preview));
        assertEquals(List.of("target_id", "<!删除>", "payload"), targets(preview));
        assertEquals(List.of("id", "obsolete", "payload"), schema.columns().stream().map(ColumnDescription::getFieldName).toList());
        assertEquals(List.of("id"), schema.primaryKeys());
        assertEquals("Target_ID", mapping.getRegexColumnMapper().getFirst().getToValue());
        verify(metadata).queryTableColumnsAndKeys("fixture-connection", null, null, "source_scope", "alpha");
        verifyNoMoreInteractions(metadata);
    }

    @Test void columnPreviewCannotRemoveTheObservedSourcePrimaryKey() throws Exception {
        var request = request("alpha"); request.setPreviewTableName("alpha");
        request.setColumnNameMapper(List.of(new AccessNameMapping("id", "")));
        when(metadata.queryTableColumnsAndKeys("fixture-connection", null, null, "source_scope", "alpha"))
                .thenReturn(new BasicTableMetadata(List.of(column("id"), column("payload")), List.of("id")));
        var error = assertThrows(ServiceException.class, () -> service.previewColumnMap(request));
        assertEquals(DATA_ACCESS_PREVIEW_PARAM_MISSING.getCode(), error.getCode());
    }

    @Test void realHiveTargetMakesTableAndColumnPreviewUseRuntimeCaseAndManagedPermissions() {
        var target = new AccessDatabaseResolver.ConnectionInfo(); target.setDatabaseId(2L);
        target.setProductType(ProductTypeEnum.HIVE); target.setSchema("warehouse");
        when(resolver.resolve(eq(2L), any())).thenReturn(target);
        tables("Items");
        var request = request("Items"); request.setTargetDatabaseId(2L); request.setPreviewTableName("Items");
        request.setTargetUpperCase(true);
        when(metadata.queryTableColumnsAndKeys("fixture-connection", null, null, "source_scope", "Items"))
                .thenReturn(new BasicTableMetadata(List.of(column("ID"), column("Payload")), List.of("ID")));
        assertEquals(List.of("items"), targets(service.previewTableMap(request)));
        assertEquals(List.of("id", "payload"), targets(service.previewColumnMap(request)));
        verify(access, times(2)).requireDatabase(2L);
    }

    @Test void realHiveTargetKeepsLowercaseEvenWhenExplicitModesAskOtherwise() {
        var target = new AccessDatabaseResolver.ConnectionInfo(); target.setDatabaseId(2L);
        target.setProductType(ProductTypeEnum.HIVE); target.setSchema("warehouse");
        when(resolver.resolve(eq(2L), any())).thenReturn(target);
        tables("OrderItem");
        var request = request("OrderItem"); request.setTargetDatabaseId(2L); request.setPreviewTableName("OrderItem");
        request.setTargetTableNameCase("UPPER"); request.setTargetColumnNameCase("CAMEL");
        when(metadata.queryTableColumnsAndKeys("fixture-connection", null, null, "source_scope", "OrderItem"))
                .thenReturn(new BasicTableMetadata(List.of(column("Order_ID"), column("Payload")), List.of("Order_ID")));
        assertEquals(List.of("orderitem"), targets(service.previewTableMap(request)));
        assertEquals(List.of("order_id", "payload"), targets(service.previewColumnMap(request)));
    }

    @ParameterizedTest
    @CsvSource({"NONE,ods_OrderItem", "UPPER,ODS_ORDERITEM", "LOWER,ods_orderitem", "SNAKE,ods_order_item", "CAMEL,odsOrderitem"})
    void explicitTableModeRunsAfterTheOdsPrefixAndIgnoresColumnModeAndLegacyFlags(String mode, String expected) {
        tables("OrderItem");
        var request = request("OrderItem"); request.setAccessMode(1);
        request.setTargetTableNameCase(mode); request.setTargetColumnNameCase("UPPER"); request.setTargetLowerCase(true);
        var preview = service.previewTableMap(request);
        assertEquals(List.of(expected), targets(preview));
        assertRuntimeNames(request, preview);
    }

    @Test void columnPreviewUsesTheColumnModeIndependentlyOfTheTableMode() {
        tables("orderItem");
        var request = request("orderItem"); request.setPreviewTableName("orderItem");
        request.setTargetTableNameCase("UPPER"); request.setTargetColumnNameCase("SNAKE");
        var schema = new BasicTableMetadata(List.of(column("orderId"), column("createdAt"), column("note")), List.of("orderId"));
        when(metadata.queryTableColumnsAndKeys("fixture-connection", null, null, "source_scope", "orderItem")).thenReturn(schema);
        var columns = service.previewColumnMap(request);
        assertEquals(List.of("order_id", "created_at", "note"), targets(columns));
        assertRuntimeColumns(request, schema, columns);
        var tables = service.previewTableMap(request);
        assertEquals(List.of("ORDERITEM"), targets(tables));
        assertRuntimeNames(request, tables);
    }

    @Test void sqlColumnPreviewAppliesTheColumnModeToResultAliases() {
        var request = sqlRequest(); request.setTargetColumnNameCase("CAMEL"); request.setTargetTableNameCase("SNAKE");
        when(metadata.querySqlColumnMeta("fixture-connection", null, null, request.getSourceSql()))
                .thenReturn(List.of(column("order_id"), column("note")));
        assertEquals(List.of("orderId", "note"), targets(service.previewColumnMap(request)));
    }

    @ParameterizedTest @CsvSource({"SNAKE,userName,user_name", "CAMEL,_,id"})
    void columnPreviewRejectsAModeThatCollapsesOrEmptiesRetainedColumns(String mode, String first, String second) {
        var request = request("alpha"); request.setPreviewTableName("alpha"); request.setTargetColumnNameCase(mode);
        when(metadata.queryTableColumnsAndKeys("fixture-connection", null, null, "source_scope", "alpha"))
                .thenReturn(new BasicTableMetadata(List.of(column(first), column(second)), List.of(second)));
        var error = assertThrows(ServiceException.class, () -> service.previewColumnMap(request));
        assertEquals(DATA_ACCESS_PREVIEW_PARAM_MISSING.getCode(), error.getCode());
    }

    @Test void tablePreviewRejectsAModeThatEmptiesTheTargetName() {
        tables("__");
        var request = request("__"); request.setTargetTableNameCase("CAMEL");
        var error = assertThrows(ServiceException.class, () -> service.previewTableMap(request));
        assertEquals(DATA_ACCESS_PREVIEW_PARAM_MISSING.getCode(), error.getCode());
    }

    @ParameterizedTest @ValueSource(strings = {"snake", "KEBAB", ""})
    void unknownModesAreRejectedBeforeAnyMetadataRead(String mode) {
        var tableRequest = request("alpha"); tableRequest.setTargetTableNameCase(mode);
        assertEquals(DATA_ACCESS_MAPPING_CONFIG_INVALID.getCode(),
                assertThrows(ServiceException.class, () -> service.previewTableMap(tableRequest)).getCode());
        var columnRequest = request("alpha"); columnRequest.setPreviewTableName("alpha"); columnRequest.setTargetColumnNameCase(mode);
        assertEquals(DATA_ACCESS_MAPPING_CONFIG_INVALID.getCode(),
                assertThrows(ServiceException.class, () -> service.previewColumnMap(columnRequest)).getCode());
        verifyNoInteractions(metadata);
    }

    @Test void explicitPrimaryKeyOverrideIsCheckedAgainstActualColumnsAndCannotBeRemoved() {
        var request = request("alpha"); request.setPreviewTableName("alpha");
        request.setSourcePrimaryKeys(List.of("payload"));
        request.setColumnNameMapper(List.of(new AccessNameMapping("payload", "")));
        when(metadata.queryTableColumnsAndKeys("fixture-connection", null, null, "source_scope", "alpha"))
                .thenReturn(new BasicTableMetadata(List.of(column("id"), column("payload")), List.of("id")));
        assertThrows(ServiceException.class, () -> service.previewColumnMap(request));
        request.setSourcePrimaryKeys(List.of("nonexistent")); request.setColumnNameMapper(List.of());
        assertThrows(ServiceException.class, () -> service.previewColumnMap(request));
    }

    @Test void deniedManagedTargetPreventsBothNameCatalogAndColumnMetadataRead() {
        var request = request("alpha"); request.setTargetDatabaseId(2L); request.setPreviewTableName("alpha");
        doThrow(new IllegalStateException("fixture target disabled")).when(access).requireDatabase(2L);
        assertThrows(RuntimeException.class, () -> service.previewTableMap(request));
        assertThrows(RuntimeException.class, () -> service.previewColumnMap(request));
        var sql = sqlRequest(); sql.setTargetDatabaseId(2L);
        assertThrows(RuntimeException.class, () -> service.previewColumnMap(sql));
        verifyNoInteractions(metadata);
        verify(resolver, never()).resolve(eq(2L), any());
    }

    @Test void commaGlobAndUnicodeBoundarySpacesAreQuotedAsOneAuthorizedPhysicalName() {
        var names = List.of("a,b", "report?", "a*b", " leading", "trailing\u3000"); tables(names.toArray(String[]::new));
        assertEquals(names, originals(service.previewTableMap(request(names.toArray(String[]::new)))));
        verify(permissions, atLeastOnce()).requireExactTables(1L, ProductTypeEnum.POSTGRESQL, "source_scope", names);
        for (String name : names) {
            var request = request(name); request.setPreviewTableName(name);
            when(metadata.queryTableColumnsAndKeys("fixture-connection", null, null, "source_scope", name))
                    .thenReturn(new BasicTableMetadata(List.of(column("id")), List.of("id")));
            assertEquals(List.of("id"), targets(service.previewColumnMap(request)));
        }
    }

    @Test void incompleteManagedTargetCannotBeGuessedOrTriggerSourceMetadataRead() {
        var target = new AccessDatabaseResolver.ConnectionInfo(); target.setProductType(ProductTypeEnum.POSTGRESQL);
        when(resolver.resolve(eq(2L), any())).thenReturn(target);
        var request = request("alpha"); request.setTargetDatabaseId(2L); request.setPreviewTableName("alpha");
        assertThrows(ServiceException.class, () -> service.previewTableMap(request));
        assertThrows(ServiceException.class, () -> service.previewColumnMap(request));
        verifyNoInteractions(metadata);
    }

    @ParameterizedTest @CsvSource({"true,false,ID,id", "false,true,id,ID"})
    void sqlAliasCaseUsesRootLocaleWithoutChangingItsResultMetadataContract(boolean lower, boolean upper, String original, String expected) {
        Locale previous = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            var request = sqlRequest(); request.setTargetLowerCase(lower); request.setTargetUpperCase(upper);
            when(metadata.querySqlColumnMeta("fixture-connection", null, null, request.getSourceSql()))
                    .thenReturn(List.of(column(original)));
            assertEquals(List.of(expected), targets(service.previewColumnMap(request)));
            verify(metadata).querySqlColumnMeta("fixture-connection", null, null, request.getSourceSql());
        } finally {
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }

    @ParameterizedTest @NullAndEmptySource
    void sqlColumnPreviewStillRejectsDeletingAResultColumn(String replacement) {
        var request = sqlRequest(); request.setColumnNameMapper(List.of(new AccessNameMapping("amount", replacement)));
        when(metadata.querySqlColumnMeta("fixture-connection", null, null, request.getSourceSql()))
                .thenReturn(List.of(column("amount"), column("note")));
        assertThrows(RuntimeException.class, () -> service.previewColumnMap(request));
    }

    @ParameterizedTest @NullAndEmptySource
    void missingSqlCannotQueryAnyMetadata(String sql) {
        var request = sqlRequest(); request.setSourceSql(sql);
        assertThrows(RuntimeException.class, () -> service.previewColumnMap(request));
        verifyNoInteractions(metadata);
    }

    @Test void deniedSqlCannotReadItsResultMetadata() {
        var request = sqlRequest();
        when(permissions.normalizeSelect(1L, request.getSourceSql())).thenThrow(new IllegalArgumentException("invalid SQL"));
        assertThrows(RuntimeException.class, () -> service.previewColumnMap(request));
        verifyNoInteractions(metadata);
    }

    @Test void deniedTableSelectCannotReadItsColumnMetadata() {
        var request = request("alpha"); request.setPreviewTableName("alpha");
        doThrow(new IllegalStateException("invalid binding")).when(permissions)
                .requireExactTables(1L, ProductTypeEnum.POSTGRESQL, "source_scope", List.of("alpha"));
        assertThrows(RuntimeException.class, () -> service.previewColumnMap(request));
        verifyNoInteractions(metadata);
    }

    private void assertRuntimeNames(DataAccessPreviewReqVO request, List<PreviewNameMapperRespVO> preview) {
        var config = new AccessTaskConfig();
        var source = new AccessTaskConfig.Source();
        source.setMapperType(request.getMapperType()); source.setRegexTableMapper(request.getTableNameMapper());
        source.setConfigMap(request.getConfigMap()); config.setSource(List.of(source));
        config.getTarget().setLowercase(request.isTargetLowerCase());
        config.getTarget().setUppercase(request.isTargetUpperCase());
        config.getTarget().setTableNameCase(request.getTargetTableNameCase());
        config.getTarget().setColumnNameCase(request.getTargetColumnNameCase());
        config.getTarget().setTablePrefix(Integer.valueOf(1).equals(request.getAccessMode()) ? "ods_" : null);
        var execution = AccessTaskConfigMapper.toRuntime(config, 2L);
        execution.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        for (var row : preview) assertEquals(MigrationTableNames.resolve(execution.getSource().getFirst(),
                execution.getTarget(), row.getOriginalName()), row.getTargetName());
    }

    /** 与执行入口相同：保存定义 → 组件运行配置 → 共用列映射。 */
    private void assertRuntimeColumns(DataAccessPreviewReqVO request, BasicTableMetadata schema, List<PreviewNameMapperRespVO> preview) {
        var config = new AccessTaskConfig();
        var source = new AccessTaskConfig.Source();
        source.setMapperType(request.getMapperType()); source.setRegexColumnMapper(request.getColumnNameMapper());
        source.setConfigMap(request.getConfigMap()); config.setSource(List.of(source));
        config.getTarget().setLowercase(request.isTargetLowerCase());
        config.getTarget().setUppercase(request.isTargetUpperCase());
        config.getTarget().setTableNameCase(request.getTargetTableNameCase());
        config.getTarget().setColumnNameCase(request.getTargetColumnNameCase());
        var execution = AccessTaskConfigMapper.toRuntime(config, 2L);
        execution.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        var plan = MigrationColumnMappings.resolve(execution.getSource().getFirst(), execution.getTarget(),
                request.getPreviewTableName(), schema.columns(), schema.primaryKeys());
        assertEquals(plan.columns().stream().map(ColumnDescription::getFieldName).toList(), targets(preview));
    }

    private void tables(String... names) {
        when(metadata.queryTableList("fixture-connection", null, null, "source_scope"))
                .thenReturn(java.util.Arrays.stream(names).map(name -> {
                    var table = new TableDescription(); table.setSchemaName("source_scope"); table.setTableName(name);
                    table.setTableType("TABLE"); table.setRemarks("remark for " + name); return table;
                }).toList());
    }

    private static DataAccessPreviewReqVO request(String... selected) {
        var request = new DataAccessPreviewReqVO(); request.setSourceDatabaseId(1L); request.setSourceType(1);
        request.setMapperType("ALL"); request.setAccessMode(2); request.setIncludeOrExclude(1);
        request.setSourceSelectedTables(List.of(selected)); return request;
    }

    private static DataAccessPreviewReqVO sqlRequest() {
        var request = request(); request.setSourceType(2); request.setSourceSql("SELECT amount, note FROM fixture_source"); return request;
    }

    private static Stream<String> unencodableNames() {
        return Stream.of(null, "", "bad" + (char) 0 + "name", "x".repeat(1025));
    }

    private static Stream<Arguments> unencodableSelections() {
        return unencodableNames().flatMap(name -> Stream.of(Arguments.of(name, 0), Arguments.of(name, 1)));
    }

    private static Stream<Arguments> unencodableCatalogSelections() {
        return Stream.of("a,b", "report?", "a*b")
                .flatMap(name -> Stream.of(Arguments.of(name, 0), Arguments.of(name, 1)));
    }

    private static AccessTableMapping tableMapping(String pattern, String target) {
        var mapping = new AccessTableMapping(); mapping.setRegexTableMapper(List.of(new AccessNameMapping(pattern, target))); return mapping;
    }

    private static ColumnDescription column(String name) {
        var column = new ColumnDescription(); column.setFieldName(name); column.setLabelName(name); return column;
    }

    private static List<String> originals(List<PreviewNameMapperRespVO> preview) {
        return preview.stream().map(PreviewNameMapperRespVO::getOriginalName).toList();
    }

    private static List<String> targets(List<PreviewNameMapperRespVO> preview) {
        return preview.stream().map(PreviewNameMapperRespVO::getTargetName).toList();
    }
}
