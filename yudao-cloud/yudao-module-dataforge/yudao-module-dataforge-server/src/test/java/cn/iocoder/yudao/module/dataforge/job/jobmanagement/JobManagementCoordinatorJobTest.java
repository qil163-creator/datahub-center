package cn.iocoder.yudao.module.dataforge.job.jobmanagement;

import cn.iocoder.yudao.module.dataforge.service.job.JobQueueCoordinator;
import com.xxl.job.core.context.XxlJobContext;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证真实 XXL 回调上下文能区分基础失败和受理完成，不将业务提交等同执行成功。 */
class JobManagementCoordinatorJobTest {
    @AfterEach void clear(){XxlJobContext.setXxlJobContext(null);}
    @Test void coordinatorInfrastructureFailureIsReportedAsFailedCallback() {
        var service=mock(JobQueueCoordinator.class);when(service.maintain()).thenReturn(1);var job=new JobManagementCoordinatorJob();ReflectionTestUtils.setField(job,"coordinator",service);
        var context=new XxlJobContext(1L,"","",0,1);XxlJobContext.setXxlJobContext(context);job.execute();
        assertEquals(XxlJobContext.HANDLE_CODE_FAIL,context.getHandleCode());assertTrue(context.getHandleMsg().contains("不盲目重投"));
    }
    @Test void successfulMaintenanceStillPointsToActualBusinessRecords() {
        var service=mock(JobQueueCoordinator.class);var job=new JobManagementCoordinatorJob();ReflectionTestUtils.setField(job,"coordinator",service);
        var context=new XxlJobContext(1L,"","",0,1);XxlJobContext.setXxlJobContext(context);job.execute();
        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS,context.getHandleCode());assertTrue(context.getHandleMsg().contains("业务结果请查看各运行记录"));
    }
}
