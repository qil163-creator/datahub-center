package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.iocoder.yudao.module.dataforge.dal.mysql.execution.RunMetricsMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每节点内部只读维护。独立工作池最多两个请求，每域至多一个物理读取在途；
 * 六秒后样本失效，但在旧读取真正退出前不补派，不能靠取消 Future 假装资源退出。
 */
@Component
@Slf4j
public class RunMetricsCollector {

    private static final Duration SAMPLE_BUDGET = Duration.ofSeconds(6);

    private final RunMetricsMapper mapper;

    private final RunMetrics metrics;

    private final Executor worker;

    private final TaskScheduler scheduler;

    private final Map<RunMetrics.Domain, AtomicBoolean> inFlight = new EnumMap<>(RunMetrics.Domain.class);

    /**
     * 创建运行指标采集器实例。
     *
     * @param mapper 数据访问
     * @param metrics 指标
     * @param worker 执行工作线程
     * @param scheduler 调度器
     */
    public RunMetricsCollector(RunMetricsMapper mapper, RunMetrics metrics,
            @Qualifier("runMetricsWorker") Executor worker,
            @Qualifier("runMetricsScheduler") TaskScheduler scheduler) {
        this.mapper = mapper;
        this.metrics = metrics;
        this.worker = worker;
        this.scheduler = scheduler;
        for (RunMetrics.Domain domain : RunMetrics.Domain.values()) {
            inFlight.put(domain, new AtomicBoolean());
        }
    }

    /**
     * 刷新运行指标采集器。
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000, scheduler = "runMetricsScheduler")
    public void refresh() {
        for (RunMetrics.Domain domain : RunMetrics.Domain.values()) {
            AtomicBoolean busy = inFlight.get(domain);
            if (!busy.compareAndSet(false, true)) {
                continue;
            }
            AtomicBoolean decided = new AtomicBoolean();
            long deadline = System.nanoTime() + SAMPLE_BUDGET.toNanos();
            ScheduledFuture<?> timeout;
            try {
                timeout = scheduler.schedule(() -> failOnce(domain, decided, "sample_timeout"),
                        Instant.now().plus(SAMPLE_BUDGET));
            } catch (RuntimeException failure) {
                busy.set(false);
                failOnce(domain, decided, failure.getClass().getSimpleName());
                continue;
            }
            try {
                worker.execute(() -> {
                    try {
                        var snapshot = domain == RunMetrics.Domain.ETL
                                ? mapper.selectEtl() : mapper.selectAccess();
                        if (snapshot == null || System.nanoTime() > deadline) {
                            failOnce(domain, decided, "sample_expired");
                        } else if (decided.compareAndSet(false, true)) {
                            metrics.snapshot(domain, snapshot);
                        }
                    } catch (RuntimeException failure) {
                        failOnce(domain, decided, failure.getClass().getSimpleName());
                    } finally {
                        if (timeout != null) {
                            timeout.cancel(false);
                        }
                        busy.set(false);
                    }
                });
            } catch (RuntimeException failure) {
                if (timeout != null) {
                    timeout.cancel(false);
                }
                busy.set(false);
                failOnce(domain, decided, failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * 标记失败一次。
     *
     * @param domain 域
     * @param decided 已决定
     * @param causeType 异常原因类型
     */
    private void failOnce(RunMetrics.Domain domain, AtomicBoolean decided, String causeType) {
        if (decided.compareAndSet(false, true)) {
            metrics.refreshFailed(domain);
            log.warn("运行指标采集失败 domain={} causeType={}", domain, causeType);
        }
    }
}
