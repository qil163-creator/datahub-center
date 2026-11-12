package cn.iocoder.yudao.module.dataforge.controller.admin.dev;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.service.dev.DevNativeRuntimeSupport;
import cn.iocoder.yudao.module.dataforge.service.dev.DevRecordService;
import cn.iocoder.yudao.module.dataforge.service.dev.DevTaskService;
import com.mzt.logapi.service.impl.DefaultLogRecordServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevTaskControllerContextTest {

    private final DevTaskService tasks = mock(DevTaskService.class);
    private final DevRecordService records = mock(DevRecordService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DevTaskController.class)
            .withBean("recordService", DefaultLogRecordServiceImpl.class, DefaultLogRecordServiceImpl::new)
            .withBean("devRecordServiceImpl", DevRecordService.class, () -> records)
            .withBean(DevTaskService.class, () -> tasks)
            .withInitializer(app -> app.getBeanFactory().registerSingleton("devScheduleCoordinator",
                    scheduleCoordinator()))
            .withInitializer(app -> app.getBeanFactory().registerSingleton("devNativeRuntimeSupport",
                    mock(DevNativeRuntimeSupport.class)))
            .withInitializer(app -> app.getBeanFactory().registerSingleton("devTaskLineageCollector",
                    mock(cn.iocoder.yudao.module.dataforge.service.lineage.DevTaskLineageCollector.class)));

    private static cn.iocoder.yudao.module.dataforge.service.dev.DevScheduleCoordinator scheduleCoordinator() {
        var scheduler = mock(cn.iocoder.yudao.module.dataforge.service.dev.DevScheduleCoordinator.class);
        when(scheduler.state(org.mockito.ArgumentMatchers.anyLong())).thenReturn(
                new cn.iocoder.yudao.module.dataforge.service.dev.DevScheduleCoordinator.SyncState("NOT_CONFIGURED", null));
        return scheduler;
    }

    @Test
    void taskDetailsUseDevelopmentRecordsWhenTheLogRecordBeanIsPresent() {
        when(tasks.getTask(11L)).thenReturn(task(11L));
        when(records.getLatestRecords(List.of(11L))).thenReturn(Map.of(11L, record(21L, 11L)));

        context.run(app -> {
            assertThat(app).hasNotFailed();
            assertThat(app.getBean("recordService")).isInstanceOf(DefaultLogRecordServiceImpl.class);
            var response = app.getBean(DevTaskController.class).getTask(11L).getCheckedData();
            assertThat(response.getId()).isEqualTo(11L);
            assertThat(response.getWorkloadMode()).isEqualTo("BATCH");
            assertThat(response.getLatestRecord().getId()).isEqualTo(21L);
            verify(records).getLatestRecords(List.of(11L));
        });
    }

    @Test
    void taskPageUsesDevelopmentRecordsWhenTheLogRecordBeanIsPresent() {
        var request = new DevTaskPageReqVO();
        when(tasks.getTaskPage(request)).thenReturn(new PageResult<>(List.of(task(11L), task(12L)), 2L));
        when(records.getLatestRecords(List.of(11L, 12L)))
                .thenReturn(Map.of(11L, record(21L, 11L), 12L, record(22L, 12L)));

        context.run(app -> {
            assertThat(app).hasNotFailed();
            var page = app.getBean(DevTaskController.class).getTaskPage(request).getCheckedData();
            assertThat(page.getTotal()).isEqualTo(2L);
            assertThat(page.getList()).extracting(task -> task.getLatestRecord().getId())
                    .containsExactly(21L, 22L);
            verify(records).getLatestRecords(List.of(11L, 12L));
        });
    }

    private static DevTaskDO task(long id) {
        return DevTaskDO.builder().id(id).taskType("SQL").content("SELECT 1").build();
    }

    @Test
    void ordinarySqlClassificationAndNativeModeUseTheirSeparateTypedContracts() {
        when(tasks.getTask(13L)).thenReturn(DevTaskDO.builder().id(13L).taskType("SQL")
                .executionConfigJson("{\"workloadMode\":\"STREAMING\"}").build());
        when(records.getLatestRecords(List.of(13L))).thenReturn(Map.of());
        when(tasks.getTask(14L)).thenReturn(DevTaskDO.builder().id(14L).taskType("FLINK_SQL")
                .programConfigJson("{\"workloadMode\":\"BATCH\"}")
                .executionConfigJson("{\"workloadMode\":\"STREAMING\"}").build());
        when(records.getLatestRecords(List.of(14L))).thenReturn(Map.of());
        context.run(app -> {
            assertThat(app).hasNotFailed(); var controller = app.getBean(DevTaskController.class);
            var sql = controller.getTask(13L).getCheckedData();
            assertThat(sql.getWorkloadMode()).isEqualTo("STREAMING"); assertThat(sql.getProgramConfig()).isNull();
            var nativeTask = controller.getTask(14L).getCheckedData();
            assertThat(nativeTask.getWorkloadMode()).isEqualTo("STREAMING");
            assertThat(nativeTask.getProgramConfig().getWorkloadMode()).isEqualTo("BATCH");
        });
    }

    private static DevRecordDO record(long id, long taskId) {
        return DevRecordDO.builder().id(id).taskId(taskId).status("SUCCESS").build();
    }
}
