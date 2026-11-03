package cn.iocoder.yudao.framework.dolphinscheduler;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dolphinscheduler.model.NativeTaskParameters;
import cn.iocoder.yudao.framework.dolphinscheduler.model.NativeTaskParameters.*;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.plugin.task.api.resource.ResourceContext;
import org.apache.dolphinscheduler.plugin.task.flink.FlinkArgsUtils;
import org.apache.dolphinscheduler.plugin.task.flink.FlinkParameters;
import org.apache.dolphinscheduler.plugin.task.flink.FlinkTask;
import org.apache.dolphinscheduler.plugin.task.spark.SparkParameters;
import org.apache.dolphinscheduler.plugin.task.spark.SparkTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Runs published 3.4.2 plugin DTO/command code; no engine or DS server is simulated as executing a job. */
class NativePluginContractTest {
    @TempDir Path directory;

    private TaskExecutionContext context(Payload payload) {
        TaskExecutionContext context = new TaskExecutionContext();
        context.setTaskAppId("native_fixture");
        context.setExecutePath(directory.toString().replace('\\', '/'));
        context.setTaskParams(JSONUtil.toJsonStr(payload.taskParams()));
        context.setPrepareParamsMap(Map.of());
        ResourceContext resources = new ResourceContext();
        resources.addResourceItem(new ResourceContext.ResourceItem("/managed/resources/job.py", "/worker/job.py"));
        resources.addResourceItem(new ResourceContext.ResourceItem("/managed/resources/job.jar", "/worker/job.jar"));
        context.setResourceContext(resources);
        return context;
    }

    @Test void realFlinkSqlPluginConsumesRawScriptAndWritesManagedInitBeforeNativeSqlClient() throws Exception {
        var payload = NativeTaskParameters.flink(new FlinkOptions(ProgramType.SQL, FlinkMode.STREAMING,
                "INSERT INTO sink SELECT * FROM source;", null, null, List.of(),
                List.of(new Resource("/managed/resources/connector.jar", "libs/connector.jar")),
                2, 2, 3, "1G", "2G", "fixture_queue", "fixture_app", 60000));
        assertThat(FlinkParameters.class.getProtectionDomain().getCodeSource().getLocation().toString()).contains("3.4.2");
        var context = context(payload);
        FlinkProbe task = new FlinkProbe(context);
        task.init(); // the vendor's Jackson 2 parser and FileUtils, not a copied DTO.
        FlinkParameters params = (FlinkParameters) task.getParameters();
        assertThat(params.checkParameters()).isTrue();
        assertThat(params.getProgramType().name()).isEqualTo("SQL");
        assertThat(params.getResourceFilesList()).extracting(resource -> resource.getResourceName())
                .containsExactly("/managed/resources/connector.jar");
        assertThat(task.script()).startsWith("${FLINK_HOME}/bin/sql-client.sh -i ").contains("native_fixture_init.sql -f ", "native_fixture_node.sql");
        assertThat(Files.readString(directory.resolve("native_fixture_init.sql")))
                .contains("execution.target=yarn-per-job", "'table.dml-sync' = 'true'", "'execution.attached' = 'true'",
                        "'execution.shutdown-on-attached-exit' = 'true'", "ADD JAR './libs/connector.jar'");
        assertThat(Files.readString(directory.resolve("native_fixture_init.sql")))
                .contains("SET 'yarn.application.name' = 'fixture_app';", "SET 'pipeline.name' = 'fixture_app';");
        assertThat(Files.readString(directory.resolve("native_fixture_node.sql")))
                .isEqualTo("INSERT INTO sink SELECT * FROM source;");
    }

