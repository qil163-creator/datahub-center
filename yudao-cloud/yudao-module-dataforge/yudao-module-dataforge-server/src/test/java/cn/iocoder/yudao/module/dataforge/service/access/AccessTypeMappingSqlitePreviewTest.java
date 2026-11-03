package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.*;
import cn.iocoder.yudao.module.dataforge.service.access.config.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实现代 SQLite 的生产预览入口；权限/平台记录为独立测试边界。 */
@EnabledIfSystemProperty(named = "datahub.test.sqlite.driver", matches = ".+")
@Timeout(45)
class AccessTypeMappingSqlitePreviewTest {
    @TempDir Path directory;
    ConfiguredJdbcDrivers drivers;
    DbSwitchRuntime runtime;
    DataAccessServiceImpl service;
    DatabaseQuerySupport permissions;
    ExecutionAccessService access;
    AccessDatabaseResolver resolver;
    AccessDatabaseResolver.ConnectionInfo source;
    AccessDatabaseResolver.ConnectionInfo target;

    @BeforeEach void setup() throws Exception {
        var artifact = Path.of(System.getProperty("datahub.test.sqlite.driver"));
        var spec = new DbSwitchProperties.Driver(); spec.setPath(artifact.toAbsolutePath().toString());
        spec.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
        spec.setDriverClassName("org.sqlite.JDBC");
        drivers = new ConfiguredJdbcDrivers(Map.of(SQLITE3, spec));
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), drivers);
        source = new AccessDatabaseResolver.ConnectionInfo(); source.setDatabaseId(1L); source.setProductType(SQLITE3);
        source.setSchema("main"); source.setJdbcUrl("jdbc:sqlite:" + directory.resolve("preview.db"));
        target = new AccessDatabaseResolver.ConnectionInfo(); target.setDatabaseId(2L); target.setProductType(POSTGRESQL);
        target.setSchema("migration"); target.setUsageType(2);
        resolver = mock(AccessDatabaseResolver.class); permissions = mock(DatabaseQuerySupport.class);
        when(resolver.resolve(eq(1L), any())).thenReturn(source); when(resolver.resolve(eq(2L), any())).thenReturn(target);
        service = new DataAccessServiceImpl();
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "databaseQuerySupport", permissions);
        access = mock(ExecutionAccessService.class);
        ReflectionTestUtils.setField(service, "executionAccess", access);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
        sql("CREATE TABLE source(id INTEGER PRIMARY KEY, amount REAL, note TEXT DEFAULT 'private-default')");
        sql("INSERT INTO source VALUES(9007199254740993,1.25,'row-value-not-previewed')");
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        if (runtime != null) runtime.close(); if (drivers != null) drivers.close();
    }

    @Test void nativePreviewBindsSingleTableRenamePrefixCaseAndColumnFingerprintWithoutReadingRows() {
        var request = request();
        var preview = service.previewTypeMap(request);
        assertEquals("SQLITE3", preview.getSourceProduct()); assertEquals("POSTGRESQL", preview.getTargetProduct());
        assertEquals("main", preview.getSourceSchema()); assertEquals("migration", preview.getTargetSchema());
        assertEquals("source", preview.getSourceTable()); assertEquals("ods_renamed", preview.getTargetTable());
        assertEquals("LOSSY", preview.getFidelity()); assertFalse(preview.isAccepted());
        assertEquals(List.of("id", "amount", "name"), preview.getColumns().stream().map(AccessTypeMappingPlan.Column::column).toList());
        for (var column : preview.getColumns()) {
            assertEquals("LOSSY", column.fidelity()); assertFalse(column.accepted());
            assertTrue(column.mappingFingerprint().matches("[0-9a-f]{64}")); assertFalse(column.lossCodes().isEmpty());
            assertTrue(column.issues().stream().noneMatch(issue -> issue.code().equals("DEFAULT_UNMAPPED")));
        }
        assertFalse(preview.toString().contains("private-default")); assertFalse(preview.toString().contains("row-value-not-previewed"));
        verify(permissions).requireExactTables(1L, SQLITE3, "main", List.of("source"));
    }

    @Test void typePreviewUsesTheSameIndependentNameModesAsTheSavedExecutionDefinition() {
        var request = request(); request.setTargetTableNameCase("SNAKE"); request.setTargetColumnNameCase("UPPER");
        var preview = service.previewTypeMap(request);
        assertEquals("ods_re_named", preview.getTargetTable());
        assertEquals(List.of("ID", "AMOUNT", "NAME"), preview.getColumns().stream().map(AccessTypeMappingPlan.Column::column).toList());
        var stored = installStorage(); var save = saveRequest();
        save.setTargetTableNameCase("SNAKE"); save.setTargetColumnNameCase("UPPER");
        service.createDataAccess(save);
        var execution = AccessTaskConfigMapper.toRuntime(stored.get().getDataAccessJson(), 2L);
        execution.getTarget().setTargetProductType(POSTGRESQL);
        assertEquals(preview.getTargetTable(), cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames.resolve(
                execution.getSource().getFirst(), execution.getTarget(), "source"));
    }

    @Test void retainingAnUnmappedDefaultCannotBeAcknowledgedAndMappingChangesInvalidateThePreviewIdentity() {
        var request = request(); var old = service.previewTypeMap(request);
        request.setSyncDefaultVal(true);
        var rejected = service.previewTypeMap(request);
        var note = rejected.getColumns().stream().filter(column -> column.column().equals("name")).findFirst().orElseThrow();
        assertEquals("UNSUPPORTED", note.fidelity()); assertFalse(note.accepted());
        assertTrue(note.issues().stream().anyMatch(issue -> issue.code().equals("DEFAULT_UNMAPPED")));
        assertNotEquals(old.getColumns().getLast().mappingFingerprint(), note.mappingFingerprint());
        assertTrue(note.lossCodes().isEmpty());
    }

    @Test void explicitPreviewChoicesSurviveSaveGetAndFreezeWhileWrongPhysicalScopeIsRejected() {
        var preview = service.previewTypeMap(request());
        var stored = installStorage(); var definitions = (DataAccessMapper) ReflectionTestUtils.getField(service, "dataAccessMapper");
        var request = saveRequest();
        request.setTypeMappingAcknowledgements(preview.getColumns().stream().map(column -> new AccessTypeMappingAcknowledgement(
                preview.getTargetProduct(), preview.getTargetSchema(), preview.getTargetTable(), column.column(),
                column.mappingFingerprint(), column.lossCodes())).toList());
        assertEquals(9007199254740993L, service.createDataAccess(request));
        assertEquals(request.getTypeMappingAcknowledgements(), service.getDataAccess(9007199254740993L).getTypeMappingAcknowledgements());
        String frozen = AccessRunSnapshot.freeze(stored.get());
        assertEquals(request.getTypeMappingAcknowledgements(), AccessRunSnapshot.restore(frozen).getDataAccessJson().getTypeMappingAcknowledgements());
        clearInvocations(definitions);
        var decision = request.getTypeMappingAcknowledgements().getFirst();
        request.setTypeMappingAcknowledgements(List.of(new AccessTypeMappingAcknowledgement("POSTGRESQL", "migration", "wrong_table",
                decision.column(), decision.mappingFingerprint(), decision.lossCodes())));
        assertThrows(RuntimeException.class, () -> service.createDataAccess(request));
        verify(definitions, never()).insert(any(DataAccessDO.class));
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/dbswitch_test")
    void savedPreviewDecisionsReachThePublicMigrationWithFullDifferenceAndStaleMappingRejection() throws Exception {
        String schema = "access_sqmap_" + UUID.randomUUID().toString().replace("-", "");
        target.setJdbcUrl(System.getProperty("datahub.test.postgres.url")); target.setSchema(schema);
        target.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test")); target.setPassword("");
        try (var connection = runtime.openConnection(POSTGRESQL, null, target.getJdbcUrl(), target.getUsername(), target.getPassword());
             var statement = connection.createStatement()) {
            assertEquals(16, connection.getMetaData().getDatabaseMajorVersion()); statement.execute("CREATE SCHEMA " + schema);
        }
        try {
            sql("INSERT INTO source VALUES(2,7,'to-delete')");
            var preview = service.previewTypeMap(request()); var stored = installStorage(); var save = saveRequest();
            save.setTypeMappingAcknowledgements(preview.getColumns().stream().filter(column -> column.fidelity().equals("LOSSY"))
                    .map(column -> new AccessTypeMappingAcknowledgement(preview.getTargetProduct(), preview.getTargetSchema(),
                            preview.getTargetTable(), column.column(), column.mappingFingerprint(), column.lossCodes())).toList());
            service.createDataAccess(save);
            var definition = AccessRunSnapshot.restore(AccessRunSnapshot.freeze(stored.get()));
            var request = AccessTaskConfigMapper.toRuntime(definition.getDataAccessJson(), definition.getTargetDatabaseId());
            var executor = new DataAccessExecutor(); ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
            ReflectionTestUtils.invokeMethod(executor, "fillSourceConnection", request.getSource().getFirst(), definition);
            ReflectionTestUtils.invokeMethod(executor, "fillTargetConnection", request.getTarget(), definition);
            assertTrue(request.getTarget().getWriterEngineInsert()); // Existing production default, not a test override.
            var full = run(request); assertTrue(full.getIfAllSuccess().get(), full.getTableResultList().toString());
            var observedPlan = full.getTableResultList().getFirst().getTypeMappingPlan(); assertNotNull(observedPlan);
            for (var column : preview.getColumns()) assertEquals(column.mappingFingerprint(), observedPlan.column(column.column()).fingerprint());
            assertEquals(List.of("2|7.0|to-delete", "9007199254740993|1.25|row-value-not-previewed"), targetRows(schema));
            sql("UPDATE source SET amount=2.5,note='中文 😀' WHERE id=9007199254740993");
            sql("DELETE FROM source WHERE id=2"); sql("INSERT INTO source VALUES(3,3.5,'new')");
            request.getTarget().setChangeDataSync(true); request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
            assertTrue(run(request).getIfAllSuccess().get());
            assertEquals(List.of("3|3.5|new", "9007199254740993|2.5|中文 😀"), targetRows(schema));
            sql("UPDATE source SET amount=3.75 WHERE id=9007199254740993");
            var deliveries = new java.util.concurrent.atomic.AtomicInteger();
            var committedReceipt = new AtomicReference<cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult>();
            var deliveryFailure = new MigrationService(request, receipt -> {
                deliveries.incrementAndGet(); committedReceipt.set(receipt);
                assertNotNull(AccessTypeMappingDiagnostics.write(AccessTypeMappingDiagnostics.from(receipt.getTypeMappingPlan(),
                        receipt.getSourceSchemaName(), receipt.getSourceTableName(), receipt.getTargetSchemaName(), receipt.getTargetTableName())));
                throw new IllegalStateException("Simulated platform result store unavailable");
            }, null, runtime);
            try {
                var failedDelivery = assertThrows(java.util.concurrent.ExecutionException.class, deliveryFailure::run);
                assertInstanceOf(IllegalStateException.class, failedDelivery.getCause());
                assertEquals("Simulated platform result store unavailable", failedDelivery.getCause().getMessage());
            }
            finally { deliveryFailure.physicalExit().toCompletableFuture().get(30, TimeUnit.SECONDS); }
            assertEquals(1, deliveries.get()); assertTrue(committedReceipt.get().getIfSuccess().get());
            assertTrue(committedReceipt.get().getTypeMappingPlan().accepted());
            var afterDifference = List.of("3|3.5|new", "9007199254740993|3.75|中文 😀"); assertEquals(afterDifference, targetRows(schema));
            request.getSource().getFirst().getConfigMap().get("source").setRegexColumnMapper(
                    List.of(new cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper("^note$", "different")));
            request.getTarget().setChangeDataSync(false); request.getTarget().setTargetDrop(true);
            var rejected = run(request); assertFalse(rejected.getIfAllSuccess().get());
            assertFalse(rejected.getTableResultList().getFirst().getTypeMappingPlan().accepted());
            assertEquals(afterDifference, targetRows(schema));
            assertEquals("Name", definition.getDataAccessJson().getSource().getFirst().getConfigMap().get("source")
                    .getRegexColumnMapper().getFirst().getToValue());
        } finally {
            assertTrue(schema.matches("access_sqmap_[a-f0-9]{32}"));
            try (var connection = runtime.openConnection(POSTGRESQL, null, target.getJdbcUrl(), target.getUsername(), target.getPassword());
                 var statement = connection.createStatement()) { statement.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    @ParameterizedTest @ValueSource(strings={"a,b", "report?", "a*b", " leading", "trailing\u3000"})
    @EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/dbswitch_test")
    void literalSelectionAndExplicitLossChoicesSurvivePreviewSaveGetAuthorizationSnapshotAndNativeMigration(String name) throws Exception {
        String schema = "access_sqmap_" + UUID.randomUUID().toString().replace("-", "");
        target.setJdbcUrl(System.getProperty("datahub.test.postgres.url")); target.setSchema(schema);
        target.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test")); target.setPassword("");
        String quotedSource = SQLITE3.quoteSchemaTableName("main", name);
        String targetName = "ods_" + name; String quotedTarget = POSTGRESQL.quoteSchemaTableName(schema, targetName);
        sql("CREATE TABLE " + quotedSource + "(id INTEGER PRIMARY KEY, amount REAL, note TEXT)");
        sql("INSERT INTO " + quotedSource + " VALUES(9007199254740993,1.25,'literal-source')");
        try (var connection = runtime.openConnection(POSTGRESQL, null, target.getJdbcUrl(), target.getUsername(), target.getPassword());
             var statement = connection.createStatement()) { statement.execute("CREATE SCHEMA " + schema); }
        try {
            var previewRequest = new AccessTypeMappingPreviewReqVO(); previewRequest.setSourceDatabaseId(1L);
            previewRequest.setTargetDatabaseId(2L); previewRequest.setAccessMode(1); previewRequest.setPreviewTableName(name);
            var preview = service.previewTypeMap(previewRequest); assertEquals(name, preview.getSourceTable());
            assertEquals(targetName, preview.getTargetTable()); assertFalse(preview.isAccepted());
            var stored = installStorage(); var save = saveRequest(); save.setMapperType("ALL");
            save.setConfigMap(Map.of()); save.setTargetLowerCase(false); save.setSourceSelectedTables(List.of(name));
            save.setTypeMappingAcknowledgements(preview.getColumns().stream().filter(column -> column.fidelity().equals("LOSSY"))
                    .map(column -> new AccessTypeMappingAcknowledgement(preview.getTargetProduct(), preview.getTargetSchema(),
                            preview.getTargetTable(), column.column(), column.mappingFingerprint(), column.lossCodes())).toList());
            service.createDataAccess(save);
            assertEquals(List.of(name), service.getDataAccess(9007199254740993L).getSourceSelectedTables());
            var definition = AccessRunSnapshot.restore(AccessRunSnapshot.freeze(stored.get())); definition.setCreator("7");
            var executor = new DataAccessExecutor(); ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
            ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
            ReflectionTestUtils.setField(executor, "databaseQuerySupport", permissions);
            // 跨产品组件能力仍保留；业务授权入口当前只开放 PostgreSQL 普通表全量新建。
            assertThrows(IllegalArgumentException.class, () -> executor.validateExecutionPermission(definition));
            verify(permissions).requireExactTables(1L, SQLITE3, "main", List.of(name));
            var request = AccessTaskConfigMapper.toRuntime(AccessRunSnapshot.restore(AccessRunSnapshot.freeze(definition))
                    .getDataAccessJson(), 2L);
            ReflectionTestUtils.invokeMethod(executor, "fillSourceConnection", request.getSource().getFirst(), definition);
            ReflectionTestUtils.invokeMethod(executor, "fillTargetConnection", request.getTarget(), definition);
            assertEquals(List.of(name), request.getSource().getFirst().getSourceTableNames());
            assertEquals("", request.getSource().getFirst().getSourceIncludes());
            var result = run(request); assertTrue(result.getIfAllSuccess().get());
            assertEquals(name, result.getTableResultList().getFirst().getSourceTableName());
            assertTrue(result.getTableResultList().getFirst().getTypeMappingPlan().accepted());
            try (var connection = runtime.openConnection(POSTGRESQL, null, target.getJdbcUrl(), target.getUsername(), target.getPassword());
                 var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id,note FROM " + quotedTarget)) {
                assertTrue(rows.next()); assertEquals(9007199254740993L, rows.getLong(1)); assertEquals("literal-source", rows.getString(2)); assertFalse(rows.next());
            }
            sql("UPDATE " + quotedSource + " SET amount=2.5,note='literal-updated' WHERE id=9007199254740993");
            request.getTarget().setChangeDataSync(true); request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
            assertTrue(run(request).getIfAllSuccess().get());
            try (var connection = runtime.openConnection(POSTGRESQL, null, target.getJdbcUrl(), target.getUsername(), target.getPassword());
                 var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id,note FROM " + quotedTarget)) {
                assertTrue(rows.next()); assertEquals(9007199254740993L, rows.getLong(1)); assertEquals("literal-updated", rows.getString(2)); assertFalse(rows.next());
            }
        } finally {
            try (var connection = runtime.openConnection(POSTGRESQL, null, target.getJdbcUrl(), target.getUsername(), target.getPassword());
                 var statement = connection.createStatement()) { statement.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    private AtomicReference<DataAccessDO> installStorage() {
        var databases = mock(DatabaseMapper.class); var definitions = mock(DataAccessMapper.class);
        var sourceDatabase = DatabaseDO.builder().id(1L).name("source").databaseSchema("main").build();
        var targetDatabase = DatabaseDO.builder().id(2L).name("target").databaseSchema(target.getSchema()).usageType(2).build();
        when(databases.selectById(1L)).thenReturn(sourceDatabase); when(databases.selectById(2L)).thenReturn(targetDatabase);
        when(databases.selectByIds(anyCollection())).thenReturn(List.of(sourceDatabase, targetDatabase));
        when(resolver.resolveProductType(sourceDatabase)).thenReturn(SQLITE3); when(resolver.resolveProductType(targetDatabase)).thenReturn(POSTGRESQL);
        when(resolver.resolve(targetDatabase)).thenReturn(target);
        var stored = new AtomicReference<DataAccessDO>();
        when(definitions.insert(any(DataAccessDO.class))).thenAnswer(inv -> {
            DataAccessDO definition = inv.getArgument(0); definition.setId(9007199254740993L); stored.set(definition); return 1;
        });
        when(definitions.selectById(anyLong())).thenAnswer(inv -> stored.get());
        ReflectionTestUtils.setField(service, "dataAccessMapper", definitions); ReflectionTestUtils.setField(service, "databaseMapper", databases);
        ReflectionTestUtils.setField(service, "deptApi", mock(cn.iocoder.yudao.module.system.api.dept.DeptApi.class));
        ReflectionTestUtils.setField(service, "adminUserApi", mock(cn.iocoder.yudao.module.system.api.user.AdminUserApi.class));
        return stored;
    }

    private DbSwitchResult run(MigrationRequest request) throws Exception {
        var service = new MigrationService(request, ignored -> { }, null, runtime);
        var result = service.run(); service.physicalExit().toCompletableFuture().get(30, TimeUnit.SECONDS); return result;
    }

    private List<String> targetRows(String schema) throws Exception {
        var rows = new ArrayList<String>();
        try (var connection = runtime.openConnection(POSTGRESQL, null, target.getJdbcUrl(), target.getUsername(), target.getPassword());
             var statement = connection.createStatement(); var data = statement.executeQuery("SELECT id,amount,name FROM " + schema + ".ods_renamed ORDER BY id")) {
            while (data.next()) rows.add(data.getLong(1) + "|" + data.getDouble(2) + "|" + data.getString(3));
        }
        return rows;
    }

    private AccessTypeMappingPreviewReqVO request() {
        var request = AccessTypeMappingPermissionTest.request(); request.setAccessMode(1); request.setMapperType("SINGLE");
        request.setTargetLowerCase(true); request.setConfigMap(Map.of("source", mapping())); return request;
    }
    private DataAccessSaveReqVO saveRequest() {
        var request = new DataAccessSaveReqVO(); request.setTaskName("type mapping"); request.setEngineType(1); request.setTaskType(2);
        request.setSourceType(1); request.setSourceDatabaseId(1L); request.setTargetDatabaseId(2L); request.setAccessMode(1);
        request.setSourceSelectedTables(List.of("source")); request.setTargetSyncExit(true); request.setMapperType("SINGLE");
        request.setTargetLowerCase(true); request.setConfigMap(Map.of("source", mapping())); return request;
    }
    private AccessTableMapping mapping() {
        var mapping = new AccessTableMapping(); mapping.setRegexTableMapper(List.of(new AccessNameMapping("^source$", "ReNamed")));
        mapping.setRegexColumnMapper(List.of(new AccessNameMapping("^note$", "Name"))); return mapping;
    }
    private void sql(String sql) throws Exception {
        try (var connection = runtime.openConnection(SQLITE3, null, source.getJdbcUrl(), null, null); var statement = connection.createStatement()) {
            assertEquals("3.49.1", connection.getMetaData().getDatabaseProductVersion()); statement.setQueryTimeout(10); statement.execute(sql);
        }
    }
}
