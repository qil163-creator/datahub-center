package cn.iocoder.yudao.module.governance.service.standardelement;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.StandardElementRuleController;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.StandardCodeMappingController;
import cn.iocoder.yudao.module.governance.controller.admin.standardelement.vo.*;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeset.vo.*;
import cn.iocoder.yudao.module.governance.controller.admin.standardcodeitem.vo.StandardCodeItemPageReqVO;
import cn.iocoder.yudao.module.governance.service.standardcodeset.StandardCodeMappingService;
import cn.iocoder.yudao.module.governance.dal.dataobject.categorydatameta.CategoryDatametaDO;
import cn.iocoder.yudao.module.governance.dal.mysql.categorydatameta.CategoryDatametaMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.doc.StandardDocMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import tools.jackson.databind.json.JsonMapper;
import jakarta.validation.Validation;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StandardWorkbenchAccessTest {
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity
    static class Config {
        @Bean("ss") SecurityFrameworkService security(){return mock(SecurityFrameworkService.class);}
        @Bean StandardElementRuleService rules(){return mock(StandardElementRuleService.class);}
        @Bean StandardCodeMappingService mappings(){return mock(StandardCodeMappingService.class);}
        @Bean StandardElementRuleController ruleController(StandardElementRuleService service){return new StandardElementRuleController(service);}
        @Bean StandardCodeMappingController mappingController(StandardCodeMappingService service){return new StandardCodeMappingController(service);}
    }
    @Test void realSecurityProxiesSeparateQueriesFromOwnedAggregateEdits() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var security=context.getBean(SecurityFrameworkService.class);var rules=context.getBean(StandardElementRuleController.class);var mappings=context.getBean(StandardCodeMappingController.class);
            assertThrows(AccessDeniedException.class,()->rules.templates("AUDIT"));
            assertThrows(AccessDeniedException.class,()->mappings.get(1,1L));
            when(security.hasPermission("governance:standard-element:query")).thenReturn(true);
            assertDoesNotThrow(()->rules.templates("AUDIT"));assertDoesNotThrow(()->rules.context(1));
            assertThrows(AccessDeniedException.class,()->rules.create(new StandardElementRuleSaveReqVO()));
            assertThrows(AccessDeniedException.class,()->mappings.get(1,1L));
            when(security.hasPermission("governance:standard-code-set:query")).thenReturn(true);
            assertDoesNotThrow(()->mappings.get(1,1L));assertThrows(AccessDeniedException.class,()->mappings.delete(1,1L));
            when(security.hasPermission("governance:standard-code-set:update")).thenReturn(true);
            assertDoesNotThrow(()->mappings.delete(1,1L));
        }
    }
    @Test void transportPreservesLargeForeignKeysAndDefaultTimestampContract() {
        var response=new StandardElementRuleRespVO();response.setId(9007199254740993L);response.setTemplateId(9007199254740995L);response.setElementId(1);response.setCreateTime(LocalDateTime.of(2026,10,16,12,0));
        var mapper=JsonMapper.builder().addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        var wire=mapper.readTree(mapper.writeValueAsString(response));assertEquals("9007199254740993",wire.get("id").asString());assertTrue(wire.get("createTime").isNumber());
        assertEquals(response.getTemplateId(),mapper.treeToValue(wire,StandardElementRuleRespVO.class).getTemplateId());
    }
    @Test void recursiveSharedCategoriesPreserveLongIdsAndWhitelistSorting() {
        var categories=mock(CategoryDatametaMapper.class);
        when(categories.selectList()).thenReturn(List.of(CategoryDatametaDO.builder().id(9007199254740993L).parentId(0L).build(),CategoryDatametaDO.builder().id(2L).parentId(9007199254740993L).build(),CategoryDatametaDO.builder().id(3L).parentId(2L).build()));
        var catalog=new StandardCatalogService(categories,mock(StandardDocMapper.class),mock(AdminUserApi.class));
        assertEquals(List.of(9007199254740993L,2L,3L),catalog.descendants(9007199254740993L));assertEquals(List.of(-1L),catalog.descendants(99L));
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var element=new StandardElementPageReqVO();var set=new StandardCodeSetPageReqVO();var item=new StandardCodeItemPageReqVO();var mapping=new StandardCodeMappingPageReqVO();mapping.setCodeSetId(1);
            for(var request:List.of(element,set,item,mapping))assertTrue(factory.getValidator().validate(request).isEmpty());
            element.setSortField("id desc;select 1");set.setSortOrder("desc nulls last");item.setSortField("name");mapping.setSortOrder("DESC");
            for(var request:List.of(element,set,item,mapping))assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }
}
