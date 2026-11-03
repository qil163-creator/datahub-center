package cn.iocoder.yudao.module.supply.api.app;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceCommandDTO;
import cn.iocoder.yudao.module.supply.api.app.dto.SupplyGrantSourceResultDTO;
import cn.iocoder.yudao.module.supply.framework.security.SourceGrantRpcFilter;
import cn.iocoder.yudao.module.supply.framework.security.TemporaryCertificates;
import cn.iocoder.yudao.module.supply.service.app.grant.SupplyGrantCoordinator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 来源级授权 v2 RPC 入口：没有容器 TLS 提供的已配置客户端证书，任何伪造内部头的直连都不能到达协调器；
 * 通过门禁后，命令也只能声明与已认证服务一致的 sourceSystem。
 */
class SourceGrantRpcSecurityTest {

    private static final String COMMAND_PATH = SupplyGrantSourceApi.PREFIX + "/command";
    private static final String OPERATION_PATH = SupplyGrantSourceApi.PREFIX + "/operation";
    private static final String REVOKE_BY_ASSET = """
            {"protocolVersion":2,"operationId":"op-1","sourceSystem":"asset","sourceKey":"asset:apply:7",
             "sourceRevision":3,"appId":1,"apiId":2,"action":"REVOKE","reason":"用户申请撤销"}""";

    @TempDir
    static Path directory;
    private static X509Certificate asset;
    private static X509Certificate stranger;
    private static String assetPin;

    private SupplyGrantCoordinator coordinator;
    private Map<String, Set<String>> peers;
    private MockMvc mockMvc;
    private SupplyGrantSourceApiImpl adapter;

    @BeforeAll
    static void certificates() throws Exception {
        asset = TemporaryCertificates.generate(directory, "asset", "asset-server");
        stranger = TemporaryCertificates.generate(directory, "stranger", "unknown-service");
        assetPin = TemporaryCertificates.pin(asset);
    }

    @BeforeEach
    void setUp() {
        coordinator = mock(SupplyGrantCoordinator.class);
        peers = Map.of("asset", Set.of(assetPin));
        adapter = new SupplyGrantSourceApiImpl();
        ReflectionTestUtils.setField(adapter, "coordinator", coordinator);
        Supplier<Map<String, Set<String>>> configured = () -> peers;
        SourceGrantRpcFilter gate = new SourceGrantRpcFilter(new VerifiedServicePeerResolver(), configured);
        mockMvc = MockMvcBuilders.standaloneSetup(adapter)
                .addFilter(gate, SourceGrantRpcFilter.PROTECTED_PATH + "/*")
                .build();
    }

    private static MockHttpServletRequestBuilder forgedHeaders(MockHttpServletRequestBuilder builder) {
        return builder.header("login-user", "{\"id\":1,\"tenantId\":1,\"userType\":1}")
                .header("tenant-id", "1")
                .header("X-Forwarded-Client-Cert", "Hash=" + assetPin)
                .header("data-permission-enable", "false");
    }

