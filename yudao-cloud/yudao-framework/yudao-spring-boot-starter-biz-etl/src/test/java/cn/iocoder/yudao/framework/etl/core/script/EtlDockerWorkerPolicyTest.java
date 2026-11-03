package cn.iocoder.yudao.framework.etl.core.script;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EtlDockerWorkerPolicyTest {
    private static final String IMAGE = "eclipse-temurin@sha256:" + "a".repeat(64);
    private static final String ID = "b".repeat(64);
    @TempDir Path directory;

    private EtlScriptWorkerPolicy policy(boolean strict) {
        var docker = new EtlScriptWorkerPolicy.Docker(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toAbsolutePath(),
                "tcp://127.0.0.1:12345", IMAGE,
                List.of(new EtlScriptWorkerPolicy.HostPathMapping(directory.toAbsolutePath(), "/worker-source")), strict, 5000);
        return new EtlScriptWorkerPolicy(true, List.of(), 1048576, 8388608, 8388608, 10000, 10000,
                65536, 30000, 30000, 256, EtlScriptWorkerPolicy.Mode.DOCKER, docker);
    }
    @Test void defaultsRetainTheOldDisabledChildJvmContract() {
        assertFalse(EtlScriptWorkerPolicy.disabled().enabled());
        assertEquals(EtlScriptWorkerPolicy.Mode.CHILD_JVM, EtlScriptWorkerPolicy.defaults(true).mode());
        assertNull(EtlScriptWorkerPolicy.defaults(true).docker());
        assertThrows(IllegalArgumentException.class, () -> new EtlScriptWorkerPolicy.Docker(
                Path.of(System.getProperty("java.home")).toAbsolutePath(), "tcp://10.0.0.1:2375", IMAGE, List.of(), true, 5000));
        assertThrows(IllegalArgumentException.class, () -> new EtlScriptWorkerPolicy.Docker(
                Path.of(System.getProperty("java.home")).toAbsolutePath(), "tcp://127.0.0.1:2375", "eclipse-temurin:25-jdk", List.of(), true, 5000));
    }
    @Test void missingCapabilitiesFailClosedRatherThanFallingBackToTheHostJvm() {
        var noSwap = JSONUtil.parseObj("{os:'linux',cgroup:'2',memory:true,swap:false,pids:true,security:['name=seccomp,profile=builtin']}");
        assertTrue(assertThrows(IllegalStateException.class, () -> EtlDockerWorker.verifyCapabilities(noSwap, true)).getMessage().contains("swap"));
        assertDoesNotThrow(() -> EtlDockerWorker.verifyCapabilities(noSwap, false));
        noSwap.set("memory", false);
        assertThrows(IllegalStateException.class, () -> EtlDockerWorker.verifyCapabilities(noSwap, false));
    }
    @Test void fixedArgumentsOnlyExposeTheMappedReadOnlyWorkerClasspath() throws Exception {
        Path dependency = Files.createFile(directory.resolve("dependency.jar")).toRealPath();
        var driver = new EtlDockerWorker(policy(true), List.of(dependency));
        var args = driver.createArguments();
        assertTrue(args.contains(IMAGE)); assertTrue(args.contains("65531:65531"));
        assertEquals("none", args.get(args.indexOf("--network") + 1));
        assertTrue(args.contains("no-new-privileges:true")); assertTrue(args.contains("--read-only"));
        assertTrue(args.contains("type=bind,source=/worker-source/dependency.jar,target=/worker/entry-0.jar,readonly,bind-propagation=rprivate,bind-recursive=readonly"));
        assertTrue(args.contains("/usr/bin/timeout")); assertTrue(args.contains("300s")); assertFalse(args.contains("--env"));
        assertThrows(UnsupportedOperationException.class, () -> args.add("--privileged"));
        Path poisoned = Files.createFile(directory.resolve("dependency,poison.jar")).toRealPath();
        assertThrows(IllegalStateException.class, () -> new EtlDockerWorker(policy(true), List.of(poisoned)));
    }
    @Test void ambiguousCreationKeepsResourcesUnconfirmed() throws Exception {
        var driver = new EtlDockerWorker(policy(true), List.of(directory.toRealPath()), (args, timeout) -> {
            throw new IllegalStateException("safe control deadline");
        });
        assertThrows(IllegalStateException.class, driver::start);
        assertFalse(driver.stopAndConfirm()); assertNull(driver.containerId());
    }
    @Test void invalidActualIsolationIsRejectedAndOnlyTheAcknowledgedOwnerMayBeRemoved() throws Exception {
        var owner = new AtomicReference<String>(); var name = new AtomicReference<String>(); var removals = new ArrayList<String>();
        var driver = new EtlDockerWorker(policy(true), List.of(directory.toRealPath()), (args, timeout) -> {
            assertTrue(timeout > 0 && timeout <= 5000);
            if (args.getFirst().equals("create")) {
                owner.set(args.get(args.indexOf("--label") + 1).substring(EtlDockerWorker.OWNER_LABEL.length() + 1));
                name.set(args.get(args.indexOf("--name") + 1)); return new EtlDockerWorker.CommandResult(0, ID);
            }
            if (args.getFirst().equals("inspect")) {
                assertEquals(ID, args.get(1));
                // Missing HostConfig must reject the create before any attach or script execution.
                return new EtlDockerWorker.CommandResult(0, "{id:'" + ID + "',name:'/" + name.get()
                        + "',owner:'" + owner.get() + "',state:{Running:false,Status:'created'}}");
            }
            if (args.getFirst().equals("rm")) { removals.add(args.get(1)); return new EtlDockerWorker.CommandResult(0, ID); }
            if (args.getFirst().equals("ps")) return new EtlDockerWorker.CommandResult(0, "");
            fail("Unexpected control command"); return null;
        });
        assertThrows(IllegalStateException.class, driver::start);
        assertTrue(driver.stopAndConfirm()); assertEquals(List.of(ID), removals);
    }
    @Test void anOwnerMismatchOrDaemonFailureNeverDeletesAnotherContainer() throws Exception {
        var driver = new EtlDockerWorker(policy(true), List.of(directory.toRealPath()), (args, timeout) -> {
            if (args.getFirst().equals("create")) return new EtlDockerWorker.CommandResult(0, ID);
            if (args.getFirst().equals("inspect")) return new EtlDockerWorker.CommandResult(0, "{id:'" + ID
                    + "',name:'/another-container',owner:'other',state:{Running:false}} ");
            fail("No removal is permitted after an owner mismatch"); return null;
        });
        assertThrows(IllegalStateException.class, driver::start);
        assertFalse(driver.stopAndConfirm());
    }
    @Test void aSuccessfulKillClientCannotProveTheContainerHasStopped() throws Exception {
        var owner = new AtomicReference<String>(); var name = new AtomicReference<String>();
        var driver = new EtlDockerWorker(policy(true), List.of(directory.toRealPath()), (args, timeout) -> {
            if (args.getFirst().equals("create")) {
                owner.set(args.get(args.indexOf("--label") + 1).substring(EtlDockerWorker.OWNER_LABEL.length() + 1));
                name.set(args.get(args.indexOf("--name") + 1)); return new EtlDockerWorker.CommandResult(0, ID);
            }
            if (args.getFirst().equals("inspect")) return new EtlDockerWorker.CommandResult(0, "{id:'" + ID
                    + "',name:'/" + name.get() + "',owner:'" + owner.get() + "',state:{Running:true,Status:'running'}}");
            if (args.getFirst().equals("kill")) return new EtlDockerWorker.CommandResult(0, ID);
            fail("No removal is permitted while the actual container remains running"); return null;
        });
        assertThrows(IllegalStateException.class, driver::start);
        assertFalse(driver.stopAndConfirm());
    }
}
