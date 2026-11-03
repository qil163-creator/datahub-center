package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.EtlResolveUnknownReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.dataforge.framework.execution.config.RunExecutionProperties;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EtlPipelineOwnershipTest {
    final EtlRecordMapper records = mock(EtlRecordMapper.class);
    final EtlRunCoordinator coordinator = mock(EtlRunCoordinator.class);
    final EtlPipelineExecutor executor = new EtlPipelineExecutor();

    @BeforeEach void setup() {
        TenantContextHolder.setTenantId(1L);
        ReflectionTestUtils.setField(executor, "recordMapper", records);
        ReflectionTestUtils.setField(executor, "runCoordinator", coordinator);
        ReflectionTestUtils.setField(executor, "executionProperties", new RunExecutionProperties());
    }
    @AfterEach void cleanup() { TenantContextHolder.clear(); executor.destroy(); }

    @Test void stopOnAnotherNodeDelegatesToDurableIntentNotLocalThreadMap() {
        executor.stop(42L);
        verify(coordinator).requestStop(42L);
        verifyNoInteractions(records);
    }

    @Test void recoveryDelegatesStageDecisionToTransactionalCoordinator() {
        var record = EtlRecordDO.builder().id(42L).build(); when(records.selectExpiredCandidates(100)).thenReturn(List.of(record));
        when(coordinator.recoverExpired(42L)).thenAnswer(invocation -> {
            assertFalse(TenantContextHolder.isIgnore());
            return true;
        });
        executor.recoverExpired();
        verify(coordinator).recoverExpired(42L);
        assertEquals(1L, TenantContextHolder.getRequiredTenantId());
    }

    @Test void publishedExecutionKeepsSnapshotWhenDraftChanges() {
        var service = new EtlPipelineServiceImpl();
        var pipeline = EtlPipelineDO.builder().id(1L).status(1).revision(8).publishedRevision(5)
                .publishedExecutionUserId(7L).name("new draft").qualityTaskId(400L)
                .nodesJson("[]").edgesJson("[]").paramsJson("[]")
                .publishedConfigJson("""
                        {"name":"released","nodes":[],"edges":[],"parameters":[],"qualityTaskId":300}
                        """).build();
        EtlPipelineDO execution = ReflectionTestUtils.invokeMethod(service, "executionSnapshot", pipeline);
        assertNotNull(execution);
        assertEquals(5, execution.getRevision()); assertEquals("released", execution.getName());
        assertEquals(300L, execution.getQualityTaskId()); assertEquals(7L, execution.getReleaseUserId());
        assertEquals(400L, pipeline.getQualityTaskId()); assertEquals(8, pipeline.getRevision());
    }

    @Test void unreleasedPipelineRunsItsCurrentDraft() {
        var service = new EtlPipelineServiceImpl();
        var pipeline = EtlPipelineDO.builder().id(1L).status(0).revision(2).name("draft")
                .nodesJson("[]").edgesJson("[]").paramsJson("[]").build();
        EtlPipelineDO execution = ReflectionTestUtils.invokeMethod(service, "executionSnapshot", pipeline);
        assertSame(pipeline, execution);
    }

    @Test void failedClaimNeverStartsLocalExecutor() {
        when(coordinator.claim(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        assertDoesNotThrow(() -> executor.submit(42L));
        verifyNoInteractions(records);
    }

    @Test void legacyResolutionUsesSameAuthorizedVersionedCoordinator() {
        var service = new EtlPipelineServiceImpl();
        var pipelines = mock(EtlPipelineMapper.class);
        var access = mock(ExecutionAccessService.class);
        ReflectionTestUtils.setField(service, "recordMapper", records);
        ReflectionTestUtils.setField(service, "pipelineMapper", pipelines);
        ReflectionTestUtils.setField(service, "runCoordinator", coordinator);
        ReflectionTestUtils.setField(service, "executionAccessService", access);
        when(records.selectById(42L)).thenReturn(EtlRecordDO.builder().id(42L).pipelineId(1L).build());
        when(pipelines.selectById(1L)).thenReturn(EtlPipelineDO.builder().id(1L).build());
        var request = new EtlResolveUnknownReqVO(); request.setRecordId(42L);
        request.setRequestId("legacy"); request.setExpectedVersion(7L);
        request.setConfirmedStopped(true); request.setOutputReviewed(true);
        request.setReason("资源及输出已核查"); request.setEvidenceRef("incident:test"); request.setOutcome("FAILED");
        service.resolveUnknown(request);
        var order = inOrder(access, coordinator);
        order.verify(access).requireUserPermission(null, "dataforge:etl:resolve");
        order.verify(coordinator).resolve(request, null);
    }
}
