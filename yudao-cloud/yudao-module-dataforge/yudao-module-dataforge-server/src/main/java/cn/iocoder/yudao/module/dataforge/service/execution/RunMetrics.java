package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.execution.RunMetricsSnapshot;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.execution.RunStopTiming;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;

/**
 * 复用 monitor starter 的注册表。事件是进程级观测，不代替持久审计；
 * 数据库快照是集群总量，多节点按 max 聚合，不按 sum 相加。
 */
@Component
@Slf4j
public class RunMetrics {

    public enum Domain {ETL, ACCESS}

    public enum Phase {RECOVER, RECONCILE, SCHEDULED, DISPATCH}

    public enum Event {
        CLAIMED("claimed"), CAPACITY_REJECTED("capacity.rejected"), RECOVERED("recovered"),
        RECONCILE_FAILED("reconcile.failures"), RESOLVED("resolved");

        private final String name;

        /**
         * 创建事件实例。
         *
         * @param name 名称
         */
        Event(String name) {
            this.name = name;
        }
    }

    private record Observation(RunMetricsSnapshot snapshot, long sampledAt, boolean available) {

    }

    private static final class Meters {

        private volatile Observation observation = new Observation(null, 0, false);

        private final Map<Event, Counter> events = new EnumMap<>(Event.class);

        private final Map<Phase, Counter> scanFailures = new EnumMap<>(Phase.class);

        private Timer stopConfirmation;

        private Counter refreshFailures;
    }

    private final MeterRegistry registry;

    private final Map<Domain, Meters> domains = new EnumMap<>(Domain.class);

    /**
     * 创建运行指标实例。
     *
     * @param registry 注册表
     */
    public RunMetrics(MeterRegistry registry) {
        this(registry, new RunExecutionProperties());
    }

    /**
     * 创建运行指标实例。
     *
     * @param registry 注册表
     * @param properties 组件配置
     */
    @Autowired
    public RunMetrics(MeterRegistry registry, RunExecutionProperties properties) {
        this.registry = registry;
        // ETL/ACCESS 共享预算，不按 domain 重复暴露；用于发现节点配置漂移，绝不是可用槽位数。
        Gauge.builder("dataforge.run.capacity.limit", properties, RunExecutionProperties::getPlatformActive)
                .tag("scope", "platform").strongReference(true).register(registry);
        Gauge.builder("dataforge.run.capacity.limit", properties, RunExecutionProperties::getPerDataSourceActive)
                .tag("scope", "datasource").strongReference(true).register(registry);
        for (Domain domain : Domain.values()) {
            Meters meters = new Meters();
            domains.put(domain, meters);
            for (Event event : Event.values()) {
                meters.events.put(event, Counter.builder("dataforge.run." + event.name)
                        .tag("domain", domain.name()).register(registry));
            }
            for (Phase phase : Phase.values()) {
                meters.scanFailures.put(phase, Counter.builder("dataforge.run.scan.failures")
                        .tags("domain", domain.name(), "phase", phase.name()).register(registry));
            }
            meters.stopConfirmation = Timer.builder("dataforge.run.stop.confirmation")
                    .description("Observed cancellation request to confirmed exit; not a durable audit")
                    .tag("domain", domain.name()).register(registry);
            meters.refreshFailures = Counter.builder("dataforge.run.metrics.failures")
                    .tag("domain", domain.name()).register(registry);
            gauge(domain, meters, "queued", RunMetricsSnapshot::getQueued);
            gauge(domain, meters, "queue.oldest.age", RunMetricsSnapshot::getQueueOldestAge);
            gauge(domain, meters, "slots", RunMetricsSnapshot::getSlots);
            gauge(domain, meters, "unknown", RunMetricsSnapshot::getUnknown);
            gauge(domain, meters, "unknown.oldest.run.age", RunMetricsSnapshot::getUnknownOldestRunAge);
            gauge(domain, meters, "review.required", RunMetricsSnapshot::getReview);
            gauge(domain, meters, "stop.pending", RunMetricsSnapshot::getStopPending);
            gauge(domain, meters, "stop.oldest.age", RunMetricsSnapshot::getStopOldestAge);
            gauge(domain, meters, "lease.expired", RunMetricsSnapshot::getExpired);
            gauge(domain, meters, "quality.pending", RunMetricsSnapshot::getQualityPending);
            gauge(domain, meters, "schedule.due", RunMetricsSnapshot::getDue);
            gauge(domain, meters, "schedule.overdue.age", RunMetricsSnapshot::getOverdueAge);
            Gauge.builder("dataforge.run.metrics.available", meters, m -> m.observation.available ? 1 : 0)
                    .tag("domain", domain.name()).register(registry);
            Gauge.builder("dataforge.run.metrics.age", meters, m -> {
                Observation observation = m.observation;
                return observation.snapshot == null ? Double.NaN
                        : Math.max(0, (registry.config().clock().monotonicTime() - observation.sampledAt) / 1e9);
            }).baseUnit("seconds").tag("domain", domain.name()).register(registry);
        }
    }

