package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.execution.RunMetricsSnapshot;
import cn.iocoder.yudao.module.dataforge.dal.mysql.execution.RunMetricsMapper;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunMetricsSchedulingTest {
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {
        @Bean("businessLeaseScheduler")
        ThreadPoolTaskScheduler businessLeaseScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(2);
            return scheduler;
        }
    }

    private AnnotationConfigApplicationContext context(RunMetricsMapper mapper) {
        var app = new AnnotationConfigApplicationContext();
        app.register(Scheduling.class, RunExecutionConfiguration.class,
                RunMetrics.class, RunMetricsCollector.class);
        app.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
        app.registerBean(RunMetricsMapper.class, () -> mapper);
        app.refresh();
        return app;
    }

    @Test void metricsRefreshDoesNotRegisterOnBusinessLeaseScheduler() {
        try (var app = context(mock(RunMetricsMapper.class))) {
            var lease = app.getBean("businessLeaseScheduler", ThreadPoolTaskScheduler.class);
            assertEquals(0, lease.getScheduledThreadPoolExecutor().getQueue().size(),
                    "metrics must not occupy the business lease scheduler");
        }
    }

    @Test void blockedAcquisitionCannotBlockSchedulerOrOtherDomain() throws Exception {
        var mapper = mock(RunMetricsMapper.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(mapper.selectEtl()).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return RunMetricsSnapshot.builder().queued(2).build();
        });
        when(mapper.selectAccess()).thenReturn(RunMetricsSnapshot.builder().queued(3).build());
        try (var caller = Executors.newSingleThreadExecutor(); var app = context(mapper)) {
            try {
                var future = caller.submit(app.getBean(RunMetricsCollector.class)::refresh);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertDoesNotThrow(() -> future.get(1, TimeUnit.SECONDS),
                        "scheduler cannot wait for a database connection");
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2)).untilAsserted(() ->
                        assertEquals(3, app.getBean(MeterRegistry.class).get("dataforge.run.queued")
                                .tag("domain", "ACCESS").gauge().value()));
            } finally {
                release.countDown();
            }
        }
    }
}

