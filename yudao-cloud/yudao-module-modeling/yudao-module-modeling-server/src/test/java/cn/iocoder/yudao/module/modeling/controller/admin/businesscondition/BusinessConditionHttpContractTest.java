package cn.iocoder.yudao.module.modeling.controller.admin.businesscondition;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.*;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.config.*;
import cn.iocoder.yudao.module.modeling.controller.admin.businesscondition.vo.*;
import cn.iocoder.yudao.module.modeling.service.businesscondition.BusinessConditionService;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorModelService;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 真实参数绑定、方法权限代理、Long 和时间 JSON 契约；持久领域服务为边界替身。 */
class BusinessConditionHttpContractTest {
    private static final String PREFIX="/admin-api/modeling/business-condition";
    private static final long LARGE_ID=9007199254740993L;
    private AnnotationConfigApplicationContext context;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;
    private JsonMapper json;
    private Set<String> permissions;
    private BusinessConditionService service;
    private IndicatorModelService indicators;
    private long loginUserId = 9L;

    @BeforeEach
    void initialize() {
        permissions=new HashSet<>(Set.of("modeling:business-condition:query"));
        json=JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        context=new AnnotationConfigApplicationContext(SecurityConfig.class);
        var gate=context.getBean(PermissionCommonApi.class);
        when(gate.hasAnyPermissions(anyLong(),any(String[].class))).thenAnswer(i -> {
            String[] requested=(String[])i.getRawArguments()[1];return CommonResult.success(Arrays.stream(requested).anyMatch(permissions::contains));
        });
        service=context.getBean(BusinessConditionService.class);indicators=context.getBean(IndicatorModelService.class);
        validator=new LocalValidatorFactoryBean();validator.afterPropertiesSet();
        mvc=MockMvcBuilders.standaloneSetup(context.getBean(BusinessConditionController.class))
                .setCustomHandlerMapping(()->new YudaoWebAutoConfiguration().webMvcRegistrations(new WebProperties()).getRequestMappingHandlerMapping())
                .setControllerAdvice(new GlobalExceptionHandler("modeling-condition-http-contract",mock(ApiErrorLogCommonApi.class)))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json)).setValidator(validator).build();
        login();
    }

    @Test
    void queryUsesIndependentPermissionAndPreservesLargeIdsAndEpochTime() throws Exception {
        var row=new BusinessConditionRespVO();row.setId(LARGE_ID);row.setSourceModelId(LARGE_ID);row.setCreateTime(LocalDateTime.of(2040,1,1,0,0));
        when(service.get(LARGE_ID)).thenReturn(row);
        var response=request(get(PREFIX+"/get").param("id",Long.toString(LARGE_ID)));
        assertEquals(0,response.get("code").intValue());assertEquals(Long.toString(LARGE_ID),response.get("data").get("id").asString());
        assertTrue(response.get("data").get("createTime").isNumber());verify(service).get(LARGE_ID);
        permissions=Set.of("modeling:indicator-model:query");login();
        assertEquals(403,request(get(PREFIX+"/get").param("id","2")).get("code").intValue());
        verify(service,never()).get(2L);
    }

    @Test
    void sourceEndpointsRequireBothConditionAndModelPermission() throws Exception {
        assertEquals(403,request(get(PREFIX+"/source-fields").param("modelId","5")).get("code").intValue());
        verifyNoInteractions(indicators);
        permissions=Set.of("modeling:business-condition:query","modeling:model:query");login();
        when(indicators.sourceFields(5L)).thenReturn(List.of());
        assertEquals(0,request(get(PREFIX+"/source-fields").param("modelId","5")).get("code").intValue());
        verify(indicators).sourceFields(5L);
    }

    @Test
    void pageBindsSourceConfigAndFreshnessFilters() throws Exception {
        when(service.page(any())).thenReturn(PageResult.empty());
        assertEquals(0,request(get(PREFIX+"/page").param("sourceModelId",Long.toString(LARGE_ID)).param("configType","BASIC")
                .param("dataFreshness","天（T+1）").param("pageNo","2").param("pageSize","10")).get("code").intValue());
        verify(service).page(argThat(r -> r.getSourceModelId()==LARGE_ID&&r.getPageNo()==2&&"BASIC".equals(r.getConfigType())&&"天（T+1）".equals(r.getDataFreshness())));
    }

    @Test
    void writesRequireActionPermissionAndValidateLengthsAndRevision() throws Exception {
        String body="{\"code\":\"valid_order\",\"name\":\"有效订单\",\"dataDomainId\":1,\"entityId\":7,\"sourceModelId\":5,\"configType\":\"BASIC\",\"logicMode\":\"UNIFIED\",\"expression\":\"status=1\"}";
        assertEquals(403,request(post(PREFIX+"/create").contentType(MediaType.APPLICATION_JSON).content(body)).get("code").intValue());
        permissions=Set.of("modeling:business-condition:create","modeling:business-condition:update");login();
        when(service.create(any())).thenReturn(LARGE_ID);
        assertEquals(Long.toString(LARGE_ID),request(post(PREFIX+"/create").contentType(MediaType.APPLICATION_JSON).content(body)).get("data").asString());
        String oversized=body.replace("status=1","x".repeat(4001));
        assertEquals(400,request(post(PREFIX+"/create").contentType(MediaType.APPLICATION_JSON).content(oversized)).get("code").intValue());
        assertEquals(400,request(put(PREFIX+"/update-status").contentType(MediaType.APPLICATION_JSON).content("{\"id\":1,\"status\":\"ACTIVE\"}")).get("code").intValue());
        verify(service,times(1)).create(any());verify(service,never()).updateStatus(any());
    }

    private JsonNode request(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    // 变更测试权限时使用新的身份，避免生产权限缓存沿用同一用户先前的判断。
    private void login(){SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new LoginUser().setId(loginUserId++),null,List.of()));}
    @AfterEach void close(){SecurityContextHolder.clearContext();if(validator!=null)validator.close();if(context!=null)context.close();}
    @Configuration(proxyBeanMethods=false)
    @EnableMethodSecurity(proxyTargetClass=true)
    static class SecurityConfig {
        @Bean static MethodValidationPostProcessor methodValidation(){var p=new MethodValidationPostProcessor();p.setProxyTargetClass(true);return p;}
        @Bean PermissionCommonApi permissionApi(){return mock(PermissionCommonApi.class);}
        @Bean("ss") SecurityFrameworkService security(PermissionCommonApi api){return new SecurityFrameworkServiceImpl(api);}
        @Bean BusinessConditionService service(){return mock(BusinessConditionService.class);}
        @Bean IndicatorModelService indicators(){return mock(IndicatorModelService.class);}
        @Bean BusinessConditionController controller(){return new BusinessConditionController();}
    }
}
