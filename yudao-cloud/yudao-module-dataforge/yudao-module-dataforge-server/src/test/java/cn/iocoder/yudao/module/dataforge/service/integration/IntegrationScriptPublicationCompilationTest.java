package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dolphinscheduler.model.NativeTaskParameters;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeConnectorCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeScriptCompiler;
import cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler;
import cn.iocoder.yudao.framework.etl.core.script.EtlNativeScriptProgram;
import cn.iocoder.yudao.framework.etl.core.script.EtlScriptBrokerClient;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.EngineConfig;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlGraphConverter;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlSubflowService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrationScriptPublicationCompilationTest {
    @Test void checkSchemaPublicationAndExecutionCarrySeparatePurposesAndNoSourceInNativeArguments() {
        var compiler = new IntegrationFlowCompiler(); var support = mock(IntegrationNativeScriptBindingSupport.class); var runtime = new IntegrationRuntimeProperties();
        runtime.setNativeKafkaBindings(Map.of(1L, new EtlNativeConnectorCompiler.KafkaBinding("managed:9092", Set.of("source"), Set.of("target"), EtlNativeConnectorCompiler.Authentication.NONE, true)));
        ReflectionTestUtils.setField(compiler, "properties", runtime); ReflectionTestUtils.setField(compiler, "scripts", support); ReflectionTestUtils.setField(compiler, "access", mock(ExecutionAccessService.class));
        var task = new IntegrationTaskDO(); task.setId(4L); task.setEngine("FLINK"); task.setMode("REALTIME"); task.setWorkerGroup("isolated"); task.setEngineConfigJson(JSONUtil.toJsonStr(new EngineConfig()));
        var fields = List.of(new EtlNativeScriptProgram.Field("id", "BIGINT", null));
        var binding = new EtlScriptBrokerClient.Binding("https://broker:8443/v1/script", "00000000-0000-0000-0000-000000000000", "bound", "a".repeat(64), "4", "isolated", "FLINK", "keyRef", "certRef", "caRef");
        var program = new EtlNativeScriptProgram("FLINK", true, 1, List.of(), List.of(),
                List.of(new EtlNativeScriptProgram.Stage("script", "SELECT 1 AS id", "dh_native_script_" + "a".repeat(24), fields, fields, binding, false, 1, 100)), List.of("INSERT INTO sink SELECT id FROM dh_native_script_" + "a".repeat(24)));
        var prepared = new IntegrationNativeScriptBindingSupport.Prepared(new EtlNativeScriptCompiler.Plan(new EtlNativeSqlCompiler.Analysis("", Map.of()), program), List.of());
        when(support.compile(any(), anyList(), anyList(), any(), anyString(), anyList(), any())).thenReturn(prepared);
        when(support.launcher("FLINK")).thenReturn(new NativeTaskParameters.Resource("managed-scripts.jar", "managed-scripts.jar"));
        var columns = List.of(Map.of("name", "id", "type", "Long"));
        var graph = JSONUtil.createObj().set("nodes", List.of(node("read", "read-kafka", Map.of("databaseId", 1, "topic", "source", "columns", columns)),
                node("script", "java-script", Map.of("code", "public class Example { String token = SHA256.hash(\"input\"); } // DO_NOT_INCLUDE_USER_SOURCE")), node("write", "write-kafka", Map.of("databaseId", 1, "topic", "target", "columns", columns))))
                .set("edges", List.of(Map.of("sourceNodeId", "read", "targetNodeId", "script"), Map.of("sourceNodeId", "script", "targetNodeId", "write")));
        var payload = compiler.compile(task, graph.toString()).getFirst().parameters();
        assertEquals("JAVA", payload.get("programType")); assertEquals("'cn.iocoder.yudao.framework.etl.script.flink.EtlNativeScriptMain'", payload.get("mainClass"));
        assertFalse(JSONUtil.toJsonStr(payload).contains("DO_NOT_INCLUDE_USER_SOURCE"));
        assertTrue(JSONUtil.toJsonStr(payload).contains("restart-strategy.fixed-delay.attempts=3"));
        var publication = compiler.compileForPublication(task, graph.toString()); assertSame(prepared, publication.scripts());
        compiler.compileForExecution(task, graph.toString());
        compiler.analyzeNative(task, EtlGraphConverter.toNodeDefs(EtlSubflowService.maps(graph.get("nodes"))), EtlGraphConverter.toEdgeDefs(EtlSubflowService.maps(graph.get("edges"))));
        var purposes = ArgumentCaptor.forClass(IntegrationNativeScriptBindingSupport.Purpose.class);
        verify(support, times(4)).compile(eq(task), anyList(), anyList(), any(), anyString(), anyList(), purposes.capture());
        assertEquals(List.of(IntegrationNativeScriptBindingSupport.Purpose.CHECK, IntegrationNativeScriptBindingSupport.Purpose.PUBLICATION,
                IntegrationNativeScriptBindingSupport.Purpose.RUN, IntegrationNativeScriptBindingSupport.Purpose.CHECK), purposes.getAllValues());
        verify(support, never()).complete(any()); verify(support, never()).abort(any());
        runtime.setFlinkInitializationSql("SET 'parallelism.default' = '4';");
        assertThrows(IllegalArgumentException.class, () -> compiler.compileForPublication(task, graph.toString()));
    }

    @Test void derivedAuthenticationSourceIsScopedToRecognizedNodeCodeWhileOtherSecretsRemainRejected() {
        var compiler = new IntegrationFlowCompiler(); String derived = "public class Example { String token = SHA256.hash(\"input\"); }";
        for (String code : List.of("java-script", "custom-token")) {
            for (Object config : List.of(Map.of("code", derived), JSONUtil.toJsonStr(Map.of("code", derived)), Map.of("code", "public class Example { String token = \"Bearer \" + SHA256.hash(\"input\"); }"))) {
                var graph = JSONUtil.createObj().set("nodes", List.of(Map.of("id", "script", "properties", Map.of("toolCode", code, "nodeJson", config)))).set("edges", List.of());
                var accepted = compiler.graph(graph.toString()); assertTrue(accepted.toString().contains("SHA256.hash"));
            }
            for (var config : List.<Map<String, Object>>of(Map.of("code", "String token = \"test-only-literal\";"),
                    Map.of("code", derived, "password", "test-only-literal"), Map.of("code", derived, "nested", Map.of("code", "token = derive();")),
                    Map.of("code", Map.of("unexpected", "object")), Map.of("code", "水".repeat(350000)))) {
                var graph = JSONUtil.createObj().set("nodes", List.of(node("script", code, config))).set("edges", List.of());
                var error = assertThrows(IllegalArgumentException.class, () -> compiler.graph(graph.toString())); assertFalse(error.getMessage().contains("test-only-literal"));
            }
        }
        var ordinary = JSONUtil.createObj().set("nodes", List.of(node("input", "read-db", Map.of("code", derived)))).set("edges", List.of());
        assertThrows(IllegalArgumentException.class, () -> compiler.graph(ordinary.toString()));
        var wrongLocation = JSONUtil.createObj().set("nodes", List.of(node("script", "java-script", Map.of("code", derived)))).set("edges", List.of()).set("code", derived);
        assertThrows(IllegalArgumentException.class, () -> compiler.graph(wrongLocation.toString()));
    }

    @Test void onlyCommittedPublicationActivatesAndRollbackDiscardsPreparedVersions() {
        for (boolean committed : List.of(true, false)) {
            var compiler = new IntegrationFlowCompiler(); var support = mock(IntegrationNativeScriptBindingSupport.class); ReflectionTestUtils.setField(compiler, "scripts", support);
            var prepared = new IntegrationNativeScriptBindingSupport.Prepared(null, List.of()); var publication = new IntegrationFlowCompiler.Publication(List.of(), prepared);
            TransactionSynchronizationManager.initSynchronization();
            try {
                compiler.completePublication(publication); verifyNoInteractions(support);
                var callbacks = TransactionSynchronizationManager.getSynchronizations(); assertEquals(1, callbacks.size());
                if (committed) callbacks.forEach(TransactionSynchronization::afterCommit);
                callbacks.forEach(callback -> callback.afterCompletion(committed ? TransactionSynchronization.STATUS_COMMITTED : TransactionSynchronization.STATUS_ROLLED_BACK));
                if (committed) { verify(support).complete(prepared); verify(support, never()).abort(any()); }
                else { verify(support).abort(prepared); verify(support, never()).complete(any()); }
            } finally { TransactionSynchronizationManager.clearSynchronization(); }
        }
    }

    private static Map<String, Object> node(String id, String code, Map<String, Object> config) {
        return Map.of("id", id, "properties", Map.of("name", id, "toolCode", code, "toolType", code.startsWith("write") ? 3 : code.startsWith("read") ? 1 : 2, "nodeJson", config));
    }
}
