package cn.iocoder.yudao.framework.etl.flink.smoke;

import cn.iocoder.yudao.framework.etl.flink.EtlWindowPickFunction;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.api.common.state.CheckpointListener;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.RichParallelSourceFunction;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 真实WindowAggregate checkpoint后故障重启，旧窗口保留首行，后续同键新窗独立输出。无外部连接。 */
public final class WindowStateProbe {
    private static final long WINDOW = 8000;
    public static void main(String[] args) throws Exception {
        directGuards();
        var env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1); env.enableCheckpointing(100);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(1, 100));
        env.getCheckpointConfig().setCheckpointStorage("file:///tmp/window-state/checkpoints");
        var table = StreamTableEnvironment.create(env);
        table.createTemporarySystemFunction("DH_WINDOW_PICK", new EtlWindowPickFunction());
        var source = env.addSource(new Input()).returns(Types.ROW_NAMED(new String[]{"id", "seq", "value"}, Types.STRING, Types.LONG, Types.STRING));
        table.createTemporaryView("source_rows", source);
        String sql = "WITH clocked AS (SELECT *,PROCTIME() AS pt FROM source_rows), windowed AS (SELECT * FROM TABLE(TUMBLE(TABLE clocked,DESCRIPTOR(pt),INTERVAL '8' SECOND))) "
                + "SELECT DH_WINDOW_PICK(ARRAY[id,CAST(seq AS STRING),`value`], '1|F|10000|16777216|1:A:N|1') picked FROM windowed GROUP BY window_start,window_end,id";
        List<Row> rows = new ArrayList<>();
        try (var iterator = table.executeSql(sql).collect()) { while (iterator.hasNext()) rows.add(iterator.next()); }
        if (rows.size() != 2) throw new AssertionError("窗口关闭应输出两个独立结果，实际" + rows);
        if (rows.stream().anyMatch(row -> row.getKind() != RowKind.INSERT)) throw new AssertionError("窗口输出不是append-only");
        List<String> values = rows.stream().map(row -> ((String[]) row.getField(0))[2]).toList();
        if (!values.containsAll(List.of("first", "next-window"))) throw new AssertionError("checkpoint未保留旧窗口首行：" + rows);
        if (!Files.exists(Path.of("/tmp/window-state/failed-once")) || !Files.exists(Path.of("/tmp/window-state/restored"))) throw new AssertionError("未发生真实checkpoint故障恢复");
        System.out.println("WINDOW_STATE_CHECKPOINT_RESTORE_PASS results=" + values);
    }
    private static void directGuards() {
        var function = new EtlWindowPickFunction(); var accumulator = function.createAccumulator();
        function.accumulate(accumulator, new String[]{"9007199254740993", "a"}, "1|F|10|1024|0:A:N|0");
        function.accumulate(accumulator, new String[]{"9007199254740992", "b"}, "1|F|10|1024|0:A:N|0");
        if (!function.getValue(accumulator)[1].equals("b")) throw new AssertionError("Long排序损失精度");
        try { function.accumulate(accumulator, new String[]{"9007199254740992", "conflict"}, "1|F|10|1024|0:A:N|0"); throw new AssertionError("未拒绝唯一键冲突"); }
        catch (IllegalArgumentException expected) { if (!expected.getMessage().contains("唯一")) throw expected; }
        accumulator = function.createAccumulator();
        function.accumulate(accumulator, new String[]{"1"}, "1|F|1|1024|0:A:N|0");
        try { function.accumulate(accumulator, new String[]{"2"}, "1|F|1|1024|0:A:N|0"); throw new AssertionError("未拒绝行预算超限"); }
        catch (IllegalArgumentException expected) { if (!expected.getMessage().contains("预算")) throw expected; }
    }
    public static final class Input extends RichParallelSourceFunction<Row> implements CheckpointedFunction, CheckpointListener {
        private volatile boolean running = true;
        private volatile int completedIndex;
        private final java.util.concurrent.ConcurrentMap<Long, Integer> positions = new java.util.concurrent.ConcurrentHashMap<>();
        private transient ListState<Integer> state; private int index;
        @Override public void run(SourceContext<Row> context) throws Exception {
            if (index == 0) while (System.currentTimeMillis() % WINDOW > 150 && running) Thread.sleep(20);
            long firstEnd = (System.currentTimeMillis() / WINDOW + 1) * WINDOW;
            while (index < 6 && running) {
                synchronized (context.getCheckpointLock()) { context.collect(Row.of("same", 9_007_199_254_740_993L + index, index == 0 ? "first" : "other-" + index)); index++; }
                if (index == 3 && !Files.exists(Path.of("/tmp/window-state/failed-once"))) {
                    while (completedIndex < 3 && running) Thread.sleep(20);
                    Files.createFile(Path.of("/tmp/window-state/failed-once"));
                    throw new IllegalStateException("intentional failure after completed checkpoint");
                }
                Thread.sleep(30);
            }
            while (System.currentTimeMillis() < firstEnd + 250 && running) Thread.sleep(30);
            if (running) synchronized (context.getCheckpointLock()) { context.collect(Row.of("same", 9_007_199_254_741_100L, "next-window")); }
            long secondEnd = (System.currentTimeMillis() / WINDOW + 1) * WINDOW;
            while (System.currentTimeMillis() < secondEnd + 300 && running) Thread.sleep(30);
        }
        @Override public void cancel() { running = false; }
        @Override public void snapshotState(FunctionSnapshotContext context) throws Exception { state.clear(); state.add(index); positions.put(context.getCheckpointId(), index); }
        @Override public void initializeState(FunctionInitializationContext context) throws Exception {
            state = context.getOperatorStateStore().getListState(new ListStateDescriptor<>("source-index", Types.INT));
            if (context.isRestored()) {
                for (int value : state.get()) index = value;
                Files.writeString(Path.of("/tmp/window-state/restored"), "sourceIndex=" + index);
                System.out.println("RESTORED_SOURCE_INDEX=" + index);
            }
        }
        @Override public void notifyCheckpointComplete(long checkpointId) { completedIndex = Math.max(completedIndex, positions.getOrDefault(checkpointId, 0)); positions.keySet().removeIf(id -> id <= checkpointId); }
    }
}
