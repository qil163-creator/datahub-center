package cn.iocoder.yudao.module.modeling.controller.admin.indicator;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.*;
import cn.iocoder.yudao.framework.web.config.*;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.*;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorModelService;
import cn.iocoder.yudao.module.modeling.service.indicator.IndicatorQueryService;
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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 指标查询与公式校验接口的独立权限、请求校验和十进制字符串契约。 */
class IndicatorQueryHttpContractTest {

    private static final String PREFIX = "/admin-api/modeling/indicator-model";
    private static final long LARGE_ID = 9007199254740993L;
    private AnnotationConfigApplicationContext context;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;
    private JsonMapper json;
    private Set<String> permissions;
    private IndicatorQueryService queries;
    private IndicatorModelService indicators;
    private long loginUserId = 30L;

    @BeforeEach
    void initialize() {
        permissions = new HashSet<>(Set.of("modeling:indicator-model:query"));
        json = JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        context = new AnnotationConfigApplicationContext(SecurityConfig.class);
        var gate = context.getBean(PermissionCommonApi.class);
        when(gate.hasAnyPermissions(anyLong(), any(String[].class))).thenAnswer(invocation -> {
            String[] requested = (String[]) invocation.getRawArguments()[1];
            return CommonResult.success(Arrays.stream(requested).anyMatch(permissions::contains));
        });
        queries = context.getBean(IndicatorQueryService.class);
        indicators = context.getBean(IndicatorModelService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(IndicatorQueryController.class),
                        context.getBean(IndicatorModelController.class))
                .setCustomHandlerMapping(() -> new YudaoWebAutoConfiguration().webMvcRegistrations(new WebProperties())
                        .getRequestMappingHandlerMapping())
                .setControllerAdvice(new GlobalExceptionHandler("modeling-indicator-query-http-contract",
                        mock(ApiErrorLogCommonApi.class)))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json)).setValidator(validator).build();
        login();
    }

    @Test
    void dataQueryRequiresDedicatedPermissionNotDefinitionQuery() throws Exception {
        String body = "{\"indicatorIds\":[" + LARGE_ID + "],\"dimensions\":[\"region-uid\"],\"timeGrain\":\"MONTH\","
                + "\"timeRange\":{\"start\":\"2026-01-01\",\"end\":\"2026-03-31\"},\"limit\":100}";
        assertEquals(403, request(post(PREFIX + "/query").contentType(MediaType.APPLICATION_JSON).content(body))
                .get("code").intValue());
        assertEquals(403, request(post(PREFIX + "/query-meta").contentType(MediaType.APPLICATION_JSON)
                .content("{\"indicatorIds\":[1]}")).get("code").intValue());
        verifyNoInteractions(queries);

        permissions = Set.of("modeling:indicator-model:data-query");
        login();
        var response = new IndicatorQueryRespVO();
        response.setRows(List.of(Map.of("ind_" + LARGE_ID, "9007199254740993.123456789012")));
        var column = new IndicatorQueryRespVO.Column();
        column.setIndicatorId(LARGE_ID);
        response.setColumns(List.of(column));
        when(queries.query(any())).thenReturn(response);
        JsonNode result = request(post(PREFIX + "/query").contentType(MediaType.APPLICATION_JSON).content(body));
        assertEquals(0, result.get("code").intValue());
        assertTrue(result.get("data").get("rows").get(0).get("ind_" + LARGE_ID).isString());
        assertEquals("9007199254740993.123456789012",
                result.get("data").get("rows").get(0).get("ind_" + LARGE_ID).asString());
        assertEquals(Long.toString(LARGE_ID), result.get("data").get("columns").get(0).get("indicatorId").asString());
        verify(queries).query(argThat(request -> request.getIndicatorIds().equals(List.of(LARGE_ID))
                && "MONTH".equals(request.getTimeGrain()) && request.getLimit() == 100
                && "2026-03-31".equals(request.getTimeRange().getEnd())));
    }

    @Test
    void queryRequestLimitsAreValidated() throws Exception {
        permissions = Set.of("modeling:indicator-model:data-query");
        login();
        List<String> invalid = List.of(
                "{\"indicatorIds\":[]}",
                "{\"indicatorIds\":[1,2,3,4,5,6,7,8,9,10,11]}",
                "{\"indicatorIds\":[1],\"limit\":1001}",
                "{\"indicatorIds\":[1],\"limit\":0}",
                "{\"indicatorIds\":[1],\"timeGrain\":\"HOUR\"}",
                "{\"indicatorIds\":[1],\"timeRange\":{\"start\":\"2026/01/01\",\"end\":\"2026-01-02\"}}",
                "{\"indicatorIds\":[1],\"filters\":[{\"fieldUid\":\"a\"}]}",
                "{\"indicatorIds\":[1],\"dimensions\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}");
        for (String body : invalid) {
            assertEquals(400, request(post(PREFIX + "/query").contentType(MediaType.APPLICATION_JSON).content(body))
                    .get("code").intValue(), body);
        }
        verifyNoInteractions(queries);
    }

    @Test
    void formulaValidationUsesDefinitionQueryPermission() throws Exception {
        var valid = new IndicatorFormulaValidateRespVO();
        valid.setValid(true);
        when(indicators.validateFormula(any())).thenReturn(valid);
        String body = "{\"dataDomainId\":1,\"code\":\"avg_price\",\"formula\":\"{gmv} / {order_cnt}\"}";
        assertTrue(request(post(PREFIX + "/validate-formula").contentType(MediaType.APPLICATION_JSON).content(body))
                .get("data").get("valid").asBoolean());
        assertEquals(400, request(post(PREFIX + "/validate-formula").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dataDomainId\":1,\"formula\":\"" + "1".repeat(2001) + "\"}")).get("code").intValue());
        permissions = Set.of("modeling:indicator-model:data-query");
        login();
        assertEquals(403, request(post(PREFIX + "/validate-formula").contentType(MediaType.APPLICATION_JSON)
                .content(body)).get("code").intValue());
        when(indicators.activeOptions(null)).thenReturn(List.of());
        assertEquals(0, request(get(PREFIX + "/active-options")).get("code").intValue());
        permissions = Set.of("modeling:model:query");
        login();
        assertEquals(403, request(get(PREFIX + "/active-options")).get("code").intValue());
        verify(indicators, times(1)).validateFormula(any());
        verify(indicators, times(1)).activeOptions(null);
    }

    @Test
    void compositeTypeIsAcceptedBySaveContract() throws Exception {
        permissions = Set.of("modeling:indicator-model:create");
        login();
        when(indicators.create(any())).thenReturn(LARGE_ID);
        String body = "{\"code\":\"avg_price\",\"name\":\"客单价\",\"indicatorType\":\"COMPOSITE\",\"dataDomainId\":1,"
                + "\"formula\":\"{gmv} / {order_cnt}\"}";
        assertEquals(Long.toString(LARGE_ID), request(post(PREFIX + "/create").contentType(MediaType.APPLICATION_JSON)
                .content(body)).get("data").asString());
        verify(indicators).create(argThat(request -> "COMPOSITE".equals(request.getIndicatorType())
                && "{gmv} / {order_cnt}".equals(request.getFormula())));
    }

    private JsonNode request(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // 变更测试权限时使用新的身份，避免生产权限缓存沿用同一用户先前的判断。
    private void login() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser().setId(loginUserId++), null, List.of()));
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        if (validator != null) {
            validator.close();
        }
        if (context != null) {
            context.close();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(proxyTargetClass = true)
    static class SecurityConfig {
        @Bean
        static MethodValidationPostProcessor methodValidation() {
            var processor = new MethodValidationPostProcessor();
            processor.setProxyTargetClass(true);
            return processor;
        }

        @Bean
        PermissionCommonApi permissionApi() {
            return mock(PermissionCommonApi.class);
        }

        @Bean("ss")
        SecurityFrameworkService security(PermissionCommonApi api) {
            return new SecurityFrameworkServiceImpl(api);
        }

        @Bean
        IndicatorQueryService queries() {
            return mock(IndicatorQueryService.class);
        }

        @Bean
        IndicatorModelService indicators() {
            return mock(IndicatorModelService.class);
        }

        @Bean
        IndicatorQueryController queryController() {
            return new IndicatorQueryController();
        }

        @Bean
        IndicatorModelController modelController() {
            return new IndicatorModelController();
        }
    }
}
