package cn.iocoder.yudao.module.governance.service.qualityrule;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.AuditRuleController;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.CleanRuleController;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.AuditRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.qualityrule.vo.CleanRulePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorycleanrule.CategoryCleanRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.qualityrule.AuditRuleDimensionDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorycleanrule.CategoryCleanRuleMapper;
import cn.iocoder.yudao.module.governance.service.categorycleanrule.CategoryCleanRuleService;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuleCatalogAccessTest {

    @Test
    void sortContractAllowsOnlyBoundedFieldsAndDirections() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var audit = new AuditRulePageReqVO();
            var clean = new CleanRulePageReqVO();
            assertEquals("createTime", audit.getSortField());
            assertEquals("desc", audit.getSortOrder());
            assertEquals("createTime", clean.getSortField());
            assertEquals("desc", clean.getSortOrder());
            for (String field : List.of("id", "createTime")) {
                for (String order : List.of("asc", "desc")) {
                    audit.setSortField(field); audit.setSortOrder(order);
                    clean.setSortField(field); clean.setSortOrder(order);
                    assertTrue(validator.validate(audit).isEmpty());
                    assertTrue(validator.validate(clean).isEmpty());
                }
            }
            for (String field : List.of("create_time", "name", "id desc; select 1", "")) {
                audit.setSortField(field); clean.setSortField(field);
                assertFalse(validator.validate(audit).isEmpty());
                assertFalse(validator.validate(clean).isEmpty());
            }
            audit.setSortField("id"); clean.setSortField("id");
            for (String order : List.of("ASC", "asc nulls first", "", "descending")) {
                audit.setSortOrder(order); clean.setSortOrder(order);
                assertFalse(validator.validate(audit).isEmpty());
                assertFalse(validator.validate(clean).isEmpty());
            }
        }
    }

    @Test
    void actualPageRoutesRejectUnlistedSortBeforeCallingServices() throws Exception {
        var auditService = mock(AuditRuleService.class);
        var cleanService = mock(CleanRuleService.class);
        var audit = new AuditRuleController();
        var clean = new CleanRuleController();
        ReflectionTestUtils.setField(audit, "auditRuleService", auditService);
        ReflectionTestUtils.setField(clean, "cleanRuleService", cleanService);
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            var mvc = MockMvcBuilders.standaloneSetup(audit, clean).setValidator(validator).build();
            for (String resource : List.of("audit-rule", "clean-rule")) {
                mvc.perform(get("/governance/" + resource + "/page").param("sortField", "id desc;select 1"))
                        .andExpect(status().isBadRequest());
                mvc.perform(get("/governance/" + resource + "/page").param("sortOrder", "desc nulls last"))
                        .andExpect(status().isBadRequest());
            }
            verifyNoInteractions(auditService, cleanService);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class SecurityConfig {
        @Bean("ss") SecurityFrameworkService security() { return mock(SecurityFrameworkService.class); }
        @Bean AuditRuleController auditController() { return new AuditRuleController(); }
        @Bean CleanRuleController cleanController() { return new CleanRuleController(); }
    }

    @Test
    void realSecurityProxiesRequireOwnQueryPermissionForPageDetailAndFlatCategories() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var audits = mock(AuditRuleService.class);
            var cleans = mock(CleanRuleService.class);
            var dimensions = mock(AuditRuleDimensionService.class);
            var categories = mock(CategoryCleanRuleService.class);
            context.getBeanFactory().registerSingleton("auditRuleService", audits);
            context.getBeanFactory().registerSingleton("cleanRuleService", cleans);
            context.getBeanFactory().registerSingleton("auditRuleDimensionService", dimensions);
            context.getBeanFactory().registerSingleton("categoryCleanRuleService", categories);
            context.getBeanFactory().registerSingleton("categoryCleanRuleMapper", mock(CategoryCleanRuleMapper.class));
            context.getBeanFactory().registerSingleton("auditSqlRegistry", new AuditSqlRegistry());
            context.getBeanFactory().registerSingleton("cleanFunctionRegistry", new CleanFunctionRegistry());
            context.getBeanFactory().registerSingleton("adminUserApi", mock(AdminUserApi.class));
            context.register(SecurityConfig.class);
            context.refresh();
            var security = context.getBean(SecurityFrameworkService.class);
            var audit = context.getBean(AuditRuleController.class);
            var clean = context.getBean(CleanRuleController.class);
            when(security.hasPermission("governance:category-data-quality:query")).thenReturn(true);
            when(security.hasPermission("governance:category-clean-rule:query")).thenReturn(true);
            when(security.hasPermission("governance:quality-rule:query")).thenReturn(true);
            assertThrows(AccessDeniedException.class, audit::getDimensionList);
            assertThrows(AccessDeniedException.class, clean::getCategoryList);
            assertThrows(AccessDeniedException.class, () -> audit.getAuditRulePage(new AuditRulePageReqVO()));
            assertThrows(AccessDeniedException.class, () -> clean.getCleanRulePage(new CleanRulePageReqVO()));
            assertThrows(AccessDeniedException.class, () -> audit.getAuditRule(1L));
            assertThrows(AccessDeniedException.class, () -> clean.getCleanRule(1L));
            verifyNoInteractions(audits, cleans, dimensions, categories);

            when(audits.getAuditRulePage(any())).thenReturn(PageResult.empty());
            when(cleans.getCleanRulePage(any())).thenReturn(PageResult.empty());
            when(dimensions.getDimensionList()).thenReturn(List.of(AuditRuleDimensionDO.builder()
                    .id(9007199254740993L).name("准确性").code("D05").status((short) 0).sort(0).build()));
            when(categories.getCategoryCleanRuleList(any())).thenReturn(List.of(CategoryCleanRuleDO.builder()
                    .id(2L).parentId(1L).categoryName("字符清洗").categoryCode("0,1,2").sort(2).build()));
            when(security.hasPermission("governance:audit-rule:query")).thenReturn(true);
            var dimension = audit.getDimensionList().getCheckedData().getFirst();
            assertEquals(9007199254740993L, dimension.getId());
            assertEquals("D05", dimension.getCode());
            assertEquals("准确性", dimension.getName());
            assertEquals((short) 0, dimension.getStatus());
            assertEquals(0, audit.getAuditRulePage(new AuditRulePageReqVO()).getCheckedData().getTotal());
            assertNull(audit.getAuditRule(1L).getCheckedData());
            assertThrows(AccessDeniedException.class, clean::getCategoryList);

            when(security.hasPermission("governance:clean-rule:query")).thenReturn(true);
            var category = clean.getCategoryList().getCheckedData().getFirst();
            assertEquals(1L, category.getParentId());
            assertEquals("字符清洗", category.getCategoryName());
            assertEquals(0, clean.getCleanRulePage(new CleanRulePageReqVO()).getCheckedData().getTotal());
            assertNull(clean.getCleanRule(1L).getCheckedData());
        }
    }
}
