package cn.iocoder.yudao.module.governance.service.category;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkServiceImpl;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.config.YudaoWebAutoConfiguration;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.governance.controller.admin.category.SafetyCategoryController;
import cn.iocoder.yudao.module.governance.controller.admin.category.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.category.SafetyCategoryDO;
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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 真实方法权限代理与 HTTP 校验，验证查询选项不依赖额外管理权限。 */
class SafetyCategoryHttpContractTest {
    private static final String PREFIX = "/admin-api/governance/safety-category";
    private AnnotationConfigApplicationContext context;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;
    private JsonMapper json;
    private SafetyCategoryService service;
    private SafetyCategoryMaskService masks;

    @BeforeEach void configure() {
        json = JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        context = new AnnotationConfigApplicationContext(Config.class);
        service = context.getBean(SafetyCategoryService.class); masks = context.getBean(SafetyCategoryMaskService.class);
        when(context.getBean(PermissionCommonApi.class).hasAnyPermissions(anyLong(), any(String[].class))).thenAnswer(call -> {
            long actor = call.getArgument(0); String[] values = (String[]) call.getRawArguments()[1];
            String required = actor == 9 ? "governance:category:query" : actor == 10 ? "governance:category:update" : "none";
            return CommonResult.success(Arrays.asList(values).contains(required));
        });
        login(9);
        when(service.getCategoryPage(any())).thenReturn(PageResult.empty());
        var row = SafetyCategoryDO.builder().id(9007199254740993L).revision(9007199254740995L).sourceRecordId("56").build();
        row.setCreateTime(LocalDateTime.of(2026, 7, 29, 19, 35, 9)); when(service.getCategory(row.getId())).thenReturn(row);
        var mask = new SafetyCategoryMaskRespVO(); mask.setId(1L); mask.setRuleVersionId(9007199254740993L); mask.setRuleRevisionToken("a".repeat(64));
        when(masks.get(1L)).thenReturn(mask);
        validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(SafetyCategoryController.class))
                .setCustomHandlerMapping(() -> new YudaoWebAutoConfiguration().webMvcRegistrations(new WebProperties()).getRequestMappingHandlerMapping())
                .setControllerAdvice(new GlobalExceptionHandler("classification-http", mock(ApiErrorLogCommonApi.class)))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json)).setValidator(validator).build();
    }

    @AfterEach void close() { SecurityContextHolder.clearContext(); if (validator != null) validator.close(); if (context != null) context.close(); }

    @Test void queryOnlyReadsAllOptionsAndConfigurationWithLosslessIdsAndDefaultTimestampWire() throws Exception {
        assertThat(request(get(PREFIX + "/page")).get("code").intValue()).isZero();
        assertThat(request(get(PREFIX + "/options")).get("code").intValue()).isZero();
        assertThat(request(get(PREFIX + "/mask").param("id", "1")).get("code").intValue()).isZero();
        var masking = request(get(PREFIX + "/mask").param("id", "1")).get("data");
        assertThat(masking.get("ruleVersionId").asString()).isEqualTo("9007199254740993");
        assertThat(masking.get("ruleRevisionToken").asString()).isEqualTo("a".repeat(64));
        var response = request(get(PREFIX + "/get").param("id", "9007199254740993")).get("data");
        assertThat(response.get("id").asString()).isEqualTo("9007199254740993");
        assertThat(response.get("revision").asString()).isEqualTo("9007199254740995");
        assertThat(response.get("sourceRecordId").asString()).isEqualTo("56");
        assertThat(response.get("createTime").isNumber()).isTrue();
    }

    @Test void queryCannotWriteAndUpdateDoesNotGrantDeleteOrUnrelatedDefinitionCreation() throws Exception {
        assertThat(request(put(PREFIX + "/change-status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"revision\":1,\"status\":0}")).get("code").intValue()).isEqualTo(403);
        login(10);
        assertThat(request(put(PREFIX + "/change-status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"revision\":1,\"status\":0}")).get("code").intValue()).isZero();
        assertThat(request(delete(PREFIX + "/delete").param("id", "1").param("revision", "1")).get("code").intValue()).isEqualTo(403);
        verify(service).changeStatus(any()); verify(service, never()).deleteCategory(anyLong(), anyLong());
    }

    @Test void unsupportedExecutionModesAndInvalidRangesAreRejectedBeforeMaskService() throws Exception {
        login(10);
        for (String[] invalid : List.of(new String[]{"applicationScene", "[\"3\"]"}, new String[]{"maskType", "\"1\""},
                new String[]{"replaceRule", "\"2\""}, new String[]{"revision", "0"}, new String[]{"intervalList", "[]"},
                new String[]{"intervalList", "[{\"startNum\":0,\"endNum\":1}]"}, new String[]{"intervalList", "[null]"},
                new String[]{"applicationScene", "[null]"})) {
            String body = "{\"id\":1,\"revision\":1,\"ruleName\":\"展示\",\"applicationScene\":[\"1\",\"2\"],\"maskType\":\"2\","
                    + "\"replaceRule\":\"1\",\"replaceContent\":\"*\",\"intervalList\":[{\"startNum\":1,\"endNum\":1}],\"status\":1}";
            var parsed = json.readTree(body).deepCopy();
            ((tools.jackson.databind.node.ObjectNode) parsed).set(invalid[0], json.readTree(invalid[1]));
            assertThat(request(put(PREFIX + "/mask").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(parsed)))
                    .get("code").intValue()).isEqualTo(400);
        }
        verifyNoInteractions(masks);
    }

    @Test void sortInjectionOversizedBatchAndWrongVerbsCannotReachTheService() throws Exception {
        assertThat(request(get(PREFIX + "/page").param("sortField", "id;drop table")).get("code").intValue()).isEqualTo(400);
        assertThat(request(get(PREFIX + "/get").param("id", "0")).get("code").intValue()).isEqualTo(400);
        assertThat(request(delete(PREFIX + "/delete-list").param("ids", "1")).get("code").intValue()).isEqualTo(405);
        login(10);
        assertThat(request(put(PREFIX + "/batch-level").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[],\"dataLevelId\":1}")).get("code").intValue()).isEqualTo(400);
        verifyNoInteractions(service);
    }

    private JsonNode request(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200); return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }
    private static void login(long id) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new LoginUser().setId(id), null, List.of()));
    }
    @Configuration(proxyBeanMethods = false) @EnableMethodSecurity(proxyTargetClass = true)
    static class Config {
        @Bean static MethodValidationPostProcessor methodValidation() { var processor = new MethodValidationPostProcessor(); processor.setProxyTargetClass(true); return processor; }
        @Bean PermissionCommonApi permissionApi() { return mock(PermissionCommonApi.class); }
        @Bean("ss") SecurityFrameworkService security(PermissionCommonApi api) { return new SecurityFrameworkServiceImpl(api); }
        @Bean SafetyCategoryService service() { return mock(SafetyCategoryService.class); }
        @Bean cn.iocoder.yudao.module.governance.dal.mysql.categorydata.CategoryDataMapper directories() { return mock(cn.iocoder.yudao.module.governance.dal.mysql.categorydata.CategoryDataMapper.class); }
        @Bean cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper levels() { return mock(cn.iocoder.yudao.module.governance.dal.mysql.level.SafetyLevelMapper.class); }
        @Bean cn.iocoder.yudao.module.governance.dal.mysql.rule.SafetyRuleMapper rulesMapper() { return mock(cn.iocoder.yudao.module.governance.dal.mysql.rule.SafetyRuleMapper.class); }
        @Bean cn.iocoder.yudao.module.system.api.user.AdminUserApi users() { return mock(cn.iocoder.yudao.module.system.api.user.AdminUserApi.class); }
        @Bean cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper categoryMapper() { return mock(cn.iocoder.yudao.module.governance.dal.mysql.category.SafetyCategoryMapper.class); }
        @Bean cn.iocoder.yudao.module.governance.service.rule.SafetyRuleService ruleServiceMock() { return mock(cn.iocoder.yudao.module.governance.service.rule.SafetyRuleService.class); }
        @Bean SafetyCategoryDisplayService display() { return mock(SafetyCategoryDisplayService.class); }
        @Bean SafetyCategoryMaskService masks() { return mock(SafetyCategoryMaskService.class); }
        @Bean SafetyCategoryController controller() { return new SafetyCategoryController(); }
    }
}
