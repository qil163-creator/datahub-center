package cn.iocoder.yudao.module.dataforge.service.execution;

import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlRecordMapper;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import cn.iocoder.yudao.module.dataforge.service.access.AccessRunCoordinator;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlPipelineExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.*;

class RunDispatchCapacityTest {
    @Test
    void bothDispatchersPassConfiguredBudgetsToTheScan() {
        var properties = new RunExecutionProperties();
        properties.setScanBatch(14);
        properties.setPlatformActive(17);
        properties.setPerDataSourceActive(3);
        var metrics = mock(RunMetrics.class);
        var etlRuns = mock(EtlRecordMapper.class);
        when(etlRuns.selectDispatchCandidates(14, 17, 3)).thenReturn(List.of());
        var etl = new EtlPipelineExecutor();
        ReflectionTestUtils.setField(etl, "executionProperties", properties);
        ReflectionTestUtils.setField(etl, "recordMapper", etlRuns);
        etl.dispatchWaiting();
        verify(etlRuns).selectDispatchCandidates(14, 17, 3);

        var accessRuns = mock(DataAccessTaskMapper.class);
        when(accessRuns.waitingCandidates(14, 17, 3)).thenReturn(List.of());
        var access = new AccessRunCoordinator();
        ReflectionTestUtils.setField(access, "properties", properties);
        ReflectionTestUtils.setField(access, "runs", accessRuns);
        ReflectionTestUtils.setField(access, "metrics", metrics);
        access.dispatchWaiting(14);
        verify(accessRuns).waitingCandidates(14, 17, 3);
    }
}
