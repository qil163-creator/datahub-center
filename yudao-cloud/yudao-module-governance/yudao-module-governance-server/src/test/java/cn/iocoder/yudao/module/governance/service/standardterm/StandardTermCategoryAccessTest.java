package cn.iocoder.yudao.module.governance.service.standardterm;

import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.vo.StandardTermSaveReqVO;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import cn.iocoder.yudao.module.governance.controller.admin.categoryterm.CategoryTermController;
import cn.iocoder.yudao.module.governance.controller.admin.standardterm.StandardTermController;
import cn.iocoder.yudao.module.governance.service.categoryterm.CategoryTermService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StandardTermCategoryAccessTest {
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean("ss") SecurityFrameworkService security() { return mock(SecurityFrameworkService.class); }
        @Bean StandardTermService terms() { return mock(StandardTermService.class); }
        @Bean CategoryTermService categories() { return mock(CategoryTermService.class); }
        @Bean StandardTermController termController() { return new StandardTermController(); }
        @Bean CategoryTermController categoryController() { return new CategoryTermController(); }
    }

    @Test
    void termReadersCanSeeCategoryTreeButCannotMaintainCategories() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var security = context.getBean(SecurityFrameworkService.class);
            var termController = context.getBean(StandardTermController.class);
            var categoryController = context.getBean(CategoryTermController.class);
            assertThrows(AccessDeniedException.class, termController::getCategories);
            when(security.hasAnyPermissions("governance:standard-term:query", "governance:standard-term:create",
                    "governance:standard-term:update")).thenReturn(true);
            assertDoesNotThrow(termController::getCategories);
            assertThrows(AccessDeniedException.class, () -> categoryController.deleteCategoryTerm(1L));
            assertThrows(AccessDeniedException.class, () -> categoryController.getCategoryTerm(1L));
        }
    }
    @Test
    void springHttpContractDistinguishesOmittedNullAndLargeIds() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var security = context.getBean(SecurityFrameworkService.class);
            var terms = context.getBean(StandardTermService.class);
            when(security.hasPermission("governance:standard-term:update")).thenReturn(true);
            when(security.hasPermission("governance:standard-term:create")).thenReturn(true);
            var mapper = JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(StandardTermController.class))
                    .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper)).build();
            String common = "\"id\":1,\"name\":\"术语\",\"definition\":\"定义\",\"status\":0";
            mvc.perform(put("/governance/standard-term/update").contentType(MediaType.APPLICATION_JSON)
                    .content("{" + common + "}")).andExpect(status().isOk());
            mvc.perform(put("/governance/standard-term/update").contentType(MediaType.APPLICATION_JSON)
                    .content("{" + common + ",\"termCategoryId\":null}")).andExpect(status().isOk());
            mvc.perform(post("/governance/standard-term/create").contentType(MediaType.APPLICATION_JSON)
                    .content("{" + common + ",\"termCategoryId\":\"9007199254740993\"}")).andExpect(status().isOk());
            var updates = ArgumentCaptor.forClass(StandardTermSaveReqVO.class);
            verify(terms, times(2)).updateStandardTerm(updates.capture());
            assertFalse(updates.getAllValues().get(0).isTermCategorySpecified());
            assertTrue(updates.getAllValues().get(1).isTermCategorySpecified());
            assertNull(updates.getAllValues().get(1).getTermCategoryId());
            var creation = ArgumentCaptor.forClass(StandardTermSaveReqVO.class);
            verify(terms).createStandardTerm(creation.capture());
            assertTrue(creation.getValue().isTermCategorySpecified());
            assertEquals(9007199254740993L, creation.getValue().getTermCategoryId());
        }
    }

}
