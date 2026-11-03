package cn.iocoder.yudao.module.governance.service.quality;
import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityRunReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlBatchRunReqDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.etl.InlineQualityReportDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityEtlBatchRunDO;
import cn.iocoder.yudao.module.governance.dal.mysql.etl.InlineQualityReportMapper;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.isNull;
class QualityRunServiceTest {
    private final QualityRunService service=new QualityRunService();
    private final QualityTaskInstanceMapper instances=mock(QualityTaskInstanceMapper.class);
    private final QualityTaskExecutor executor=mock(QualityTaskExecutor.class);
    private final QualityRunAuthorizationService auth=mock(QualityRunAuthorizationService.class);
    private final QualityTaskMapper tasks=mock(QualityTaskMapper.class);
    private final QualityEtlBatchRunMapper etlRuns=mock(QualityEtlBatchRunMapper.class);
    private final InlineQualityReportMapper reports=mock(InlineQualityReportMapper.class);
    private final cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver databaseResolver=mock(cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver.class);
    private final QualityTaskObjectMapper taskObjects=mock(QualityTaskObjectMapper.class);
    private final QualityCheckObjectMapper checkObjects=mock(QualityCheckObjectMapper.class);
    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(),"quality-test"),QualityTaskInstanceDO.class);
        ReflectionTestUtils.setField(service,"instanceMapper",instances);
        ReflectionTestUtils.setField(service,"executor",executor);
        ReflectionTestUtils.setField(service,"authorization",auth);
        ReflectionTestUtils.setField(service,"taskMapper",tasks);
        ReflectionTestUtils.setField(service,"inlineQualityReportMapper",reports);
        ReflectionTestUtils.setField(service,"etlBatchRunMapper",etlRuns);
        ReflectionTestUtils.setField(service,"databaseResolver",databaseResolver);
        ReflectionTestUtils.setField(service,"taskObjectMapper",taskObjects);
        ReflectionTestUtils.setField(service,"checkObjectMapper",checkObjects);
    }
    @AfterEach void clearTransaction() { TransactionSynchronizationManager.clear(); }
    private QualityRunReqDTO request() {
        var req=new QualityRunReqDTO(); req.setRequestKey("dataforge:run1");
        req.setQualityTaskId(10L); req.setExecutionUserId(20L); return req;
    }
    private void existing(String status) {
        when(instances.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(QualityTaskInstanceDO.builder().id(30L).taskId(10L).executionUserId(20L)
                        .instanceStatus(status).build());
    }
    @Test void retryReturnsExactHistoricalInstanceWithoutRerunningOrRevalidatingDefinition() {
        existing("SUCCESS");
        assertEquals(30L,service.submit(request()));
        verifyNoInteractions(auth,executor);
        verify(instances,never()).insert(any(QualityTaskInstanceDO.class));
    }
    @Test void sameKeyCannotBeReusedForAnotherTask() {
        existing("SUCCESS"); var req=request();req.setQualityTaskId(11L);
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->service.submit(req));
        verifyNoInteractions(executor);
    }
    @Test void queuedRetryResubmitsOnlyTheOriginalInstance() {
        existing("QUEUED");
        assertEquals(30L,service.submit(request()));
        verify(executor).dispatchGate(30L);
        verify(instances,never()).insert(any(QualityTaskInstanceDO.class));
    }
    @Test void t31ExpiredRunningIsUnknownAndQueuedIsRedispatched() {
        service.recover();
        verify(instances).update(isNull(), any());
        verify(instances).selectList(any());
    }

    @Test void exactResultCannotBeReadWithAnotherExecutionIdentity() {
        when(instances.selectById(30L)).thenReturn(QualityTaskInstanceDO.builder().id(30L).executionUserId(20L).build());
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,()->service.result(30L,21L));
    }

    @Test void workOrderRunDispatchesOnlyAfterCommit() {
        prepareWorkOrderRun();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertEquals(30L, service.submitIdempotent(10L, "attempt:1", 20L, "WORK_ORDER_RECHECK"));
        verify(executor, never()).dispatchGate(any());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(executor).dispatchGate(30L);
    }

    @Test void rolledBackAttemptNeverDispatches() {
        prepareWorkOrderRun();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        service.submitIdempotent(10L, "attempt:1", 20L, "WORK_ORDER_RECHECK");
        TransactionSynchronizationManager.getSynchronizations().forEach(
                sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(executor, never()).dispatchGate(any());
    }

    @Test void workOrderRunKeyAlsoBindsActorAndRunType() {
        when(instances.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(QualityTaskInstanceDO.builder().id(30L).taskId(10L).executionUserId(20L)
                        .runType("WORK_ORDER_RECHECK").instanceStatus("SUCCESS").build());
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.submitIdempotent(10L, "attempt:1", 21L, "WORK_ORDER_RECHECK"));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.submitIdempotent(10L, "attempt:1", 20L, "MANUAL"));
        assertEquals(30L, service.submitIdempotent(10L, "attempt:1", 20L, "WORK_ORDER_RECHECK"));
        verifyNoInteractions(executor);
    }

    @Test void etlBatchAdmissionMustEqualInlineReportPassPlusAuthorizedWarnings() {
        when(auth.require(10L, 20L)).thenReturn(QualityTaskDO.builder().id(10L).taskName("batch").build());
        when(reports.selectById(40L)).thenReturn(InlineQualityReportDO.builder().id(40L).sourceType("ETL_INLINE")
                .executionUserId(20L).runId("run-1").nodeId("quality-1").batchId("batch-1")
                .totalCount(10L).passCount(8L).warnCount(1L).rejectCount(1L).build());
        QualityEtlBatchRunReqDTO request = new QualityEtlBatchRunReqDTO();
        request.setQualityTaskId(10L); request.setExecutionUserId(20L); request.setInlineReportId(40L);
        request.setQualityNodeId("quality-1"); request.setQualityBatchId("batch-1");
        var manifest = new QualityEtlBatchRunReqDTO.Manifest();
        manifest.setSchemaVersion(1); manifest.setTargetBatchId(50L); manifest.setRunId("run-1");
        manifest.setRecordId(70L);
        manifest.setNodeId("sink-1"); manifest.setDatabaseId(60L); manifest.setSchemaName("public");
        manifest.setTableName("target"); manifest.setBatchColumn("_etl_batch_key"); manifest.setBatchValue("a".repeat(64));
        manifest.setRowColumn("_etl_row_no"); manifest.setStatusColumn("_quality_status");
        manifest.setPayloadHash("b".repeat(64)); manifest.setRowCount(10L); manifest.setAdmittedRowCount(8L);
        manifest.setAllowWarnings(true); manifest.setIdentityFields(java.util.List.of("id"));
        manifest.setIdentityHash("c".repeat(64)); request.setManifest(manifest);

        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> service.submitEtlBatch(request));
        verify(instances, never()).insert(any(QualityTaskInstanceDO.class));
    }

    @Test void etlBatchUnknownOutcomeRetryReturnsTheExistingInstanceWithoutResolvingTargetAgain() {
        when(auth.require(10L, 20L)).thenReturn(QualityTaskDO.builder().id(10L).taskName("batch").build());
        QualityEtlBatchRunReqDTO request = validEtlBatchRequest();
        when(reports.selectById(40L)).thenReturn(InlineQualityReportDO.builder().id(40L).sourceType("ETL_INLINE")
                .executionUserId(20L).runId("run-1").nodeId("quality-1").batchId("batch-1")
                .totalCount(10L).passCount(8L).warnCount(1L).rejectCount(1L)
                .identityFieldsJson("[\"id\"]").identityHash("c".repeat(64)).build());
        String manifestJson = ReflectionTestUtils.invokeMethod(service, "canonicalManifest", request);
        String manifestHash = cn.hutool.crypto.digest.DigestUtil.sha256Hex(manifestJson);
        when(etlRuns.selectByTaskAndTargetBatch(10L, 50L)).thenReturn(QualityEtlBatchRunDO.builder()
                .qualityTaskId(10L).inlineReportId(40L).manifestHash(manifestHash).instanceId(30L).build());
        when(instances.selectById(30L)).thenReturn(QualityTaskInstanceDO.builder().id(30L).taskId(10L)
                .executionUserId(20L).instanceStatus("SUCCESS").build());

        assertEquals(30L, service.submitEtlBatch(request));
        verifyNoInteractions(databaseResolver, taskObjects, checkObjects);
    }

    @Test void etlBatchSameTargetWithDifferentFrozenManifestConflicts() {
        when(auth.require(10L, 20L)).thenReturn(QualityTaskDO.builder().id(10L).taskName("batch").build());
        QualityEtlBatchRunReqDTO request = validEtlBatchRequest();
        when(reports.selectById(40L)).thenReturn(InlineQualityReportDO.builder().id(40L).sourceType("ETL_INLINE")
                .executionUserId(20L).runId("run-1").nodeId("quality-1").batchId("batch-1")
                .totalCount(10L).passCount(8L).warnCount(1L).rejectCount(1L)
                .identityFieldsJson("[\"id\"]").identityHash("c".repeat(64)).build());
        String originalHash = DigestUtil.sha256Hex((String) ReflectionTestUtils.invokeMethod(service,
                "canonicalManifest", request));
        when(etlRuns.selectByTaskAndTargetBatch(10L, 50L)).thenReturn(QualityEtlBatchRunDO.builder()
                .qualityTaskId(10L).targetBatchId(50L).inlineReportId(40L).manifestHash(originalHash).instanceId(30L).build());
        request.getManifest().setBatchValue("d".repeat(64));

        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.submitEtlBatch(request));
        verifyNoInteractions(databaseResolver, taskObjects, checkObjects, executor);
    }

    @Test void etlBatchUsesOnlyTheTaskBoundExactDatabaseAndTable() {
        when(auth.require(10L, 20L)).thenReturn(QualityTaskDO.builder().id(10L).taskName("batch").build());
        when(reports.selectById(40L)).thenReturn(InlineQualityReportDO.builder().id(40L).sourceType("ETL_INLINE")
                .executionUserId(20L).runId("run-1").nodeId("quality-1").batchId("batch-1")
                .totalCount(10L).passCount(8L).warnCount(1L).rejectCount(1L)
                .identityFieldsJson("[\"id\"]").identityHash("c".repeat(64)).build());
        when(etlRuns.selectByTaskAndTargetBatch(10L, 50L)).thenReturn(null);
        when(taskObjects.selectListByTaskId(10L)).thenReturn(java.util.List.of(
                QualityTaskObjectDO.builder().objectId(1L).build(), QualityTaskObjectDO.builder().objectId(2L).build(),
                QualityTaskObjectDO.builder().objectId(3L).build()));
        QualityCheckObjectDO wrongDatabase = QualityCheckObjectDO.builder().id(1L).datasourceConnId(61L)
                .tableName("target").build();
        QualityCheckObjectDO wrongTable = QualityCheckObjectDO.builder().id(2L).datasourceConnId(60L)
                .tableName("another_table").build();
        QualityCheckObjectDO exact = QualityCheckObjectDO.builder().id(3L).datasourceConnId(60L)
                .tableName("target").build();
        when(checkObjects.selectBatchIds(java.util.List.of(1L, 2L, 3L)))
                .thenReturn(java.util.List.of(wrongDatabase, wrongTable, exact));
        var route = new cn.iocoder.yudao.module.governance.service.master.MasterDatabaseResolver.ConnectionInfo();
        route.setDatabaseId(60L); route.setSchema("public");
        when(databaseResolver.resolve(60L, cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_CHECK_OBJECT_NOT_EXISTS))
                .thenReturn(route);
        when(executor.snapshotEtlBatchDefinition(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.same(exact),
                org.mockito.ArgumentMatchers.eq("_etl_batch_key"), org.mockito.ArgumentMatchers.eq("a".repeat(64)),
                org.mockito.ArgumentMatchers.eq("_quality_status"), org.mockito.ArgumentMatchers.eq(java.util.List.of("PASS", "WARN")),
                org.mockito.ArgumentMatchers.eq(9L))).thenReturn("frozen-definition");
        when(instances.insert(any(QualityTaskInstanceDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, QualityTaskInstanceDO.class).setId(901L); return 1;
        });
        when(etlRuns.insert(any(QualityEtlBatchRunDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, QualityEtlBatchRunDO.class).setId(902L); return 1;
        });

        assertEquals(901L, service.submitEtlBatch(validEtlBatchRequest()));
        verify(executor).snapshotEtlBatchDefinition(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.same(exact), org.mockito.ArgumentMatchers.eq("_etl_batch_key"),
                org.mockito.ArgumentMatchers.eq("a".repeat(64)), org.mockito.ArgumentMatchers.eq("_quality_status"),
                org.mockito.ArgumentMatchers.eq(java.util.List.of("PASS", "WARN")), org.mockito.ArgumentMatchers.eq(9L));
        verify(executor).dispatchGate(901L);
    }

    private QualityEtlBatchRunReqDTO validEtlBatchRequest() {
        var request = new QualityEtlBatchRunReqDTO();
        request.setQualityTaskId(10L); request.setExecutionUserId(20L); request.setInlineReportId(40L);
        request.setQualityNodeId("quality-1"); request.setQualityBatchId("batch-1");
        var manifest = new QualityEtlBatchRunReqDTO.Manifest();
        manifest.setSchemaVersion(1); manifest.setTargetBatchId(50L); manifest.setRunId("run-1");
        manifest.setRecordId(70L);
        manifest.setNodeId("sink-1"); manifest.setDatabaseId(60L); manifest.setSchemaName("public");
        manifest.setTableName("target"); manifest.setBatchColumn("_etl_batch_key"); manifest.setBatchValue("a".repeat(64));
        manifest.setRowColumn("_etl_row_no"); manifest.setStatusColumn("_quality_status");
        manifest.setPayloadHash("b".repeat(64)); manifest.setRowCount(10L); manifest.setAdmittedRowCount(9L);
        manifest.setAllowWarnings(true); manifest.setIdentityFields(java.util.List.of("id"));
        manifest.setIdentityHash("c".repeat(64)); request.setManifest(manifest);
        return request;
    }

    private void prepareWorkOrderRun() {
        var task = QualityTaskDO.builder().id(10L).taskName("复检").build();
        when(tasks.selectById(10L)).thenReturn(task);
        when(executor.snapshotTaskDefinition(task)).thenReturn("{}");
        doAnswer(call -> { call.getArgument(0, QualityTaskInstanceDO.class).setId(30L); return 1; })
                .when(instances).insert(any(QualityTaskInstanceDO.class));
    }
}
