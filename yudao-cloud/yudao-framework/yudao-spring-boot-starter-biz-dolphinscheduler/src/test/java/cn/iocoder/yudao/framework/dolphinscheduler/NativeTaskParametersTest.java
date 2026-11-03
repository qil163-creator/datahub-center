package cn.iocoder.yudao.framework.dolphinscheduler;

import cn.iocoder.yudao.framework.dolphinscheduler.model.NativeTaskParameters;
import cn.iocoder.yudao.framework.dolphinscheduler.model.NativeTaskParameters.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class NativeTaskParametersTest {
    private FlinkOptions flinkOptions(String sql) {
        return new FlinkOptions(ProgramType.SQL, FlinkMode.STREAMING, sql, null, null,
                List.of(), List.of(new Resource("/managed/resources/connector.jar", "libs/connector.jar")),
                2, 3, 4, "1G", "2G", "fixture-queue", "fixture-app", 60000);
    }

    private SparkOptions sparkOptions(String sql) {
        return new SparkOptions(ProgramType.SQL, sql, null, null, List.of(), List.of(),
                1, "1G", 1, 1, "1G", "fixture", "fixture");
    }

    @Test void typedFlinkRestartPolicyAddsOnlyTrustedSqlOrJarSettings() {
        var original = NativeTaskParameters.flink(flinkOptions("SELECT 1;"));
        var fixed = NativeTaskParameters.withFlinkRestartPolicy(original, 3, 1000);
        assertThat(fixed.taskParams().get("initScript").toString()).contains("SET 'restart-strategy.type' = 'fixed-delay';",
                "SET 'restart-strategy.fixed-delay.attempts' = '3';", "SET 'restart-strategy.fixed-delay.delay' = '1000ms';");
        assertThat(original.taskParams().get("initScript").toString()).doesNotContain("restart-strategy");
        assertThat(fixed.taskParams().get("rawScript")).isEqualTo(original.taskParams().get("rawScript"));
        assertThat(NativeTaskParameters.withFlinkRestartPolicy(original, 0, 0).taskParams().get("initScript").toString())
                .contains("SET 'restart-strategy.type' = 'none';").doesNotContain("restart-strategy.fixed-delay");
        var jar = NativeTaskParameters.flink(new FlinkOptions(ProgramType.JAVA, FlinkMode.STREAMING, null,
                new Resource("/managed/job.jar"), "fixture.Main", List.of(), List.of(), 1, 1, 1, "1G", "1G", "fixture", "fixture"));
        assertThat(NativeTaskParameters.withFlinkRestartPolicy(jar, 2, 500).taskParams().get("others").toString())
                .contains("-Drestart-strategy.type=fixed-delay", "-Drestart-strategy.fixed-delay.attempts=2", "-Drestart-strategy.fixed-delay.delay=500ms");
        assertThat(NativeTaskParameters.withFlinkRestartPolicy(jar, 0, 0).taskParams().get("others").toString())
                .contains("-Drestart-strategy.type=none").doesNotContain("-Drestart-strategy.fixed-delay");
        assertThatThrownBy(() -> NativeTaskParameters.withSqlInitialization(original, "SET 'restart-strategy.type'='none';"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void invalidTypedFlinkRestartPoliciesCannotReachTheNativePlugin() {
        var payload = NativeTaskParameters.flink(flinkOptions("SELECT 1;"));
        for (long[] values : List.of(new long[]{-1, 1000}, new long[]{11, 1000}, new long[]{1, 0}, new long[]{1, -1}, new long[]{1, 300001}, new long[]{0, -1})) {
            assertThatThrownBy(() -> NativeTaskParameters.withFlinkRestartPolicy(payload, (int) values[0], values[1])).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> NativeTaskParameters.withFlinkRestartPolicy(NativeTaskParameters.spark(sparkOptions("SELECT 1;")), 1, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NativeTaskParameters.withFlinkRestartPolicy(null, 1, 1000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void managedJvmProgramsSupportOnlyTypedLocalAndStandaloneDeployments() {
        var options = new FlinkOptions(ProgramType.JAVA, FlinkMode.STREAMING, null, new Resource("/managed/job.jar"), "fixture.Launcher",
                List.of("AAAA=="), List.of(), 2, 1, 1, "1G", "2G", "fixture", "fixture", 60000);
        var local = NativeTaskParameters.flinkManagedProgram(options, FlinkDeployment.LOCAL, null);
        assertThat(local.taskParams()).containsEntry("deployMode", "local").containsEntry("programType", "JAVA").containsEntry("yarnQueue", "");
        assertThat(local.taskParams().get("others").toString()).contains("-Dexecution.target=local", "-Dlocal.number-taskmanager=1", "-Dtaskmanager.numberOfTaskSlots=2");
        var standalone = NativeTaskParameters.flinkManagedProgram(options, FlinkDeployment.STANDALONE, "flink-worker:8081");
        assertThat(standalone.taskParams().get("others").toString()).contains("-Dexecution.target=remote", "-Drest.address=flink-worker", "-Drest.port=8081");
        assertThat(local.taskParams()).doesNotContainKey("rawScript");
        assertThat(local.taskParams().get("initScript")).isEqualTo("");
        for (String address : List.of("https://host:8081", "host:65536", "host:0", "host;touch:8081"))
            assertThatThrownBy(() -> NativeTaskParameters.flinkManagedProgram(options, FlinkDeployment.STANDALONE, address)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NativeTaskParameters.flinkManagedProgram(flinkOptions("SELECT 1;"), FlinkDeployment.LOCAL, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NativeTaskParameters.flink(options, FlinkDeployment.LOCAL, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sparkPayloadFixesItsParserModeAndAcceptsOnlyItsSingleQuotedStringEscapes() {
        String sql = "SELECT 'O\\'Reilly', 'line\\nnext', 'path\\\\safe';";
        var payload = NativeTaskParameters.spark(sparkOptions(sql));
        assertThat(payload.taskParams().get("rawScript")).isEqualTo(sql);
        assertThat(payload.taskParams().get("others").toString()).contains("--conf spark.sql.parser.escapedStringLiterals=false");
        var initialized = NativeTaskParameters.withSqlInitialization(payload,
                "CREATE TEMPORARY VIEW fixture AS SELECT 'O\\'Reilly' AS label;");
        assertThat(initialized.taskParams().get("rawScript").toString()).contains("CREATE TEMPORARY VIEW", sql);
        var local = NativeTaskParameters.spark(sparkOptions(sql), "local[1]");
        assertThat(local.taskParams().get("others").toString()).contains("spark.sql.parser.escapedStringLiterals=false");
        assertThatThrownBy(() -> NativeTaskParameters.flink(flinkOptions(sql))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NativeTaskParameters.validateSql(sql)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sparkEscapesCannotHideManagedSessionChangesOrIdentifierBreakouts() {
        for (String sql : List.of("SELECT 'O\\'Reilly'; SET spark.sql.parser.escapedStringLiterals=true;",
                "SELECT '\\\\'; RESET;", "SELECT 'a\\'; RESET;'; SET spark.sql.shuffle.partitions=1;",
                "SELECT `a\\`; SET spark.sql.shuffle.partitions=1; SELECT `", "SELECT \"a\\\"; SET ignored\";",
                "SELECT 'unterminated\\'", "SELECT 1; /* nested /* inner */ RESET */")) {
            assertThatThrownBy(() -> NativeTaskParameters.spark(sparkOptions(sql))).as(sql).isInstanceOf(IllegalArgumentException.class);
        }
        var payload = NativeTaskParameters.spark(sparkOptions("SELECT 'O\\'Reilly';"));
        assertThatThrownBy(() -> NativeTaskParameters.withSqlInitialization(payload, "SET spark.sql.parser.escapedStringLiterals=true;"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void managedFunctionJarResourcesPassTheFormalPayloadWithoutPermittingSessionCommands() throws Exception {
        String flink = "CREATE TEMPORARY SYSTEM FUNCTION fixture AS 'fixture.Function' USING JAR 'file:///managed/runtime.jar', JAR 'file:///managed/dependency.jar';";
        String spark = "CREATE TEMPORARY FUNCTION fixture AS 'fixture.Function' USING JAR 'file:///managed/runtime.jar', JAR 'file:///managed/dependency.jar';";
        String manifestPath = System.getProperty("datahub.functions.manifest");
        if (manifestPath != null && !manifestPath.isBlank()) {
            var manifest = cn.hutool.json.JSONUtil.parseObj(java.nio.file.Files.readString(java.nio.file.Path.of(manifestPath)));
            flink = manifest.getStr("flinkInitializationSql"); spark = manifest.getStr("sparkInitializationSql");
        }
        var flinkPayload = NativeTaskParameters.withSqlInitialization(NativeTaskParameters.flink(flinkOptions("SELECT 1;")), flink);
        var sparkPayload = NativeTaskParameters.withSqlInitialization(NativeTaskParameters.spark(sparkOptions("SELECT 1;")), spark);
        assertThat(flinkPayload.taskParams().get("rawScript").toString()).startsWith(flink.strip());
        assertThat(sparkPayload.taskParams().get("rawScript").toString()).startsWith(spark.strip());
        assertThat(sparkPayload.taskParams().get("others").toString()).contains("spark.sql.parser.escapedStringLiterals=false");
    }

    @Test void sqlUsesNativePluginAndManagedSynchronousAttachedSessionWithRealDependency() {
        var payload = NativeTaskParameters.flink(flinkOptions("INSERT INTO sink SELECT * FROM source;"));
        assertThat(payload.taskType()).isEqualTo("FLINK");
        assertThat(payload.taskParams()).containsEntry("programType", "SQL").containsEntry("deployMode", "cluster")
                .containsEntry("flinkVersion", ">=1.13").containsEntry("localParams", List.of());
        assertThat(payload.taskParams().get("initScript").toString())
                .contains("'execution.runtime-mode' = 'STREAMING'", "'table.dml-sync' = 'true'",
                        "'execution.attached' = 'true'", "'execution.shutdown-on-attached-exit' = 'true'",
                        "'slotmanager.number-of-slots.max' = '6'", "'execution.checkpointing.interval' = '60000ms'",
                        "ADD JAR './libs/connector.jar';");
        assertThat(payload.taskParams()).doesNotContainKeys("database", "sql", "mainJar");
    }

    @Test void programArgumentsAreSingleShellValuesAndDetachedOptionsAreNotExposed() {
        var options = new FlinkOptions(ProgramType.PYTHON, FlinkMode.BATCH, null,
                new Resource("/managed/resources/job.py"), null,
                List.of("space value", "it's literal", "$(touch /tmp/not-executed)", "; echo ignored"), List.of(),
                1, 1, 1, "1G", "1G", "fixture", "fixture", 1000);
        var params = NativeTaskParameters.flink(options).taskParams();
        assertThat(params.get("mainArgs")).isEqualTo("'space value' 'it'\"'\"'s literal' '$(touch /tmp/not-executed)' '; echo ignored'");
        assertThat(params.get("others").toString()).startsWith("-sae ").contains("-Dexecution.attached=true", "-Dexecution.checkpointing.interval=1000ms")
                .doesNotContain("--detached");
        assertThat(params).containsEntry("programType", "PYTHON").doesNotContainKey("rawScript");
    }

    @Test void validJvmEntryPointsRemainShellLiteralIncludingNestedAndScalaClassNames() {
        for (String mainClass : List.of("fixture.Main", "fixture.Outer$Inner", "fixture.Outer$$Inner", "fixture.Main$")) {
            var main = new Resource("/fixture/job.jar");
            var flink = NativeTaskParameters.flink(new FlinkOptions(ProgramType.JAVA, FlinkMode.BATCH, null,
                    main, mainClass, List.of(), List.of(), 1, 1, 1, "1G", "1G", "fixture", "fixture"));
            var spark = NativeTaskParameters.spark(new SparkOptions(ProgramType.SCALA, null, main, mainClass,
                    List.of(), List.of(), 1, "1G", 1, 1, "1G", "fixture", "fixture"));
            assertThat(flink.taskParams()).containsEntry("mainClass", "'" + mainClass + "'");
            assertThat(spark.taskParams()).containsEntry("mainClass", "'" + mainClass + "'");
        }
    }

    @Test void sqlSessionCommandsCannotOverrideManagedSettingsAfterQuotesCommentsOrSemicolons() {
        for (String command : List.of("SET 'execution.attached'='false'", "RESET", "SOURCE 'extra.sql'",
                "ADD JAR '/unmanaged.jar'", "REMOVE JAR '/managed.jar'", "RUN 'code'", "QUIT", "EXIT", "!echo hello", "dfs -ls /")) {
            assertThatThrownBy(() -> NativeTaskParameters.validateSql("SELECT '; SET ignored'; /* ignored ; */ ; -- ignored\n" + command))
                    .as(command).isInstanceOf(IllegalArgumentException.class);
        }
        NativeTaskParameters.validateSql("-- SET ignored\nSELECT 'SET ''execution.attached''=''false'';';\n/* RESET */ INSERT INTO sink VALUES ('hello');");
    }

    @Test void conflictingEngineBackslashQuotingAndExecutableCommentsAreRejected() {
        for (String script : List.of("SELECT '\\'; SET 'execution.attached'='false'; -- '\\'",
                "SELECT 'a\\\'b; RESET;';", "SELECT 1; /* unterminated", "SELECT 'unterminated",
                "SELECT 1; /*! SET unsafe */", "SELECT 1; /* nested /* inner */ RESET */")) {
            assertThatThrownBy(() -> NativeTaskParameters.validateSql(script)).as(script).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void noTextualDsTemplatesOrInlinePythonAreSilentlyAccepted() {
        for (String script : List.of("SELECT '${value}'", "SELECT '$[yyyyMMdd]'", "SELECT '!{value}'")) {
            assertThatThrownBy(() -> NativeTaskParameters.validateSql(script)).isInstanceOf(IllegalArgumentException.class);
        }
        var python = new SparkOptions(ProgramType.PYTHON, "print('inline')", null, null, List.of(), List.of(),
                1, "1G", 1, 1, "1G", "fixture", "fixture");
        assertThatThrownBy(() -> NativeTaskParameters.spark(python)).isInstanceOf(IllegalArgumentException.class);
        var params = new SparkOptions(ProgramType.PYTHON, null, new Resource("/fixture/job.py"), null,
                List.of("${unsafe}"), List.of(), 1, "1G", 1, 1, "1G", "fixture", "fixture");
        assertThatThrownBy(() -> NativeTaskParameters.spark(params)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void currentAndLegacyResourceResponsesRequireOneUnambiguousStorageName() {
        assertThat(NativeTaskParameters.normalizeResource(Map.of("id", 7, "res", "display.jar", "fullName", "/fixture/job.jar")))
                .isEqualTo(new Resource("/fixture/job.jar"));
        assertThat(NativeTaskParameters.normalizeResource(Map.of("resourceName", "/fixture/job.jar", "resourceRelativePath", "libs/job.jar")))
                .isEqualTo(new Resource("/fixture/job.jar", "libs/job.jar"));
        assertThatThrownBy(() -> NativeTaskParameters.normalizeResource(Map.of("id", 7, "res", "display.jar"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NativeTaskParameters.normalizeResource(Map.of("resourceName", "/fixture/a.jar", "fullName", "/fixture/b.jar")))
                .isInstanceOf(IllegalArgumentException.class);
        for (String name : List.of("/fixture/../escape.jar", "https://host/job.jar?token=secret", "/fixture/a;command.jar", "/fixture/${value}.jar")) {
            assertThatThrownBy(() -> new Resource(name)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new Resource("/fixture/a.jar", "a//b.jar")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void dependencyPathMustBeVerifiedAndCannotCollideWithAnotherArtifact() {
        for (List<Resource> dependencies : List.of(List.of(new Resource("/fixture/a.jar")),
                List.of(new Resource("/fixture/a.jar", "same.jar"), new Resource("/fixture/b.jar", "same.jar")))) {
            var options = new SparkOptions(ProgramType.SQL, "SELECT 1;", null, null, List.of(), dependencies,
                    1, "1G", 1, 1, "1G", "fixture", "fixture");
            assertThatThrownBy(() -> NativeTaskParameters.spark(options)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void limitsAndCollectionsAreBoundedAndImmutable() {
        var args = new ArrayList<>(List.of("first"));
        var options = new SparkOptions(ProgramType.JAVA, null, new Resource("/fixture/job.jar"), "fixture.Main", args,
                List.of(), 1024, "1G", 1, 1024, "1G", "fixture", "fixture");
        args.add("changed");
        var params = NativeTaskParameters.spark(options).taskParams();
        assertThat(params.get("mainArgs")).isEqualTo("'first'");
        assertThatThrownBy(() -> params.put("others", "--detached")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> NativeTaskParameters.validateSql("😀".repeat(NativeTaskParameters.MAX_SCRIPT_BYTES / 4 + 1))).isInstanceOf(IllegalArgumentException.class);
        var excessive = new FlinkOptions(ProgramType.SQL, FlinkMode.BATCH, "SELECT 1", null, null, List.of(), List.of(),
                1, 1, 2, "1G", "1G", "fixture", "fixture");
        assertThatThrownBy(() -> NativeTaskParameters.flink(excessive)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sparkSqlUsesClientDriverAndProgramsKeepAttachedYarnSubmission() {
        var sql = NativeTaskParameters.spark(new SparkOptions(ProgramType.SQL, "SELECT 1;", null, null, List.of(),
                List.of(new Resource("/fixture/a.jar", "libs/a.jar")), 1, "1G", 1, 1, "1G", "fixture", "fixture"));
        assertThat(sql.taskParams()).containsEntry("deployMode", "client").containsEntry("sqlExecutionType", "SCRIPT")
                .containsEntry("master", "yarn").containsEntry("programType", "SQL");
        assertThat(sql.taskParams().get("others").toString()).contains("spark.yarn.submit.waitAppCompletion=true", "--jars './libs/a.jar'");
        assertThat(sql.taskParams()).doesNotContainKey("mainJar");
    }
}
