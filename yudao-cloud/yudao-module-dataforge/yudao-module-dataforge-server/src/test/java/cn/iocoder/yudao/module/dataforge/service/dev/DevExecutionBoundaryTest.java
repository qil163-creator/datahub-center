package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class DevExecutionBoundaryTest {
    @Test void shellAndProcedureRequireAnExplicitIsolatedExecutionTarget() {
        DevDsSubmitter submitter = new DevDsSubmitter();
        DevTaskDO task = DevTaskDO.builder().taskType("SHELL").build();
        assertThrows(ServiceException.class, () -> submitter.executionTarget(task));
        task.setTaskType("PROCEDURE");
        assertThrows(ServiceException.class, () -> submitter.executionTarget(task));
        ReflectionTestUtils.setField(submitter, "workerGroup", "isolated");
        ReflectionTestUtils.setField(submitter, "tenantCode", "root");
        assertThrows(ServiceException.class, () -> submitter.executionTarget(task));
        ReflectionTestUtils.setField(submitter, "tenantCode", "datahub");
        assertEquals("isolated", submitter.executionTarget(task).workerGroup());
        assertEquals("datahub", submitter.executionTarget(task).tenantCode());
    }

    @Test void sqlTasksRetainConfiguredDefaultCompatibility() {
        DevDsSubmitter submitter = new DevDsSubmitter();
        assertEquals("default", submitter.executionTarget(
                DevTaskDO.builder().taskType("SQL").build()).workerGroup());
    }

}
