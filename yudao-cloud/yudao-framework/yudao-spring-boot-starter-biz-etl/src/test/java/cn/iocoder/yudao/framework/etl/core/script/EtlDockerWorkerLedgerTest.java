package cn.iocoder.yudao.framework.etl.core.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EtlDockerWorkerLedgerTest {
    private static final String ID = "b".repeat(64);
    private static final String IMAGE_ID = "sha256:" + "d".repeat(64);
    private static final EtlDockerWorker.Mount MOUNT = new EtlDockerWorker.Mount("/trusted/dependency.jar", "/worker/entry-0.jar");
    private static final String MOUNT_PROOF = EtlDockerWorker.mountProof(List.of(MOUNT));
    @TempDir Path directory;
    @org.junit.jupiter.api.BeforeEach void canonicalLocalFixture() throws java.io.IOException {
        // macOS 默认临时目录含 /var 符号父路径；合法账本夹具须传真实路径，拒绝符号链接的负例另行构造。
        directory = directory.toRealPath();
    }
    private EtlScriptWorkerPolicy policy(String endpoint, String image) {
        var recovery = new EtlScriptWorkerPolicy.Recovery(directory, "ledger-test", 4, false);
        var docker = new EtlScriptWorkerPolicy.Docker(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toAbsolutePath(),
                endpoint, "eclipse-temurin@sha256:" + image.repeat(64), List.of(), false, 5000, 300000, recovery);
        return new EtlScriptWorkerPolicy(true, List.of(), 1048576, 8388608, 8388608, 10000, 10000,
                65536, 30000, 30000, 256, EtlScriptWorkerPolicy.Mode.DOCKER, docker);
    }
    private EtlScriptWorkerPolicy policy() { return policy("tcp://127.0.0.1:12345", "a"); }
    private Path slot() { return directory.resolve("ledger-test/slot-0000.log"); }

    @Test void disabledRecoveryPreservesExistingConfigurationAndMakesNoLocalArtifacts() {
        assertNull(EtlScriptWorkerPolicy.defaults(true).docker());
        assertEquals(new EtlScriptRunner.RecoveryReport(0, 0, 0, 0, 0), new EtlScriptRunner(EtlScriptWorkerPolicy.disabled()).recoverOrphanedWorkers());
        assertEquals(0, directory.toFile().list().length);
    }
    @Test void symbolicSlotCannotBorrowTheIdentityOfItsRealHardLinkAnchor() throws Exception {
        var ledger = new EtlDockerWorkerLedger(policy());
        try (var registration = ledger.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF)) { registration.acknowledge(ID); }
        Path anchor = directory.resolve("ledger-test/slot-0000.anchor");
        assertTrue(Files.isSameFile(slot(), anchor));
        Files.delete(slot()); Files.createSymbolicLink(slot(), anchor);
        assertTrue(Files.isSameFile(slot(), anchor));
        assertThrows(IllegalStateException.class, ledger::checkAvailable);
        assertThrows(IllegalStateException.class, () -> ledger.scan(ignored -> { fail("符号链接不能触发恢复守护进程操作"); return true; }));
    }
    @Test void activeLeaseIsProtectedAcrossMultipleLedgerAndRunnerInstancesWithoutPidHeuristics() {
        var first = new EtlDockerWorkerLedger(policy()); var second = new EtlDockerWorkerLedger(policy());
        try (var registration = first.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF)) {
            var report = second.scan(ignored -> { fail("An active lease must never be reconciled"); return false; });
            assertEquals(1, report.active()); assertEquals(0, report.scanned());
            assertEquals(1, new EtlScriptRunner(policy()).recoverOrphanedWorkers().active());
        }
        assertEquals(1, second.scan(ignored -> false).pending());
    }
    @Test void unknownCreateIsRetainedAndCapacityRemainsBoundedAcrossRestart() {
        var ledger = new EtlDockerWorkerLedger(policy());
        for (int i = 0; i < 4; i++) try (var registration = ledger.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF)) {
            assertEquals("INTENT", registration.entry().state());
        }
        var restarted = new EtlDockerWorkerLedger(policy());
        for (int i = 0; i < 2; i++) {
            var report = restarted.scan(registration -> new EtlDockerWorker(policy(), List.of(), (args, timeout) -> {
                assertEquals("ps", args.getFirst()); return new EtlDockerWorker.CommandResult(0, "");
            }).reconcile(registration));
            assertEquals(4, report.pending()); assertEquals(0, report.cleaned());
        }
        assertThrows(IllegalStateException.class, () -> restarted.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF));
        assertEquals(11, Objects.requireNonNull(directory.resolve("ledger-test").toFile().list()).length);
    }
    @Test void acknowledgedAbsentContainerIsReconciledThenItsStableSlotMayBeReused() {
        var ledger = new EtlDockerWorkerLedger(policy());
        try (var registration = ledger.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF)) { registration.acknowledge(ID); }
        Path anchor = directory.resolve("ledger-test/slot-0000.anchor");
        assertTrue(assertDoesNotThrow(() -> Files.isSameFile(slot(), anchor)));
        var calls = new AtomicInteger();
        var report = new EtlDockerWorkerLedger(policy()).scan(registration -> new EtlDockerWorker(policy(), List.of(), (args, timeout) -> {
            calls.incrementAndGet(); assertEquals("ps", args.getFirst()); return new EtlDockerWorker.CommandResult(0, "");
        }).reconcile(registration));
        assertEquals(1, report.cleaned()); assertEquals(2, calls.get());
        try (var registration = new EtlDockerWorkerLedger(policy("tcp://127.0.0.1:12345", "c")).reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF)) {
            assertEquals("INTENT", registration.entry().state()); // A proven-clean slot can be reused after an image upgrade.
        }
        assertTrue(assertDoesNotThrow(() -> Files.isSameFile(slot(), anchor)));
    }
    @Test void endpointAndImageChangesRetainPendingRecordsWithoutCallingTheDaemon() {
        var ledger = new EtlDockerWorkerLedger(policy());
        try (var registration = ledger.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF)) { registration.acknowledge(ID); }
        for (var changed : List.of(policy("tcp://127.0.0.1:12346", "a"), policy("tcp://127.0.0.1:12345", "c"))) {
            var report = new EtlDockerWorkerLedger(changed).scan(ignored -> { fail("Incompatible ownership must never reach Docker"); return false; });
            assertEquals(1, report.incompatible()); assertEquals(1, report.pending()); assertEquals(0, report.cleaned());
        }
        assertThrows(IllegalStateException.class, () -> new EtlDockerWorkerLedger(policy("tcp://127.0.0.1:12346", "a")).checkAvailable());
    }
    @Test void tornAcknowledgementRetainsTheDurableIntentAndCanBeCompletedAfterProof() throws Exception {
        var ledger = new EtlDockerWorkerLedger(policy());
        try (var ignored = ledger.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF)) { }
        Files.write(slot(), ByteBuffer.allocate(18).putInt(0x45544c44).putInt(100).putLong(0).putShort((short) 1).array(), StandardOpenOption.APPEND);
        var report = new EtlDockerWorkerLedger(policy()).scan(registration -> {
            assertEquals("INTENT", registration.entry().state()); registration.acknowledge(ID); registration.cleaned(); return true;
        });
        assertEquals(1, report.cleaned());
        assertEquals(0, new EtlDockerWorkerLedger(policy()).scan(ignored -> { fail("Cleaned slot must not reach recovery"); return false; }).scanned());
    }
    @Test void corruptedOrReplacedObjectsRemainUnprovenAndAreNeverReconciled() throws Exception {
        var ledger = new EtlDockerWorkerLedger(policy());
        try (var ignored = ledger.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF)) { }
        byte[] data = Files.readAllBytes(slot()); data[20] ^= 1; Files.write(slot(), data);
        var report = ledger.scan(ignored -> { fail("Corrupt records must not reach Docker"); return false; });
        assertEquals(1, report.pending()); assertArrayEquals(data, Files.readAllBytes(slot()));
        Path anchor = directory.resolve("ledger-test/slot-0000.anchor"); Files.delete(anchor); Files.createFile(anchor);
        assertThrows(IllegalStateException.class, ledger::checkAvailable); assertTrue(Files.exists(slot())); assertTrue(Files.exists(anchor));
    }
    @Test void daemonFailureDoesNotTurnAnAcknowledgedRecordIntoAnAbsentContainer() {
        var ledger = new EtlDockerWorkerLedger(policy());
        try (var registration = ledger.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF)) { registration.acknowledge(ID); }
        var report = ledger.scan(registration -> new EtlDockerWorker(policy(), List.of(), (args, timeout) -> {
            assertEquals("ps", args.getFirst()); return new EtlDockerWorker.CommandResult(1, "");
        }).reconcile(registration));
        assertEquals(1, report.pending()); assertEquals(0, report.cleaned());
    }
    @Test void expiredRecoveryDeadlineNeverCallsTheDaemonOrClearsTheRecord() {
        var ledger = new EtlDockerWorkerLedger(policy());
        try (var registration = ledger.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF)) { registration.acknowledge(ID); }
        var report = ledger.scan(registration -> new EtlDockerWorker(policy(), List.of(), (args, timeout) -> {
            fail("An expired recovery budget must prevent all Docker commands"); return null;
        }).reconcile(registration, System.nanoTime() - 1));
        assertEquals(1, report.pending()); assertEquals(0, report.cleaned());
    }
    @Test void matchingOwnerAndNameCannotOverrideMismatchedServiceOrStorageLabels() {
        var ledger = new EtlDockerWorkerLedger(policy());
        try (var registration = ledger.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF)) { registration.acknowledge(ID); }
        for (String mismatch : List.of(EtlDockerWorker.SERVICE_LABEL, EtlDockerWorker.STORAGE_LABEL)) {
            var report = ledger.scan(registration -> new EtlDockerWorker(policy(), List.of(), (args, timeout) -> {
                if (args.getFirst().equals("ps")) return new EtlDockerWorker.CommandResult(0, ID);
                assertEquals("inspect", args.getFirst(), "Unproven containers must never be killed or removed");
                var entry = registration.entry();
                var labels = new HashMap<>(Map.of(EtlDockerWorker.SERVICE_LABEL, entry.service(), EtlDockerWorker.STORAGE_LABEL, entry.storage()));
                labels.put(mismatch, "unproven");
                return new EtlDockerWorker.CommandResult(0, cn.hutool.json.JSONUtil.toJsonStr(Map.of("id", ID,
                        "name", "/etl-script-" + entry.owner(), "owner", entry.owner(), "config", Map.of("Labels", labels))));
            }).reconcile(registration));
            assertEquals(1, report.pending()); assertEquals(0, report.cleaned());
        }
    }
    @Test void aMissingRegisteredSlotPairCannotBeRecreatedAsAnEmptyFreeSlot() throws Exception {
        var ledger = new EtlDockerWorkerLedger(policy());
        try (var registration = ledger.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF)) { registration.acknowledge(ID); }
        Path anchor = directory.resolve("ledger-test/slot-0000.anchor"); Files.delete(slot()); Files.delete(anchor);
        var restarted = new EtlDockerWorkerLedger(policy());
        assertThrows(IllegalStateException.class, restarted::checkAvailable);
        assertThrows(IllegalStateException.class, () -> restarted.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF));
        assertThrows(IllegalStateException.class, () -> restarted.scan(ignored -> { fail("Missing registered history must prevent all daemon actions"); return false; }));
        assertFalse(Files.exists(slot())); assertFalse(Files.exists(anchor));
    }
    @Test void aRecoveredIntentRequiresRecordedImageAndExactOriginalReadOnlyMountsBeforeAcknowledgement() {
        var ledger = new EtlDockerWorkerLedger(policy());
        try (var ignored = ledger.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF)) { }
        for (String invalid : List.of("image", "path", "extra", "writable", "configured")) {
            var report = ledger.scan(registration -> new EtlDockerWorker(policy(), List.of(), (args, timeout) -> {
                if (args.getFirst().equals("ps")) return new EtlDockerWorker.CommandResult(0, ID);
                assertEquals("inspect", args.getFirst(), "Image or mount proof failures cannot acknowledge, kill or remove");
                var value = inspection(registration.entry());
                switch (invalid) {
                    case "image" -> value.set("image", "sha256:" + "e".repeat(64));
                    case "path" -> {
                        value.getJSONArray("mounts").getJSONObject(0).set("Source", "/other/dependency.jar");
                        value.getJSONObject("host").getJSONArray("Mounts").getJSONObject(0).set("Source", "/other/dependency.jar");
                    }
                    case "extra" -> {
                        value.getJSONArray("mounts").add(Map.of("Type", "bind", "Source", "/unexpected", "Destination", "/extra", "RW", false, "Propagation", "rprivate"));
                        value.getJSONObject("host").getJSONArray("Mounts").add(Map.of("Type", "bind", "Source", "/unexpected", "Target", "/extra", "ReadOnly", true,
                                "BindOptions", Map.of("ReadOnlyForceRecursive", true, "Propagation", "rprivate")));
                    }
                    case "writable" -> value.getJSONArray("mounts").getJSONObject(0).set("RW", true);
                    case "configured" -> value.getJSONObject("host").getJSONArray("Mounts").getJSONObject(0).set("Source", "/other/dependency.jar");
                    default -> fail("Unexpected fixture");
                }
                return new EtlDockerWorker.CommandResult(0, cn.hutool.json.JSONUtil.toJsonStr(value));
            }).reconcile(registration));
            assertEquals(1, report.pending()); assertEquals(0, report.cleaned());
            ledger.scan(registration -> { assertEquals("INTENT", registration.entry().state()); return false; });
        }
    }
    @Test void onlyAnOrphanWithProvenContentImageAndOriginalBindsCanBeAcknowledgedAndRemoved() {
        var ledger = new EtlDockerWorkerLedger(policy());
        try (var ignored = ledger.reserve(UUID.randomUUID().toString(), IMAGE_ID, MOUNT_PROOF)) { }
        var removed = new AtomicInteger();
        var report = ledger.scan(registration -> new EtlDockerWorker(policy(), List.of(), (args, timeout) -> {
            if (args.getFirst().equals("ps")) return new EtlDockerWorker.CommandResult(0, removed.get() == 0 ? ID : "");
            if (args.getFirst().equals("inspect")) return new EtlDockerWorker.CommandResult(0, cn.hutool.json.JSONUtil.toJsonStr(inspection(registration.entry())));
            assertEquals("rm", args.getFirst()); assertEquals("ACK", registration.entry().state());
            assertEquals(ID, args.get(1)); removed.incrementAndGet(); return new EtlDockerWorker.CommandResult(0, ID);
        }).reconcile(registration));
        assertEquals(1, report.cleaned()); assertEquals(0, report.pending()); assertEquals(1, removed.get());
    }
    private cn.hutool.json.JSONObject inspection(EtlDockerWorkerLedger.Entry entry) {
        var value = new cn.hutool.json.JSONObject();
        value.set("id", ID).set("image", IMAGE_ID).set("name", "/etl-script-" + entry.owner()).set("owner", entry.owner());
        value.set("state", Map.of("Running", false, "Status", "created"));
        var config = new cn.hutool.json.JSONObject();
        config.set("Labels", Map.of(EtlDockerWorker.SERVICE_LABEL, entry.service(), EtlDockerWorker.STORAGE_LABEL, entry.storage()));
        config.set("User", "65531:65531").set("WorkingDir", "/").set("Image", policy().docker().image());
        config.set("Entrypoint", List.of("/usr/bin/timeout")).set("Cmd", List.of("--signal=KILL", "300s", "/bin/sleep", "infinity"));
        value.set("config", config);
        var host = new cn.hutool.json.JSONObject();
        host.set("NetworkMode", "none").set("ReadonlyRootfs", true).set("Privileged", false).set("Init", true).set("PidMode", "");
        host.set("IpcMode", "private").set("CgroupnsMode", "private").set("NanoCpus", 1_000_000_000L).set("Memory", EtlDockerWorker.MEMORY).set("PidsLimit", 64L);
        host.set("CapDrop", List.of("ALL")).set("SecurityOpt", List.of("no-new-privileges:true")).set("LogConfig", Map.of("Type", "none"));
        host.set("Tmpfs", Map.of("/tmp", "rw,noexec,nosuid,nodev,size=64m,uid=65532,gid=65532,mode=0700"));
        host.set("Mounts", List.of(Map.of("Type", "bind", "Source", MOUNT.source(), "Target", MOUNT.destination(), "ReadOnly", true,
                "BindOptions", Map.of("ReadOnlyForceRecursive", true, "Propagation", "rprivate"))));
        value.set("host", host).set("mounts", List.of(Map.of("Type", "bind", "Source", MOUNT.source(), "Destination", MOUNT.destination(), "RW", false, "Propagation", "rprivate")));
        return value;
    }
}
