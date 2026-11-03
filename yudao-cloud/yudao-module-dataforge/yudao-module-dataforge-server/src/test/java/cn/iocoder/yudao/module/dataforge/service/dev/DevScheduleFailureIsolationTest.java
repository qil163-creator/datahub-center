package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.api.WorkflowInstanceApi;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevScheduleDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevScheduleMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevScheduleFailureIsolationTest {
    private DevScheduleCoordinator coordinator;
    private WorkflowInstanceApi instances;
    private DevTaskService tasks;
    private DevScheduleDO binding;

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "marker-failure-test"), DevScheduleDO.class);
        coordinator = new DevScheduleCoordinator(); var schedules = mock(DevScheduleMapper.class);
        when(schedules.update(any(), any())).thenReturn(1);
        var client = mock(DolphinSchedulerClient.class); instances = mock(WorkflowInstanceApi.class);
        when(client.workflowInstance()).thenReturn(instances); tasks = mock(DevTaskService.class);
        @SuppressWarnings("unchecked") var provider = (ObjectProvider<DevTaskService>) mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(tasks);
        ReflectionTestUtils.setField(coordinator, "schedules", schedules); ReflectionTestUtils.setField(coordinator, "client", client);
        ReflectionTestUtils.setField(coordinator, "tasks", provider); ReflectionTestUtils.setField(coordinator, "apiTimezoneId", "Asia/Shanghai");
        binding = new DevScheduleDO(); binding.setId(1L); binding.setTaskId(2L);
        binding.setVersionId(80L); binding.setGeneration(3); binding.setDsProjectCode(19L); binding.setDsWorkflowCode(20L);
        binding.setTimezoneId("Asia/Shanghai"); binding.setWorkerGroup("controlled"); binding.setTenantCode("isolated");
    }

    @Test void failedAndStoppedMarkersAreRecordedWithoutBlockingLaterSuccessfulTriggers() {
        when(instances.queryWorkflowInstanceById(19L, 1)).thenReturn(ok(instance(1, "FAILURE")));
        when(instances.queryWorkflowInstanceById(19L, 2)).thenReturn(ok(instance(2, "STOP")));
        when(instances.queryWorkflowInstanceById(19L, 3)).thenReturn(ok(instance(3, "SUCCESS")));
        Long water = ReflectionTestUtils.invokeMethod(coordinator, "importInstances", binding, "owned",
                List.of(new JSONObject().set("id", 1), new JSONObject().set("id", 2), new JSONObject().set("id", 3)), 0L);
        assertEquals(3L, water);
        var date = LocalDateTime.of(2026, 10, 14, 0, 0);
        verify(tasks).recordTaskScheduledFailure(binding, 1L, date, "FAILED");
        verify(tasks).recordTaskScheduledFailure(binding, 2L, date, "STOPPED");
        verify(tasks).runTaskScheduled(binding, 3L, date);
        verify(tasks, times(1)).runTaskScheduled(any(), any(), any());
    }

    @Test void foreignFailedMarkerStillFailsVerificationWithoutRecordingAnything() {
        when(instances.queryWorkflowInstanceById(19L, 1)).thenReturn(ok(instance(1, "FAILURE").set("projectCode", 999L)));
        assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(coordinator, "importInstances", binding,
                "owned", List.of(new JSONObject().set("id", 1)), 0L));
        verifyNoInteractions(tasks);
    }

    private JSONObject instance(int id, String state) {
        var markers = new JSONArray();
        Map.of("task", 2L, "version", 80L, "generation", 3L).forEach((key, value) ->
                markers.put(new JSONObject().set("prop", "dh_" + key).set("value", value.toString())));
        return new JSONObject().set("id", id).set("workflowDefinitionCode", 20L).set("workflowDefinitionVersion", 1)
                .set("projectCode", 19L).set("commandType", "SCHEDULER").set("dryRun", 0)
                .set("workerGroup", "controlled").set("tenantCode", "isolated").set("globalParams", markers)
                .set("state", state).set("scheduleTime", "2026-10-14 00:00:00");
    }

    private JSONObject ok(JSONObject data) { return new JSONObject().set("code", 0).set("data", data); }
}
