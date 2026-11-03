package cn.iocoder.yudao.module.dataforge.controller.admin.integration;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.SaveReq;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.TaskResp;
import cn.iocoder.yudao.module.dataforge.service.integration.IntegrationTaskService;
import cn.iocoder.yudao.module.dataforge.service.integration.IntegrationComponentCapabilitiesService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class IntegrationTaskControllerContextTest {
    private final IntegrationTaskService tasks = mock(IntegrationTaskService.class);
    private final SecurityFrameworkService security = mock(SecurityFrameworkService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(SecurityConfiguration.class, IntegrationTaskController.class)
            .withInitializer(app -> app.getBeanFactory().registerSingleton("integrationTaskService", tasks))
            .withBean(IntegrationComponentCapabilitiesService.class)
            .withInitializer(app -> app.getBeanFactory().registerSingleton("integrationWebServiceMetadataService",
                    mock(cn.iocoder.yudao.module.dataforge.service.integration.IntegrationWebServiceMetadataService.class)))
            .withBean("ss", SecurityFrameworkService.class, () -> security);

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class SecurityConfiguration { }

    @Test void queryPermissionCannotCreateOrDeleteTasks() {
        when(security.hasPermission("dataforge:integration:query")).thenReturn(true);
        var task = new TaskResp(); task.setId(9007199254740993L); when(tasks.get(task.getId())).thenReturn(task);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("operator", "", List.of()));
        try {
            context.run(app -> {
                assertThat(app).hasNotFailed();
                var controller = app.getBean(IntegrationTaskController.class);
                assertThat(controller.get(task.getId()).getCheckedData().getId()).isEqualTo(task.getId());
                assertThat(controller.componentCapabilities("FLINK", "REALTIME").getCheckedData().components()).hasSize(49);
                var request = new cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.FlowReq();
                var schema = new cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaResult(); when(tasks.schema(request)).thenReturn(schema);
                assertThat(controller.schema(request).getCheckedData()).isSameAs(schema);
                verify(security, never()).hasPermission("dataforge:etl:query");
                assertThrows(AccessDeniedException.class, () -> controller.create(new SaveReq()));
                assertThrows(AccessDeniedException.class, () -> controller.delete(task.getId()));
                verify(tasks, never()).create(any()); verify(tasks, never()).delete(any());
            });
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void runPermissionDoesNotGrantStatusOrScheduleWrites() {
        when(security.hasPermission("dataforge:integration:run")).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("operator", "", List.of()));
        try {
            context.run(app -> {
                assertThat(app).hasNotFailed();
                var controller = app.getBean(IntegrationTaskController.class);
                assertThrows(AccessDeniedException.class, () -> controller.status(null));
                assertThrows(AccessDeniedException.class, () -> controller.schema(null));
                assertThrows(AccessDeniedException.class, () -> controller.webServiceMetadata(null));
                assertThrows(AccessDeniedException.class, () -> controller.apiMetadata(null));
                assertThrows(AccessDeniedException.class, () -> controller.componentCapabilities("FLINK", "REALTIME"));
                assertThrows(AccessDeniedException.class, () -> controller.schedule(null));
                verifyNoInteractions(tasks);
            });
        } finally { SecurityContextHolder.clearContext(); }
    }
}
