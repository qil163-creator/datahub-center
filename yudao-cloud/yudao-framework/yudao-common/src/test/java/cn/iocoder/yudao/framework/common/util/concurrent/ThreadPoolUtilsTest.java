package cn.iocoder.yudao.framework.common.util.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadPoolUtilsTest {

    @Test
    void cancellationRemovesDelayedTasksAndWorkersAreNamedDaemons() throws Exception {
        try (var scheduler = ThreadPoolUtils.newDaemonScheduler(1, "test-deadline")) {
            var task = scheduler.schedule(() -> {}, 1, TimeUnit.DAYS);
            assertTrue(task.cancel(false));
            assertTrue(scheduler.getQueue().isEmpty());
            assertTrue(scheduler.submit(() -> Thread.currentThread().isDaemon()
                    && Thread.currentThread().getName().startsWith("test-deadline-")).get(5, TimeUnit.SECONDS));
        }
    }
}
