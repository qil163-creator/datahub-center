package cn.iocoder.yudao.module.dataforge.framework.execution.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RunExecutionPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RunExecutionConfiguration.class);

    @Test
    void defaultsProvideHeadroomAndBoundedDispatch() {
        context.run(app -> {
            assertThat(app).hasNotFailed();
            RunExecutionProperties properties = app.getBean(RunExecutionProperties.class);
            assertThat(properties.getLease()).isEqualTo(Duration.ofSeconds(90));
            assertThat(properties.getHeartbeat()).isEqualTo(Duration.ofSeconds(20));
            assertThat(properties.getStopPoll()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getScanBatch()).isEqualTo(100);
            assertThat(properties.getPerTaskCatchUp()).isEqualTo(1);
            assertThat(properties.getPerTaskPending()).isEqualTo(100);
            assertThat(properties.getPlatformActive()).isEqualTo(32);
            assertThat(properties.getPerDataSourceActive()).isEqualTo(8);
            assertThat(properties.getAutomaticFailureLimit()).isEqualTo(10);
        });
    }

    @Test
    void configuredLeaseMustHaveAtLeastThreeHeartbeatIntervals() {
        context.withPropertyValues("yudao.dataforge.execution.lease=30s",
                "yudao.dataforge.execution.heartbeat=11s").run(app -> assertThat(app).hasFailed());
        context.withPropertyValues("yudao.dataforge.execution.lease=30s",
                "yudao.dataforge.execution.heartbeat=10s").run(app -> assertThat(app).hasNotFailed());
    }

    @Test
    void rejectsZeroNegativeAndExcessiveCapacitiesAtStartup() {
        for (String value : new String[]{"lease=0s", "heartbeat=-1s", "stop-poll=0s",
                "scan-batch=0", "scan-batch=10001", "per-task-pending=0", "per-task-catch-up=0",
                "platform-active=0", "platform-active=10001",
                "per-data-source-active=0", "per-data-source-active=10001",
                "automatic-failure-limit=0", "stop-poll=91s", "stop-poll=6s",
                "worker-concurrency=0", "worker-concurrency=129", "worker-queue=-1", "worker-queue=10001"}) {
            context.withPropertyValues("yudao.dataforge.execution." + value)
                    .run(app -> assertThat(app).hasFailed());
        }
    }
}
