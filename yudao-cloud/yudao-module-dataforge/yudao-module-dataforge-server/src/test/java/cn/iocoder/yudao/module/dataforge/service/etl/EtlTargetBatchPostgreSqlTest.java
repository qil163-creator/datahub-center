package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic.AtomicBatchCodec;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Platform mapper/tenant/transaction integration and actual DB Switch target writes on PostgreSQL 16. */
@Testcontainers
@Timeout(90)
class EtlTargetBatchPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> PG = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer();
    EtlBatchPgFixture f;
    @BeforeEach void setup() throws Exception { f = new EtlBatchPgFixture(PG); f.requireAtomicPostgres16(); }
    @AfterEach void cleanup() throws Exception { if (f != null) f.close(); }

    @ParameterizedTest @CsvSource({"STRICT,950,true", "KEEP_TAGGED,1000,true", "BATCH_BLOCK,0,false"})
    void thousandRowsFollowExplicitDispositionWithoutTreatingCommitAsQualityPass(String disposition, int targetCount, boolean ready) throws Exception {
        var run = f.run("disposition", true); var rows = rows(); quality(run, rows);
        var dag = dag(disposition, "sink"); var port = port(run, dag);
        port.stage("sink", rows);
        var batch = batch(run);
        assertEquals(1000, batch.getInputCount()); assertEquals(950, batch.getAdmittedCount()); assertEquals(50, batch.getRejectedCount());
        assertEquals("0", targetCount()); assertEquals("STAGED", batch.getCommitStatus());
        assertEquals(ready, f.service.approve(run, dag));
        if (ready) {
            doAnswer(invocation -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return invocation.callRealMethod(); })
                    .when(f.adapter).commit(any());
            assertTrue(f.service.commitApproved(run)); assertEquals("COMMITTED", batch(run).getCommitStatus());
            assertEquals("PENDING", batch(run).getQualityStatus(), "target commit is not quality acceptance");
            assertEquals("950", f.value("SELECT count(*) FROM " + f.targetSchema + ".rows WHERE _quality_status IN ('PASS','WARN')"));
        } else {
            assertEquals("BLOCKED", batch(run).getCommitStatus()); assertFalse(f.service.commitApproved(run));
        }
        assertEquals(Integer.toString(targetCount), targetCount());
    }

    @Test void durableTypedPayloadSurvivesPlatformRoundTripWithExactNumbersDatesAndBytes() throws Exception {
        var run = f.run("typed", true); var rows = rows(); quality(run, rows);
        var dag = dag("STRICT", "sink"); port(run, dag).stage("sink", rows);
        var batch = batch(run); var restored = AtomicBatchCodec.decode(batch.getPayloadJson());
        assertEquals(batch.getPayloadHash(), restored.payloadHash()); assertEquals(950, restored.rowCount());
        assertEquals(9007199254740993L, restored.rows().getFirst()[0]);
        assertEquals(new BigDecimal("12345678901234567890.123456"), restored.rows().getFirst()[1]);
        assertEquals(OffsetDateTime.parse("2026-10-03T12:34:56.123456+08:00"), restored.rows().getFirst()[2]);
        assertArrayEquals(new byte[]{0, 1, -1}, (byte[]) restored.rows().getFirst()[3]);
        assertEquals(1000, AtomicBatchCodec.decode(batch.getSourcePayloadJson()).rowCount());
        f.service.approve(run, dag); assertTrue(f.service.commitApproved(run));
        assertEquals("12345678901234567890.123456", f.value("SELECT amount::text FROM " + f.targetSchema + ".rows WHERE _etl_row_no=1"));
        assertEquals("9007199254740993", f.value("SELECT id::text FROM " + f.targetSchema + ".rows WHERE _etl_row_no=1"));
        assertEquals("0001ff", f.value("SELECT encode(raw,'hex') FROM " + f.targetSchema + ".rows WHERE _etl_row_no=1"));
    }

    @Test void lostAcknowledgementAfterActualTargetCommitRecoversFromStableLedgerWithoutDuplicating() throws Exception {
        var run = f.run("unknown", true); var rows = rows(); quality(run, rows);
        var dag = dag("STRICT", "sink"); port(run, dag).stage("sink", rows); f.service.approve(run, dag);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object receipt = invocation.callRealMethod();
            if (first.getAndSet(false)) throw new IllegalStateException("fixture lost adapter response after target commit");
            return receipt;
        }).when(f.adapter).commit(any());
        assertFalse(f.service.commitApproved(run)); assertEquals("950", targetCount());
        assertEquals("UNKNOWN", batch(run).getCommitStatus());
        f.sql("UPDATE dh_dataforge_etl_target_batch SET next_attempt_at=clock_timestamp()-interval '1 second'");
        assertTrue(f.service.commitApproved(run)); assertEquals("950", targetCount());
        assertEquals("1", f.value("SELECT count(*) FROM " + f.targetSchema + ".dbswitch_batch_receipt_v1"));
        assertNotNull(batch(run).getReceiptJson());
    }

    @Test void incompleteMultipleSinkGraphCannotApproveAnyTarget() throws Exception {
        var run = f.run("incomplete", true); var rows = rows(); quality(run, rows);
        var dag = dag("STRICT", "one", "two"); var port = port(run, dag);
        port.stage("one", rows);
        assertThrows(RuntimeException.class, () -> f.service.approve(run, dag));
        assertEquals("STAGED", batch(run).getCommitStatus()); assertEquals("0", targetCount());
        port.stage("two", rows); assertTrue(f.service.approve(run, dag));
        assertEquals("2", f.value("SELECT count(*) FROM dh_dataforge_etl_target_batch WHERE commit_status='READY'"));
    }

    @Test void approvingAllSinksRollsBackTogetherWhenOneUpdateFails() throws Exception {
        var run = f.run("atomic-approve", true); var rows = rows(); quality(run, rows);
        var dag = dag("STRICT", "one", "two"); var port = port(run, dag);
        port.stage("one", rows); port.stage("two", rows);
        f.sql("CREATE FUNCTION reject_second_ready() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.node_no='two' AND NEW.commit_status='READY' THEN RAISE EXCEPTION 'fixture'; END IF; RETURN NEW; END $$");
        f.sql("CREATE TRIGGER reject_second_ready BEFORE UPDATE ON dh_dataforge_etl_target_batch FOR EACH ROW EXECUTE FUNCTION reject_second_ready()");
        assertThrows(RuntimeException.class, () -> f.service.approve(run, dag));
        assertEquals("2", f.value("SELECT count(*) FROM dh_dataforge_etl_target_batch WHERE commit_status='STAGED'"));
        assertEquals("0", targetCount());
    }

    @Test void stopRequestPreventsReadyDespiteLiveExecutionLease() throws Exception {
        var run = f.run("stop", true); var rows = rows(); quality(run, rows);
        var dag = dag("STRICT", "sink"); port(run, dag).stage("sink", rows);
        f.coordinator.requestStop(run.getId());
        assertTrue(f.coordinator.owns(run.getId(), run.getExecutionToken()), "stop is not proof of worker exit");
        assertThrows(RuntimeException.class, () -> f.service.approve(run, dag));
        assertEquals("STAGED", batch(run).getCommitStatus()); assertEquals("0", targetCount());
    }

    @Test void expiredLeaseCannotStageOrApprove() throws Exception {
        var run = f.run("expired", true); var rows = rows(); quality(run, rows);
        var dag = dag("STRICT", "sink"); var port = port(run, dag);
        f.sql("UPDATE dh_dataforge_etl_record SET lease_until=clock_timestamp()-interval '1 second' WHERE id=" + run.getId());
        assertThrows(RuntimeException.class, () -> port.stage("sink", rows));
        assertTrue(f.service.listRaw(run.getId()).isEmpty()); assertEquals("0", targetCount());
    }

    @Test void platformIntentRemainsAddressableWithoutTenantContext() throws Exception {
        var run = f.run("platform-intent", true); var rows = rows(); quality(run, rows);
        var dag = dag("STRICT", "sink"); port(run, dag).stage("sink", rows);
        long id = batch(run).getId(); TenantContextHolder.clear();
        assertEquals(id, f.service.require(id).getId());
        assertEquals(1, f.service.list(run.getId()).size());
        assertThrows(RuntimeException.class, () -> f.service.require(-1L));
        assertEquals("0", targetCount());
    }

    @Test void sameIntentWithDifferentFilteredSourceOrOutputIsRejected() throws Exception {
        var run = f.run("changed", true); var rows = rows(); quality(run, rows);
        var dag = dag("STRICT", "sink"); var port = port(run, dag); port.stage("sink", rows);
        var original = batch(run).getSourcePayloadJson();
        rows.get(999).put("amount", new BigDecimal("9"));
        assertThrows(RuntimeException.class, () -> port.stage("sink", rows));
        assertEquals(original, batch(run).getSourcePayloadJson());
        rows.getFirst().put("amount", new BigDecimal("11"));
        assertThrows(RuntimeException.class, () -> port.stage("sink", rows)); assertEquals("0", targetCount());
    }

    @Test void migrationReplayRetainsOneToolAndDatabaseRejectsImpossibleCountOrMissingReceipt() throws Exception {
        assertEquals("1", f.value("SELECT count(*) FROM dh_dataforge_etl_tool WHERE code='managed-db-output'"));
        var run = f.run("constraints", true); var rows = rows(); quality(run, rows);
        port(run, dag("STRICT", "sink")).stage("sink", rows);
        assertThrows(SQLException.class, () -> f.sql("UPDATE dh_dataforge_etl_target_batch SET admitted_count=output_count+1"));
        assertThrows(SQLException.class, () -> f.sql("UPDATE dh_dataforge_etl_target_batch SET commit_status='COMMITTED',receipt_json=NULL"));
        assertThrows(SQLException.class, () -> f.sql("INSERT INTO dh_dataforge_etl_target_batch SELECT (jsonb_populate_record(NULL::dh_dataforge_etl_target_batch,to_jsonb(t)||'{\"id\":999999999}'::jsonb)).* FROM dh_dataforge_etl_target_batch t"));
        assertEquals(1, f.service.listRaw(run.getId()).size());
    }

    @Test void stoppedReadyIntentCannotStartFirstTargetCommit() throws Exception {
        var run = f.run("stop-ready", true); var rows = rows(); quality(run, rows);
        var dag = dag("STRICT", "sink"); port(run, dag).stage("sink", rows);
        f.service.approve(run, dag); f.coordinator.requestStop(run.getId());
        assertFalse(f.service.commitApproved(run));
        assertEquals("CANCELLED", batch(run).getCommitStatus()); assertEquals("0", targetCount());
        verify(f.adapter, never()).commit(any());
    }

    @Test void stoppedUnknownOnlyReconcilesCommittedReceiptWithoutRewriting() throws Exception {
        var run = f.run("stopped-unknown", true); var rows = rows(); quality(run, rows);
        var dag = dag("STRICT", "sink"); port(run, dag).stage("sink", rows); f.service.approve(run, dag);
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("lost response"); }).when(f.adapter).commit(any());
        assertFalse(f.service.commitApproved(run)); assertEquals("950", targetCount());
        f.coordinator.requestStop(run.getId()); f.sql("UPDATE dh_dataforge_etl_target_batch SET next_attempt_at=NULL");
        assertTrue(f.service.commitApproved(run)); assertEquals("950", targetCount());
        verify(f.adapter, times(1)).commit(any()); verify(f.adapter).findCommitted(any());
    }

    @Test void expiredDeliveryLeaseCannotPersistLateReceiptAndNextOwnerRecoversSameCommit() throws Exception {
        var run = f.run("late-receipt", true); var rows = rows(); quality(run, rows);
        var dag = dag("STRICT", "sink"); port(run, dag).stage("sink", rows); f.service.approve(run, dag);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            var receipt = invocation.callRealMethod();
            if (first.getAndSet(false)) f.sql("UPDATE dh_dataforge_etl_target_batch SET lease_until=clock_timestamp()-interval '1 second'");
            return receipt;
        }).when(f.adapter).commit(any());
        assertFalse(f.service.commitApproved(run)); assertEquals("COMMITTING", batch(run).getCommitStatus());
        assertNull(batch(run).getReceiptJson()); assertEquals("950", targetCount());
        assertTrue(f.service.commitApproved(run)); assertNotNull(batch(run).getReceiptJson());
        assertEquals("950", targetCount()); assertEquals("1", f.value("SELECT count(*) FROM " + f.targetSchema + ".dbswitch_batch_receipt_v1"));
    }

    @Test void manualRetryRequiresFailedStateAndRecordsAuditWithoutChangingFrozenIntent() throws Exception {
        var run = f.run("manual-retry", true); var rows = rows(); quality(run, rows);
        var dag = dag("STRICT", "sink"); port(run, dag).stage("sink", rows);
        long id = batch(run).getId(); var frozen = batch(run).getSourcePayloadJson();
        assertThrows(RuntimeException.class, () -> f.coordinator.retryTargetBatch(id, run.getId(), 99L, "retry"));
        f.sql("UPDATE dh_dataforge_etl_target_batch SET commit_status='FAILED',attempt_count=10");
        f.coordinator.retryTargetBatch(id, run.getId(), 99L, "reviewed recovery");
        assertEquals("UNKNOWN", batch(run).getCommitStatus()); assertEquals(0, batch(run).getAttemptCount());
        assertEquals(frozen, batch(run).getSourcePayloadJson());
        assertEquals("1", f.value("SELECT count(*) FROM dh_dataforge_etl_target_action WHERE action='RECONCILE_RETRY'"));
        assertThrows(RuntimeException.class, () -> f.coordinator.retryTargetBatch(id, run.getId(), 99L, "duplicate"));
        assertEquals("1", f.value("SELECT count(*) FROM dh_dataforge_etl_target_action")); assertEquals("0", targetCount());
    }

    @Test void admittedReadPreservesValuesAndQualityStatusFilteringBeforePagination() throws Exception {
        var run = f.run("consume", true); var rows = rows(); quality(run, rows);
        var dag = dag("KEEP_TAGGED", "sink"); port(run, dag).stage("sink", rows);
        f.service.approve(run, dag); assertTrue(f.service.commitApproved(run));
        f.sql("UPDATE dh_dataforge_etl_target_batch SET quality_status='PASSED'");
        assertTrue(f.coordinator.finish(run.getId(), run.getExecutionToken(), EtlRecordDO.RUN_STATUS_SUCCESS,
                cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.COMPLETE, true, "complete"));
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(99L);
            var page = f.service.rows(batch(run).getId(), 925, 20);
            assertEquals(20, page.rows().size()); assertTrue(page.hasMore()); assertEquals(945, page.nextAfterRow());
            assertEquals("12345678901234567890.123456", page.rows().getFirst().get("amount").toString());
            var tail = f.service.rows(batch(run).getId(), page.nextAfterRow(), 20);
            assertEquals(5, tail.rows().size()); assertFalse(tail.hasMore()); assertEquals(950, tail.nextAfterRow());
            assertTrue(tail.rows().stream().allMatch(r -> "WARN".equals(r.get("_quality_status"))));
        }
        assertEquals("1000", targetCount(), "KEEP_TAGGED retains rejected rows physically but never consumes them");
    }

    @Test void oneUnacceptedTargetBlocksWholeRunConsumptionUntilAllTargetsAccepted() throws Exception {
        var run = f.run("consume-gate", true); var rows = rows(); quality(run, rows);
        var dag = dag("STRICT", "one", "two"); var port = port(run, dag);
        port.stage("one", rows); port.stage("two", rows); f.service.approve(run, dag); assertTrue(f.service.commitApproved(run));
        assertTrue(f.coordinator.finish(run.getId(), run.getExecutionToken(), EtlRecordDO.RUN_STATUS_SUCCESS,
                cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.COMPLETE, true, "complete"));
        f.sql("UPDATE dh_dataforge_etl_target_batch SET quality_status='PASSED' WHERE node_no='one'");
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(99L);
            assertThrows(RuntimeException.class, () -> f.service.rows(batch(run).getId(), 0, 20));
            f.sql("UPDATE dh_dataforge_etl_target_batch SET quality_status='PASSED'");
            assertEquals(20, f.service.rows(batch(run).getId(), 0, 20).rows().size());
        }
    }

    @Test void authorizedReplayReplacesOriginalPhysicalBatchAndRepeatedOperationDoesNotAppend() throws Exception {
        var replay = prepareReplay(); quality(replay.run(), replay.rows());
        port(replay.run(), replay.dag()).stage("sink", replay.rows()); assertTrue(f.service.approve(replay.run(), replay.dag()));
        assertTrue(f.service.commitApproved(replay.run())); var repaired = batch(replay.run());
        assertEquals("1000", targetCount()); assertEquals(replay.original().getBatchKey(), repaired.getPhysicalBatchKey());
        assertNotEquals(replay.original().getBatchKey(), repaired.getBatchKey());
        assertEquals("SUPERSEDED", f.batches.selectById(replay.original().getId()).getCommitStatus());
        assertEquals(repaired.getId(), f.batches.selectById(replay.original().getId()).getReplacedByBatchId());
        assertTrue(f.service.commitApproved(replay.run())); assertNotNull(f.adapter.commit(repaired));
        assertEquals("1000", targetCount()); assertEquals("1", f.value("SELECT count(*) FROM " + f.targetSchema + ".dbswitch_batch_operation_v1"));
        assertEquals(replay.original().getReceiptJson(), f.batches.selectById(replay.original().getId()).getReceiptJson());
    }

    @Test void replayLostAcknowledgementRecoversImmutableOperationReceiptWithoutSecondReplacement() throws Exception {
        var replay = prepareReplay(); quality(replay.run(), replay.rows());
        port(replay.run(), replay.dag()).stage("sink", replay.rows()); f.service.approve(replay.run(), replay.dag());
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            var receipt = invocation.callRealMethod();
            if (first.getAndSet(false)) throw new IllegalStateException("lost replacement receipt"); return receipt;
        }).when(f.adapter).commit(any());
        assertFalse(f.service.commitApproved(replay.run())); assertEquals("1000", targetCount());
        assertEquals("UNKNOWN", batch(replay.run()).getCommitStatus());
        assertEquals("COMMITTED", f.batches.selectById(replay.original().getId()).getCommitStatus());
        f.sql("UPDATE dh_dataforge_etl_target_batch SET next_attempt_at=NULL WHERE record_id=" + replay.run().getId());
        assertTrue(f.service.commitApproved(replay.run()));
        assertEquals("SUPERSEDED", f.batches.selectById(replay.original().getId()).getCommitStatus());
        assertEquals("1000", targetCount()); assertEquals("1", f.value("SELECT count(*) FROM " + f.targetSchema + ".dbswitch_batch_operation_v1"));
    }

    @Test void replaySubsetCannotClaimCompleteOriginalPopulation() throws Exception {
        var replay = prepareReplay(); replay.rows().removeLast(); quality(replay.run(), replay.rows());
        var port = port(replay.run(), replay.dag());
        assertThrows(RuntimeException.class, () -> port.stage("sink", replay.rows()));
        assertTrue(f.service.listRaw(replay.run().getId()).isEmpty()); assertEquals("950", targetCount());
        assertEquals("COMMITTED", f.batches.selectById(replay.original().getId()).getCommitStatus());
    }

    @Test void replayCannotOverwriteExternallyModifiedOriginalBatch() throws Exception {
        var replay = prepareReplay(); quality(replay.run(), replay.rows());
        port(replay.run(), replay.dag()).stage("sink", replay.rows()); f.service.approve(replay.run(), replay.dag());
        f.sql("UPDATE " + f.targetSchema + ".rows SET amount=777 WHERE _etl_row_no=1");
        assertFalse(f.service.commitApproved(replay.run()));
        assertEquals("950", targetCount()); assertEquals("777", f.value("SELECT amount::text FROM " + f.targetSchema + ".rows WHERE _etl_row_no=1"));
        assertEquals("UNKNOWN", batch(replay.run()).getCommitStatus());
        assertEquals("COMMITTED", f.batches.selectById(replay.original().getId()).getCommitStatus());
        assertEquals("0", f.value("SELECT count(*) FROM " + f.targetSchema + ".dbswitch_batch_operation_v1"));
    }

    private Replay prepareReplay() throws Exception {
        var originalRun = f.run("original", true); var originalRows = rows(); quality(originalRun, originalRows);
        var graph = dag("STRICT", "sink"); port(originalRun, graph).stage("sink", originalRows);
        f.service.approve(originalRun, graph); assertTrue(f.service.commitApproved(originalRun)); var original = batch(originalRun);
        assertTrue(f.coordinator.finish(originalRun.getId(), originalRun.getExecutionToken(), EtlRecordDO.RUN_STATUS_SUCCESS,
                cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.COMPLETE, true, "complete"));
        var replayRun = f.run("repaired", true);
        var snapshot = cn.hutool.json.JSONUtil.parseObj(originalRun.getConfigJson());
        snapshot.set("governanceReplay", new JSONObject().set("confirmedTargetReplacement", true).set("recordId", originalRun.getId())
                .set("workOrderId", 41L).set("submissionId", 42L).set("targets", new JSONObject().set("sink",
                        new JSONObject().set("batchId", original.getId()).set("payloadHash", original.getPayloadHash()))));
        replayRun.setConfigJson(snapshot.toString());
        var scope = new cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeRespDTO();
        scope.setWorkOrderId(41L); scope.setSubmissionId(42L);
        var target = new cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeRespDTO.OriginalTarget();
        target.setTargetBatchId(original.getId()); target.setRecordId(originalRun.getId()); target.setSinkNodeId("sink"); target.setQualityTaskId(100L);
        scope.setOriginalTargets(List.of(target));
        when(f.qualityApi.describeEtlWorkOrderReplay(any())).thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(scope));
        var fixed = rows(); fixed.forEach(r -> { r.put("_quality_status", "PASS"); r.put("amount", new BigDecimal("17")); });
        return new Replay(original, replayRun, graph, fixed);
    }

    @Test void consumerRpcPinsExactBatchIdentityAndPreservesDecimalWirePrecision() throws Exception {
        var run = f.run("consumer", true); var input = rows(); quality(run, input); var graph = dag("KEEP_TAGGED", "sink");
        port(run, graph).stage("sink", input); f.service.approve(run, graph); assertTrue(f.service.commitApproved(run));
        assertTrue(f.coordinator.finish(run.getId(), run.getExecutionToken(), EtlRecordDO.RUN_STATUS_SUCCESS,
                cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.COMPLETE, true, "complete"));
        f.sql("UPDATE dh_dataforge_etl_target_batch SET quality_status='PASSED'");
        var description = f.service.describe(batch(run).getId(), 99L);
        assertTrue(description.getAvailable()); assertEquals(1000L, description.getInputCount()); assertEquals(50L, description.getRejectCount());
        var request = new cn.iocoder.yudao.module.dataforge.api.etl.dto.EtlTargetBatchReadReqDTO();
        request.setBatchId(description.getBatchId()); request.setExecutionUserId(99L); request.setAfterRow(929L); request.setLimit(30);
        request.setExpectedPayloadHash(description.getPayloadHash()); request.setExpectedDatabaseId(description.getDatabaseId());
        request.setExpectedSchemaName(description.getSchemaName()); request.setExpectedTableName(description.getTableName());
        var page = f.service.readForConsumer(request);
        assertEquals(21, page.getRows().size()); assertEquals(950L, page.getNextAfterRow()); assertFalse(page.getHasMore());
        assertEquals("12345678901234567890.123456", page.getRows().getFirst().get("amount"));
        request.setExpectedPayloadHash("a".repeat(64)); assertThrows(RuntimeException.class, () -> f.service.readForConsumer(request)); request.setExpectedPayloadHash(description.getPayloadHash());
        request.setExpectedDatabaseId(999L); assertThrows(RuntimeException.class, () -> f.service.readForConsumer(request)); request.setExpectedDatabaseId(description.getDatabaseId());
        request.setExpectedSchemaName("wrong"); assertThrows(RuntimeException.class, () -> f.service.readForConsumer(request)); request.setExpectedSchemaName(description.getSchemaName());
        request.setExpectedTableName("wrong"); assertThrows(RuntimeException.class, () -> f.service.readForConsumer(request)); request.setExpectedTableName(description.getTableName());
        f.sql("UPDATE " + f.targetSchema + ".rows SET amount=42 WHERE _etl_row_no=1");
        assertThrows(RuntimeException.class, () -> f.service.describe(description.getBatchId(), 99L));
        assertThrows(RuntimeException.class, () -> f.service.readForConsumer(request));
    }

    @Test void consumerDescriptionShowsPendingAdmissionAndStillRequiresObjectAccess() throws Exception {
        var run = f.run("pending-consumer", true); var input = rows(); quality(run, input); var graph = dag("STRICT", "sink");
        port(run, graph).stage("sink", input);
        var described = f.service.describe(batch(run).getId(), 99L);
        assertFalse(described.getAvailable()); assertEquals(batch(run).getPayloadHash(), described.getPayloadHash());
        doThrow(new IllegalStateException("source denied")).when(f.access).requireDatabase(1L);
        assertThrows(IllegalStateException.class, () -> f.service.describe(described.getBatchId(), 99L));
    }
    @Test void fullCapacityBatchHasBoundedPayloadAndRejectsAnExtraRow() throws Exception {
        var run = f.run("capacity", true); var input = new ArrayList<Map<String,Object>>();
        var sample = rows().getFirst();
        for (int i=0; i<5000; i++) { var row = new LinkedHashMap<>(sample); row.put("id", 9007199254740993L+i); input.add(row); }
        quality(run, input); var graph = dag("KEEP_TAGGED", "sink");
        long started = System.nanoTime();
        port(run, graph).stage("sink", input); assertTrue(f.service.approve(run, graph)); assertTrue(f.service.commitApproved(run));
        long elapsed = (System.nanoTime()-started)/1_000_000;
        assertEquals("5000", targetCount());
        int encodedBytes = batch(run).getPayloadJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(encodedBytes < 8*1024*1024);
        System.out.println("ETL_CAPACITY_BASELINE product=PostgreSQL16 concurrency=1 rows=5000 businessFields=5 targetFields=7 stageCommitMs=" + elapsed + " typedPayloadBytes=" + encodedBytes);
        input.add(new LinkedHashMap<>(sample));
        assertThrows(RuntimeException.class, () -> EtlTargetBatchService.identityHash(List.of("id"), input));
        assertEquals("5000", targetCount());
    }

    @Test void secondRepairReplacesCurrentHeadAndPreservesOriginalPopulationEvidence() throws Exception {
        var first = prepareReplay(); quality(first.run(), first.rows());
        port(first.run(), first.dag()).stage("sink", first.rows()); f.service.approve(first.run(), first.dag());
        assertTrue(f.service.commitApproved(first.run())); var firstBatch = batch(first.run());
        assertTrue(f.coordinator.finish(first.run().getId(), first.run().getExecutionToken(), EtlRecordDO.RUN_STATUS_SUCCESS,
                cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.COMPLETE, true, "complete"));
        f.sql("UPDATE dh_dataforge_etl_target_batch SET quality_status='FAILED' WHERE id="+firstBatch.getId());
        assertEquals(firstBatch.getId(), EtlReplayLineage.current(f.batches, f.batches.selectById(first.original().getId())).getId());
        var second = f.run("second-repair", true);
        var snapshot = cn.hutool.json.JSONUtil.parseObj(first.run().getConfigJson());
        var replay = snapshot.getJSONObject("governanceReplay"); replay.set("submissionId", 43L);
        replay.getJSONObject("targets").set("sink", new JSONObject().set("batchId",firstBatch.getId())
                .set("recordId",first.run().getId()).set("payloadHash",firstBatch.getPayloadHash()));
        second.setConfigJson(snapshot.toString());
        var scope = new cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeRespDTO();
        scope.setWorkOrderId(41L); scope.setSubmissionId(43L);
        var described = new cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeRespDTO.OriginalTarget();
        described.setTargetBatchId(firstBatch.getId()); described.setRecordId(first.run().getId()); scope.setOriginalTargets(List.of(described));
        when(f.qualityApi.describeEtlWorkOrderReplay(any())).thenReturn(cn.iocoder.yudao.framework.common.pojo.CommonResult.success(scope));
        var fixed = first.rows(); fixed.forEach(row -> row.put("amount",new BigDecimal("19")));
        quality(second,fixed); port(second,first.dag()).stage("sink",fixed); f.service.approve(second,first.dag());
        assertTrue(f.service.commitApproved(second));
        assertEquals("1000", targetCount()); assertEquals("19", f.value("SELECT amount::text FROM "+f.targetSchema+".rows WHERE _etl_row_no=1"));
        var current = EtlReplayLineage.current(f.batches,f.batches.selectById(first.original().getId()));
        assertEquals(batch(second).getId(),current.getId()); assertEquals(first.original().getIdentityHash(),current.getIdentityHash());
        assertEquals(first.original().getReceiptJson(),f.batches.selectById(first.original().getId()).getReceiptJson());
        assertEquals("2",f.value("SELECT count(*) FROM "+f.targetSchema+".dbswitch_batch_operation_v1"));
    }

    private record Replay(EtlTargetBatchDO original, EtlRecordDO run, EtlDag dag, List<Map<String, Object>> rows) { }

    private void quality(EtlRecordDO run, List<Map<String, Object>> rows) throws Exception {
        int pass = (int) rows.stream().filter(r -> "PASS".equals(r.get("_quality_status"))).count();
        int warn = (int) rows.stream().filter(r -> "WARN".equals(r.get("_quality_status"))).count();
        f.quality(run, "quality", rows.size(), pass, warn, rows.size() - pass - warn);
        String hash = EtlTargetBatchService.identityHash(List.of("id"), rows);
        f.sql("UPDATE dh_dataforge_etl_quality_batch SET identity_fields_json='[\"id\"]',identity_hash='" + hash + "' WHERE record_id=" + run.getId());
    }
    private EtlTargetBatchDO batch(EtlRecordDO run) { return f.service.listRaw(run.getId()).getFirst(); }
    private cn.iocoder.yudao.framework.etl.core.connection.EtlStagedOutputPort port(EtlRecordDO run, EtlDag dag) {
        var snapshot = cn.hutool.json.JSONUtil.parseObj(run.getConfigJson());
        if (!snapshot.containsKey("targetPlans")) snapshot.set("targetPlans", f.service.validate(dag, 99L, Map.of()));
        snapshot.set("nodes", dag.getNodes().stream().map(n -> Map.of("id", n.getNo(), "properties", Map.of(
                "toolCode", n.getToolCode(), "toolType", n.getToolType(), "nodeJson", n.getNodeJson()))).toList());
        run.setConfigJson(snapshot.toString());
        assertEquals(1, f.records.updateById(EtlRecordDO.builder().id(run.getId()).configJson(run.getConfigJson()).build()));
        return f.service.createPort(run, dag, Map.of());
    }
    private String targetCount() throws Exception { return f.value("SELECT count(*) FROM " + f.targetSchema + ".rows"); }
    private EtlDag dag(String disposition, String... nodes) {
        var list = new ArrayList<EtlNodeDef>();
        for (String id : nodes) {
            var config = new JSONObject().set("databaseId", 1L).set("tableName", "\"rows\"").set("qualityTaskId", 100L)
                    .set("qualityNodeNo", "quality").set("disposition", disposition).set("allowWarnings", true)
                    .set("businessKeys", List.of("id")).set("fields", List.of("id", "amount", "observed_at", "raw", "_quality_status"));
            var node = new EtlNodeDef(); node.setNo(id); node.setToolCode("managed-db-output"); node.setToolType(3); node.setNodeJson(config.toString()); list.add(node);
        }
        return EtlDag.buildLenient(list, List.of());
    }
    private List<Map<String, Object>> rows() {
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 1000; i++) {
            var row = new LinkedHashMap<String, Object>(); row.put("id", 9007199254740993L + i);
            row.put("amount", new BigDecimal("12345678901234567890.123456"));
            row.put("observed_at", OffsetDateTime.parse("2026-10-03T12:34:56.123456+08:00")); row.put("raw", new byte[]{0, 1, -1});
            row.put("_quality_status", i < 930 ? "PASS" : i < 950 ? "WARN" : "REJECT"); rows.add(row);
        }
        return rows;
    }
}
