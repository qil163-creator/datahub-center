package cn.iocoder.yudao.module.dataforge.service.batchfile;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.batchfile.vo.BatchFileModels.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.batchfile.*;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.infra.api.file.ManagedFileApi;
import cn.iocoder.yudao.module.infra.api.file.dto.ManagedFileRespDTO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BatchFileServiceTest {
    @Test void revokedExecutionIdentityCannotEnqueueOrClaim() {
        BatchFileRunService service = new BatchFileRunService();
        ExecutionAccessService access = mock(ExecutionAccessService.class); BatchFileRunMapper runs = mock(BatchFileRunMapper.class);
        ReflectionTestUtils.setField(service, "access", access); ReflectionTestUtils.setField(service, "runs", runs);
        doThrow(new IllegalStateException("denied")).when(access).requireUserPermission(5L, "dataforge:batch-file:execute");
        Execute request = new Execute(); request.setId(1L); request.setRequestId(UUID.randomUUID().toString());
        try { assertThrows(RuntimeException.class, () -> service.execute(request, 5L)); verifyNoInteractions(runs); }
        finally { service.close(); }
    }
    @Test void sameRequestReplaysOnlyForSameTaskAndActor() {
        BatchFileRunService service = spy(new BatchFileRunService());
        BatchFileTaskMapper tasks = mock(BatchFileTaskMapper.class); BatchFileRunMapper runs = mock(BatchFileRunMapper.class);
        ReflectionTestUtils.setField(service, "tasks", tasks); ReflectionTestUtils.setField(service, "runs", runs);
        ReflectionTestUtils.setField(service, "access", mock(ExecutionAccessService.class));
        ReflectionTestUtils.setField(service, "transaction", transaction());
        doNothing().when(service).submit(anyLong());
        BatchFileTaskDO task = new BatchFileTaskDO(); task.setId(1L); task.setOnline(false); when(tasks.lock(1L)).thenReturn(task);
        BatchFileRunDO run = new BatchFileRunDO(); run.setId(20L); run.setTaskId(1L); run.setExecutionUserId(5L); run.setStatus("SUCCESS");
        String key = UUID.randomUUID().toString();
        // 方法引用实例不稳定，使用泛型匹配仍只模拟公共单列查询合同。
        when(runs.selectOne(any(com.baomidou.mybatisplus.core.toolkit.support.SFunction.class), eq(key))).thenReturn(run);
        Execute request = new Execute(); request.setId(1L); request.setRequestId(key);
        try {
            assertTrue(service.execute(request, 5L).replayed());
            assertThrows(RuntimeException.class, () -> service.execute(request, 6L));
            verify(runs, never()).insert(any(BatchFileRunDO.class));
        } finally { service.close(); }
    }
    @Test void failedCrossServiceUploadKeepsPendingIntentWithoutRawException() {
        BatchFileService service = new BatchFileService(); BatchFileUploadMapper uploads = mock(BatchFileUploadMapper.class);
        ManagedFileApi storage = mock(ManagedFileApi.class);
        ReflectionTestUtils.setField(service, "uploads", uploads); ReflectionTestUtils.setField(service, "storage", storage);
        doAnswer(inv -> { ((BatchFileUploadDO) inv.getArgument(0)).setId(1L); return 1; }).when(uploads).insert(any(BatchFileUploadDO.class));
        when(storage.createManaged(any())).thenThrow(new IllegalStateException("secret-password"));
        var file = new MockMultipartFile("file", "test.csv", "text/csv", "字段\nvalue\n".getBytes(StandardCharsets.UTF_8));
        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.upload(file, 5L));
        assertFalse(failure.getMessage().contains("secret-password"));
        verify(uploads).insert(argThat((BatchFileUploadDO value) -> value.getOwnerId().equals(5L)
                && "PENDING".equals(value.getState()) && value.getOperationKey() != null));
        verify(uploads, never()).updateById(any(BatchFileUploadDO.class));
    }
    @Test void deletedStorageTombstoneStillRequiresSuccessfulPhysicalDelete() {
        BatchFileService service = new BatchFileService(); BatchFileUploadMapper uploads = mock(BatchFileUploadMapper.class);
        ManagedFileApi storage = mock(ManagedFileApi.class);
        ReflectionTestUtils.setField(service, "uploads", uploads); ReflectionTestUtils.setField(service, "storage", storage);
        BatchFileUploadDO upload = new BatchFileUploadDO(); upload.setId(1L); upload.setOperationKey("operation"); upload.setState("CLEANUP_PENDING");
        when(uploads.selectById(1L)).thenReturn(upload);
        ManagedFileRespDTO receipt = new ManagedFileRespDTO(); receipt.setId(2L); receipt.setState("DELETED");
        when(storage.getManaged("operation")).thenReturn(CommonResult.success(receipt));
        when(storage.deleteManaged(2L, "operation")).thenThrow(new IllegalStateException("unconfirmed")).thenReturn(CommonResult.success(true));
        when(uploads.completeCleanup(1L)).thenReturn(1);
        service.clean(1L); assertEquals("CLEANUP_PENDING", upload.getState()); verify(uploads, never()).updateById(any(BatchFileUploadDO.class));
        service.clean(1L); assertEquals("CLEANED", upload.getState()); verify(storage, times(2)).deleteManaged(2L, "operation");
        verify(uploads).completeCleanup(1L);
    }
    @Test void lateUploadReceiptCannotReviveCleanupIntent() {
        BatchFileService service = new BatchFileService(); BatchFileUploadMapper uploads = mock(BatchFileUploadMapper.class);
        ManagedFileApi storage = mock(ManagedFileApi.class);
        ReflectionTestUtils.setField(service, "uploads", uploads); ReflectionTestUtils.setField(service, "storage", storage);
        var persisted = new java.util.concurrent.atomic.AtomicReference<BatchFileUploadDO>();
        doAnswer(inv -> { BatchFileUploadDO row = inv.getArgument(0); row.setId(1L); persisted.set(row); return 1; })
                .when(uploads).insert(any(BatchFileUploadDO.class));
        byte[] bytes = "字段\nvalue\n".getBytes(StandardCharsets.UTF_8);
        ManagedFileRespDTO receipt = new ManagedFileRespDTO(); receipt.setId(2L); receipt.setState("READY");
        receipt.setSize((long) bytes.length); receipt.setSha256(BatchFileService.sha(bytes));
        when(storage.createManaged(any())).thenAnswer(inv -> {
            persisted.get().setState("CLEANUP_PENDING"); return CommonResult.success(receipt);
        });
        when(uploads.ready(1L, 2L, "5")).thenReturn(0);
        assertThrows(RuntimeException.class, () -> service.upload(new MockMultipartFile("file", "test.csv", "text/csv", bytes), 5L));
        assertEquals("CLEANUP_PENDING", persisted.get().getState());
        verify(uploads).selectById(1L);
        verify(uploads, never()).updateById(any(BatchFileUploadDO.class));
    }
    @Test void missingReceiptRetainsCleanupIntentForDelayedRemoteCreate() {
        BatchFileService service = new BatchFileService(); BatchFileUploadMapper uploads = mock(BatchFileUploadMapper.class);
        ManagedFileApi storage = mock(ManagedFileApi.class);
        ReflectionTestUtils.setField(service, "uploads", uploads); ReflectionTestUtils.setField(service, "storage", storage);
        BatchFileUploadDO upload = new BatchFileUploadDO(); upload.setId(1L); upload.setOperationKey("delayed"); upload.setState("CLEANUP_PENDING");
        when(uploads.selectById(1L)).thenReturn(upload); when(storage.getManaged("delayed")).thenReturn(CommonResult.success(null));
        service.clean(1L);
        assertEquals("CLEANUP_PENDING", upload.getState()); verify(uploads, never()).completeCleanup(anyLong());
        verify(storage, never()).deleteManaged(anyLong(), anyString());
    }
    @Test void persistentCleanupFailuresDoNotStarveLaterPages() {
        BatchFileService service = new BatchFileService(); BatchFileUploadMapper uploads = mock(BatchFileUploadMapper.class);
        ManagedFileApi storage = mock(ManagedFileApi.class);
        ReflectionTestUtils.setField(service, "uploads", uploads); ReflectionTestUtils.setField(service, "storage", storage);
        ReflectionTestUtils.setField(service, "transaction", transaction());
        List<BatchFileUploadDO> first = new ArrayList<>();
        for (long id = 1; id <= 51; id++) {
            BatchFileUploadDO row = new BatchFileUploadDO(); row.setId(id); row.setOperationKey("op-" + id);
            row.setState("CLEANUP_PENDING"); row.setExpiresAt(LocalDateTime.now().minusDays(1));
            when(uploads.lock(id)).thenReturn(row); when(uploads.selectById(id)).thenReturn(row);
            if (id <= 50) first.add(row); else when(uploads.selectExpired(50L)).thenReturn(List.of(row));
        }
        when(uploads.selectExpired(0L)).thenReturn(first);
        when(storage.getManaged(anyString())).thenThrow(new IllegalStateException("temporarily unavailable"));
        service.cleanupExpired(); service.cleanupExpired();
        verify(uploads).selectExpired(50L); verify(storage).getManaged("op-51");
    }
    @Test void editsRejectOnlineActiveAndStaleVersions() {
        BatchFileService service = new BatchFileService(); BatchFileRunMapper runs = mock(BatchFileRunMapper.class);
        ReflectionTestUtils.setField(service, "runs", runs); BatchFileTaskDO task = new BatchFileTaskDO();
        task.setId(1L); task.setRevision(2L); task.setOnline(true);
        assertThrows(RuntimeException.class, () -> service.editable(task, 2L));
        task.setOnline(false); assertThrows(RuntimeException.class, () -> service.editable(task, 1L));
        when(runs.countActive(1L)).thenReturn(1L); assertThrows(RuntimeException.class, () -> service.editable(task, 2L));
        when(runs.countActive(1L)).thenReturn(0L); assertDoesNotThrow(() -> service.editable(task, 2L));
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    static TransactionTemplate transaction() {
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(inv -> ((TransactionCallback) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        return transaction;
    }
}

