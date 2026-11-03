package cn.iocoder.yudao.framework.etl.core.script;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real containers, opt in with etl.test.docker.{executable,endpoint,image}; no mocks or host-JVM fallback.
 * The local no-swap-limit daemon is used with requireSwapLimit=false explicitly for these fixtures.
 * This demonstrates resident-memory containment; production strict swap admission remains enabled. */
class EtlScriptDockerIntegrationTest {
    private EtlScriptRunner runner(boolean strict, long timeout) {
        String executable = System.getProperty("etl.test.docker.executable");
        String endpoint = System.getProperty("etl.test.docker.endpoint");
        String image = System.getProperty("etl.test.docker.image");
        assumeTrue(executable != null && endpoint != null && image != null, "Explicit trusted Docker integration fixture required");
        var mappings = new ArrayList<EtlScriptWorkerPolicy.HostPathMapping>();
        if (Boolean.getBoolean("etl.test.docker.wsl")) for (Path root : FileSystems.getDefault().getRootDirectories()) {
            String drive = root.toString().substring(0, 1).toLowerCase(Locale.ROOT);
            mappings.add(new EtlScriptWorkerPolicy.HostPathMapping(root, "/mnt/" + drive));
        }
        var docker = new EtlScriptWorkerPolicy.Docker(Path.of(executable), endpoint, image, mappings, strict, 5000);
        return new EtlScriptRunner(new EtlScriptWorkerPolicy(true, List.of(), 1048576, 8388608, 8388608,
                10000, 10000, 65536, 30000, timeout, 256, EtlScriptWorkerPolicy.Mode.DOCKER, docker));
    }
    private static String source(String body) {
        return "import java.util.*; import java.nio.file.*; import cn.iocoder.yudao.framework.etl.core.script.EtlJavaScript; "
                + "public class Example implements EtlJavaScript { private int batch; "
                + "public List<Map<String,Object>> execute(List<Map<String,Object>> rows) throws Exception { " + body + " } }";
    }
    private void assertAbsent(EtlScriptRunner runner, String id) throws Exception {
        var docker = runner.policy().docker();
        Process p = new ProcessBuilder(docker.executable().toString(), "--host", docker.endpoint(), "ps", "--all", "--no-trunc",
                "--filter", "id=" + id, "--format", "{{.ID}}").start();
        p.getOutputStream().close();
        assertTrue(p.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, p.exitValue());
        assertEquals("", new String(p.getInputStream().readAllBytes()).trim());
        assertEquals(0, runner.activeWorkers());
    }
    @Test void realJavaAndJsPreserveTheirBatchContractsAndContainerCleanup() throws Exception {
        var runner = runner(false, 30000); String id;
        try (var session = runner.open(EtlScriptRunner.Language.JAVA, source("rows.getFirst().put(\"batch\", ++batch); return rows;"),
                () -> { }, () -> fail("Container termination unconfirmed"))) {
            id = session.containerId(); assertNotNull(id);
            var rows = List.<Map<String, Object>>of(new LinkedHashMap<>(Map.of("id", 9007199254740993L,
                    "decimal", new BigDecimal("123456789012345678901.12345678901234567890"))));
            var first = session.execute(rows).getFirst();
            assertEquals(rows.getFirst().get("id"), first.get("id")); assertInstanceOf(Long.class, first.get("id"));
            assertEquals(rows.getFirst().get("decimal"), first.get("decimal")); assertInstanceOf(BigDecimal.class, first.get("decimal"));
            assertEquals(1, first.get("batch")); assertEquals(2, session.execute(rows).getFirst().get("batch"));
        }
        assertAbsent(runner, id);
        try (var session = runner.open(EtlScriptRunner.Language.JS, "var n=0; function execute(rows) { rows[0].n=++n; return rows; }",
                () -> { }, () -> fail("Container termination unconfirmed"))) {
            id = session.containerId();
            assertEquals(1, ((Number) session.execute(List.of(Map.of("v", 1))).getFirst().get("n")).intValue());
            assertEquals(2, ((Number) session.execute(List.of(Map.of("v", 1))).getFirst().get("n")).intValue());
        }
        assertAbsent(runner, id);
    }
    @Test void realWorkerSeesNonRootNoCapabilitiesReadOnlyRootAndActualCgroupLimits() throws Exception {
        var runner = runner(false, 30000); String id;
        try (var session = runner.open(EtlScriptRunner.Language.JAVA, source("var r = new LinkedHashMap<String,Object>(); "
                + "r.put(\"status\",Files.readString(Path.of(\"/proc/self/status\"))); "
                + "r.put(\"command\",Arrays.asList(new String(Files.readAllBytes(Path.of(\"/proc/self/cmdline\")),java.nio.charset.StandardCharsets.UTF_8).split(\"\\\\x00\"))); "
                + "r.put(\"memory\",Files.readString(Path.of(\"/sys/fs/cgroup/memory.max\")).trim()); "
                + "r.put(\"pids\",Files.readString(Path.of(\"/sys/fs/cgroup/pids.max\")).trim()); "
                + "r.put(\"cpu\",Files.readString(Path.of(\"/sys/fs/cgroup/cpu.max\")).trim()); "
                + "r.put(\"network\",Files.list(Path.of(\"/sys/class/net\")).map(p->p.getFileName().toString()).toList()); "
                + "var upInterfaces=new ArrayList<String>(); for(var interfaces=java.net.NetworkInterface.getNetworkInterfaces();interfaces.hasMoreElements();) { var net=interfaces.nextElement(); if(net.isUp())upInterfaces.add(net.getName()); } r.put(\"upInterfaces\",upInterfaces); "
                + "r.put(\"routes\",Files.readAllLines(Path.of(\"/proc/net/route\")).stream().skip(1).filter(line->!line.isBlank()).toList()); "
                + "boolean externalConnected=false;try(var socket=new java.net.Socket()){socket.connect(new java.net.InetSocketAddress(\"192.0.2.1\",443),500);externalConnected=true;}catch(java.io.IOException expected){} r.put(\"externalConnected\",externalConnected); "
                + "int supervisorStop=-1; try(var proc=Files.list(Path.of(\"/proc\"))) { for(Path p:proc.toList()) { "
                + "try { if(Files.readString(p.resolve(\"comm\")).trim().equals(\"timeout\")) supervisorStop=new ProcessBuilder(\"/bin/kill\",\"-STOP\",p.getFileName().toString()).start().waitFor(); } catch(java.io.IOException ignored) { } } } "
                + "r.put(\"supervisorStop\",supervisorStop); "
                + "try { Files.writeString(Path.of(\"/etc/etl-test\"),\"x\"); r.put(\"readOnly\",false); } catch(java.io.IOException e) { r.put(\"readOnly\",true); } "
                + "Path tmp=Files.createTempFile(\"etl-test-\",\".txt\"); Files.writeString(tmp,\"ok\"); r.put(\"tmp\",Files.readString(tmp)); Files.delete(tmp); return List.of(r);"),
                () -> { }, () -> fail("Container termination unconfirmed"))) {
            id = session.containerId(); var result = session.execute(List.of(Map.of())).getFirst();
            String status = (String) result.get("status");
            assertTrue(status.contains("Uid:\t65532\t65532\t65532\t65532"));
            assertTrue(status.contains("CapEff:\t0000000000000000")); assertTrue(status.contains("NoNewPrivs:\t1"));
            @SuppressWarnings("unchecked") var command = (List<String>) result.get("command");
            assertEquals("/opt/java/openjdk/bin/java", command.getFirst());
            assertEquals(List.of("-Xms32m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:+ExitOnOutOfMemoryError",
                    "-XX:ActiveProcessorCount=1", "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=/tmp", "-cp"), command.subList(1, 9));
            assertTrue(command.get(9).startsWith("/worker/entry-0")); assertEquals(EtlScriptWorker.class.getName(), command.getLast());
            assertEquals(Long.toString(EtlDockerWorker.MEMORY), result.get("memory")); assertEquals("64", result.get("pids"));
            assertEquals("100000 100000", result.get("cpu"));
            // sysfs 可显示内核占位设备，隔离应核对本进程 namespace 的实际接口与路由，不能按目录名猜测。
            assertEquals(List.of("lo"), result.get("upInterfaces"));assertEquals(List.of(), result.get("routes"));assertEquals(false,result.get("externalConnected"));
            var docker=runner.policy().docker();var inspect=new ProcessBuilder(docker.executable().toString(),"--host",docker.endpoint(),"inspect","--format","{{.HostConfig.NetworkMode}}",id).start();
            assertTrue(inspect.waitFor(5,TimeUnit.SECONDS));assertEquals(0,inspect.exitValue());assertEquals("none",new String(inspect.getInputStream().readAllBytes()).trim());
            assertEquals(true, result.get("readOnly")); assertEquals("ok", result.get("tmp"));
            assertEquals(1, result.get("supervisorStop"), "The script UID must not be able to suspend the trusted supervisor");
        }
        assertAbsent(runner, id);
    }
    @Test void timeoutKillsTheActualContainerBeforeReturning() throws Exception {
        var runner = runner(false, 300); String id;
        try (var session = runner.open(EtlScriptRunner.Language.JAVA, source("while(true) { }"),
                () -> { }, () -> fail("Container termination unconfirmed"))) {
            id = session.containerId(); long start = System.nanoTime();
            assertThrows(IllegalStateException.class, () -> session.execute(List.of(Map.of())));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 5000);
            assertAbsent(runner, id);
        }
    }
    @Test void cancellationAndSystemExitCannotLeaveBackgroundChildrenOutsideTheContainer() throws Exception {
        var runner = runner(false, 30000); String id; var stop = new AtomicBoolean();
        try (var session = runner.open(EtlScriptRunner.Language.JAVA, source("new ProcessBuilder(\"/bin/sleep\",\"600\").start(); System.exit(37); return rows;"),
                () -> { }, () -> fail("Container termination unconfirmed"))) {
            id = session.containerId(); assertThrows(IllegalStateException.class, () -> session.execute(List.of(Map.of())));
            assertAbsent(runner, id);
        }
        try (var session = runner.open(EtlScriptRunner.Language.JAVA, source("while(true) { }"),
                () -> { if(stop.get()) throw new IllegalStateException("stop requested"); }, () -> fail("Container termination unconfirmed"))) {
            id = session.containerId(); stop.set(true);
            assertThrows(IllegalStateException.class, () -> session.execute(List.of(Map.of())));
            assertAbsent(runner, id);
        }
    }
    @Test void strictAdmissionChecksTheActualDaemonSwapCapability() throws Exception {
        var runner = runner(true, 30000); var d = runner.policy().docker();
        var p = new ProcessBuilder(d.executable().toString(), "--host", d.endpoint(), "info", "--format", "{{.SwapLimit}}").start();
        p.getOutputStream().close(); assertTrue(p.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, p.exitValue());
        String supported = new String(p.getInputStream().readAllBytes()).trim();
        if (supported.equals("false")) assertTrue(assertThrows(IllegalStateException.class, runner::ensureAvailable).getMessage().contains("swap"));
        else { assertEquals("true", supported); assertDoesNotThrow(runner::ensureAvailable); }
        assertEquals(0, runner.activeWorkers());
    }

    @Test void independentSupervisorExpiresAnOrphanedInfiniteWorkerWithoutTheParentCleanup() throws Exception {
        var base = runner(false, 30000).policy(); var d = base.docker();
        var shortDocker = new EtlScriptWorkerPolicy.Docker(d.executable(), d.endpoint(), d.image(), d.hostPathMappings(), false, 5000, 5000);
        var policy = new EtlScriptWorkerPolicy(true, base.classpathEntries(), base.maxSourceBytes(), base.maxBatchBytes(),
                base.maxResultBytes(), base.maxBatchRows(), base.maxResultRows(), base.maxLogBytes(),
                base.startupTimeoutMillis(), base.batchTimeoutMillis(), base.heapMegabytes(), base.mode(), shortDocker);
        // Only this fixture directly drives the bounded protocol to simulate parent-client loss without
        // Session.close automatically killing the container. Classpaths still come from the trusted runner.
        var runner = new EtlScriptRunner(policy);
        var entriesMethod = EtlScriptRunner.class.getDeclaredMethod("workerEntries"); entriesMethod.setAccessible(true);
        @SuppressWarnings("unchecked") var entries = (List<Path>) entriesMethod.invoke(runner);
        var driver = new EtlDockerWorker(policy, entries); driver.preflight(); Process attach = null;
        try {
            attach = driver.start();
            byte[] init = EtlScriptProtocol.encode(policy.maxSourceBytes() + 512, out -> {
                out.writeByte(EtlScriptProtocol.INIT); out.writeInt(1);
                EtlScriptProtocol.writeText(out, "JAVA", 16);
                EtlScriptProtocol.writeText(out, source("while(true) { }"), policy.maxSourceBytes());
                out.writeInt(policy.maxBatchBytes()); out.writeInt(policy.maxResultBytes());
                out.writeInt(policy.maxBatchRows()); out.writeInt(policy.maxResultRows()); out.writeInt(policy.maxLogBytes());
            });
            EtlScriptProtocol.writeFrame(attach.getOutputStream(), init);
            try (var response = EtlScriptProtocol.readFrame(attach.getInputStream(), policy.maxResultBytes())) {
                assertEquals(EtlScriptProtocol.OK, response.readUnsignedByte());
                assertEquals(List.of(), EtlScriptProtocol.readRows(response, policy.maxResultRows(), policy.maxResultBytes()));
                EtlScriptProtocol.requireEnd(response);
            }
            byte[] batch = EtlScriptProtocol.encode(policy.maxBatchBytes(), out -> {
                out.writeByte(EtlScriptProtocol.BATCH); EtlScriptProtocol.writeRows(out, List.of(Map.of()), policy.maxBatchRows(), policy.maxBatchBytes());
            });
            EtlScriptProtocol.writeFrame(attach.getOutputStream(), batch);
            attach.destroyForcibly(); assertTrue(attach.waitFor(2, TimeUnit.SECONDS));
            // No kill, rm, or parent watchdog here: Docker wait observes the independent OS supervisor.
            var wait = new ProcessBuilder(d.executable().toString(), "--host", d.endpoint(), "wait", driver.containerId()).start();
            wait.getOutputStream().close();
            assertTrue(wait.waitFor(8, TimeUnit.SECONDS)); assertEquals(0, wait.exitValue());
            assertNotEquals("0", new String(wait.getInputStream().readAllBytes()).trim());
        } finally {
            if (attach != null && attach.isAlive()) attach.destroyForcibly();
            assertTrue(driver.stopAndConfirm(), "Cleanup must confirm the expired container and remove its own ID");
        }
        assertAbsent(runner, driver.containerId());
    }
}
