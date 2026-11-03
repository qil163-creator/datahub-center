package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.DataAssetMapPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.DataAssetController;
import cn.iocoder.yudao.module.asset.service.dataasset.DataAssetService;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.asset.controller.admin.market.MarketAccessController;
import cn.iocoder.yudao.module.asset.controller.admin.market.MarketCategoryController;
import cn.iocoder.yudao.module.asset.controller.admin.market.MarketMartController;
import cn.iocoder.yudao.module.asset.controller.admin.market.MarketResourceController;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;
import cn.iocoder.yudao.module.supply.api.app.SupplyQuotaApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MarketCatalogReadSecurityTest {

    private AnnotationConfigApplicationContext context;
    private MarketMartController mart;
    private MarketResourceController resource;
    private MarketCategoryController category;
    private MarketAccessController access;
    private DataAssetController map;
    private MarketMartService martService;
    private MarketResourceService resourceService;
    private MarketCategoryService categoryService;
    private MarketAccessService accessService;
    private DataAssetService mapService;
    private SecurityFrameworkService permissionGate;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        mart = context.getBean(MarketMartController.class);
        resource = context.getBean(MarketResourceController.class);
        category = context.getBean(MarketCategoryController.class);
        access = context.getBean(MarketAccessController.class);
        map = context.getBean(DataAssetController.class);
        martService = context.getBean(MarketMartService.class);
        resourceService = context.getBean(MarketResourceService.class);
        categoryService = context.getBean(MarketCategoryService.class);
        accessService = context.getBean(MarketAccessService.class);
        mapService = context.getBean(DataAssetService.class);
        permissionGate = context.getBean(SecurityFrameworkService.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @ParameterizedTest
    @EnumSource(CatalogRead.class)
    void authenticatedUsersCanReadCatalogWithoutQueryPermissions(CatalogRead entry) {
        loginWithoutPermissions();

        assertThat(read(entry).getCode()).isZero();

        verifyNoInteractions(permissionGate);
        verifyReadDelegation(entry);
    }

    @ParameterizedTest
    @EnumSource(CatalogRead.class)
    void anonymousAuthenticationCannotReadCatalog(CatalogRead entry) {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "fixture", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(() -> read(entry)).isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(martService, resourceService, categoryService, accessService, mapService);
    }

    @ParameterizedTest
    @EnumSource(CatalogRead.class)
    void missingAuthenticationCannotReadCatalog(CatalogRead entry) {
        assertThatThrownBy(() -> read(entry)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);

        verifyNoInteractions(martService, resourceService, categoryService, accessService, mapService);
    }

    @ParameterizedTest
    @EnumSource(RestrictedAction.class)
    void catalogAdmissionDoesNotGrantManagementOrConsumptionActions(RestrictedAction action) {
        loginWithoutPermissions();

        assertThatThrownBy(() -> restricted(action)).isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(martService, resourceService, categoryService, accessService, mapService);
    }

    private void loginWithoutPermissions() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(new LoginUser().setId(9L), null, List.of()));
    }

    private CommonResult<?> read(CatalogRead entry) {
        return switch (entry) {
            case MART_TOPICS -> mart.getTopics();
            case MART_PAGE -> mart.getPage(new MarketPageReqVO());
            case MART_STATISTICS -> mart.getStatistics();
            case RESOURCE_PAGE -> resource.getPublishedPage(new DataAssetPageReqVO());
            case RESOURCE_DETAIL -> resource.getPublishedDetail(7L);
            case RESOURCE_FACETS -> resource.getPublishedFacets();
            case ENABLED_CATEGORIES -> category.simpleList();
            case ACCESS_CHECK -> access.checkAccess(7L);
            case AUTHORIZED_PREVIEW -> access.previewAuthorized(7L, 20);
            case MAP_PAGE -> map.getAssetPage(new DataAssetMapPageReqVO());
            case MAP_FACETS -> map.getAssetFacets();
            case MAP_DETAIL -> map.getAssetDetail(7L);
            case MAP_NAVIGATION -> map.navigationCategories();
        };
    }

    private void verifyReadDelegation(CatalogRead entry) {
        switch (entry) {
            case MART_TOPICS -> verify(martService).getTopics();
            case MART_PAGE -> verify(martService).getPage(any(MarketPageReqVO.class));
            case MART_STATISTICS -> verify(martService).getStatistics();
            case RESOURCE_PAGE -> verify(resourceService).getPublishedPage(any(DataAssetPageReqVO.class));
            case RESOURCE_DETAIL -> verify(resourceService).getConsumerDetail(7L);
            case RESOURCE_FACETS -> verify(resourceService).getPublishedFacets();
            case ENABLED_CATEGORIES -> verify(categoryService).getEnabledList();
            case ACCESS_CHECK -> verify(accessService).hasEffectiveAccess(9L, 7L);
            case AUTHORIZED_PREVIEW -> verify(accessService).previewAuthorizedAsset(7L, 20);
            case MAP_PAGE -> verify(mapService).getAssetPage(any(DataAssetPageReqVO.class));
            case MAP_FACETS -> verify(mapService).getAssetFacets();
            case MAP_DETAIL -> verify(mapService).getAssetDetail(7L);
            case MAP_NAVIGATION -> verify(mapService).getNavigationCategories();
        }
    }

    private void restricted(RestrictedAction action) {
        switch (action) {
            case PUBLISH -> mart.publish(new MarketPublishReqVO());
            case FAVORITE -> mart.setFavorite(new MarketFavoriteReqVO());
            case LISTING_CONFIGURATION -> mart.getListing(7L);
            case BIND_API -> resource.bindApi(new MarketEndpointBindReqVO());
            case ENDPOINTS -> resource.listEndpoints(7L);
            case CATEGORY_LIST -> category.list(new MarketCategoryListReqVO());
            case CATEGORY_DETAIL -> category.get(7L);
            case CATEGORY_CREATE -> category.create(new MarketCategorySaveReqVO());
            case CATEGORY_UPDATE -> category.update(new MarketCategorySaveReqVO());
            case CATEGORY_STATUS -> category.status(new MarketCategoryStatusReqVO());
            case CATEGORY_DELETE -> category.delete(7L);
            case CATEGORY_DELETE_LIST -> category.deleteList(List.of(7L));
        }
    }

    enum CatalogRead {
        MART_TOPICS, MART_PAGE, MART_STATISTICS, RESOURCE_PAGE, RESOURCE_DETAIL, RESOURCE_FACETS,
        ENABLED_CATEGORIES, ACCESS_CHECK, AUTHORIZED_PREVIEW, MAP_PAGE, MAP_FACETS, MAP_DETAIL, MAP_NAVIGATION
    }

    enum RestrictedAction {
        PUBLISH, FAVORITE, LISTING_CONFIGURATION, BIND_API, ENDPOINTS, CATEGORY_LIST, CATEGORY_DETAIL,
        CATEGORY_CREATE, CATEGORY_UPDATE, CATEGORY_STATUS, CATEGORY_DELETE, CATEGORY_DELETE_LIST
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean("ss")
        SecurityFrameworkService permissionGate() {
            return mock(SecurityFrameworkService.class);
        }

        @Bean
        MarketMartService martService() {
            return mock(MarketMartService.class);
        }

        @Bean
        MarketResourceService resourceService() {
            return mock(MarketResourceService.class);
        }

        @Bean
        MarketCategoryService categoryService() {
            return mock(MarketCategoryService.class);
        }

        @Bean
        MarketAccessService accessService() {
            return mock(MarketAccessService.class);
        }

        @Bean
        SupplyQuotaApi quotaApi() {
            return mock(SupplyQuotaApi.class);
        }

        @Bean
        DataAssetService mapService() { return mock(DataAssetService.class); }

        @Bean
        AssetCatalogService catalogService() { return mock(AssetCatalogService.class); }

        @Bean
        DataAssetController mapController(DataAssetService service) {
            var controller = new DataAssetController();
            ReflectionTestUtils.setField(controller, "dataAssetService", service);
            return controller;
        }

        @Bean
        MarketMartController martController(MarketMartService service) {
            var controller = new MarketMartController();
            ReflectionTestUtils.setField(controller, "marketMartService", service);
            return controller;
        }

        @Bean
        MarketResourceController resourceController(MarketResourceService service) {
            var controller = new MarketResourceController();
            ReflectionTestUtils.setField(controller, "marketResourceService", service);
            return controller;
        }

        @Bean
        MarketCategoryController categoryController(MarketCategoryService service) {
            var controller = new MarketCategoryController();
            ReflectionTestUtils.setField(controller, "service", service);
            return controller;
        }

        @Bean
        MarketAccessController accessController(MarketAccessService service) {
            var controller = new MarketAccessController();
            ReflectionTestUtils.setField(controller, "marketAccessService", service);
            return controller;
        }
    }
}
