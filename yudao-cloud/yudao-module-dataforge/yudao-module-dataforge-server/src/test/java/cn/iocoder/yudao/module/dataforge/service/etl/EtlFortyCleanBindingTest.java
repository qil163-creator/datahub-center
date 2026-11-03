package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.quality.core.clean.CleanFunctionRegistry;
import cn.iocoder.yudao.module.governance.api.etl.dto.CleanRulePlanRespDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class EtlFortyCleanBindingTest {
    private final EtlGovernanceCleanExecutor executor = new EtlGovernanceCleanExecutor(new CleanFunctionRegistry());

    @Test void wholeRowDeletionInspectsEveryActualFieldAndKeepsUsefulZeroValues() {
        var rules = List.of(rule("REMOVE_EMPTY_ROW", Map.of("columns", List.of())));
        var rows = List.of(row("a", null, "b", " "), row("a", null, "b", 0), row("a", "useful", "b", ""));
        assertTrue(executor.requiresRowSet(rules));
        assertEquals(rows.subList(1, 3), executor.applyRows(rules, rows));
        assertNull(rows.getFirst().get("a"));
        assertThrows(IllegalArgumentException.class, () -> executor.applyRows(List.of(rule("REMOVE_EMPTY_ROW", Map.of("columns", List.of("a")))), rows));
    }

    @Test void completeInputMeanAndUnitConversionRunInFrozenOrderWithExactNumbers() {
        var rows = List.of(row("id", 1, "amount", "1000"), row("id", 2, "amount", null), row("id", 3, "amount", "3000"));
        var rules = List.of(rule("NUMBER_FILL_NULL", Map.of("columns", List.of("amount"), "mode", "MEAN", "scale", 3)),
                rule("UNIT_CONVERSION", Map.of("columns", List.of("amount"), "factor", "0.001", "scale", 3)));
        assertTrue(executor.requiresRowSet(rules));
        assertEquals(List.of(new BigDecimal("1.000"), new BigDecimal("2.000"), new BigDecimal("3.000")),
                executor.applyRows(rules, rows).stream().map(row -> row.get("amount")).toList());
        assertNull(rows.get(1).get("amount"));
        assertThrows(IllegalStateException.class, () -> executor.apply(rules, rows.getFirst()));
        assertThrows(IllegalArgumentException.class, () -> executor.applyRows(List.of(rule("NUMBER_FILL_NULL",
                Map.of("columns", List.of("amount", "id"), "mode", "MEAN"))), rows));
    }

    record Auxiliary(String strategy, Map<String, Object> config, List<String> required) { }
    static Stream<Auxiliary> auxiliaryRules() {
        return Stream.of(new Auxiliary("PK_DEDUP_LATEST", Map.of("timeField", "updated"), List.of("updated")),
                new Auxiliary("DEDUP_BY_PRIORITY", Map.of("priorityField", "source", "priorityValues", List.of("high", "low"), "timeField", "updated"), List.of("source", "updated")),
                new Auxiliary("DEDUP_PREFER_NON_NULL", Map.of("preferredFields", List.of("phone", "name"), "timeField", "updated"), List.of("phone", "name", "updated")));
    }
    @ParameterizedTest @MethodSource("auxiliaryRules")
    void runtimeRejectsEveryMissingAuxiliaryFieldBeforeSelection(Auxiliary example) {
        Map<String, Object> config = new LinkedHashMap<>(example.config()); config.put("columns", List.of("id"));
        var row = row("id", "a", "source", "high", "phone", "1", "name", "A", "updated", "2026-01-01");
        assertEquals(List.of(row), executor.applyRows(List.of(rule(example.strategy(), config)), List.of(row)));
        for (String required : example.required()) {
            Map<String, Object> incomplete = new LinkedHashMap<>(row); incomplete.remove(required);
            assertThrows(IllegalStateException.class, () -> executor.applyRows(List.of(rule(example.strategy(), config)), List.of(incomplete)), required);
        }
    }

    private static CleanRulePlanRespDTO.Rule rule(String strategy, Map<String, Object> config) {
        var rule = new CleanRulePlanRespDTO.Rule(); rule.setRuleId(1L); rule.setCode("CLEAN"); rule.setName(strategy); rule.setStrategyKey(strategy); rule.setConfig(config); return rule;
    }
    private static Map<String, Object> row(Object... fields) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) row.put((String) fields[index], fields[index + 1]);
        return row;
    }
}
