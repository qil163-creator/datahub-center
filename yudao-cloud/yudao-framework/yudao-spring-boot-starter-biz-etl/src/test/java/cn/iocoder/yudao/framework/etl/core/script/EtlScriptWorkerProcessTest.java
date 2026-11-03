package cn.iocoder.yudao.framework.etl.core.script;

import org.junit.jupiter.api.Test;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Every success/failure path here uses a real child JVM, not a mocked script engine. */
class EtlScriptWorkerProcessTest {
    private static final String SECRET = "script-sensitive-sentinel";
    private static String javaSource(String body) {
        return "import java.util.*; import cn.iocoder.yudao.framework.etl.core.script.EtlJavaScript; "
                + "public class Example implements EtlJavaScript { private int batch; "
                + "public List<Map<String,Object>> execute(List<Map<String,Object>> rows) throws Exception { " + body + " } }";
    }
    private EtlScriptRunner runner(long batchTimeout, int batchBytes, int resultRows) {
        return new EtlScriptRunner(new EtlScriptWorkerPolicy(true, List.of(), 1024 * 1024, batchBytes, 8 * 1024 * 1024,
                10_000, resultRows, 64 * 1024, 30_000, batchTimeout, 256));
    }
    private EtlScriptRunner runner() { return runner(30_000, 8 * 1024 * 1024, 10_000); }

    @Test void javaRowsRetainExactNumericAndTemporalTypesAcrossTheRealWorker() {
        var input = new LinkedHashMap<String, Object>();
        input.put("long", 9_007_199_254_740_993L);
        input.put("decimal", new BigDecimal("123456789012345678901.12345678901234567890"));
        input.put("integer", new BigInteger("1234567890123456789012345678901234567890"));
        input.put("date", new Date(1_730_001_234_567L));
        var timestamp = java.sql.Timestamp.valueOf("2026-10-08 12:34:56.123456789");
        input.put("timestamp", timestamp); input.put("sql-date", new java.sql.Date(1_730_001_234_567L));
        input.put("sql-time", new java.sql.Time(1_730_001_234_567L));
        input.put("local", LocalDateTime.of(2026, 10, 8, 12, 34, 56, 123_456_789));
        input.put("offset", OffsetTime.of(12, 34, 56, 123_456_789, ZoneOffset.ofHours(8)));
        input.put("instant", Instant.parse("2026-10-08T12:34:56.123456789Z"));
        input.put("uuid", UUID.fromString("3dd8aa3c-59ec-48ae-b62b-a5539ef62caa"));
        input.put("nested", List.of(Map.of("id", 9_007_199_254_740_993L)));
        input.put("null", null); input.put("bytes", new byte[] { 0, 1, 2, -1 });
        var runner = runner();
        try (var worker = runner.open(EtlScriptRunner.Language.JAVA, javaSource("return rows;"), () -> { }, () -> fail("cleanup"))) {
            var output = worker.execute(List.of(input)).getFirst();
            for (var entry : input.entrySet()) {
                if ("bytes".equals(entry.getKey())) { assertArrayEquals((byte[]) entry.getValue(), (byte[]) output.get(entry.getKey())); continue; }
                assertEquals(entry.getValue(), output.get(entry.getKey()), entry.getKey());
                if (entry.getValue() != null && !(entry.getValue() instanceof Map<?, ?>) && !(entry.getValue() instanceof List<?>))
                    assertEquals(entry.getValue().getClass(), output.get(entry.getKey()).getClass(), entry.getKey());
            }
        }
        assertEquals(0, runner.activeWorkers());
    }

    @Test void javaRetainsInstanceStateButRunsOutsideTheParentJvm() {
        try (var worker = runner().open(EtlScriptRunner.Language.JAVA,
                javaSource("return List.of(Map.of(\"batch\", ++batch, \"pid\", ProcessHandle.current().pid()));"), () -> { }, () -> fail("cleanup"))) {
            assertNotEquals(ProcessHandle.current().pid(), worker.processId());
            assertEquals(1, worker.execute(List.of(Map.of())).getFirst().get("batch"));
            var second = worker.execute(List.of(Map.of())).getFirst();
            assertEquals(2, second.get("batch")); assertEquals(worker.processId(), second.get("pid"));
        }
    }

