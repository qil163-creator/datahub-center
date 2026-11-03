package cn.iocoder.yudao.framework.dolphinscheduler;

import cn.iocoder.yudao.framework.dolphinscheduler.model.NativeTaskParameters;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeLocalDeploymentTest {
    @Test void sparkLocalAndStandaloneHaveNoYarnFlags() {
        var local = NativeTaskParameters.spark(spark(), "local[2]");
        assertEquals("local[2]", local.taskParams().get("master")); assertEquals("local", local.taskParams().get("deployMode"));
        assertFalse(String.valueOf(local.taskParams().get("others")).contains("spark.yarn"));
        var remote = NativeTaskParameters.spark(spark(), "spark://spark-master:7077");
        assertEquals("client", remote.taskParams().get("deployMode"));
        assertThrows(IllegalArgumentException.class, () -> NativeTaskParameters.spark(spark(), "local[2]; touch /tmp/x"));
        assertThrows(IllegalArgumentException.class, () -> NativeTaskParameters.spark(spark(), "spark://host:99999"));
    }

    @Test void flinkLocalAndStandaloneKeepAttachedLifecycleAndResources() {
        var local = NativeTaskParameters.flink(flink(), NativeTaskParameters.FlinkDeployment.LOCAL, null);
        String script = String.valueOf(local.taskParams().get("initScript"));
        assertEquals("LOCAL", local.taskParams().get("deployMode")); assertTrue(script.contains("execution.target' = 'local"));
        assertTrue(script.contains("execution.attached' = 'true")); assertTrue(script.contains("taskmanager.numberOfTaskSlots' = '2"));
        assertTrue(script.contains("sql-client.execution.result-mode' = 'TABLEAU"));
        assertTrue(script.contains("jobmanager.memory.process.size' = '1G")); assertFalse(script.contains("yarn.application"));
        var remote = NativeTaskParameters.flink(flink(), NativeTaskParameters.FlinkDeployment.STANDALONE, "flink:8081");
        assertTrue(String.valueOf(remote.taskParams().get("initScript")).contains("rest.address' = 'flink"));
        assertThrows(IllegalArgumentException.class, () -> NativeTaskParameters.flink(flink(), NativeTaskParameters.FlinkDeployment.STANDALONE, "host:8081;id"));
    }

    private static NativeTaskParameters.SparkOptions spark() {
        return new NativeTaskParameters.SparkOptions(NativeTaskParameters.ProgramType.SQL, "SELECT 1", null, null,
                List.of(), List.of(), 1, "512m", 1, 1, "512m", "default", "native_local");
    }

    private static NativeTaskParameters.FlinkOptions flink() {
        return new NativeTaskParameters.FlinkOptions(NativeTaskParameters.ProgramType.SQL, NativeTaskParameters.FlinkMode.BATCH,
                "SELECT 1", null, null, List.of(), List.of(), 2, 2, 2, "1G", "2G", "default", "native_local");
    }
}
