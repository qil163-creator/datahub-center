package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.builtin.ReadDbExecutor;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtlTableReadConfigTest {
    @Test void incompleteDraftsAndLosslessDecimalValuesRoundTripWithoutGrantingExecution() {
        var config = new JSONObject().set("readMode", "ID_INCREMENT").set("incrementColumn", "id").set("startValue", "9007199254740993.123456789");
        String original = config.toString();
        assertDoesNotThrow(() -> EtlTableReadConfig.validateDraft(config));
        assertEquals(original, config.toString());
        assertEquals("9007199254740993.123456789", JSONUtil.parseObj(original).getStr("startValue"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EtlTableReadConfig.requireSupportedExecution(config)).getMessage().contains("持久游标"));
        assertDoesNotThrow(() -> EtlTableReadConfig.validateDraft(new JSONObject().set("readMode", "TIME_INCREMENT").set("timeConditions", List.of(Map.of("baselineType", "FIXED")))));
        assertThrows(IllegalArgumentException.class, () -> EtlTableReadConfig.validateDraft(config.set("startValue", 9007199254740993L)));
    }

    @Test void timeConditionsValidateOperatorsStrictDatesAndMultilineSqlDrafts() {
        for (String type : List.of("FIXED", "TIME_RANGE", "SQL")) {
            var condition = new JSONObject().set("field", "updated_at").set("operator", ">=").set("baselineType", type)
                    .set("baselineValue", type.equals("SQL") ? "CURRENT_TIMESTAMP\n" : "2026-11-03").set("cursorTime", "2026-11-01");
            var config = new JSONObject().set("readMode", "TIME_INCREMENT").set("timeFormat", "yyyy-MM-dd").set("timeConditions", List.of(condition));
            assertDoesNotThrow(() -> EtlTableReadConfig.validateDraft(config));
            condition.set("operator", "OR 1=1");
            assertThrows(IllegalArgumentException.class, () -> EtlTableReadConfig.validateDraft(config));
            condition.set("operator", ">=");
            if (!type.equals("SQL")) {
                condition.set(type.equals("FIXED") ? "baselineValue" : "cursorTime", "2026-02-30");
                assertThrows(IllegalArgumentException.class, () -> EtlTableReadConfig.validateDraft(config));
            }
        }
    }

    @Test void batchDraftUsesExactBoundsAndCannotBeMistakenForJdbcFetchSize() {
        var batch = new JSONObject().set("enabled", true).set("mode", "ID").set("field", "id")
                .set("start", "9007199254740993").set("end", "9007199254740994").set("size", "1")
                .set("maxRetries", 3).set("retryIntervalSeconds", 60);
        var config = new JSONObject().set("readMode", "FULL").set("fetchSize", 5000).set("batchRead", batch);
        assertDoesNotThrow(() -> EtlTableReadConfig.validateDraft(config));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EtlTableReadConfig.requireSupportedExecution(config)).getMessage().contains("分片检查点"));
        batch.set("enabled", false);
        assertDoesNotThrow(() -> EtlTableReadConfig.requireSupportedExecution(config));
        batch.set("size", 1);
        assertDoesNotThrow(() -> EtlTableReadConfig.requireSupportedExecution(config));
        batch.set("enabled", true);
        assertThrows(IllegalArgumentException.class, () -> EtlTableReadConfig.validateDraft(config));
        batch.set("mode", "TIME").set("size", 1).set("timeUnit", "HOUR").set("start", "2026-11-03 00:00:00").set("end", "2026-11-04 00:00:00");
        assertDoesNotThrow(() -> EtlTableReadConfig.validateDraft(config));
        batch.set("maxRetries", 1.5);
        assertThrows(IllegalArgumentException.class, () -> EtlTableReadConfig.validateDraft(config));
    }

    @Test void sqlBranchRetainsInactiveTableDraftWithoutChangingSqlExecutionRules() {
        var config = new JSONObject().set("ifSql", 1).set("readMode", "TIME_INCREMENT").set("timeConditions", "unfinished")
                .set("batchRead", Map.of("enabled", true)).set("sql", "SELECT id FROM source");
        assertDoesNotThrow(() -> EtlTableReadConfig.requireSupportedExecution(config));
        config.set("ifSql", 0);
        assertThrows(IllegalArgumentException.class, () -> EtlTableReadConfig.validateDraft(config));
        config.set("readMode", "FULL").set("batchRead", Map.of("enabled", false)).set("startValue", 9007199254740993L);
        assertDoesNotThrow(() -> EtlTableReadConfig.requireSupportedExecution(config));
    }

    @Test void nativeAndLightweightPathsRejectBeforeOpeningDatasourceOrResolvingTable() {
        for (var config : List.of(Map.<String, Object>of("readMode", "ID_INCREMENT"),
                Map.<String, Object>of("batchRead", Map.of("enabled", true)))) {
            var source = node("source", "read-db", config);
            var sink = node("sink", "insert-db", Map.of());
            var edge = new EtlEdgeDef(); edge.setSourceNo("source"); edge.setTargetNo("sink");
            for (var engine : EtlNativeSqlCompiler.Engine.values())
                assertThrows(IllegalArgumentException.class, () -> EtlNativeSqlCompiler.compile(engine, false, List.of(source, sink), List.of(edge),
                        (id, table, schema) -> { throw new AssertionError("不得先解析外部数据源"); }));
            var context = mock(EtlRunContext.class);
            when(context.getVariables()).thenReturn(new cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext(Map.of()));
            var executor = new ReadDbExecutor(source, context) { public void invoke() throws Exception { execute(); } };
            assertThrows(IllegalArgumentException.class, executor::invoke);
            verify(context).getVariables(); verifyNoMoreInteractions(context);
        }
    }

    @Test void lightweightExecutionCannotSilentlyIgnoreTableProjectionOrWhereInEitherConnectionMode() {
        for (int sqlMode : List.of(0, 1)) for (var option : List.of(Map.<String, Object>of("whereCondition", "id > 10"),
                Map.<String, Object>of("selectedColumns", List.of("id")))) {
            var config = new java.util.LinkedHashMap<>(option); config.put("ifSql", sqlMode);
            var context = mock(EtlRunContext.class);
            when(context.getVariables()).thenReturn(new cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext(Map.of()));
            var executor = new ReadDbExecutor(node("source", "read-db", config), context) { public void invoke() throws Exception { execute(); } };
            assertTrue(assertThrows(IllegalArgumentException.class, executor::invoke).getMessage().contains("轻量表输入"));
            verify(context).getVariables(); verifyNoMoreInteractions(context);
        }
    }

    private static EtlNodeDef node(String id, String tool, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(tool); node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
}