    /**
     * 注册按业务域区分的运行瞬时指标，从当前观测快照读取指标值。
     *
     * @param domain 域
     * @param meters 度量
     * @param name 名称
     * @param read 读取
     */
    private void gauge(Domain domain, Meters meters, String name, ToDoubleFunction<RunMetricsSnapshot> read) {
        var gauge = Gauge.builder("dataforge.run." + name, meters, m -> {
            RunMetricsSnapshot snapshot = m.observation.snapshot;
            return snapshot == null ? Double.NaN : read.applyAsDouble(snapshot);
        }).tag("domain", domain.name());
        if (name.endsWith(".age")) {
            gauge.baseUnit("seconds");
        }
        gauge.register(registry);
    }

    /** 注册后只读取内存快照，Prometheus scrape 不执行数据库查询。 */
    public void snapshot(Domain domain, RunMetricsSnapshot snapshot) {
        java.util.Objects.requireNonNull(snapshot);
        domains.get(domain).observation = new Observation(snapshot, registry.config().clock().monotonicTime(), true);
    }

    /**
     * 刷新失败。
     *
     * @param domain 域
     */
    public void refreshFailed(Domain domain) {
        Meters meters = domains.get(domain);
        Observation previous = meters.observation;
        meters.observation = new Observation(previous.snapshot, previous.sampledAt, false);
        safely(() -> meters.refreshFailures.increment());
    }

    /**
     * 在本地事务成功提交后累加指定业务域的事件计数。
     *
     * @param domain 域
     * @param event 事件
     */
    public void event(Domain domain, Event event) {
        afterCommit(() -> domains.get(domain).events.get(event).increment());
    }

    /**
     * 指标专用读取只能在提交后执行，异常不得回滚已经确认的业务终态。
     *
     * @param domain 域
     * @param requested 已请求
     * @param confirmed 已确认
     */
    public void stopConfirmed(Domain domain, LocalDateTime requested, LocalDateTime confirmed) {
        if (requested == null || confirmed == null || confirmed.isBefore(requested)) {
            return;
        }
        Duration latency = Duration.between(requested, confirmed);
        afterCommit(() -> domains.get(domain).stopConfirmation.record(latency.toNanos(), TimeUnit.NANOSECONDS));
    }

    /** 指标专用读取只能在提交后执行，异常不得回滚已经确认的业务终态。 */
    public void stopConfirmed(Domain domain, java.util.function.Supplier<RunStopTiming> readCommittedTiming) {
        afterCommit(() -> {
            RunStopTiming timing = readCommittedTiming.get();
            if (timing == null || timing.requested() == null || timing.confirmed() == null
                    || timing.confirmed().isBefore(timing.requested())) {
                return;
            }
            domains.get(domain).stopConfirmation.record(Duration.between(timing.requested(), timing.confirmed()));
        });
    }

    /** 扫描失败不是成功事务事件；计数单位为该阶段捕获的候选/阶段异常。 */
    public void scanFailed(Domain domain, Phase phase, int failures) {
        if (failures > 0) {
            safely(() -> domains.get(domain).scanFailures.get(phase).increment(failures));
        }
    }

    /**
     * 在本地事务成功提交后触发对应的后续处理。
     *
     * @param observation 观测
     */
    private void afterCommit(Runnable observation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            safely(observation);
        } else if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /**
                 * 在本地事务成功提交后触发对应的后续处理。
                 */
                @Override
                public void afterCommit() {
                    safely(observation);
                }
            });
        }
        // 活跃但不支持同步的事务不提前计成功；持久快照仍能反映实际结果。
    }

    /**
     * 隔离指标观测异常，避免观测失败影响业务处理结果。
     *
     * @param observation 观测
     */
    private void safely(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException failure) {
            log.warn("运行指标记录失败 causeType={}", failure.getClass().getSimpleName());
        }
    }
}
