package cn.iocoder.yudao.module.governance.api.standardterm;

import cn.iocoder.yudao.framework.jackson.config.YudaoJacksonAutoConfiguration;
import cn.iocoder.yudao.framework.security.core.rpc.VerifiedServicePeerResolver;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.governance.api.standardterm.dto.StandardTermRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardterm.StandardTermDO;
import cn.iocoder.yudao.module.governance.framework.security.StandardTermRpcFilter;
import cn.iocoder.yudao.module.governance.service.standardterm.StandardTermService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class StandardTermApiImplTest extends BaseMockitoUnitTest {

    @Mock
    private StandardTermService standardTermService;
    @InjectMocks
    private StandardTermApiImpl api;

    @BeforeEach
    void authenticateAssetChannel() throws Exception {
        var certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn(new byte[]{1, 2, 3});
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setAttribute(VerifiedServicePeerResolver.CERTIFICATE_ATTRIBUTE, new X509Certificate[]{certificate});
        new StandardTermRpcFilter(() -> Map.of("asset", Set.of(VerifiedServicePeerResolver.fingerprint(certificate))))
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
        ReflectionTestUtils.setField(api, "servletRequest", request);
    }

    @Test
    void currentTermProjectionKeepsDefinitionAliasStatusAndLosslessId() {
        var term = StandardTermDO.builder().id(9007199254740993L).name("客户编号")
                .alias("客户ID,cust_id").definition("客户在业务系统中的唯一编号").status((short) 1).build();
        when(standardTermService.getStandardTermList(List.of(term.getId()))).thenReturn(List.of(term));

        StandardTermRespDTO result = api.getTermList(List.of(term.getId())).getCheckedData().getFirst();
        assertEquals(term.getDefinition(), result.getDefinition());
        assertEquals(term.getAlias(), result.getAlias());
        assertEquals((short) 1, result.getStatus());
        var mapper = JsonMapper.builder()
                .addModule(new YudaoJacksonAutoConfiguration().timestampSupportModuleBean()).build();
        var wire = mapper.readTree(mapper.writeValueAsString(result));
        assertTrue(wire.get("id").isString());
        assertEquals("9007199254740993", wire.get("id").asString());
        assertEquals(term.getId(), mapper.treeToValue(wire, StandardTermRespDTO.class).getId());
        assertEquals(5, wire.size(), "the RPC must not expose owner, audit, or internal DO fields");
    }

    @Test
    void selectionUsesEnabledLookupAndCurrentIdsCanBeMissing() {
        var term = StandardTermDO.builder().id(10L).name("客户编号").status((short) 0).build();
        when(standardTermService.getEnabledStandardTermList("客户")).thenReturn(List.of(term));
        when(standardTermService.getStandardTermList(List.of(99L))).thenReturn(List.of());
        assertEquals(10L, api.getEnabledTermList("客户").getCheckedData().getFirst().getId());
        assertTrue(api.getTermList(List.of(99L)).getCheckedData().isEmpty());
    }

    @Test
    void rpcRejectsUnboundedAndInvalidLookups() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator().forExecutables();
            var byIds = StandardTermApi.class.getMethod("getTermList", List.class);
            assertFalse(validator.validateParameters(api, byIds, new Object[]{List.of()}).isEmpty());
            assertFalse(validator.validateParameters(api, byIds, new Object[]{Collections.nCopies(201, 1L)}).isEmpty());
            assertFalse(validator.validateParameters(api, byIds, new Object[]{List.of(-1L)}).isEmpty());
            assertTrue(validator.validateParameters(api, byIds,
                    new Object[]{List.of(9007199254740993L)}).isEmpty());
            var enabled = StandardTermApi.class.getMethod("getEnabledTermList", String.class);
            assertFalse(validator.validateParameters(api, enabled, new Object[]{"a".repeat(101)}).isEmpty());
            assertTrue(validator.validateParameters(api, enabled, new Object[]{null}).isEmpty());
        }
    }

}
