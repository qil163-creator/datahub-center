package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlReplayReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.governance.api.quality.QualityTaskApi;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityEtlWorkOrderReplayDescribeRespDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_PIPELINE_GRAPH_INVALID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlReplayAdmissionTest {
    @Test void originalAndLatestReplayAdmissionRejectDisabledScriptsBeforeBindingOrAcceptingAReplay() {
        for (String mode : List.of("ORIGINAL", "LATEST_PUBLISHED")) {
            var fixture = new Fixture(true);
            doThrow(new IllegalStateException("private-worker-path-sentinel")).when(fixture.runner).ensureAvailable();
            try (var security = mockStatic(SecurityFrameworkUtils.class)) {
                security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(55L);
                var failure = assertThrows(ServiceException.class, () -> fixture.service.create(fixture.request(mode)));
                assertEquals(ETL_PIPELINE_GRAPH_INVALID.getCode(), failure.getCode());
                assertFalse(failure.getMessage().contains("private-worker-path-sentinel"));
                verify(fixture.runner).ensureAvailable();
                verifyNoInteractions(fixture.targetService, fixture.coordinator, fixture.nodeAccess, fixture.design, fixture.policies);
                verify(fixture.targets, never()).selectList(any());
            }
        }
    }

    @Test void originalReplayAuthorizesValidatesAndFreezesTheOriginalExactOverrides() {
        var fixture = new Fixture(false);
        fixture.assertReplayParameters("ORIGINAL", Map.of("amount", "9007199254740993.123456789012345678"));
    }

    @Test void latestPublishedReplayUsesAndFreezesItsDefaultsRatherThanStaleOverrides() {
        var fixture = new Fixture(false);
        fixture.assertReplayParameters("LATEST_PUBLISHED", Map.of("amount", "1.25"));
    }

    @Test void legacyOriginalReplayWithoutParameterValuesUsesTheSharedDefaultResolver() {
        var fixture = new Fixture(false);
        var snapshot = JSONUtil.parseObj(fixture.original.getConfigJson()); snapshot.remove("parameterValues");
        fixture.original.setConfigJson(snapshot.toString());
        fixture.assertReplayParameters("ORIGINAL", Map.of("amount", "1.25"));
    }

    @Test void malformedOriginalSnapshotValuesCannotAuthorizeOrEnqueueAReplay() {
        var fixture = new Fixture(false);
        var snapshot = JSONUtil.parseObj(fixture.original.getConfigJson());
        snapshot.set("parameterValues", Map.of("amount", 123)); fixture.original.setConfigJson(snapshot.toString());
        try (var security = mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(55L);
            assertThrows(IllegalStateException.class, () -> fixture.service.create(fixture.request("ORIGINAL")));
            verifyNoInteractions(fixture.nodeAccess, fixture.targetService, fixture.coordinator);
        }
    }

    private static final class Fixture {
        final EtlReplayService service = new EtlReplayService();
        final EtlReplayMapper replays = mock(EtlReplayMapper.class);
        final EtlRecordMapper records = mock(EtlRecordMapper.class);
        final EtlPipelineMapper pipelines = mock(EtlPipelineMapper.class);
        final EtlTargetBatchMapper targets = mock(EtlTargetBatchMapper.class);
        final EtlRunCoordinator coordinator = mock(EtlRunCoordinator.class);
        final EtlNodeAccessService nodeAccess = mock(EtlNodeAccessService.class);
        final EtlTargetBatchService targetService = mock(EtlTargetBatchService.class);
        final EtlPolicyPlanService policies = mock(EtlPolicyPlanService.class);
        final EtlDesignService design = mock(EtlDesignService.class);
        final EtlScriptRunner runner = mock(EtlScriptRunner.class);
        final EtlRecordDO original;
        final JSONObject binding = JSONUtil.parseObj("{\"binding\":\"same-authorized-target\"}");

        Fixture(boolean script) {
            var quality = mock(QualityTaskApi.class);
            var nodes = new java.util.ArrayList<EtlNodeDef>(); nodes.add(node("source", "generate-records", 1));
            var edges = new java.util.ArrayList<Map<String, Object>>();
            String previous = "source";
            if (script) {
                nodes.add(node("script", "js-script", 4));
                edges.add(Map.of("sourceNodeId", previous, "targetNodeId", "script")); previous = "script";
            }
            nodes.add(node("sink", "managed-db-output", 3));
            edges.add(Map.of("sourceNodeId", previous, "targetNodeId", "sink"));
            var snapshot = new JSONObject().set("executionNodes", EtlSubflowService.nodes(nodes)).set("executionEdges", edges)
                    .set("targetPlans", Map.of("sink", binding)).set("governancePlans", Map.of())
                    .set("parameters", List.of(Map.of("name", "amount", "type", "DECIMAL", "defaultValue", "1.25")))
                    .set("parameterValues", Map.of("amount", "9007199254740993.123456789012345678"));
            original = EtlRecordDO.builder().id(100L).pipelineId(10L).pipelineRevision(2).name("original")
                    .runStatus(EtlRecordDO.RUN_STATUS_SUCCESS).configJson(snapshot.toString()).build();
            var pipeline = EtlPipelineDO.builder().id(10L).status(EtlPipelineDO.STATUS_RELEASED)
                    .publishedRevision(3).publishedConfigJson(snapshot.toString()).build();
            when(records.selectById(100L)).thenReturn(original);
            when(pipelines.selectById(10L)).thenReturn(pipeline);
            var target = new EtlTargetBatchDO(); target.setId(201L); target.setRecordId(100L); target.setNodeNo("sink");
            target.setQualityTaskId(301L); target.setCommitStatus("COMMITTED"); target.setQualityStatus("PASSED");
            target.setIdentityHash("population-hash"); target.setInputCount(1L); target.setPayloadHash("target-payload-hash");
            target.setConfigJson(binding.toString());
            when(targets.selectList(any())).thenReturn(List.of(target));
            when(targetService.validate(any(), eq(55L), any())).thenReturn(Map.of("sink", binding));
            when(policies.decode(any())).thenReturn(Map.of());
            when(coordinator.enqueueGovernedReplay(any(), any(), any())).thenReturn(new RunReceipt(500L, false, 0, false));
            var scope = new QualityEtlWorkOrderReplayDescribeRespDTO();
            scope.setWorkOrderId(1L); scope.setSubmissionId(2L); scope.setRunId("100");
            scope.setIdentityHash("population-hash"); scope.setTotalCount(1L);
            var described = new QualityEtlWorkOrderReplayDescribeRespDTO.OriginalTarget();
            described.setTargetBatchId(201L); described.setRecordId(100L); described.setSinkNodeId("sink");
            described.setQualityTaskId(301L); scope.setOriginalTargets(List.of(described));
            when(quality.describeEtlWorkOrderReplay(any())).thenReturn(CommonResult.success(scope));
            var admission = new EtlScriptAdmissionService(); ReflectionTestUtils.setField(admission, "scriptRunner", runner);
            Map<String, Object> dependencies = new java.util.LinkedHashMap<>();
            dependencies.put("replays", replays); dependencies.put("records", records); dependencies.put("pipelines", pipelines);
            dependencies.put("targets", targets); dependencies.put("coordinator", coordinator); dependencies.put("nodeAccess", nodeAccess);
            dependencies.put("targetService", targetService); dependencies.put("policies", policies); dependencies.put("design", design);
            dependencies.put("access", mock(ExecutionAccessService.class)); dependencies.put("quality", quality);
            dependencies.put("archives", mock(EtlArchiveService.class)); dependencies.put("environments", mock(EtlEnvironmentService.class));
            dependencies.put("scriptAdmissionService", admission);
            dependencies.forEach((name, dependency) -> ReflectionTestUtils.setField(service, name, dependency));
        }

        void assertReplayParameters(String mode, Map<String, String> expected) {
            try (var security = mockStatic(SecurityFrameworkUtils.class)) {
                security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(55L);
                assertEquals(500L, service.create(request(mode)).recordId());
                verify(nodeAccess).authorize(anyList(), eq(55L), eq(expected));
                verify(targetService).validate(any(), eq(55L), eq(expected));
                var prepared = ArgumentCaptor.forClass(EtlRecordDO.class);
                verify(coordinator).enqueueGovernedReplay(any(), prepared.capture(), any());
                var frozen = JSONUtil.parseObj(prepared.getValue().getConfigJson());
                assertEquals(expected, EtlRunParameterResolver.resolve(frozen.getJSONArray("parameters"), frozen.getJSONObject("parameterValues")));
                assertEquals(expected.get("amount"), frozen.getJSONObject("parameterValues").get("amount"));
                assertEquals(mode.equals("ORIGINAL") ? 2 : 3, prepared.getValue().getPipelineRevision());
            }
        }
        EtlReplayReqVO request(String mode) {
            var request = new EtlReplayReqVO(); request.setWorkOrderId(1L); request.setSubmissionId(2L);
            request.setRequestId("stable-request"); request.setSnapshotMode(mode); request.setReason("fixture replay");
            request.setConfirmedTargetReplacement(true); return request;
        }
        private static EtlNodeDef node(String id, String code, int type) {
            var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setToolType(type);
            node.setNodeJson("{}"); return node;
        }
    }
}
