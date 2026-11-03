package cn.iocoder.yudao.module.system.api.user;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.framework.security.core.service.*;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.system.api.user.dto.*;
import cn.iocoder.yudao.module.system.framework.security.config.*;
import cn.iocoder.yudao.module.system.framework.security.core.AdminUserOptionRpcFilter;
import cn.iocoder.yudao.module.system.service.user.AdminUserService;
import cn.iocoder.yudao.module.system.service.dept.DeptService;
import feign.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.AopTestUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.json.JsonMapper;
import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AdminUserOptionRpcSecurityTest {
    private AnnotationConfigApplicationContext context;
    private LocalValidatorFactoryBean validator;
    @AfterEach void close(){SecurityContextHolder.clearContext();if(validator!=null)validator.close();if(context!=null)context.close();}

    @Test void forgedHeadersAndOldAliasCannotReachCandidates()throws Exception {
        var request=new MockHttpServletRequest();request.setSecure(true);
        request.addHeader("login-user","{\"id\":1}");request.addHeader("data-permission-enable","false");
        request.addHeader("X-Forwarded-Client-Cert","governance");
        var response=new MockHttpServletResponse();
        new AdminUserOptionRpcFilter(Map::of).doFilter(request,response,(a,b)->{throw new AssertionError("未认证通道");});
        assertThat(response.getStatus()).isEqualTo(401);
        var api=new AdminUserOptionApiImpl();var users=mock(AdminUserService.class);
        ReflectionTestUtils.setField(api,"servletRequest",request);ReflectionTestUtils.setField(api,"userService",users);
        assertThatThrownBy(()->api.getUserOptionPage(new AdminUserOptionPageReqDTO())).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(users);
        assertThat(Arrays.stream(AdminUserApi.class.getMethods()).map(m->m.getName())).doesNotContain("getUserOptionPage");
        assertThat(Arrays.stream(AdminUserApiImpl.class.getMethods()).map(m->m.getName())).doesNotContain("getUserOptionPage");
    }

    @Test void physicalTlsRequiresExactGovernanceCertificateAndFilterRegistrationIsNarrow()throws Exception {
        var cert=certificate();String pin=VerifiedServicePeerResolver.fingerprint(cert);
        for(boolean tls:List.of(false,true))for(String peer:List.of("governance","asset")){
            var request=new MockHttpServletRequest();request.setSecure(tls);request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE,new X509Certificate[]{cert});
            var reached=new AtomicBoolean();new AdminUserOptionRpcFilter(()->Map.of(peer,Set.of(pin))).doFilter(request,new MockHttpServletResponse(),(a,b)->reached.set(true));
            assertThat(reached.get()).isEqualTo(tls&&peer.equals("governance"));
        }
        var properties=new AdminUserOptionRpcProperties();properties.validate();assertThat(properties.getPeers()).isEmpty();
        var filter=new AdminUserOptionRpcConfiguration().adminUserOptionRpcFilter(properties);
        assertThat(filter.getUrlPatterns()).containsExactly(AdminUserOptionApi.PREFIX+"/page");
        assertThat(filter.getOrder()).isLessThan(WebFilterOrderEnum.TENANT_CONTEXT_FILTER-1);
        properties.setPeers(Map.of("asset",Set.of(pin)));assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void authenticatedServiceStillRequiresRealActorPermissionAndBoundedAccountPage()throws Exception {
        context=new AnnotationConfigApplicationContext(Config.class);var controller=context.getBean(AdminUserOptionApiImpl.class);
        var users=context.getBean(AdminUserService.class);when(users.getUserPage(any())).thenReturn(PageResult.empty());
        when(context.getBean(PermissionCommonApi.class).hasAnyPermissions(anyLong(),any(String[].class))).thenAnswer(call->CommonResult.success(Objects.equals(call.getArgument(0),9L)));
        var cert=certificate();String pin=VerifiedServicePeerResolver.fingerprint(cert);
        validator=new LocalValidatorFactoryBean();validator.afterPropertiesSet();
        var json=JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        var mvc=MockMvcBuilders.standaloneSetup(controller).addFilters(new AdminUserOptionRpcFilter(()->Map.of("governance",Set.of(pin))))
            .addInterceptors(new HandlerInterceptor(){@Override public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler){
                Object target=AopTestUtils.getUltimateTargetObject(controller);ReflectionTestUtils.setField(target,"servletRequest",req);return true;
            }})
            .setControllerAdvice(new GlobalExceptionHandler("option-rpc",mock(ApiErrorLogCommonApi.class)))
            .setMessageConverters(new JacksonJsonHttpMessageConverter(json)).setValidator(validator).build();
        for(long actor:List.of(8L,9L)){
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new LoginUser().setId(actor),null,List.of()));
            var outcome=mvc.perform(get(AdminUserOptionApi.PREFIX+"/page").secure(true).requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE,new X509Certificate[]{cert})
                .param("username","local").param("mobile","138").param("pageNo","2").param("pageSize","20")).andReturn();
            var response=outcome.getResponse();
            assertThat(response.getStatus()).isEqualTo(200);assertThat(json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).get("code").intValue()).withFailMessage("真实异常：%s / %s",outcome.getResolvedException(),outcome.getResolvedException()==null?null:outcome.getResolvedException().getCause()).isEqualTo(actor==9?0:403);
        }
        verify(users,times(1)).getUserPage(argThat(q->"local".equals(q.getUsername())&&"138".equals(q.getMobile())&&Objects.equals(q.getStatus(),0)&&Objects.equals(q.getPageNo(),2)));
        var oversized=mvc.perform(get(AdminUserOptionApi.PREFIX+"/page").secure(true).requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE,new X509Certificate[]{cert}).param("pageSize","101")).andReturn().getResponse();
        assertThat(json.readTree(oversized.getContentAsString(StandardCharsets.UTF_8)).get("code").intValue()).isEqualTo(400);
    }

    @Test void dedicatedFeignContextRejectsMissingBundleAndHttpWithoutChangingLegacyUserApi()throws Exception {
        var annotation=AdminUserOptionApi.class.getAnnotation(org.springframework.cloud.openfeign.FeignClient.class);
        assertThat(annotation.contextId()).isEqualTo("adminUserOptionApi");assertThat(annotation.configuration()).containsExactly(AdminUserOptionClientConfiguration.class);
        assertThat(AdminUserApi.class.getAnnotation(org.springframework.cloud.openfeign.FeignClient.class).configuration()).isEmpty();
        var config=new AdminUserOptionClientConfiguration();var env=mock(Environment.class);
        Client missing=config.adminUserOptionClient(env,provider(null),provider(null),provider(null),provider(null));
        var https=Request.create(Request.HttpMethod.GET,"https://localhost:1/test",Map.of(),new byte[0],StandardCharsets.UTF_8);
        assertThatThrownBy(()->missing.execute(https,config.adminUserOptionOptions())).isInstanceOf(IllegalStateException.class);
        when(env.getProperty(AdminUserOptionClientConfiguration.SSL_BUNDLE_PROPERTY)).thenReturn("fixture");
        var bundles=mock(SslBundles.class);var bundle=mock(SslBundle.class);when(bundles.getBundle("fixture")).thenReturn(bundle);when(bundle.createSslContext()).thenReturn(SSLContext.getDefault());
        Client configured=config.adminUserOptionClient(env,provider(bundles),provider(null),provider(null),provider(null));
        var http=Request.create(Request.HttpMethod.GET,"http://localhost:1/test",Map.of(),new byte[0],StandardCharsets.UTF_8);
        assertThatThrownBy(()->configured.execute(http,config.adminUserOptionOptions())).isInstanceOf(IllegalStateException.class);
        assertThat(config.adminUserOptionRetryer()).isSameAs(Retryer.NEVER_RETRY);assertThat(config.adminUserOptionOptions().isFollowRedirects()).isFalse();
    }
    private static X509Certificate certificate()throws Exception{var cert=mock(X509Certificate.class);when(cert.getEncoded()).thenReturn("fixture-governance-option-peer".getBytes(StandardCharsets.UTF_8));return cert;}
    @SuppressWarnings("unchecked")private static <T>ObjectProvider<T> provider(T value){var p=mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(value);when(p.orderedStream()).thenAnswer(c->Stream.empty());return p;}
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity(proxyTargetClass=true) static class Config {
        @Bean PermissionCommonApi permissions(){return mock(PermissionCommonApi.class);}
        @Bean("ss")SecurityFrameworkService ss(PermissionCommonApi p){return new SecurityFrameworkServiceImpl(p);}
        @Bean AdminUserService users(){return mock(AdminUserService.class);}
        @Bean DeptService departments(){return mock(DeptService.class);}
        @Bean HttpServletRequest request(){return new MockHttpServletRequest();}
        @Bean AdminUserOptionApiImpl controller(){return new AdminUserOptionApiImpl();}
    }
}
