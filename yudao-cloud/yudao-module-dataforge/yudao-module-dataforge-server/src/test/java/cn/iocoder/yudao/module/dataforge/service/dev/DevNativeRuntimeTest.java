package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties;
import cn.iocoder.yudao.module.dataforge.model.dev.DevProgramConfig;
import cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DevNativeRuntimeTest {
    final DevNativeRuntimeSupport support = new DevNativeRuntimeSupport();
    final DevNativeRuntimeProperties properties = new DevNativeRuntimeProperties();
    final ExecutionAccessService access = mock(ExecutionAccessService.class);
    DevNativeRuntimeProperties.Profile profile;

    @BeforeEach void setup() {
        TenantContextHolder.setTenantId(7L);
        ReflectionTestUtils.setField(support, "properties", properties);
        ReflectionTestUtils.setField(support, "executionAccessService", access);
        wireSslBundles(support);
        profile = profile("FLINK"); properties.setProfiles(List.of(profile));
    }
    @AfterEach void clear() { TenantContextHolder.clear(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    static DevNativeRuntimeProperties.Profile profile(String engine) {
        var p = new DevNativeRuntimeProperties.Profile(); p.setCode("sandbox"); p.setName("Sandbox"); p.setRevision("revision-1");
        p.setDsProjectCode(19L); p.setEngine(engine); p.setEngineVersion("FLINK".equals(engine) ? "1.18" : "3.5");
        p.setWorkerGroup("sandbox-workers"); p.setTenantCode("sandbox-user"); p.setEnvironmentCode(29L); p.setQueue("restricted");
        p.setYarnEndpoint("https://yarn.invalid/proxy"); p.setYarnSslBundle("test-yarn");
        p.setAllowedTaskTypes("FLINK".equals(engine) ? List.of("FLINK_SQL", "FLINK_JAR", "PYFLINK") : List.of("SPARK_SQL", "SPARK_JAR", "PYSPARK"));
        var jar = new DevNativeRuntimeProperties.Artifact(); jar.setFileId(9007199254740993L); jar.setName("Approved jar");
        jar.setSha256("a".repeat(64)); jar.setResourceName("/approved/example.jar"); jar.setProgramTypes(List.of("JAR")); jar.setEntryPoint("org.example.Main");
        var python = new DevNativeRuntimeProperties.Artifact(); python.setFileId(32L); python.setName("Approved Python");
        python.setSha256("b".repeat(64)); python.setResourceName("/approved/example.py"); python.setProgramTypes(List.of("PYTHON"));
        var dependency = new DevNativeRuntimeProperties.Artifact(); dependency.setFileId(33L); dependency.setName("Approved dependency");
        dependency.setSha256("c".repeat(64)); dependency.setResourceName("/approved/connector.jar"); dependency.setWorkerRelativePath("approved/connector.jar");
        p.setArtifacts(List.of(jar, python)); p.setDependencyResources(List.of(dependency)); return p;
    }

    static DevTaskDO task(String type, DevNativeRuntimeProperties.Profile p) {
        var config = new DevProgramConfig(); config.setRuntimeProfileCode(p.getCode()); config.setEngineVersion(p.getEngineVersion()); config.setQueue(p.getQueue());
        if (type.endsWith("JAR")) { config.setArtifactFileId(9007199254740993L); config.setArtifactSha256("a".repeat(64)); config.setEntryPoint("org.example.Main"); }
        if (type.startsWith("PY")) { config.setArtifactFileId(32L); config.setArtifactSha256("b".repeat(64)); }
        return DevTaskDO.builder().id(1L).taskType(type).content(type.endsWith("SQL") ? "SELECT 1" : "")
                .timeoutSeconds(300).programConfigJson(JsonUtils.toJsonString(config)).executionUserId(42L).build();
    }

    @Test void exposesSafeOptionsAndPreservesLongWireIds() {
        var other = profile("SPARK"); other.setCode("spark-sandbox"); properties.setProfiles(List.of(profile, other));
        assertEquals(2, support.profiles().size());
        var mapper = tools.jackson.databind.json.JsonMapper.builder().addModule(
                new cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        String json = mapper.writeValueAsString(support.profiles());
        assertFalse(json.contains("resourceName")); assertFalse(json.contains("workerRelativePath"));
        assertFalse(json.contains("workerGroup")); assertFalse(json.contains("tenantCode"));
        assertEquals("9007199254740993", mapper.readTree(json).get(0).path("artifacts").get(0).path("fileId").asString());
    }

    @Test void enablesAllSixShapesOnlyWithMatchingManagedConfiguration() {
        for (String engine : List.of("FLINK", "SPARK")) {
            profile = profile(engine); properties.setProfiles(List.of(profile));
            for (String type : profile.getAllowedTaskTypes()) assertEquals("NATIVE", support.capability(task(type, profile)), type);
        }
        assertEquals("LEGACY_SQL", support.capability(DevTaskDO.builder().taskType("FLINK_SQL").build()));
        assertEquals("NOT_READY", support.capability(DevTaskDO.builder().taskType("FLINK_JAR").build()));
    }

    @Test void rejectsForeignResourceWrongDigestAndModifiedEntrypoint() {
        var task = task("FLINK_JAR", profile); var config = DevTaskDefinition.readConfig(task.getProgramConfigJson());
        for (Long bad : List.of(123L, 32L)) { config.setArtifactFileId(bad); task.setProgramConfigJson(JsonUtils.toJsonString(config)); assertThrows(ServiceException.class, () -> support.resolve(task)); }
        config.setArtifactFileId(9007199254740993L); config.setArtifactSha256("d".repeat(64)); task.setProgramConfigJson(JsonUtils.toJsonString(config));
        assertThrows(ServiceException.class, () -> support.resolve(task));
        config.setArtifactSha256("a".repeat(64)); config.setEntryPoint("org.example.Unapproved"); task.setProgramConfigJson(JsonUtils.toJsonString(config));
        assertThrows(ServiceException.class, () -> support.resolve(task));
    }

    @Test void profileContentChangeEvenWithoutRevisionChangeRequiresRepublish() {
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(task("FLINK_SQL", profile)); support.freeze(snapshot);
        var restored = DevTaskSnapshot.read(snapshot.write()); assertEquals(19L, restored.getNativeProjectCode());
        assertDoesNotThrow(() -> support.requireFrozen(restored));
        profile.setWorkerGroup("different-workers"); assertThrows(ServiceException.class, () -> support.requireFrozen(restored));
    }

    @Test void refusesDuplicateProfilesResourcesAndUnmanagedExecutionTargets() {
        properties.setProfiles(List.of(profile, profile)); assertTrue(support.profiles().isEmpty());
        properties.setProfiles(List.of(profile)); profile.setDependencyResources(List.of(profile.getArtifacts().getFirst())); assertTrue(support.profiles().isEmpty());
        profile.setDependencyResources(List.of()); profile.setWorkerGroup("default"); assertTrue(support.profiles().isEmpty());
        profile.setWorkerGroup("sandbox"); profile.setTenantCode("root"); assertTrue(support.profiles().isEmpty());
        profile.setTenantCode("sandbox-user"); profile.setDsVersion("3.2.2"); assertTrue(support.profiles().isEmpty());
    }

    @Test void missingOrInvalidRmObservationBundleCannotExposeAnExecutableProfile() {
        profile.setYarnEndpoint(null); assertTrue(support.profiles().isEmpty()); assertEquals("NOT_READY", support.capability(task("FLINK_SQL", profile)));
        profile.setYarnEndpoint("http://yarn.invalid"); assertTrue(support.profiles().isEmpty());
        profile.setYarnEndpoint("https://yarn.invalid/proxy"); profile.setYarnSslBundle(null); assertTrue(support.profiles().isEmpty());
        profile.setYarnSslBundle("missing"); assertTrue(support.profiles().isEmpty());
        profile.setYarnSslBundle("test-yarn"); profile.setTenantCode("unsafe user"); assertTrue(support.profiles().isEmpty());
    }

    @Test void nativeProgramsCannotClaimJdbcDatasourceOrBoundParameters() {
        var task = task("FLINK_SQL", profile); task.setDatabaseId(2L); assertThrows(ServiceException.class, () -> support.resolve(task));
        task.setDatabaseId(null); task.setDsDatasourceId(9); assertThrows(ServiceException.class, () -> support.resolve(task));
        task.setDsDatasourceId(null); var config = new DevExecutionConfig(); var parameter = new DevExecutionConfig.LocalParam();
        parameter.setProp("id"); parameter.setValue("1"); config.setLocalParams(List.of(parameter)); task.setExecutionConfigJson(JsonUtils.toJsonString(config));
        assertThrows(ServiceException.class, () -> support.resolve(task));
    }

    @Test void nativePermissionIsItsOwnRequiredCheckRatherThanAnyPermissionOr() {
        support.requireActor(task("FLINK_SQL", profile), 42L);
        verify(access).requireUserPermission(42L, ExecutionAccessService.PERMISSION_DEV_TASK_NATIVE);
        verifyNoMoreInteractions(access);
        doThrow(new ServiceException(1000, "denied")).when(access).requireUserPermission(42L, ExecutionAccessService.PERMISSION_DEV_TASK_NATIVE);
        assertThrows(ServiceException.class, () -> support.requireActor(task("FLINK_SQL", profile), 42L));
    }

    @Test void streamMayDisableTimeoutWhileBatchCannotAndBoundsAreValidated() {
        var task = task("FLINK_SQL", profile); task.setTimeoutSeconds(0);
        assertThrows(ServiceException.class, () -> support.resolve(task));
        var config = DevTaskDefinition.readConfig(task.getProgramConfigJson()); config.setWorkloadMode("STREAMING");
        task.setProgramConfigJson(JsonUtils.toJsonString(config)); assertDoesNotThrow(() -> support.resolve(task));
        config.setTaskManagerSlots(1025); task.setProgramConfigJson(JsonUtils.toJsonString(config)); assertThrows(ServiceException.class, () -> support.resolve(task));
    }

    @Test void nativeBuilderUsesActualPluginTypesManagedResourcesAndSafeArgvWithNoJdbcAccess() {
        var submitter = new DevDsSubmitter();
        var ds = mock(cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient.class);
        var resources = mock(cn.iocoder.yudao.framework.dolphinscheduler.api.ResourceApi.class);
        var jdbc = mock(cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver.class);
        var scripts = mock(DevScriptAuthorizationSupport.class);
        ReflectionTestUtils.setField(submitter, "nativeRuntimeSupport", support); ReflectionTestUtils.setField(submitter, "dsClient", ds);
        ReflectionTestUtils.setField(submitter, "databaseResolver", jdbc); ReflectionTestUtils.setField(submitter, "scriptAuthorizationSupport", scripts);
        when(ds.resource()).thenReturn(resources);
        when(resources.resourceSha256("/approved/example.jar", 67_108_864L)).thenReturn("a".repeat(64));
        when(resources.resourceSha256("/approved/example.py", 67_108_864L)).thenReturn("b".repeat(64));
        when(resources.resourceSha256("/approved/connector.jar", 67_108_864L)).thenReturn("c".repeat(64));
        for (String engine : List.of("FLINK", "SPARK")) {
            profile = profile(engine); properties.setProfiles(List.of(profile));
            for (String type : profile.getAllowedTaskTypes()) {
                var task = task(type, profile); var config = DevTaskDefinition.readConfig(task.getProgramConfigJson());
                config.setDependencyFileIds(List.of(33L));
                if (!type.endsWith("SQL")) config.setArguments(List.of("literal'; touch injected; '"));
                task.setProgramConfigJson(JsonUtils.toJsonString(config));
                cn.hutool.json.JSONObject params = ReflectionTestUtils.invokeMethod(submitter, "buildTaskParams", task, new StringBuilder());
                assertFalse(params.containsKey("datasource")); assertFalse(params.containsKey("sql"));
                assertEquals(0, params.getJSONArray("localParams").size());
                if (type.endsWith("SQL")) assertEquals("SELECT 1", params.getStr("rawScript"));
                else assertEquals(type.startsWith("PY") ? "/approved/example.py" : "/approved/example.jar", params.getJSONObject("mainJar").getStr("resourceName"));
                String definitions = ReflectionTestUtils.invokeMethod(submitter, "buildTaskDefinitionJson", task, 55L, params, "sandbox-workers");
                var node = new cn.hutool.json.JSONArray(definitions).getJSONObject(0);
                assertEquals(engine, node.getStr("taskType")); assertEquals(29L, node.getLong("environmentCode"));
                assertTrue(type.endsWith("SQL") || params.getStr("mainArgs").startsWith("'"));
            }
        }
        verifyNoInteractions(jdbc, scripts);
        verify(resources, atLeastOnce()).resourceSha256("/approved/example.jar", 67_108_864L);
    }

    @Test void verifiesActualResourceDigestBeforePublicationAndNeverBuildsChangedBytes() {
        var submitter = new DevDsSubmitter(); var ds = mock(cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient.class);
        var resources = mock(cn.iocoder.yudao.framework.dolphinscheduler.api.ResourceApi.class);
        ReflectionTestUtils.setField(submitter, "nativeRuntimeSupport", support); ReflectionTestUtils.setField(submitter, "dsClient", ds);
        when(ds.resource()).thenReturn(resources);
        when(resources.resourceSha256(anyString(), anyLong())).thenReturn("e".repeat(64));
        assertThrows(ServiceException.class, () -> submitter.validateForSave(task("FLINK_JAR", profile)));
        verify(ds, never()).workflowDefinition(); verify(ds, never()).executor();
        verify(access).requireUserPermission(42L, ExecutionAccessService.PERMISSION_DEV_TASK_NATIVE);
    }

    @Test void frozenNativeRecordsStillAllowCleanupAfterProfileRetirementAndRejectOtherProjects() {
        cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.setLoginUser(
                new cn.iocoder.yudao.framework.security.core.LoginUser().setId(42L).setTenantId(7L).setUserType(2),
                new org.springframework.mock.web.MockHttpServletRequest());
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(task("FLINK_SQL", profile)); support.freeze(snapshot);
        var record = cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO.builder()
                .dsProjectCode(19L).snapshotJson(snapshot.write()).build(); properties.setProfiles(List.of()); assertDoesNotThrow(() -> support.requireRecordAccess(record));
        record.setDsProjectCode(29L); assertThrows(ServiceException.class, () -> support.requireRecordAccess(record));
        record.setDsProjectCode(19L); assertDoesNotThrow(() -> support.requireRecordAccess(record));
        doThrow(new ServiceException(1000, "denied")).when(access)
                .requireUserPermission(42L, ExecutionAccessService.PERMISSION_DEV_TASK_NATIVE);
        assertThrows(ServiceException.class, () -> support.requireRecordAccess(record));
    }

    @Test void defaultProfilesAreEmptyAndSpringBindsTheSandboxContract() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(RuntimeContext.class)
                .run(context -> { assertNull(context.getStartupFailure(), () -> rootCause(context.getStartupFailure())); assertTrue(context.getBean(DevNativeRuntimeSupport.class).profiles().isEmpty()); });
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(RuntimeContext.class)
                .withPropertyValues("dataforge.dev.native.profiles[0].code=sandbox", "dataforge.dev.native.profiles[0].revision=1",
                        "dataforge.dev.native.profiles[0].ds-project-code=19",
                        "dataforge.dev.native.profiles[0].engine=FLINK", "dataforge.dev.native.profiles[0].engine-version=1.18",
                        "dataforge.dev.native.profiles[0].worker-group=sandbox-workers", "dataforge.dev.native.profiles[0].tenant-code=sandbox-user",
                        "dataforge.dev.native.profiles[0].environment-code=29", "dataforge.dev.native.profiles[0].queue=restricted",
                        "dataforge.dev.native.profiles[0].yarn-endpoint=https://yarn.invalid/proxy", "dataforge.dev.native.profiles[0].yarn-ssl-bundle=test-yarn",
                        "dataforge.dev.native.profiles[0].allowed-task-types[0]=FLINK_SQL")
                .run(context -> { assertNull(context.getStartupFailure(), () -> rootCause(context.getStartupFailure())); assertEquals(1, context.getBean(DevNativeRuntimeSupport.class).profiles().size()); });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(DevNativeRuntimeProperties.class)
    @org.springframework.context.annotation.Import(DevNativeRuntimeSupport.class)
    static class RuntimeContext {
        // 依赖是外部领域服务；注册已完成的替身避免扫描替身类上的数据库/RPC 注入点。
        @org.springframework.context.annotation.Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor accessDependency() {
            return factory -> factory.registerSingleton("executionAccessService", mock(ExecutionAccessService.class));
        }
        @org.springframework.context.annotation.Bean org.springframework.boot.ssl.SslBundles sslBundles() { return testSslBundles(); }
    }

    static void wireSslBundles(DevNativeRuntimeSupport support) {
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerSingleton("sslBundles", testSslBundles());
        ReflectionTestUtils.setField(support, "sslBundles", factory.getBeanProvider(org.springframework.boot.ssl.SslBundles.class));
    }

    private static org.springframework.boot.ssl.SslBundles testSslBundles() {
        var bundles = mock(org.springframework.boot.ssl.SslBundles.class); var bundle = mock(org.springframework.boot.ssl.SslBundle.class);
        when(bundles.getBundle("test-yarn")).thenReturn(bundle);
        try { when(bundle.createSslContext()).thenReturn(javax.net.ssl.SSLContext.getDefault()); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        return bundles;
    }

    private static String rootCause(Throwable failure) {
        if (failure == null) return "";
        while (failure.getCause() != null) failure = failure.getCause();
        return failure.toString();
    }
}
