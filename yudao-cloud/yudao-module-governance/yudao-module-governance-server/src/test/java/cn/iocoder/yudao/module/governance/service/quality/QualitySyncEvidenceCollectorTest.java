package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.module.dataforge.api.migration.*;
import cn.iocoder.yudao.module.dataforge.api.migration.dto.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskInstanceMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QualitySyncEvidenceCollectorTest {
    @Test void commitInspectionBindsRunAttemptRowVersionAndSnapshotButNeverChangesReplicaAdmission() throws Exception {
        configureRecheck(); withTransfer(6L, transferWithCommits(completeCommits()));
        var snapshot = mock(cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot.class);
        var evidence = new cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot.Evidence(1, "POSTGRESQL_REPEATABLE_READ_V1", "c".repeat(64));
        when(snapshot.evidence()).thenReturn(evidence);
        when(snapshot.inspectCommits(anyList())).thenAnswer(call -> {
            List<cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt> receipts = call.getArgument(0);
            return new cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot.CommitVisibility(1, evidence,
                    receipts.stream().map(r -> new cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot.CommitObservation(r, "VISIBLE")).toList());
        });
        var before = observe();
        var result = QualitySyncEvidenceCollector.inspectCommits(before, snapshot);
        assertEquals("OBSERVED_NOT_ARRIVAL_PROOF", result.reason());
        assertEquals(9007199254740993L, result.bindings().getFirst().runId());
        assertEquals(1, result.bindings().getFirst().attempt());
        assertEquals(1L, result.bindings().getFirst().rowVersion());
        assertEquals(1L, result.bindings().getFirst().batch());
        assertEquals(evidence, result.visibility().snapshot());
        var read = QualityReadEvidence.capture(conn, object, "select 1", true,
                java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
        read.setCommitScope(result);
        assertEquals("UNPROVEN_REPLICA", JsonUtils.parseObject(JsonUtils.toJsonString(read), QualityReadEvidence.class).getReadMode());
        assertEquals(result, JsonUtils.parseObject(JsonUtils.toJsonString(read), QualityReadEvidence.class).getCommitScope());
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("invalidCommitLists")
    void missingInvalidTruncatedOrExcessiveReceiptsNeverQuerySnapshot(SyncCommitReceiptsDTO commits) {
        configureRecheck(); withTransfer(6L, transferWithCommits(commits));
        var snapshot = mock(cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot.class);
        assertNotEquals("OBSERVED_NOT_ARRIVAL_PROOF", QualitySyncEvidenceCollector.inspectCommits(observe(), snapshot).reason());
        verifyNoInteractions(snapshot);
    }

    static java.util.stream.Stream<SyncCommitReceiptsDTO> invalidCommitLists() {
        var valid = completeCommits(); var entry = valid.receipts().getFirst();
        var many = java.util.stream.LongStream.rangeClosed(1,17).mapToObj(n -> new SyncCommitReceiptsDTO.Entry(n, 1,
                entry.protocol(), Long.toString(100+n), entry.routeFingerprint())).toList();
        return java.util.stream.Stream.of(null,
                new SyncCommitReceiptsDTO(2,1L,1L,false,valid.receipts()),
                new SyncCommitReceiptsDTO(1,2L,2L,true,valid.receipts()),
                new SyncCommitReceiptsDTO(1,1L,0L,false,valid.receipts()),
                new SyncCommitReceiptsDTO(1,1L,1L,false,null),
                new SyncCommitReceiptsDTO(1,0L,0L,false,List.of()),
                new SyncCommitReceiptsDTO(1,17L,17L,false,many),
                new SyncCommitReceiptsDTO(1,2L,2L,false,List.of(entry,entry)),
                new SyncCommitReceiptsDTO(1,1L,1L,false,List.of(new SyncCommitReceiptsDTO.Entry(2L,1,entry.protocol(),"101",entry.routeFingerprint()))),
                new SyncCommitReceiptsDTO(1,1L,1L,false,List.of(new SyncCommitReceiptsDTO.Entry(1L,null,entry.protocol(),"101",entry.routeFingerprint()))),
                new SyncCommitReceiptsDTO(1,1L,1L,false,List.of(new SyncCommitReceiptsDTO.Entry(1L,1,entry.protocol(),"1; select",entry.routeFingerprint()))));
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"unsupported","sql","mismatch"})
    void unavailableOrWrongSnapshotCannotPublishCommitObservations(String fault) throws Exception {
        configureRecheck(); withTransfer(6L, transferWithCommits(completeCommits()));
        var snapshot = mock(cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot.class);
        when(snapshot.evidence()).thenReturn(new cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot.Evidence(1,"snapshot","c".repeat(64)));
        if (fault.equals("unsupported")) when(snapshot.inspectCommits(anyList())).thenThrow(new java.sql.SQLFeatureNotSupportedException("private"));
        else if (fault.equals("sql")) when(snapshot.inspectCommits(anyList())).thenThrow(new java.sql.SQLException("private"));
        else when(snapshot.inspectCommits(anyList())).thenReturn(new cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot.CommitVisibility(1,
                new cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot.Evidence(1,"other","d".repeat(64)),List.of()));
        var result = QualitySyncEvidenceCollector.inspectCommits(observe(),snapshot);
        assertNull(result.visibility()); assertTrue(result.bindings().isEmpty());
        assertFalse(JsonUtils.toJsonString(result).contains("private"));
    }

    private static SyncCommitReceiptsDTO completeCommits() {
        return new SyncCommitReceiptsDTO(1,1L,1L,false,List.of(new SyncCommitReceiptsDTO.Entry(1L,1,"POSTGRESQL_XID8_V1","101","a".repeat(64))));
    }
    private static SyncTableTransferDTO transferWithCommits(SyncCommitReceiptsDTO commits) {
        return new SyncTableTransferDTO(1,"FULL_SCAN_TABLE",3L,3L,2,2,0L,false,false,false,commits);
    }
    final QualitySyncEvidenceCollector collector = new QualitySyncEvidenceCollector();
    final SyncBatchEvidenceApi api = mock(SyncBatchEvidenceApi.class);
    final QualityTaskInstanceMapper instances = mock(QualityTaskInstanceMapper.class);
    final MasterDatabaseResolver.ConnectionInfo conn = new MasterDatabaseResolver.ConnectionInfo();
    final QualityCheckObjectDO object = QualityCheckObjectDO.builder().tableName("public.orders").datasourceType("ODS").build();
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(collector, "api", api); ReflectionTestUtils.setField(collector, "instanceMapper", instances);
        conn.setDatabaseId(20L); conn.setProductType(ProductTypeEnum.POSTGRESQL); conn.setSchema("public");
        conn.setUsageType(2); conn.setProviderConfigurationRevision("rev"); conn.setConfigurationRevision("jdbc-v1:digest");
        when(instances.selectById(1L)).thenReturn(QualityTaskInstanceDO.builder().id(1L).taskId(2L).executionUserId(99L).build());
        when(api.observe(any())).thenReturn(CommonResult.success(batch(1, "rev", false, false)));
    }
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }
    static SyncBatchObservationDTO batch(long version, String revision, boolean truncated, boolean unscoped) {
        return new SyncBatchObservationDTO(1, 20L, revision, "POSTGRESQL", "public", "orders", "public.orders",
                truncated, unscoped, List.of(new SyncBatchObservationDTO.Batch(9007199254740993L, 1, version, 3,
                false, false, false, "COMPLETE", "rev", "TABLE_COPY", "CONFIRMED", true, true, 3L)));
    }
    QualitySyncEvidenceCollector.Observation observe() { return collector.observe(1L, 2L, conn, object); }

    private QualityWorkOrderSubmissionDO configureRecheck() {
        var attempts = mock(cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderRecheckMapper.class);
        var submissions = mock(cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderSubmissionMapper.class);
        var orders = mock(cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderMapper.class);
        ReflectionTestUtils.setField(collector, "recheckMapper", attempts);
        ReflectionTestUtils.setField(collector, "submissionMapper", submissions);
        ReflectionTestUtils.setField(collector, "workOrderMapper", orders);
        object.setId(3L);
        when(instances.selectById(1L)).thenReturn(QualityTaskInstanceDO.builder().id(1L).taskId(2L)
                .executionUserId(99L).runType("WORK_ORDER_RECHECK").requestKey("attempt-1").build());
        when(attempts.selectByInstance(1L)).thenReturn(QualityWorkOrderRecheckDO.builder().id(4L).workOrderId(8L)
                .submissionId(9L).instanceId(1L).requestedBy(99L).requestKey("attempt-1").build());
        var submission = QualityWorkOrderSubmissionDO.builder().id(9L).workOrderId(8L).instanceId(1L)
                .submissionType("SOURCE_FIX").status("ACTIVE").payloadHash("b".repeat(64)).build();
        when(submissions.selectById(9L)).thenReturn(submission);
        when(orders.selectById(8L)).thenReturn(QualityWorkOrderDO.builder().id(8L).taskId(2L).currentSubmissionId(9L).build());
        when(api.ensureBoundary(any())).thenAnswer(call -> {
            var request = call.getArgument(0, SyncBoundaryReqDTO.class);
            return CommonResult.success(new SyncBoundaryDTO(1, request.requestKey(), 20L, "rev", "POSTGRESQL", "public", "orders", 5L));
        });
        return submission;
    }

    private void withStartPosition(Long position) {
        withTransfer(position, new SyncTableTransferDTO(1, "FULL_SCAN_TABLE", 3L, 3L, 2, 2, 0L, false, false, false));
    }
    private void withTransfer(Long position, SyncTableTransferDTO transfer) {
        var b = batch(1, "rev", false, false); var old = b.batches().getFirst();
        when(api.observe(any())).thenReturn(CommonResult.success(new SyncBatchObservationDTO(1, 20L, "rev",
                b.product(), b.schema(), b.table(), b.tableReference(), false, false,
                List.of(new SyncBatchObservationDTO.Batch(old.runId(), old.attempt(), old.rowVersion(), old.runStatus(),
                        old.occupiesSlot(), old.reviewRequired(), old.stopRequested(), old.effectState(), old.targetRevision(),
                        old.mode(), old.termination(), old.manifestComplete(), old.tableSuccessful(), old.rows(), position, transfer)))));
    }

    @Test void boundaryIsRequestedOnlyFromCommittedRecheckContext() {
        configureRecheck(); withStartPosition(6L);
        var before = observe(); var after = observe();
        assertEquals("POST_SUBMISSION_BATCH_WRITE_ACKNOWLEDGED", QualitySyncEvidenceCollector.combine(before, after).reason());
        assertEquals(3, QualitySyncEvidenceCollector.combine(before, after).schemaVersion());
        assertEquals(before.boundary(), after.boundary());
        var ordered = inOrder(api);
        ordered.verify(api).ensureBoundary(any()); ordered.verify(api).observe(any());
        ordered.verify(api).ensureBoundary(any()); ordered.verify(api).observe(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("unprovenTransfers")
    void nonCopyOrIncompleteTransferCannotBecomeAcknowledgedScan(SyncTableTransferDTO transfer) {
        configureRecheck(); withTransfer(6L, transfer);
        var observed = observe();
        assertEquals("BATCH_TRANSFER_UNPROVEN", QualitySyncEvidenceCollector.combine(observed, observed).reason());
    }

    static java.util.stream.Stream<SyncTableTransferDTO> unprovenTransfers() {
        return java.util.stream.Stream.of(null,
                new SyncTableTransferDTO(1, "SKIPPED_EXISTING", 0L, 0L, 2, 0, 0L, false, false, false),
                new SyncTableTransferDTO(1, "SKIPPED_NO_COLUMNS", 0L, 0L, 2, 0, 0L, false, false, false),
                new SyncTableTransferDTO(1, "DDL_ONLY", 0L, 0L, 2, 0, 0L, false, false, false),
                new SyncTableTransferDTO(1, "SQL_QUERY", 3L, 3L, 2, 2, 0L, false, false, false),
                new SyncTableTransferDTO(1, "CHANGE_DATA", 3L, 3L, 2, 2, 0L, false, false, false),
                new SyncTableTransferDTO(1, "FULL_SCAN_TABLE", 3L, 2L, 2, 2, 0L, false, false, false),
                new SyncTableTransferDTO(1, "FULL_SCAN_TABLE", 4L, 4L, 2, 2, 0L, false, false, false),
                new SyncTableTransferDTO(1, "FULL_SCAN_TABLE", 3L, 3L, 2, 1, 0L, false, false, false),
                new SyncTableTransferDTO(1, "FULL_SCAN_TABLE", 3L, 3L, 2, 2, 1L, false, false, false),
                new SyncTableTransferDTO(1, "FULL_SCAN_TABLE", 3L, 3L, 2, 2, 0L, true, false, false),
                new SyncTableTransferDTO(1, "FULL_SCAN_TABLE", 3L, 3L, 2, 2, 0L, false, true, false),
                new SyncTableTransferDTO(1, "FULL_SCAN_TABLE", 3L, 3L, 2, 2, 0L, false, false, true),
                new SyncTableTransferDTO(2, "FULL_SCAN_TABLE", 3L, 3L, 2, 2, 0L, false, false, false),
                new SyncTableTransferDTO(1, "FULL_SCAN_TABLE", 3L, 3L, 2, 2, 0L, null, null, null),
                new SyncTableTransferDTO(1, "FULL_SCAN_TABLE", null, null, null, null, null, false, false, false));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {1, 5})
    void oldOrEqualStartOrderCannotBecomePostSubmissionEvidence(long position) {
        configureRecheck(); withStartPosition(position);
        var observation = observe();
        assertEquals("POST_SUBMISSION_BATCH_NOT_OBSERVED", QualitySyncEvidenceCollector.combine(observation, observation).reason());
    }

    @Test void oldNodesWithoutStartOrderStayUnproven() {
        configureRecheck(); var observation = observe();
        assertEquals("BATCH_START_ORDER_UNPROVEN", QualitySyncEvidenceCollector.combine(observation, observation).reason());
    }

    @Test void lostBoundaryResponseRetriesTheSameKeyAndNeverFallsBackToOldSuccess() {
        configureRecheck();
        var keys = new java.util.ArrayList<String>();
        doAnswer(call -> {
            var req = call.getArgument(0, SyncBoundaryReqDTO.class); keys.add(req.requestKey());
            if (keys.size() == 1) throw new IllegalStateException("synthetic lost response");
            return CommonResult.success(new SyncBoundaryDTO(1, req.requestKey(), 20L, "rev", "POSTGRESQL", "public", "orders", 5L));
        }).when(api).ensureBoundary(any());
        assertEquals("SUBMISSION_BOUNDARY_UNAVAILABLE", observe().reason());
        verify(api, never()).observe(any());
        assertEquals("OBSERVED", observe().reason());
        assertEquals(keys.getFirst(), keys.getLast());
    }

    @Test void repeatedRecheckOfSameSubmissionDoesNotMoveBoundaryIdentity() {
        var submission = configureRecheck();
        var first = observe().boundary();
        when(instances.selectById(2L)).thenReturn(QualityTaskInstanceDO.builder().id(2L).taskId(2L)
                .executionUserId(99L).runType("WORK_ORDER_RECHECK").requestKey("attempt-2").build());
        var attempts = (cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityWorkOrderRecheckMapper)
                ReflectionTestUtils.getField(collector, "recheckMapper");
        when(attempts.selectByInstance(2L)).thenReturn(QualityWorkOrderRecheckDO.builder().id(5L).workOrderId(8L)
                .submissionId(9L).instanceId(2L).requestedBy(99L).requestKey("attempt-2").build());
        submission.setInstanceId(2L);
        assertEquals(first, collector.observe(2L, 2L, conn, object).boundary());
        assertEquals("SUBMISSION_BOUNDARY_CONTEXT_INVALID", observe().reason());
    }

    @Test void withdrawnSubmissionCannotRegisterOrReuseBoundary() {
        var submission = configureRecheck(); submission.setStatus("WITHDRAWN");
        assertEquals("SUBMISSION_BOUNDARY_CONTEXT_INVALID", observe().reason()); verifyNoInteractions(api);
    }

    @Test void changedSubmissionPayloadGetsNewIdentityRatherThanBorrowingPrevious() {
        var submission = configureRecheck(); String first = observe().boundary().requestKey();
        submission.setPayloadHash("c".repeat(64));
        assertNotEquals(first, observe().boundary().requestKey());
    }

    @Test void changedTargetOrInvalidRemoteReceiptCannotBeAccepted() {
        configureRecheck();
        doReturn(CommonResult.success(new SyncBoundaryDTO(1, "quality:" + "a".repeat(64),
                99L, "rev", "POSTGRESQL", "public", "orders", 5L))).when(api).ensureBoundary(any());
        assertEquals("SUBMISSION_BOUNDARY_INVALID", observe().reason()); verify(api, never()).observe(any());
    }

    @Test void boundaryAndStartPositionRoundTripWithoutLongNarrowing() {
        configureRecheck(); withStartPosition(9007199254740993L);
        var before = observe();
        var scope = QualitySyncEvidenceCollector.combine(before, before);
        var restored = JsonUtils.parseObject(JsonUtils.toJsonString(scope), QualitySyncEvidenceCollector.Scope.class);
        assertEquals(scope, restored); assertEquals(9007199254740993L, restored.before().batch().batches().getFirst().startPosition());
    }
    @Test void usesPersistedActorAndProviderRevisionNotCombinedDriverRevision() {
        assertEquals("OBSERVED", observe().reason());
        verify(api).observe(new SyncBatchQueryReqDTO(20L, "public.orders", 99L));
    }
    @Test void missingActorNeverBorrowsLoggedInUserOrCreator() {
        when(instances.selectById(1L)).thenReturn(QualityTaskInstanceDO.builder().id(1L).taskId(2L).build());
        assertEquals("EXECUTION_ACTOR_MISSING", observe().reason()); verifyNoInteractions(api);
    }
    @Test void anotherTaskInstanceCannotProvideActor() {
        when(instances.selectById(1L)).thenReturn(QualityTaskInstanceDO.builder().id(1L).taskId(3L).executionUserId(99L).build());
        assertEquals("EXECUTION_ACTOR_MISSING", observe().reason()); verifyNoInteractions(api);
    }
    @Test void directSourceDoesNotIssueAdditionalRpc() {
        object.setDatasourceType("THIRD_PARTY"); conn.setUsageType(1);
        var result = observe(); assertEquals("SOURCE_DIRECT", result.reason());
        assertNull(QualitySyncEvidenceCollector.combine(result, result)); verifyNoInteractions(api, instances);
    }
    @Test void activePlatformTransactionDoesNotCallRemote() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertEquals("PLATFORM_TRANSACTION_ACTIVE", observe().reason()); verifyNoInteractions(api, instances);
    }
    @Test void remoteFailuresAndPermissionRevocationAreSanitized() {
        when(api.observe(any())).thenThrow(new IllegalStateException("password=must-not-leak"));
        var result = observe(); assertEquals("BATCH_QUERY_UNAVAILABLE", result.reason());
        assertFalse(JsonUtils.toJsonString(result).contains("must-not-leak"));
    }
    @Test void changedProviderConfigurationCannotUseOldBatch() {
        when(api.observe(any())).thenReturn(CommonResult.success(batch(1, "changed", false, false)));
        assertEquals("BATCH_RESPONSE_INVALID", observe().reason());
    }
    @Test void wrongTargetAndUnknownProtocolCannotBind() {
        var b = batch(1, "rev", false, false);
        when(api.observe(any())).thenReturn(CommonResult.success(new SyncBatchObservationDTO(2, 999L, "rev",
                b.product(), b.schema(), b.table(), b.tableReference(), false, false, b.batches())));
        assertEquals("BATCH_RESPONSE_INVALID", observe().reason());
    }
    @Test void lateUpdateCannotLookStableAcrossRead() {
        var before = observe();
        when(api.observe(any())).thenReturn(CommonResult.success(batch(2, "rev", false, false)));
        assertEquals("BATCH_CHANGED_DURING_READ", QualitySyncEvidenceCollector.combine(before, observe()).reason());
    }
    @Test void sameBatchesWithChangedActorAreNotStable() {
        var before = observe();
        when(instances.selectById(1L)).thenReturn(QualityTaskInstanceDO.builder().id(1L).taskId(2L).executionUserId(100L).build());
        assertEquals("BATCH_CHANGED_DURING_READ", QualitySyncEvidenceCollector.combine(before, observe()).reason());
    }
    @Test void boundedAndUnscopedWindowsKeepSpecificReasons() {
        when(api.observe(any())).thenReturn(CommonResult.success(batch(1, "rev", true, false)));
        var b = observe(); assertEquals("BATCH_WINDOW_TRUNCATED", QualitySyncEvidenceCollector.combine(b, b).reason());
        when(api.observe(any())).thenReturn(CommonResult.success(batch(1, "rev", false, true)));
        b = observe(); assertEquals("BATCH_SCOPE_UNPROVEN", QualitySyncEvidenceCollector.combine(b, b).reason());
    }
    @Test void missingOrFailedAfterObservationNeverReusesBefore() {
        var before = observe();
        when(api.observe(any())).thenReturn(CommonResult.error(403, "forbidden"));
        assertEquals("BATCH_QUERY_UNAVAILABLE", QualitySyncEvidenceCollector.combine(before, observe()).reason());
        assertEquals("COLLECTOR_UNAVAILABLE", QualitySyncEvidenceCollector.combine(before, null).reason());
    }
    @Test void successfulUnchangedBatchOnlyRecordsFacts() {
        var before = observe(); var scope = QualitySyncEvidenceCollector.combine(before, observe());
        assertEquals("STABLE_BATCH_FACTS", scope.reason());
        assertEquals(scope, JsonUtils.parseObject(JsonUtils.toJsonString(scope), QualitySyncEvidenceCollector.Scope.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"5,true,CONFIRMED,true,BATCH_RUN_UNSETTLED",
            "3,false,UNCONFIRMED,true,BATCH_RESULT_UNPROVEN", "3,false,CONFIRMED,false,BATCH_RESULT_UNPROVEN"})
    void unsettledAndIncompleteBatchesKeepDiagnosticReasons(int status, boolean occupied, String termination,
                                                           boolean complete, String expected) {
        var b = batch(1, "rev", false, false);
        var detail = new SyncBatchObservationDTO.Batch(10L, 1, 1L, status, occupied, false, false, "COMPLETE",
                "rev", "TABLE_COPY", termination, complete, true, 1L);
        when(api.observe(any())).thenReturn(CommonResult.success(new SyncBatchObservationDTO(1, 20L, "rev",
                b.product(), b.schema(), b.table(), b.tableReference(), false, false, List.of(detail))));
        var observed = observe();
        assertEquals(expected, QualitySyncEvidenceCollector.combine(observed, observed).reason());
    }

    @Test void realExecutorBracketsItsQueriesAndPersistsBothObservations() throws Exception {
        var receipts = new SyncCommitReceiptsDTO(1, 1L, 1L, false, List.of(
                new SyncCommitReceiptsDTO.Entry(1L, 1, "POSTGRESQL_XID8_V1", "9007199254740993", "a".repeat(64))));
        withTransfer(6L, new SyncTableTransferDTO(1, "FULL_SCAN_TABLE", 3L, 3L, 2, 2, 0L,
                false, false, false, receipts));
        var executor = QualityRuntimeFixture.executor();
        var registry = mock(cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry.class);
        var audits = mock(cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityAuditLogMapper.class);
        ReflectionTestUtils.setField(executor, "syncEvidenceCollector", collector);
        ReflectionTestUtils.setField(executor, "auditSqlRegistry", registry);
        ReflectionTestUtils.setField(executor, "auditLogMapper", audits);
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.Statement.class); var results = mock(java.sql.ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(results);
        when(results.next()).thenReturn(true); when(results.getLong(1)).thenReturn(0L, 3L);
        var rule = QualityRuleDO.builder().id(3L).ruleContent("SELECT id FROM orders WHERE false")
                .collectIssueDetail(0).build();
        // 本测试聚焦领域观察顺序，快照/真实 JDBC 语义由 ReadSnapshotPostgreSqlTest 覆盖。
        {
            var database = mock(cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase.class);
            var snapshot = mock(cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot.class);
            var runtime = mock(cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.class);
            var products = mock(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.class);
            when(runtime.products()).thenReturn(products);
            when(products.metadata(conn.getProductType())).thenReturn(database);
            ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
            when(database.openReadSnapshot(eq(connection), anyInt())).thenReturn(snapshot);
            when(snapshot.evidence()).thenReturn(new cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot.Evidence(
                    1, "POSTGRESQL_REPEATABLE_READ_V1", "a".repeat(64)));
            QualityAuditLogDO audit = QualityRuntimeFixture.executeRule(executor, 1L,
                    QualityTaskDO.builder().id(2L).build(), connection, conn, object, rule);
            var read = JsonUtils.parseObject(audit.getReadEvidence(), QualityReadEvidence.class);
            assertEquals("STABLE_BATCH_FACTS", read.getSyncScope().reason());
            assertEquals("UNPROVEN_REPLICA", read.getReadMode());
            assertEquals(receipts, read.getSyncScope().before().batch().batches().getFirst().transfer().commits());
            assertEquals(snapshot.evidence(), read.getSnapshotScope());
            var ordered = inOrder(api, database, statement, snapshot, audits);
            ordered.verify(api).observe(any()); ordered.verify(database).openReadSnapshot(eq(connection), anyInt());
            ordered.verify(statement, times(2)).executeQuery(anyString()); ordered.verify(snapshot).close();
            ordered.verify(api).observe(any()); ordered.verify(audits).insert(audit);
        }
    }
    @Test void realJacksonContextPreservesLargeIdsAsDecimalStrings() {
        var previous = JsonUtils.getObjectMapper();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class, YudaoJacksonAutoConfiguration.class))
                    .run(ctx -> {
                        assertNull(ctx.getStartupFailure());
                        var mapper = ctx.getBean(tools.jackson.databind.ObjectMapper.class);
                        var value = CommonResult.success(batch(1, "rev", false, false));
                        String json = mapper.writeValueAsString(value);
                        assertTrue(json.contains("\"runId\":\"9007199254740993\""));
                        assertTrue(json.contains("\"databaseId\":20"));
                        var restored = mapper.readValue(mapper.writeValueAsString(value.getData()), SyncBatchObservationDTO.class);
                        assertEquals(9007199254740993L, restored.batches().getFirst().runId());
                        var transfer = new SyncTableTransferDTO(1, "FULL_SCAN_TABLE", 9007199254740993L,
                                9007199254740993L, 2, 2, 0L, false, false, false);
                        String transferJson = mapper.writeValueAsString(transfer);
                        assertTrue(transferJson.contains("\"acknowledgedRows\":\"9007199254740993\""));
                        assertEquals(transfer, mapper.readValue(transferJson, SyncTableTransferDTO.class));
                        var legacyMapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        assertEquals(transfer, legacyMapper.readValue(transferJson, SyncTableTransferDTO.class));
                        assertEquals(transfer, mapper.readValue(legacyMapper.writeValueAsString(transfer), SyncTableTransferDTO.class));
                    });
        } finally { JsonUtils.init(previous); }
    }
    @Test void scopedClientCanStartWithoutTlsButCannotSend() {
        new ApplicationContextRunner().withUserConfiguration(SyncBatchEvidenceClientConfiguration.class)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertSame(feign.Retryer.NEVER_RETRY, ctx.getBean(feign.Retryer.class));
                    assertFalse(ctx.getBean(feign.Request.Options.class).isFollowRedirects());
                    assertThrows(IllegalStateException.class, () -> ctx.getBean(feign.Client.class).execute(
                            feign.Request.create(feign.Request.HttpMethod.POST, "https://localhost:1/test",
                                    java.util.Map.of(), null, java.nio.charset.StandardCharsets.UTF_8), new feign.Request.Options()));
                });
    }
}
