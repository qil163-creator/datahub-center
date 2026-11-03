package cn.iocoder.yudao.framework.etl.flink.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Connection;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.connectors.rabbitmq.RMQSink;
import org.apache.flink.streaming.connectors.rabbitmq.RMQSinkPublishOptions;
import org.apache.flink.streaming.connectors.rabbitmq.SerializableReturnListener;
import org.apache.flink.table.data.RowData;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 官方发布器发送持久消息并逐条等待确认，交付语义不冒充跨系统事务。 */
final class ManagedRabbitSink extends RMQSink<RowData> {
    private static final long serialVersionUID = 1L;
    private final ManagedRabbitConnection resource;
    private final int timeout;
    private final Returned returned;
    ManagedRabbitSink(ManagedRabbitConnection resource, int timeout, SerializationSchema<RowData> encoder) {
        this(resource, timeout, encoder, new Returned());
    }
    private ManagedRabbitSink(ManagedRabbitConnection resource, int timeout, SerializationSchema<RowData> encoder, Returned returned) {
        super(new org.apache.flink.streaming.connectors.rabbitmq.common.RMQConnectionConfig.Builder().setHost(resource.host).setPort(resource.port).setVirtualHost(resource.virtualHost).setUserName("managed-reference").setPassword("unavailable").build(), encoder, new Publish(resource.queue), returned);
        this.resource = resource; this.timeout = timeout; this.returned = returned;
    }
    @Override protected Connection setupConnection() {
        try { return resource.openFactory().newConnection(); }
        catch (Exception failure) { throw new IllegalStateException("RabbitMQ 受管写入连接失败"); }
    }
    @Override protected void setupQueue() throws IOException { channel.queueDeclarePassive(resource.queue); }
    @Override public void open(Configuration configuration) throws Exception {
        super.open(configuration); channel.confirmSelect();
    }
    @Override public void invoke(RowData value) {
        super.invoke(value);
        try { channel.waitForConfirmsOrDie(timeout); if (returned.failed.get()) throw new IllegalStateException(); }
        catch (Exception failure) { throw new IllegalStateException("RabbitMQ 发布未获服务端确认"); }
    }
    private record Publish(String queue) implements RMQSinkPublishOptions<RowData> {
        @Override public String computeRoutingKey(RowData value) { return queue; }
        @Override public AMQP.BasicProperties computeProperties(RowData value) { return new AMQP.BasicProperties.Builder().deliveryMode(2).contentType("application/json").build(); }
        @Override public String computeExchange(RowData value) { return ""; }
        @Override public boolean computeMandatory(RowData value) { return true; }
        @Override public boolean computeImmediate(RowData value) { return false; }
    }
    private static final class Returned implements SerializableReturnListener {
        private static final long serialVersionUID = 1L;
        private final AtomicBoolean failed = new AtomicBoolean();
        @Override public void handleReturn(int code, String text, String exchange, String routingKey, AMQP.BasicProperties properties, byte[] body) { failed.set(true); }
    }
}
