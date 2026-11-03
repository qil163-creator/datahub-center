package cn.iocoder.yudao.framework.etl.config;

import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.engine.EtlPipelineEngine;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EtlAutoConfigurationTest {
    @TempDir java.nio.file.Path recoveryDirectory;
    @Test
    void propertiesBindAndSharedRuntimeBeansStartWithoutExtraDependencies() {
        try (var runtime = DbSwitchRuntime.standalone(); var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "yudao.etl.file-output.allowed-directories[0]", System.getProperty("java.io.tmpdir"),
                    "yudao.etl.file-output.replace-existing", "true",
                    "yudao.etl.script-worker.enabled", "true",
                    "yudao.etl.script-worker.max-batch-rows", "20")));
            context.registerBean(DbSwitchRuntime.class, () -> runtime);
            context.register(YudaoEtlAutoConfiguration.class);
            context.refresh();
            assertNotNull(context.getBean(EtlPipelineEngine.class));
            assertNotNull(context.getBean(EtlExecutorRegistry.class));
            var policy = context.getBean(EtlProperties.class).fileOutputPolicy();
            assertEquals(1, policy.allowedDirectories().size()); assertTrue(policy.replaceExisting());
            var runner = context.getBean(cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner.class);
            assertTrue(runner.policy().enabled()); assertEquals(20, runner.policy().maxBatchRows());
            assertDoesNotThrow(runner::ensureAvailable);
        }
    }

    @Test
    void missingPropertiesDenyFileOutputAndExistingEngineBeanTakesPrecedence() {
        try (var runtime = DbSwitchRuntime.standalone(); var context = new AnnotationConfigApplicationContext()) {
            var engine = new EtlPipelineEngine(new EtlExecutorRegistry(), runtime);
            context.registerBean(DbSwitchRuntime.class, () -> runtime);
            context.registerBean(EtlPipelineEngine.class, () -> engine);
            context.register(YudaoEtlAutoConfiguration.class);
            context.refresh();
            assertSame(engine, context.getBean(EtlPipelineEngine.class));
            assertTrue(context.getBean(EtlProperties.class).fileOutputPolicy().allowedDirectories().isEmpty());
            var runner = context.getBean(cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner.class);
            assertFalse(runner.policy().enabled()); assertThrows(IllegalStateException.class, runner::ensureAvailable);
            assertEquals(new cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner.RecoveryReport(0, 0, 0, 0, 0), runner.recoverOrphanedWorkers());
        }
    }

    @Test
    void trustedDockerModeBindsWithoutStartingAContainerOrFallingBackToChildJvm() {
        try (var runtime = DbSwitchRuntime.standalone(); var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("docker", Map.of(
                    "yudao.etl.script-worker.mode", "DOCKER",
                    "yudao.etl.script-worker.docker.executable", java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
                    "yudao.etl.script-worker.docker.endpoint", "tcp://127.0.0.1:12345",
                    "yudao.etl.script-worker.docker.image", "eclipse-temurin@sha256:" + "a".repeat(64),
                    "yudao.etl.script-worker.docker.max-lifetime-millis", "120000")));
            context.registerBean(DbSwitchRuntime.class, () -> runtime);
            context.register(YudaoEtlAutoConfiguration.class); context.refresh();
            var runner = context.getBean(cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner.class);
            assertFalse(runner.policy().enabled());
            assertEquals(cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorkerPolicy.Mode.DOCKER, runner.policy().mode());
            assertTrue(runner.policy().docker().requireSwapLimit());
            assertEquals(120000, runner.policy().docker().maxLifetimeMillis());
            assertThrows(IllegalStateException.class, runner::ensureAvailable); assertEquals(0, runner.activeWorkers());
        }
    }

    @Test
    void explicitRecoveryConfigurationBindsAndStartupRecoveryCanBeDisabled() {
        try (var runtime = DbSwitchRuntime.standalone(); var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("recovery", Map.ofEntries(
                    Map.entry("yudao.etl.script-worker.enabled", "true"),
                    Map.entry("yudao.etl.script-worker.mode", "DOCKER"),
                    Map.entry("yudao.etl.script-worker.docker.executable", java.nio.file.Path.of(System.getProperty("java.home"), "bin",
                            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString()),
                    Map.entry("yudao.etl.script-worker.docker.endpoint", "tcp://127.0.0.1:12345"),
                    Map.entry("yudao.etl.script-worker.docker.image", "eclipse-temurin@sha256:" + "a".repeat(64)),
                    Map.entry("yudao.etl.script-worker.docker.recovery.enabled", "true"),
                    Map.entry("yudao.etl.script-worker.docker.recovery.directory", recoveryDirectory.toString()),
                    Map.entry("yudao.etl.script-worker.docker.recovery.service-id", "binding-test"),
                    Map.entry("yudao.etl.script-worker.docker.recovery.max-records", "8"),
                    Map.entry("yudao.etl.script-worker.docker.recovery.recover-on-startup", "false"),
                    Map.entry("yudao.etl.script-worker.docker.recovery.reconciliation-timeout-millis", "2000"))));
            context.registerBean(DbSwitchRuntime.class, () -> runtime); context.register(YudaoEtlAutoConfiguration.class); context.refresh();
            var runner = context.getBean(cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner.class);
            var recovery = runner.policy().docker().recovery();
            assertNotNull(recovery); assertEquals("binding-test", recovery.serviceId()); assertEquals(8, recovery.maxRecords());
            assertEquals(2000, recovery.reconciliationTimeoutMillis()); assertFalse(recovery.recoverOnStartup());
            assertEquals(0, java.util.Objects.requireNonNull(recoveryDirectory.toFile().list()).length);
        }
    }
}
