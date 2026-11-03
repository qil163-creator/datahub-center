package cn.iocoder.yudao.framework.etl.core.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in real Docker + independent host JVM crash recovery. No business database is accessed. */
class EtlScriptDockerRecoveryIntegrationTest {
    @TempDir Path directory;
    private static EtlScriptWorkerPolicy policy(Path root, String executable, String endpoint, String image, boolean wsl) {
        var mappings = new ArrayList<EtlScriptWorkerPolicy.HostPathMapping>();
        if (wsl) for (Path drive : FileSystems.getDefault().getRootDirectories())
            mappings.add(new EtlScriptWorkerPolicy.HostPathMapping(drive, "/mnt/" + drive.toString().substring(0, 1).toLowerCase(Locale.ROOT)));
        var recovery = new EtlScriptWorkerPolicy.Recovery(root, "recovery-integration", 4, false);
        var docker = new EtlScriptWorkerPolicy.Docker(Path.of(executable), endpoint, image, mappings,
                false, 5000, 300000, recovery); // Explicit local fixture without strict swap admission.
        return new EtlScriptWorkerPolicy(true, List.of(), 1048576, 8388608, 8388608, 10000, 10000,
                65536, 30000, 30000, 256, EtlScriptWorkerPolicy.Mode.DOCKER, docker);
    }
    private EtlScriptWorkerPolicy policy() {
        String exe = System.getProperty("etl.test.docker.executable"), endpoint = System.getProperty("etl.test.docker.endpoint"), image = System.getProperty("etl.test.docker.image");
        assumeTrue(exe != null && endpoint != null && image != null, "Explicit trusted Docker recovery fixture required");
        return policy(directory, exe, endpoint, image, Boolean.getBoolean("etl.test.docker.wsl"));
    }
    private static String source() {
        return "import java.util.*; import cn.iocoder.yudao.framework.etl.core.script.EtlJavaScript; "
                + "public class Example implements EtlJavaScript { public List<Map<String,Object>> execute(List<Map<String,Object>> rows) { return rows; } }";
    }
    @Test void killedHostWithAcknowledgedIdIsRecoveredWhileAnotherHostWorkerRemainsActive() throws Exception { crashAndRecover("ACK"); }
    @Test void killedHostAfterCreateBeforeIdRegistrationRecoversOnlyItsProvenContainer() throws Exception { crashAndRecover("LOST_ACK"); }

    private void crashAndRecover(String mode) throws Exception {
        var policy = policy(); var docker = policy.docker(); var runner = new EtlScriptRunner(policy);
        String javaCommand = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        var child = new ProcessBuilder(javaCommand, "-cp", System.getProperty("java.class.path"), CrashHost.class.getName(),
                directory.toString(), docker.executable().toString(), docker.endpoint(), docker.image(),
                Boolean.toString(Boolean.getBoolean("etl.test.docker.wsl")), mode).start();
        var readers = Executors.newSingleThreadExecutor(task -> { var thread = new Thread(task, "etl-recovery-ready"); thread.setDaemon(true); return thread; });
        String orphanId = null;
        try {
            var ready = readers.submit(() -> {
                try (var in = new BufferedReader(new InputStreamReader(child.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII))) {
                    String line = in.readLine(); assertNotNull(line); assertTrue(line.length() <= 120); return line;
                }
            }).get(40, TimeUnit.SECONDS);
            String[] parts = ready.split(" "); assertEquals(3, parts.length); assertEquals("READY", parts[0]);
            orphanId = parts[1]; assertTrue(orphanId.matches("[a-f0-9]{64}")); long clientPid = Long.parseLong(parts[2]);
            try (var live = runner.open(EtlScriptRunner.Language.JAVA, source(), () -> { }, () -> fail("Live worker cleanup unconfirmed"))) {
                assertEquals(2, runner.recoverOrphanedWorkers().active());
                child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS));
                var restarted = new EtlScriptRunner(policy); var report = restarted.recoverOrphanedWorkers();
                assertEquals(1, report.active()); assertEquals(1, report.cleaned()); assertEquals(0, report.pending());
                assertAbsent(docker, orphanId);
                assertEquals(List.of(Map.of("v", 1)), live.execute(List.of(Map.of("v", 1))));
                if (clientPid > 0) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (ProcessHandle.of(clientPid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline)
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                    assertFalse(ProcessHandle.of(clientPid).map(ProcessHandle::isAlive).orElse(false));
                }
                assertFalse(report.toString().contains(directory.toString())); assertFalse(report.toString().contains(docker.endpoint()));
            }
            assertEquals(0, runner.activeWorkers());
            assertEquals(0, new EtlScriptRunner(policy).recoverOrphanedWorkers().scanned());
        } finally {
            if (child.isAlive()) child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS);
            child.getInputStream().close(); child.getErrorStream().close(); child.getOutputStream().close();
            readers.shutdownNow(); assertTrue(readers.awaitTermination(2, TimeUnit.SECONDS));
            var last = new EtlScriptRunner(policy).recoverOrphanedWorkers(); assertEquals(0, last.pending());
            if (orphanId != null) assertAbsent(docker, orphanId);
        }
    }
    private static void assertAbsent(EtlScriptWorkerPolicy.Docker docker, String id) throws Exception {
        var process = new ProcessBuilder(docker.executable().toString(), "--host", docker.endpoint(), "ps", "--all", "--no-trunc",
                "--filter", "id=" + id, "--format", "{{.ID}}").start();
        process.getOutputStream().close(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        assertEquals("", new String(process.getInputStream().readAllBytes()).trim());
    }

    /** A genuine second server process: the parent test kills it without orderly Session.close. */
    public static final class CrashHost {
        public static void main(String[] args) {
            try {
                var policy = policy(Path.of(args[0]), args[1], args[2], args[3], Boolean.parseBoolean(args[4]));
                if (args[5].equals("LOST_ACK")) {
                    var runner = new EtlScriptRunner(policy);
                    Method entriesMethod = EtlScriptRunner.class.getDeclaredMethod("workerEntries"); entriesMethod.setAccessible(true);
                    @SuppressWarnings("unchecked") var entries = (List<Path>) entriesMethod.invoke(runner);
                    Method command = EtlDockerWorker.class.getDeclaredMethod("command", List.class, long.class); command.setAccessible(true);
                    var holder = new AtomicReference<EtlDockerWorker>();
                    var driver = new EtlDockerWorker(policy, entries, (arguments, timeout) -> {
                        try {
                            var result = (EtlDockerWorker.CommandResult) command.invoke(holder.get(), arguments, timeout);
                            if (arguments.getFirst().equals("create") && result.exitCode() == 0) {
                                System.out.println("READY " + result.stdout().trim() + " 0"); System.out.flush();
                                for (;;) Thread.sleep(1000); // The parent kills this crash window before ACK is persisted.
                            }
                            return result;
                        } catch (ReflectiveOperationException | InterruptedException failure) { throw new IllegalStateException("Safe fixture control failure"); }
                    });
                    holder.set(driver); driver.preflight(); driver.start();
                } else {
                    var runner = new EtlScriptRunner(policy);
                    try (var worker = runner.open(EtlScriptRunner.Language.JAVA, source(), () -> { }, () -> { })) {
                        System.out.println("READY " + worker.containerId() + " " + worker.processId()); System.out.flush();
                        for (;;) Thread.sleep(1000);
                    }
                }
            } catch (Throwable failure) { System.err.println("RECOVERY_FIXTURE_FAILED"); System.exit(77); }
        }
    }
}