    @Test void realFlinkPythonAndJvmCommandResolveManagedResourcesAndKeepAttachedFlags() {
        for (ProgramType program : List.of(ProgramType.PYTHON, ProgramType.JAVA, ProgramType.SCALA)) {
            var payload = NativeTaskParameters.flink(new FlinkOptions(program, FlinkMode.BATCH, null,
                    new Resource("/managed/resources/job" + (program == ProgramType.PYTHON ? ".py" : ".jar")),
                    program == ProgramType.PYTHON ? null : "fixture.Main", List.of("safe value"),
                    List.of(new Resource("/managed/resources/dependency.jar", "libs/dependency.jar")),
                    1, 1, 1, "1G", "1G", "fixture_queue", "fixture_app"));
            var params = JSONUtils.parseObject(JSONUtil.toJsonStr(payload.taskParams()), FlinkParameters.class);
            assertThat(params.checkParameters()).isTrue();
            var args = FlinkArgsUtils.buildRunCommandLine(context(payload), params);
            assertThat(args).startsWith("${FLINK_HOME}/bin/flink", "run", "-t", "yarn-per-job");
            assertThat(String.join(" ", args)).contains("-sae", "-Dexecution.attached=true", "-C 'file:./libs/dependency.jar'", "'safe value'", "-ynm fixture_app");
            if (program == ProgramType.PYTHON) assertThat(args).containsSubsequence("-py", "/worker/job.py");
            else assertThat(args).containsSubsequence("-c", "'fixture.Main'", "/worker/job.jar");
        }
    }

    @Test void realFlinkSqlPluginParsesTheExplicitLocalModeInsteadOfFallingBackToYarn() throws Exception {
        var payload = NativeTaskParameters.flink(new FlinkOptions(ProgramType.SQL, FlinkMode.BATCH, "SELECT 1;", null, null,
                List.of(), List.of(), 1, 1, 1, "1G", "1G", "fixture", "fixture"), FlinkDeployment.LOCAL, null);
        FlinkProbe task = new FlinkProbe(context(payload)); task.init();
        assertThat(((FlinkParameters) task.getParameters()).getDeployMode().name()).isEqualTo("LOCAL");
        assertThat(task.script()).contains("sql-client.sh");
        assertThat(Files.readString(directory.resolve("native_fixture_init.sql"))).contains("execution.target=local", "SET 'execution.target' = 'local'")
                .doesNotContain("yarn-per-job");
    }

    @Test void realSparkSqlPluginUsesSparkSqlClientAndWritesTheWholeNativeScript() throws Exception {
        var payload = NativeTaskParameters.spark(new SparkOptions(ProgramType.SQL,
                "CREATE TEMP VIEW fixture AS SELECT 1;\nSELECT * FROM fixture;", null, null, List.of(),
                List.of(new Resource("/managed/resources/dependency.jar", "libs/dependency.jar")),
                1, "1G", 2, 1, "1G", "fixture_queue", "fixture_app"));
        assertThat(SparkParameters.class.getProtectionDomain().getCodeSource().getLocation().toString()).contains("3.4.2");
        SparkProbe task = new SparkProbe(context(payload));
        task.init();
        assertThat(((SparkParameters) task.getParameters()).checkParameters()).isTrue();
        assertThat(task.script()).startsWith("${SPARK_HOME}/bin/spark-sql --master yarn --deploy-mode client ")
                .contains("spark.yarn.submit.waitAppCompletion=true", "--jars './libs/dependency.jar'", " -f ", "native_fixture_node.sql")
                .doesNotContain("spark-submit");
        assertThat(task.script()).contains("--name fixture_app");
        assertThat(Files.readString(directory.resolve("native_fixture_node.sql")))
                .isEqualTo("CREATE TEMP VIEW fixture AS SELECT 1;\nSELECT * FROM fixture;");
    }

