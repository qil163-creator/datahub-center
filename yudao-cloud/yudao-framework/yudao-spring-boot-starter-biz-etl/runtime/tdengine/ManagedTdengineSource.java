package cn.iocoder.yudao.framework.etl.flink.tdengine;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets;
import com.taosdata.flink.cdc.TDengineCdcSource;
import com.taosdata.flink.cdc.enumerator.TDengineCdcEnumState;
import com.taosdata.flink.cdc.serializable.TDengineCdcEnumStateSerializer;
import com.taosdata.flink.cdc.serializable.TDengineCdcSplitSerializer;
import com.taosdata.flink.cdc.split.TDengineCdcSplit;
import com.taosdata.flink.common.TDengineCdcParams;
import org.apache.flink.api.common.state.CheckpointListener;
import org.apache.flink.api.common.eventtime.Watermark;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.connector.source.*;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.table.types.logical.RowType;

import java.io.StringReader;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** 只序列化公开资源和引用；官方 TMQ reader/enumerator 在运行端创建时才取得凭据。 */
public final class ManagedTdengineSource implements Source<RowData, TDengineCdcSplit, TDengineCdcEnumState>, ResultTypeQueryable<RowData> {
    private static final long serialVersionUID = 1L;
    private final String servers, topic, group, offset, credentialRef;
    private final int pollMillis;
    private final boolean ssl;
    private final RowType rowType;

    public ManagedTdengineSource(String servers, String topic, String group, String offset, String credentialRef,
                                 int pollMillis, boolean ssl, RowType rowType) {
        this.servers = servers; this.topic = topic; this.group = group; this.offset = offset;
        this.credentialRef = credentialRef; this.pollMillis = pollMillis; this.ssl = ssl; this.rowType = rowType;
    }

    @Override public Boundedness getBoundedness() { return Boundedness.CONTINUOUS_UNBOUNDED; }
    @Override public TypeInformation<RowData> getProducedType() { return InternalTypeInfo.of(rowType); }
    @Override public SimpleVersionedSerializer<TDengineCdcSplit> getSplitSerializer() { return new TDengineCdcSplitSerializer(); }
    @Override public SimpleVersionedSerializer<TDengineCdcEnumState> getEnumeratorCheckpointSerializer() { return new TDengineCdcEnumStateSerializer(); }
    @Override public SplitEnumerator<TDengineCdcSplit, TDengineCdcEnumState> createEnumerator(SplitEnumeratorContext<TDengineCdcSplit> context) throws Exception {
        return delegate().createEnumerator(context);
    }
    @Override public SplitEnumerator<TDengineCdcSplit, TDengineCdcEnumState> restoreEnumerator(SplitEnumeratorContext<TDengineCdcSplit> context, TDengineCdcEnumState state) throws Exception {
        if (state == null) throw new IllegalArgumentException("TDengine 恢复状态缺失");
        validate(state.getUnassignedCdcSplits()); validate(state.getAssignmentCdcSplits());
        return delegate().restoreEnumerator(context, state);
    }
    @Override public SourceReader<RowData, TDengineCdcSplit> createReader(SourceReaderContext context) throws Exception {
        return new CheckedReader(delegate().createReader(context));
    }

