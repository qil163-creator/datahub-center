package cn.iocoder.yudao.module.dataforge.job.etl;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlPipelineDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlPipelineMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlRecordMapper;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineExecutor;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineService;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlRunCoordinator;
import cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt;
import com.xxl.job.core.context.XxlJobHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlScanFailureTest {
    private final EtlPipelineMapper pipelines = mock(EtlPipelineMapper.class);
    private final EtlPipelineService service = mock(EtlPipelineService.class);
    private final EtlPipelineExecutor worker = mock(EtlPipelineExecutor.class);
    private final EtlPipelineJob job = new EtlPipelineJob();
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry metricsRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private void configureJob() {
        ReflectionTestUtils.setField(job, "pipelineMapper", pipelines);
        ReflectionTestUtils.setField(job, "pipelineService", service);
        ReflectionTestUtils.setField(job, "executor", worker);
        ReflectionTestUtils.setField(job, "executionProperties", new RunExecutionProperties());
        ReflectionTestUtils.setField(job, "metrics",
                new cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics(metricsRegistry));
        when(pipelines.selectDueList(anyInt(), anyInt())).thenReturn(List.of());
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
        metricsRegistry.close();
    }

    @Test
    void failedAcceptanceContinuesOtherTasksButReportsFailedJob() {
        configureJob();
        var first = due(10L);
        var second = due(20L);
        when(pipelines.selectDueList(anyInt(), anyInt())).thenReturn(List.of(first, second));
        when(service.runPipelineScheduled(eq(10L), anyLong(), any())).thenThrow(new IllegalStateException("test"));
        when(service.runPipelineScheduled(eq(20L), anyLong(), any())).thenAnswer(invocation -> {
            return new RunReceipt(200L, false, 1, false);
        });
        TenantContextHolder.setTenantId(77L);
        TenantContextHolder.setIgnore(true);
        try (var xxl = mockStatic(XxlJobHelper.class)) {
            job.execute();
            verify(service).runPipelineScheduled(20L, 1L, second.getNextRunTime());
            verify(worker).dispatchWaiting();
            xxl.verify(() -> XxlJobHelper.handleFail(contains("1")), times(1));
            xxl.verify(() -> XxlJobHelper.handleSuccess(anyString()), never());
        }
        assertEquals(77L, TenantContextHolder.getRequiredTenantId());
        assertTrue(TenantContextHolder.isIgnore());
        assertEquals(1, metricsRegistry.get("dataforge.run.scan.failures")
                .tags("domain", "ETL", "phase", "SCHEDULED").counter().count());
    }

    @Test
    void failedRecoveryStillAttemptsIndependentPhasesAndReportsFailure() {
        configureJob();
        doThrow(new IllegalStateException("test")).when(worker).recoverExpired();
        try (var xxl = mockStatic(XxlJobHelper.class)) {
            assertDoesNotThrow(job::execute);
            verify(worker).reconcileQuality();
            verify(worker).dispatchWaiting();
            verify(pipelines).selectDueList(anyInt(), anyInt());
            xxl.verify(() -> XxlJobHelper.handleFail(anyString()), times(1));
            xxl.verify(() -> XxlJobHelper.handleSuccess(anyString()), never());
        }
        assertEquals(1, metricsRegistry.get("dataforge.run.scan.failures")
                .tags("domain", "ETL", "phase", "RECOVER").counter().count());
    }

    @Test
    void successfulPhasesKeepSuccessfulJobContract() {
        configureJob();
        try (var xxl = mockStatic(XxlJobHelper.class)) {
            job.execute();
            xxl.verify(() -> XxlJobHelper.handleSuccess(anyString()), times(1));
            xxl.verify(() -> XxlJobHelper.handleFail(anyString()), never());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"recover", "dispatch", "quality"})
    void recordFailureIsReportedAfterOtherTasksAreProcessed(String phase) {
        var executor = spy(new EtlPipelineExecutor());
        var records = mock(EtlRecordMapper.class);
        var coordinator = mock(EtlRunCoordinator.class);
        ReflectionTestUtils.setField(executor, "recordMapper", records);
        ReflectionTestUtils.setField(executor, "runCoordinator", coordinator);
        ReflectionTestUtils.setField(executor, "qualityEvidenceService", mock(
                cn.iocoder.yudao.module.dataforge.service.etl.EtlQualityEvidenceService.class));
        ReflectionTestUtils.setField(executor, "targetBatchService", mock(
                cn.iocoder.yudao.module.dataforge.service.etl.EtlTargetBatchService.class));
        ReflectionTestUtils.setField(executor, "replayService", mock(
                cn.iocoder.yudao.module.dataforge.service.etl.EtlReplayService.class));

        ReflectionTestUtils.setField(executor, "operationsMetrics", mock(
                cn.iocoder.yudao.module.dataforge.service.etl.EtlOperationsMetrics.class));
        ReflectionTestUtils.setField(executor, "executionProperties", new RunExecutionProperties());
        var first = record(10L);
        var second = record(20L);
        Runnable scan;
        switch (phase) {
            case "recover" -> {
                when(records.selectExpiredCandidates(anyInt())).thenReturn(List.of(first, second));
                doThrow(new IllegalStateException("test")).when(coordinator).recoverExpired(10L);
                scan = executor::recoverExpired;
            }
            case "dispatch" -> {
                when(records.selectDispatchCandidates(anyInt(), anyInt(), anyInt())).thenReturn(List.of(first, second));
                doThrow(new IllegalStateException("test")).when(executor).submit(10L);
                doAnswer(invocation -> { return null; })
                        .when(executor).submit(20L);
                scan = executor::dispatchWaiting;
            }
            default -> {
                when(records.selectQualityCandidates(anyInt(), anyInt())).thenReturn(List.of(first, second));
                when(coordinator.claimQuality(10L)).thenThrow(new IllegalStateException("test"));
                when(coordinator.claimQuality(20L)).thenAnswer(invocation -> {
                    return Optional.empty();
                });
                scan = executor::reconcileQuality;
            }
        }
        TenantContextHolder.setTenantId(77L);
        TenantContextHolder.setIgnore(true);
        assertThrows(IllegalStateException.class, scan::run);
        switch (phase) {
            case "recover" -> verify(coordinator).recoverExpired(20L);
            case "dispatch" -> verify(executor).submit(20L);
            default -> verify(coordinator).claimQuality(20L);
        }
        assertEquals(77L, TenantContextHolder.getRequiredTenantId());
        assertTrue(TenantContextHolder.isIgnore());
        executor.destroy();
    }

    private static EtlPipelineDO due(long id) {
        var pipeline = EtlPipelineDO.builder().id(id).scheduleGeneration(1L)
                .nextRunTime(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
        return pipeline;
    }

    private static EtlRecordDO record(long id) {
        var record = EtlRecordDO.builder().id(id).build();
        return record;
    }
}
