package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlPreviewReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlSchemaReqVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlDesignServiceTest {
    private final EtlDesignService service = new EtlDesignService();
    private final EtlPolicyPlanService policies = mock(EtlPolicyPlanService.class);
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "registry", new EtlExecutorRegistry());
        ReflectionTestUtils.setField(service, "previewMasks", EtlPreviewMaskSupportTest.passThrough());
        ReflectionTestUtils.setField(service, "plans", policies);
        ReflectionTestUtils.setField(service, "targets", mock(EtlTargetBatchService.class));
        ReflectionTestUtils.setField(service, "subflows", new EtlSubflowService());
        ReflectionTestUtils.setField(service, "environments", new EtlEnvironmentService());
        ReflectionTestUtils.setField(service, "nodeAccess", mock(EtlNodeAccessService.class));
    }

    @Test void structuralFailureDoesNotRequestGovernanceAndAuthorizationFailureIsVisible() {
        var request = new EtlSchemaReqVO(); request.setNodes(List.of(node("source", "unregistered", 1, "{}")));
        assertFalse(service.validate(request).valid()); verifyNoInteractions(policies);
        request.setNodes(List.of(node("source", "generate-records", 1,
                "{\"fields\":[{\"name\":\"id\",\"value\":\"1\"}]}")));
        when(policies.freeze(any(), any(), any())).thenThrow(new IllegalStateException("permission revoked"));
        var result = service.validate(request); assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> "PLAN_INVALID".equals(issue.code()) && issue.message().contains("permission revoked")));
    }

    @Test void previewOnlyFreezesTargetAncestorsAndLeavesUnrelatedGovernanceNodeAlone() {
        var request = new EtlPreviewReqVO(); request.setTargetNodeId("source"); request.setLimit(3); request.setParameters(Map.of());
        request.setNodes(List.of(node("source", "generate-records", 1,
                "{\"count\":5,\"fields\":[{\"name\":\"id\",\"value\":\"1\"}]}") ,
                node("unrelated", "quality-check", 2, "{}")));
        request.setEdges(List.of());
        when(policies.freeze(any(), any(), any())).thenAnswer(call -> {
            List<EtlNodeDef> selected = call.getArgument(0); assertEquals(List.of("source"), selected.stream().map(EtlNodeDef::getNo).toList());
            return Map.of();
        });
        var result = service.preview(request); assertTrue(result.success()); assertEquals(3, result.rows().size()); assertTrue(result.truncated());
        verify(policies).createPort(Map.of(), null);
    }

    @Test void previewRejectsMissingAuthorizedPlanBeforeExecution() {
        var request = new EtlPreviewReqVO(); request.setTargetNodeId("source"); request.setNodes(List.of(node("source", "generate-records", 1, "{}")));
        when(policies.freeze(any(), any(), any())).thenThrow(new IllegalStateException("plan unavailable"));
        assertThrows(IllegalStateException.class, () -> service.preview(request));
        verify(policies, never()).createPort(any(), any());
    }

    @Test void previewBindsTypedParameterAsLiteralJsonString() {
        var request = new EtlPreviewReqVO();
        request.setTargetNodeId("source");
        request.setLimit(3);
        request.setParameterDefinitions(List.of(parameter("payload", "STRING")));
        String payload = "quote=\"\\ slash\nnext\",\\\"admin\\\":true";
        request.setParameters(Map.of("payload", payload));
        request.setNodes(List.of(node("source", "generate-records", 1,
                "{\"count\":1,\"fields\":[{\"name\":\"payload\",\"value\":\"${params.payload}\"}]}")));
        request.setEdges(List.of());
        when(policies.freeze(any(), any(), any())).thenReturn(Map.of());

        var result = service.preview(request);

        assertTrue(result.success(), () -> result.issues().toString());
        assertEquals(payload, result.rows().getFirst().get("payload"));
        verify(policies, times(2)).freeze(any(), any(), eq(Map.of("payload", payload)));
    }

    @Test void synchronousPreviewReturnsIndependentInputAndOutputSamplesAndRechecksAccess() {
        var request = new EtlPreviewReqVO(); request.setTargetNodeId("filtered"); request.setLimit(10);
        request.setNodes(List.of(node("source", "generate-records", 1,
                "{\"count\":3,\"fields\":[{\"name\":\"id\",\"value\":\"1\"}]}"),
                node("filtered", "data-filter", 2, "{\"filterConditions\":[{\"fieldName\":\"id\",\"operator\":\"EQUALS\",\"value\":\"0\"}]}")));
        request.setEdges(List.of(Map.of("sourceNodeId", "source", "targetNodeId", "filtered")));
        when(policies.freeze(any(), any(), any())).thenReturn(Map.of());

        var result = service.preview(request);

        assertTrue(result.success(), result.issues().toString());
        assertEquals("INDEPENDENT_SAMPLES", result.comparisonMode());
        assertEquals(3, result.targetInputRows()); assertEquals(3, result.inputSamples().getFirst().rowCount());
        assertEquals(3, result.inputSamples().getFirst().rows().size()); assertTrue(result.rows().isEmpty());
        verify(policies).authorize(Map.of(), null);
    }

    private static cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlPipelineSaveReqVO.ParamItem parameter(
            String name, String type) {
        var item = new cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlPipelineSaveReqVO.ParamItem();
        item.setName(name);
        item.setType(type);
        return item;
    }

    private static Map<String, Object> node(String id, String code, int type, String config) {
        return Map.of("id", id, "properties", Map.of("toolCode", code, "toolType", type, "nodeJson", config));
    }
}
