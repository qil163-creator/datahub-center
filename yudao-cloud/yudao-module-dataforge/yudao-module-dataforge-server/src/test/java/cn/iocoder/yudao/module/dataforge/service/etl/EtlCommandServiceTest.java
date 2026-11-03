package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.iocoder.yudao.module.dataforge.controller.admin.etl.EtlPipelineController;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.*;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EtlCommandServiceTest {
    final EtlPipelineServiceImpl service = new EtlPipelineServiceImpl();
    final EtlRunCoordinator coordinator = mock(EtlRunCoordinator.class);
    final EtlRecordMapper records = mock(EtlRecordMapper.class);
    final EtlPipelineMapper pipelines = mock(EtlPipelineMapper.class);
    final ExecutionAccessService access = mock(ExecutionAccessService.class);

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "runCoordinator", coordinator);
        ReflectionTestUtils.setField(service, "recordMapper", records);
        ReflectionTestUtils.setField(service, "pipelineMapper", pipelines);
        ReflectionTestUtils.setField(service, "executionAccessService", access);
        when(records.selectById(42L)).thenReturn(EtlRecordDO.builder().id(42L).pipelineId(1L).build());
        when(pipelines.selectById(1L)).thenReturn(EtlPipelineDO.builder().id(1L).build());
    }

    @Test void currentPermissionIsCheckedBeforeManualReceiptHit() {
        doThrow(new IllegalStateException("revoked")).when(access).requireUserPermission(
                any(), eq("dataforge:etl:run"));
        assertThrows(IllegalStateException.class, () -> service.runPipelineCommand(1L, "stable"));
        verifyNoInteractions(coordinator);
    }

    @Test void parameterizedRunUsesCanonicalInputFingerprintForPersistedReceiptLookup() {
        long actor = 99L;
        var supplied = java.util.Map.of("id", "9007199254740993");
        String expected = service.manualInputFingerprint(3, supplied);
        var receipt = new cn.iocoder.yudao.module.dataforge.service.execution.RunReceipt(42L, true, 1, false);
        when(coordinator.findManualReceipt(1L, actor, "stable", expected)).thenReturn(java.util.Optional.of(receipt));
        try (var login = mockStatic(SecurityFrameworkUtils.class)) {
            login.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(actor);
            assertEquals(receipt, service.runPipelineCommand(1L, "stable", 3, supplied));
            verify(coordinator).findManualReceipt(1L, actor, "stable", expected);

            var changed = java.util.Map.of("id", "9007199254740994");
            String changedFingerprint = service.manualInputFingerprint(3, changed);
            when(coordinator.findManualReceipt(1L, actor, "stable", changedFingerprint))
                    .thenThrow(new IllegalStateException("request payload conflict"));
            assertThrows(IllegalStateException.class,
                    () -> service.runPipelineCommand(1L, "stable", 3, changed));
            verify(coordinator).findManualReceipt(1L, actor, "stable", changedFingerprint);
        }
        verify(access, times(2)).requireUserPermission(actor, "dataforge:etl:run");
        verify(coordinator, never()).enqueueManual(any(), any(), anyString(), anyString());
    }

    @Test void runParameterDefinitionLookupRequiresQueryAndRunPermissions() {
        doThrow(new IllegalStateException("query permission revoked")).when(access)
                .requireUserPermission(99L, "dataforge:etl:query");
        assertThrows(IllegalStateException.class, () -> service.getRunParameters(1L, 99L));
        verify(access).requireUserPermission(99L, "dataforge:etl:query");
        verify(access, never()).requireUserPermission(99L, "dataforge:etl:run");
    }

    @Test void runParameterDefinitionLookupChecksBothPermissionsAndUsesDraftRevision() {
        when(pipelines.selectById(1L)).thenReturn(EtlPipelineDO.builder().id(1L).status(0)
                .revision(7).paramsJson("[]").build());
        var response = service.getRunParameters(1L, 99L);
        assertEquals(7, response.definitionRevision());
        assertFalse(response.published());
        assertTrue(response.parameters().isEmpty());
        verify(access).requireUserPermission(99L, "dataforge:etl:query");
        verify(access).requireUserPermission(99L, "dataforge:etl:run");
    }

    @Test void runParameterDefinitionLookupUsesPublishedSnapshotInsteadOfNewerDraft() {
        String published = "{\"parameters\":[{\"name\":\"publishedValue\",\"type\":\"INTEGER\",\"defaultValue\":\"7\"}]}";
        when(pipelines.selectById(1L)).thenReturn(EtlPipelineDO.builder().id(1L)
                .status(EtlPipelineDO.STATUS_RELEASED).revision(9).publishedRevision(4)
                .paramsJson("[{\"name\":\"draftValue\",\"type\":\"STRING\"}]")
                .publishedConfigJson(published).build());

        var response = service.getRunParameters(1L, 99L);

        assertEquals(4, response.definitionRevision());
        assertTrue(response.published());
        assertEquals(1, response.parameters().size());
        assertEquals("publishedValue", response.parameters().getFirst().getName());
    }

    @Test void currentPermissionIsCheckedBeforeStopAndResolutionReceiptHits() {
        doThrow(new IllegalStateException("revoked")).when(access).requireUserPermission(any(), any(String[].class));
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "stopRecordCommand", 42L, "stop-stable"));
        verify(access).requireUserPermission(any(), eq("dataforge:etl:run"));
        var request = new EtlResolveCommandReqVO(); request.setRecordId(42L);
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(service, "resolveCommand", request));
        verify(access).requireUserPermission(any(), eq("dataforge:etl:resolve"));
        verifyNoInteractions(coordinator);
    }

    @Test void failedRunHoldingSlotIsNotReportedAsFinished() {
        when(records.selectById(42L)).thenReturn(EtlRecordDO.builder().id(42L).pipelineId(1L)
                .runStatus(4).occupiesSlot(true).reviewRequired(true).build());
        assertFalse(service.getRecordLog(42L).getEnd());
    }

    @Test void bothResolutionRoutesRequireDedicatedPermission() throws Exception {
        var legacy = EtlPipelineController.class.getMethod("resolveUnknown", EtlResolveUnknownReqVO.class);
        assertEquals("@ss.hasPermission('dataforge:etl:resolve')", legacy.getAnnotation(PreAuthorize.class).value());
        var command = EtlPipelineController.class.getMethod("resolveCommand", EtlResolveCommandReqVO.class);
        assertEquals("@ss.hasPermission('dataforge:etl:resolve')", command.getAnnotation(PreAuthorize.class).value());
    }
}
