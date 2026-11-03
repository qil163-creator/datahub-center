package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskSaveReqVO;
import cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DevSqlWorkloadClassificationTest {
    @Test
    void realtimePageClassificationDoesNotTurnOrdinarySqlIntoAnUnboundedNativeProgram() {
        var service = new DevTaskServiceImpl(); ReflectionTestUtils.setField(service,"dirService",mock(DevDirService.class));
        var config = new DevExecutionConfig(); config.setWorkloadMode("STREAMING");
        var request = new DevTaskSaveReqVO(); request.setName("实时页面SQL"); request.setTaskType("SQL");
        request.setExecutionConfig(config); request.setTimeoutSeconds(60);
        assertDoesNotThrow(()->ReflectionTestUtils.invokeMethod(service,"validateTaskInput",request));
        assertNull(request.getProgramConfig());
        request.setTimeoutSeconds(0);
        assertThrows(ServiceException.class,()->ReflectionTestUtils.invokeMethod(service,"validateTaskInput",request));
        config.setWorkloadMode("UNBOUNDED");
        assertThrows(ServiceException.class,()->DevTaskDefinition.validateExecutionConfig(config));
    }
}
