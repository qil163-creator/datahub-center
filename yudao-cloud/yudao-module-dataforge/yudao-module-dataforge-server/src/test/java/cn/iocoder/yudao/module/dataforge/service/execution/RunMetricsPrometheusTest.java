package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.execution.RunMetricsSnapshot;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verify exported names and bound configuration, not just SimpleMeterRegistry names. */
class RunMetricsPrometheusTest {
    @Test
    void currentBootAutoConfigurationCreatesPrometheusRegistry() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                MetricsAutoConfiguration.class, PrometheusMetricsExportAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(PrometheusMeterRegistry.class));
    }

    @Test
    void exportsProductionMetricNamesAndKeepsStaleSamplesDistinctFromZero() {
        var clock = new MockClock();
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, new PrometheusRegistry(), clock);
        try {
            registry.config().commonTags("application", "dataforge-server");
            var metrics = new RunMetrics(registry);
            assertThat(registry.scrape()).contains("dataforge_run_queued{application=\"dataforge-server\",domain=\"ETL\"} NaN");
            metrics.snapshot(RunMetrics.Domain.ETL, RunMetricsSnapshot.builder().queued(12).queueOldestAge(600).build());
            metrics.event(RunMetrics.Domain.ETL, RunMetrics.Event.CAPACITY_REJECTED);
            clock.add(Duration.ofSeconds(120));
            metrics.refreshFailed(RunMetrics.Domain.ETL);
            assertThat(registry.scrape())
                    .contains("dataforge_run_queued{application=\"dataforge-server\",domain=\"ETL\"} 12.0")
                    .contains("dataforge_run_queue_oldest_age_seconds{application=\"dataforge-server\",domain=\"ETL\"} 600.0")
                    .contains("dataforge_run_metrics_age_seconds{application=\"dataforge-server\",domain=\"ETL\"} 120.0")
                    .contains("dataforge_run_metrics_available{application=\"dataforge-server\",domain=\"ETL\"} 0.0")
                    .contains("dataforge_run_capacity_rejected_total{application=\"dataforge-server\",domain=\"ETL\"} 1.0")
                    .doesNotContain("tenant_id", "datasource_id", "operation_id");
        } finally { registry.close(); }
    }

    @Test
    void capacityGaugesUseSpringBoundConfigurationInsteadOfDefaults() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "yudao.dataforge.execution.platform-active", 17,
                    "yudao.dataforge.execution.per-data-source-active", 3)));
            context.registerBean(MeterRegistry.class, () -> registry);
            context.register(RunExecutionConfiguration.class, RunMetrics.class);
            context.refresh();
            assertThat(registry.scrape())
                    .contains("dataforge_run_capacity_limit{scope=\"platform\"} 17.0")
                    .contains("dataforge_run_capacity_limit{scope=\"datasource\"} 3.0");
        } finally { registry.close(); }
    }
}
