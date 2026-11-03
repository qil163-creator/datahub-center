package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.quality.core.clean.CleanFunction;
import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.framework.etl.core.connection.EtlRowPolicyPort;
import cn.iocoder.yudao.module.governance.api.etl.dto.CleanRulePlanRespDTO;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EtlGovernanceCleanExecutorTest {

    @Test
    void appliesExistingRegistryFunctionWithoutMutatingRawInput() {
        EtlGovernanceCleanExecutor executor = new EtlGovernanceCleanExecutor(new CleanFunctionRegistry());
        Map<String, Object> raw = new LinkedHashMap<>(Map.of("name", "  Alice  ", "id", 7));
        Map<String, Object> result = executor.apply(List.of(rule("REMOVE_WHITESPACE", Map.of(
                "columns", List.of("name"), "handleType", 1))), raw);
        assertEquals("Alice", result.get("name"));
        assertEquals(7, result.get("id"));
        assertEquals(Map.of("name", "  Alice  ", "id", 7), raw);
        assertNotSame(raw, result);
    }

    @Test
    void rejectsFunctionsThatExpandRowsOrChangeTheFieldSet() {
        CleanFunctionRegistry registry = new CleanFunctionRegistry();
        registry.register(function("REMOVE_WHITESPACE", (rows, config) -> List.of(rows.getFirst(), rows.getFirst())));
        EtlGovernanceCleanExecutor executor = new EtlGovernanceCleanExecutor(registry);
        Map<String, Object> raw = Map.of("name", "Alice");
        assertThrows(IllegalStateException.class, () -> executor.apply(List.of(rule("REMOVE_WHITESPACE", Map.of(
                "columns", List.of("name")))), raw));
        assertEquals(Map.of("name", "Alice"), raw);
        registry.register(function("REMOVE_WHITESPACE", (rows, config) -> List.of(Map.of("new_field", "invalid"))));
        assertThrows(IllegalStateException.class, () -> executor.apply(List.of(rule("REMOVE_WHITESPACE", Map.of(
                "columns", List.of("name")))), raw));
    }

    @Test
    void appliesMappedValuesDatesAndCaseThroughTheSharedRegistry() {
        var executor = new EtlGovernanceCleanExecutor(new CleanFunctionRegistry());
        var raw = Map.<String, Object>of("state", "old", "name", "alice", "date", "2025/06/07");
        var rules = List.of(
                rule("MENU_CUSTOM", Map.of("columns", List.of("state"), "stringValue", List.of(Map.of("value", "old", "name", "new")))),
                rule("TO_UPPERCASE", Map.of("columns", List.of("name"))),
                rule("DATE_FORMAT_STD", Map.of("columns", List.of("date"), "inputFormats", List.of("yyyy/MM/dd"), "targetFormat", "yyyy-MM-dd")));
        assertFalse(executor.requiresRowSet(rules));
        assertEquals(Map.of("state", "new", "name", "ALICE", "date", "2025-06-07"), executor.apply(rules, raw));
        assertEquals("old", raw.get("state"));
    }

    @Test
    void completeInputDeduplicationAndDeletionPreserveRuleOrderAndSourceRows() {
        var executor = new EtlGovernanceCleanExecutor(new CleanFunctionRegistry());
        var rules = List.of(
                rule("TO_UPPERCASE", Map.of("columns", List.of("key"))),
                rule("KEEP_LATEST_OR_FIRST", Map.of("columns", List.of("key", "kind"), "stringValue",
                        List.of(Map.of("columns", "revision", "type", 0, "sort", 1)))),
                rule("REMOVE_EMPTY_COMBINATION", Map.of("columns", List.of("key", "kind"))));
        var rows = List.<Map<String, Object>>of(
                Map.of("key", "a", "kind", "x", "revision", 1),
                Map.of("key", "A", "kind", "x", "revision", 2),
                Map.of("key", "", "kind", "", "revision", 3));
        assertTrue(executor.requiresRowSet(rules));
        assertEquals(List.of(Map.of("key", "A", "kind", "x", "revision", 2)), executor.applyRows(rules, rows));
        assertEquals("a", rows.getFirst().get("key"));
        assertThrows(IllegalStateException.class, () -> executor.apply(rules, rows.getFirst()));
    }

    @Test
    void expiryDeletesOrUpdatesOnlyExistingFieldsAndRejectsUnknownSortFields() {
        var executor = new EtlGovernanceCleanExecutor(new CleanFunctionRegistry());
        var rows = List.<Map<String, Object>>of(Map.of("date", "2020-01-01", "status", "active"),
                Map.of("date", "2030-01-01", "status", "active"));
        var parameters = new LinkedHashMap<String, Object>(Map.of("columns", List.of("date"), "dataRange", 1,
                "dataRangeValue", "2025-01-01", "handleType", 1));
        assertEquals(List.of(rows.get(1)), executor.applyRows(List.of(rule("CHECK_EXPIRATION", parameters)), rows));
        parameters.put("handleType", 0); parameters.put("handleColumns", "status"); parameters.put("handleValue", "expired");
        assertEquals("expired", executor.applyRows(List.of(rule("CHECK_EXPIRATION", parameters)), rows).getFirst().get("status"));
        assertEquals("active", rows.getFirst().get("status"));
        parameters.put("handleColumns", "new_field");
        assertThrows(IllegalStateException.class, () -> executor.applyRows(List.of(rule("CHECK_EXPIRATION", parameters)), rows));
        assertThrows(IllegalStateException.class, () -> executor.applyRows(List.of(rule("KEEP_LATEST_OR_FIRST", Map.of(
                "columns", List.of("date"), "stringValue", List.of(Map.of("columns", "missing", "type", 0, "sort", 1))))), rows));
    }

    @Test
    void runtimeControlChecksEveryIntermediateRuleAndStopsBeforeOversizedOutputIsRetained() {
        var executor = new EtlGovernanceCleanExecutor(new CleanFunctionRegistry());
        var rules = List.of(rule("TO_UPPERCASE", Map.of("columns", List.of("key"))),
                rule("REMOVE_EMPTY_COMBINATION", Map.of("columns", List.of("key"))));
        var rows = List.<Map<String, Object>>of(Map.of("key", "a"), Map.of("key", ""));
        var before = new ArrayList<List<Map<String, Object>>>();
        var retained = new ArrayList<Map<String, Object>>();
        var checkpoints = new AtomicInteger();
        var control = new EtlRowPolicyPort.CleanRowSetControl() {
            @Override public void beforeRule(List<Map<String, Object>> current) { before.add(List.copyOf(current)); }
            @Override public void retainOutput(Map<String, Object> row) { retained.add(Map.copyOf(row)); }
            @Override public void checkpoint() { checkpoints.incrementAndGet(); }
        };
        assertEquals(List.of(Map.of("key", "A")), executor.applyRows(rules, rows, control));
        assertEquals(2, before.size());
        assertEquals("a", before.getFirst().getFirst().get("key"));
        assertEquals("A", before.get(1).getFirst().get("key"));
        assertEquals(3, retained.size());
        assertTrue(checkpoints.get() >= 4);
        var overflow = new EtlRowPolicyPort.CleanRowSetControl() {
            @Override public void beforeRule(List<Map<String, Object>> current) { }
            @Override public void retainOutput(Map<String, Object> row) { throw new IllegalStateException("budget"); }
            @Override public void checkpoint() { }
        };
        assertThrows(IllegalStateException.class, () -> executor.applyRows(rules, rows, overflow));
        assertEquals("a", rows.getFirst().get("key"));
    }

    @Test
    void firstRuleBudgetRejectionHappensBeforeAnyDefensiveMapCopy() {
        var entryReads = new AtomicInteger();
        var tracked = new LinkedHashMap<String, Object>() {
            @Override public java.util.Set<Map.Entry<String, Object>> entrySet() {
                entryReads.incrementAndGet();
                return super.entrySet();
            }
        };
        tracked.put("key", "a");
        var control = new EtlRowPolicyPort.CleanRowSetControl() {
            @Override public void beforeRule(List<Map<String, Object>> current) {
                assertSame(tracked, current.getFirst());
                throw new IllegalStateException("budget");
            }
            @Override public void retainOutput(Map<String, Object> row) { fail("预算失败后不得生成结果"); }
            @Override public void checkpoint() { }
        };
        var executor = new EtlGovernanceCleanExecutor(new CleanFunctionRegistry());
        assertThrows(IllegalStateException.class, () -> executor.applyRows(List.of(
                rule("TO_UPPERCASE", Map.of("columns", List.of("key")))), List.of(tracked), control));
        assertEquals(0, entryReads.get(), "预算拒绝之前不能遍历条目复制任何输入行");
        assertEquals("a", tracked.get("key"));
    }

    private static CleanRulePlanRespDTO.Rule rule(String strategy, Map<String, Object> config) {
        CleanRulePlanRespDTO.Rule rule = new CleanRulePlanRespDTO.Rule();
        rule.setRuleId(1L); rule.setCode("CLEAN-1"); rule.setName("test");
        rule.setStrategyKey(strategy); rule.setConfig(config);
        return rule;
    }

    private static CleanFunction function(String key, java.util.function.BiFunction<List<Map<String, Object>>,
            Map<String, Object>, List<Map<String, Object>>> apply) {
        return new CleanFunction() {
            @Override public ExecutionScope executionScope() { return ExecutionScope.ROW; }
            @Override public String getStrategyKey() { return key; }
            @Override public String getStrategyName() { return key; }
            @Override public String getConfigExample() { return "{}"; }
            @Override public List<Map<String, Object>> apply(List<Map<String, Object>> rows, Map<String, Object> config) {
                return apply.apply(rows, config);
            }
        };
    }
}
