package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.job.access.DataAccessSyncJob;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DataAccessSyncJobTest {
    @Test void failuresInEveryPhaseRemainVisibleToMonitoring() {
        var coordinator = mock(AccessRunCoordinator.class);
        when(coordinator.recoverExpired(anyInt())).thenThrow(new IllegalStateException("offline"));
        when(coordinator.reconcileDue(anyInt())).thenThrow(new IllegalStateException("offline"));
        when(coordinator.scanScheduled(anyInt())).thenThrow(new IllegalStateException("offline"));
        when(coordinator.dispatchWaiting(anyInt())).thenThrow(new IllegalStateException("offline"));
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            var job = new DataAccessSyncJob();
            ReflectionTestUtils.setField(job, "coordinator", coordinator);
            ReflectionTestUtils.setField(job, "properties",
                    new cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties());
            ReflectionTestUtils.setField(job, "metrics",
                    new cn.iocoder.yudao.module.dataforge.service.execution.RunMetrics(registry));
            job.execute();
            for (String phase : List.of("RECOVER", "RECONCILE", "SCHEDULED", "DISPATCH"))
                org.junit.jupiter.api.Assertions.assertEquals(1, registry.get("dataforge.run.scan.failures")
                        .tags("domain", "ACCESS", "phase", phase).counter().count());
        } finally {
            registry.close();
        }
    }

    @Test void schedulerMustNeverAdvanceDueTimeOutsideDurableAcceptance() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DataAccessDO.class);
        var mapper = mock(DataAccessMapper.class);
        var due = DataAccessDO.builder().id(10L).taskType(3).status(1).scheduleGeneration(3L)
                .cron("0 * * * * *").nextRunTime(LocalDateTime.of(2026,1,1,0,0)).build();
        when(mapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(due));
        doThrow(new AssertionError("schedule advanced outside durable acceptance")).when(mapper).update(isNull(), any());
        var job = new DataAccessSyncJob();
        if (ReflectionUtils.findField(DataAccessSyncJob.class, "dataAccessMapper") != null)
            ReflectionTestUtils.setField(job, "dataAccessMapper", mapper);
        if (ReflectionUtils.findField(DataAccessSyncJob.class, "dataAccessExecutor") != null)
            ReflectionTestUtils.setField(job, "dataAccessExecutor", mock(DataAccessExecutor.class));
        if (ReflectionUtils.findField(DataAccessSyncJob.class, "coordinator") != null)
            ReflectionTestUtils.setField(job, "coordinator", mock(AccessRunCoordinator.class));
        if (ReflectionUtils.findField(DataAccessSyncJob.class, "properties") != null)
            ReflectionTestUtils.setField(job, "properties", new cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties());
        job.execute();
        verify(mapper, never()).update(any(), any());
    }
}
