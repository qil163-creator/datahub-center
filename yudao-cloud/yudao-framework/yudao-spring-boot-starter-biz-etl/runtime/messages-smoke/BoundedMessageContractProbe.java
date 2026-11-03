package cn.iocoder.yudao.framework.etl.messages;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlBoundedConnectorTransport;
import cn.iocoder.yudao.framework.etl.flink.bounded.ManagedFlinkMessageTransport;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 无网络合同探针：精确标量/确定路径和关闭不发布，SDK引用由真实工作端类加载器解析。 */
public final class BoundedMessageContractProbe {
    public static void main(String[] arguments) throws Exception {
        var transports = ServiceLoader.load(EtlBoundedConnectorTransport.class).stream().map(ServiceLoader.Provider::get).filter(value -> value.kinds().contains("kafka")).toList();
        if (transports.size() != 1) throw new IllegalStateException("消息SPI没有唯一注册");
        var method = ManagedFlinkMessageTransport.class.getDeclaredMethod("payload", JSONObject.class, byte[].class); method.setAccessible(true);
        var fields = new JSONObject().set("id", new JSONObject().set("sqlType", "BIGINT")).set("amount", new JSONObject().set("sqlType", "DECIMAL(38,8)"));
        var plan = new JSONObject().set("resultTypes", fields).set("columns", List.of(Map.of("name", "id", "path", "$.payload.id"), Map.of("name", "amount", "path", "$.payload.amount")));
        String text = (String) method.invoke(null, plan, "{\"payload\":{\"id\":9007199254740993,\"amount\":12345678901234567890.12345678}}".getBytes(StandardCharsets.UTF_8));
        var row = JSONUtil.parseObj(text); if (!"9007199254740993".equals(row.getStr("id")) || !"12345678901234567890.12345678".equals(row.getStr("amount"))) throw new IllegalStateException("消息精确数值改变");
        try { method.invoke(null, plan, "{\"payload\":{\"id\":1}}".getBytes(StandardCharsets.UTF_8)); throw new IllegalStateException("缺失路径未拒绝"); }
        catch (InvocationTargetException expected) { if (!(expected.getCause() instanceof IllegalArgumentException)) throw expected; }
        var nullablePlan = new JSONObject().set("resultTypes", new JSONObject().set("id", new JSONObject().set("sqlType", "BIGINT")).set("label", new JSONObject().set("sqlType", "STRING")));
        var nullable = new ManagedFlinkMessageTransport().writer("kafka", nullablePlan); var values = new LinkedHashMap<String,String>(); values.put("id", "9007199254740994"); values.put("label", null); nullable.write(values);
        var staged = nullable.getClass().getDeclaredField("rows"); staged.setAccessible(true); String message = new String((byte[]) ((List<?>) staged.get(nullable)).get(0), StandardCharsets.UTF_8); nullable.close();
        if (!message.contains("\"label\":null")) throw new IllegalStateException("声明的空字段不能从消息JSON中丢弃");
        method.invoke(null, nullablePlan, message.getBytes(StandardCharsets.UTF_8));
        var offset = ManagedFlinkMessageTransport.class.getDeclaredMethod("rocketStart", long.class, long.class, long.class, String.class); offset.setAccessible(true);
        if (!Long.valueOf(3).equals(offset.invoke(null, 1L, 8L, 3L, "latest")) || !Long.valueOf(8).equals(offset.invoke(null, 1L, 8L, -1L, "latest"))) throw new IllegalStateException("RocketMQ重置模式不能覆盖已有明确消费位点");
        try { offset.invoke(null, 4L, 8L, 3L, "earliest"); throw new IllegalStateException("过期消费位点未拒绝"); } catch (InvocationTargetException expected) { if (!(expected.getCause() instanceof IllegalStateException)) throw expected; }
        var writer = transports.get(0).writer("kafka", plan); writer.write(Map.of("id", "9007199254740993", "amount", "12345678901234567890.12345678")); writer.close();
        boolean denied = false; try { writer.finish(); } catch (IllegalStateException expected) { denied = true; } if (!denied) throw new IllegalStateException("关闭后错误提交");
        Class.forName("org.apache.kafka.clients.consumer.KafkaConsumer"); Class.forName("com.rabbitmq.client.ConnectionFactory"); Class.forName("org.apache.rocketmq.client.consumer.DefaultMQPullConsumer"); Class.forName("com.taosdata.jdbc.ws.WebSocketDriver");
        System.out.println("bounded-message-contracts=passed; networkRequests=0; precision=exact; closePublishes=false");
    }
}
