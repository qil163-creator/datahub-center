package cn.iocoder.yudao.module.dataforge.controller.admin.dev;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskStatisticsRespVO;
import cn.iocoder.yudao.module.dataforge.service.dev.DevNativeRuntimeSupport;
import cn.iocoder.yudao.module.dataforge.service.dev.DevRecordService;
import cn.iocoder.yudao.module.dataforge.service.dev.DevScheduleCoordinator;
import cn.iocoder.yudao.module.dataforge.service.dev.DevTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DevWorkloadPermissionTest {
    private final DevTaskService tasks = mock(DevTaskService.class);
    private final DevRecordService records = mock(DevRecordService.class);
    private final SecurityFrameworkService security = mock(SecurityFrameworkService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SecurityConfiguration.class,DevTaskController.class)
            .withBean(DevTaskService.class,()->tasks)
            .withBean("devRecordService",DevRecordService.class,()->records)
            .withInitializer(app->app.getBeanFactory().registerSingleton("nativeRuntimeSupport",mock(DevNativeRuntimeSupport.class)))
            .withInitializer(app->app.getBeanFactory().registerSingleton("scheduleCoordinator",mock(DevScheduleCoordinator.class)))
            .withInitializer(app->app.getBeanFactory().registerSingleton("devTaskLineageCollector",
                    mock(cn.iocoder.yudao.module.dataforge.service.lineage.DevTaskLineageCollector.class)))
            .withBean("ss",SecurityFrameworkService.class,()->security);

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class SecurityConfiguration { }

    @Test
    void workloadQueriesRequireTheExistingQueryPermissionAndDoNotGrantWrites() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("operator","",List.of()));
        try {
            context.run(app->{
                assertThat(app).hasNotFailed();
                var controller = app.getBean(DevTaskController.class);
                var page = new DevTaskPageReqVO(); page.setWorkloadMode("STREAMING");
                assertThrows(AccessDeniedException.class,()->controller.getTaskPage(page));
                assertThrows(AccessDeniedException.class,()->controller.getStatistics("STREAMING"));
                assertThrows(AccessDeniedException.class,()->controller.getTaskListByDir(null,"STREAMING"));
                verifyNoInteractions(tasks);
                when(security.hasPermission("dataforge:dev-task:query")).thenReturn(true);
                when(tasks.getTaskPage(page)).thenReturn(new PageResult<>(List.of(),0L));
                when(tasks.getStatistics("STREAMING")).thenReturn(new DevTaskStatisticsRespVO());
                when(tasks.getTaskListByDir(null,"STREAMING")).thenReturn(List.of());
                when(records.getLatestRecords(List.of())).thenReturn(java.util.Map.of());
                assertEquals(0,controller.getTaskPage(page).getCheckedData().getTotal());
                assertNotNull(controller.getStatistics("STREAMING").getCheckedData());
                assertTrue(controller.getTaskListByDir(null,"STREAMING").getCheckedData().isEmpty());
                assertThrows(AccessDeniedException.class,()->controller.createTask(new DevTaskSaveReqVO()));
                assertThrows(AccessDeniedException.class,()->controller.updateTaskStatus(1L,1));
                verify(tasks,never()).createTask(any()); verify(tasks,never()).updateTaskStatus(any(),any());
            });
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void workloadParametersAcceptOnlyTheTwoSupportedModes() throws Exception {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var page = new DevTaskPageReqVO(); page.setWorkloadMode("BATCH");
            assertTrue(validator.validate(page).isEmpty());
            page.setWorkloadMode("STREAMING"); assertTrue(validator.validate(page).isEmpty());
            page.setWorkloadMode("STREAMING' OR 1=1 --"); assertFalse(validator.validate(page).isEmpty());
            var method = DevTaskController.class.getMethod("getStatistics",String.class);
            assertFalse(validator.forExecutables().validateParameters(new DevTaskController(),method,
                    new Object[]{"UNKNOWN"}).isEmpty());
        }
    }
}
