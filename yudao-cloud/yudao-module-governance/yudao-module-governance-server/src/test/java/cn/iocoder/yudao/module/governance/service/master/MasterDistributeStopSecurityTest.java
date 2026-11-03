package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.controller.admin.master.MasterDistributeController;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterDistributeLogDO;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MasterDistributeStopSecurityTest {
    private MasterDistributeStopReqVO request() {
        var r=new MasterDistributeStopReqVO();r.setRecordId(9007199254740993L);r.setReason("维护停止");return r;
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}

    @Test void requestRequiresPositiveRecordAndBoundedNonBlankReason() {
        try(var factory=Validation.buildDefaultValidatorFactory()){
            var validator=factory.getValidator();assertTrue(validator.validate(request()).isEmpty());
            for(var change:java.util.List.<java.util.function.Consumer<MasterDistributeStopReqVO>>of(
                    r->r.setRecordId(null),r->r.setRecordId(0L),r->r.setRecordId(-1L),
                    r->r.setReason(null),r->r.setReason(" \n"),r->r.setReason("x".repeat(501)))){
                var r=request();change.accept(r);assertFalse(validator.validate(r).isEmpty());
            }
        }
    }
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity
    static class ControllerConfig{
        @Bean("ss") SecurityFrameworkService security(){return mock(SecurityFrameworkService.class);}
        @Bean MasterDistributeController controller(){return new MasterDistributeController();}
    }
    @Test void realSecurityProxyRequiresRunPermissionNotReleaseQueryOrResolve() {
        try(var ctx=new AnnotationConfigApplicationContext()){
            var service=mock(MasterDistributeService.class);
            ctx.getBeanFactory().registerSingleton("distributeService",service);
            ctx.getBeanFactory().registerSingleton("reviewService",mock(MasterDistributeRunReviewService.class));
            ctx.getBeanFactory().registerSingleton("modelService",mock(MasterModelService.class));
            ctx.register(ControllerConfig.class);ctx.refresh();
            var ss=ctx.getBean(SecurityFrameworkService.class);var controller=ctx.getBean(MasterDistributeController.class);
            when(ss.hasPermission("governance:master-distribute:release")).thenReturn(true);
            when(ss.hasPermission("governance:master-distribute:query")).thenReturn(true);
            when(ss.hasPermission("governance:master-distribute:resolve")).thenReturn(true);
            var req=request();assertThrows(AccessDeniedException.class,()->controller.stopRun(req));verifyNoInteractions(service);
            when(ss.hasPermission("governance:master-distribute:run")).thenReturn(true);
            var receipt=new MasterDistributeLogRespVO();when(service.stopRun(req)).thenReturn(receipt);
            assertSame(receipt,controller.stopRun(req).getCheckedData());verify(service).stopRun(req);
        }
    }
    @Test void actualHttpRouteValidatesBodyBeforeInvokingService() throws Exception {
        var controller=new MasterDistributeController();var service=mock(MasterDistributeService.class);
        ReflectionTestUtils.setField(controller,"distributeService",service);
        try(var validator=new LocalValidatorFactoryBean()){
            validator.afterPropertiesSet();
            var mvc=MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();
            mvc.perform(put("/governance/master-distribute/stop").contentType("application/json")
                    .content("{\"recordId\":0,\"reason\":\" \"}")).andExpect(status().isBadRequest());
            verifyNoInteractions(service);
            mvc.perform(put("/governance/master-distribute/stop").contentType("application/json")
                    .content("{\"recordId\":\"9007199254740993\",\"reason\":\"维护停止\"}")).andExpect(status().isOk());
            verify(service).stopRun(argThat(r->r.getRecordId()==9007199254740993L && r.getReason().equals("维护停止")));
        }
    }
    @Test void serviceDerivesActorFromLoginAndReturnsOnlyResponseContract() {
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L).setTenantId(1L),new MockHttpServletRequest());
        var service=new MasterDistributeServiceImpl();var coordinator=mock(MasterDistributeRunCoordinator.class);
        ReflectionTestUtils.setField(service,"coordinator",coordinator);
        var req=request();when(coordinator.stop(req,99L)).thenReturn(MasterDistributeLogDO.builder()
                .id(req.getRecordId()).runStatus(4).stopRequestedBy(99L).stopReason(req.getReason()).build());
        assertEquals(99L,service.stopRun(req).getStopRequestedBy());verify(coordinator).stop(req,99L);
    }
    @Test void stopReceiptPreservesLargeActorIdAndTimestampWithoutLeaseOrTenant() {
        var record=MasterDistributeLogDO.builder().id(9007199254740993L).stopRequestedBy(9007199254740995L)
                .stopRequestedAt(LocalDateTime.of(2026,9,27,12,0)).stopReason("维护停止").leaseToken("private-token").runStatus(5).build();
        var module=new tools.jackson.databind.module.SimpleModule();
        module.addSerializer(Long.class,cn.iocoder.yudao.framework.common.util.json.databind.NumberSerializer.INSTANCE);
        module.addSerializer(LocalDateTime.class,cn.iocoder.yudao.framework.common.util.json.databind.TimestampLocalDateTimeSerializer.INSTANCE);
        var mapper=tools.jackson.databind.json.JsonMapper.builder().addModule(module).build();
        var json=mapper.readTree(mapper.writeValueAsString(BeanUtils.toBean(record,MasterDistributeLogRespVO.class)));
        assertEquals("9007199254740993",json.get("id").asString());assertEquals("9007199254740995",json.get("stopRequestedBy").asString());
        assertTrue(json.get("stopRequestedAt").isNumber());assertEquals("维护停止",json.get("stopReason").asString());
        assertFalse(json.has("leaseToken"));assertFalse(json.has("tenantId"));
    }
}
