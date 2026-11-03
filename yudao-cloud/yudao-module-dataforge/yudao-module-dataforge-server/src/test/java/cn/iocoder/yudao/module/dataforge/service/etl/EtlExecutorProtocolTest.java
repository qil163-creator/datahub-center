package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.framework.etl.core.engine.EtlPipelineEngine;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EtlExecutorProtocolTest {
    final EtlPipelineExecutor executor = new EtlPipelineExecutor();
    final EtlRunCoordinator coordinator = mock(EtlRunCoordinator.class);
    final EtlPipelineEngine engine = mock(EtlPipelineEngine.class);
    final QualityGateService quality = mock(QualityGateService.class);
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(executor, "runCoordinator", coordinator);
        ReflectionTestUtils.setField(executor, "pipelineEngine", engine);
        // These protocol fixtures have no Java/JS nodes; exercise the real no-script admission path.
        ReflectionTestUtils.setField(executor, "scriptAdmissionService", new EtlScriptAdmissionService());
        ReflectionTestUtils.setField(executor, "qualityGateService", quality);
        ReflectionTestUtils.setField(executor, "executionAccessService", mock(ExecutionAccessService.class));
        ReflectionTestUtils.setField(executor, "policyPlanService", mock(EtlPolicyPlanService.class));
        ReflectionTestUtils.setField(executor, "targetBatchService", mock(EtlTargetBatchService.class));
        ReflectionTestUtils.setField(executor, "fileRecoveryService", mock(EtlFileRecoveryService.class));
        var sourceProofs = mock(EtlFileSourceProofService.class);
        var proofSession = mock(EtlFileSourceProofService.Session.class);
        // This fixture tests run completion; optional source proof must preserve the original ports and listener.
        when(sourceProofs.prepare(any(), any(), any())).thenReturn(proofSession);
        when(proofSession.wrapRead(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(proofSession.wrapFiles(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(proofSession.wrapListener(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(proofSession.checkConnection(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        ReflectionTestUtils.setField(executor, "fileSourceProofService", sourceProofs);
        ReflectionTestUtils.setField(executor, "replayService", mock(EtlReplayService.class));
        ReflectionTestUtils.setField(executor, "nodeAccessService", mock(EtlNodeAccessService.class));
        ReflectionTestUtils.setField(executor, "environmentService", mock(EtlEnvironmentService.class));

        ReflectionTestUtils.setField(executor, "operationsMetrics", mock(EtlOperationsMetrics.class));
        ReflectionTestUtils.setField(executor, "nodeRecordMapper", mock(EtlNodeRecordMapper.class));
        when(coordinator.writeOwned(anyLong(), anyString(), any())).thenReturn(true);
        when(coordinator.startEffects(anyLong(), anyString())).thenReturn(true);
        when(coordinator.finish(anyLong(), anyString(), anyInt(), any(), anyBoolean(), anyString())).thenReturn(true);
    }
    @AfterEach void clearInterrupt() { Thread.interrupted(); executor.destroy(); }

    @Test void staleExecutionMonitorCannotInterruptIndependentQualityWork() throws Exception {
        var record = EtlRecordDO.builder().id(42L).pipelineId(1L).executionUserId(7L).executionToken("token")
                .qualityTaskId(8L).configJson("""
                        {"nodes":[{"id":"a","properties":{"toolCode":"test","toolType":1}}],
                         "edges":[],"parameters":[]}
                        """).build();
        var controlType = Class.forName(EtlPipelineExecutor.class.getName() + "$LocalRun");
        var ctor = controlType.getDeclaredConstructor(); ctor.setAccessible(true);
        var control = ctor.newInstance();
        ReflectionTestUtils.invokeMethod(control, "attach");
        var success = new EtlRunResult(); success.setSuccess(true);
        when(engine.execute(any(), any())).thenReturn(success);
        when(coordinator.claimQuality(42L)).thenReturn(Optional.of(record));
        when(quality.evaluate(anyString(), anyLong(), anyLong())).thenAnswer(invocation -> {
            // 已进入的旧停止轮询即使在 finish 之后返回，也不可中断新的质量阶段。
            ReflectionTestUtils.invokeMethod(control, "signalStop");
            assertFalse(Thread.currentThread().isInterrupted(), "execution monitor leaked into quality lease");
            return new QualityGateService.Result("PASSED", 9L, "done");
        });
        ReflectionTestUtils.invokeMethod(executor, "execute", record, control);
        verify(quality).evaluate("ETL:42", 8L, 7L);
        verify(coordinator).completeQuality(eq(record), any());
    }

    @Test void exhaustedLocalCapacityNeverClaimsDurableRun() {
        ReflectionTestUtils.setField(executor, "capacity", new java.util.concurrent.Semaphore(0));
        executor.submit(42L);
        verifyNoInteractions(coordinator);
    }

    @Test void publishedFileKeepsPartialEffectWhenManagedBatchApprovalFails() throws Exception {
        var record = EtlRecordDO.builder().id(42L).pipelineId(1L).executionUserId(7L).executionToken("token")
                .configJson("""
                        {"nodes":[{"id":"a","properties":{"toolCode":"test","toolType":1}}],
                         "edges":[],"parameters":[]}
                        """).build();
        var controlType = Class.forName(EtlPipelineExecutor.class.getName() + "$LocalRun");
        var ctor = controlType.getDeclaredConstructor(); ctor.setAccessible(true);
        var control = ctor.newInstance();
        ReflectionTestUtils.invokeMethod(control, "attach");
        var success = new EtlRunResult(); success.setSuccess(true);
        success.setFileOutputStates(java.util.Map.of("a", "PUBLISHED"));
        when(engine.execute(any(), any())).thenReturn(success);
        var batches = (EtlTargetBatchService) ReflectionTestUtils.getField(executor, "targetBatchService");
        assertNotNull(batches);
        when(batches.hasManagedOutput(any())).thenReturn(true);
        when(batches.approve(any(), any())).thenReturn(false);
        ReflectionTestUtils.invokeMethod(executor, "execute", record, control);
        verify(coordinator).finish(eq(42L), eq("token"), eq(EtlRecordDO.RUN_STATUS_FAILED),
                eq(cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.PARTIAL), eq(true), anyString());
        verifyNoInteractions(quality);
    }

    @Test void unresolvedFileCannotBecomeNoEffectEvenWhenManagedOutputWasNotCommitted() throws Exception {
        var record = EtlRecordDO.builder().id(42L).pipelineId(1L).executionUserId(7L).executionToken("token")
                .configJson("""
                        {"nodes":[{"id":"a","properties":{"toolCode":"test","toolType":1}}],
                         "edges":[],"parameters":[]}
                        """).build();
        var controlType = Class.forName(EtlPipelineExecutor.class.getName() + "$LocalRun");
        var ctor = controlType.getDeclaredConstructor(); ctor.setAccessible(true);
        var control = ctor.newInstance();
        ReflectionTestUtils.invokeMethod(control, "attach");
        var result = new EtlRunResult(); result.setSuccess(false);
        result.setFileOutputStates(java.util.Map.of("a", "UNKNOWN"));
        when(engine.execute(any(), any())).thenReturn(result);
        var batches = (EtlTargetBatchService) ReflectionTestUtils.getField(executor, "targetBatchService");
        when(batches.createPort(any(), any(), any())).thenReturn(
                mock(cn.iocoder.yudao.framework.etl.core.connection.EtlStagedOutputPort.class));
        ReflectionTestUtils.invokeMethod(executor, "execute", record, control);
        verify(coordinator).finish(eq(42L), eq("token"), eq(EtlRecordDO.RUN_STATUS_UNKNOWN),
                eq(cn.iocoder.yudao.module.dataforge.service.execution.RunProtocol.Effect.UNKNOWN), eq(true), anyString());
        verifyNoInteractions(quality);
    }
}
