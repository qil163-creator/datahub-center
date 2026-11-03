package cn.iocoder.yudao.framework.dbswitch.config;

import cn.iocoder.yudao.framework.dbswitch.data.query.QueryMetrics;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** 可选指标导出；data 模块不保留 Micrometer 或 actuator 依赖。 */
@AutoConfiguration(after = YudaoDbSwitchAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnBean(DbSwitchRuntime.class)
public class DbSwitchQueryMetricsAutoConfiguration {

    /**
     * 创建数据库查询指标采集组件。
     */
    @Bean
    public MeterBinder dbSwitchQueryMetrics(DbSwitchRuntime runtime) {
        var queries = runtime.queries();
        return registry -> {
            Gauge.builder("dbswitch.query.active", queries, value -> value.activeOperations()).register(registry);
            FunctionCounter.builder("dbswitch.query.admission.rejected", queries.metrics(),
                    value -> value.rejectedAdmissions()).register(registry);
            for (QueryMetrics.Phase phase : QueryMetrics.Phase.values()) {
                for (QueryMetrics.Outcome outcome : QueryMetrics.Outcome.values()) {
                    FunctionTimer.builder("dbswitch.query.phase", queries.metrics(),
                                    value -> value.snapshot(phase, outcome).count(),
                                    value -> value.snapshot(phase, outcome).nanos(), TimeUnit.NANOSECONDS)
                            .tag("phase", phase.name().toLowerCase(Locale.ROOT))
                            .tag("outcome", outcome.name().toLowerCase(Locale.ROOT)).register(registry);
                }
            }
        };
    }
}
