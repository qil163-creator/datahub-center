package cn.iocoder.yudao.framework.etl.core.runtime;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.builtin.*;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.variable.EtlVariableContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlBoundedFileOutputTest {
    @TempDir Path root;

    @Test void failedDelegateCloseRemainsFailedAfterResourceWasClosed() throws Exception {
        var failed = new java.util.concurrent.atomic.AtomicBoolean(); var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var delegate = new java.io.ByteArrayOutputStream() { @Override public void close() throws IOException { throw new IOException("close failed"); }};
        var stream = EtlFileOutputs.bounded(delegate, 4, () -> failed.set(true), () -> closed.set(true)); stream.write(1);
        assertThrows(IOException.class, stream::close); assertTrue(closed.get()); assertTrue(failed.get());
        assertThrows(IOException.class, () -> stream.write(2));
    }

    @Test void failedDelegateFlushLatchesFailureAndStillClosesResource() throws Exception {
        var failed = new java.util.concurrent.atomic.AtomicBoolean(); var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var delegate = new java.io.ByteArrayOutputStream() { @Override public void flush() throws IOException { throw new IOException("flush failed"); }};
        var stream = EtlFileOutputs.bounded(delegate, 4, () -> failed.set(true), () -> closed.set(true)); stream.write(1);
        assertThrows(IOException.class, stream::flush); assertTrue(failed.get()); assertThrows(IOException.class, () -> stream.write(2));
        stream.close(); assertTrue(closed.get()); assertTrue(failed.get());
    }

    @Test void encodedByteLimitRejectsBeforeWritingBeyondLimitAndLatchesFailure() throws Exception {
        var outputs = outputs(5); var part = outputs.stage("n", root.toString(), "out.txt");
        try (var stream = outputs.open("n")) {
            stream.write("中".getBytes(StandardCharsets.UTF_8)); assertEquals(3, Files.size(part));
            assertThrows(IOException.class, () -> stream.write("文".getBytes(StandardCharsets.UTF_8)));
            assertEquals(3, Files.size(part)); assertThrows(IOException.class, () -> stream.write(1));
        }
        assertThrows(IllegalStateException.class, () -> outputs.ready("n")); assertThrows(IllegalStateException.class, outputs::publish);
        outputs.discard(true); assertFalse(Files.exists(part));
    }

    @Test void charsetBytesRatherThanJavaCharacterCountDefineLimit() throws Exception {
        var outputs = outputs(6); var part = outputs.stage("n", root.toString(), "out.txt");
        try (var stream = outputs.open("n"); var writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) { writer.write("中文"); }
        assertEquals(6, Files.size(part)); outputs.ready("n"); outputs.publish(); outputs.discard(true);
        assertEquals("中文", Files.readString(root.resolve("out.txt")));
    }

    @Test void noReopenCanResetBudgetAndOpenWriterCannotBeSealed() throws Exception {
        var outputs = outputs(5); outputs.stage("n", root.toString(), "out.txt");
        try (var stream = outputs.open("n")) {
            stream.write(new byte[]{1, 2, 3, 4, 5});
            assertThrows(IllegalStateException.class, () -> outputs.open("n"));
            assertThrows(IllegalStateException.class, () -> outputs.ready("n"));
        }
        assertThrows(IllegalStateException.class, () -> outputs.open("n")); outputs.ready("n"); outputs.discard(true);
    }

    @ParameterizedTest @ValueSource(strings = {"csv", "json", "text", "excel"})
    void everyRealWriterUsesByteBoundedStreamAndCannotBecomeReadyOnOverflow(String format) throws Exception {
        var outputs = outputs(8); var context = context(format, outputs);
        try {
            writer(format, context).run(); assertFalse(context.getNodeFuture("out").join());
            assertThrows(IllegalStateException.class, () -> outputs.ready("out")); assertThrows(IllegalStateException.class, outputs::publish);
            try (var parts = Files.walk(root)) { for (Path part : parts.filter(p -> p.toString().endsWith(".part")).toList()) assertTrue(Files.size(part) <= 8); }
        } finally { outputs.discard(true); context.getDatabaseRuntime().close(); }
        try (var paths = Files.list(root)) { assertTrue(paths.findAny().isEmpty()); }
    }

    @ParameterizedTest @ValueSource(strings = {"csv", "json", "text", "excel"})
    void everyRealWriterStillPublishesValidOutputWithinBudget(String format) throws Exception {
        var outputs = outputs(1024 * 1024); var context = context(format, outputs);
        try {
            writer(format, context).run(); assertTrue(context.getNodeFuture("out").join(), context.failureMessage());
            outputs.publish(); assertEquals("PUBLISHED", outputs.states().get("out"));
        } finally { outputs.discard(true); context.getDatabaseRuntime().close(); }
        try (var paths = Files.list(root)) { var files = paths.toList(); assertEquals(1, files.size()); assertTrue(Files.size(files.getFirst()) > 0); }
    }

    private EtlFileOutputs outputs(long bytes) { return new EtlFileOutputs(new EtlFileOutputPolicy(List.of(root), false), null, bytes); }
    private EtlRunContext context(String format, EtlFileOutputs outputs) throws Exception {
        var source = new EtlNodeDef(); source.setNo("source"); source.setName("source"); source.setToolCode("generate-records"); source.setToolType(1);
        var out = new EtlNodeDef(); out.setNo("out"); out.setName("out"); out.setToolCode("write-" + format); out.setToolType(3);
        out.setNodeJson(JSONUtil.toJsonStr(Map.of("filePath", root.toString(), "fileName", "out", "template", "中文".repeat(20), "charset", "UTF-8")));
        var edge = new EtlEdgeDef(); edge.setSourceNo("source"); edge.setTargetNo("out"); edge.setEdgeType("DATA");
        var context = new EtlRunContext(EtlDag.build(List.of(source, out), List.of(edge)), null, new EtlRunListener() { }, new EtlVariableContext(Map.of()));
        context.setFileOutputs(outputs); assertTrue(context.getChannel("source", "out").push(DataPacket.data(Map.of("value", "中文".repeat(20))), 100));
        assertTrue(context.getChannel("source", "out").push(DataPacket.end(), 100)); context.getNodeFuture("source").complete(true); return context;
    }
    private AbstractEtlNodeExecutor writer(String format, EtlRunContext context) {
        var node = context.getDag().getNode("out");
        return switch (format) {
            case "csv" -> new WriteCsvExecutor(node, context);
            case "json" -> new WriteJsonExecutor(node, context);
            case "text" -> new WriteTextExecutor(node, context);
            case "excel" -> new WriteExcelExecutor(node, context);
            default -> throw new IllegalArgumentException(format);
        };
    }
}