    private TDengineCdcSource<RowData> delegate() {
        try {
            Properties secret = new Properties(); secret.load(new StringReader(EtlManagedSecrets.readFromEnvironment(credentialRef)));
            if (!secret.stringPropertyNames().equals(Set.of("username", "password", "bootstrap.servers", "topics", "groups", "useSsl"))) throw new IllegalArgumentException();
            if (!servers.equals(secret.getProperty("bootstrap.servers")) || !Boolean.toString(ssl).equals(secret.getProperty("useSsl"))
                    || !allowed(secret.getProperty("topics"), topic) || !allowed(secret.getProperty("groups"), group)) throw new IllegalArgumentException();
            String username = secret.getProperty("username"), password = secret.getProperty("password");
            if (username == null || !username.matches("[A-Za-z0-9_.-]{1,64}") || password == null || password.isEmpty() || password.length() > 1024
                    || password.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            Properties properties = new RedactedProperties();
            properties.setProperty(TDengineCdcParams.CONNECT_TYPE, "ws"); properties.setProperty(TDengineCdcParams.BOOTSTRAP_SERVERS, servers);
            properties.setProperty(TDengineCdcParams.CONNECT_USER, username); properties.setProperty(TDengineCdcParams.CONNECT_PASS, password);
            properties.setProperty(TDengineCdcParams.CONNECT_USE_SSL, Boolean.toString(ssl)); properties.setProperty(TDengineCdcParams.GROUP_ID, group);
            properties.setProperty(TDengineCdcParams.AUTO_OFFSET_RESET, offset); properties.setProperty(TDengineCdcParams.ENABLE_AUTO_COMMIT, "false");
            properties.setProperty(TDengineCdcParams.VALUE_DESERIALIZER, ManagedTdengineDeserializer.class.getName());
            properties.setProperty(ManagedTdengineDeserializer.SCHEMA, rowType.asSerializableString());
            properties.setProperty(TDengineCdcParams.VALUE_DESERIALIZER_ENCODING, "UTF-8");
            properties.setProperty(TDengineCdcParams.POLL_INTERVAL_MS, Integer.toString(pollMillis)); properties.setProperty(TDengineCdcParams.CONNECT_TIMEOUT, "10000");
            properties.setProperty(TDengineCdcParams.CONNECT_MESSAGE_TIMEOUT, "10000");
            return new TDengineCdcSource<>(topic, properties, RowData.class);
        } catch (Exception failure) { throw new IllegalStateException("TDengine 受管凭据不可用或资源范围不匹配"); }
    }

    private static boolean allowed(String names, String expected) {
        return names != null && Arrays.stream(names.split(",", -1)).map(String::trim).anyMatch(expected::equals);
    }
    private void validate(Collection<TDengineCdcSplit> splits) {
        if (splits == null) return;
        for (TDengineCdcSplit split : splits) {
            if (split == null || !topic.equals(split.getTopic()) || !group.equals(split.getGroupId()))
                throw new IllegalArgumentException("TDengine 恢复分片不属于当前受管主题和消费组");
            if (split.getStartPartitions() != null && (split.getStartPartitions().size() > 4096 || split.getStartPartitions().stream().anyMatch(partition -> partition == null || !topic.equals(partition.getTopic()))))
                throw new IllegalArgumentException("TDengine 恢复位点不属于当前受管主题");
        }
    }
    private static final class RedactedProperties extends Properties {
        @Override public synchronized String toString() { return "{managed TDengine connection properties redacted}"; }
    }

    /** 恢复分片也验证资源范围；checkpoint 通知继续交给官方 reader 提交位点。 */
    private final class CheckedReader implements SourceReader<RowData, TDengineCdcSplit>, CheckpointListener {
        private final SourceReader<RowData, TDengineCdcSplit> reader;
        private boolean sourceReady;
        private List<TDengineCdcSplit> beforeReadyState;
        private long beforeReadyCheckpoint = -1, completedBeforeReady = -1;
        private CheckedReader(SourceReader<RowData, TDengineCdcSplit> reader) { this.reader = reader; }
        @Override public void start() { reader.start(); }
        @Override public InputStatus pollNext(ReaderOutput<RowData> output) throws Exception {
            InputStatus status = reader.pollNext(new ReaderOutput<>() {
                @Override public void collect(RowData row) { sourceReady = true; output.collect(row); }
                @Override public void collect(RowData row, long timestamp) { sourceReady = true; output.collect(row, timestamp); }
                @Override public void emitWatermark(Watermark watermark) { output.emitWatermark(watermark); }
                @Override public void markIdle() { output.markIdle(); }
                @Override public void markActive() { output.markActive(); }
                @Override public SourceOutput<RowData> createOutputForSplit(String id) {
                    SourceOutput<RowData> target = output.createOutputForSplit(id);
                    return new SourceOutput<>() {
                        @Override public void collect(RowData row) { sourceReady = true; target.collect(row); }
                        @Override public void collect(RowData row, long timestamp) { sourceReady = true; target.collect(row, timestamp); }
                        @Override public void emitWatermark(Watermark watermark) { target.emitWatermark(watermark); }
                        @Override public void markIdle() { target.markIdle(); }
                        @Override public void markActive() { target.markActive(); }
                    };
                }
                @Override public void releaseOutputForSplit(String id) { output.releaseOutputForSplit(id); }
            });
            if (sourceReady && completedBeforeReady >= 0 && (Object) reader instanceof CheckpointListener listener) {
                listener.notifyCheckpointComplete(completedBeforeReady); completedBeforeReady = -1;
            }
            if (sourceReady) beforeReadyState = null;
            return status;
        }
        @Override public List<TDengineCdcSplit> snapshotState(long checkpoint) {
            if (!sourceReady && beforeReadyState != null) return copy(beforeReadyState);
            var splits = reader.snapshotState(checkpoint); validate(splits);
            if (!sourceReady) { beforeReadyCheckpoint = checkpoint; beforeReadyState = copy(splits); }
            return splits;
        }
        @Override public CompletableFuture<Void> isAvailable() { return reader.isAvailable(); }
        @Override public void addSplits(List<TDengineCdcSplit> splits) { validate(splits); if (!sourceReady) beforeReadyState = null; reader.addSplits(splits); }
        @Override public void notifyNoMoreSplits() { reader.notifyNoMoreSplits(); }
        @Override public void handleSourceEvents(SourceEvent event) { reader.handleSourceEvents(event); }
        @Override public void close() throws Exception { reader.close(); }
        @Override public void notifyCheckpointComplete(long checkpoint) throws Exception {
            // 官方 reader 在恢复后可能先收到 checkpoint 完成事件，TMQ consumer 此时尚未完成惰性订阅。
            // 首条实际记录证明订阅完成后补交已完成的旧位点；等待期间复用同一原生状态，避免 SDK 提交队列无界增长。
            if (!sourceReady) { if (beforeReadyCheckpoint >= 0 && checkpoint >= beforeReadyCheckpoint) completedBeforeReady = beforeReadyCheckpoint; return; }
            if ((Object) reader instanceof CheckpointListener listener) listener.notifyCheckpointComplete(checkpoint);
        }
        @Override public void notifyCheckpointAborted(long checkpoint) throws Exception { if ((Object) reader instanceof CheckpointListener listener) listener.notifyCheckpointAborted(checkpoint); }
        private List<TDengineCdcSplit> copy(List<TDengineCdcSplit> splits) {
            return splits.stream().map(split -> {
                var copied = new TDengineCdcSplit(split.splitId(), split.getTopic(), split.getGroupId(), split.getClientId());
                copied.setStartPartitions(split.getStartPartitions() == null ? null : List.copyOf(split.getStartPartitions()));
                return copied;
            }).toList();
        }
    }
}
