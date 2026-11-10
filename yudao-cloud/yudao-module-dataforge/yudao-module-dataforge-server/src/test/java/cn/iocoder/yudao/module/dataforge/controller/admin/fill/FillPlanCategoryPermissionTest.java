package cn.iocoder.yudao.module.dataforge.controller.admin.fill;

import cn.iocoder.yudao.module.dataforge.controller.admin.fill.vo.FillPlanCategorySaveReqVO;
import cn.iocoder.yudao.module.dataforge.service.fill.FillPlanCategoryService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;

class FillPlanCategoryPermissionTest {

    @Test
    void categoryAndPlanPermissionsAuthorizeOnlyTheirCorrespondingOperations() {
        try (var context = new AnnotationConfigApplicationContext(Configuration.class)) {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated("tester", "", List.of()));
            var controller = context.getBean(FillPlanCategoryController.class);
            var service = context.getBean(FillPlanCategoryService.class);
            var permissions = context.getBean(Permissions.class);
            var request = new FillPlanCategorySaveReqVO();
            request.setId(1L);
            request.setParentId(0L);
            request.setName("分类");

            checkPermission(permissions, service, "query", controller::getCategoryList);
            checkPermission(permissions, service, "query", () -> controller.getCategory(1L));
            checkPermission(permissions, service, "create", () -> controller.createCategory(request));
            checkPermission(permissions, service, "update", () -> controller.updateCategory(request));
            checkPermission(permissions, service, "delete", () -> controller.deleteCategory(1L));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void checkPermission(Permissions permissions, FillPlanCategoryService service,
                                        String operation, Runnable action) {
        clearInvocations(service);
        permissions.granted = Set.of("dataforge:fill-plan-category:unrelated");
        assertThrows(AccessDeniedException.class, action::run);
        verifyNoInteractions(service);
        for (String prefix : List.of("dataforge:fill-plan:", "dataforge:fill-plan-category:")) {
            permissions.granted = Set.of(prefix + operation);
            action.run();
            assertFalse(mockingDetails(service).getInvocations().isEmpty());
            clearInvocations(service);
        }
    }

    @org.springframework.context.annotation.Configuration
    @EnableMethodSecurity
    static class Configuration {
        @Bean FillPlanCategoryController controller() { return new FillPlanCategoryController(); }
        @Bean FillPlanCategoryService service() { return mock(FillPlanCategoryService.class); }
        @Bean("ss") Permissions permissions() { return new Permissions(); }
    }

    public static class Permissions {
        Set<String> granted = Set.of();

        public boolean hasAnyPermissions(String... permissions) {
            return Arrays.stream(permissions).anyMatch(granted::contains);
        }
    }
}
