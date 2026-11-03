package cn.iocoder.yudao.module.dataforge.service.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static cn.iocoder.yudao.module.dataforge.service.execution.DistributedRunFaultSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real PostgreSQL + two independent JVMs + real transactional coordinators.
 * External submission is a deliberately controllable simulator, NOT real SeaTunnel acceptance.
 * Run with -Ddatahub.test.postgres.url=jdbc:postgresql://127.0.0.1:<owned-test-port>/postgres.
 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = ".+")
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(120)
class DistributedRunFaultPostgreSqlTest {
    private DistributedRunFaultSupport fixture;

    @BeforeEach void setup() throws Exception {
        fixture = new DistributedRunFaultSupport(System.getProperty("datahub.test.postgres.url"));
    }

    @AfterEach void cleanup() throws Exception {
        if (fixture != null) fixture.close();
    }

    @Test void fixtureRejectsNonLoopbackUrlsJdbcOptionsAndForeignSchemasBeforeConnecting() {
        assertThrows(IllegalArgumentException.class, () -> validate("jdbc:postgresql://192.0.2.1:5432/postgres", fixture.schema));
        assertThrows(IllegalArgumentException.class, () -> validate(fixture.url + "?currentSchema=public", fixture.schema));
        assertThrows(IllegalArgumentException.class, () -> validate(fixture.url, "public"));
        assertThrows(IllegalArgumentException.class, () -> validate(fixture.url, "datahub-center"));
        assertThrows(IllegalArgumentException.class, () -> validate("jdbc:postgresql://127.0.0.1:65536/postgres", fixture.schema));
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void simultaneousJvmScansAndClaimsAcceptOneOccurrenceAndOneExternalCommit(Domain domain) throws Exception {
        fixture.seed(domain, 10, 1);
        var pair = fixture.startPair();
        long scanA = pair.get(0).send("SCAN", domain, 0, 77, "schedule");
        long scanB = pair.get(1).send("SCAN", domain, 0, 88, "schedule");
        assertEquals("1", pair.get(0).await(scanA, "SCANNED"));
        assertEquals("1", pair.get(1).await(scanB, "SCANNED"));
        fixture.open("schedule");
        assertEquals("1", pair.get(0).await(scanA, "DONE"));
        assertEquals("1", pair.get(1).await(scanB, "DONE"));
        assertEquals(1, fixture.count("SELECT count(*) FROM " + domain.runs));
        long record = fixture.count("SELECT id FROM " + domain.runs);
        assertSchedule(domain, 10, SCHEDULED.plusMinutes(1));

        long claimA = pair.get(0).send("CLAIM_SCAN", domain, record, 1, "claim");
        long claimB = pair.get(1).send("CLAIM_SCAN", domain, record, 1, "claim");
        pair.get(0).await(claimA, "SCANNED"); pair.get(1).await(claimB, "SCANNED");
        fixture.open("claim");
        boolean aWon = Boolean.parseBoolean(pair.get(0).await(claimA, "DONE"));
        boolean bWon = Boolean.parseBoolean(pair.get(1).await(claimB, "DONE"));
        assertTrue(aWon ^ bWon, "Exactly one independent JVM must own the database slot");
        var owner = pair.get(aWon ? 0 : 1);
        var run = fixture.run(domain, record);
        assertEquals(owner.name, run.get("lease_owner"));
        assertEquals(1, number(run, "attempt_count"));
        assertEquals(1, slots(domain, 10));
        complete(owner, domain, record, 1);
        assertState(domain, record, 3, "COMPLETE", false, false);
        assertEquals(1, fixture.commits(domain, record));
        assertEquals(1, fixture.count("SELECT count(*) FROM " + domain.runs));
        assertSchedule(domain, 10, SCHEDULED.plusMinutes(1));
        assertSummary(domain, 10, record, 3);
    }

    @Test void independentJvmsSharePlatformCapacityAcrossEtlAndAccessAndReleaseAfterCompletion() throws Exception {
        fixture.seed(Domain.ETL, 10, 1);
        fixture.seed(Domain.ACCESS, 20, 1);
        fixture.seed(Domain.ACCESS, 30, 2);
        long etl = fixture.enqueue(Domain.ETL, 10, 1, "quota-etl");
        long access = fixture.enqueue(Domain.ACCESS, 20, 1, "quota-access");
        long otherTenant = fixture.enqueue(Domain.ACCESS, 30, 2, "quota-other-tenant");
        var pair = fixture.startPair();
        for (var node : pair) assertEquals("configured", node.call("CAPACITY", Domain.ETL, 0, 1, "1"));
        long a = pair.get(0).send("CLAIM_SCAN", Domain.ETL, etl, 1, "capacity");
        long b = pair.get(1).send("CLAIM_SCAN", Domain.ACCESS, access, 1, "capacity");
        pair.get(0).await(a, "SCANNED"); pair.get(1).await(b, "SCANNED");
        fixture.open("capacity");
        boolean etlWon = Boolean.parseBoolean(pair.get(0).await(a, "DONE"));
        boolean accessWon = Boolean.parseBoolean(pair.get(1).await(b, "DONE"));
        assertTrue(etlWon ^ accessWon, "ETL and ACCESS on different JVMs share one platform slot");
        var owner = pair.get(etlWon ? 0 : 1);
        var waiter = pair.get(etlWon ? 1 : 0);
        Domain winningDomain = etlWon ? Domain.ETL : Domain.ACCESS;
        Domain waitingDomain = etlWon ? Domain.ACCESS : Domain.ETL;
        long winningRun = etlWon ? etl : access;
        long waitingRun = etlWon ? access : etl;
        assertEquals("false", waiter.call("CLAIM", waitingDomain, waitingRun, 1, ""));
        assertEquals("false", waiter.call("CLAIM", Domain.ACCESS, otherTenant, 2, ""));
        complete(owner, winningDomain, winningRun, 1);
        assertEquals("true", waiter.call("CLAIM", waitingDomain, waitingRun, 1, ""));
        complete(waiter, waitingDomain, waitingRun, 1);
        assertEquals("true", waiter.call("CLAIM", Domain.ACCESS, otherTenant, 2, ""));
        complete(waiter, Domain.ACCESS, otherTenant, 2);
    }

    @Test void independentJvmsShareDataSourceBudgetButOtherSourceMayProceed() throws Exception {
        fixture.seed(Domain.ETL, 10, 1);
        fixture.seed(Domain.ACCESS, 20, 1);
        fixture.seed(Domain.ACCESS, 30, 1);
        long etl = fixture.enqueue(Domain.ETL, 10, 1, "source-etl");
        long access = fixture.enqueue(Domain.ACCESS, 20, 1, "source-access");
        long unrelated = fixture.enqueue(Domain.ACCESS, 30, 1, "source-other");
        fixture.db.update("UPDATE dh_dataforge_etl_record SET resource_ids='[42]'::jsonb WHERE id=?", etl);
        fixture.db.update("UPDATE dh_dataforge_access_task SET resource_ids='[42]'::jsonb WHERE id=?", access);
        fixture.db.update("UPDATE dh_dataforge_access_task SET resource_ids='[77]'::jsonb WHERE id=?", unrelated);
        var pair = fixture.startPair();
        for (var node : pair) {
            assertEquals("configured", node.call("CAPACITY", Domain.ETL, 0, 1, "3"));
            assertEquals("configured", node.call("SOURCE_CAPACITY", Domain.ETL, 0, 1, "1"));
        }
        long a = pair.get(0).send("CLAIM_SCAN", Domain.ETL, etl, 1, "source-budget");
        long b = pair.get(1).send("CLAIM_SCAN", Domain.ACCESS, access, 1, "source-budget");
        pair.get(0).await(a, "SCANNED"); pair.get(1).await(b, "SCANNED");
        fixture.open("source-budget");
        boolean etlWon = Boolean.parseBoolean(pair.get(0).await(a, "DONE"));
        boolean accessWon = Boolean.parseBoolean(pair.get(1).await(b, "DONE"));
        assertTrue(etlWon ^ accessWon, "不同节点、不同任务的同一数据源只能占一个位置");
        var winner = pair.get(etlWon ? 0 : 1);
        var waiter = pair.get(etlWon ? 1 : 0);
        assertEquals("true", waiter.call("CLAIM", Domain.ACCESS, unrelated, 1, ""));
        complete(winner, etlWon ? Domain.ETL : Domain.ACCESS, etlWon ? etl : access, 1);
        assertEquals("true", waiter.call("CLAIM", etlWon ? Domain.ACCESS : Domain.ETL,
                etlWon ? access : etl, 1, ""));
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void unknownExecutionRetainsPlatformCapacityAfterOwnerDies(Domain domain) throws Exception {
        fixture.seed(domain, 10, 1);
        Domain otherDomain = domain == Domain.ETL ? Domain.ACCESS : Domain.ETL;
        fixture.seed(otherDomain, 20, 1);
        long first = fixture.enqueue(domain, 10, 1, "quota-crash");
        long waiting = fixture.enqueue(otherDomain, 20, 1, "quota-waiting");
        var pair = fixture.startPair();
        for (var node : pair) node.call("CAPACITY", domain, 0, 1, "1");
        assertEquals("true", pair.get(0).call("CLAIM", domain, first, 1, ""));
        assertEquals("true", pair.get(0).call("START", domain, first, 1, ""));
        pair.get(0).kill();
        fixture.awaitExpired(domain, first);
        pair.get(1).call("RECOVER_SCAN", domain, first, 1, "");
        assertEquals(true, fixture.run(domain, first).get("occupies_slot"));
        assertEquals("UNKNOWN", fixture.run(domain, first).get("effect_state"));
        assertEquals("false", pair.get(1).call("CLAIM", otherDomain, waiting, 1, ""));
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void nonExecutingJvmPersistsStopAndOwnerConfirmsExitBeforeReleasingSlot(Domain domain) throws Exception {
        var pair = prepare(domain);
        long record = fixture.enqueue(domain, 10, 1, "stop-run");
        var owner = pair.get(0); var other = pair.get(1);
        assertEquals("true", owner.call("CLAIM", domain, record, 1, ""));
        long work = owner.send("RUN", domain, record, 1, "COMPLETE");
        owner.await(work, "BEFORE_COMMIT");
        assertEquals("2", other.call("STOP", domain, record, 1, ""));
        assertState(domain, record, 2, "UNKNOWN", true, false);
        assertEquals(true, fixture.run(domain, record).get("stop_requested"));
        assertEquals(1, fixture.count("SELECT count(*) FROM dh_dataforge_run_command WHERE domain=? AND record_id=? AND actor_id=99", domain.name(), record));
        assertEquals("protected", other.call("DELETE_PROTECTED", domain, record, 1, ""));
        assertEquals(1, slots(domain, 10));
        fixture.open("before-" + work);
        assertEquals("stopped-before-commit", owner.await(work, "DONE"));
        assertState(domain, record, domain.stopped, "NONE", false, false);
        assertEquals(0, fixture.commits(domain, record));
        assertSummary(domain, 10, record, domain.stopped);
        assertSchedule(domain, 10, SCHEDULED);
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void killedPreparationRemainsDurableUntilRecoveryAndReclaimsWithNewToken(Domain domain) throws Exception {
        var pair = prepare(domain);
        long record = fixture.enqueue(domain, 10, 1, "prepare-crash");
        assertEquals("true", pair.get(0).call("CLAIM", domain, record, 1, ""));
        Object previousToken = fixture.run(domain, record).get("execution_token");
        assertNotNull(previousToken);
        pair.get(0).kill();
        fixture.awaitExpired(domain, record);
        assertState(domain, record, 1, "NONE", true, false);
        assertEquals("PREPARE", fixture.run(domain, record).get("execution_stage"));
        assertEquals(0, fixture.commits(domain, record));
        pair.get(1).call("RECOVER_SCAN", domain, record, 77, "");
        assertState(domain, record, 1, "NONE", false, false);
        assertNull(fixture.run(domain, record).get("execution_token"));
        assertNotNull(fixture.run(domain, record).get("next_attempt_at"));
        assertEquals("false", pair.get(1).call("CLAIM", domain, record, 1, ""));
        fixture.awaitRetry(domain, record);
        assertEquals("true", pair.get(1).call("CLAIM", domain, record, 1, ""));
        assertTrue(!Objects.equals(previousToken, fixture.run(domain, record).get("execution_token")), "Reclaim must issue a new token");
        assertEquals(2, number(fixture.run(domain, record), "attempt_count"));
        complete(pair.get(1), domain, record, 1);
        assertState(domain, record, 3, "COMPLETE", false, false);
        assertEquals(1, fixture.commits(domain, record));
        assertSchedule(domain, 10, SCHEDULED);
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void killedStartedOwnerBecomesUnknownWithoutExternalCommitOrAutomaticReplay(Domain domain) throws Exception {
        var pair = prepare(domain);
        long record = fixture.enqueue(domain, 10, 1, "started-crash");
        assertEquals("true", pair.get(0).call("CLAIM", domain, record, 1, ""));
        long work = pair.get(0).send("RUN", domain, record, 1, "COMPLETE");
        pair.get(0).await(work, "BEFORE_COMMIT");
        pair.get(0).kill();
        recoverUnknown(pair.get(1), domain, record, 0);
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void committedExternalSubmissionWithLostResponseRetainsUnknownAndCannotResubmit(Domain domain) throws Exception {
        var pair = prepare(domain);
        long record = fixture.enqueue(domain, 10, 1, "response-lost");
        assertEquals("true", pair.get(0).call("CLAIM", domain, record, 1, ""));
        long work = pair.get(0).send("RUN", domain, record, 1, "LOST_RESPONSE");
        pair.get(0).await(work, "BEFORE_COMMIT"); fixture.open("before-" + work);
        pair.get(0).await(work, "AFTER_COMMIT");
        assertEquals(1, fixture.commits(domain, record), "Parent connection observes durable external submission before response");
        assertState(domain, record, 2, "UNKNOWN", true, false);
        fixture.open("after-" + work);
        assertEquals("response-lost", pair.get(0).await(work, "DONE"));
        assertUnknownCannotReplay(pair.get(1), domain, record, 1);
    }

    static Stream<Arguments> committedCrashWindows() {
        return Stream.of(Domain.values()).flatMap(domain -> Stream.of("AFTER_COMMIT", "BEFORE_FINISH")
                .map(window -> Arguments.of(domain, window)));
    }

    @ParameterizedTest @MethodSource("committedCrashWindows")
    void killedAfterExternalCommitOrBeforeTerminalSaveNeverSubmitsTwice(Domain domain, String window) throws Exception {
        var pair = prepare(domain);
        long record = fixture.enqueue(domain, 10, 1, "committed-crash");
        assertEquals("true", pair.get(0).call("CLAIM", domain, record, 1, ""));
        long work = pair.get(0).send("RUN", domain, record, 1, "COMPLETE");
        pair.get(0).await(work, "BEFORE_COMMIT"); fixture.open("before-" + work);
        pair.get(0).await(work, "AFTER_COMMIT");
        if ("BEFORE_FINISH".equals(window)) {
            fixture.open("after-" + work);
            pair.get(0).await(work, "BEFORE_FINISH");
        }
        assertEquals(1, fixture.commits(domain, record));
        pair.get(0).kill();
        recoverUnknown(pair.get(1), domain, record, 1);
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void staleOwnerCannotRenewStartWriteChildrenWatermarksOrFinishAfterAnotherJvmReclaims(Domain domain) throws Exception {
        var pair = prepare(domain);
        long record = fixture.enqueue(domain, 10, 1, "stale-callback");
        assertEquals("true", pair.get(0).call("CLAIM", domain, record, 1, ""));
        Object oldToken = fixture.run(domain, record).get("execution_token");
        // The original process stays alive with its old token, but deliberately emits no heartbeats.
        fixture.awaitExpired(domain, record);
        pair.get(1).call("RECOVER_SCAN", domain, record, 77, "");
        fixture.awaitRetry(domain, record);
        assertEquals("true", pair.get(1).call("CLAIM", domain, record, 1, ""));
        var reclaimed = fixture.run(domain, record);
        assertTrue(!Objects.equals(oldToken, reclaimed.get("execution_token")), "Tokens must differ without exposing them in logs");
        assertEquals("renew/start/write/finish-rejected", pair.get(0).call("STALE", domain, record, 1, ""));
        assertEquals(number(reclaimed, "row_version"), number(fixture.run(domain, record), "row_version"));
        assertEquals(pair.get(1).name, fixture.run(domain, record).get("lease_owner"));
        assertEquals(0, fixture.count("SELECT count(*) FROM dh_dataforge_access_task_detail"));
        assertEquals(0, fixture.count("SELECT count(*) FROM dh_dataforge_access_increase_log"));
        assertEquals(0, fixture.commits(domain, record));
        // Positive control proves real Spring/MyBatis rollback AND commit inside the owning callback.
        assertEquals("rollback-and-commit-observed", pair.get(1).call("TRANSACTION", domain, record, 1, ""));
        assertEquals(1, fixture.count("SELECT count(*) FROM fault_local_write WHERE record_id=?", record));
        complete(pair.get(1), domain, record, 1);
        assertEquals(1, fixture.commits(domain, record));
        assertSummary(domain, 10, record, 3);
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void realExecutorRejectionKeepsScheduledIntentAndBackoffWithoutConsumingAttempt(Domain domain) throws Exception {
        var pair = prepare(domain);
        fixture.open("schedule");
        assertEquals("1", pair.get(0).call("SCAN", domain, 0, 77, "schedule"));
        long record = fixture.count("SELECT id FROM " + domain.runs);
        assertEquals("rejected", pair.get(0).call("REJECT", domain, record, 1, ""));
        assertState(domain, record, 1, "NONE", false, false);
        assertEquals(0, number(fixture.run(domain, record), "attempt_count"));
        assertNotNull(fixture.run(domain, record).get("next_attempt_at"));
        assertEquals(0, fixture.commits(domain, record));
        assertSchedule(domain, 10, SCHEDULED.plusMinutes(1));
        assertEquals("false", pair.get(1).call("CLAIM", domain, record, 1, ""));
        fixture.awaitRetry(domain, record);
        assertEquals("true", pair.get(1).call("CLAIM", domain, record, 1, ""));
        complete(pair.get(1), domain, record, 1);
        assertEquals(1, number(fixture.run(domain, record), "attempt_count"));
        assertEquals(1, fixture.commits(domain, record));
        assertSchedule(domain, 10, SCHEDULED.plusMinutes(1));
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void stopAfterOneBatchRetainsPartialSlotAndRealAccessCallbackDoesNotAdvanceWatermark(Domain domain) throws Exception {
        var pair = prepare(domain);
        if (domain == Domain.ACCESS) fixture.db.update("INSERT INTO dh_dataforge_access_increase_log(id,data_access_id,table_name,increase_column,end_val) VALUES(1,10,'orders','id','0')");
        long record = fixture.enqueue(domain, 10, 1, "partial");
        long next = fixture.enqueue(domain, 10, 1, "next");
        assertEquals("true", pair.get(0).call("CLAIM", domain, record, 1, ""));
        long work = pair.get(0).send("RUN", domain, record, 1, "PARTIAL");
        pair.get(0).await(work, "BEFORE_COMMIT"); fixture.open("before-" + work);
        pair.get(0).await(work, "AFTER_COMMIT");
        assertEquals(1, fixture.commits(domain, record));
        assertEquals("2", pair.get(1).call("STOP", domain, record, 1, ""));
        assertEquals("false", pair.get(1).call("CLAIM", domain, next, 1, ""));
        assertState(domain, record, 2, "UNKNOWN", true, false);
        fixture.open("after-" + work);
        assertEquals("stopped-one-of-two-batches", pair.get(0).await(work, "DONE"));
        assertState(domain, record, domain.stopped, "PARTIAL", true, true);
        assertNotNull(fixture.run(domain, record).get("end_time"));
        assertEquals("protected", pair.get(1).call("DELETE_PROTECTED", domain, record, 1, ""));
        assertEquals("false", pair.get(1).call("CLAIM", domain, next, 1, ""));
        assertEquals(1, slots(domain, 10));
        assertEquals(1, fixture.commits(domain, record));
        assertEquals(0, fixture.commits(domain, next));
        if (domain == Domain.ACCESS) {
            assertEquals(1, fixture.count("SELECT count(*) FROM dh_dataforge_access_task_detail WHERE task_id=? AND if_success=0 AND sync_count=1", record));
            assertEquals(1, fixture.count("SELECT count(*) FROM dh_dataforge_access_increase_log"));
            assertEquals("0", fixture.db.queryForObject("SELECT end_val FROM dh_dataforge_access_increase_log WHERE id=1", String.class));
        }
        assertSchedule(domain, 10, SCHEDULED);
    }

    @ParameterizedTest @EnumSource(Domain.class)
    void globalScansPreserveTaskOwnershipAndStaleLeaseCannotMutateOwner(Domain domain) throws Exception {
        var pair = prepare(domain);
        fixture.seed(domain, 20, 2);
        fixture.open("tenants");
        assertEquals("2", pair.get(0).call("SCAN", domain, 0, 77, "tenants"));
        long first = fixture.count("SELECT id FROM " + domain.runs + " WHERE " + domain.taskColumn + "=10");
        long second = fixture.count("SELECT id FROM " + domain.runs + " WHERE " + domain.taskColumn + "=20");
        assertEquals("true", pair.get(0).call("CLAIM", domain, first, 1, ""));
        var before = fixture.run(domain, first);
        assertEquals("stale-denied", pair.get(0).call("STALE_DENIED", domain, first, 2, ""));
        assertEquals(number(before, "row_version"), number(fixture.run(domain, first), "row_version"));
        assertEquals(false, fixture.run(domain, first).get("stop_requested"));
        assertEquals(0, fixture.count("SELECT count(*) FROM dh_dataforge_run_command"));
        assertEquals("true", pair.get(1).call("CLAIM", domain, second, 2, ""));
        complete(pair.get(0), domain, first, 1);
        complete(pair.get(1), domain, second, 2);
        assertEquals(1, fixture.count("SELECT count(*) FROM fault_engine_commit WHERE record_id=?", first));
        assertEquals(1, fixture.count("SELECT count(*) FROM fault_engine_commit WHERE record_id=?", second));
        assertState(domain, first, 3, "COMPLETE", false, false);
        assertState(domain, second, 3, "COMPLETE", false, false);
        assertSchedule(domain, 10, SCHEDULED.plusMinutes(1));
        assertSchedule(domain, 20, SCHEDULED.plusMinutes(1));
    }

    private List<DistributedRunFaultSupport.Node> prepare(Domain domain) throws Exception {
        fixture.seed(domain, 10, 1);
        return fixture.startPair();
    }
    private void complete(DistributedRunFaultSupport.Node owner, Domain domain, long record, long tenant) {
        long work = owner.send("RUN", domain, record, tenant, "COMPLETE");
        owner.await(work, "BEFORE_COMMIT"); fixture.open("before-" + work);
        owner.await(work, "AFTER_COMMIT"); fixture.open("after-" + work);
        owner.await(work, "BEFORE_FINISH"); fixture.open("finish-" + work);
        assertEquals("complete", owner.await(work, "DONE"));
    }
    private void recoverUnknown(DistributedRunFaultSupport.Node other, Domain domain, long record, long expectedCommits) {
        fixture.awaitExpired(domain, record);
        assertState(domain, record, 2, "UNKNOWN", true, false);
        assertNotNull(fixture.run(domain, record).get("execution_token"), "Process death alone must not rewrite durable state");
        other.call("RECOVER_SCAN", domain, record, 77, "");
        assertUnknownCannotReplay(other, domain, record, expectedCommits);
    }
    private void assertUnknownCannotReplay(DistributedRunFaultSupport.Node other, Domain domain, long record, long expectedCommits) {
        assertState(domain, record, domain.unknown, "UNKNOWN", true, true);
        assertNull(fixture.run(domain, record).get("execution_token"));
        assertEquals(1, number(fixture.run(domain, record), "attempt_count"));
        assertSummary(domain, 10, record, domain.unknown);
        long version = number(fixture.run(domain, record), "row_version");
        other.call("RECOVER_SCAN", domain, record, 77, "");
        assertEquals(version, number(fixture.run(domain, record), "row_version"));
        long queued = fixture.enqueue(domain, 10, 1, "blocked-after-unknown");
        assertEquals("false", other.call("CLAIM", domain, record, 1, ""));
        assertEquals("false", other.call("CLAIM", domain, queued, 1, ""));
        assertEquals("protected", other.call("DELETE_PROTECTED", domain, record, 1, ""));
        assertEquals(1, slots(domain, 10));
        assertEquals(0, number(fixture.run(domain, record), "deleted"));
        assertEquals(expectedCommits, fixture.commits(domain, record));
        assertEquals(0, fixture.commits(domain, queued));
        assertEquals(0, fixture.count("SELECT count(*) FROM dh_dataforge_access_increase_log"));
        assertSchedule(domain, 10, SCHEDULED);
    }
    private long slots(Domain domain, long task) { return fixture.count("SELECT count(*) FROM " + domain.runs + " WHERE " + domain.taskColumn + "=? AND occupies_slot", task); }
    private static long number(Map<String, Object> values, String field) { return ((Number) Objects.requireNonNull(values.get(field), field)).longValue(); }
    private void assertState(Domain domain, long record, int status, String effect, boolean slot, boolean review) {
        var run = fixture.run(domain, record);
        assertEquals(status, number(run, "run_status")); assertEquals(effect, run.get("effect_state"));
        assertEquals(slot, run.get("occupies_slot")); assertEquals(review, run.get("review_required"));
    }
    private void assertSchedule(Domain domain, long task, LocalDateTime expected) {
        assertEquals(expected, fixture.db.queryForObject("SELECT next_run_time FROM " + domain.tasks + " WHERE id=?", LocalDateTime.class, task));
    }
    private void assertSummary(Domain domain, long task, long record, int status) {
        var summary = fixture.db.queryForMap("SELECT latest_run_id,run_status FROM " + domain.tasks + " WHERE id=?", task);
        assertEquals(record, number(summary, "latest_run_id")); assertEquals(status, number(summary, "run_status"));
    }
}
