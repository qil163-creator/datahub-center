package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.executor.builtin.ShellScriptExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EtlScriptCancellationTest {
    @TempDir Path directory;

    @Test
    void silentProcessDoesNotDelayPipelineDeadlineUntilStdoutEnds() {
        long start = System.nanoTime();
        var result = run("silent", null, EtlRunOptions.builder().timeoutSeconds(1).build());
        assertFalse(result.isSuccess());
        assertFalse(result.isTerminationUnconfirmed());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2_500,
                "silent stdout must not block the deadline monitor");
    }

    @Test
    void stopRequestTerminatesChildAndParentEvenWithSilentOutput() throws Exception {
        Path pidFile = directory.resolve("child.pid");
        try {
            long start = System.nanoTime();
            var result = run("child", pidFile, EtlRunOptions.builder()
                    .stopRequested(() -> Files.exists(pidFile)).build());
            assertTrue(result.isStopped());
            assertFalse(result.isTerminationUnconfirmed());
            long childPid = Long.parseLong(Files.readString(pidFile));
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 4);
        } finally {
            if (Files.exists(pidFile)) ProcessHandle.of(Long.parseLong(Files.readString(pidFile)))
                    .ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult run(String mode, Path pidFile, EtlRunOptions options) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = List.of(java, "-cp", System.getProperty("java.class.path"),
                Fixture.class.getName(), mode, pidFile == null ? "unused" : pidFile.toString());
        EtlExecutorRegistry registry = new EtlExecutorRegistry();
        registry.register("script-fixture", (n, c) -> new ShellScriptExecutor(n, c) {
            protected void execute() throws Exception { runProcess(command); }
        });
        EtlNodeDef node = new EtlNodeDef();
        node.setNo("script"); node.setName("script"); node.setToolCode("script-fixture"); node.setToolType(4);
        try (var engine = new EtlPipelineEngine(registry)) {
            return engine.execute(EtlDag.build(List.of(node), List.of()), options);
        }
    }

    /** 独立 JVM 作为跨平台进程夹具，不依赖 bash/cmd 的具体命令。 */
    public static class Fixture {
        public static void main(String[] args) throws Exception {
            if ("child".equals(args[0])) {
                Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), "child-silent").start();
                Files.writeString(Path.of(args[1]), Long.toString(child.pid()));
            }
            Thread.sleep("child-silent".equals(args[0]) ? 30_000 : 5_000);
        }
    }
}
