package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.governance.controller.admin.master.MasterDistributeController;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeLogDO;
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
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterDistributeReviewSecurityTest {
    @AfterEach void cleanup(){TenantContextHolder.clear();SecurityContextHolder.clearContext();}
    private MasterDistributeResolveReqVO request(){
        var r=new MasterDistributeResolveReqVO();r.setRecordId(9007199254740993L);r.setRequestId(UUID.randomUUID().toString());
        r.setOutcome("NO_EFFECT");r.setReason("核对全部输出");r.setConfirmedStopped(true);r.setOutputReviewed(true);
        r.setStoppedEvidenceRef("CASE-STOP-1");r.setOutputEvidenceRef("CASE-OUTPUT-1");return r;
    }
    private void login(){
        TenantContextHolder.clear();TenantContextHolder.setIgnore(false);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L).setTenantId(1L),new MockHttpServletRequest());
    }
    @Test void requestRejectsInvalidEvidenceOutcomeIdsAndMissingAttestations(){
        try(var factory=Validation.buildDefaultValidatorFactory()){
            var validator=factory.getValidator();assertTrue(validator.validate(request()).isEmpty());
            for(var change:java.util.List.<java.util.function.Consumer<MasterDistributeResolveReqVO>>of(
                    r->r.setRecordId(-1L),r->r.setRequestId("bad"),r->r.setOutcome("SUCCESS"),r->r.setReason(" "),
                    r->r.setReason("x".repeat(501)),r->r.setOutputReviewed(null),r->r.setConfirmedStopped(false),
                    r->r.setStoppedEvidenceRef("https://invalid/?token=secret"),r->r.setOutputEvidenceRef(" "))){
                var r=request();change.accept(r);assertFalse(validator.validate(r).isEmpty());
            }
        }
    }
    @Test void serviceChecksCurrentActorAndIndependentPermissionBeforeTransaction(){
        login();var permission=mock(PermissionApi.class);var users=mock(AdminUserApi.class);var coordinator=mock(MasterDistributeRunCoordinator.class);
        var service=new MasterDistributeRunReviewService();ReflectionTestUtils.setField(service,"permissionApi",permission);
        ReflectionTestUtils.setField(service,"userApi",users);ReflectionTestUtils.setField(service,"coordinator",coordinator);
        var req=request();when(permission.hasAnyPermissions(99L,"governance:master-distribute:resolve")).thenReturn(CommonResult.success(false));
        assertThrows(RuntimeException.class,()->service.resolve(req));verifyNoInteractions(coordinator);
        when(permission.hasAnyPermissions(99L,"governance:master-distribute:resolve")).thenAnswer(call->{
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return CommonResult.success(true);
        });
        when(coordinator.resolve(req,99L)).thenReturn(MasterDistributeLogDO.builder().id(req.getRecordId()).runStatus(6).resolvedBy(99L).build());
        assertEquals(99L,service.resolve(req).getResolvedBy());verify(users,times(2)).validateUser(99L);
        SecurityContextHolder.clearContext();assertThrows(RuntimeException.class,()->service.resolve(req));verify(coordinator,times(1)).resolve(req,99L);
        login();TenantContextHolder.clear();assertEquals(99L,service.resolve(req).getResolvedBy());
    }
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity
    static class ControllerConfig{
        @Bean("ss") SecurityFrameworkService security(){return mock(SecurityFrameworkService.class);}
        @Bean MasterDistributeController controller(){return new MasterDistributeController();}
    }
    @Test void actualSecurityProxyDoesNotTreatRunAsReviewPermission(){
        login();try(var ctx=new AnnotationConfigApplicationContext()){
            ctx.getBeanFactory().registerSingleton("distributeService",mock(MasterDistributeService.class));
            ctx.getBeanFactory().registerSingleton("reviewService",mock(MasterDistributeRunReviewService.class));
            ctx.getBeanFactory().registerSingleton("modelService",mock(MasterModelService.class));
            ctx.register(ControllerConfig.class);ctx.refresh();
            var ss=ctx.getBean(SecurityFrameworkService.class);var service=ctx.getBean(MasterDistributeRunReviewService.class);
            var controller=ctx.getBean(MasterDistributeController.class);var req=request();
            when(ss.hasPermission("governance:master-distribute:run")).thenReturn(true);
            assertThrows(AccessDeniedException.class,()->controller.resolve(req));verifyNoInteractions(service);
            when(ss.hasPermission("governance:master-distribute:resolve")).thenReturn(true);
            when(service.resolve(req)).thenReturn(new MasterDistributeLogRespVO());assertNotNull(controller.resolve(req).getCheckedData());
        }
    }
    @Configuration(proxyBeanMethods=false)
    static class ValidationConfig{
        @Bean static MethodValidationPostProcessor validation(){return new MethodValidationPostProcessor();}
        @Bean MasterDistributeRunReviewService service(){return new MasterDistributeRunReviewService();}
    }
    @Test void actualServiceProxyValidatesBeforeCallingAuthorizationOrDatabase(){
        login();try(var ctx=new AnnotationConfigApplicationContext()){
            var permission=mock(PermissionApi.class);var users=mock(AdminUserApi.class);var coordinator=mock(MasterDistributeRunCoordinator.class);
            ctx.getBeanFactory().registerSingleton("permissionApi",permission);ctx.getBeanFactory().registerSingleton("userApi",users);
            ctx.getBeanFactory().registerSingleton("coordinator",coordinator);ctx.register(ValidationConfig.class);ctx.refresh();
            var req=request();req.setOutputReviewed(false);
            assertThrows(jakarta.validation.ConstraintViolationException.class,()->ctx.getBean(MasterDistributeRunReviewService.class).resolve(req));
            verifyNoInteractions(permission,users,coordinator);
        }
    }
    @Test void wireContractPreservesLargeIdsTimestampAndHidesLease(){
        var record=MasterDistributeLogDO.builder().id(9007199254740993L).resolvedBy(9007199254740995L)
                .resolvedAt(LocalDateTime.of(2026,9,27,12,0)).leaseToken("private-token").runStatus(6).resolutionOutcome("NO_EFFECT").build();
        var module=new tools.jackson.databind.module.SimpleModule();
        module.addSerializer(Long.class,cn.iocoder.yudao.framework.common.util.json.databind.NumberSerializer.INSTANCE);
        module.addSerializer(LocalDateTime.class,cn.iocoder.yudao.framework.common.util.json.databind.TimestampLocalDateTimeSerializer.INSTANCE);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().addModule(module).build();
        var json=mapper.readTree(mapper.writeValueAsString(BeanUtils.toBean(record,MasterDistributeLogRespVO.class)));
        assertEquals("9007199254740993",json.get("id").asString());assertEquals("9007199254740995",json.get("resolvedBy").asString());
        assertTrue(json.get("resolvedAt").isNumber());assertFalse(json.has("leaseToken"));assertFalse(json.has("tenantId"));
    }
}
