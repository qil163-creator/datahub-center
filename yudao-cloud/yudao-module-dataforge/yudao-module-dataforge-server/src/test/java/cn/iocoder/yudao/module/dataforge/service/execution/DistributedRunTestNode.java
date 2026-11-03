package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineExecutor;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static cn.iocoder.yudao.module.dataforge.service.execution.DistributedRunFaultSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent test JVM only. No production profile, HTTP endpoint, service discovery, or live engine. */
public final class DistributedRunTestNode {
    private final DistributedRunFaultSupport.Runtime runtime;
    private final String node;
    private final Map<String, RunLease> leases = new HashMap<>();

    private DistributedRunTestNode(DistributedRunFaultSupport.Runtime runtime, String node) {
        this.runtime = runtime; this.node = node;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[2].matches("[ab]-[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid fault-test node arguments");
        validate(args[0], args[1]);
        try (var runtime = new DistributedRunFaultSupport.Runtime(args[0], args[1])) {
            new DistributedRunTestNode(runtime, args[2]).loop();
        } finally { clearIdentity(); }
    }

    private void loop() throws Exception {
        event(0, "READY", "independent-jvm");
        long last = 0;
        // Bound orphan lifetime even if the parent JVM is unexpectedly terminated.
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4);
        while (System.nanoTime() < deadline) {
            var commands = runtime.db.queryForList("SELECT * FROM fault_command WHERE node=? AND id>? ORDER BY id LIMIT 1", node, last);
            if (commands.isEmpty()) { Thread.sleep(25); continue; }
            var row = commands.getFirst();
            long command = ((Number) row.get("id")).longValue();
            long record = ((Number) row.get("record_id")).longValue();
            long tenant = ((Number) row.get("tenant_id")).longValue();
            Domain domain = Domain.valueOf((String) row.get("domain"));
            identity(tenant);
            try {
                String result = execute(command, (String) row.get("action"), domain, record, (String) row.get("payload"));
                assertEquals(tenant, TenantContextHolder.getRequiredTenantId(), "Production call leaked another tenant");
                assertFalse(TenantContextHolder.isIgnore(), "Production call leaked tenant-ignore scope");
                event(command, "DONE", result);
            } catch (Exception | AssertionError failure) {
                event(command, "ERROR", failure.getClass().getName());
                throw failure;
            } finally {
                clearIdentity();
                assertNull(TenantContextHolder.getTenantId());
                assertFalse(TenantContextHolder.isIgnore());
            }
            last = command;
        }
        throw new IllegalStateException("Fault-test node lifetime exceeded");
    }

    private String execute(long command, String action, Domain domain, long record, String payload) {
        return switch (action) {
            case "CAPACITY" -> {
                runtime.properties.setPlatformActive(Integer.parseInt(payload));
                yield "configured";
            }
            case "SOURCE_CAPACITY" -> {
                runtime.properties.setPerDataSourceActive(Integer.parseInt(payload));
                yield "configured";
            }
            case "SCAN" -> scan(command, domain, payload);
            case "CLAIM_SCAN" -> {
                boolean found = domain == Domain.ETL
                        ? runtime.records.selectDispatchCandidates(100, runtime.properties.getPlatformActive(), runtime.properties.getPerDataSourceActive()).stream().anyMatch(run -> run.getId() == record)
                        : runtime.accessRuns.waitingCandidates(100, runtime.properties.getPlatformActive(), runtime.properties.getPerDataSourceActive()).stream().anyMatch(run -> run.getId() == record);
                assertTrue(found, "Both JVMs must discover the same eligible candidate before claiming");
                event(command, "SCANNED", Long.toString(record));
                gate(payload, null, null);
                yield claim(domain, record);
            }
            case "CLAIM" -> claim(domain, record);
            case "START" -> Boolean.toString(runtime.start(domain, lease(domain, record)));
            case "RUN" -> simulateEngine(command, domain, lease(domain, record), payload);
            case "STOP" -> {
                var receipt = runtime.stop(domain, record, "stop-" + command);
                assertTrue(receipt.stopRequested());
                yield Integer.toString(receipt.runStatus());
            }
            case "RECOVER_SCAN" -> {
                if (domain == Domain.ACCESS) runtime.access.recoverExpired(100);
                else {
                    var executor = new EtlPipelineExecutor();
                    ReflectionTestUtils.setField(executor, "runCoordinator", runtime.etl);
                    ReflectionTestUtils.setField(executor, "recordMapper", runtime.records);
                    ReflectionTestUtils.setField(executor, "executionProperties", runtime.properties);
                    executor.recoverExpired();
                }
                yield "recovery-scanned";
            }
            case "STALE" -> {
                var old = lease(domain, record);
                assertFalse(runtime.renew(domain, old));
                assertFalse(runtime.start(domain, old));
                assertFalse(runtime.write(domain, old, () -> fail("Stale local callback ran")));
                if (domain == Domain.ACCESS) runtime.accessTableResult(old, true);
                assertFalse(runtime.finish(domain, old, 3, RunProtocol.Effect.COMPLETE, true));
                yield "renew/start/write/finish-rejected";
            }
            case "REJECT" -> { runtime.rejectInRealExecutor(domain, record); yield "rejected"; }
            case "DELETE_PROTECTED" -> {
                assertThrows(ServiceException.class, () -> runtime.delete(domain, record));
                yield "protected";
            }
            case "STALE_DENIED" -> {
                var owner = lease(domain, record);
                var old = new RunLease(record, "stale-token", owner.leaseUntil());
                assertTrue(runtime.claim(domain, record, node).isEmpty());
                assertFalse(runtime.renew(domain, old));
                assertFalse(runtime.start(domain, old));
                assertFalse(runtime.write(domain, old, () -> fail("Stale callback ran")));
                assertFalse(runtime.finish(domain, old, 3, RunProtocol.Effect.COMPLETE, true));
                yield "stale-denied";
            }
            case "TRANSACTION" -> {
                var owned = lease(domain, record);
                var failure = assertThrows(IllegalStateException.class, () -> runtime.transactionProbe(domain, owned, true));
                assertEquals("injected local callback rollback", failure.getMessage());
                assertEquals(0L, runtime.db.queryForObject("SELECT count(*) FROM fault_local_write", Long.class));
                runtime.transactionProbe(domain, owned, false);
                assertEquals(1L, runtime.db.queryForObject("SELECT count(*) FROM fault_local_write", Long.class));
                yield "rollback-and-commit-observed";
            }
            default -> throw new IllegalArgumentException("Unknown test command " + action);
        };
    }

    private String scan(long command, Domain domain, String barrier) {
        // Freeze BOTH discovered due occurrences before either JVM is allowed to call the coordinator.
        // Admission identity, row locks, inserts and schedule advancement are exclusively production code.
        if (domain == Domain.ETL) {
            var candidates = runtime.pipelines.selectDueList(100, 100);
            event(command, "SCANNED", Integer.toString(candidates.size()));
            gate(barrier, null, null);
            for (var task : candidates)
                runtime.etl.enqueueScheduled(task, runtime.prepared(task.getId())).orElseThrow();
            return Integer.toString(candidates.size());
        }
        var candidates = runtime.accessTasks.selectList(Wrappers.lambdaQuery(DataAccessDO.class)
                .eq(DataAccessDO::getStatus, 1).eq(DataAccessDO::getTaskType, 3)
                .apply("next_run_time <= clock_timestamp()").orderByAsc(DataAccessDO::getNextRunTime).last("LIMIT 100"));
        event(command, "SCANNED", Integer.toString(candidates.size()));
        gate(barrier, null, null);
        for (var task : candidates)
            runtime.access.enqueueScheduled(task.getId(), task.getScheduleGeneration(), task.getNextRunTime()).orElseThrow();
        return Integer.toString(candidates.size());
    }

    private String claim(Domain domain, long record) {
        var result = runtime.claim(domain, record, node);
        result.ifPresent(owned -> leases.put(key(domain, record), owned));
        return Boolean.toString(result.isPresent());
    }
    private RunLease lease(Domain domain, long record) {
        return java.util.Objects.requireNonNull(leases.get(key(domain, record)), "Node never owned this run");
    }
    private String key(Domain domain, long record) { return domain + ":" + record; }

    /** Controllable external-engine simulator, explicitly not SeaTunnel or the full ETL/DB Switch execution path. */
    private String simulateEngine(long command, Domain domain, RunLease owned, String outcome) {
        assertTrue(runtime.start(domain, owned));
        event(command, "BEFORE_COMMIT", "started-no-external-commit");
        gate("before-" + command, domain, owned);
        if (runtime.stopRequested(domain, owned)) {
            assertTrue(runtime.finish(domain, owned, domain.stopped, RunProtocol.Effect.NONE, true));
            return "stopped-before-commit";
        }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "External submission held a platform transaction");
        // Separate auto-commit connection; a crash can never roll this back together with platform state.
        runtime.db.update("INSERT INTO fault_engine_commit(domain,record_id,batch) VALUES (?,?,1)",
                domain.name(), owned.recordId());
        event(command, "AFTER_COMMIT", "external-commit-durable-response-withheld");
        gate("after-" + command, domain, owned);
        if ("LOST_RESPONSE".equals(outcome)) {
            // Explicit simulated transport ambiguity; no replay or inference from the simulator's private ledger.
            assertTrue(runtime.finish(domain, owned, domain.unknown, RunProtocol.Effect.UNKNOWN, false));
            return "response-lost";
        }
        if ("PARTIAL".equals(outcome)) {
            assertTrue(runtime.stopRequested(domain, owned), "Partial scenario requires an independent stop command");
            if (domain == Domain.ACCESS) runtime.accessTableResult(owned, false);
            assertTrue(runtime.finish(domain, owned, domain.stopped, RunProtocol.Effect.PARTIAL, true));
            return "stopped-one-of-two-batches";
        }
        assertEquals("COMPLETE", outcome);
        event(command, "BEFORE_FINISH", "external-response-known-terminal-not-saved");
        gate("finish-" + command, domain, owned);
        assertTrue(runtime.finish(domain, owned, 3, RunProtocol.Effect.COMPLETE, true));
        return "complete";
    }

    private void gate(String name, Domain domain, RunLease owned) {
        long[] lastRenew = {System.nanoTime()};
        waitFor("fault gate " + name, () -> {
            if (owned != null && System.nanoTime() - lastRenew[0] >= TimeUnit.SECONDS.toNanos(2)) {
                assertTrue(runtime.renew(domain, owned), "Simulator owner lost its lease while paused");
                lastRenew[0] = System.nanoTime();
            }
            return runtime.db.queryForObject("SELECT count(*) FROM fault_gate WHERE name=?", Long.class, name) == 1;
        });
    }
    private void event(long command, String phase, String detail) {
        runtime.db.update("INSERT INTO fault_event(node,command_id,phase,detail,pid) VALUES (?,?,?,?,?)",
                node, command, phase, detail, ProcessHandle.current().pid());
    }
}
