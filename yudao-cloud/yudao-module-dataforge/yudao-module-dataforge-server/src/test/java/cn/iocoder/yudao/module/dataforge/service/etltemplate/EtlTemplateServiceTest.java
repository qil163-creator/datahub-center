package cn.iocoder.yudao.module.dataforge.service.etltemplate;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etltemplate.vo.EtlTemplateCreateReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etltemplate.EtlTemplateDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etltemplate.EtlTemplateMapper;
import cn.iocoder.yudao.module.dataforge.service.etl.*;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlTemplateServiceTest {
    private final EtlTemplateService service = new EtlTemplateService();
    private final EtlTemplateMapper mapper = mock(EtlTemplateMapper.class);
    private final EtlNodeAccessService access = mock(EtlNodeAccessService.class);
    private final EtlPolicyPlanService policies = mock(EtlPolicyPlanService.class);
    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), "template-test"), EtlTemplateDO.class);
        ReflectionTestUtils.setField(service, "mapper", mapper); ReflectionTestUtils.setField(service, "nodeAccess", access); ReflectionTestUtils.setField(service, "policies", policies);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L).setTenantId(5L), new MockHttpServletRequest());
        when(mapper.insert(any(EtlTemplateDO.class))).thenAnswer(inv -> { ((EtlTemplateDO) inv.getArgument(0)).setId(100L); return 1; });
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void storesOnlyImmutableSanitizedFragmentAndRechecksCurrentActorOnRead() {
        assertEquals(100L, service.create(request()));
        var capture = ArgumentCaptor.forClass(EtlTemplateDO.class); verify(mapper).insert(capture.capture()); var row = capture.getValue();
        assertFalse(row.getConfigJson().contains("runStatus")); assertFalse(row.getConfigJson().contains("selected"));
        assertEquals(DigestUtil.sha256Hex(row.getConfigJson()), row.getContentHash());
        when(mapper.selectById(100L)).thenReturn(row);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(8L).setTenantId(5L), new MockHttpServletRequest());
        assertFalse(service.get(100L).isCanDelete()); verify(access).authorize(anyList(), eq(8L), eq(Map.of()));
        doThrow(new IllegalStateException("revoked")).when(access).authorize(anyList(), eq(8L), anyMap());
        assertThrows(IllegalStateException.class, () -> service.get(100L));
        assertThrows(RuntimeException.class, () -> service.delete(100L)); verify(mapper, never()).delete(any());
    }
    @Test void duplicateRequestReturnsSameTemplateButChangedContentIsRejected() {
        service.create(request()); var capture = ArgumentCaptor.forClass(EtlTemplateDO.class); verify(mapper).insert(capture.capture());
        when(mapper.selectOne(any())).thenReturn(capture.getValue());
        assertEquals(100L, service.create(request()));
        var changed = request(); changed.setName("changed"); assertThrows(RuntimeException.class, () -> service.create(changed));
        verify(mapper, times(1)).insert(any(EtlTemplateDO.class));
    }
    @Test void credentialsScriptsUnknownConfigurationAndDanglingEdgesCannotBecomeSharedTemplates() {
        var request = request();
        for (String code : List.of("rest-api", "sql-script", "read-kafka", "write-json", "data-encrypt"))
            assertThrows(RuntimeException.class, () -> EtlTemplateGraph.sanitize(List.of(node(code, Map.of())), List.of()));
        assertThrows(RuntimeException.class, () -> EtlTemplateGraph.sanitize(List.of(node("generate-records", Map.of("count", 1, "password", "fixture-secret"))), List.of()));
        assertThrows(RuntimeException.class, () -> EtlTemplateGraph.sanitize(List.of(node("generate-records", Map.of("fields", List.of(Map.of("secretKey", "fixture-secret"))))), List.of()));
        assertThrows(RuntimeException.class, () -> EtlTemplateGraph.sanitize(request.getNodes(), List.of(Map.of("sourceNodeId", "missing", "targetNodeId", "node"))));
    }
    @Test void fragmentsMayHaveUnconnectedTransformBoundariesButCannotContainCycles() {
        var one = node("sort-records", Map.of("sorts", List.of()));
        assertEquals(1, EtlTemplateGraph.sanitize(List.of(one), List.of()).nodes().size());
        assertThrows(RuntimeException.class, () -> EtlTemplateGraph.sanitize(List.of(one), List.of(Map.of("sourceNodeId", "node", "targetNodeId", "node"))));
    }
    private static EtlTemplateCreateReqVO request() {
        var request = new EtlTemplateCreateReqVO(); request.setName("test"); request.setRequestId("fixture-request-0001");
        request.setNodes(List.of(node("generate-records", Map.of("count", 1, "fields", List.of(Map.of("name", "id", "type", "LONG", "value", "9007199254740993")))))); request.setEdges(List.of()); return request;
    }
    private static Map<String, Object> node(String code, Map<String, Object> config) {
        return Map.of("id", "node", "properties", Map.of("toolCode", code, "toolType", 1, "nodeJson", config, "selected", true, "runStatus", 3));
    }
}
