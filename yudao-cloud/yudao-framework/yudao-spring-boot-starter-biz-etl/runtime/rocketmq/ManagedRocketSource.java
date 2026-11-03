package cn.iocoder.yudao.framework.etl.flink.rocketmq;

import org.apache.flink.api.common.serialization.*;
import org.apache.flink.api.common.state.CheckpointListener;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;
import org.apache.flink.table.data.RowData;
import org.apache.rocketmq.client.consumer.*;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.heartbeat.MessageModel;

import java.io.Serializable;
import java.util.*;

/** 使用独立受管消费组，位点随 Flink 检查点保存，完成后才更新官方 Broker OffsetStore。 */
public final class ManagedRocketSource extends RichSourceFunction<RowData> implements CheckpointedFunction, CheckpointListener, ResultTypeQueryable<RowData> {
    private static final long serialVersionUID = 1L;
    private final ManagedRocketResource resource;
    private final String reset;
    private final int timeout;
    private final DeserializationSchema<RowData> decoder;
    private transient volatile DefaultMQPullConsumer consumer;
    private volatile boolean running = true;
    private transient ListState<Cursor> state;
    private transient List<Cursor> restored;
    private transient LinkedHashMap<MessageQueue, Long> positions;
    private transient NavigableMap<Long, List<Cursor>> pending;

