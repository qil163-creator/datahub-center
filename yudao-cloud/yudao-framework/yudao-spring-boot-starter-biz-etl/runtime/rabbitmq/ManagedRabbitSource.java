package cn.iocoder.yudao.framework.etl.flink.rabbitmq;

import com.rabbitmq.client.Connection;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.table.data.RowData;
import org.apache.flink.streaming.connectors.rabbitmq.RMQSource;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext;

import java.io.IOException;

/** 使用官方检查点确认机制，不自动创建或改写管理员队列。 */
final class ManagedRabbitSource extends RMQSource<RowData> {
    private static final long serialVersionUID = 1L;
    private final ManagedRabbitConnection resource;
    private final int prefetch;
    ManagedRabbitSource(ManagedRabbitConnection resource, int prefetch, DeserializationSchema<RowData> decoder) {
        super(new org.apache.flink.streaming.connectors.rabbitmq.common.RMQConnectionConfig.Builder().setHost(resource.host).setPort(resource.port).setVirtualHost(resource.virtualHost).setUserName("managed-reference").setPassword("unavailable").build(), resource.queue, false, decoder); this.resource = resource; this.prefetch = prefetch;
    }
    @Override public void open(Configuration configuration) throws Exception {
        if (!(getRuntimeContext() instanceof StreamingRuntimeContext context) || !context.isCheckpointingEnabled())
            throw new IllegalStateException("RabbitMQ 持续读取必须启用检查点确认，不能以自动确认模式消费消息");
        super.open(configuration);
    }
    @Override protected Connection setupConnection() {
        try { return resource.openFactory().newConnection(); }
        catch (Exception failure) { throw new IllegalStateException("RabbitMQ 受管读取连接失败"); }
    }
    @Override protected void setupQueue() throws IOException {
        // 每个消费者单独设置预取，避免旧版官方实现的全局 QoS 限制。
        channel.basicQos(prefetch, false); channel.queueDeclarePassive(resource.queue);
    }
}
