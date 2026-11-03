package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.NamedSqlBindings;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.api.DataSourceApi;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevScriptAuthorizationTest {
    private final DevScriptAuthorizationSupport scripts = new DevScriptAuthorizationSupport();
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
    private final DatabaseMapper databases = mock(DatabaseMapper.class);
    private final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
    private final DatabaseDO database = new DatabaseDO();
    private final AccessDatabaseResolver.ConnectionInfo route = new AccessDatabaseResolver.ConnectionInfo();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(scripts, "executionAccessService", access);
        ReflectionTestUtils.setField(scripts, "databaseMapper", databases);
        ReflectionTestUtils.setField(scripts, "databaseResolver", resolver);
        ReflectionTestUtils.setField(scripts, "dbSwitchRuntime", runtime);
        when(runtime.products()).thenReturn(DbSwitchProductRegistry.discover());
        database.setId(2L); route.setProductType(ProductTypeEnum.POSTGRESQL);
        route.setConfigurationRevision("revision-fixture"); route.setSchema("public");
        when(databases.selectById(2L)).thenReturn(database);
        when(resolver.resolve(database)).thenReturn(route);

    }
    @AfterEach void clearTenant() { TenantContextHolder.clear(); }

    @Test void ordinaryDmlKeepsExactTargetAndOrderedNamedBindings() {
        var task = task("UPDATE samples SET value = ${value} WHERE id = ${id}", "NON_QUERY");
        var config = DevTaskDefinition.readExecutionConfig(task.getExecutionConfigJson());
        config.setLocalParams(List.of(parameter("id", "9223372036854775807"), parameter("value", "literal'; DROP TABLE other; --")));
        task.setExecutionConfigJson(JsonUtils.toJsonString(config));
        var result = scripts.authorize(task);
        var compiled = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL, result.steps().getFirst().sql());
        assertEquals(List.of("value", "id"), compiled.parameterNames());
        assertTrue(result.steps().getFirst().sql().contains("samples"));
        assertFalse(result.steps().getFirst().sql().contains("DROP TABLE"));
        assertEquals("9223372036854775807", config.getLocalParams().getFirst().getValue());
        assertFalse(result.steps().getFirst().query());
        verify(access).requireUserPermission(7L, ExecutionAccessService.PERMISSION_DEV_TASK_WRITE);
    }

    @Test void schemaDefinitionsAndCallsAreWholePreStatementsInARegularSqlNode() {
        var task = task("CREATE TABLE samples(id int, value text);"
                + "CREATE PROCEDURE populate() LANGUAGE SQL AS $$ INSERT INTO samples VALUES(1,'fixed'); $$;"
                + "CALL populate();", "PROCEDURE");
        task.setTaskType("PROCEDURE");
        var params = build(task);
        assertEquals(3, params.getJSONArray("preStatements").size());
        assertTrue(params.getJSONArray("preStatements").getStr(1).contains("INSERT INTO"));
        assertEquals("SELECT 1", params.getStr("sql")); assertEquals("0", params.getStr("sqlType"));
        assertFalse(params.containsKey("method"));
        var submitter = new DevDsSubmitter();
        String json = ReflectionTestUtils.invokeMethod(submitter, "buildTaskDefinitionJson", task, 10L, params, "managed");
        assertEquals("SQL", new JSONArray(json).getJSONObject(0).getStr("taskType"));
        verify(access).requireUserPermission(7L, ExecutionAccessService.PERMISSION_DEV_TASK_WRITE);
    }

    @Test void revokedFixedActorWritePermissionStopsBeforeManagedSchemaPlan() {
        doThrow(new ServiceException(1000, "denied")).when(access).requireUserPermission(7L, ExecutionAccessService.PERMISSION_DEV_TASK_WRITE);
        assertThrows(ServiceException.class, () -> scripts.authorize(task("CREATE TABLE samples(id int)", "NON_QUERY")));
    }

    @Test void schedulerQuestionMarkExpansionCannotConfuseLiteralAndNamedBindings() {
        var task = task("SELECT id FROM samples WHERE value = '?' AND id = ${id}", "QUERY");
        var config = DevTaskDefinition.readExecutionConfig(task.getExecutionConfigJson());
        config.setLocalParams(List.of(parameter("id", "1")));
        task.setExecutionConfigJson(JsonUtils.toJsonString(config));
        assertThrows(ServiceException.class, () -> scripts.authorize(task));
    }

    @Test void schedulerCannotChangeAnAuthorizedParameterThroughRecursiveTemplates() {
        for (String unsafe : List.of("${other}", "prefix ${other} suffix", "$[yyyyMMdd]", "first\n${other}")) {
            var task = task("UPDATE samples SET value = ${value} WHERE id = 1", "NON_QUERY");
            var config = new DevExecutionConfig(); config.setSqlType("NON_QUERY");
            config.setLocalParams(List.of(parameter("value", unsafe)));
            task.setExecutionConfigJson(JsonUtils.toJsonString(config));
            assertThrows(ServiceException.class, () -> scripts.authorize(task));
        }
    }

    @Test void missingRevisionOrChangedProductCannotProduceAPlan() {
        route.setConfigurationRevision(null);
        assertThrows(ServiceException.class, () -> scripts.authorize(task("SELECT id FROM samples", "QUERY")));
        route.setConfigurationRevision("revision-fixture"); route.setProductType(ProductTypeEnum.MYSQL);
        assertThrows(ServiceException.class, () -> scripts.authorize(task("SELECT id FROM samples", "QUERY")));
    }

    @Test void managedSchemaCannotEscapeTheBoundSchemaOrExecuteUnverifiedCalls() {
        for (String sql : List.of("CREATE TABLE other.samples(id int)",
                "CREATE TABLE samples(id int); INSERT INTO other.samples(id) VALUES(1)",
                "CALL unknown_existing()")) {
            assertThrows(ServiceException.class, () -> scripts.authorize(task(sql, "AUTO")));
        }
    }

    @Test void queryPlacementAndExecutionTypeAreRejectedDuringPreflight() {
        assertThrows(ServiceException.class, () -> scripts.authorize(task("SELECT id FROM samples; CREATE TABLE created(id int)", "AUTO")));
        assertThrows(ServiceException.class, () -> scripts.authorize(task("SELECT id FROM samples", "NON_QUERY")));
        assertThrows(ServiceException.class, () -> scripts.authorize(task("CREATE TABLE created(id int)", "QUERY")));
        assertThrows(ServiceException.class, () -> scripts.authorize(task("CREATE TABLE created(id int)", "PROCEDURE")));
        assertThrows(ServiceException.class, () -> scripts.authorize(task("CALL unknown_existing()", "PROCEDURE")));
    }

    @Test void schemaLastQueryIsSummarizedWithoutDependingOnUncreatedTableMetadata() {
        var result = scripts.authorize(task("CREATE TABLE created(id int); SELECT id FROM created ORDER BY id LIMIT 2", "AUTO"));
        assertTrue(result.steps().getLast().query());
        assertTrue(result.steps().getLast().sql().startsWith("SELECT pg_catalog.COUNT(*) AS execution_row_count FROM ("));
        assertTrue(result.steps().getLast().sql().contains("LIMIT 2"));
    }

    @Test void aDataSourceRevisionChangeAfterAuthorizationNeverContactsDolphinScheduler() {
        var changed = new AccessDatabaseResolver.ConnectionInfo(); changed.setProductType(ProductTypeEnum.POSTGRESQL);
        changed.setConfigurationRevision("changed"); changed.setSchema("public");
        when(resolver.resolve(database)).thenReturn(route, changed);
        assertThrows(ServiceException.class, () -> build(task("CREATE TABLE created(id int)", "NON_QUERY")));
    }

    @Test void nestedWritesSelectIntoAndRowLocksNeverReachReadOnlyAuthorization() {
        doThrow(new ServiceException(1000, "write denied")).when(access)
                .requireUserPermission(7L, ExecutionAccessService.PERMISSION_DEV_TASK_WRITE);
        for (String sql : List.of("SELECT * INTO copied FROM samples",
                "WITH x AS (DELETE FROM samples RETURNING id) SELECT * FROM x",
                "SELECT * FROM samples FOR UPDATE")) {
            assertThrows(ServiceException.class, () -> scripts.authorize(task(sql, "AUTO")), sql);
        }
        assertTrue(scripts.authorize(task("SELECT id FROM samples WHERE value = 'literal;value'", "QUERY"))
                .steps().getFirst().query());
    }

    @Test void aLaterDeniedOrInvalidStatementPreventsAnyDolphinSchedulerCall() {
        var submitter = new DevDsSubmitter(); var ds = mock(DolphinSchedulerClient.class);
        ReflectionTestUtils.setField(submitter, "scriptAuthorizationSupport", scripts);
        ReflectionTestUtils.setField(submitter, "dsClient", ds);
        for (String sql : List.of("UPDATE samples SET value = 'allowed'; DELETE FROM other.forbidden_table",
                "UPDATE samples SET value = 'allowed'; bad syntax", "SELECT 1; SELECT 2")) {
            var error = assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(submitter,
                    "buildTaskParams", task(sql, "AUTO"), new StringBuilder()), sql);
            assertFalse(error.getMessage().contains("samples"));
            assertFalse(error.getMessage().contains("bad syntax"));
        }
        verifyNoInteractions(ds);
    }

    private DevTaskDO task(String sql, String type) {
        var config = new DevExecutionConfig(); config.setSqlType(type);
        return DevTaskDO.builder().id(1L).name("fixture").taskType("SQL").databaseId(2L).dbType("POSTGRESQL")
                .executionUserId(7L).content(sql).executionConfigJson(JsonUtils.toJsonString(config)).build();
    }
    private DevExecutionConfig.LocalParam parameter(String name, String value) {
        var parameter = new DevExecutionConfig.LocalParam(); parameter.setProp(name); parameter.setValue(value); return parameter;
    }
    private JSONObject build(DevTaskDO task) {
        TenantContextHolder.setTenantId(1L);
        var submitter = new DevDsSubmitter(); var ds = mock(DolphinSchedulerClient.class); var sources = mock(DataSourceApi.class);
        ReflectionTestUtils.setField(submitter, "scriptAuthorizationSupport", scripts);
        ReflectionTestUtils.setField(submitter, "databaseMapper", databases); ReflectionTestUtils.setField(submitter, "databaseResolver", resolver);
        ReflectionTestUtils.setField(submitter, "dsClient", ds); when(ds.dataSource()).thenReturn(sources);
        String suffix = cn.hutool.crypto.digest.DigestUtil.sha256Hex("null").substring(0, 16);
        when(sources.queryDataSourceListPaging(anyString(), eq(1), eq(10))).thenReturn(new JSONObject().set("data",
                new JSONObject().set("totalList", new JSONArray().put(new JSONObject().set("id", 9).set("type", "POSTGRESQL")
                        .set("name", "dh_ds_2_" + suffix)))));
        try { return ReflectionTestUtils.invokeMethod(submitter, "buildTaskParams", task, new StringBuilder()); }
        finally { verify(sources, never()).createDataSource(anyMap()); }
    }
}
