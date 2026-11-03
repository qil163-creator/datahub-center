package cn.iocoder.yudao.module.dataforge.job.database;

import cn.iocoder.yudao.module.dataforge.service.database.DatabaseMonitorService;
import com.xxl.job.core.context.XxlJobContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseMonitorCollectJobTest {
    @Test void collectsPlatformOnceOnCallingThread() {
        var monitor = mock(DatabaseMonitorService.class);
        Thread caller = Thread.currentThread();
        when(monitor.collectPlatform()).thenAnswer(call -> {
            assertSame(caller, Thread.currentThread());
            return 2;
        });
        new DatabaseMonitorCollectJob(monitor).execute();
        verify(monitor).collectPlatform();
        verifyNoMoreInteractions(monitor);
    }
    @Test void collectionFailureIsReportedToScheduler() {
        var monitor = mock(DatabaseMonitorService.class);
        when(monitor.collectPlatform()).thenThrow(new IllegalStateException("fixture failure"));
        var context = new XxlJobContext(7, "", "77.log", 0, 1);
        XxlJobContext.setXxlJobContext(context);
        try {
            new DatabaseMonitorCollectJob(monitor).execute();
            assertEquals(500, context.getHandleCode());
        } finally { XxlJobContext.setXxlJobContext(null); }
    }
}
