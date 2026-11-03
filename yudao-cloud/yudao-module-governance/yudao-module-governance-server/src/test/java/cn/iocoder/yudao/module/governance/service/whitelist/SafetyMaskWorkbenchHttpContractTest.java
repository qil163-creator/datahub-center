package cn.iocoder.yudao.module.governance.service.whitelist;
import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.pojo.*;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.*;
import cn.iocoder.yudao.framework.security.core.service.*;
import cn.iocoder.yudao.framework.web.config.*;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.governance.controller.admin.rule.*;
import cn.iocoder.yudao.module.governance.controller.admin.rule.vo.*;
import cn.iocoder.yudao.module.governance.controller.admin.whitelist.*;
import cn.iocoder.yudao.module.governance.controller.admin.whitelist.vo.*;
import cn.iocoder.yudao.module.governance.service.rule.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class SafetyMaskWorkbenchHttpContractTest {

    private static final String RULE = "/admin-api/governance/safety-rule/workbench", WHITE = "/admin-api/governance/safety-whitelist/workbench";

    private AnnotationConfigApplicationContext ctx;

    private MockMvc mvc;

    private JsonMapper json;

    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setup() {
        json = JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        ctx = new AnnotationConfigApplicationContext();
        ctx.getBeanFactory().registerSingleton("rules", mock(SafetyRuleWorkbenchService.class));
        ctx.getBeanFactory().registerSingleton("whites", mock(SafetyWhitelistWorkbenchService.class));
        ctx.register(Config.class);
        ctx.refresh();
        when(ctx.getBean(PermissionCommonApi.class).hasAnyPermissions(anyLong(), any(String[].class))).thenAnswer(
                call -> {
                    Long actor = call.getArgument(0);
                    String[] perms = (String[]) call.getRawArguments()[1];
                    String suffix = actor == 9 ? ":query" : actor == 10 ? ":update" : actor == 11 ? ":create" : actor == 12 ? ":delete" : "none";
                    return CommonResult.success(Arrays.stream(perms).anyMatch(v -> v.endsWith(suffix)));
                });
        login(9);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(ctx.getBean(SafetyRuleWorkbenchController.class),
                        ctx.getBean(SafetyWhitelistWorkbenchController.class))
                .setCustomHandlerMapping(() -> new YudaoWebAutoConfiguration().webMvcRegistrations(new WebProperties())
                        .getRequestMappingHandlerMapping())
                .setControllerAdvice(new GlobalExceptionHandler("mask-workbench", mock(ApiErrorLogCommonApi.class)))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json)).setValidator(validator).build();
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        validator.close();
        ctx.close();
    }

    @Test
    void queryReadsBothOptionsButCannotWriteOrReadAssetsThroughFrontendOnlyPermissions() throws Exception {
        assertThat(request(get(RULE + "/options")).get("code").intValue()).isZero();
        assertThat(request(get(WHITE + "/options")).get("code").intValue()).isZero();
        assertThat(request(post(WHITE + "/delete").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"revision\":1}")).get("code").intValue()).isEqualTo(403);
        verify(ctx.getBean(SafetyWhitelistWorkbenchService.class), never()).delete(any());
    }

    @Test
    void actualWireKeepsLosslessIdentityTimestampAndDateOnlyWithoutSourceActorBecomingLocalUser() throws Exception {
        var row = new SafetyWhitelistWorkbenchRespVO();
        row.setId(9007199254740993L);
        row.setRevision(9007199254740995L);
        row.setSourceRecordId("2");
        row.setStartTime(LocalDate.of(2026, 8, 4));
        row.setCreateTime(LocalDateTime.of(2026, 8, 4, 19, 33, 31));
        row.setUserIds(List.of(1L));
        when(ctx.getBean(SafetyWhitelistWorkbenchService.class).get(row.getId())).thenReturn(row);
        var response = request(get(WHITE + "/get").param("id", "9007199254740993")).get("data");
        assertThat(response.get("id").asString()).isEqualTo("9007199254740993");
        assertThat(response.get("revision").asString()).isEqualTo("9007199254740995");
        assertThat(response.get("startTime").asString()).isEqualTo("2026-08-04");
        assertThat(response.get("createTime").isNumber()).isTrue();
        assertThat(response.get("userIds").get(0).intValue()).isEqualTo(1);
    }

    @Test
    void malformedPolicySelectionsAndMissingCASAreRejectedBeforeWriter() throws Exception {
        login(11);
        String good = "{\"name\":\"测试白名单\",\"categoryId\":25,\"effectiveCategory\":\"1\",\"userIds\":[1],\"startTime\":\"2026-08-04\",\"endTime\":\"2099-12-31\",\"status\":1}";
        for (String[] invalid : List.of(new String[]{"effectiveCategory", "\"2\""}, new String[]{"name", "\"带 空格\""},
                new String[]{"userIds", "[]"}, new String[]{"userIds", "[null]"}, new String[]{"status", "2"})) {
            var tree = (tools.jackson.databind.node.ObjectNode) json.readTree(good);
            tree.set(invalid[0], json.readTree(invalid[1]));
            assertThat(request(post(WHITE + "/create").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(tree))).get("code").intValue()).isEqualTo(400);
        }
        login(10);
        assertThat(request(put(WHITE + "/change-status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":1,\"status\":0}")).get("code").intValue()).isEqualTo(400);
        verifyNoInteractions(ctx.getBean(SafetyWhitelistWorkbenchService.class));
    }

    @Test
    void sensitiveAndAccountSearchStayBoundedAndSortIsWhitelisted() throws Exception {
        assertThat(request(get(RULE + "/sensitive-page").param("ruleId", "0")).get("code").intValue()).isEqualTo(400);
        assertThat(request(get(WHITE + "/page").param("sortField", "id;drop table")).get("code").intValue()).isEqualTo(
                400);
        assertThat(request(get(WHITE + "/user-options").param("pageSize", "101")).get("code").intValue()).isEqualTo(
                400);
        assertThat(request(delete(WHITE + "/delete").param("id", "1").param("revision", "1")).get("code")
                .intValue()).isEqualTo(405);
    }

    @Test void accountLookupUsesOnlyDedicatedAuthenticatedClientAndNeverFallsBackToLegacyUsers(){
  var service=new SafetyWhitelistWorkbenchService();
  var options=mock(cn.iocoder.yudao.module.system.api.user.AdminUserOptionApi.class);
  var legacy=mock(cn.iocoder.yudao.module.system.api.user.AdminUserApi.class);
  org.springframework.test.util.ReflectionTestUtils.setField(service,"userOptionsApi",options);
  org.springframework.test.util.ReflectionTestUtils.setField(service,"users",legacy);
  var req=new cn.iocoder.yudao.module.system.api.user.dto.AdminUserOptionPageReqDTO();req.setUsername("local");
  when(options.getUserOptionPage(req)).thenReturn(CommonResult.success(PageResult.empty()));
  assertThat(service.userOptions(req).getList()).isEmpty();
  when(options.getUserOptionPage(req)).thenThrow(new IllegalStateException("认证通道不可用"));
  assertThatThrownBy(()->service.userOptions(req)).isInstanceOf(IllegalStateException.class);
  verifyNoInteractions(legacy);
 }
 private JsonNode request(MockHttpServletRequestBuilder req) throws Exception {
        var response = mvc.perform(req).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private static void login(long id) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(new LoginUser().setId(id), null, List.of()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(proxyTargetClass = true)
    static class Config {

        @Bean
        static MethodValidationPostProcessor validation() {
            var p = new MethodValidationPostProcessor();
            p.setProxyTargetClass(true);
            return p;
        }

        @Bean
        PermissionCommonApi permissions() {
            return mock(PermissionCommonApi.class);
        }

        @Bean("ss")
        SecurityFrameworkService ss(PermissionCommonApi p) {
            return new SecurityFrameworkServiceImpl(p);
        }

        @Bean
        SafetyRuleWorkbenchController ruleController() {
            return new SafetyRuleWorkbenchController();
        }

        @Bean
        SafetyWhitelistWorkbenchController whiteController() {
            return new SafetyWhitelistWorkbenchController();
        }
    }
}
