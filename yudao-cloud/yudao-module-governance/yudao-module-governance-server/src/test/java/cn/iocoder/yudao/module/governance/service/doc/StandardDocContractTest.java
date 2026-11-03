package cn.iocoder.yudao.module.governance.service.doc;

import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.governance.controller.admin.doc.StandardDocController;
import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.doc.vo.StandardDocSaveReqVO;
import cn.iocoder.yudao.module.governance.service.standardelement.StandardCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StandardDocContractTest {
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class TestConfiguration {
        @Bean("ss") SecurityFrameworkService security() { return mock(SecurityFrameworkService.class); }
        @Bean StandardDocService docService() { return mock(StandardDocService.class); }
        @Bean StandardCatalogService catalogService() { return mock(StandardCatalogService.class); }
        @Bean StandardDocController docController() { return new StandardDocController(); }
    }

    @Test
    void sharedCategoriesRequireDocumentQueryPermissionAndDoNotGrantCreatePermission() {
        try (var context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            var security = context.getBean(SecurityFrameworkService.class);
            var controller = context.getBean(StandardDocController.class);
            var catalog = context.getBean(StandardCatalogService.class);
            assertThatThrownBy(controller::getDocCategories).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(catalog);
            when(security.hasPermission("governance:doc:query")).thenReturn(true);
            when(catalog.categoryList()).thenReturn(List.of());
            assertThat(controller.getDocCategories().getCheckedData()).isEmpty();
            assertThatThrownBy(() -> controller.createDoc(new StandardDocSaveReqVO()))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void datesRemainIsoStringsAndForeignKeysRemainLosslessWithGlobalJsonConfiguration() {
        var mapper = JsonMapper.builder()
                .addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        var response = new StandardDocRespVO();
        response.setId(9007199254740993L);
        response.setCategoryId(9007199254740995L);
        response.setPublishDate(LocalDate.of(2026, 1, 2));
        response.setImplementDate(LocalDate.of(2026, 2, 3));
        response.setAbolishDate(LocalDate.of(2028, 3, 4));
        response.setUpdateTime(LocalDateTime.of(2026, 10, 19, 12, 30));
        var wire = mapper.readTree(mapper.writeValueAsString(response));
        assertThat(wire.get("id").asString()).isEqualTo("9007199254740993");
        assertThat(wire.get("categoryId").asString()).isEqualTo("9007199254740995");
        assertThat(wire.get("publishDate").asString()).isEqualTo("2026-01-02");
        assertThat(wire.get("implementDate").asString()).isEqualTo("2026-02-03");
        assertThat(wire.get("abolishDate").asString()).isEqualTo("2028-03-04");
        assertThat(wire.get("updateTime").isNumber()).isTrue();
        var request = mapper.readValue("{\"categoryId\":\"9007199254740995\",\"abolishDate\":\"2028-03-04\"}", StandardDocSaveReqVO.class);
        assertThat(request.getCategoryId()).isEqualTo(response.getCategoryId());
        assertThat(request.getAbolishDate()).isEqualTo(response.getAbolishDate());
    }
}