    @Test void jsRetainsStateAndOriginalJsonObjectArrayContract() {
        String code = "var batch = 0; function execute(rows) { batch++; rows[0].batch = batch; rows[0].value += 2; return rows; }";
        try (var worker = runner().open(EtlScriptRunner.Language.JS, code, () -> { }, () -> fail("cleanup"))) {
            var first = worker.execute(List.of(Map.of("value", 40))).getFirst();
            assertEquals(42, ((Number) first.get("value")).intValue()); assertEquals(1, ((Number) first.get("batch")).intValue());
            assertEquals(2, ((Number) worker.execute(List.of(Map.of("value", 1))).getFirst().get("batch")).intValue());
        }
    }

    @Test void systemExitTerminatesOnlyTheWorkerAndReleasesTheSlot() {
        var runner = runner(); long pid;
        try (var worker = runner.open(EtlScriptRunner.Language.JAVA,
                javaSource("System.exit(37); return rows;"), () -> { }, () -> fail("cleanup"))) {
            pid = worker.processId();
            var failure = assertThrows(IllegalStateException.class, () -> worker.execute(List.of(Map.of("id", 1))));
            assertTrue(failure.getMessage().contains("退出")); assertNull(failure.getCause());
        }
        assertFalse(alive(pid)); assertEquals(0, runner.activeWorkers());
        assertTrue(runner.checkJava(javaSource("return rows;")).isEmpty());
    }

