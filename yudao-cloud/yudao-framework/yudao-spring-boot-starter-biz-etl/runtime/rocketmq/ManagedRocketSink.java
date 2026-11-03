package cn.iocoder.yudao.framework.etl.flink.rocketmq;

import org.apache.flink.api.common.serialization.*;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.table.data.RowData;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;

/** 同步发送必须得到官方 SEND_OK；无确认、越界或超预算均使作业失败。 */
final class ManagedRocketSink extends RichSinkFunction<RowData> {
    private static final long serialVersionUID = 1L;
    private final ManagedRocketResource resource;
    private final int timeout;
    private final SerializationSchema<RowData> encoder;
    private transient DefaultMQProducer producer;
    ManagedRocketSink(ManagedRocketResource resource, int timeout, SerializationSchema<RowData> encoder) {
        this.resource = resource; this.timeout = timeout; this.encoder = encoder;
    }
    @Override public void open(Configuration configuration) throws Exception {
        encoder.open(RuntimeContextInitializationContextAdapters.serializationAdapter(getRuntimeContext(), metric -> metric.addGroup("user")));
        try {
            producer = new DefaultMQProducer(resource.group, resource.credentials()); resource.configure(producer);
            producer.setSendMsgTimeout(timeout); producer.setRetryTimesWhenSendFailed(0); producer.setMaxMessageSize(1_048_576);
            producer.setCreateTopicKey("DATAHUB_IMPLICIT_TOPIC_CREATION_DISABLED"); producer.start();
            var queues = producer.fetchPublishMessageQueues(resource.topic);
            if (queues == null || queues.isEmpty() || queues.size() > 64 || queues.stream().anyMatch(queue -> !resource.topic.equals(queue.getTopic()))) throw new IllegalStateException();
        } catch (Exception failure) { if (producer != null) producer.shutdown(); throw new IllegalStateException("RocketMQ 受管输出主题初始化失败"); }
    }
    @Override public void invoke(RowData row, Context context) {
        try {
            byte[] body = encoder.serialize(row);
            if (body == null || body.length == 0 || body.length > 1_048_576) throw new IllegalArgumentException();
            var result = producer.send(new Message(resource.topic, body), timeout);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) throw new IllegalStateException();
        } catch (Exception failure) { throw new IllegalStateException("RocketMQ 消息未获同步确认或超过消息预算"); }
    }
    @Override public void close() { if (producer != null) producer.shutdown(); }
}
