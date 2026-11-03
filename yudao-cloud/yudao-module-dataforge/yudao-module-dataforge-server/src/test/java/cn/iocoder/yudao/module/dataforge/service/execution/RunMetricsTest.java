package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.execution.RunMetricsSnapshot;
import cn.iocoder.yudao.module.dataforge.dal.mysql.execution.RunMetricsMapper;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;

import static cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Domain.*;
import static cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics.Event.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Missing commit guards, reset-to-zero on failed refresh, or ID tags must break these tests. */
class RunMetricsTest {
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);

    @AfterEach
    void cleanup() {
        TenantContextHolder.clear();
        TransactionSynchronizationManager.clear();
        registry.close();
    }

    @Test
    void eventsBecomeVisibleOnlyAfterCommitAndRollbackPublishesNothing() {
        var metrics = new RunMetrics(registry);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        metrics.event(ETL, CLAIMED);
        assertEquals(0, count("dataforge.run.claimed", "ETL"));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clearSynchronization();
        assertEquals(0, count("dataforge.run.claimed", "ETL"));
        TransactionSynchronizationManager.initSynchronization();
        metrics.event(ETL, CLAIMED);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertEquals(1, count("dataforge.run.claimed", "ETL"));
        assertEquals(0, count("dataforge.run.claimed", "ACCESS"));
    }

    @Test
    void confirmedStopLatencyUsesPersistedTimesAndNeverRecordsUnknownEvidence() {
        var metrics = new RunMetrics(registry);
        LocalDateTime request = LocalDateTime.of(2026, 9, 20, 10, 0);
        metrics.stopConfirmed(ACCESS, request, request.plusSeconds(7));
        metrics.stopConfirmed(ACCESS, null, request);
        metrics.stopConfirmed(ACCESS, request, request.minusSeconds(1));
        var timer = registry.get("dataforge.run.stop.confirmation").tag("domain", "ACCESS").timer();
        assertEquals(1, timer.count());
        assertEquals(7, timer.totalTime(java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void missingSnapshotIsNotReportedAsZeroAndScrapeDoesNotQueryDatabase() {
        var mapper = mock(RunMetricsMapper.class);
        var metrics = new RunMetrics(registry);
        new RunMetricsCollector(mapper, metrics, Runnable::run, mock(TaskScheduler.class));
        assertTrue(Double.isNaN(gauge("dataforge.run.queued", "ETL")));
        assertEquals(0, gauge("dataforge.run.metrics.available", "ETL"));
        verifyNoInteractions(mapper);
    }

    @Test
    void refreshIsGlobalAndRestoresContextEvenWhenOneDomainFails() {
        var mapper = mock(RunMetricsMapper.class);
        var metrics = new RunMetrics(registry);
        var collector = new RunMetricsCollector(mapper, metrics, Runnable::run, mock(TaskScheduler.class));
        TenantContextHolder.setTenantId(77L);
        TenantContextHolder.setIgnore(false);
        when(mapper.selectEtl()).thenAnswer(invocation -> {
            return snapshot(4, 90, 2);
        });
        when(mapper.selectAccess()).thenThrow(new IllegalStateException("synthetic private detail"));
        collector.refresh();
        assertEquals(4, gauge("dataforge.run.queued", "ETL"));
        assertEquals(90, gauge("dataforge.run.queue.oldest.age", "ETL"));
        assertEquals(2, gauge("dataforge.run.slots", "ETL"));
        assertEquals(77L, TenantContextHolder.getTenantId());
        assertFalse(TenantContextHolder.isIgnore());
        assertTrue(Double.isNaN(gauge("dataforge.run.queued", "ACCESS")));
        assertEquals(1, count("dataforge.run.metrics.failures", "ACCESS"));
    }

    @Test
    void failedRefreshPreservesLastSnapshotAndExposesItsAge() {
        var mapper = mock(RunMetricsMapper.class);
        var metrics = new RunMetrics(registry);
        var collector = new RunMetricsCollector(mapper, metrics, Runnable::run, mock(TaskScheduler.class));
        when(mapper.selectEtl()).thenReturn(snapshot(3, 10, 1));
        when(mapper.selectAccess()).thenReturn(snapshot(0, 0, 0));
        collector.refresh();
        clock.add(Duration.ofSeconds(40));
        when(mapper.selectEtl()).thenThrow(new IllegalStateException("offline"));
        collector.refresh();
        assertEquals(3, gauge("dataforge.run.queued", "ETL"));
        assertEquals(0, gauge("dataforge.run.metrics.available", "ETL"));
        assertEquals(40, gauge("dataforge.run.metrics.age", "ETL"));
        assertEquals(1, gauge("dataforge.run.metrics.available", "ACCESS"));
        assertEquals(0, gauge("dataforge.run.metrics.age", "ACCESS"));
        doReturn(snapshot(0, 0, 0)).when(mapper).selectEtl();
        collector.refresh();
        assertEquals(0, gauge("dataforge.run.queued", "ETL"));
        assertEquals(1, gauge("dataforge.run.metrics.available", "ETL"));
        assertEquals(0, gauge("dataforge.run.metrics.age", "ETL"));
    }

    @Test
    void sharedCapacityLimitsAreObservableWithoutTenantOrDatasourceLabels() {
        new RunMetrics(registry);
        assertEquals(32, registry.get("dataforge.run.capacity.limit").tag("scope", "platform").gauge().value());
        assertEquals(8, registry.get("dataforge.run.capacity.limit").tag("scope", "datasource").gauge().value());
    }

    @Test
    void onlyBoundedDomainAndPhaseTagsAreRegistered() {
        var metrics = new RunMetrics(registry);
        for (RunMetrics.Domain domain : RunMetrics.Domain.values()) {
            for (RunMetrics.Event event : RunMetrics.Event.values()) metrics.event(domain, event);
            for (RunMetrics.Phase phase : RunMetrics.Phase.values()) metrics.scanFailed(domain, phase, 1);
        }
        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag ->
                assertTrue(java.util.Set.of("domain", "phase", "scope").contains(tag.getKey()), tag.toString())));
        assertEquals(1, registry.get("dataforge.run.scan.failures")
                .tags("domain", "ETL", "phase", "SCHEDULED").counter().count());
    }

    @Test
    void timeoutDiscardsLateSampleWithoutDispatchingOverlappingPhysicalRead() {
        var mapper = mock(RunMetricsMapper.class);
        var metrics = new RunMetrics(registry);
        var reads = new ArrayList<Runnable>();
        var deadlines = new ArrayList<Runnable>();
        var scheduler = mock(TaskScheduler.class);
        when(scheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(call -> {
            deadlines.add(call.getArgument(0));
            return null;
        });
        when(mapper.selectEtl()).thenReturn(snapshot(99, 0, 0), snapshot(2, 0, 0));
        when(mapper.selectAccess()).thenReturn(snapshot(3, 0, 0));
        var collector = new RunMetricsCollector(mapper, metrics, reads::add, scheduler);
        metrics.snapshot(ETL, snapshot(1, 0, 0));
        collector.refresh();
        assertEquals(2, reads.size());
        deadlines.getFirst().run();
        deadlines.getFirst().run();
        assertEquals(1, count("dataforge.run.metrics.failures", "ETL"));
        assertEquals(0, gauge("dataforge.run.metrics.available", "ETL"));
        collector.refresh();
        assertEquals(2, reads.size(), "timeout does not mean the physical read exited");
        reads.get(0).run();
        reads.get(1).run();
        assertEquals(1, gauge("dataforge.run.queued", "ETL"), "late success cannot overwrite retained data");
        assertEquals(0, gauge("dataforge.run.metrics.available", "ETL"));
        assertEquals(1, gauge("dataforge.run.metrics.available", "ACCESS"));
        collector.refresh();
        assertEquals(4, reads.size());
        reads.get(2).run();
        deadlines.getFirst().run();
        assertEquals(2, gauge("dataforge.run.queued", "ETL"));
        assertEquals(1, gauge("dataforge.run.metrics.available", "ETL"));
        assertEquals(1, count("dataforge.run.metrics.failures", "ETL"));
    }

    @Test
    void rejectedWorkerDoesNotKeepDomainPermanentlyInFlight() {
        var mapper = mock(RunMetricsMapper.class);
        var metrics = new RunMetrics(registry);
        var collector = new RunMetricsCollector(mapper, metrics, action -> {
            throw new java.util.concurrent.RejectedExecutionException();
        }, mock(TaskScheduler.class));
        collector.refresh();
        collector.refresh();
        assertEquals(2, count("dataforge.run.metrics.failures", "ETL"));
        assertEquals(2, count("dataforge.run.metrics.failures", "ACCESS"));
        verifyNoInteractions(mapper);
    }

    private static RunMetricsSnapshot snapshot(long queue, double age, long slots) {
        return RunMetricsSnapshot.builder().queued(queue).queueOldestAge(age).slots(slots).build();
    }
    private double gauge(String name, String domain) { return registry.get(name).tag("domain", domain).gauge().value(); }
    private double count(String name, String domain) { return registry.get(name).tag("domain", domain).counter().count(); }
}
