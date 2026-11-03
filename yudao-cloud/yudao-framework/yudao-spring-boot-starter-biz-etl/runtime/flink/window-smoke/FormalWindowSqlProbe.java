package cn.iocoder.yudao.framework.etl.flink.smoke;

import cn.iocoder.yudao.framework.etl.flink.EtlWindowPickFunction;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.RichParallelSourceFunction;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.types.Row;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

/** 原样执行正式图编译器INSERT，验证窗口SQL与真实字段类型。只写隔离容器临时filesystem目标。 */
public final class FormalWindowSqlProbe {
    public static void main(String[] args) throws Exception {
        boolean pivot = args[0].equals("pivot");
        var configuration = new Configuration(); configuration.setString("restart-strategy.type", "none");
        configuration.setString("rest.bind-port", "0");
        var environment = StreamExecutionEnvironment.createLocalEnvironment(1, configuration);
        environment.enableCheckpointing(100);
        var table = StreamTableEnvironment.create(environment);
        table.createTemporarySystemFunction("DH_WINDOW_PICK", new EtlWindowPickFunction());
        var source = environment.addSource(new Input()).returns(Types.ROW_NAMED(new String[]{"key", "seq", "value", "group_id", "text"}, Types.STRING, Types.LONG, Types.BIG_DEC, Types.LONG, Types.STRING));
        table.createTemporaryView("window_source", source);
        String columns = pivot ? "group_id BIGINT,total DECIMAL(38,18),average DOUBLE,minimum DOUBLE,maximum DOUBLE,`last` STRING,`day` TIMESTAMP"
                : "`key` STRING,seq BIGINT,`value` DECIMAL(38,18),group_id BIGINT,`text` STRING";
        table.executeSql("CREATE TEMPORARY TABLE window_target(" + columns + ") WITH('connector'='filesystem','path'='/tmp/window-state/" + args[0] + "-results','format'='json')");
        String sql = Files.readString(Path.of(args[1]));
        Files.writeString(Path.of("/tmp/window-state/" + args[0] + "-explain.txt"), table.explainSql(sql));
        table.executeSql(sql).await();
        System.out.println("FORMAL_WINDOW_SQL_PASS " + args[0]);
    }
    public static final class Input extends RichParallelSourceFunction<Row> {
        private volatile boolean running = true;
        @Override public void run(SourceContext<Row> context) throws Exception {
            while (System.currentTimeMillis() % 2000 > 100 && running) Thread.sleep(10);
            long end = (System.currentTimeMillis() / 2000 + 1) * 2000;
            String[] keys = {"A", "a", "b", "day", "day"}, values = {"10", "20", "5", "0", "0"}, texts = {"first", "second", "third", "2026-11-03", "missing"};
            for (int i = 0; i < keys.length && running; i++) {
                synchronized (context.getCheckpointLock()) { context.collect(Row.of(keys[i], 9_007_199_254_740_993L + i, new BigDecimal(values[i]), 1L, texts[i])); }
                Thread.sleep(25);
            }
            while (System.currentTimeMillis() < end + 150 && running) Thread.sleep(10);
            if (running) synchronized (context.getCheckpointLock()) { context.collect(Row.of("a", 9_007_199_254_741_100L, new BigDecimal("7"), 1L, "next-window")); }
            end = (System.currentTimeMillis() / 2000 + 1) * 2000;
            while (System.currentTimeMillis() < end + 250 && running) Thread.sleep(10);
        }
        @Override public void cancel() { running = false; }
    }
}
