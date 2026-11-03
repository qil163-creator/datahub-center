package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.asset.controller.admin.market.MarketMyAssetController;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyPageReqVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只有我的资产权限即可进入本人范围，不借用提供方或资产管理权限。 */
class MarketMyAssetSecurityTest {
    private AnnotationConfigApplicationContext context;
    private MarketMyAssetController controller;
    private MarketMyAssetService service;

    @BeforeEach
    void configure() {
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(MarketMyAssetController.class);
        service = context.getBean(MarketMyAssetService.class);
    }

    @AfterEach
    void close() { SecurityContextHolder.clearContext(); context.close(); }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void ownReadPermissionAloneAdmitsEachEntry(Entry entry) {
        login("asset:market:my-assets:query");
        assertThat(call(entry).getCode()).isZero();
        verify(context.getBean(SecurityFrameworkService.class)).hasPermission("asset:market:my-assets:query");
        verifyNoMoreInteractions(context.getBean(SecurityFrameworkService.class));
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void managementPermissionCannotReplaceOwnReadPermission(Entry entry) {
        login("asset:market:query");
        assertThatThrownBy(() -> call(entry)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }

    private CommonResult<?> call(Entry entry) {
        return switch (entry) {
            case PAGE -> controller.page(new MarketMyAssetPageReqVO());
            case GET -> controller.get(7L);
            case PREVIEW -> controller.preview(7L, 20);
            case DOCUMENT -> controller.apiDocument(7L);
            case APPLICATIONS -> controller.applications(new MarketApplyPageReqVO());
            case APPLICATION -> controller.application(7L);
        };
    }

    private void login(String permission) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser().setId(9L), null, List.of(new SimpleGrantedAuthority(permission))));
    }

    enum Entry { PAGE, GET, PREVIEW, DOCUMENT, APPLICATIONS, APPLICATION }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean("ss")
        SecurityFrameworkService permissionGate() {
            var gate = mock(SecurityFrameworkService.class);
            when(gate.hasPermission(anyString())).thenAnswer(call -> {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                return auth != null && auth.getAuthorities().stream()
                        .anyMatch(authority -> authority.getAuthority().equals(call.getArgument(0, String.class)));
            });
            return gate;
        }

        @Bean MarketMyAssetService service() { return mock(MarketMyAssetService.class); }

        @Bean MarketMyAssetController controller(MarketMyAssetService service) {
            var controller = new MarketMyAssetController();
            ReflectionTestUtils.setField(controller, "service", service); return controller;
        }
    }
}
