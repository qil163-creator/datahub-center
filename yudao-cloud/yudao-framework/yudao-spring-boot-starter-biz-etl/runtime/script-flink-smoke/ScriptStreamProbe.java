package cn.iocoder.yudao.framework.etl.script.fixture;

import cn.iocoder.yudao.framework.etl.core.script.*;
import cn.iocoder.yudao.framework.etl.script.flink.EtlNativeScriptMain;
import org.apache.flink.api.common.state.*;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.execution.SavepointFormatType;
import org.apache.flink.runtime.state.*;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.RichParallelSourceFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.table.runtime.typeutils.ExternalTypeInfo;
import org.apache.flink.types.*;
import java.math.BigDecimal;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 官方本地 Flink 的可信测试源只控制到达时刻；被测算子直接复用正式 launcher。 */
public final class ScriptStreamProbe {
    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]); String name = arguments[1], phase = arguments[2];
        var program = EtlNativeScriptProgram.fromArguments(Files.readAllLines(root.resolve(name + ".args")).toArray(String[]::new));
        var stage = program.stages().get(0);
        if (phase.equals("restore-scope")) stage = new EtlNativeScriptProgram.Stage(stage.id(), stage.inputSql(), stage.view(), stage.inputFields(), stage.outputFields(), stage.binding(), stage.partitionBatch(), stage.maximumRows() + 1, stage.flushMillis());
        Path output = root.resolve(name + ".rows");
        var config = new Configuration();
        config.setString("taskmanager.memory.managed.size", "32m"); config.setString("taskmanager.memory.network.min", "16m"); config.setString("taskmanager.memory.network.max", "16m");
        if (name.equals("cache")) { config.setString("restart-strategy.type", "fixed-delay"); config.setString("restart-strategy.fixed-delay.attempts", "1"); config.setString("restart-strategy.fixed-delay.delay", "100ms"); }
        int parallelism = phase.equals("restore-rescaled") ? 2 : 1;
        config.setString("taskmanager.numberOfTaskSlots", Integer.toString(parallelism)); config.setString("rest.bind-port", "0"); if (!name.equals("cache")) config.setString("restart-strategy.type", "none");
        if (phase.startsWith("restore")) config.setString("execution.savepoint.path", Files.readString(root.resolve(name + ".savepoint")).trim());
        var environment = StreamExecutionEnvironment.createLocalEnvironment(parallelism, config); environment.enableCheckpointing(500);
        environment.getCheckpointConfig().setCheckpointTimeout(30000);
        int initial = name.equals("cache") ? 0 : name.equals("resume") || name.equals("timer") || name.equals("cancel") ? 1 : 2;
        var input = environment.addSource(new Source(root.toString(), name, initial), ExternalTypeInfo.of(EtlNativeScriptMain.rowType(stage.inputFields())))
                .uid("controlled-source").setParallelism(parallelism);
        var result = EtlNativeScriptMain.stage(input, stage, UUID.randomUUID().toString(), true);
        result.addSink(new Sink(output.toString(), name.equals("cache"))).uid("controlled-result").setParallelism(1);
        try (var serialized = new ObjectOutputStream(Files.newOutputStream(root.resolve(name + "-" + phase + ".jobgraph")))) { serialized.writeObject(environment.getStreamGraph(false).getJobGraph()); }
        var client = environment.executeAsync("datahub-script-" + name + "-" + phase);
        Files.writeString(root.resolve(name + ".jobid"), client.getJobID().toString());
        if (phase.equals("restore-rescaled") || phase.equals("restore-scope")) {
            client.getJobExecutionResult().get(30, TimeUnit.SECONDS); throw new IllegalStateException("缩放恢复必须拒绝");
        } else if (name.equals("cache")) {
            await(root.resolve(name + ".started"), 1, 30000);
            client.triggerSavepoint(root.resolve("savepoints").toString(), SavepointFormatType.CANONICAL).get(30, TimeUnit.SECONDS);
            Files.writeString(root.resolve(name + ".release"), "release"); await(output, 3, 45000); Thread.sleep(1000); client.cancel().get(20, TimeUnit.SECONDS);
        } else if (name.equals("cancel")) {
            await(root.resolve(name + ".emitted"), 1, 30000); Thread.sleep(2500); client.cancel().get(20, TimeUnit.SECONDS);
        } else if (phase.startsWith("save")) {
            await(root.resolve(name + ".emitted"), initial, 30000);
            if (initial == 2) await(output, 2, 30000);
            String point = client.triggerSavepoint(root.resolve("savepoints").toString(), SavepointFormatType.CANONICAL).get(30, TimeUnit.SECONDS);
            Files.writeString(root.resolve(name + ".savepoint"), point);
            if (initial == 1 && lines(output) != 0) throw new IllegalStateException("检查点不得强制刷新未满窗口");
            client.cancel().get(20, TimeUnit.SECONDS);
        } else {
            if (phase.equals("restore-timer")) { await(output, 1, 30000); Files.writeString(root.resolve(name + ".release"), "release"); await(output, 2, 30000); }
            else if (phase.equals("restore-session")) { Files.writeString(root.resolve(name + ".release"), "release"); await(output, 4, 30000); }
            else await(output, name.equals("timer") ? 1 : 2, 30000);
            Thread.sleep(1200); client.cancel().get(20, TimeUnit.SECONDS);
        }
        System.out.println("DATAHUB_SCRIPT_PROBE_PASS " + name + " " + phase + " rows=" + lines(output));
    }
    private static long lines(Path path) throws Exception { return Files.exists(path) ? Files.readAllLines(path).size() : 0; }
    private static void await(Path path, long count, long timeout) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (System.nanoTime() < deadline) { if (lines(path) >= count) return; Thread.sleep(50); }
        throw new IllegalStateException("限时等待实际结果失败：" + path.getFileName());
    }
    public static final class Source extends RichParallelSourceFunction<Row> implements CheckpointedFunction {
        private final String root, name; private final int initial;
        private volatile boolean running = true; private transient ListState<Integer> state; private int next;
        Source(String root, String name, int initial) { this.root = root; this.name = name; this.initial = initial; }
        @Override public void run(SourceContext<Row> context) throws Exception {
            Files.writeString(Path.of(root, name + ".started"), "started\n");
            while (running) {
                if (next < initial || Files.exists(Path.of(root, name + ".release")) && next < (name.equals("resume") || name.equals("cache") ? 2 : 4)) {
                    synchronized (context.getCheckpointLock()) {
                        Row row = Row.ofKind(RowKind.INSERT, 9007199254740993L + next, "河道 O'Reilly😀-" + next,
                                new BigDecimal("12345678901234567890123456.7800"), LocalDate.of(2026, 11, 2), LocalDateTime.of(2026, 11, 2, 12, 34, 56, 123456789));
                        Files.writeString(Path.of(root, name + ".emitted"), "emitted\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); context.collect(row); next++;
                    }
                } else Thread.sleep(20);
            }
        }
        @Override public void cancel() { running = false; }
        @Override public void snapshotState(FunctionSnapshotContext context) throws Exception { state.update(List.of(next)); }
        @Override public void initializeState(FunctionInitializationContext context) throws Exception {
            state = context.getOperatorStateStore().getListState(new ListStateDescriptor<>("controlled-position", Integer.class));
            if (context.isRestored()) { var values = state.get().iterator(); next = values.hasNext() ? values.next() : 0; if (values.hasNext()) throw new IllegalStateException("测试不允许重分片"); }
        }
    }
    public static final class Sink extends RichSinkFunction<Row> {
        private final String path; private final boolean failFirst; Sink(String path, boolean failFirst) { this.path = path; this.failFirst = failFirst; }
        @Override public void invoke(Row row, Context context) throws Exception {
            var parts = new ArrayList<String>();
            for (int i = 0; i < row.getArity(); i++) {
                Object value = row.getField(i);
                parts.add(value == null ? "\\N" : i == 1 ? Base64.getEncoder().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8)) : value.toString());
            }
            Files.writeString(Path.of(path), String.join("|", parts) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (failFirst && !Files.exists(Path.of(path + ".failed"))) { Files.writeString(Path.of(path + ".failed"), "failed"); throw new IOException("受控下游失败，用于证明同一输入批次仅执行一次"); }
        }
    }
}
