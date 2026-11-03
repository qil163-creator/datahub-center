package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.enums.dev.DevTaskTypeEnum;
import cn.iocoder.yudao.module.dataforge.model.dev.DevProgramConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DevTaskDefinitionTest {
    @Test void allSixEngineFormsHaveDistinctEngineAndProgramKinds() {
        for (String engine : List.of("FLINK", "SPARK")) {
            for (String program : List.of("SQL", "JAR", "PYTHON")) {
                String type = "PYTHON".equals(program) ? "PY" + engine : engine + "_" + program;
                var resolved = DevTaskDefinition.requireType(type);
                assertEquals(engine, resolved.getEngine());
                assertEquals(program, resolved.getProgramType());
                assertTrue(DevTaskDO.isClusterType(type));
            }
        }
        assertThrows(ServiceException.class, () -> DevTaskDefinition.requireType("FLINK_OTHER"));
    }

    @Test void legacySqlRemainsUsableUntilNativeConfigurationIsExplicitlySaved() {
        for (String type : List.of("FLINK_SQL", "SPARK_SQL")) {
            var task = DevTaskDO.builder().taskType(type).build();
            assertEquals("LEGACY_SQL", DevTaskDefinition.capability(task));
            assertDoesNotThrow(() -> DevTaskDefinition.requireExecutable(task));
            task.setProgramConfigJson("{}");
            assertEquals("NOT_READY", DevTaskDefinition.capability(task));
            assertThrows(ServiceException.class, () -> DevTaskDefinition.requireExecutable(task));
        }
        assertEquals("STANDARD", DevTaskDefinition.capability(DevTaskDO.builder().taskType("SQL").build()));
    }

    @Test void nativeProgramsCannotReachTheJdbcTaskBuilderEvenWithoutConfiguration() {
        var submitter = new DevDsSubmitter();
        ReflectionTestUtils.setField(submitter, "nativeRuntimeSupport", new DevNativeRuntimeSupport());
        for (String type : List.of("FLINK_JAR", "PYFLINK", "SPARK_JAR", "PYSPARK")) {
            var task = DevTaskDO.builder().taskType(type).content("program").build();
            assertEquals("NOT_READY", DevTaskDefinition.capability(task));
            assertThrows(ServiceException.class, () ->
                    ReflectionTestUtils.invokeMethod(submitter, "buildTaskParams", task, new StringBuilder()));
        }
    }

    @Test void rejectsCrossEngineAndNonPythonConfigurationWhileAllowingIncompleteMetadata() {
        var config = new DevProgramConfig();
        assertDoesNotThrow(() -> DevTaskDefinition.validateConfig(DevTaskTypeEnum.FLINK_JAR, config));
        config.setDriverCores(2);
        assertThrows(ServiceException.class, () -> DevTaskDefinition.validateConfig(DevTaskTypeEnum.FLINK_SQL, config));
        config.setDriverCores(null); config.setParallelism(2);
        assertThrows(ServiceException.class, () -> DevTaskDefinition.validateConfig(DevTaskTypeEnum.PYSPARK, config));
        config.setParallelism(null); config.setPythonEnvironment("python-3");
        assertThrows(ServiceException.class, () -> DevTaskDefinition.validateConfig(DevTaskTypeEnum.SPARK_JAR, config));
        assertDoesNotThrow(() -> DevTaskDefinition.validateConfig(DevTaskTypeEnum.PYSPARK, config));
        assertThrows(ServiceException.class, () -> DevTaskDefinition.validateConfig(DevTaskTypeEnum.SQL, config));
    }

    @Test void rejectsAmbiguousOrFutureConfigDocuments() {
        assertNull(DevTaskDefinition.readConfig(null));
        assertEquals(1, DevTaskDefinition.readConfig("{}").getSchemaVersion());
        for (String json : List.of("[]", "null", "{} {}", "{\"schemaVersion\":null}",
                "{\"schemaVersion\":2}", "{\"schemaVersion\":1.2}", "{\"schemaVersion\":\"1\"}")) {
            assertThrows(ServiceException.class, () -> DevTaskDefinition.readConfig(json), json);
        }
    }

    @Test void persistedConfigurationPreservesLargeFileIdentifiersAndArguments() {
        var config = new DevProgramConfig();
        config.setArtifactFileId(9007199254740993L);
        config.setDependencyFileIds(List.of(9007199254740995L));
        config.setArguments(List.of("--date", "2026-10-09", "value with spaces"));
        var restored = DevTaskDefinition.readConfig(JsonUtils.toJsonString(config));
        assertEquals(config, restored);
    }

    @Test void taskResponseUsesTheExistingLosslessFileIdWireContract() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().addModule(
                new cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration()
                        .timestampSupportModuleBean()).build();
        var response = new cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskRespVO();
        var config = new DevProgramConfig();
        config.setArtifactFileId(9007199254740993L);
        config.setDependencyFileIds(List.of(12L, 9007199254740995L));
        response.setProgramConfig(config);
        var wire = mapper.readTree(mapper.writeValueAsString(response)).path("programConfig");
        assertTrue(wire.path("artifactFileId").isString());
        assertEquals("9007199254740993", wire.path("artifactFileId").asString());
        assertTrue(wire.path("dependencyFileIds").get(0).isIntegralNumber());
        assertEquals("9007199254740995", wire.path("dependencyFileIds").get(1).asString());
    }
}
