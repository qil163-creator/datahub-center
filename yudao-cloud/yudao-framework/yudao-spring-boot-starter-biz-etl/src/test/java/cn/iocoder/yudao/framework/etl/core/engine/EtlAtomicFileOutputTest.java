package cn.iocoder.yudao.framework.etl.core.engine;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputPolicy;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlFileOutputs;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EtlAtomicFileOutputTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"csv", "json", "text", "excel"})
    void laterPipelineFailureKeepsExistingFinalFileAndCleansTemporaryFiles(String format) throws Exception {
        String name = "result." + ("excel".equals(format) ? "xlsx" : "text".equals(format) ? "txt" : format);
        Path target = directory.resolve(name);
        Files.writeString(target, "previous result");
        var result = run(format, name, true, true, null);
        assertFalse(result.isSuccess());
        assertEquals("previous result", Files.readString(target));
        assertEquals("DISCARDED", result.getFileOutputStates().get("output"));
        assertNoTemporaryFiles();
    }

    @Test
    void finalFileAppearsOnlyAfterEveryNodeSucceeds() throws Exception {
        Path target = directory.resolve("result.json");
        var result = run("json", "result.json", false, false, () -> assertFalse(Files.exists(target)));
        assertTrue(result.isSuccess(), result.getFirstError());
        assertEquals("PUBLISHED", result.getFileOutputStates().get("output"));
        assertTrue(Files.readString(target).contains("123"));
        assertNoTemporaryFiles();
    }

    @Test
    void explicitOverwriteUsesAtomicReplacementAfterSuccess() throws Exception {
        Path target = directory.resolve("result.json");
        Files.writeString(target, "old");
        var result = run("json", "result.json", true, false,
                () -> assertEquals("old", Files.readString(target)));
        assertTrue(result.isSuccess(), result.getFirstError());
        assertTrue(Files.readString(target).contains("123"));
        assertNoTemporaryFiles();
    }

    @Test
    void directoriesAndFileNamesCannotGrantThemselvesAccess() throws Exception {
        EtlFileOutputPolicy policy = new EtlFileOutputPolicy(List.of(directory), false);
        assertThrows(IllegalArgumentException.class, () -> policy.resolve(directory.toString(), "../escape.txt"));
        assertThrows(IllegalArgumentException.class, () -> policy.resolve(directory.toString(), "nested\\escape.txt"));
        assertThrows(IllegalArgumentException.class, () -> policy.resolve(directory.getParent().toString(), "escape.txt"));
        assertThrows(IllegalStateException.class, () -> EtlFileOutputPolicy.disabled().resolve(directory.toString(), "x"));
    }

    @Test
    void defaultPolicyRejectsFileNodesWithoutWritingAnything() throws Exception {
        var registry = registry();
        try (var engine = new EtlPipelineEngine(registry)) {
            var result = engine.execute(graph("json", "result.json", false), EtlRunOptions.builder().build());
            assertFalse(result.isSuccess());
            assertTrue(result.getFirstError().contains("允许目录"));
        }
        assertFalse(Files.exists(directory.resolve("result.json")));
        assertNoTemporaryFiles();
    }

    @Test
    void concurrentProcessesCannotOverwriteAnExistingTargetInCreateOnlyMode() throws Exception {
        EtlFileOutputs outputs = new EtlFileOutputs(new EtlFileOutputPolicy(List.of(directory), false));
        Path temporary = outputs.stage("output", directory.toString(), "result.json");
        Files.writeString(temporary, "new"); outputs.ready("output");
        Files.writeString(directory.resolve("result.json"), "other writer");
        assertThrows(IllegalStateException.class, outputs::publish);
        outputs.discard(true);
        assertEquals("other writer", Files.readString(directory.resolve("result.json")));
        assertNoTemporaryFiles();
    }

    @Test
    void multiFilePublicationReportsAlreadyPublishedFilesWhenLaterPublicationFails() throws Exception {
        EtlFileOutputs outputs = new EtlFileOutputs(new EtlFileOutputPolicy(List.of(directory), true));
        Path a = outputs.stage("a", directory.toString(), "a.txt");
        Path b = outputs.stage("b", directory.toString(), "b.txt");
        Files.writeString(a, "a"); outputs.ready("a"); outputs.ready("b");
        Files.delete(b);
        assertThrows(java.io.IOException.class, outputs::publish);
        outputs.discard(true);
        assertEquals(Map.of("a", "PUBLISHED", "b", "DISCARDED"), outputs.states());
        assertEquals("a", Files.readString(directory.resolve("a.txt")));
        assertFalse(Files.exists(directory.resolve("b.txt")));
        assertNoTemporaryFiles();
    }

    @Test
    void twoNodesCannotStageTheSameTargetAndReleasedTargetsCanBeReused() throws Exception {
        var policy = new EtlFileOutputPolicy(List.of(directory), true);
        var first = new EtlFileOutputs(policy);
        var second = new EtlFileOutputs(policy);
        first.stage("a", directory.toString(), "same.txt");
        assertThrows(IllegalStateException.class, () -> second.stage("b", directory.toString(), "same.txt"));
        first.discard(true);
        second.stage("b", directory.toString(), "same.txt");
        second.discard(true);
        assertNoTemporaryFiles();
    }

    private EtlRunResult run(String format, String name, boolean overwrite, boolean fail, CheckedCheck check) {
        var registry = registry();
        registry.register("after-output", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            protected void execute() throws Exception {
                if (check != null) check.run();
                if (fail) throw new IllegalStateException("later node failed");
            }
        });
        try (var engine = new EtlPipelineEngine(registry)) {
            return engine.execute(graph(format, name, true), EtlRunOptions.builder()
                    .fileOutputPolicy(new EtlFileOutputPolicy(List.of(directory), overwrite)).build());
        }
    }

    private EtlExecutorRegistry registry() {
        var registry = new EtlExecutorRegistry();
        registry.register("source", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            protected void execute() throws Exception { pushRow(Map.of("id", 123)); }
        });
        return registry;
    }

    private EtlDag graph(String format, String name, boolean after) {
        EtlNodeDef source = node("source", "source", 1, Map.of());
        EtlNodeDef target = node("output", "write-" + format, 3,
                Map.of("filePath", directory.toString(), "fileName", name, "template", "record"));
        EtlEdgeDef data = edge("source", "output", "DATA");
        return after ? EtlDag.build(List.of(source, target, node("after", "after-output", 4, Map.of())),
                List.of(data, edge("output", "after", "CONTROL"))) : EtlDag.build(List.of(source, target), List.of(data));
    }

    private EtlNodeDef node(String id, String code, int type, Map<String, Object> config) {
        EtlNodeDef node = new EtlNodeDef();
        node.setNo(id); node.setName(id); node.setToolCode(code); node.setToolType(type); node.setNodeJson(JSONUtil.toJsonStr(config));
        return node;
    }

    private EtlEdgeDef edge(String source, String target, String type) {
        EtlEdgeDef edge = new EtlEdgeDef(); edge.setSourceNo(source); edge.setTargetNo(target); edge.setEdgeType(type); return edge;
    }

    private void assertNoTemporaryFiles() throws Exception {
        try (var files = Files.list(directory)) { assertTrue(files.noneMatch(file -> file.getFileName().toString().endsWith(".part"))); }
    }

    @FunctionalInterface private interface CheckedCheck { void run() throws Exception; }
}
