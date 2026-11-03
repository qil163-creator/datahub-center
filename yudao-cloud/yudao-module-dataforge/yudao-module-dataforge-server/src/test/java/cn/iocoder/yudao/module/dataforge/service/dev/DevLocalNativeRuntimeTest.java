package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dolphinscheduler.api.LocalApplicationApi.Execution;
import cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DevLocalNativeRuntimeTest {
    private final DevNativeRuntimeSupport support = new DevNativeRuntimeSupport();
    private final DevNativeRuntimeProperties properties = new DevNativeRuntimeProperties();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(support,"properties",properties);
        DevNativeRuntimeTest.wireSslBundles(support);
    }

    static DevNativeRuntimeProperties.Profile profile(String engine) {
        var profile = DevNativeRuntimeTest.profile(engine);
        profile.setDeployMode("LOCAL"); profile.setEngineVersion("FLINK".equals(engine) ? "1.20.3" : "3.5.6");
        profile.setAllowedTaskTypes(List.of("FLINK".equals(engine) ? "FLINK_SQL" : "SPARK_SQL"));
        profile.setYarnEndpoint(null); profile.setYarnSslBundle(null);
        profile.setLocalObserverEndpoint("https://worker.invalid/observer"); profile.setLocalObserverSslBundle("test-yarn");
        profile.setArtifacts(List.of()); profile.setDependencyResources(List.of());
        return profile;
    }

    @Test
    void localProfilesRequireTrustedObservationAndOnlyExposeTheVerifiedSqlShapes() {
        for (String engine : List.of("FLINK","SPARK")) {
            var profile = profile(engine); properties.setProfiles(List.of(profile));
            assertEquals(1,support.profiles().size());
            assertEquals("NATIVE",support.capability(DevNativeRuntimeTest.task(profile.getAllowedTaskTypes().getFirst(),profile)));
            profile.setAllowedTaskTypes(List.of("FLINK".equals(engine) ? "FLINK_JAR" : "PYSPARK"));
            assertTrue(support.profiles().isEmpty());
        }
    }

    @Test
    void invalidObserverVersionsAndArbitrarySparkMastersCannotCreateExecutableProfiles() {
        var profile = profile("SPARK"); properties.setProfiles(List.of(profile));
        profile.setLocalObserverEndpoint("http://worker.invalid"); assertTrue(support.profiles().isEmpty());
        profile.setLocalObserverEndpoint("https://worker.invalid"); profile.setLocalObserverSslBundle("missing");
        assertTrue(support.profiles().isEmpty());
        profile.setLocalObserverSslBundle("test-yarn");
        for (String master : List.of("yarn","local[*]","local[65]","spark://other:7077","local[1]; id")) {
            profile.setSparkMaster(master); assertTrue(support.profiles().isEmpty(),master);
        }
        profile.setSparkMaster("local[2]"); assertTrue(support.profiles().isEmpty());
        profile.setSparkMaster("local[1]"); assertEquals(1,support.profiles().size());
        profile.setEngineVersion("3.5.7"); assertTrue(support.profiles().isEmpty());
        profile = profile("FLINK"); properties.setProfiles(List.of(profile));
        profile.setEngineVersion("1.18.1"); assertTrue(support.profiles().isEmpty());
        profile.setEngineVersion("1.20.3"); profile.setTenantCode("root"); assertTrue(support.profiles().isEmpty());
    }

    @Test
    void localParametersUseTheApprovedNativePluginOverloadsAndKeepTheGeneratedIdentity() {
        var profile = profile("FLINK"); properties.setProfiles(List.of(profile));
        var task = DevNativeRuntimeTest.task("FLINK_SQL",profile);
        var params = DevNativeParameters.build(task,profile,"dh_native_41").taskParams();
        assertEquals("LOCAL",params.get("deployMode")); assertEquals("",params.get("yarnQueue"));
        assertTrue(String.valueOf(params.get("initScript")).contains("'execution.target' = 'local'"));
        assertTrue(String.valueOf(params.get("initScript")).contains("'pipeline.name' = 'dh_native_41'"));
        assertFalse(String.valueOf(params.get("initScript")).contains("SET 'yarn."));
        profile = profile("SPARK"); profile.setSparkMaster("local[1]"); properties.setProfiles(List.of(profile));
        task = DevNativeRuntimeTest.task("SPARK_SQL",profile);
        params = DevNativeParameters.build(task,profile,"dh_native_42").taskParams();
        assertEquals("local[1]",params.get("master")); assertEquals("local",params.get("deployMode"));
        assertEquals("dh_native_42",params.get("appName"));
        assertFalse(String.valueOf(params.get("others")).contains("spark.yarn.submit.waitAppCompletion"));
        var config = DevTaskDefinition.readConfig(task.getProgramConfigJson()); config.setWorkloadMode("STREAMING");
        task.setProgramConfigJson(JsonUtils.toJsonString(config));
        var streaming = task; assertThrows(ServiceException.class,()->support.resolve(streaming));
    }

    @Test
    void localObserverAndMasterAreFrozenWhileInactiveLocalFieldsDoNotInvalidateYarnSnapshots() {
        var profile = profile("SPARK"); properties.setProfiles(List.of(profile));
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(DevNativeRuntimeTest.task("SPARK_SQL",profile));
        support.freeze(snapshot); assertDoesNotThrow(()->support.requireFrozen(snapshot));
        profile.setSparkMaster("local[2]"); assertThrows(ServiceException.class,()->support.requireFrozen(snapshot));
        profile.setSparkMaster("local[1]"); profile.setLocalObserverEndpoint("https://other.invalid");
        assertThrows(ServiceException.class,()->support.requireFrozen(snapshot));
        var yarn = DevNativeRuntimeTest.profile("FLINK"); properties.setProfiles(List.of(yarn));
        var yarnSnapshot = new DevTaskSnapshot(); yarnSnapshot.setTask(DevNativeRuntimeTest.task("FLINK_SQL",yarn));
        support.freeze(yarnSnapshot);
        yarn.setLocalObserverEndpoint("https://inactive.invalid"); yarn.setLocalObserverSslBundle("inactive"); yarn.setSparkMaster("local[9]");
        assertDoesNotThrow(()->support.requireFrozen(yarnSnapshot));
    }

    @Test
    void schedulerSuccessRequiresEveryObservedExactEngineToHaveSucceededAndExited() {
        assertEquals("SUCCESS",status(List.of(execution("SUCCEEDED",0,false,true,true)),"SUCCESS",false));
        assertNull(status(List.of(),"SUCCESS",false));
        assertNull(status(List.of(execution("FAILED",1,false,true,true)),"SUCCESS",false));
        assertNull(status(List.of(execution("CANCELLED",0,false,true,true)),"SUCCESS",false));
        assertNull(status(List.of(execution("SUCCEEDED",null,false,true,true)),"SUCCESS",false));
        assertNull(status(List.of(execution("SUCCEEDED",0,false,true,true),execution("FAILED",1,false,true,true)),"SUCCESS",false));
        assertNull(status(java.util.Collections.nCopies(65,execution("SUCCEEDED",0,false,true,true)),"SUCCESS",false));
        assertNull(status(List.of(new Execution("other","sandbox-user","FLINK","SUCCEEDED",0,false,true,true)),"SUCCESS",false));
        assertNull(status(List.of(new Execution("dh_native_41","other","FLINK","SUCCEEDED",0,false,true,true)),"SUCCESS",false));
        assertNull(status(List.of(new Execution("dh_native_41","sandbox-user","SPARK","SUCCEEDED",0,false,true,true)),"SUCCESS",false));
    }

    @Test
    void cancellationNeedsAStartedFinishedProcessTreeAndPreservesUnrequestedFailures() {
        var stopped = execution("CANCELLED",143,false,true,true);
        assertEquals("STOPPED",status(List.of(stopped),"FAILED",true));
        assertEquals("STOPPED",status(List.of(stopped),"STOPPED",false));
        assertEquals("FAILED",status(List.of(stopped),"FAILED",false));
        assertNull(status(List.of(execution("RUNNING",null,true,true,false)),"FAILED",true));
        assertNull(status(List.of(execution("CANCELLED",143,true,true,true)),"FAILED",true));
        assertNull(status(List.of(execution("CANCELLED",143,false,false,true)),"FAILED",true));
        assertNull(status(List.of(execution("RUNNING",null,false,false,false)),"STOPPED",true));
    }

    @Test
    void localFlinkRejectsUnverifiedParallelismAndMemoryBeforePublication() {
        var profile = profile("FLINK"); properties.setProfiles(List.of(profile));
        var task = DevNativeRuntimeTest.task("FLINK_SQL",profile);
        var config = DevTaskDefinition.readConfig(task.getProgramConfigJson());
        config.setTaskManagerSlots(2); task.setProgramConfigJson(JsonUtils.toJsonString(config));
        assertThrows(ServiceException.class,()->support.resolve(task));
        config.setTaskManagerSlots(1); config.setTaskManagerMemoryMb(2048); task.setProgramConfigJson(JsonUtils.toJsonString(config));
        assertThrows(ServiceException.class,()->support.resolve(task));
        config.setTaskManagerMemoryMb(768); config.setJobManagerMemoryMb(512); task.setProgramConfigJson(JsonUtils.toJsonString(config));
        assertDoesNotThrow(()->support.resolve(task));
    }

    @Test
    void localSparkRejectsExtraExecutorsWhileYarnRetainsItsExistingResourceRange() {
        var profile = profile("SPARK"); properties.setProfiles(List.of(profile));
        var task = DevNativeRuntimeTest.task("SPARK_SQL",profile);
        var config = DevTaskDefinition.readConfig(task.getProgramConfigJson());
        config.setNumExecutors(2); task.setProgramConfigJson(JsonUtils.toJsonString(config));
        assertThrows(ServiceException.class,()->support.resolve(task));
        config.setNumExecutors(1); config.setExecutorMemoryMb(128); task.setProgramConfigJson(JsonUtils.toJsonString(config));
        assertThrows(ServiceException.class,()->support.resolve(task));
        var yarn = DevNativeRuntimeTest.profile("SPARK"); properties.setProfiles(List.of(yarn));
        var yarnTask = DevNativeRuntimeTest.task("SPARK_SQL",yarn);
        var yarnConfig = DevTaskDefinition.readConfig(yarnTask.getProgramConfigJson());
        yarnConfig.setNumExecutors(2); yarnConfig.setExecutorMemoryMb(2048); yarnTask.setProgramConfigJson(JsonUtils.toJsonString(yarnConfig));
        assertDoesNotThrow(()->support.resolve(yarnTask));
    }

    private static Execution execution(String state,Integer exitCode,boolean alive,boolean started,boolean finished) {
        return new Execution("dh_native_41","sandbox-user","FLINK",state,exitCode,alive,started,finished);
    }

    private static String status(List<Execution> executions,String schedulerStatus,boolean cancel) {
        return DevDsSubmitter.localTerminalStatus(executions,"dh_native_41","sandbox-user","FLINK",schedulerStatus,cancel);
    }
}
