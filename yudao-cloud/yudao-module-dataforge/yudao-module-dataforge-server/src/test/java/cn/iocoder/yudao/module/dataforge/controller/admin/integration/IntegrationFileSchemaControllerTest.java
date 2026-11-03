package cn.iocoder.yudao.module.dataforge.controller.admin.integration;

import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationFileSchemaModels;
import cn.iocoder.yudao.module.dataforge.service.integration.IntegrationFileSchemaService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IntegrationFileSchemaControllerTest {
    @Test void permissionExpressionBlocksUnauthorizedParsingBeforeServiceAccess() {
        try (var context = new AnnotationConfigApplicationContext(Configuration.class)) {
            authenticate();
            var service = context.getBean(IntegrationFileSchemaService.class);
            var controller = context.getBean(IntegrationFileSchemaController.class);
            assertThrows(AccessDeniedException.class, () -> controller.schema(request()));
            verifyNoInteractions(service);
            context.getBean(Permissions.class).allowed = true;
            when(service.parse(any())).thenReturn(new IntegrationFileSchemaModels.Response(List.of(), List.of("采样"), true));
            assertTrue(controller.schema(request()).getCheckedData().partial());
            verify(service).parse(any());
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void httpRequestAcceptsLosslessStringIdAndValidatesBoundsBeforeParsing() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Configuration.class)) {
            authenticate(); context.getBean(Permissions.class).allowed = true;
            var service = context.getBean(IntegrationFileSchemaService.class);
            when(service.parse(any())).thenReturn(new IntegrationFileSchemaModels.Response(List.of(), List.of(), true));
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(IntegrationFileSchemaController.class)).build();
            mvc.perform(post("/dataforge/integration/file-schema").contentType("application/json")
                    .content("{\"fileId\":\"9007199254740993\",\"format\":\"JSONL\"}")).andExpect(status().isOk());
            verify(service).parse(argThat(req -> req.getFileId().equals(9007199254740993L) && req.getStartRow() == 2));
            clearInvocations(service);
            for (String body : List.of("{\"fileId\":1,\"format\":\"URL\"}",
                    "{\"fileId\":1,\"format\":\"EXCEL\",\"startRow\":0}",
                    "{\"fileId\":1,\"format\":\"EXCEL\",\"startColumn\":257}",
                    "{\"format\":\"JSONL\"}"))
                mvc.perform(post("/dataforge/integration/file-schema").contentType("application/json").content(body)).andExpect(status().isBadRequest());
            verifyNoInteractions(service);
        } finally { SecurityContextHolder.clearContext(); }
    }

    private static void authenticate() { SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("tester", "", List.of())); }
    private static IntegrationFileSchemaModels.Request request() { var req = new IntegrationFileSchemaModels.Request(); req.setFileId(1L); req.setFormat("JSONL"); return req; }

    @org.springframework.context.annotation.Configuration
    @EnableMethodSecurity
    static class Configuration {
        @Bean IntegrationFileSchemaController controller() { return new IntegrationFileSchemaController(); }
        @Bean IntegrationFileSchemaService service() { return mock(IntegrationFileSchemaService.class); }
        @Bean cn.iocoder.yudao.module.infra.api.file.ManagedFileApi files() { return mock(cn.iocoder.yudao.module.infra.api.file.ManagedFileApi.class); }
        @Bean("ss") Permissions permissions() { return new Permissions(); }
    }
    public static class Permissions { boolean allowed; public boolean hasAnyPermissions(String... permissions) { return allowed; } }
}
