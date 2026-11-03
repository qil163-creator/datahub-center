package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlVersionRespVO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtlSubflowServiceTest {
    final EtlSubflowService service = new EtlSubflowService();
    final EtlVersionService versions = mock(EtlVersionService.class);
    final ExecutionAccessService access = mock(ExecutionAccessService.class);
    EtlVersionRespVO version;
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "versions", versions); ReflectionTestUtils.setField(service, "access", access);
        version = new EtlVersionRespVO(); version.setId(9007199254740993L); version.setPipelineId(7L); version.setEventType("PUBLISH"); version.setContentHash("immutable");
        version.setConfigJson(JSONUtil.toJsonStr(Map.of("nodes", List.of(
                node("in", "boundary-input", 1, Map.of("portName", "input", "fields", List.of(Map.of("name", "id", "type", "Object")))),
                node("out", "boundary-output", 3, Map.of("portName", "result", "fields", List.of(Map.of("name", "id", "type", "Object"))))),
                "edges", List.of(Map.of("sourceNodeId", "in", "targetNodeId", "out")),
                "parameters", List.of(Map.of("name", "label", "type", "STRING", "maxLength", 100,
                        "defaultValue", "DO_NOT_SHARE_DEFAULT")))));
        when(versions.get(version.getId())).thenReturn(version);
    }
    private Map<String,Object> node(String id, String code, int type, Map<String,Object> config) { return Map.of("id", id, "properties", Map.of("toolCode", code, "toolType", type, "name", id, "nodeJson", config)); }
    @Test void immutableContractContainsOnlyParameterNamesAndLosslessVersionId() {
        var contract = service.contract(version.getId(), 8L);
        assertEquals(9007199254740993L, contract.versionId()); assertEquals(List.of("label"), contract.parameters());
        assertEquals("input", contract.inputs().getFirst().name()); assertEquals("result", contract.outputs().getFirst().name());
        assertFalse(JSONUtil.toJsonStr(contract).contains("DO_NOT_SHARE_DEFAULT"));
        assertEquals("STRING", contract.parameterDefinitions().getFirst().getType());
        assertEquals(100, contract.parameterDefinitions().getFirst().getMaxLength());
        assertNull(contract.parameterDefinitions().getFirst().getDefaultValue());
        verify(access, atLeastOnce()).requireUserPermission(8L, "dataforge:etl:query");
    }
    @Test void revokedPermissionFailsBeforeVersionRead() {
        doThrow(new IllegalStateException("revoked")).when(access).requireUserPermission(8L, "dataforge:etl:query");
        assertThrows(IllegalStateException.class, () -> service.contract(version.getId(), 8L)); verifyNoInteractions(versions);
    }
    @Test void restoredDraftIsNotAReusableRelease() {
        version.setEventType("RESTORE"); assertThrows(RuntimeException.class, () -> service.contract(version.getId(), 8L));
    }
    @Test void foreignPipelineReferenceFailsBeforeExpansion() {
        var call = new EtlNodeDef(); call.setNo("call"); call.setName("call"); call.setToolCode("sub-pipeline"); call.setToolType(2);
        call.setNodeJson("{\"pipelineId\":\"99\",\"versionId\":\"9007199254740993\",\"parameters\":{\"label\":\"x\"}}");
        assertThrows(RuntimeException.class, () -> service.expand(List.of(call), List.of(), 8L));
    }

    @Test void publicFieldContractRetainsOptionalSnapshotConstraintsWithoutAddingUnknownDefaults() {
        var snapshot = JSONUtil.parseObj(version.getConfigJson());
        snapshot.getJSONArray("nodes").getJSONObject(0).getJSONObject("properties").getJSONObject("nodeJson")
                .set("fields", List.of(Map.of("name", "id", "type", "Decimal", "nullable", false, "precision", 38, "scale", 18)));
        version.setConfigJson(JSONUtil.toJsonStr(snapshot));
        var contract = service.contract(version.getId(), 8L);
        assertEquals(Map.of("name", "id", "type", "Decimal", "nullable", false, "precision", 38, "scale", 18),
                contract.inputs().getFirst().fields().getFirst());
        var old = contract.outputs().getFirst().fields().getFirst();
        assertEquals("Object", old.get("type")); assertFalse(old.containsKey("nullable"));
        assertFalse(old.containsKey("precision")); assertFalse(old.containsKey("timezone"));
    }
}
