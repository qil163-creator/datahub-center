package cn.iocoder.yudao.module.dataforge.controller.admin.fileresource;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.dataforge.controller.admin.fileresource.vo.*;
import cn.iocoder.yudao.module.dataforge.service.fileresource.FileResourceService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 真实方法安全代理与 Boot JSON 配置验证文件管理传输边界。 */
class FileResourceControllerContractTest {
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
    @Test void queryCannotAuthorizeUploadRenameDeleteOrDownload() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("file-contract","unused",List.of()));
        try(var context=new AnnotationConfigApplicationContext()){
            var service=mock(FileResourceService.class);
            context.getBeanFactory().registerSingleton("service",service);
            context.register(SecurityConfiguration.class);context.refresh();
            var controller=context.getBean(FileResourceController.class);
            var security=context.getBean(SecurityFrameworkService.class);
            assertThrows(AccessDeniedException.class,controller::connections);
            verifyNoInteractions(service);
            when(security.hasPermission("dataforge:data-file:query")).thenReturn(true);
            when(service.connections()).thenReturn(List.of());
            assertNotNull(controller.connections().getCheckedData());
            var name=new FileResourceNameReqVO();name.setConnectionId(1L);name.setPath("/");name.setName("a");
            var batch=new FileResourceBatchReqVO();batch.setConnectionId(1L);batch.setPaths(List.of("/a"));
            var path=new FileResourcePathReqVO();path.setConnectionId(1L);
            assertThrows(AccessDeniedException.class,()->controller.mkdir(name));
            assertThrows(AccessDeniedException.class,()->controller.rename(name));
            assertThrows(AccessDeniedException.class,()->controller.delete(batch));
            assertThrows(AccessDeniedException.class,()->controller.upload(1L,"/",new MockMultipartFile("file",new byte[]{1}),false));
            assertThrows(AccessDeniedException.class,()->controller.download(path,new MockHttpServletResponse()));
            when(security.hasPermission("dataforge:data-file:update")).thenReturn(true);
            assertTrue(controller.rename(name).getCheckedData());
            verify(service).rename(name);
        }
    }
    @Test void requestBoundsAndLargeIdsAreEnforced() {
        try(var factory=Validation.buildDefaultValidatorFactory()){
            var request=new FileResourceBatchReqVO();request.setConnectionId(9007199254740993L);
            request.setPaths(java.util.Collections.nCopies(101,"/a"));
            assertFalse(factory.getValidator().validate(request).isEmpty());
            request.setPaths(List.of("/a"));assertTrue(factory.getValidator().validate(request).isEmpty());
            var name=new FileResourceNameReqVO();name.setConnectionId(1L);name.setName("x".repeat(51));
            assertFalse(factory.getValidator().validate(name).isEmpty());
        }
    }
    @Test void bootJsonPreservesIdentifierAndNeverExposesConnectionSecrets() {
        var previous=JsonUtils.getObjectMapper();
        try {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,YudaoJacksonAutoConfiguration.class)).run(context->{
                assertNull(context.getStartupFailure());
                ObjectMapper mapper=context.getBean(ObjectMapper.class);
                var request=mapper.readValue("{\"connectionId\":\"9007199254740993\",\"path\":\"/中文目录\"}",FileResourcePathReqVO.class);
                assertEquals(9007199254740993L,request.getConnectionId());
                var response=new FileResourceConnectionRespVO();response.setId(request.getConnectionId());response.setName("测试连接");response.setType("FTP");
                var json=mapper.readTree(mapper.writeValueAsBytes(response));
                assertTrue(json.get("id").isString());assertEquals("9007199254740993",json.get("id").asString());
                assertNull(json.get("password"));assertNull(json.get("secretKey"));assertNull(json.get("jdbcUrl"));
                var entry=new FileResourceEntryRespVO();entry.setUpdateTime(1700000000000L);
                assertTrue(mapper.readTree(mapper.writeValueAsBytes(entry)).get("updateTime").isNumber());
            });
        } finally {JsonUtils.init(previous);}
    }
    @Test void allPathRequestsMatchTheComponent4096CharacterLimit() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            String longest = "/" + "p/".repeat(2047) + "p";
            var path = new FileResourcePathReqVO(); path.setConnectionId(1L); path.setPath(longest);
            var page = new FileResourcePageReqVO(); page.setConnectionId(1L); page.setPath(longest);
            var batch = new FileResourceBatchReqVO(); batch.setConnectionId(1L); batch.setPaths(List.of(longest));
            var name = new FileResourceNameReqVO(); name.setConnectionId(1L); name.setPath(longest); name.setName("safe");
            for (Object request : List.of(path, page, batch, name)) {
                assertTrue(factory.getValidator().validate(request).isEmpty());
            }
            path.setPath(longest + "x"); page.setPath(longest + "x");
            batch.setPaths(List.of(longest + "x")); name.setPath(longest + "x");
            for (Object request : List.of(path, page, batch, name)) {
                assertFalse(factory.getValidator().validate(request).isEmpty());
            }
        }
    }
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity
    static class SecurityConfiguration {
        @Bean("ss") SecurityFrameworkService security(){return mock(SecurityFrameworkService.class);}
        @Bean FileResourceController controller(){return new FileResourceController();}
    }
}

