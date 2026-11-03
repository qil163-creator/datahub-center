package cn.iocoder.yudao.module.infra.controller.admin.file;

import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.module.infra.controller.admin.file.vo.file.FileUploadReqVO;
import cn.iocoder.yudao.module.infra.dal.dataobject.file.FileDO;
import cn.iocoder.yudao.module.infra.service.file.FileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FileUploadContractTest {
    private static final byte[] CONTENT = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void multipartUploadReturnsRegisteredIdentityAndLegacyUploadStillReturnsUrl() throws Exception {
        var service = mock(FileService.class);
        var controller = new FileController();
        ReflectionTestUtils.setField(controller, "fileService", service);
        when(service.createFileWithInfo(any(), eq("standard.pdf"), eq("standard/doc"), eq("application/pdf")))
                .thenReturn(new FileDO().setId(42L).setName("standard.pdf").setUrl("https://files.example.test/unique.pdf"));
        when(service.createFile(any(), any(), any(), any())).thenReturn("https://files.example.test/legacy.pdf");
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        var file = new MockMultipartFile("file", "standard.pdf", "application/pdf", CONTENT);

        mvc.perform(multipart("/infra/file/upload-with-info").file(file).param("directory", "standard/doc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.name").value("standard.pdf"))
                .andExpect(jsonPath("$.data.url").value("https://files.example.test/unique.pdf"))
                .andExpect(jsonPath("$.data.configId").doesNotExist());
        mvc.perform(multipart("/infra/file/upload").file(file).param("directory", "standard/doc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("https://files.example.test/legacy.pdf"));
        verify(service).createFileWithInfo(aryEq(CONTENT), eq("standard.pdf"), eq("standard/doc"), eq("application/pdf"));
    }

    @Test
    void registrationFailureDoesNotReturnSuccessOrRetryLegacyUpload() {
        var service = mock(FileService.class);
        var controller = new FileController();
        ReflectionTestUtils.setField(controller, "fileService", service);
        var failure = new IllegalStateException("登记失败");
        when(service.createFileWithInfo(any(), any(), any(), any())).thenThrow(failure);
        assertThatThrownBy(() -> controller.uploadFileWithInfo(request())).isSameAs(failure);
        verify(service, never()).createFile(any(), any(), any(), any());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class TestConfiguration {
        @Bean FileService fileService() { return mock(FileService.class); }
        @Bean FileController fileController() { return new FileController(); }
    }

    @Test
    void loginIsRequiredAndLargeFileIdsUseLosslessJsonContract() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(TestConfiguration.class)) {
            var controller = context.getBean(FileController.class);
            var service = context.getBean(FileService.class);
            assertThatThrownBy(() -> controller.uploadFileWithInfo(request()))
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            verifyNoInteractions(service);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("test-user", null, List.of()));
            when(service.createFileWithInfo(any(), any(), any(), any()))
                    .thenReturn(new FileDO().setId(9007199254740993L).setName("standard.pdf")
                            .setUrl("https://files.example.test/registered.pdf"));
            var response = controller.uploadFileWithInfo(request());
            var mapper = JsonMapper.builder()
                    .addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
            var wire = mapper.readTree(mapper.writeValueAsString(response));
            assertThat(wire.get("data").get("id").isString()).isTrue();
            assertThat(wire.get("data").get("id").asString()).isEqualTo("9007199254740993");
        }
    }

    private FileUploadReqVO request() {
        var request = new FileUploadReqVO();
        request.setFile(new MockMultipartFile("file", "standard.pdf", "application/pdf", CONTENT));
        request.setDirectory("standard/doc");
        return request;
    }
}
