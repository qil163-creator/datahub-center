package cn.iocoder.yudao.framework.etl.messages;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.flink.bounded.ManagedFlinkMessageTransport;
import cn.iocoder.yudao.framework.etl.flink.rabbitmq.ManagedRabbitConnection;
import java.util.*;

/** 真正AMQP夹具：确认种子发布、有限浏览、输出字段保真及关闭通道后源队列仍保留。 */
public final class BoundedMessageRabbitFixture {
    public static void main(String[] arguments) throws Exception {
        boolean seed = arguments[0].equals("seed");
        String queue = seed ? "qa_bounded_input" : "qa_bounded_output";
        var resource = new ManagedRabbitConnection("bounded-rabbit", 5672, "/", queue, "qa.rabbit", false, seed);
        if (seed) try (var connection = resource.openFactory().newConnection(); var channel = connection.createChannel()) {
            channel.queueDeclare("qa_bounded_input", true, false, false, Map.of()); channel.queueDeclare("qa_bounded_output", true, false, false, Map.of());
        }
        var types = new JSONObject().set("id", new JSONObject().set("sqlType", "BIGINT")).set("label", new JSONObject().set("sqlType", "STRING")).set("amount", new JSONObject().set("sqlType", "DECIMAL(38,8)"));
        var plan = new JSONObject().set("host", "bounded-rabbit").set("port", 5672).set("virtualHost", "/").set("queue", queue).set("credentialRef", "qa.rabbit").set("useSsl", false)
                .set("properties", new JSONObject().set("confirm.timeout.ms", 10000)).set("resultTypes", types).set("maxRows", 1000).set("maxBytes", 1_048_576).set("timeoutMillis", 30_000);
        var transport = new ManagedFlinkMessageTransport();
        if (seed) {
            try (var writer = transport.writer("rabbitmq", plan)) { writer.write(Map.of("id", "9007199254740993", "label", "river O'Reilly😀", "amount", "12345678901234567890.12345678"));
                var second = new LinkedHashMap<String,String>(); second.put("id", "9007199254740994"); second.put("label", null); second.put("amount", null); writer.write(second); writer.finish(); }
            System.out.println("rabbit-seed-confirmed=2");
        } else {
            var rows = new ArrayList<JSONObject>(); transport.read("rabbitmq", plan, json -> rows.add(JSONUtil.parseObj(json)));
            rows.sort(Comparator.comparing(value -> value.getStr("id")));
            if (rows.size() != 2 || !"9007199254740993".equals(rows.get(0).getStr("id")) || !"RIVER O'REILLY😀".equals(rows.get(0).getStr("label"))
                    || !"12345678901234567890.12345678".equals(rows.get(0).getStr("amount")) || !"9007199254740994".equals(rows.get(1).getStr("id"))
                    || !JSONUtil.isNull(rows.get(1).get("label")) || !JSONUtil.isNull(rows.get(1).get("amount"))) throw new IllegalStateException("Spark Rabbit实际结果不符：" + rows);
            var input = new ManagedRabbitConnection("bounded-rabbit", 5672, "/", "qa_bounded_input", "qa.rabbit", false, false);
            try (var connection = input.openFactory().newConnection(); var channel = connection.createChannel()) {
                if (channel.queueDeclarePassive("qa_bounded_input").getMessageCount() != 2 || channel.queueDeclarePassive("qa_bounded_output").getMessageCount() != 2)
                    throw new IllegalStateException("有界浏览错误确认或丢弃了原队列消息");
            }
            System.out.println("spark-rabbit-exact-rows=" + rows + "; sourceRetained=2; outputRetained=2");
        }
    }
}
