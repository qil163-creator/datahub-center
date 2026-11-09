package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.module.governance.controller.admin.quality.QualityWorkOrderController;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderStatisticsRespVO;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityWorkOrderStatisticsSecurityTest {
    @Test void queryPermissionIsEnforcedByTheRealControllerProxy() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("fixture", "", List.of()));
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(QualityWorkOrderController.class);
            var service = context.getBean(QualityWorkOrderService.class);
            var gate = context.getBean(Gate.class);
            assertThatThrownBy(() -> controller.getWorkOrderStatistics(7)).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(service);
            gate.allowed = true;
            var stats = new QualityWorkOrderStatisticsRespVO();
            when(service.getWorkOrderStatistics(7)).thenReturn(stats);
            assertThat(controller.getWorkOrderStatistics(7).getData()).isSameAs(stats);
        } finally { SecurityContextHolder.clearContext(); }
    }

    public static class Gate {
        boolean allowed;
        String expectedPermission = QualityWorkOrderGuard.PERM_QUERY;
        public boolean hasPermission(String code) {
            assertThat(code).isEqualTo(expectedPermission);
            return allowed;
        }
    }

    @Test void returnCannotBypassTheControllerConfirmPermission() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("fixture", "", List.of()));
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var controller = context.getBean(QualityWorkOrderController.class);
            var service = context.getBean(QualityWorkOrderService.class);
            var gate = context.getBean(Gate.class);
            gate.expectedPermission = QualityWorkOrderGuard.PERM_CONFIRM;
            var req = new cn.iocoder.yudao.module.governance.controller.admin.quality.vo.workorder.QualityWorkOrderCommandReqVO();
            req.setId(8L); req.setExpectedVersion(1); req.setRequestId("return");
            req.setConfirmAction("RETURN"); req.setReason("补充材料");
            assertThatThrownBy(() -> controller.confirm(req)).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(service);
            gate.allowed = true;
            controller.confirm(req);
            verify(service).confirm(req);
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean(name = "ss") Gate gate() { return new Gate(); }
        @Bean QualityWorkOrderService service() { return mock(QualityWorkOrderService.class); }
        @Bean QualityExceptionExpiryFailureService expiryFailureService() {
            return mock(QualityExceptionExpiryFailureService.class);
        }
        @Bean QualityWorkOrderController controller(QualityWorkOrderService service,
                QualityExceptionExpiryFailureService expiryFailureService) {
            var controller = new QualityWorkOrderController();
            ReflectionTestUtils.setField(controller, "workOrderService", service);
            ReflectionTestUtils.setField(controller, "expiryFailureService", expiryFailureService);
            return controller;
        }
    }
}