    @Test void silentInfiniteJavaExecutionIsKilledAtTheBatchDeadline() {
        var runner = runner(300, 8 * 1024 * 1024, 10_000); long pid;
        try (var worker = runner.open(EtlScriptRunner.Language.JAVA, javaSource("while (true) { }"), () -> { }, () -> fail("cleanup"))) {
            pid = worker.processId(); long start = System.nanoTime();
            assertThrows(IllegalStateException.class, () -> worker.execute(List.of(Map.of())));
            assertFalse(alive(pid), "a deadline must terminate the process before execute returns");
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2_000);
        }
        assertFalse(alive(pid)); assertEquals(0, runner.activeWorkers());
    }

    @Test void infiniteJsInitializationIsBoundedAndCleansItsProcess() {
        var runner = new EtlScriptRunner(new EtlScriptWorkerPolicy(true, List.of(), 1024 * 1024,
                8 * 1024 * 1024, 8 * 1024 * 1024, 10_000, 10_000, 64 * 1024, 300, 30_000, 256));
        var unconfirmed = new AtomicBoolean(); long start = System.nanoTime();
        var failure = assertThrows(IllegalStateException.class, () -> runner.open(EtlScriptRunner.Language.JS,
                "while(true) {} function execute(rows) { return rows; }", () -> { }, () -> unconfirmed.set(true)));
        assertTrue(failure.getMessage().contains("截止"));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3_000);
        assertFalse(unconfirmed.get()); assertEquals(0, runner.activeWorkers());
    }

    @Test void explicitCancellationDoesNotWaitForSilentWorkerOutput() {
        var cancelled = new AtomicBoolean(); var runner = runner(); long pid;
        try (var worker = runner.open(EtlScriptRunner.Language.JAVA, javaSource("while(true) {}"), () -> {
            if (cancelled.get()) throw new IllegalStateException("任务已停止");
        }, () -> fail("cleanup"))) {
            pid = worker.processId(); cancelled.set(true);
            assertThrows(IllegalStateException.class, () -> worker.execute(List.of(Map.of())));
            assertFalse(alive(pid), "cancellation must terminate the process before execute returns");
        }
        assertFalse(alive(pid)); assertEquals(0, runner.activeWorkers());
    }

    @Test void observedDescendantsAreTerminatedBeforeReturningToThePipeline() {
        String body = "String javaCommand = java.nio.file.Path.of(System.getProperty(\"java.home\"),\"bin\", "
                + "System.getProperty(\"os.name\").toLowerCase().contains(\"windows\") ? \"java.exe\" : \"java\").toString();"
                + "Process child = new ProcessBuilder(javaCommand,\"-cp\",System.getProperty(\"java.class.path\"),"
                + "\"cn.iocoder.yudao.framework.etl.core.script.EtlScriptWorker\").start();"
                + "return List.of(Map.of(\"pid\", child.pid()));";
        long childPid;
        try (var worker = runner().open(EtlScriptRunner.Language.JAVA, javaSource(body), () -> { }, () -> fail("cleanup"))) {
            childPid = (Long) worker.execute(List.of(Map.of())).getFirst().get("pid");
        }
        assertFalse(alive(childPid));
    }

    @Test void workerExceptionsAndPrintedDataNeverBecomeParentErrors() {
        String source = javaSource("System.out.println(\"" + SECRET + "\"); System.err.println(\"" + SECRET
                + "\"); throw new RuntimeException(\"" + SECRET + "\");");
        try (var worker = runner().open(EtlScriptRunner.Language.JAVA, source, () -> { }, () -> fail("cleanup"))) {
            var failure = assertThrows(IllegalStateException.class, () -> worker.execute(List.of(Map.of("value", SECRET))));
            assertFalse(failure.getMessage().contains(SECRET)); assertNull(failure.getCause());
        }
    }

    @Test void logByteLimitAppliesAcrossBatches() {
        String code = javaSource("System.out.print(\"x\".repeat(40_000)); return rows;");
        try (var worker = runner().open(EtlScriptRunner.Language.JAVA, code, () -> { }, () -> fail("cleanup"))) {
            assertEquals(1, worker.execute(List.of(Map.of())).size());
            var failure = assertThrows(IllegalStateException.class, () -> worker.execute(List.of(Map.of())));
            assertTrue(failure.getMessage().contains("日志"));
        }
    }

    @Test void oversizedInputAndUnsupportedValueFailWithNoDataInTheirErrors() {
        try (var worker = runner(30_000, 1024, 10_000).open(EtlScriptRunner.Language.JAVA,
                javaSource("return rows;"), () -> { }, () -> fail("cleanup"))) {
            var oversized = assertThrows(IllegalStateException.class,
                    () -> worker.execute(List.of(Map.of("value", SECRET.repeat(200)))));
            assertFalse(oversized.getMessage().contains(SECRET));
        }
        try (var worker = runner().open(EtlScriptRunner.Language.JAVA,
                javaSource("return rows;"), () -> { }, () -> fail("cleanup"))) {
            var unsupported = assertThrows(IllegalStateException.class,
                    () -> worker.execute(List.of(Map.of("value", new Object()))));
            assertFalse(unsupported.getMessage().contains(SECRET));
        }
    }

    @Test void oversizedResultRowsAndNonObjectRowsAreRejectedInTheWorker() {
        try (var worker = runner(30_000, 8 * 1024 * 1024, 2).open(EtlScriptRunner.Language.JAVA,
                javaSource("return List.of(Map.of(),Map.of(),Map.of());"), () -> { }, () -> fail("cleanup"))) {
            assertThrows(IllegalStateException.class, () -> worker.execute(List.of(Map.of())));
        }
        try (var worker = runner().open(EtlScriptRunner.Language.JAVA,
                javaSource("return (List) List.of(\"" + SECRET + "\");"), () -> { }, () -> fail("cleanup"))) {
            var failure = assertThrows(IllegalStateException.class, () -> worker.execute(List.of(Map.of())));
            assertFalse(failure.getMessage().contains(SECRET));
        }
    }

    @Test void malformedWorkerFrameIsRejectedBeforeAllocationAndTerminatesTheSession() {
        String body = "java.io.DataOutputStream raw = new java.io.DataOutputStream(new java.io.FileOutputStream(java.io.FileDescriptor.out)); "
                + "raw.writeInt(Integer.MAX_VALUE); raw.flush(); return rows;";
        var runner = runner();
        try (var worker = runner.open(EtlScriptRunner.Language.JAVA, javaSource(body), () -> { }, () -> fail("cleanup"))) {
            long pid = worker.processId();
            var failure = assertThrows(IllegalStateException.class, () -> worker.execute(List.of(Map.of())));
            assertTrue(failure.getMessage().contains("协议")); assertNull(failure.getCause());
            assertFalse(alive(pid)); assertEquals(0, runner.activeWorkers());
        }
    }

    @Test void compilerChecksStayInWorkerAndReturnOnlyLocationsAndDiagnosticCodes() {
        var runner = runner();
        assertTrue(runner.checkJava(javaSource("return rows;")).isEmpty());
        var errors = runner.checkJava(javaSource("return " + SECRET.replace('-', '_') + ";"));
        assertFalse(errors.isEmpty()); assertFalse(String.join(" ", errors).contains(SECRET.replace('-', '_')));
        assertEquals(0, runner.activeWorkers());
        assertThrows(IllegalStateException.class, () -> EtlJavaCompiler.compileScript(javaSource("return rows;")));
    }

    @Test void workerDoesNotInheritParentEnvironmentOrBusinessClasspath() {
        String body = "boolean absent; try { Class.forName(\"org.springframework.context.ApplicationContext\"); absent=false; }"
                + "catch (ClassNotFoundException expected) { absent=true; }"
                + "return List.of(Map.of(\"noPath\", System.getenv(\"PATH\") == null, \"noSpring\", absent));";
        try (var worker = runner().open(EtlScriptRunner.Language.JAVA, javaSource(body), () -> { }, () -> fail("cleanup"))) {
            var result = worker.execute(List.of(Map.of())).getFirst();
            assertEquals(true, result.get("noPath")); assertEquals(true, result.get("noSpring"));
        }
    }

    @Test void unknownScriptCreatedFilesAreRetainedWithoutPretendingTheProcessStillRuns() throws Exception {
        var runner = runner(); var unconfirmed = new AtomicBoolean();
        String body = javaSource("java.nio.file.Path file = java.nio.file.Path.of(\"script-created.txt\"); "
                + "java.nio.file.Files.writeString(file, \"" + SECRET + "\"); "
                + "return List.of(Map.of(\"directory\", System.getProperty(\"user.dir\"))); ");
        var retained = runner.open(EtlScriptRunner.Language.JAVA, body, () -> { }, () -> unconfirmed.set(true));
        long retainedPid = retained.processId();
        String directory = (String) retained.execute(List.of(Map.of())).getFirst().get("directory");
        var failure = assertThrows(IllegalStateException.class, retained::close);
        assertTrue(failure.getMessage().contains("进程已退出")); assertFalse(unconfirmed.get());
        assertFalse(alive(retainedPid)); assertEquals(0, runner.activeWorkers());
        var target = java.nio.file.Path.of(directory).resolve("script-created.txt");
        assertEquals(SECRET, java.nio.file.Files.readString(target));
        // Fixture removes only its own exact, registered file; runtime never recursively deletes user files.
        java.nio.file.Files.delete(target); java.nio.file.Files.delete(java.nio.file.Path.of(directory));
    }

    @Test void disabledWorkerRejectsStaticPreflightAndCompilerChecksWithoutStartingAnything() {
        var runner = new EtlScriptRunner(EtlScriptWorkerPolicy.disabled());
        assertThrows(IllegalStateException.class, runner::ensureAvailable);
        assertFalse(runner.checkJava(javaSource("return rows;")).isEmpty()); assertEquals(0, runner.activeWorkers());
    }

    private static boolean alive(long pid) { return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); }
}
