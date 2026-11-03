package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.DataPushTaskController;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskLogDO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.system.api.permission.PermissionApi;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataPushRunReviewTest {
    @AfterEach void clear() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }
    private DataPushResolveReqVO request() {
        var req=new DataPushResolveReqVO(); req.setRecordId(9007199254740993L);
        req.setRequestId(UUID.randomUUID().toString()); req.setOutcome("NO_EFFECT"); req.setReason("核对全部输出");
        req.setConfirmedStopped(true); req.setOutputReviewed(true);
        req.setStoppedEvidenceRef("CASE-STOP-1"); req.setOutputEvidenceRef("CASE-OUTPUT-1"); return req;
    }
    private void login() {
        TenantContextHolder.setTenantId(1L); TenantContextHolder.setIgnore(false);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L).setTenantId(1L),new MockHttpServletRequest());
    }

    @Test void voRejectsUnsupportedOutcomesMissingAttestationsAndSensitiveUrlQuery() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var v=factory.getValidator(); var req=request(); assertTrue(v.validate(req).isEmpty());
            req.setOutcome("SUCCESS"); assertFalse(v.validate(req).isEmpty());
            req=request(); req.setOutputReviewed(null); assertFalse(v.validate(req).isEmpty());
            req=request(); req.setConfirmedStopped(false); assertFalse(v.validate(req).isEmpty());
            req=request(); req.setStoppedEvidenceRef("https://example.invalid/?token=secret"); assertFalse(v.validate(req).isEmpty());
            req=request(); req.setReason("x".repeat(501)); assertFalse(v.validate(req).isEmpty());
            req=request(); req.setRequestId("not-a-uuid"); assertFalse(v.validate(req).isEmpty());
        }
    }

    @Test void serviceUsesCurrentActorAndIndependentPermissionBeforeTransaction() {
        login(); var permission=mock(PermissionApi.class); var users=mock(AdminUserApi.class);
        var access=new ExecutionAccessService(); ReflectionTestUtils.setField(access,"permissionApi",permission);
        ReflectionTestUtils.setField(access,"adminUserApi",users);
        var coordinator=mock(DataPushRunCoordinator.class); var service=new DataPushRunReviewService();
        ReflectionTestUtils.setField(service,"access",access); ReflectionTestUtils.setField(service,"coordinator",coordinator);
        var req=request();
        when(permission.hasAnyPermissions(99L,"dataforge:data-push:resolve")).thenReturn(CommonResult.success(false));
        assertThrows(RuntimeException.class,()->service.resolve(req)); verifyNoInteractions(coordinator);
        when(permission.hasAnyPermissions(99L,"dataforge:data-push:resolve")).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return CommonResult.success(true);
        });
        when(coordinator.resolve(req,99L)).thenReturn(DataPushTaskLogDO.builder().id(req.getRecordId()).runStatus(6).resolvedBy(99L).build());
        assertEquals(99L,service.resolve(req).getResolvedBy()); verify(users,times(2)).validateUser(99L);
        SecurityContextHolder.clearContext(); assertThrows(RuntimeException.class,()->service.resolve(req));
        verify(coordinator,times(1)).resolve(req,99L);
        login(); TenantContextHolder.setIgnore(true); assertEquals(99L, service.resolve(req).getResolvedBy());
    }

    @Configuration(proxyBeanMethods=false)
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean("ss") SecurityFrameworkService security() { return mock(SecurityFrameworkService.class); }
        @Bean DataPushTaskController controller() { return new DataPushTaskController(); }
    }

    @Test void actualControllerProxyRejectsRunPermissionAndRequiresResolvePermission() {
        login();
        try(var ctx=new AnnotationConfigApplicationContext()) {
            // 依赖以已构造的测试桩注册，避免对 Mockito 对象递归注入真实业务依赖。
            ctx.getBeanFactory().registerSingleton("taskService",mock(DataPushTaskService.class));
            ctx.getBeanFactory().registerSingleton("reviewService",mock(DataPushRunReviewService.class));
            ctx.register(SecurityConfig.class); ctx.refresh();
            var ss=ctx.getBean(SecurityFrameworkService.class); var service=ctx.getBean(DataPushRunReviewService.class);
            var controller=ctx.getBean(DataPushTaskController.class); var req=request();
            when(ss.hasPermission("dataforge:data-push:run")).thenReturn(true);
            assertThrows(AccessDeniedException.class,()->controller.resolve(req)); verifyNoInteractions(service);
            when(ss.hasPermission("dataforge:data-push:resolve")).thenReturn(true);
            when(service.resolve(req)).thenReturn(new DataPushTaskLogRespVO());
            assertNotNull(controller.resolve(req).getCheckedData()); verify(service).resolve(req);
        }
    }

    @Test void responsePreservesUnsafeIdsAndDoesNotExposeDispatchToken() {
        var row=DataPushTaskLogDO.builder().id(9007199254740993L).resolvedBy(9007199254740995L)
                .dispatchToken("private-token").runStatus(6).resolutionOutcome("NO_EFFECT").build();
        var vo=BeanUtils.toBean(row,DataPushTaskLogRespVO.class);
        var module=new tools.jackson.databind.module.SimpleModule();
        module.addSerializer(Long.class,cn.iocoder.yudao.framework.common.util.json.databind.NumberSerializer.INSTANCE);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().addModule(module).build();
        String json=mapper.writeValueAsString(vo);
        assertTrue(json.contains("\"id\":\"9007199254740993\""));
        assertTrue(json.contains("\"resolvedBy\":\"9007199254740995\""));
        assertFalse(json.contains("dispatch")); assertFalse(json.contains("private-token"));
    }
}
