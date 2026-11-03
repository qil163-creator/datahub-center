package cn.iocoder.yudao.module.dataforge.service.batchfile;

import cn.iocoder.yudao.module.dataforge.controller.admin.batchfile.vo.BatchFileModels.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.batchfile.BatchFileRunDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.batchfile.BatchFileRunMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.batchfile.BatchFileTaskMapper;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static cn.iocoder.yudao.module.dataforge.service.batchfile.BatchFileRules.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BatchFileRunServiceTest {
    @Test void queuedExecutionRechecksRevokedIdentityBeforeReadingFile() {
        try (Fixture f = new Fixture(1)) {
            doThrow(new IllegalStateException("revoked")).when(f.access)
                    .requireUserPermission(5L, "dataforge:batch-file:execute");
            f.work();
            verifyNoInteractions(f.target); verify(f.files, never()).content(any());
            verify(f.runs).finish(eq(10L), anyString(), eq("FAILED"), anyString(), eq(0), eq(1), eq(0L), isNull());
        }
    }
    @Test void failedFileContinuesInConfiguredOrderAndProducesPartialResult() {
        try (Fixture f = new Fixture(2)) {
            f.task.getTargets().getFirst().setFileIds(List.of(2L, 1L)); f.snapshot();
            when(f.target.append(any(), argThat(file -> file != null && file.getId().equals(2L)), any(), eq(5L), any()))
                    .thenReturn(new BatchFileTargetAdapter.Outcome("FAILED", 0, "类型校验失败"));
            when(f.target.append(any(), argThat(file -> file != null && file.getId().equals(1L)), any(), eq(5L), any()))
                    .thenReturn(new BatchFileTargetAdapter.Outcome("SUCCESS", 3, null));
            f.work();
            var order = inOrder(f.target);
            order.verify(f.target).append(any(), argThat(file -> file != null && file.getId().equals(2L)), any(), eq(5L), any());
            order.verify(f.target).append(any(), argThat(file -> file != null && file.getId().equals(1L)), any(), eq(5L), any());
            verify(f.runs).finish(eq(10L), anyString(), eq("PARTIAL_SUCCESS"), anyString(), eq(1), eq(1), eq(3L), isNull());
        }
    }
    @Test void systemFileRechecksSourcePermissionBeforeReadingManagedCopy() {
        try (Fixture f = new Fixture(1)) {
            f.task.getFiles().getFirst().setSource("SYSTEM"); f.snapshot();
            doThrow(new IllegalStateException("revoked")).when(f.access)
                    .requireUserPermission(5L, "dataforge:data-file:download");
            f.work();
            verify(f.access).requireUserPermission(5L, "dataforge:data-file:query");
            verifyNoInteractions(f.target); verify(f.files, never()).content(any());
            verify(f.runs).finish(eq(10L), anyString(), eq("FAILED"), anyString(), eq(0), eq(1), eq(0L), isNull());
        }
    }
    @Test void uncertainWriteStopsItsTargetButContinuesOtherTargets() {
        try (Fixture f = new Fixture(3)) {
            f.task.getTargets().getFirst().setFileIds(List.of(1L, 2L));
            Target other = new Target(); other.setName("other_target"); other.setFileIds(List.of(3L));
            f.task.setTargets(List.of(f.task.getTargets().getFirst(), other)); f.snapshot();
            when(f.target.append(any(), argThat(file -> file != null && file.getId().equals(1L)), any(), eq(5L), any()))
                    .thenReturn(new BatchFileTargetAdapter.Outcome("UNKNOWN", 500, "提交回执未知"));
            when(f.target.append(any(), argThat(file -> file != null && file.getId().equals(3L)), any(), eq(5L), any()))
                    .thenReturn(new BatchFileTargetAdapter.Outcome("SUCCESS", 1, null));
            f.work();
            verify(f.target, never()).append(any(), argThat(file -> file != null && file.getId().equals(2L)), any(), anyLong(), any());
            ArgumentCaptor<String> items = ArgumentCaptor.forClass(String.class);
            verify(f.runs).finish(eq(10L), anyString(), eq("UNKNOWN"), items.capture(), eq(1), eq(2), eq(501L), anyString());
            assertEquals(List.of("UNKNOWN", "FAILED", "SUCCESS"), Arrays.stream(read(items.getValue(), RunItem[].class)).map(RunItem::getStatus).toList());
            when(f.runs.claim(eq(10L), anyString())).thenReturn(0);
            f.work(); verify(f.target, times(2)).append(any(), any(), any(), anyLong(), any());
        }
    }
    @Test void malformedSnapshotStillCancelsHeartbeat() {
        try (Fixture f = new Fixture(1)) {
            ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
            ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
            doReturn(heartbeat).when(scheduler).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
            ReflectionTestUtils.setField(f.service, "heartbeats", scheduler);
            f.run.setConfigJson("invalid-json");
            assertDoesNotThrow(f::work); verify(heartbeat).cancel(false);
        }
    }
    static class Fixture implements AutoCloseable {
        final BatchFileRunService service = new BatchFileRunService();
        final BatchFileRunMapper runs = mock(BatchFileRunMapper.class);
        final ExecutionAccessService access = mock(ExecutionAccessService.class);
        final BatchFileService files = mock(BatchFileService.class);
        final BatchFileTargetAdapter target = mock(BatchFileTargetAdapter.class);
        final BatchFileRunDO run = new BatchFileRunDO();
        final Task task = new Task();
        Fixture(int count) {
            ReflectionTestUtils.setField(service, "runs", runs); ReflectionTestUtils.setField(service, "access", access);
            ReflectionTestUtils.setField(service, "tasks", mock(BatchFileTaskMapper.class));
            ReflectionTestUtils.setField(service, "service", files); ReflectionTestUtils.setField(service, "target", target);
            ReflectionTestUtils.setField(service, "transaction", BatchFileServiceTest.transaction());
            run.setId(10L); run.setTaskId(1L); run.setExecutionUserId(5L);
            List<File> values = new ArrayList<>(); List<Long> ids = new ArrayList<>();
            for (long i = 1; i <= count; i++) { File file = new File(); file.setId(i); file.setName(i + ".csv"); values.add(file); ids.add(i); }
            task.setFiles(values); Target group = new Target(); group.setName("same_target"); group.setFileIds(ids); task.setTargets(List.of(group));
            snapshot(); when(runs.selectById(10L)).thenReturn(run);
            when(runs.claim(eq(10L), anyString())).thenReturn(1); when(runs.heartbeat(eq(10L), anyString())).thenReturn(1);
            when(runs.progress(eq(10L), anyString(), anyString(), anyInt(), anyInt(), anyLong())).thenReturn(1);
            when(files.content(any())).thenReturn(new byte[]{1});
        }
        void snapshot() { run.setConfigJson(json(task)); run.setItemsJson(json(BatchFileRunService.initialItems(task))); }
        void work() { ReflectionTestUtils.invokeMethod(service, "work", 10L); }
        @Override public void close() { service.close(); }
    }
}
