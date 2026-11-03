package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.compile.EtlSubflowExpander;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlPipelineSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlPipelineDO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlSubflowParameterIdentityTest {

    private final EtlPipelineServiceImpl service = new EtlPipelineServiceImpl();
    private final List<EtlPipelineSaveReqVO.ParamItem> definitions = List.of(parameter());
    private final Map<String, String> changed = Map.of("scope", "changed");

    @Test
    void changedBoundaryBridgeConfigurationIsRejectedWhenRuntimeParameterChanges() {
        assertThrows(RuntimeException.class, () -> validate(
                node("boundary-bridge", "{\"fields\":[{\"name\":\"source_id\"}]}"),
                node("boundary-bridge", "{\"fields\":[{\"name\":\"customer_id\"}]}") ));
    }

    @Test
    void turningPublishedGovernanceCleanIntoLocalCleanCannotBypassProtection() {
        assertThrows(RuntimeException.class, () -> validate(
                node("data-clean", "{\"executionMode\":\"LOCAL\",\"ruleBindings\":[{\"ruleId\":12}] }"),
                node("data-clean", "{\"executionMode\":\"GOVERNANCE\",\"ruleBindings\":[{\"ruleId\":12}] }")));
    }

    @Test
    void topLevelGovernanceOrFieldRemovalModeCannotBeOverriddenThroughParameter() {
        assertTopLevelCleanOverrideRejected("{\"executionMode\":\"${params.mode}\"}", "mode", "GOVERNANCE", "LOCAL");
        assertTopLevelCleanOverrideRejected("{\"enableFieldRemoval\":\"${params.mode}\"}", "mode", "true", "false");
        assertTopLevelCleanOverrideRejected("{\"enableFieldRemoval\":\"${params.mode}\"}", "mode", " true ", "false");
        assertTopLevelCleanOverrideRejected("{\"enableFieldRemoval\":\"${params.mode}\"}", "mode", "1", "false");
        assertTopLevelCleanOverrideRejected("{\"enableFieldRemoval\":\"${params.mode}\"}", "mode", "on", "false");
        assertTopLevelCleanOverrideRejected("{\"enableFieldRemoval\":\"yes\",\"fieldsToRemove\":\"${params.fields}\"}",
                "fields", "source_id", "customer_id");
    }

    @Test
    void childPureTransformMayStillUseChangedRuntimeParameters() {
        assertDoesNotThrow(() -> validate(
                node("column-map", "{\"mapping\":{\"source\":\"target\"}}"),
                node("column-map", "{\"mapping\":{\"source\":\"renamed\"}}")));
    }

    @Test
    void ordinaryPipelineOverrideDoesNotRequireASubflowPublishedSnapshot() {
        var ordinary = node("column-map", "{\"mapping\":{\"source\":\"renamed\"}}");
        var expanded = new EtlSubflowExpander.Result(List.of(ordinary), List.<EtlEdgeDef>of(), List.of(),
                Map.of(ordinary.getNo(), new EtlSubflowExpander.Origin("", "ordinary", "", "ordinary")));
        var pipeline = EtlPipelineDO.builder().build();
        assertDoesNotThrow(() -> service.assertExpandedSubflowIdentityStable(pipeline, definitions, changed, expanded));
    }

    @Test
    void prepareRunRejectsChangedNestedBoundaryAndGovernanceConfigBeforeDownstreamServices() {
        assertPrepareRejected("boundary-bridge", "{\"fields\":[{\"name\":\"source_id\"}]}",
                "boundary-bridge", "{\"fields\":[{\"name\":\"customer_id\"}]}");
        assertPrepareRejected("data-clean", "{\"executionMode\":\"LOCAL\",\"ruleBindings\":[{\"ruleId\":12}]}",
                "data-clean", "{\"executionMode\":\"GOVERNANCE\",\"ruleBindings\":[{\"ruleId\":12}]}");
    }

    @Test
    void prepareRunRejectsNestedVersionSwitchEvenWhenNodeIdAndBoundaryConfigAreUnchanged() {
        assertPrepareRejected("boundary-bridge", "{\"fields\":[{\"name\":\"source_id\"}]}",
                "boundary-bridge", "{\"fields\":[{\"name\":\"source_id\"}]}", "child:3");
    }

    @Test
    void prepareRunFreezesRuntimeParametersInsteadOfReusingPublishedDefaults() {
        var pipelineService = new EtlPipelineServiceImpl();
        var subflows = mock(EtlSubflowService.class);
        var environments = mock(EtlEnvironmentService.class);
        var policies = mock(EtlPolicyPlanService.class);
        var targets = mock(EtlTargetBatchService.class);

        var quality = mock(cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService.class);
        var access = mock(EtlNodeAccessService.class);
        ReflectionTestUtils.setField(pipelineService, "subflowService", subflows);
        ReflectionTestUtils.setField(pipelineService, "environmentService", environments);
        ReflectionTestUtils.setField(pipelineService, "policyPlanService", policies);
        ReflectionTestUtils.setField(pipelineService, "targetBatchService", targets);

        ReflectionTestUtils.setField(pipelineService, "qualityGateService", quality);
        ReflectionTestUtils.setField(pipelineService, "nodeAccessService", access);
        ReflectionTestUtils.setField(pipelineService, "designService", mock(EtlDesignService.class));
        ReflectionTestUtils.setField(pipelineService, "executorRegistry",
                new cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry());

        var source = node("read-db", "{\"databaseId\":1,\"tableName\":\"source\"}");
        source.setNo("source");
        source.setToolType(EtlNodeDef.TOOL_TYPE_INPUT);
        var transform = node("column-map", "{\"regexColumnMapper\":[{\"fromPattern\":\"^source_id$\",\"toValue\":\"${params.scope}\"}]}");
        transform.setNo("map");
        var edge = new EtlEdgeDef();
        edge.setSourceNo(source.getNo());
        edge.setTargetNo(transform.getNo());
        var nodes = List.of(source, transform);
        var edges = List.of(edge);
        var expanded = new EtlSubflowExpander.Result(nodes, edges, List.of(), Map.of());
        when(subflows.expand(anyList(), anyList(), eq(99L), eq(changed))).thenReturn(expanded);
        when(environments.reuse(isNull(), anyList(), eq(99L), any()))
                .thenReturn(new EtlEnvironmentService.Resolved(nodes, Map.of()));
        when(policies.freeze(anyList(), eq(99L), anyMap())).thenReturn(Map.of());
        when(targets.validate(any(), eq(99L), anyMap())).thenReturn(Map.of());

        var pipeline = EtlPipelineDO.builder().id(1L).status(EtlPipelineDO.STATUS_RELEASED)
                .revision(4).publishedRevision(4).taskType(1).paramsJson(
                        "[{\"name\":\"scope\",\"type\":\"STRING\",\"defaultValue\":\"default\"}]")
                .nodesJson(JSONUtil.toJsonStr(EtlSubflowService.nodes(nodes)))
                .edgesJson(JSONUtil.toJsonStr(EtlSubflowService.edges(expanded)))
                .publishedConfigJson(JSONUtil.toJsonStr(Map.of("nodes", EtlSubflowService.nodes(nodes),
                        "edges", EtlSubflowService.edges(expanded),
                        "executionNodes", EtlSubflowService.nodes(nodes),
                        "executionEdges", EtlSubflowService.edges(expanded),
                        "dependencies", List.of(), "sourceMap", Map.of(),
                        "parameters", List.of(Map.of("name", "scope", "type", "STRING", "defaultValue", "default")),
                        "environmentBindings", Map.of())))
                .build();

        var record = ReflectionTestUtils.invokeMethod(pipelineService, "prepareRun", pipeline, 1, 99L, false, changed);

        var snapshot = JSONUtil.parseObj(((cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO) record).getConfigJson());
        assertEquals("changed", snapshot.getJSONObject("parameterValues").getStr("scope"));
    }

    @Test
    void draftRequiredParameterWithoutDefaultStillRunsAndFreezesLineage() {
        var pipelineService = new EtlPipelineServiceImpl();
        var subflows = mock(EtlSubflowService.class);
        var environments = mock(EtlEnvironmentService.class);
        var policies = mock(EtlPolicyPlanService.class);
        var targets = mock(EtlTargetBatchService.class);

        var quality = mock(cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService.class);
        var access = mock(EtlNodeAccessService.class);
        ReflectionTestUtils.setField(pipelineService, "subflowService", subflows);
        ReflectionTestUtils.setField(pipelineService, "environmentService", environments);
        ReflectionTestUtils.setField(pipelineService, "policyPlanService", policies);
        ReflectionTestUtils.setField(pipelineService, "targetBatchService", targets);

        ReflectionTestUtils.setField(pipelineService, "qualityGateService", quality);
        ReflectionTestUtils.setField(pipelineService, "nodeAccessService", access);
        ReflectionTestUtils.setField(pipelineService, "designService", mock(EtlDesignService.class));
        ReflectionTestUtils.setField(pipelineService, "executorRegistry",
                new cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry());

        var source = node("read-db", "{\"databaseId\":1,\"tableName\":\"source\"}");
        source.setNo("source");
        source.setToolType(EtlNodeDef.TOOL_TYPE_INPUT);
        var transform = node("column-map", "{\"regexColumnMapper\":[{\"fromPattern\":\"^source_id$\",\"toValue\":\"${params.scope}\"}]}");
        transform.setNo("map");
        var edge = new EtlEdgeDef();
        edge.setSourceNo(source.getNo());
        edge.setTargetNo(transform.getNo());
        var nodes = List.of(source, transform);
        var edges = List.of(edge);
        var expanded = new EtlSubflowExpander.Result(nodes, edges, List.of(), Map.of());
        var values = Map.of("scope", "provided");
        when(subflows.expand(anyList(), anyList(), eq(99L), eq(values))).thenReturn(expanded);
        when(environments.freeze(isNull(), anyList(), eq(99L))).thenReturn(new EtlEnvironmentService.Resolved(nodes, Map.of()));
        when(policies.freeze(anyList(), eq(99L), anyMap())).thenReturn(Map.of());
        when(targets.validate(any(), eq(99L), anyMap())).thenReturn(Map.of());

        var pipeline = EtlPipelineDO.builder().id(1L).status(0)
                .revision(5).taskType(1).paramsJson(
                        "[{\"name\":\"scope\",\"type\":\"STRING\",\"required\":true}]")
                .nodesJson(JSONUtil.toJsonStr(EtlSubflowService.nodes(nodes)))
                .edgesJson(JSONUtil.toJsonStr(EtlSubflowService.edges(expanded))).build();
        var record = ReflectionTestUtils.invokeMethod(pipelineService, "prepareRun", pipeline, 1, 99L, false, values);

        var snapshot = JSONUtil.parseObj(((cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO) record).getConfigJson());
        assertEquals("provided", snapshot.getJSONObject("parameterValues").getStr("scope"));
    }

    private void assertPrepareRejected(String currentCode, String currentConfig,
                                       String publishedCode, String publishedConfig) {
        assertPrepareRejected(currentCode, currentConfig, publishedCode, publishedConfig, "child:2");
    }

    private void assertPrepareRejected(String currentCode, String currentConfig,
                                       String publishedCode, String publishedConfig, String currentDefinitionKey) {
        var pipelineService = new EtlPipelineServiceImpl();
        var subflows = mock(EtlSubflowService.class);
        var environments = mock(EtlEnvironmentService.class);
        var policies = mock(EtlPolicyPlanService.class);
        var targets = mock(EtlTargetBatchService.class);

        var quality = mock(cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService.class);
        var access = mock(EtlNodeAccessService.class);
        ReflectionTestUtils.setField(pipelineService, "subflowService", subflows);
        ReflectionTestUtils.setField(pipelineService, "environmentService", environments);
        ReflectionTestUtils.setField(pipelineService, "policyPlanService", policies);
        ReflectionTestUtils.setField(pipelineService, "targetBatchService", targets);

        ReflectionTestUtils.setField(pipelineService, "qualityGateService", quality);
        ReflectionTestUtils.setField(pipelineService, "nodeAccessService", access);
        ReflectionTestUtils.setField(pipelineService, "designService", mock(EtlDesignService.class));
        ReflectionTestUtils.setField(pipelineService, "executorRegistry",
                new cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry());

        var child = node(currentCode, currentConfig);
        var origin = new EtlSubflowExpander.Origin("call-1", "original", currentDefinitionKey, "call-1");
        var expanded = new EtlSubflowExpander.Result(List.of(child), List.<EtlEdgeDef>of(),
                List.of(new EtlSubflowExpander.Dependency(currentDefinitionKey, currentDefinitionKey.equals("child:2") ? "hash-2" : "hash-3")),
                Map.of(child.getNo(), origin));
        when(subflows.expand(anyList(), anyList(), eq(99L), eq(changed))).thenReturn(expanded);

        var pipeline = EtlPipelineDO.builder().id(1L).status(EtlPipelineDO.STATUS_RELEASED)
                .revision(4).publishedRevision(4).taskType(1).paramsJson(
                        "[{\"name\":\"scope\",\"type\":\"STRING\",\"defaultValue\":\"default\"}]")
                .nodesJson(JSONUtil.toJsonStr(List.of(Map.of("id", "call-1", "properties", Map.of(
                        "toolCode", "sub-pipeline", "toolType", EtlNodeDef.TOOL_TYPE_TRANSFORM,
                        "nodeJson", Map.of("pipelineId", "123", "versionId", "2",
                                "parameters", Map.of("scope", "${params.scope}")))))))
                .edgesJson("[]")
                .publishedConfigJson(JSONUtil.toJsonStr(Map.of(
                        "executionNodes", EtlSubflowService.nodes(List.of(node(publishedCode, publishedConfig))),
                        "dependencies", List.of(Map.of("key", "child:2", "contentHash", "hash-2")),
                        "sourceMap", Map.of("sf-stable-node", Map.of("callPath", "call-1",
                                "originalNodeNo", "original", "definitionKey", "child:2", "designNodeNo", "call-1")))))
                .build();

        var error = assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> ReflectionTestUtils.invokeMethod(pipelineService, "prepareRun", pipeline, 1, 99L,
                        false, changed));
        assertEquals(cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_PIPELINE_GRAPH_INVALID.getCode(),
                error.getCode());
        verify(subflows).expand(anyList(), anyList(), eq(99L), eq(changed));
        verifyNoInteractions(environments, policies, targets, quality, access);
    }

    private void validate(EtlNodeDef current, EtlNodeDef published) {
        var origin = new EtlSubflowExpander.Origin("call-1", "original", "child:2", "call-1");
        var expanded = new EtlSubflowExpander.Result(List.of(current), List.<EtlEdgeDef>of(),
                List.of(new EtlSubflowExpander.Dependency("child:2", "hash-2")),
                Map.of(current.getNo(), origin));
        var pipeline = EtlPipelineDO.builder().publishedConfigJson(JSONUtil.toJsonStr(Map.of(
                "executionNodes", EtlSubflowService.nodes(List.of(published)),
                "dependencies", List.of(Map.of("key", "child:2", "contentHash", "hash-2")),
                "sourceMap", Map.of(published.getNo(), Map.of("callPath", "call-1", "originalNodeNo", "original",
                        "definitionKey", "child:2", "designNodeNo", "call-1"))))).build();
        service.assertExpandedSubflowIdentityStable(pipeline, definitions, changed, expanded);
    }

    private void assertTopLevelCleanOverrideRejected(String config, String parameterName, String defaultValue, String override) {
        var definition = parameter(parameterName, defaultValue);
        var pipeline = EtlPipelineDO.builder().nodesJson(JSONUtil.toJsonStr(List.of(Map.of("id", "clean",
                "properties", Map.of("toolCode", "data-clean", "toolType", EtlNodeDef.TOOL_TYPE_TRANSFORM,
                        "nodeJson", config))))).build();
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "assertStableParameterOverrides", pipeline,
                        List.of(definition), Map.of(parameterName, override)));
    }

    private static EtlPipelineSaveReqVO.ParamItem parameter(String name, String defaultValue) {
        var item = new EtlPipelineSaveReqVO.ParamItem();
        item.setName(name);
        item.setType("STRING");
        item.setDefaultValue(defaultValue);
        return item;
    }

    private static EtlNodeDef node(String toolCode, String json) {
        var node = new EtlNodeDef();
        node.setNo("sf-stable-node");
        node.setName("child");
        node.setToolCode(toolCode);
        node.setToolType(EtlNodeDef.TOOL_TYPE_TRANSFORM);
        node.setNodeJson(json);
        return node;
    }

    private static EtlPipelineSaveReqVO.ParamItem parameter() {
        var item = new EtlPipelineSaveReqVO.ParamItem();
        item.setName("scope");
        item.setType("STRING");
        item.setDefaultValue("default");
        return item;
    }
}
