package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.etl.core.compile.EtlSubflowExpander;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.script.EtlScriptRunner;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlPipelineDO;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.ETL_PIPELINE_GRAPH_INVALID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EtlScriptAdmissionTest {

    @Test
    void javaCheckUsesTheConfiguredWorker() {
        var service = new EtlPipelineServiceImpl();
        var runner = mock(EtlScriptRunner.class);
        ReflectionTestUtils.setField(service, "scriptRunner", runner);
        String code = "public class UserScript {}";
        when(runner.checkJava(code)).thenReturn(List.of("SCRIPT_WORKER_DISABLED"));

        assertEquals(List.of("SCRIPT_WORKER_DISABLED"), service.compileJavaScript(code));
        verify(runner).checkJava(code);
    }

    @Test
    void unavailableWorkerRejectsPublicationAndManualRunBeforeEnvironmentOrEffects() {
        for (String tool : List.of("java-script", "js-script")) {
            for (boolean publication : List.of(false, true)) {
                var service = new EtlPipelineServiceImpl();
                var runner = mock(EtlScriptRunner.class);
                var subflows = mock(EtlSubflowService.class);
                var environments = mock(EtlEnvironmentService.class);
                var admission = new EtlScriptAdmissionService();
                ReflectionTestUtils.setField(admission, "scriptRunner", runner);
                ReflectionTestUtils.setField(service, "scriptRunner", runner);
                ReflectionTestUtils.setField(service, "scriptAdmissionService", admission);
                ReflectionTestUtils.setField(service, "subflowService", subflows);
                ReflectionTestUtils.setField(service, "environmentService", environments);
                doThrow(new IllegalStateException("private-worker-path-sentinel"))
                        .when(runner).ensureAvailable();

                var node = new EtlNodeDef();
                node.setNo("script");
                node.setName("script");
                node.setToolType(EtlNodeDef.TOOL_TYPE_SCRIPT);
                node.setToolCode(tool);
                node.setNodeJson("{\"code\":\"untrusted-script-sentinel\"}");
                when(subflows.expand(anyList(), anyList(), eq(99L), eq(Map.of())))
                        .thenReturn(new EtlSubflowExpander.Result(List.of(node), List.of(), List.of(), Map.of()));
                var pipeline = EtlPipelineDO.builder().id(1L).status(0).revision(1)
                        .taskType(EtlPipelineDO.TASK_TYPE_ONCE)
                        .nodesJson(JsonUtils.toJsonString(EtlSubflowService.nodes(List.of(node))))
                        .edgesJson("[]").paramsJson("[]").build();

                var error = assertThrows(ServiceException.class,
                        () -> ReflectionTestUtils.invokeMethod(service, "prepareRun", pipeline, 1, 99L,
                                publication, Map.of()));
                assertEquals(ETL_PIPELINE_GRAPH_INVALID.getCode(), error.getCode());
                assertFalse(error.getMessage().contains("private-worker-path-sentinel"));
                assertFalse(error.getMessage().contains("untrusted-script-sentinel"));
                verify(runner).ensureAvailable();
                verifyNoInteractions(environments);
            }
        }
    }
}
