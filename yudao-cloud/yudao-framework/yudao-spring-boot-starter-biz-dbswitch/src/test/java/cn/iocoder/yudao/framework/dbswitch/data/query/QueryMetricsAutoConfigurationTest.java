package cn.iocoder.yudao.framework.dbswitch.data.query;

import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchQueryMetricsAutoConfiguration;
import cn.iocoder.yudao.framework.dbswitch.config.YudaoDbSwitchAutoConfiguration;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class QueryMetricsAutoConfigurationTest {
    @Test void deploymentQuotasAndOptionalMetricsBindToTheActualContext() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                        YudaoDbSwitchAutoConfiguration.class, DbSwitchQueryMetricsAutoConfiguration.class))
                .withPropertyValues("yudao.dbswitch.query.maximum-operations=6",
                        "yudao.dbswitch.query.maximum-operations-per-scope=1")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var runtime = context.getBean(DbSwitchRuntime.class);
                    assertEquals(new QueryRuntime.AdmissionLimits(6, 1), runtime.queries().limits());
                    var registry = new SimpleMeterRegistry();
                    try {
                        context.getBean(MeterBinder.class).bindTo(registry);
                        var operation = runtime.queries().register("tenant_sensitive", "record_sensitive", UUID.randomUUID(), OperationBudget.defaults());
                        operation.bindWorker();
                        assertEquals(1.0, registry.get("dbswitch.query.active").gauge().value());
                        operation.workerExited();
                        assertEquals(1, registry.get("dbswitch.query.phase").tags("phase", "queue", "outcome", "success").functionTimer().count());
                        assertTrue(registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                                .noneMatch(tag -> tag.getValue().contains("sensitive")));
                    } finally { registry.close(); }
                });
    }
}
