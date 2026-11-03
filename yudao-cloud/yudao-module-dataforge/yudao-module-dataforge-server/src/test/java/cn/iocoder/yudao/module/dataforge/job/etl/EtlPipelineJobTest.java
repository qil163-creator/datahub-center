package cn.iocoder.yudao.module.dataforge.job.etl;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlPipelineDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlPipelineMapper;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineExecutor;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Job 仅负责全局扫描和逐租户调度，不能先独立推进时间。 */
class EtlPipelineJobTest {
    @Test
    void dueScanHasNoUnboundedLocalClockOverload() {
        var methods = java.util.Arrays.stream(EtlPipelineMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("selectDueList")).toList();
        org.assertj.core.api.Assertions.assertThat(methods).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(methods.getFirst().getParameterTypes())
                .containsExactly(int.class, int.class);
    }

    @Test
    void jobNeverAdvancesScheduleOutsideDurableAcceptanceTransaction() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "etl-job-test"), EtlPipelineDO.class);
        EtlPipelineMapper mapper = mock(EtlPipelineMapper.class);
        EtlPipelineService service = mock(EtlPipelineService.class);
        EtlPipelineExecutor executor = mock(EtlPipelineExecutor.class);
        EtlPipelineJob job = new EtlPipelineJob();
        ReflectionTestUtils.setField(job, "pipelineMapper", mapper);
        ReflectionTestUtils.setField(job, "pipelineService", service);
        ReflectionTestUtils.setField(job, "executor", executor);
        ReflectionTestUtils.setField(job, "executionProperties",
                new cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties());
        var due = EtlPipelineDO.builder().id(10L).status(1).scheduleStatus(1).scheduleGeneration(2L)
                .nextRunTime(LocalDateTime.of(2026, 1, 1, 0, 0))
                .publishedConfigJson("{\"cron\":\"0 * * * * *\"}").build();
        when(mapper.selectDueList(anyInt(), anyInt())).thenReturn(List.of(due));
        when(service.runPipelineScheduled(10L, 2L, due.getNextRunTime())).thenAnswer(invocation -> {
            return new cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt(20L, false, 1, false);
        });
        TenantContextHolder.setIgnore(true);
        try {
            job.execute();
            verify(mapper, never()).update(any(), any());
            verify(service).runPipelineScheduled(10L, 2L, due.getNextRunTime());
            verify(executor).dispatchWaiting();
        } finally {
            TenantContextHolder.clear();
        }
    }
}
