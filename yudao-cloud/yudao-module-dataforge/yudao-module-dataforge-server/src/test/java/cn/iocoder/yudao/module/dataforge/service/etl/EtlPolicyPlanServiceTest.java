package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.quality.config.YudaoQualityAutoConfiguration;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.governance.api.etl.GovernanceEtlApi;
import cn.iocoder.yudao.module.governance.api.etl.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlPolicyPlanServiceTest {
    private final EtlPolicyPlanService service = new EtlPolicyPlanService();
    private final GovernanceEtlApi governance = mock(GovernanceEtlApi.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final EtlQualityEvidenceService evidence = mock(EtlQualityEvidenceService.class);

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "governanceApi", governance);
        ReflectionTestUtils.setField(service, "executionAccess", access);
        ReflectionTestUtils.setField(service, "evidenceService", evidence);
        var registry = new CleanFunctionRegistry();
        ReflectionTestUtils.setField(service, "cleanFunctionRegistry", registry);
        ReflectionTestUtils.setField(service, "governanceCleanExecutor", new EtlGovernanceCleanExecutor(registry));
    }

    @Test void unavailableUnauthorizedOrNonExecutablePlanFailsBeforeRuntime() {
        var node = node("quality", "quality-check", "{\"ruleSetId\":7,\"fieldBindings\":[{\"ruleId\":1,\"field\":\"amount\"}]}");
        when(governance.getRowQualityPlan(any())).thenReturn(CommonResult.error(403, "denied"));
        assertThrows(RuntimeException.class, () -> service.freeze(List.of(node), 42L, Map.of()));
        when(governance.getRowQualityPlan(any())).thenReturn(CommonResult.success(null));
        assertThrows(RuntimeException.class, () -> service.freeze(List.of(node), 42L, Map.of()));
        RowQualityPlanRespDTO response = quality(); response.setExecutable(false);
        when(governance.getRowQualityPlan(any())).thenReturn(CommonResult.success(response));
        assertThrows(RuntimeException.class, () -> service.freeze(List.of(node), 42L, Map.of()));
        var plan = new EtlPolicyPlanService.Plan(); plan.setQuality(response);
        assertThrows(RuntimeException.class, () -> service.createPort(Map.of("quality", plan), null));
        verifyNoInteractions(evidence);
    }

    @Test void duplicateFieldBindingIsRejectedBeforeGovernanceCall() {
        var node = node("quality", "quality-check", "{\"ruleSetId\":7,\"fieldBindings\":[{\"ruleId\":1,\"field\":\"a\"},{\"ruleId\":1,\"field\":\"b\"}]}");
        assertThrows(IllegalArgumentException.class, () -> service.freeze(List.of(node), 42L, Map.of()));
        verifyNoInteractions(governance, evidence);
    }

    @Test void governanceCleanFreezesPersistedRuleParametersAndExecutesOnlyThatSnapshot() {
        CleanRulePlanRespDTO persisted = cleanPlan();
        when(governance.snapshotCleanRulePlan(any())).thenAnswer(call -> {
            CleanRulePlanReqDTO request = call.getArgument(0);
            assertEquals(42L, request.getExecutionUserId());
            assertEquals(12L, request.getRules().getFirst().getRuleId());
            assertEquals("name", request.getRules().getFirst().getField());
            if (request.getRules().getFirst().getLegacyConfig() == null) {
                assertNull(request.getRules().getFirst().getLegacyConfig());
            } else {
                assertEquals(Map.of("columns", List.of("name"), "handleType", 2),
                        request.getRules().getFirst().getLegacyConfig());
            }
            return CommonResult.success(persisted);
        });
        var node = node("clean", "data-clean", "{\"executionMode\":\"GOVERNANCE\","
                + "\"governanceRules\":[{\"ruleId\":12,\"field\":\"name\"}]}");
        var frozen = service.freeze(List.of(node), 42L, Map.of());
        var cleanPlan = frozen.get("clean").getClean();
        assertEquals(2, cleanPlan.getRules().getFirst().getConfig().get("handleType"));
        service.authorize(frozen, 42L);
        var port = service.createPort(frozen, null);
        Map<String, Object> input = new HashMap<>(Map.of("name", "A B", "id", 7));
        Map<String, Object> output = port.clean("clean", input);
        assertEquals("AB", output.get("name"));
        assertEquals(7, output.get("id"));
        assertEquals(Map.of("name", "A B", "id", 7), input);
        verify(governance, times(2)).snapshotCleanRulePlan(any());
    }

    @Test void currentPermissionRevocationRejectsFrozenPlans() {
        var qualityPlan = new EtlPolicyPlanService.Plan(); qualityPlan.setQuality(quality());
        var frozen = Map.of("quality", qualityPlan);
        service.authorize(frozen, 42L);
        verify(access).requireUserPermission(42L, "governance:quality-rule:query");
        verify(access).requireUserPermission(42L, "governance:quality-rule-set:query");
        doThrow(new IllegalStateException("permission revoked")).when(access)
                .requireUserPermission(42L, "governance:quality-rule:query");
        assertThrows(IllegalStateException.class, () -> service.authorize(frozen, 42L));
    }

    @Test void compositeCleanBindingsFreezeReauthorizeAndExecuteAsOneRowSet() {
        var persisted = cleanPlan();
        var rule = persisted.getRules().getFirst();
        rule.setStrategyKey("PK_DEDUP_FIRST");
        rule.setConfig(Map.of("columns", List.of("id", "kind")));
        when(governance.snapshotCleanRulePlan(any())).thenAnswer(call -> {
            CleanRulePlanReqDTO request = call.getArgument(0);
            assertNull(request.getRules().getFirst().getField());
            assertEquals(List.of("id", "kind"), request.getRules().getFirst().getFields());
            return CommonResult.success(persisted);
        });
        var node = node("clean", "data-clean", "{\"executionMode\":\"GOVERNANCE\","
                + "\"governanceRules\":[{\"ruleId\":12,\"fields\":[\"id\",\"kind\"]}]}");
        var plans = service.freeze(List.of(node), 42L, Map.of());
        service.authorize(plans, 42L);
        var port = service.createPort(plans, null);
        assertTrue(port.requiresCleanRowSet("clean"));
        var rows = List.<Map<String, Object>>of(Map.of("id", "a", "kind", "x", "value", "first"),
                Map.of("id", "a", "kind", "x", "value", "second"), Map.of("id", "a", "kind", "y", "value", "third"));
        assertEquals(List.of(rows.get(0), rows.get(2)), port.cleanRows("clean", rows));
        assertThrows(IllegalStateException.class, () -> port.clean("clean", rows.getFirst()));
    }

    @Test void compiledRulesCannotChangeThroughMutableSnapshotDtos() {
        var quality = quality();
        var qualityPlan = new EtlPolicyPlanService.Plan(); qualityPlan.setQuality(quality);
        var port = service.createPort(Map.of("quality", qualityPlan), null);
        quality.getRules().getFirst().getConfig().put("minValue", "999");
        assertTrue(port.evaluate("quality", Map.of("amount", "2")).isEmpty());
        assertThrows(IllegalStateException.class, () -> port.evaluate("unknown", Map.of()));
        assertThrows(IllegalStateException.class, () -> port.recordIssue("unknown", 1, Map.of(), List.of()));
    }

    @Test void evidenceFailurePropagatesAndPreviewHasNoPersistentEvidence() {
        var plan = new EtlPolicyPlanService.Plan(); plan.setQuality(quality());
        var record = EtlRecordDO.builder().id(99L).executionUserId(42L).executionToken("token").build();
        when(evidence.begin(record, "quality", "quality-hash")).thenReturn(7L);
        var port = service.createPort(Map.of("quality", plan), record);
        List<EtlRowPolicyPort.Violation> violations = port.evaluate("quality", Map.of("amount", "0"));
        doThrow(new IllegalStateException("evidence insert failed")).when(evidence)
                .recordIssue(eq(record), eq(7L), eq(1L), anyString(), eq(violations));
        assertThrows(IllegalStateException.class, () -> port.recordIssue("quality", 1, Map.of("amount", "0"), violations));
        verify(evidence, never()).complete(any(), any(), any());
        clearInvocations(evidence);
        var preview = service.createPort(Map.of("quality", plan), null);
        preview.recordIssue("quality", 1, Map.of("amount", "0"), violations);
        preview.complete("quality", new EtlRowPolicyPort.Counts(1, 0, 0, 1, 1));
        verifyNoInteractions(evidence);
    }

    @Test void evidenceBudgetRejectsOversizedRowBeforeInsert() {
        var plan = new EtlPolicyPlanService.Plan(); plan.setQuality(quality());
        var record = EtlRecordDO.builder().id(99L).executionUserId(42L).executionToken("token").build();
        when(evidence.begin(any(), anyString(), anyString())).thenReturn(7L);
        var port = service.createPort(Map.of("quality", plan), record);
        assertThrows(IllegalStateException.class, () -> port.recordIssue("quality", 1, Map.of("text", "x".repeat(130 * 1024)), List.of()));
        verify(evidence, never()).recordIssue(any(), any(), anyLong(), anyString(), any());
    }

    @Test void evidenceBudgetIncludesUtf8WidthAndAccumulatedViolationPayload() {
        var plan = new EtlPolicyPlanService.Plan(); plan.setQuality(quality());
        var record = EtlRecordDO.builder().id(99L).executionUserId(42L).executionToken("token").build();
        when(evidence.begin(any(), anyString(), anyString())).thenReturn(7L);
        var port = service.createPort(Map.of("quality", plan), record);
        assertThrows(IllegalStateException.class, () -> port.recordIssue("quality", 1, Map.of("text", "汉".repeat(50_000)), List.of()));
        verify(evidence, never()).recordIssue(any(), any(), anyLong(), anyString(), any());
        var violations = java.util.stream.IntStream.range(0, 100).mapToObj(i -> new EtlRowPolicyPort.Violation(
                "rule-" + i + "x".repeat(110), "f".repeat(128), "ERROR", "INVALID", "m".repeat(32))).toList();
        var bounded = service.createPort(Map.of("quality", plan), record);
        assertThrows(IllegalStateException.class, () -> {
            for (int i = 1; i <= 500; i++) bounded.recordIssue("quality", i, Map.of("id", i), violations);
        });
        verify(evidence, atMost(499)).recordIssue(any(), any(), anyLong(), anyString(), any());
    }

    @Test void minimalSpringContextAndSnapshotDateJsonRoundTrip() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("governanceApi", governance);
            context.getBeanFactory().registerSingleton("executionAccess", access);
            context.getBeanFactory().registerSingleton("evidenceService", evidence);
            context.register(YudaoQualityAutoConfiguration.class,
                    EtlGovernanceCleanExecutor.class, EtlPolicyPlanService.class);
            context.refresh();
            CleanFunctionRegistry registry = context.getBean(CleanFunctionRegistry.class);
            assertNotNull(registry);
            assertSame(registry, ReflectionTestUtils.getField(
                    context.getBean(EtlGovernanceCleanExecutor.class), "cleanFunctionRegistry"));
            assertSame(registry, ReflectionTestUtils.getField(
                    context.getBean(EtlPolicyPlanService.class), "cleanFunctionRegistry"));
        }
        var plan = new EtlPolicyPlanService.Plan(); plan.setQuality(quality());
        var original = Map.of("node", plan);
        var restored = service.decode(JsonUtils.parseObject(JsonUtils.toJsonString(original), Map.class));
        assertEquals(plan.getQuality().getFrozenAt(), restored.get("node").getQuality().getFrozenAt());
    }

    private static EtlNodeDef node(String id, String code, String config) {
        var node = new EtlNodeDef(); node.setNo(id); node.setToolCode(code); node.setNodeJson(config); return node;
    }
    private static CleanRulePlanRespDTO cleanPlan() {
        var rule = new CleanRulePlanRespDTO.Rule();
        rule.setRuleId(12L); rule.setCode("TRIM_ALL"); rule.setName("Remove whitespace");
        rule.setStrategyKey("REMOVE_WHITESPACE");
        rule.setConfig(new HashMap<>(Map.of("columns", List.of("name"), "handleType", 2)));
        var plan = new CleanRulePlanRespDTO(); plan.setPlanHash("clean-hash");
        plan.setRules(new ArrayList<>(List.of(rule)));
        return plan;
    }
    private static RowQualityPlanRespDTO quality() {
        var rule = new RowQualityRuleDTO(); rule.setRuleId(1L); rule.setCode("range"); rule.setStrategy("NUMERIC_RANGE");
        rule.setField("amount"); rule.setSeverity("ERROR"); rule.setConfig(new HashMap<>(Map.of("minValue", "1")));
        var quality = new RowQualityPlanRespDTO(); quality.setExecutable(true); quality.setPlanHash("quality-hash");
        quality.setFrozenAt(LocalDateTime.of(2026, 10, 3, 8, 0)); quality.setRules(new ArrayList<>(List.of(rule)));
        quality.setUnsupportedRules(List.of()); return quality;
    }
}
