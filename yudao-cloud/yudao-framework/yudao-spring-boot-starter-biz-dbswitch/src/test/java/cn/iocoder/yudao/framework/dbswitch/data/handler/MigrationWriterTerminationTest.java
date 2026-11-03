package cn.iocoder.yudao.framework.dbswitch.data.handler;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@Timeout(15)
class MigrationWriterTerminationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acknowledgementUsesOriginalBatchSizeEvenWhenWriterConsumesIt(boolean async) throws Exception {
        var writer = mock(IDatabaseWriter.class);
        when(writer.writeWithReceipt(anyList(), anyList())).thenCallRealMethod();
        when(writer.write(anyList(), anyList())).thenAnswer(call -> {
            List<Object[]> rows = call.getArgument(1); int count = rows.size(); rows.clear(); return (long) count;
        });
        var handler = newHandler();
        var type = Class.forName(MigrationHandler.class.getName() + "$PipelineBatchWriter");
        var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        try (var pipeline = (AutoCloseable) constructor.newInstance(handler, writer, List.of("id"), "test", async)) {
            var method = type.getDeclaredMethod("submit", List.class, long.class); method.setAccessible(true);
            method.invoke(pipeline, new java.util.ArrayList<>(List.<Object[]>of(new Object[]{1})), 4L);
            invoke(pipeline, "finish");
            var count = type.getDeclaredField("acknowledgedRows"); count.setAccessible(true);
            assertEquals(1L, ((java.util.concurrent.atomic.AtomicLong) count.get(pipeline)).get());
            verify(writer, times(1)).write(anyList(), anyList());
        }
        org.awaitility.Awaitility.await().atMost(2, SECONDS).until(() -> !handler.hasActiveWriters());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,0", "false,-1", "false,2", "true,0", "true,-1", "true,2"})
    void mismatchedAcknowledgementCannotReportSuccessfulCopy(boolean async, long acknowledged) throws Exception {
        var writer = mock(IDatabaseWriter.class);
        when(writer.writeWithReceipt(anyList(), anyList())).thenCallRealMethod();
        when(writer.write(anyList(), anyList())).thenReturn(acknowledged);
        var handler = newHandler();
        var type = Class.forName(MigrationHandler.class.getName() + "$PipelineBatchWriter");
        var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        try (var pipeline = (AutoCloseable) constructor.newInstance(handler, writer, List.of("id"), "test", async)) {
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> {
                submit(pipeline); invoke(pipeline, "finish");
            });
            assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException.class, failure.getCause());
            var repeated = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> invoke(pipeline, "finish"));
            assertInstanceOf(cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException.class, repeated.getCause());
            verify(writer, times(1)).write(anyList(), anyList());
        }
        org.awaitility.Awaitility.await().atMost(2, SECONDS).until(() -> !handler.hasActiveWriters());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void handlerCancellationUnblocksFullQueueWithoutInterruptingProducer(boolean finishing) throws Exception {
        var writeEntered = new CountDownLatch(1);
        var releaseWrite = new CountDownLatch(1);
        var writer = mock(IDatabaseWriter.class);
        when(writer.write(anyList(), anyList())).thenAnswer(call -> {
            writeEntered.countDown();
            assertTrue(releaseWrite.await(5, SECONDS));
            return 1L;
        });
        var handler = newHandler();
        try (var pipeline = pipeline(handler, writer)) {
            submit(pipeline);
            assertTrue(writeEntered.await(5, SECONDS));
            submit(pipeline);
            submit(pipeline);
            var finished = new CompletableFuture<Void>();
            var producer = new Thread(() -> {
                try {
                    if (finishing) { invoke(pipeline, "finish"); } else { submit(pipeline); }
                    finished.complete(null);
                } catch (Throwable t) { finished.completeExceptionally(t); }
            });
            producer.start();
            try {
                org.awaitility.Awaitility.await().atMost(2, SECONDS).until(() ->
                        java.util.Arrays.stream(producer.getStackTrace()).anyMatch(frame ->
                                frame.getClassName().equals("java.util.concurrent.ArrayBlockingQueue")
                                        && (frame.getMethodName().equals("put") || frame.getMethodName().equals("offer"))));
                handler.interrupt();
                releaseWrite.countDown();
                assertThrows(java.util.concurrent.ExecutionException.class, () -> finished.get(2, SECONDS));
            } finally {
                releaseWrite.countDown();
                producer.interrupt();
                producer.join(5000);
            }
        }
    }

    @Test
    void handlerCancellationAlsoReleasesIdleWriter() throws Exception {
        var handler = newHandler();
        try (var ignored = pipeline(handler, mock(IDatabaseWriter.class))) {
            org.awaitility.Awaitility.await().atMost(2, SECONDS).until(() ->
                    Thread.getAllStackTraces().entrySet().stream().anyMatch(entry ->
                            entry.getKey().getName().startsWith("dbswitch-pipeline-writer-")
                                    && java.util.Arrays.stream(entry.getValue()).anyMatch(frame ->
                                    frame.getClassName().equals("java.util.concurrent.ArrayBlockingQueue")
                                            && (frame.getMethodName().equals("take") || frame.getMethodName().equals("poll")))));
            handler.interrupt();
            org.awaitility.Awaitility.await().atMost(2, SECONDS).until(() -> !handler.hasActiveWriters());
        }
    }

    private static void submit(AutoCloseable pipeline) throws Exception {
        var method = pipeline.getClass().getDeclaredMethod("submit", List.class, long.class);
        method.setAccessible(true);
        method.invoke(pipeline, List.<Object[]>of(new Object[]{1}), 4L);
    }

    @Test
    void finishWaitsForWriterExitBookkeepingBeforeAllowingAfterSql() throws Exception {
        var epilogueEntered = new CountDownLatch(1);
        var releaseEpilogue = new CountDownLatch(1);
        var handler = newHandler();
        var counter = spy(new AtomicInteger());
        doAnswer(call -> {
            epilogueEntered.countDown();
            assertTrue(releaseEpilogue.await(5, SECONDS));
            return call.callRealMethod();
        }).when(counter).decrementAndGet();
        var field = MigrationHandler.class.getDeclaredField("activeWriters");
        field.setAccessible(true);
        field.set(handler, counter);
        try (var pipeline = pipeline(handler, mock(IDatabaseWriter.class))) {
            var finish = new CompletableFuture<Void>();
            var finisher = new Thread(() -> {
                try {
                    invoke(pipeline, "finish");
                    finish.complete(null);
                } catch (Throwable t) { finish.completeExceptionally(t); }
            });
            finisher.start();
            try {
                assertTrue(epilogueEntered.await(5, SECONDS));
                assertTrue(handler.hasActiveWriters());
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> finish.get(200, java.util.concurrent.TimeUnit.MILLISECONDS));
                releaseEpilogue.countDown();
                finish.get(5, SECONDS);
                assertFalse(handler.hasActiveWriters());
            } finally {
                releaseEpilogue.countDown();
                finisher.join(5000);
            }
        }
    }

    private static MigrationHandler newHandler() {
        var properties = new MigrationRequest();
        var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        properties.setSource(List.of(source));
        properties.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        var table = new TableDescription();
        table.setSchemaName("public");
        table.setTableName("termination_test");
        return MigrationHandler.createInstance(table, properties, 0,
                new HikariDataSource(), new HikariDataSource());
    }

    private static AutoCloseable pipeline(MigrationHandler handler, IDatabaseWriter writer) throws Exception {
        when(writer.writeWithReceipt(anyList(), anyList())).thenCallRealMethod();
        var type = Class.forName(MigrationHandler.class.getName() + "$PipelineBatchWriter");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (AutoCloseable) constructor.newInstance(handler, writer, List.of("id"), "test", true);
    }

    private static void invoke(AutoCloseable pipeline, String name) throws Exception {
        var method = pipeline.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(pipeline);
    }

    @Test
    void writerExitObserverRunsOnlyAfterACancelledWriterActuallyExits() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writer = mock(IDatabaseWriter.class);
        when(writer.write(anyList(), anyList())).thenAnswer(ignored -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignoredInterrupt) { }
            }
            return 1L;
        });
        var handler = newHandler();
        var observed = new AtomicInteger();
        var activeWhenObserved = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        // Reflection lets the original RED execute as a missing-contract assertion, not a compilation error.
        var hook = assertDoesNotThrow(() -> MigrationHandler.class.getMethod("observeWriterExit", Runnable.class));
        hook.invoke(handler, (Runnable) () -> { activeWhenObserved.set(handler.hasActiveWriters()); observed.incrementAndGet(); });
        var pipeline = pipeline(handler, writer);
        try {
            submit(pipeline);
            assertTrue(entered.await(5, SECONDS));
            pipeline.close();
            assertTrue(handler.hasActiveWriters());
            assertEquals(0, observed.get(), "a cancelled Future is not an exit event");
            release.countDown();
            org.awaitility.Awaitility.await().atMost(5, SECONDS).until(() -> observed.get() == 1);
            assertEquals(false, activeWhenObserved.get());
        } finally {
            release.countDown();
            pipeline.close();
        }
    }

    @Test
    void cancellingWriterFutureDoesNotEraseLiveWriterEvidence() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var writer = mock(IDatabaseWriter.class);
        when(writer.write(anyList(), anyList())).thenAnswer(ignored -> {
            entered.countDown();
            try {
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignoredInterrupt) { }
                }
                return 1L;
            } finally { exited.countDown(); }
        });
        var properties = new MigrationRequest();
        var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        properties.setSource(List.of(source));
        properties.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        var table = new TableDescription();
        table.setSchemaName("public");
        table.setTableName("termination_test");
        var handler = MigrationHandler.createInstance(table, properties, 0,
                new HikariDataSource(), new HikariDataSource());
        // Exercise the actual pipeline without requiring a database for this thread-lifetime race.
        when(writer.writeWithReceipt(anyList(), anyList())).thenCallRealMethod();
        var pipelineType = Class.forName(MigrationHandler.class.getName() + "$PipelineBatchWriter");
        var constructor = pipelineType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var pipeline = (AutoCloseable) constructor.newInstance(handler, writer, List.of("id"), "test", true);
        Method submit = pipelineType.getDeclaredMethod("submit", List.class, long.class);
        submit.setAccessible(true);
        try {
            submit.invoke(pipeline, List.<Object[]>of(new Object[]{1}), 4L);
            assertTrue(entered.await(5, SECONDS));
            pipeline.close();
            assertEquals(1, exited.getCount());
            var active = assertDoesNotThrow(() -> MigrationHandler.class.getMethod("hasActiveWriters"));
            assertEquals(true, active.invoke(handler));
            release.countDown();
            assertTrue(exited.await(5, SECONDS));
            org.awaitility.Awaitility.await().atMost(5, SECONDS)
                    .until(() -> Boolean.FALSE.equals(active.invoke(handler)));
        } finally {
            release.countDown();
            pipeline.close();
            assertTrue(exited.await(5, SECONDS));
        }
    }
}