    ManagedRocketSource(ManagedRocketResource resource, String reset, int timeout, DeserializationSchema<RowData> decoder) {
        this.resource = resource; this.reset = reset; this.timeout = timeout; this.decoder = decoder;
    }
    @Override public void initializeState(FunctionInitializationContext context) throws Exception {
        state = context.getOperatorStateStore().getListState(new ListStateDescriptor<>("datahub-rocket-offsets", TypeInformation.of(Cursor.class)));
        restored = new ArrayList<>(); pending = new TreeMap<>();
        if (context.isRestored()) for (Cursor cursor : state.get()) {
            validate(cursor); restored.add(cursor);
        }
    }
    @Override public void open(Configuration configuration) throws Exception {
        if (!(getRuntimeContext() instanceof StreamingRuntimeContext context) || !context.isCheckpointingEnabled()) throw new IllegalStateException("RocketMQ 持续读取必须启用检查点");
        decoder.open(RuntimeContextInitializationContextAdapters.deserializationAdapter(getRuntimeContext(), metric -> metric.addGroup("user")));
        try {
            consumer = new DefaultMQPullConsumer(resource.group, resource.credentials()); resource.configure(consumer);
            consumer.setMessageModel(MessageModel.CLUSTERING); consumer.setRegisterTopics(Set.of(resource.topic)); consumer.start();
            var queues = queues(); positions = new LinkedHashMap<>();
            if (!restored.isEmpty() && (restored.size() != queues.size() || restored.stream().anyMatch(cursor -> queues.stream()
                    .noneMatch(queue -> cursor.getBroker().equals(queue.getBrokerName()) && cursor.getQueueId() == queue.getQueueId()))))
                throw new IllegalStateException("RocketMQ 检查点队列与当前拓扑不一致，不能隐式丢弃历史位点");
            for (var queue : queues) {
                var saved = restored.stream().filter(cursor -> cursor.getBroker().equals(queue.getBrokerName()) && cursor.getQueueId() == queue.getQueueId()).toList();
                if (saved.size() > 1) throw new IllegalStateException();
                long offset = saved.isEmpty() ? consumer.fetchConsumeOffset(queue, true) : saved.get(0).getOffset();
                if (offset < 0) offset = reset.equals("earliest") ? consumer.minOffset(queue) : consumer.maxOffset(queue);
                if (offset < 0) throw new IllegalStateException();
                positions.put(queue, offset);
            }
        } catch (Exception failure) { if (consumer != null) consumer.shutdown(); throw new IllegalStateException("RocketMQ 受管队列和检查点位点初始化失败"); }
    }
    @Override public void run(SourceContext<RowData> context) throws Exception {
        long checked = 0;
        while (running) {
            if (System.nanoTime() - checked > 5_000_000_000L) {
                if (!positions.keySet().equals(new LinkedHashSet<>(queues()))) throw new IllegalStateException("RocketMQ 队列拓扑已变化，请从检查点明确恢复");
                checked = System.nanoTime();
            }
            boolean received = false;
            for (var entry : new ArrayList<>(positions.entrySet())) {
                if (!running) break;
                PullResult result;
                try { result = consumer.pull(entry.getKey(), "*", entry.getValue(), 1, timeout); }
                catch (Exception failure) { if (!running) return; throw new IllegalStateException("RocketMQ 受管拉取失败"); }
                if (result.getPullStatus() == PullStatus.OFFSET_ILLEGAL || result.getNextBeginOffset() < entry.getValue()) throw new IllegalStateException("RocketMQ 读取位点已失效，不能隐式跳过消息");
                synchronized (context.getCheckpointLock()) {
                    synchronized (this) {
                        if (result.getPullStatus() == PullStatus.FOUND) for (var message : result.getMsgFoundList()) {
                            if (!resource.topic.equals(message.getTopic())) throw new IllegalStateException("RocketMQ 返回了批准主题之外的消息");
                            context.collect(decoder.deserialize(message.getBody())); received = true;
                        }
                        positions.put(entry.getKey(), result.getNextBeginOffset());
                    }
                }
            }
            if (!received && running) Thread.sleep(100);
        }
    }
    @Override public synchronized void snapshotState(FunctionSnapshotContext context) throws Exception {
        if (positions == null || positions.isEmpty()) throw new IllegalStateException("RocketMQ 尚未建立可保存的明确位点");
        var snapshot = new ArrayList<Cursor>();
        positions.forEach((queue, offset) -> snapshot.add(new Cursor(resource.identity(), queue.getBrokerName(), queue.getQueueId(), offset)));
        state.update(snapshot); pending.put(context.getCheckpointId(), snapshot);
        while (pending.size() > 32) pending.pollFirstEntry();
    }
    @Override public synchronized void notifyCheckpointComplete(long id) throws Exception {
        var completed = pending.floorEntry(id);
        if (completed == null || !running) return;
        var queues = new HashSet<MessageQueue>();
        for (Cursor cursor : completed.getValue()) {
            validate(cursor); var queue = new MessageQueue(resource.topic, cursor.getBroker(), cursor.getQueueId());
            consumer.updateConsumeOffset(queue, cursor.getOffset()); queues.add(queue);
        }
        consumer.getOffsetStore().persistAll(queues); pending.headMap(id, true).clear();
    }
    @Override public void cancel() { running = false; if (consumer != null) consumer.shutdown(); }
    @Override public void close() { cancel(); }
    @Override public TypeInformation<RowData> getProducedType() { return decoder.getProducedType(); }
    private List<MessageQueue> queues() throws Exception {
        var queues = consumer.fetchSubscribeMessageQueues(resource.topic);
        if (queues == null || queues.isEmpty() || queues.size() > 64 || queues.stream().anyMatch(queue -> !resource.topic.equals(queue.getTopic()) || queue.getQueueId() < 0 || queue.getQueueId() > 65535
                || queue.getBrokerName() == null || !queue.getBrokerName().matches("[A-Za-z0-9_.-]{1,128}"))) throw new IllegalStateException("RocketMQ 队列元数据超出批准范围或预算");
        return queues.stream().sorted(Comparator.comparing(MessageQueue::getBrokerName).thenComparingInt(MessageQueue::getQueueId)).toList();
    }
    private void validate(Cursor cursor) {
        if (cursor == null || !resource.identity().equals(cursor.getResourceIdentity()) || cursor.getOffset() < 0 || cursor.getQueueId() < 0 || cursor.getQueueId() > 65535
                || cursor.getBroker() == null || !cursor.getBroker().matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("RocketMQ 恢复位点不属于当前受管资源");
    }
    public static final class Cursor implements Serializable {
        private static final long serialVersionUID = 1L;
        private String resourceIdentity, broker;
        private int queueId;
        private long offset;
        public Cursor() { }
        Cursor(String resourceIdentity, String broker, int queueId, long offset) { this.resourceIdentity = resourceIdentity; this.broker = broker; this.queueId = queueId; this.offset = offset; }
        public String getResourceIdentity() { return resourceIdentity; } public void setResourceIdentity(String value) { resourceIdentity = value; }
        public String getBroker() { return broker; } public void setBroker(String value) { broker = value; }
        public int getQueueId() { return queueId; } public void setQueueId(int value) { queueId = value; }
        public long getOffset() { return offset; } public void setOffset(long value) { offset = value; }
    }
}
