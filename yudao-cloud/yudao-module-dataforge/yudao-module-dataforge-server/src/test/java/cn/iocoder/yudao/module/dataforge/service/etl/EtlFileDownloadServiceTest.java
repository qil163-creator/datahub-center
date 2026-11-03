package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.etl.config.EtlProperties;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlFileStore;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.etl.EtlFileOutputController;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlFileOutputDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.etl.EtlRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlFileOutputMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.etl.EtlRecordMapper;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real protected local objects and HTTP body; database/domain authorization adapters are isolated mocks. */
class EtlFileDownloadServiceTest {
    private static final Long FILE_ID = 9_007_199_254_740_993L;
    private static final Long RUN_ID = 9_007_199_254_741_001L;
    @TempDir Path root;
    private final EtlFileDownloadService service = new EtlFileDownloadService();
    private final EtlFileRecoveryService recovery = new EtlFileRecoveryService();
    private final EtlFileOutputMapper files = mock(EtlFileOutputMapper.class);
    private final EtlRecordMapper records = mock(EtlRecordMapper.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final EtlNodeAccessService nodeAccess = mock(EtlNodeAccessService.class);
    private final EtlFileSourceProofService sourceProof = new EtlFileSourceProofService();
    private final cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport queries =
            mock(cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport.class);
    private final EtlConnectionResolverAdapter connections = mock(EtlConnectionResolverAdapter.class);
    private final EtlRunCoordinator coordinator = mock(EtlRunCoordinator.class);
    private final EtlFileNativeSourceGuard nativeGuard = mock(EtlFileNativeSourceGuard.class);
    private EtlFileOutputDO row;
    private EtlRecordDO run;
    private EtlFileStore.Binding binding;
    private byte[] payload;

    @BeforeEach void setup() throws Exception {
        EtlFileSourceProofTest.initializeRecordTable();
        TenantContextHolder.setTenantId(7L); login(99L);
        ReflectionTestUtils.setField(service, "files", files); ReflectionTestUtils.setField(service, "records", records);
        ReflectionTestUtils.setField(service, "recovery", recovery); ReflectionTestUtils.setField(service, "access", access);
        ReflectionTestUtils.setField(service, "nodeAccess", nodeAccess);
        ReflectionTestUtils.setField(service, "sourceProof", sourceProof);
        EtlFileSourceProofTest.configure(sourceProof, queries, access, connections, records, coordinator);
        EtlFileSourceProofTest.configureNative(sourceProof, nativeGuard);
        var storage = new EtlFileStorageProperties(); storage.setStorageRoots(Map.of("exports", root.toString()));
        var policy = new EtlProperties(); policy.getFileOutput().setAllowedDirectories(List.of(root.toString()));
        ReflectionTestUtils.setField(recovery, "storageProperties", storage); ReflectionTestUtils.setField(recovery, "etlProperties", policy);
        payload = "id,name\n1,张三\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        binding = EtlFileStore.binding(root, List.of("owner-99", "run-" + RUN_ID,
                "node-" + DigestUtil.sha256Hex("output")), UUID.randomUUID().toString(), "结果.csv");
        EtlFileStore.Prepared prepared;
        try (var ignored = EtlFileStore.acquire(binding)) {
            EtlFileStore.create(binding); Files.write(binding.temporary(), payload);
            prepared = new EtlFileStore.Prepared(binding, EtlFileStore.seal(binding)); EtlFileStore.publish(prepared);
        }
        row = new EtlFileOutputDO(); row.setId(FILE_ID); row.setOwnerId(99L); row.setRecordId(RUN_ID);
        row.setPipelineId(55L); row.setNodeNo("output"); row.setOperationKey(binding.operationKey()); row.setStorageKey("exports");
        row.setStorageIdentity(binding.storageIdentity()); row.setFileName("结果.csv"); row.setState("PUBLISHED"); row.setRecoveryStatus("DONE");
        row.setPublicationApproved(true); row.setRevision(5L); row.setTemporaryResourcesJson("[]");
        row.setRetentionStatus("KEEP"); row.setPurgePhase("NONE");
        row.setTemporaryFileKey(prepared.fingerprint().fileKey()); row.setPayloadHash(prepared.fingerprint().sha256()); row.setPayloadBytes(prepared.fingerprint().bytes());
        run = new EtlRecordDO(); run.setId(RUN_ID); run.setExecutionUserId(99L); run.setPipelineId(55L);
        run.setProtocolVersion(1); run.setRunStatus(EtlRecordDO.RUN_STATUS_SUCCESS); run.setEffectState("COMPLETE");
        run.setReviewRequired(false); run.setOccupiesSlot(false); run.setResourceIds("[]"); run.setRowVersion(4L);
        run.setConfigJson(snapshot(List.of(outputNode())));
        when(files.selectById(FILE_ID)).thenReturn(row); when(records.selectById(RUN_ID)).thenReturn(run);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); TenantContextHolder.clear(); }

    @Test void requestedPurgedOrUnknownRetentionCannotStartAnHttpDownload() throws Exception {
        var controller = new EtlFileOutputController(); ReflectionTestUtils.setField(controller, "downloads", service);
        for (String retention : List.of("REQUESTED", "PURGED", "UNKNOWN")) {
            row.setRetentionStatus(retention);
            var response = new MockHttpServletResponse();
            assertThrows(RuntimeException.class, () -> controller.download(FILE_ID, response));
            assertEquals(0, response.getContentAsByteArray().length); assertNull(response.getHeader("Content-Disposition"));
            assertTrue(Files.exists(binding.target())); assertTrue(Files.exists(binding.anchor()));
        }
    }

    @Test void ownerDownloadsPublishedSuccessfulFrozenFileWithoutLossyIdConversionOrPaths() throws Exception {
        var controller = new EtlFileOutputController(); ReflectionTestUtils.setField(controller, "downloads", service);
        var response = new MockHttpServletResponse(); controller.download(FILE_ID, response);
        assertArrayEquals(payload, response.getContentAsByteArray());
        assertEquals(String.valueOf(payload.length), response.getHeader("Content-Length"));
        assertEquals("application/octet-stream", response.getContentType()); assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertTrue(response.getHeader("Content-Disposition").startsWith("attachment;"));
        assertFalse(response.getHeader("Content-Disposition").contains(root.toString()));
        assertFalse(response.getHeader("Content-Disposition").contains("\r")); assertFalse(response.getHeader("Content-Disposition").contains("\n"));
        verify(files, times(2)).selectById(FILE_ID); verify(records, times(2)).selectById(RUN_ID);
        verify(access, times(2)).requireUserPermission(99L, "dataforge:etl:query");
        assertTrue(Files.exists(binding.target())); assertTrue(Files.exists(binding.anchor()));
    }

    @Test void anotherActorCannotReadTheLedgerEvenWithQueryPermission() {
        login(100L); assertCode(ETL_FILE_NOT_OWNER.getCode(), () -> service.open(FILE_ID));
        verifyNoInteractions(records, nodeAccess);
    }

    @Test void anonymousAndMissingLedgerAreDenied() {
        SecurityContextHolder.clearContext(); assertCode(ETL_FILE_NOT_OWNER.getCode(), () -> service.open(FILE_ID));
        login(99L); when(files.selectById(FILE_ID)).thenReturn(null); assertCode(ETL_FILE_NOT_OWNER.getCode(), () -> service.open(FILE_ID));
        assertCode(ETL_FILE_NOT_OWNER.getCode(), () -> service.open(0L));
    }

    @Test void currentQueryGrantIsRequiredEvenThoughTheRunWasOwnedAndSucceeded() {
        doThrow(exception(OPERATION_PERMISSION_DENIED)).when(access).requireUserPermission(99L, "dataforge:etl:query");
        assertCode(OPERATION_PERMISSION_DENIED.getCode(), () -> service.open(FILE_ID)); verifyNoInteractions(files);
    }

    @Test void revokedGrantDuringHashPreflightClosesTheHandleBeforeWritingAnyHttpBody() throws Exception {
        doNothing().doThrow(exception(OPERATION_PERMISSION_DENIED)).when(access).requireUserPermission(99L, "dataforge:etl:query");
        var controller = new EtlFileOutputController(); ReflectionTestUtils.setField(controller, "downloads", service);
        var response = new MockHttpServletResponse();
        assertCode(OPERATION_PERMISSION_DENIED.getCode(), () -> controller.download(FILE_ID, response));
        assertEquals(0, response.getContentAsByteArray().length); assertNull(response.getHeader("Content-Disposition"));
        try (var ignored = EtlFileStore.acquire(binding)) { /* Rejected transfer released its open channel and operation fence. */ }
    }

    @Test void failedUnknownActiveOrPartiallyAppliedRunsCannotExposeAlreadyPublishedBytes() {
        for (int state : List.of(EtlRecordDO.RUN_STATUS_FAILED, EtlRecordDO.RUN_STATUS_UNKNOWN, EtlRecordDO.RUN_STATUS_RUNNING,
                EtlRecordDO.RUN_STATUS_STOPPED)) {
            run.setRunStatus(state); assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID));
        }
        run.setRunStatus(EtlRecordDO.RUN_STATUS_SUCCESS); run.setEffectState("PARTIAL"); assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID));
        run.setEffectState("COMPLETE"); run.setReviewRequired(true); assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID));
        run.setReviewRequired(false); run.setExecutionToken("still-running"); assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID));
    }

    @Test void missingRunAndForeignExecutionActorCannotReuseAnotherOwnerReceipt() {
        run.setExecutionUserId(100L); assertCode(ETL_FILE_NOT_OWNER.getCode(), () -> service.open(FILE_ID));
        run.setExecutionUserId(99L); when(records.selectById(RUN_ID)).thenReturn(null); assertCode(ETL_FILE_NOT_OWNER.getCode(), () -> service.open(FILE_ID));
    }

    @Test void unapprovedPendingUnknownAndMissingSealedEvidenceAreDenied() {
        row.setPublicationApproved(false); assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID));
        row.setPublicationApproved(true); row.setState("PUBLISHING"); assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID));
        row.setState("PUBLISHED"); row.setRecoveryStatus("MANUAL"); assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID));
        row.setRecoveryStatus("DONE"); row.setPayloadHash(null); assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID));
    }

    @Test void storageIdentityMismatchReplacedObjectAndMissingTargetFailBeforeHeadersAndPreserveFiles() throws Exception {
        row.setStorageIdentity(UUID.randomUUID().toString()); assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID));
        row.setStorageIdentity(binding.storageIdentity()); Files.delete(binding.target()); Files.write(binding.target(), payload);
        assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID)); assertArrayEquals(payload, Files.readAllBytes(binding.target()));
        Files.delete(binding.target());
        var controller = new EtlFileOutputController(); ReflectionTestUtils.setField(controller, "downloads", service);
        var response = new MockHttpServletResponse(); assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> controller.download(FILE_ID, response));
        assertEquals(0, response.getContentAsByteArray().length); assertNull(response.getHeader("Content-Disposition"));
        assertFalse(Files.exists(binding.target())); assertTrue(Files.exists(binding.anchor()));
    }

    @Test void unsafeHeaderFileNamesAndOutputSnapshotMismatchAreDenied() {
        for (String name : List.of("result.csv\r\nX-Test: injected", "result\u007f.csv", "../result.csv")) {
            row.setFileName(name); assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID));
        }
        row.setFileName("different.csv"); assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID));
    }

    @Test void historicalMissingFrozenParametersAndMissingAuxiliaryCleanupEvidenceAreDenied() {
        run.setProtocolVersion(0); assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID)); run.setProtocolVersion(1);
        var snapshot = JSONUtil.parseObj(run.getConfigJson()); snapshot.remove("parameterValues"); run.setConfigJson(snapshot.toString());
        assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID)); run.setConfigJson(snapshot(List.of(outputNode())));
        row.setTemporaryResourcesJson("[{\"name\":\"excel-sheet-1\",\"state\":\"CREATED\",\"fileKey\":\"opaque\"}]");
        assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID));
    }

    @Test void databaseDerivedFilesRequireObjectAccessAndCompletedSourceProof() {
        var source = node("input", "read-db", Map.of("databaseId", 42L, "tableName", "source"));
        run.setConfigJson(snapshot(List.of(source, outputNode())));
        assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID));
        verify(nodeAccess).authorize(argThat(nodes -> nodes.size() == 1 && "read-db".equals(nodes.getFirst().getToolCode())), eq(99L), eq(Map.of()));
        doThrow(exception(OPERATION_PERMISSION_DENIED)).when(nodeAccess).authorize(anyList(), eq(99L), anyMap());
        assertCode(OPERATION_PERMISSION_DENIED.getCode(), () -> service.open(FILE_ID));
    }

    @Test void sealedDatabaseReadProofIsRecheckedBeforeAndAfterHashingThenStreamsPublishedBytes() throws Exception {
        sealedDatabaseProof(); clearInvocations(queries, nativeGuard);
        try (var download = service.open(FILE_ID)) {
            var buffer = new ByteArrayOutputStream(); download.content().transferTo(buffer); assertArrayEquals(payload, buffer.toByteArray());
        }
        verify(queries, times(2)).normalizeSelect(42L, EtlFileSourceProofTest.SQL);
        verify(nativeGuard, times(2)).inspect(any(), eq(EtlFileSourceProofTest.SQL), eq(99L));
    }

    @Test void queryOrSourceChangesBeforeAndAfterFullHashingLeaveNoHeadersOrBody() throws Exception {
        sealedDatabaseProof();
        when(queries.normalizeSelect(42L, EtlFileSourceProofTest.SQL)).thenReturn(
                EtlFileSourceProofTest.SQL + " WHERE id = 2");
        assertNoDownloadBody();
        when(queries.normalizeSelect(42L, EtlFileSourceProofTest.SQL)).thenReturn(
                EtlFileSourceProofTest.SQL,
                EtlFileSourceProofTest.SQL + " WHERE id = 2");
        assertNoDownloadBody();
        when(queries.normalizeSelect(42L, EtlFileSourceProofTest.SQL)).thenReturn(
                EtlFileSourceProofTest.SQL);
        doThrow(exception(DATABASE_UNAVAILABLE)).when(access).requireDatabase(42L);
        assertNoDownloadBody();
        try (var ignored = EtlFileStore.acquire(binding)) { assertTrue(Files.exists(binding.target())); }
    }

    @Test void aForgedOrForeignRunProofAndAChangedSourceConnectionAreRejected() throws Exception {
        sealedDatabaseProof(); var original = run.getConfigJson();
        var json = JSONUtil.parseObj(original); json.getJSONObject(EtlFileSourceProofService.KEY).set("run", "1"); run.setConfigJson(json.toString());
        assertNoDownloadBody(); run.setConfigJson(original);
        var connection = EtlFileSourceProofTest.connection(); connection.setJdbcUrl("jdbc:postgresql://different-fixture.invalid/test");
        when(connections.resolve(42L)).thenReturn(connection); assertNoDownloadBody();
    }

    @Test void nativeSourceIdentityChangesAndLostPrivilegesBeforeOrAfterFullHashingNeverWriteHeadersOrBytes() throws Exception {
        sealedDatabaseProof();
        doThrow(new EtlFileNativeSourceGuard.Unproven()).when(nativeGuard).inspect(any(), anyString(), anyLong());
        assertNoDownloadBody();
        var original = new EtlFileNativeSourceGuard.Identity("c".repeat(64), "d".repeat(64));
        var recreated = new EtlFileNativeSourceGuard.Identity("c".repeat(64), "e".repeat(64));
        doReturn(original, recreated).when(nativeGuard).inspect(any(), anyString(), anyLong());
        assertNoDownloadBody();
        try (var ignored = EtlFileStore.acquire(binding)) { assertTrue(Files.exists(binding.target())); }
    }

    @Test void changedReceiptAfterHashingIsRejectedAndDoesNotRetainTheFence() throws Exception {
        var changed = BeanUtils.toBean(row, EtlFileOutputDO.class); changed.setRevision(6L);
        when(files.selectById(FILE_ID)).thenReturn(row, changed);
        assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> service.open(FILE_ID));
        try (var ignored = EtlFileStore.acquire(binding)) { assertTrue(Files.exists(binding.target())); }
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void malformedPrivateSnapshotsNeverEnterLogsOrExternalErrorCauses(CapturedOutput output) {
        String sentinel = "PRIVATE-DOWNLOAD-SNAPSHOT-DO-NOT-LOG";
        run.setConfigJson("{\"executionNodes\":\"" + sentinel + "\",\"parameterValues\":{}}");
        ServiceException failure = assertThrows(ServiceException.class, () -> service.open(FILE_ID));
        assertEquals(ETL_FILE_STATE_CHANGED.getCode(), failure.getCode()); assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains(root.toString())); assertFalse(output.getAll().contains(sentinel));
    }

    private Map<String, Object> outputNode() { return node("output", "write-csv", Map.of("filePath", "etl://exports", "fileName", "结果.csv")); }
    private static Map<String, Object> node(String id, String code, Map<String, Object> config) {
        return Map.of("id", id, "properties", Map.of("toolCode", code, "toolType", code.startsWith("write-") ? 3 : 1, "nodeJson", config));
    }
    private static String snapshot(List<Map<String, Object>> nodes) {
        var value = new LinkedHashMap<String, Object>(); value.put("executionNodes", nodes); value.put("parameters", List.of()); value.put("parameterValues", Map.of());
        value.put("executionEdges", nodes.size() > 1 ? List.of(Map.of("sourceNodeId", "input", "targetNodeId", "output")) : List.of());
        return JSONUtil.toJsonStr(value);
    }
    private void sealedDatabaseProof() {
        var dag = EtlFileSourceProofTest.graph(List.of(EtlFileSourceProofTest.source("input", EtlFileSourceProofTest.SQL), EtlFileSourceProofTest.output()));
        run.setConfigJson(EtlFileSourceProofTest.snapshot(dag)); run.setExecutionToken("owned-proof-token");
        when(connections.resolve(42L)).thenReturn(EtlFileSourceProofTest.connection());
        when(queries.normalizeSelect(42L, EtlFileSourceProofTest.SQL)).thenReturn(
                EtlFileSourceProofTest.SQL);
        when(coordinator.writeOwned(anyLong(), anyString(), any())).thenAnswer(call -> { ((Runnable) call.getArgument(2)).run(); return true; });
        when(records.update(isNull(), any())).thenReturn(1);
        var session = sourceProof.prepare(run, dag, Map.of());
        var read = session.wrapRead(mock(cn.iocoder.yudao.framework.etl.core.connection.EtlReadPolicyPort.class));
        read.rewrite("input", 42L, EtlFileSourceProofTest.SQL);
        EtlFileSourceProofTest.observeActualRead(read, "input", 42L, EtlFileSourceProofTest.SQL);
        session.wrapListener(new cn.iocoder.yudao.framework.etl.core.runtime.EtlRunListener() { }).onNodeSuccess("input", 0, 0);
        session.wrapFiles(mock(cn.iocoder.yudao.framework.etl.core.connection.EtlFileOutputPort.class)).approvePublish(List.of());
        run.setExecutionToken(null);
    }
    private void assertNoDownloadBody() throws Exception {
        var controller = new EtlFileOutputController(); ReflectionTestUtils.setField(controller, "downloads", service);
        var response = new MockHttpServletResponse(); assertCode(ETL_FILE_STATE_CHANGED.getCode(), () -> controller.download(FILE_ID, response));
        assertEquals(0, response.getContentAsByteArray().length); assertNull(response.getHeader("Content-Disposition"));
    }
    private void login(Long actor) { SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(actor).setTenantId(7L), new MockHttpServletRequest()); }
    private static void assertCode(Integer code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ServiceException.class, action).getCode());
    }
}