    @Test void realFlinkJvmPluginConsumesManagedLocalAndStandaloneTargetsWithoutYarnFlags() {
        var options = new FlinkOptions(ProgramType.JAVA, FlinkMode.STREAMING, null, new Resource("/managed/resources/job.jar"),
                "fixture.Launcher", List.of("QUJDRA=="), List.of(), 1, 1, 1, "1G", "1G", "fixture", "fixture", 60000);
        for (var deployment : List.of(FlinkDeployment.LOCAL, FlinkDeployment.STANDALONE)) {
            var payload = NativeTaskParameters.flinkManagedProgram(options, deployment, deployment == FlinkDeployment.STANDALONE ? "flink-worker:8081" : null);
            var parameters = JSONUtils.parseObject(JSONUtil.toJsonStr(payload.taskParams()), FlinkParameters.class);
            assertThat(parameters.checkParameters()).isTrue();
            var args = FlinkArgsUtils.buildRunCommandLine(context(payload), parameters); String command = String.join(" ", args);
            assertThat(command).contains("-c 'fixture.Launcher' /worker/job.jar", "'QUJDRA=='", "-Dexecution.attached=true")
                    .doesNotContain("yarn-per-job", " -ynm ", " -yqu ");
            assertThat(command).contains(deployment == FlinkDeployment.LOCAL ? "-Dexecution.target=local" : "-Drest.address=flink-worker -Drest.port=8081");
        }
    }

    @Test void realSparkPythonAndJvmProgramsUseSparkSubmitWithResolvedResources() {
        for (ProgramType program : List.of(ProgramType.PYTHON, ProgramType.JAVA, ProgramType.SCALA)) {
            var payload = NativeTaskParameters.spark(new SparkOptions(program, null,
                    new Resource("/managed/resources/job" + (program == ProgramType.PYTHON ? ".py" : ".jar")),
                    program == ProgramType.PYTHON ? null : "fixture.Main", List.of("one value"), List.of(),
                    1, "1G", 1, 1, "1G", "fixture_queue", "fixture_app"));
            SparkProbe task = new SparkProbe(context(payload));
            task.init();
            assertThat(task.script()).startsWith("${SPARK_HOME}/bin/spark-submit --master yarn --deploy-mode cluster ")
                    .contains("spark.yarn.submit.waitAppCompletion=true", "'one value'", "--name fixture_app");
            if (program == ProgramType.PYTHON) assertThat(task.script()).contains("/worker/job.py").doesNotContain("--class");
            else assertThat(task.script()).contains("--class 'fixture.Main'", "/worker/job.jar");
        }
    }

    @Test void realJvmPluginCommandsPreserveDollarEntryPointsAsSingleQuotedShellValues() {
        for (String mainClass : List.of("fixture.Outer$Inner", "fixture.Outer$$Inner", "fixture.Main$")) {
            for (ProgramType program : List.of(ProgramType.JAVA, ProgramType.SCALA)) {
                var main = new Resource("/managed/resources/job.jar");
                var flink = NativeTaskParameters.flink(new FlinkOptions(program, FlinkMode.BATCH, null, main,
                        mainClass, List.of(), List.of(), 1, 1, 1, "1G", "1G", "fixture_queue", "fixture_app"));
                var flinkParameters = JSONUtils.parseObject(JSONUtil.toJsonStr(flink.taskParams()), FlinkParameters.class);
                assertThat(flinkParameters.checkParameters()).isTrue();
                var flinkArgs = FlinkArgsUtils.buildRunCommandLine(context(flink), flinkParameters);
                assertThat(flinkArgs).containsSubsequence("-c", "'" + mainClass + "'", "/worker/job.jar");
                assertThat(String.join(" ", flinkArgs)).contains("-c '" + mainClass + "' /worker/job.jar");

                var spark = NativeTaskParameters.spark(new SparkOptions(program, null, main, mainClass,
                        List.of(), List.of(), 1, "1G", 1, 1, "1G", "fixture_queue", "fixture_app"));
                SparkProbe sparkTask = new SparkProbe(context(spark));
                sparkTask.init();
                assertThat(((SparkParameters) sparkTask.getParameters()).checkParameters()).isTrue();
                assertThat(sparkTask.script()).contains("--class '" + mainClass + "'", "/worker/job.jar");
            }
        }
    }

    private static final class FlinkProbe extends FlinkTask {
        private FlinkProbe(TaskExecutionContext context) { super(context); }
        private String script() { return super.getScript(); }
    }
    private static final class SparkProbe extends SparkTask {
        private SparkProbe(TaskExecutionContext context) { super(context); }
        private String script() { return super.getScript(); }
    }
}
