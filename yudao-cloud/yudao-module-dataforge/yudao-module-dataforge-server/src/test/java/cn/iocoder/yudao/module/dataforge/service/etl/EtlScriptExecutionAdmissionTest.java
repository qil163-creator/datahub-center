package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.etl.core.engine.EtlPipelineEngine;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlNodeRecordMapper;
import cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_PIPELINE_GRAPH_INVALID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlScriptExecutionAdmissionTest {
    @Test void admissionPreservesSafeNonScriptPathsAndDelegatesEveryScriptLanguageToThePublicRunner() {
        var runner = mock(EtlScriptRunner.class);
        var admission = admission(runner);
        admission.requireAvailable(List.of(node("source", "generate-records", 1), node("sql", "sql-script", 4)));
        verifyNoInteractions(runner);
        for (String code : List.of("java-script", "js-script")) admission.requireAvailable(List.of(node("script", code, 4)));
        verify(runner, times(2)).ensureAvailable();
    }

    @Test void admissionRedactsTrustedWorkerPathsAndUserSourceWhenRejectingUnavailableConfiguration() {
        var runner = mock(EtlScriptRunner.class);
        doThrow(new IllegalStateException("private-worker-path-sentinel")).when(runner).ensureAvailable();
        var failure = assertThrows(ServiceException.class,
                () -> admission(runner).requireAvailable(List.of(node("script", "java-script", 4))));
        assertEquals(ETL_PIPELINE_GRAPH_INVALID.getCode(), failure.getCode());
        assertFalse(failure.getMessage().contains("private-worker-path-sentinel"));
    }

    @Test void actualClaimedExecutionRejectsDisabledRunnerBeforeTargetFileNodeOrEffectOperations() throws Exception {
        var executor = new EtlPipelineExecutor();
        var runner = mock(EtlScriptRunner.class);
        doThrow(new IllegalStateException("private-worker-path-sentinel")).when(runner).ensureAvailable();
        var coordinator = mock(EtlRunCoordinator.class);
        var target = mock(EtlTargetBatchService.class);
        var files = mock(EtlFileRecoveryService.class);
        var engine = mock(EtlPipelineEngine.class);
        var nodes = mock(EtlNodeRecordMapper.class);
        var access = mock(EtlNodeAccessService.class);
        ReflectionTestUtils.setField(executor, "scriptAdmissionService", admission(runner));
        ReflectionTestUtils.setField(executor, "runCoordinator", coordinator);
        ReflectionTestUtils.setField(executor, "targetBatchService", target);
        ReflectionTestUtils.setField(executor, "fileRecoveryService", files);
        ReflectionTestUtils.setField(executor, "pipelineEngine", engine);
        ReflectionTestUtils.setField(executor, "nodeRecordMapper", nodes);
        ReflectionTestUtils.setField(executor, "nodeAccessService", access);
        var definitions = List.of(node("source", "generate-records", 1), node("script", "js-script", 4));
        String snapshot = JsonUtils.toJsonString(Map.of("executionNodes", EtlSubflowService.nodes(definitions),
                "executionEdges", List.of(Map.of("sourceNodeId", "source", "targetNodeId", "script")), "parameters", List.of()));
        var record = EtlRecordDO.builder().id(100L).executionToken("owned-token").executionUserId(7L)
                .configJson(snapshot).build();
        var constructor = Class.forName(EtlPipelineExecutor.class.getName() + "$LocalRun").getDeclaredConstructor();
        constructor.setAccessible(true);
        Object local = constructor.newInstance();

        ReflectionTestUtils.invokeMethod(executor, "execute", record, local);

        verify(runner).ensureAvailable();
        verifyNoInteractions(target, files, engine, nodes, access);
        verify(coordinator, never()).startEffects(anyLong(), anyString());
        verify(coordinator).finish(eq(100L), eq("owned-token"), eq(EtlRecordDO.RUN_STATUS_FAILED),
                eq(RunProtocol.Effect.NONE), eq(true), argThat(log -> !log.contains("private-worker-path-sentinel")));
    }

    private static EtlScriptAdmissionService admission(EtlScriptRunner runner) {
        var service = new EtlScriptAdmissionService();
        ReflectionTestUtils.setField(service, "scriptRunner", runner);
        return service;
    }
    private static EtlNodeDef node(String id, String code, int type) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setToolType(type);
        node.setNodeJson("{}"); return node;
    }
}
