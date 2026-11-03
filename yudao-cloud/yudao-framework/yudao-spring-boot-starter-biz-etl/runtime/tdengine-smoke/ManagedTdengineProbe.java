import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets;
import cn.iocoder.yudao.framework.etl.flink.tdengine.ManagedTdengineDeserializer;
import cn.iocoder.yudao.framework.etl.flink.tdengine.ManagedTdengineSource;
import cn.iocoder.yudao.framework.etl.vendor.tdengine.com.taosdata.flink.cdc.enumerator.TDengineCdcEnumState;
import cn.iocoder.yudao.framework.etl.vendor.tdengine.com.taosdata.flink.cdc.split.TDengineCdcSplit;
import cn.iocoder.yudao.framework.etl.vendor.tdengine.com.taosdata.jdbc.ws.tmq.ConsumerAction;
import cn.iocoder.yudao.framework.etl.vendor.tdengine.com.taosdata.flink.cdc.entity.CdcTopicPartition;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.eventtime.Watermark;
import org.apache.flink.api.common.state.CheckpointListener;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.api.connector.source.*;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.v2.DiscardingSink;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.*;
import org.apache.flink.util.InstantiationUtil;

import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** 独立于生产 JAR 的 Java17 探针：实际序列化、官方 checkpoint serializer 与类型值验证。 */
public final class ManagedTdengineProbe {
    @SuppressWarnings("unchecked")
    public static void main(String[] arguments) throws Exception {
        Path directory = Path.of(arguments[0]); Files.createDirectories(directory);
        if (!ConsumerAction.COMMIT_OFFSET.getAction().equals("commit_offset") || !ConsumerAction.COMMIT.getAction().equals("commit") || !ConsumerAction.COMMITTED.getAction().equals("committed"))
            throw new AssertionError("厂商包重定位不能改写网络协议动作名");
        RowType type = RowType.of(new LogicalType[]{new TimestampType(3), new DoubleType(), new VarCharType(VarCharType.MAX_LENGTH)}, new String[]{"ts", "reading", "station"});
        var source = new ManagedTdengineSource("datahub-native-tdengine:6041", "sensor_topic", "sensor_group", "earliest", "tdengine-test", 500, false, type);
        SplitEnumeratorContext<TDengineCdcSplit> context = (SplitEnumeratorContext<TDengineCdcSplit>) Proxy.newProxyInstance(ManagedTdengineProbe.class.getClassLoader(), new Class[]{SplitEnumeratorContext.class}, (proxy, method, args) -> switch (method.getName()) {
            case "currentParallelism" -> 1;
            case "registeredReaders" -> Map.of();
            case "toString" -> "managed-test-context";
            default -> null;
        });
        if (arguments.length > 1 && arguments[1].equals("deny")) {
            try { source.createEnumerator(context); throw new AssertionError("默认空权限没有拒绝凭据读取"); }
            catch (IllegalStateException expected) { Files.writeString(directory.resolve("denied.json"), "{\"denied\":true}\n"); return; }
        }
        Properties credentials = new Properties(); credentials.load(new StringReader(EtlManagedSecrets.readFromEnvironment("tdengine-test")));
        String password = credentials.getProperty("password");
        var sizes = new LinkedHashMap<String, Integer>();
        write(directory, "source.bin", InstantiationUtil.serializeObject(source), password, sizes);
        var environment = StreamExecutionEnvironment.getExecutionEnvironment(); environment.setParallelism(1);
        environment.fromSource(source, WatermarkStrategy.noWatermarks(), "managed-tdengine", source.getProducedType()).uid("managed-source").sinkTo(new DiscardingSink<>()).uid("managed-sink");
        write(directory, "jobgraph.bin", InstantiationUtil.serializeObject(environment.getStreamGraph().getJobGraph()), password, sizes);
        TDengineCdcEnumState state;
        try (var enumerator = source.createEnumerator(context)) { enumerator.start(); state = enumerator.snapshotState(1); }
        byte[] stateBytes = source.getEnumeratorCheckpointSerializer().serialize(state);
        write(directory, "enumerator-checkpoint.bin", stateBytes, password, sizes);
        var restored = source.getEnumeratorCheckpointSerializer().deserialize(source.getEnumeratorCheckpointSerializer().getVersion(), stateBytes);
        if (restored.getUnassignedCdcSplits().size() != 1) throw new AssertionError("官方枚举状态恢复失败");
        try (var enumerator = source.restoreEnumerator(context, restored)) { enumerator.start(); if (enumerator.snapshotState(2).getUnassignedCdcSplits().size() != 1) throw new AssertionError("受管枚举恢复失败"); }
        var split = restored.getUnassignedCdcSplits().getFirst();
        byte[] splitBytes = source.getSplitSerializer().serialize(split);
        write(directory, "split-checkpoint.bin", splitBytes, password, sizes);
        var restoredSplit = source.getSplitSerializer().deserialize(source.getSplitSerializer().getVersion(), splitBytes);
        if (!restoredSplit.getTopic().equals("sensor_topic") || !restoredSplit.getGroupId().equals("sensor_group")) throw new AssertionError("官方分片恢复失败");
        var forged = new TDengineCdcEnumState(new ArrayDeque<>(List.of(new TDengineCdcSplit("sensor_topic", "other_group", "client", (List) null))), List.of(), true);
        try { source.restoreEnumerator(context, forged); throw new AssertionError("越权分片没有拒绝"); } catch (IllegalArgumentException expected) { }
        validateDeserializer();
        validateDeferredCheckpoint(source);
        String jsonSizes = String.join(",", sizes.entrySet().stream().map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue()).toList());
        Files.writeString(directory.resolve("probe.json"), "{\"serializedBytes\":{" + jsonSizes + "},\"passwordSentinelMatches\":0,\"officialSerializerRestore\":true,\"foreignGroupDenied\":true,\"nanosecondTimestampExact\":true,\"actualTopicSchemaChecked\":true,\"deferredCheckpointAndBoundedPendingState\":true,\"wireActionsVerified\":true,\"timestampZone\":\"" + java.time.ZoneId.systemDefault().getId() + "\"}\n");
    }

    private static void write(Path directory, String name, byte[] bytes, String password, Map<String, Integer> sizes) throws Exception {
        for (var charset : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            byte[] sentinel = password.getBytes(charset);
            for (int start = 0; start <= bytes.length - sentinel.length; start++) {
                boolean match = true; for (int i = 0; i < sentinel.length; i++) if (bytes[start + i] != sentinel[i]) { match = false; break; }
                if (match) throw new AssertionError("凭据材料进入了作业或 checkpoint 序列化内容");
            }
        }
        Files.write(directory.resolve(name), bytes); sizes.put(name, bytes.length);
    }

    private static void validateDeserializer() throws Exception {
        var deserializer = new ManagedTdengineDeserializer();
        RowType type = RowType.of(new LogicalType[]{new TimestampType(9), new DoubleType(), new VarCharType(VarCharType.MAX_LENGTH)}, new String[]{"ts", "reading", "station"});
        var configuration = new HashMap<String, String>(); configuration.put(ManagedTdengineDeserializer.SCHEMA, type.asSerializableString());
        deserializer.configure(configuration);
        if (configuration.containsKey(ManagedTdengineDeserializer.SCHEMA)) throw new AssertionError("私有字段不能发送给 TDengine 服务器");
        Timestamp timestamp = Timestamp.valueOf("2026-11-02 08:09:10.123456789");
        Object[] values = {timestamp, 2.5d, "汉字🙂".getBytes(StandardCharsets.UTF_8)};
        String[] names = {"ts", "reading", "station"};
        ResultSetMetaData meta = (ResultSetMetaData) Proxy.newProxyInstance(ManagedTdengineProbe.class.getClassLoader(), new Class[]{ResultSetMetaData.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> 3; case "getColumnLabel" -> names[(int) args[0] - 1]; default -> throw new UnsupportedOperationException();
        });
        ResultSet rows = (ResultSet) Proxy.newProxyInstance(ManagedTdengineProbe.class.getClassLoader(), new Class[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getMetaData" -> meta; case "getObject" -> values[(int) args[0] - 1]; default -> throw new UnsupportedOperationException();
        });
        RowData row = deserializer.deserialize(rows, "sensors", "values");
        if (!row.getTimestamp(0, 9).toTimestamp().equals(timestamp) || !row.getString(2).toString().equals("汉字🙂")) throw new AssertionError("时间戳或 Unicode 转换错误");
        names[1] = "wrong"; try { deserializer.deserialize(rows, "sensors", "values"); throw new AssertionError("实际主题变化没有拒绝"); } catch (SQLException expected) { }
        names[1] = "reading"; values[1] = "2.5"; try { deserializer.deserialize(rows, "sensors", "values"); throw new AssertionError("类型变化没有拒绝"); } catch (SQLException expected) { }
        values[1] = null; if (!deserializer.deserialize(rows, "sensors", "values").isNullAt(1)) throw new AssertionError("SQL NULL 转换错误");
    }

    @SuppressWarnings("unchecked")
    private static void validateDeferredCheckpoint(ManagedTdengineSource source) throws Exception {
        var constructor = Class.forName(ManagedTdengineSource.class.getName() + "$CheckedReader").getDeclaredConstructors()[0]; constructor.setAccessible(true);
        FakeReader official = new FakeReader();
        SourceReader<RowData, TDengineCdcSplit> checked = (SourceReader<RowData, TDengineCdcSplit>) constructor.newInstance(source, official);
        CheckpointListener listener = (CheckpointListener) checked;
        for (int checkpoint = 1; checkpoint <= 10_000; checkpoint++) {
            var state = checked.snapshotState(checkpoint);
            if (!state.get(0).splitId().equals("stable-split-identity")) throw new AssertionError("等待订阅期间不能改变恢复分片身份");
            listener.notifyCheckpointComplete(checkpoint);
        }
        if (official.snapshots != 1 || official.commits != 0) throw new AssertionError("尚未订阅时提交位点或产生无界的 SDK 提交队列");
        var sink = new NoopOutput(); checked.pollNext(sink);
        if (official.commits != 1) throw new AssertionError("订阅完成后的已完成 checkpoint 位点必须实际提交");
        checked.snapshotState(10_001); listener.notifyCheckpointComplete(10_001);
        if (official.snapshots != 2 || official.commits != 2) throw new AssertionError("订阅后的正常 checkpoint 通知未交给官方 reader");
        var forged = new TDengineCdcSplit("sensor_topic", "sensor_group", "client", List.of(new CdcTopicPartition("foreign_topic", 8L, 2)));
        try { checked.addSplits(List.of(forged)); throw new AssertionError("越权嵌套主题位点没有拒绝"); } catch (IllegalArgumentException expected) { }
    }

    private static final class FakeReader implements SourceReader<RowData, TDengineCdcSplit>, CheckpointListener {
        private int snapshots, commits; private boolean subscribed;
        @Override public void start() { }
        @Override public InputStatus pollNext(ReaderOutput<RowData> output) { subscribed = true; output.createOutputForSplit("stable-split-identity").collect(new GenericRowData(3)); return InputStatus.NOTHING_AVAILABLE; }
        @Override public List<TDengineCdcSplit> snapshotState(long id) { snapshots++; var split = new TDengineCdcSplit("stable-split-identity", "sensor_topic", "sensor_group", "client"); split.setStartPartitions(List.of(new CdcTopicPartition("sensor_topic", 9L, 2))); return List.of(split); }
        @Override public CompletableFuture<Void> isAvailable() { return CompletableFuture.completedFuture(null); }
        @Override public void addSplits(List<TDengineCdcSplit> splits) { }
        @Override public void notifyNoMoreSplits() { }
        @Override public void close() { }
        @Override public void notifyCheckpointComplete(long id) { if (!subscribed) throw new AssertionError("TMQ 尚未订阅"); commits++; }
    }
    private static final class NoopOutput implements ReaderOutput<RowData> {
        @Override public void collect(RowData row) { }
        @Override public void collect(RowData row, long timestamp) { }
        @Override public void emitWatermark(Watermark watermark) { }
        @Override public void markIdle() { }
        @Override public void markActive() { }
        @Override public SourceOutput<RowData> createOutputForSplit(String id) { return this; }
        @Override public void releaseOutputForSplit(String id) { }
    }
}
