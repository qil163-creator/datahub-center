package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.api.DataSourceApi;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.dir.DevDirSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.*;
import cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DevDevelopmentPresentationTest {
    @AfterEach void cleanup() { TenantContextHolder.clear(); }

    @Test void datasourceDisplayUsesConfiguredNamesWithoutChangingTheExecutionConnection() {
        var service = new DevTaskServiceImpl();
        var resolver = mock(AccessDatabaseResolver.class);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        var connection = new AccessDatabaseResolver.ConnectionInfo();
        connection.setName("source");
        connection.setProductType(ProductTypeEnum.POSTGRESQL);
        connection.setJdbcUrl("jdbc:postgresql://localhost:5432/execution_database");
        connection.setSchema("reporting");
        connection.setDatabaseName("configured_database");
        when(resolver.resolve(eq(2L), any())).thenReturn(connection);
        var task = DevTaskDO.builder().databaseId(2L).build();

        ReflectionTestUtils.invokeMethod(service, "fillDatasourceInfo", task);
        assertEquals("reporting", task.getDatabaseName());
        connection.setSchema("");
        ReflectionTestUtils.invokeMethod(service, "fillDatasourceInfo", task);
        assertEquals("configured_database", task.getDatabaseName());
        connection.setDatabaseName(null);
        ReflectionTestUtils.invokeMethod(service, "fillDatasourceInfo", task);
        assertEquals("", task.getDatabaseName());
        assertEquals("source", task.getDatasourceName());
        assertEquals("POSTGRESQL", task.getDbType());
        assertEquals(2L, task.getDatabaseId());
        assertEquals("jdbc:postgresql://localhost:5432/execution_database", connection.getJdbcUrl());
    }

    @Test void legacyConfigUsesBoundedDefaultsAndInvalidSnapshotsFailClosed() {
        var defaults = DevTaskDefinition.readExecutionConfig(null);
        assertEquals("MEDIUM", defaults.getPriority());
        assertEquals(0, defaults.getRetryTimes());
        assertEquals(List.of(), defaults.getLocalParams());
        for (String json : List.of("null", "[]", "{\"priority\":\"ADMIN\"}", "{\"retryTimes\":11}",
                "{\"statementSeparator\":\"GO\"}", "{\"localParams\":null}")) {
            assertThrows(ServiceException.class, () -> DevTaskDefinition.readExecutionConfig(json));
        }
        var snapshot = new DevTaskSnapshot();
        snapshot.setTask(DevTaskDO.builder().executionConfigJson("{\"retryTimes\":-1}").build());
        assertThrows(ServiceException.class, snapshot::write);
    }

    @Test void duplicateOrCredentialParametersAreRejectedWithoutEchoingTheirValue() {
        var config = new DevExecutionConfig();
        var parameter = new DevExecutionConfig.LocalParam(); parameter.setProp("station");
        parameter.setValue("private-fixture");
        config.setLocalParams(List.of(parameter, parameter));
        var duplicate = assertThrows(ServiceException.class, () -> DevTaskDefinition.validateExecutionConfig(config));
        assertFalse(duplicate.getMessage().contains("private-fixture"));
        parameter.setProp("access_token"); config.setLocalParams(List.of(parameter));
        assertThrows(ServiceException.class, () -> DevTaskDefinition.validateExecutionConfig(config));
    }

    @Test void executionAttributesAreFrozenAndMappedToTheDolphinSchedulerNode() {
        var config = new DevExecutionConfig();
        config.setPriority("HIGH"); config.setRetryTimes(3); config.setRetryIntervalMinutes(5); config.setDelayMinutes(2);
        var task = DevTaskDO.builder().id(1L).name("task").taskType("SQL").timeoutSeconds(61)
                .executionConfigJson(JsonUtils.toJsonString(config)).build();
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(task);
        String frozen = snapshot.write();
        task.setExecutionConfigJson("{}");
        var restored = DevTaskSnapshot.read(frozen).getTask();
        String json = ReflectionTestUtils.invokeMethod(new DevDsSubmitter(), "buildTaskDefinitionJson",
                restored, 10L, new JSONObject(), "managed-group");
        var node = new JSONArray(json).getJSONObject(0);
        assertEquals("HIGH", node.getStr("taskPriority"));
        assertEquals(3, node.getInt("failRetryTimes"));
        assertEquals(5, node.getInt("failRetryInterval"));
        assertEquals(2, node.getInt("delayTime"));
        assertEquals(2, node.getInt("timeout"));
        assertEquals("managed-group", node.getStr("workerGroup"));
    }

    @Test void userCannotSelectAnUnmanagedWorkerGroupOrBypassProcedureIsolation() {
        var submitter = new DevDsSubmitter();
        ReflectionTestUtils.setField(submitter, "workerGroup", "managed");
        var config = new DevExecutionConfig(); config.setWorkerGroup("other");
        var task = DevTaskDO.builder().taskType("SQL").executionConfigJson(JsonUtils.toJsonString(config)).build();
        assertThrows(ServiceException.class, () -> submitter.executionTarget(task));
        config.setWorkerGroup("default"); config.setSqlType("PROCEDURE");
        task.setExecutionConfigJson(JsonUtils.toJsonString(config));
        assertThrows(ServiceException.class, () -> submitter.executionTarget(task));
        ReflectionTestUtils.setField(submitter, "tenantCode", "isolated_user");
        assertEquals("managed", submitter.executionTarget(task).workerGroup());
    }

    @Test void namedValuesStayOutsideAuthorizedSqlAndAreSentAsInputBindings() {
        var submitter = new DevDsSubmitter();
        var ds = mock(DolphinSchedulerClient.class);
        var sources = mock(DataSourceApi.class);
        var databases = mock(DatabaseMapper.class);
        var resolver = mock(AccessDatabaseResolver.class);
        var scripts = new DevScriptAuthorizationSupport();
        ReflectionTestUtils.setField(scripts, "executionAccessService", mock(cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.class));
        ReflectionTestUtils.setField(scripts, "databaseMapper", databases);
        ReflectionTestUtils.setField(scripts, "databaseResolver", resolver);
        ReflectionTestUtils.setField(submitter, "dsClient", ds);
        ReflectionTestUtils.setField(submitter, "databaseMapper", databases);
        ReflectionTestUtils.setField(submitter, "databaseResolver", resolver);
        ReflectionTestUtils.setField(submitter, "scriptAuthorizationSupport", scripts);
        TenantContextHolder.setTenantId(1L);
        var database = new DatabaseDO(); database.setId(2L);
        when(databases.selectById(2L)).thenReturn(database);
        var connection = new AccessDatabaseResolver.ConnectionInfo(); connection.setProductType(ProductTypeEnum.POSTGRESQL);
        connection.setConfigurationRevision("fixture-revision"); connection.setSchema("public");
        when(resolver.resolve(database)).thenReturn(connection);
        when(ds.dataSource()).thenReturn(sources);
        String revision = cn.hutool.crypto.digest.DigestUtil.sha256Hex("null").substring(0, 16);
        when(sources.queryDataSourceListPaging(anyString(), eq(1), eq(10))).thenReturn(
                new JSONObject().set("data", new JSONObject().set("totalList", new JSONArray().put(
                        new JSONObject().set("id", 9).set("type", "POSTGRESQL").set("name", "dh_ds_2_" + revision)))));
        var config = new DevExecutionConfig(); config.setSqlType("QUERY");
        var parameter = new DevExecutionConfig.LocalParam(); parameter.setProp("station");
        parameter.setValue("untrusted'; DROP TABLE example; --"); config.setLocalParams(List.of(parameter));
        var task = DevTaskDO.builder().id(1L).taskType("SQL").databaseId(2L).dbType("POSTGRESQL")
                .executionUserId(7L).content("SELECT id FROM readings WHERE station = ${station}")
                .executionConfigJson(JsonUtils.toJsonString(config)).build();
        JSONObject params = ReflectionTestUtils.invokeMethod(submitter, "buildTaskParams", task, new StringBuilder());
        assertTrue(params.getStr("sql").startsWith("SELECT pg_catalog.COUNT(*) AS execution_row_count FROM ("));
        assertTrue(params.getStr("sql").contains("station = ${station}"));
        assertTrue(params.getStr("sql").contains("\"public\".\"readings\""));
        assertFalse(params.getStr("sql").contains("DROP TABLE"));
        assertEquals(parameter.getValue(), params.getJSONArray("localParams").getJSONObject(0).getStr("value"));
        assertEquals("IN", params.getJSONArray("localParams").getJSONObject(0).getStr("direct"));
        assertEquals("0", params.getStr("sqlType"));
    }

    @Test void cronPreviewIsStrictlyIncreasingAndInvalidExpressionFails() {
        var service = new DevTaskServiceImpl();
        var coordinator = mock(DevScheduleCoordinator.class); when(coordinator.zone()).thenReturn(java.time.ZoneId.of("Asia/Shanghai"));
        ReflectionTestUtils.setField(service, "scheduleCoordinator", coordinator);
        var times = service.previewCron("0 * * * * ?");
        assertEquals(5, times.size());
        assertTrue(times.getFirst().isAfter(LocalDateTime.now().minusSeconds(1)));
        for (int i = 1; i < times.size(); i++) assertTrue(times.get(i).isAfter(times.get(i - 1)));
        assertThrows(ServiceException.class, () -> service.previewCron("invalid"));
        assertEquals(5, service.previewCron("0 * * * * ? 2099").size());
        assertTrue(service.previewCron("0 * * * * ? 2099").stream().allMatch(time -> time.getYear() == 2099));
        assertTrue(service.previewCron("0 * * * * ? 1970").isEmpty());
    }

    @Test void taskOwnerMetadataComesFromTheSystemApi() {
        var service = new DevTaskServiceImpl();
        var users = mock(cn.iocoder.yudao.module.system.api.user.AdminUserApi.class);
        ReflectionTestUtils.setField(service, "adminUserApi", users);
        var canonical = new cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO();
        canonical.setId(9L); canonical.setNickname("canonical owner"); canonical.setMobile("fixture-phone");
        when(users.validateUser(9L)).thenReturn(canonical);
        var req = new cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskSaveReqVO();
        req.setOwnerId(9L);
        var task = new DevTaskDO();
        ReflectionTestUtils.invokeMethod(service, "fillTaskManagementInfo", task, req);
        assertEquals(9L, task.getOwnerId()); assertEquals("canonical owner", task.getOwnerName());
        assertEquals("fixture-phone", task.getContactNumber()); assertEquals("DOLPHIN_SCHEDULER", task.getScheduler());
    }

    @Test void executionAndStatisticsJsonUseTheApplicationWireContract() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class,
                        cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration.class));
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var mapper = context.getBean(tools.jackson.databind.ObjectMapper.class);
            var task = new cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskRespVO();
            task.setId(Long.MAX_VALUE); task.setOwnerId(9L); task.setExecutionConfig(new DevExecutionConfig());
            var tree = mapper.readTree(mapper.writeValueAsBytes(task));
            assertTrue(tree.get("id").isString()); assertEquals(Long.toString(Long.MAX_VALUE), tree.get("id").asString());
            assertTrue(tree.get("ownerId").isNumber()); assertTrue(tree.get("executionConfig").get("retryTimes").isNumber());
            assertTrue(tree.get("executionConfig").get("localParams").isArray());
            var statistics = new cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskStatisticsRespVO();
            statistics.setTodaySuccessRate(66.67); statistics.setRefreshedAt(LocalDateTime.of(2026, 10, 11, 1, 2));
            var stats = mapper.readTree(mapper.writeValueAsBytes(statistics));
            assertTrue(stats.get("todaySuccessRate").isNumber()); assertTrue(stats.get("refreshedAt").isNumber());
        });
    }

    @Test void disabledAncestorAndConcurrentSiblingNameAreBusinessFailures() {
        var service = new DevDirServiceImpl(); var directories = mock(DevDirMapper.class);
        ReflectionTestUtils.setField(service, "dirMapper", directories);
        when(directories.selectById(1L)).thenReturn(DevDirDO.builder().id(1L).parentId(2L).status(0).build());
        when(directories.selectById(2L)).thenReturn(DevDirDO.builder().id(2L).parentId(0L).status(1).build());
        assertThrows(ServiceException.class, () -> service.validateDirExists(1L));
        var req = new DevDirSaveReqVO(); req.setName("duplicate"); req.setParentId(0L);
        doThrow(new org.springframework.dao.DuplicateKeyException("constraint"))
                .when(directories).insert(any(DevDirDO.class));
        assertThrows(ServiceException.class, () -> service.createDir(req));
    }
}
