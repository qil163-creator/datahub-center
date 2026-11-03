package cn.iocoder.yudao.module.infra.framework.security;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.infra.api.file.ManagedFileApi;
import cn.iocoder.yudao.module.infra.api.file.ManagedFileClientConfiguration;
import cn.iocoder.yudao.module.infra.api.file.ManagedFileApiImpl;
import cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileRespDTO;
import cn.iocoder.yudao.module.infra.framework.security.config.ManagedFileRpcConfiguration;
import cn.iocoder.yudao.module.infra.service.file.FileService;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedFileRpcSecurityTest {
    @Test void forgedHeadersAndDirectPlaintextDoNotAuthenticate() throws Exception {
        var request = new MockHttpServletRequest("GET", ManagedFileApi.PREFIX + "/content");
        request.addHeader(SecurityFrameworkUtils.LOGIN_USER_HEADER, "forged");
        request.addHeader("X-Forwarded-Client-Cert", "forged");
        request.addHeader("x-service-id", "dataforge");
        var response = new MockHttpServletResponse();
        new ManagedFileRpcFilter(() -> Map.of()).doFilter(request, response, (req, res) -> fail("未认证请求不得进入接口"));
        assertEquals(401, response.getStatus());
        assertThrows(RuntimeException.class, () -> ManagedFileRpcFilter.requireOwner(request));
    }

    @Test void validPinnedCertificateOwnsOperationAndStripsForgedControlHeaders() throws Exception {
        var certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn(new byte[]{8, 3, 1, 7});
        var request = new MockHttpServletRequest("GET", ManagedFileApi.PREFIX + "/get");
        request.setSecure(true);
        request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate});
        request.addHeader(SecurityFrameworkUtils.LOGIN_USER_HEADER, "forged");
        request.addHeader("data-permission-enable", "false");
        var fileService = mock(FileService.class);
        when(fileService.getManaged("dataforge", "operation_1234567890")).thenReturn(new ManagedFileRespDTO().setId(9007199254740993L).setState("READY").setSize(7L));
        new ManagedFileRpcFilter(() -> Map.of("dataforge", Set.of(VerifiedServicePeerResolver.fingerprint(certificate))))
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                    var http = (jakarta.servlet.http.HttpServletRequest) req;
                    assertEquals("dataforge", ManagedFileRpcFilter.requireOwner(http));
                    assertNull(http.getHeader(SecurityFrameworkUtils.LOGIN_USER_HEADER));
                    assertNull(http.getHeader("data-permission-enable"));
                    var api = new ManagedFileApiImpl();
                    ReflectionTestUtils.setField(api, "request", http);
                    ReflectionTestUtils.setField(api, "fileService", fileService);
                    assertEquals(9007199254740993L, api.getManaged("operation_1234567890").getCheckedData().getId());
                });
        verify(fileService).getManaged("dataforge", "operation_1234567890");
    }

    @Test void dedicatedClientRestrictsMethodsAndFailsClosedWithoutCertificateBundle() {
        var configuration = new ManagedFileClientConfiguration();
        var transport = configuration.managedFileClient(mock(Environment.class), provider(), provider(), provider(), provider());
        var request = Request.create(Request.HttpMethod.GET, "https://infra.example.test" + ManagedFileApi.PREFIX + "/get", Map.of(), new byte[0], StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> transport.execute(request, configuration.managedFileOptions()));
        var forbidden = Request.create(Request.HttpMethod.POST, "https://infra.example.test/admin-api/infra/file/create", Map.of(), new byte[0], StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> ManagedFileClientConfiguration.restrict((req, opt) -> { fail(); return null; }).execute(forbidden, configuration.managedFileOptions()));
        assertEquals(5, new SpringMvcContract().parseAndValidateMetadata(ManagedFileApi.class).size());
        assertSame(feign.Retryer.NEVER_RETRY, configuration.managedFileRetryer());
    }

    @Test void userUploadReadCannotBypassPinnedServiceAuthentication() {
        var api = new ManagedFileApiImpl();
        var fileService = mock(FileService.class);
        ReflectionTestUtils.setField(api, "request", new MockHttpServletRequest("GET", ManagedFileApi.PREFIX + "/user-upload"));
        ReflectionTestUtils.setField(api, "fileService", fileService);
        assertThrows(RuntimeException.class, () -> api.readUserUpload(77L, 42L));
        verifyNoInteractions(fileService);
    }

    @Test void minimalConfigurationContextDefaultsToNoTrustedPeers() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment());
            context.register(ManagedFileRpcConfiguration.class);
            context.refresh();
            var filter = context.getBean("managedFileRpcFilter", org.springframework.boot.web.servlet.FilterRegistrationBean.class);
            assertTrue(filter.getUrlPatterns().contains(ManagedFileApi.PREFIX + "/*"));
        }
    }

    @Test void receiptJsonDoesNotContainStorageAddressOrBytes() {
        String json = JsonUtils.toJsonString(CommonResult.success(new ManagedFileRespDTO().setId(9007199254740993L)
                .setSize(10L).setSha256("a".repeat(64)).setState("READY")));
        assertTrue(json.contains("9007199254740993"));
        assertFalse(json.contains("url")); assertFalse(json.contains("path")); assertFalse(json.contains("content"));
    }

    @SuppressWarnings("unchecked") private static <T> ObjectProvider<T> provider() {
        var provider = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.empty());
        return provider;
    }
}
