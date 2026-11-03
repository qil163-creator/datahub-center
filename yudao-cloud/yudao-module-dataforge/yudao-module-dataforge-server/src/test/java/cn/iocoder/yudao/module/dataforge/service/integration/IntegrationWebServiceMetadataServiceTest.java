package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlWebServiceRows;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationWebServiceMetadataReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationTaskMapper;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationWebServiceMetadataServiceTest {
    private final IntegrationTaskMapper tasks = mock(IntegrationTaskMapper.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final IntegrationDataxBindingsProperties bindings = new IntegrationDataxBindingsProperties();
    private final IntegrationWebServiceMetadataService service = new IntegrationWebServiceMetadataService();
    private final IntegrationTaskDO task = new IntegrationTaskDO();
    private final IntegrationDataxBindingsProperties.ManagedBinding binding = new IntegrationDataxBindingsProperties.ManagedBinding();
    private final String address = "https://authorized.example/service?wsdl";
    IntegrationWebServiceMetadataServiceTest() {
        ReflectionTestUtils.setField(service, "tasks", tasks); ReflectionTestUtils.setField(service, "access", access); ReflectionTestUtils.setField(service, "bindings", bindings);
        task.setId(91L); task.setRevision(3); task.setEngine("SPARK"); task.setMode("OFFLINE"); when(tasks.selectById(91L)).thenReturn(task);
        binding.setAllowedTaskIds(Set.of(91L)); binding.setConnectorCodes(Set.of("read-webservice")); binding.setAddresses(List.of(address)); bindings.setBindings(Map.of("document", binding));
    }
    private IntegrationWebServiceMetadataReqVO request() {
        var req = new IntegrationWebServiceMetadataReqVO(); req.setId(91L); req.setRevision(3); req.setResourceBindingId("document"); req.setApiUrl(address); return req;
    }
    @Test void defaultClosedWrongTaskRevisionEngineAndCredentialQueryNeverReachTransport() {
        try (var transport = mockStatic(EtlWebServiceRows.class)) {
            assertThrows(ServiceException.class, () -> service.inspect(request()));
            binding.setMetadataAddresses(Set.of(address));
            var stale = request(); stale.setRevision(2); assertThrows(ServiceException.class, () -> service.inspect(stale));
            task.setEngine("DATAX"); assertThrows(ServiceException.class, () -> service.inspect(request())); task.setEngine("SPARK");
            binding.setAllowedTaskIds(Set.of(92L)); assertThrows(ServiceException.class, () -> service.inspect(request())); binding.setAllowedTaskIds(Set.of(91L));
            var credential = request(); credential.setApiUrl("https://authorized.example/service?access_token=private"); assertThrows(ServiceException.class, () -> service.inspect(credential));
            binding.setMetadataAddresses(Set.of("https://unapproved.example/wsdl")); assertThrows(ServiceException.class, () -> service.inspect(request()));
            transport.verifyNoInteractions();
        }
    }
    @Test void apiSamplingRejectsPostsCredentialsAndMissingControlPlaneGrantBeforeTransport() {
        binding.setConnectorCodes(Set.of("rest-api")); binding.setInstalledPlugins(Set.of("spark-http"));
        var req = new cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationApiMetadataReqVO();
        req.setId(91L); req.setRevision(3); req.setResourceBindingId("document");
        try (var transport = mockStatic(cn.iocoder.yudao.framework.etl.core.executor.support.EtlHttpRows.class)) {
            req.setApiConfig(Map.of("requestUrl", address, "requestMethod", "POST")); assertThrows(ServiceException.class, () -> service.sample(req));
            req.setApiConfig(Map.of("requestUrl", address, "requestMethod", "GET")); assertThrows(ServiceException.class, () -> service.sample(req));
            binding.setMetadataAddresses(Set.of(address));
            req.setApiConfig(Map.of("requestUrl", address, "requestMethod", "GET", "requestHeader", List.of(Map.of("name", "Authorization", "value", "private"))));
            assertThrows(ServiceException.class, () -> service.sample(req));
            req.setApiConfig(Map.of("requestUrl", address, "requestMethod", "GET"));
            assertThrows(ServiceException.class, () -> service.sample(req));
            transport.verifyNoInteractions();
        }
    }

    @Test void apiSamplingReturnsOnlyConservativeFieldMetadataFromOneRealGet() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/rows";
        var count = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/rows", exchange -> {
            count.incrementAndGet(); assertEquals("GET", exchange.getRequestMethod());
            byte[] bytes = "[{\"label\":\"private-business-value\",\"id\":9007199254740993,\"amount\":1.25,\"unknown\":null}]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        binding.setConnectorCodes(Set.of("rest-api")); binding.setInstalledPlugins(Set.of("spark-http"));
        binding.setAuthentication(cn.iocoder.yudao.framework.etl.core.compile.EtlDataxConnectorCompiler.Authentication.NONE);
        binding.setAddresses(List.of(endpoint)); binding.setMetadataAddresses(Set.of(endpoint));
        var req = new cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationApiMetadataReqVO();
        req.setId(91L); req.setRevision(3); req.setResourceBindingId("document"); req.setApiConfig(Map.of("requestUrl", endpoint, "requestMethod", "GET", "dataType", 2));
        server.start();
        try {
            var result = service.sample(req); assertTrue(result.partial()); assertEquals(1, count.get());
            assertEquals(List.of("STRING", "LONG", "DECIMAL", "UNKNOWN"), result.fields().stream().map(field -> field.dataType() == null ? "UNKNOWN" : field.dataType()).toList());
            assertTrue(result.fields().stream().allMatch(field -> field.length() == null && field.precision() == null && field.scale() == null));
            assertFalse(cn.hutool.json.JSONUtil.toJsonStr(result).contains("private-business-value"));
            assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("前32行")));
        } finally { server.stop(0); }
    }

    @Test void grantedIdentityDelegatesOnlyTheIntersectionOfExactMetadataAndExecutionAddresses() {
        binding.setMetadataAddresses(Set.of(address));
        try (var identity = mockStatic(SecurityFrameworkUtils.class); var transport = mockStatic(EtlWebServiceRows.class)) {
            identity.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(7L);
            var expected = new EtlWebServiceRows.Metadata(List.of(), List.of());
            transport.when(() -> EtlWebServiceRows.inspect(address, Set.of(address), Set.of(address))).thenReturn(expected);
            assertSame(expected, service.inspect(request())); verify(access).requireUserPermission(7L, "dataforge:integration:query");
            transport.verify(() -> EtlWebServiceRows.inspect(address, Set.of(address), Set.of(address)));
        }
    }
}