    private static MockHttpServletRequestBuilder tls(MockHttpServletRequestBuilder builder, X509Certificate... chain) {
        return builder.secure(true).requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, chain);
    }

    private MockHttpServletRequestBuilder command(String json) {
        return post(COMMAND_PATH).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    void forgedInternalHeadersWithoutCertificateAreRejectedBeforeTheCoordinator() throws Exception {
        mockMvc.perform(forgedHeaders(command(REVOKE_BY_ASSET)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(forgedHeaders(get(OPERATION_PATH).param("sourceSystem", "asset").param("operationId", "op-1")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(coordinator);
    }

    @Test
    void plainChannelWithCertificateAttributeIsStillRejected() throws Exception {
        mockMvc.perform(forgedHeaders(command(REVOKE_BY_ASSET)).secure(false)
                        .requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{asset}))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(coordinator);
    }

    @Test
    void unpinnedCertificateIsRejected() throws Exception {
        mockMvc.perform(tls(command(REVOKE_BY_ASSET), stranger)).andExpect(status().isUnauthorized());
        verifyNoInteractions(coordinator);
    }

    @Test
    void missingOrAmbiguousPeerConfigurationDeniesEveryone() throws Exception {
        peers = Map.of();
        mockMvc.perform(tls(command(REVOKE_BY_ASSET), asset)).andExpect(status().isUnauthorized());
        peers = Map.of("asset", Set.of(assetPin), "governance", Set.of(assetPin));
        mockMvc.perform(tls(command(REVOKE_BY_ASSET), asset)).andExpect(status().isUnauthorized());
        verifyNoInteractions(coordinator);
    }

    @Test
    void verifiedPeerCommandReachesCoordinatorWithAuthenticatedSource() throws Exception {
        var receipt = new SupplyGrantSourceResultDTO("op-1", SupplyGrantSourceResultDTO.Outcome.APPLIED, null, 3, 8, "h");
        when(coordinator.apply( any())).thenReturn(receipt);
        mockMvc.perform(tls(forgedHeaders(command(REVOKE_BY_ASSET)), asset))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.operationId").value("op-1"))
                .andExpect(jsonPath("$.data.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.data.aggregateRevision").value(8));
        var captured = org.mockito.ArgumentCaptor.forClass(SupplyGrantSourceCommandDTO.class);
        verify(coordinator).apply( captured.capture());
        assertThat(captured.getValue().sourceSystem()).isEqualTo("asset");
        assertThat(captured.getValue().sourceRevision()).isEqualTo(3L);
        assertThat(captured.getValue().action()).isEqualTo(SupplyGrantSourceCommandDTO.Action.REVOKE);
    }

    @Test
    void verifiedPeerQueryIsScopedToAuthenticatedSource() throws Exception {
        when(coordinator.query( anyString(), anyString())).thenReturn(
                new SupplyGrantSourceResultDTO("op-1", SupplyGrantSourceResultDTO.Outcome.NOT_FOUND, null, 0, 0, null));
        mockMvc.perform(tls(get(OPERATION_PATH).param("sourceSystem", "asset").param("operationId", "op-1"), asset))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("NOT_FOUND"));
        verify(coordinator).query( "asset", "op-1");
    }

    @Test
    void verifiedPeerCannotSpeakForAnotherSourceSystem() {
        String byGovernance = REVOKE_BY_ASSET.replace("\"sourceSystem\":\"asset\"", "\"sourceSystem\":\"governance\"");
        assertServiceCode(() -> mockMvc.perform(tls(command(byGovernance), asset)), 1_500_042_017);
        assertServiceCode(() -> mockMvc.perform(tls(get(OPERATION_PATH)
                .param("sourceSystem", "governance").param("operationId", "op-1"), asset)), 1_500_042_017);
        verifyNoInteractions(coordinator);
    }

    @Test
    void adapterWithoutTheGateAttributeFailsClosed() {
        // 即使过滤器未注册（例如路径映射被改动），适配层本身也不接受缺少已认证身份的请求。
        MockMvc ungated = MockMvcBuilders.standaloneSetup(adapter).build();
        assertServiceCode(() -> ungated.perform(forgedHeaders(command(REVOKE_BY_ASSET)).secure(true)
                .requestAttr(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{asset})),
                1_500_042_016);
        verifyNoInteractions(coordinator);
    }

    @Test
    void verifiedPeerCommandWorksWithoutTenantContext() throws Exception {
        when(coordinator.apply(any())).thenReturn(new SupplyGrantSourceResultDTO(
                "op-1", SupplyGrantSourceResultDTO.Outcome.APPLIED, null, 3, 8, "h"));
        mockMvc.perform(tls(command(REVOKE_BY_ASSET), asset))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("APPLIED"));
        verify(coordinator).apply(any());
    }

    private static void assertServiceCode(ThrowingRunnable runnable, int code) {
        assertThatThrownBy(runnable::run)
                .extracting(ex -> rootCause(ex))
                .isInstanceOfSatisfying(ServiceException.class, se -> assertThat(se.getCode()).isEqualTo(code));
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
