package cn.iocoder.yudao.framework.etl.messages;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.flink.bounded.ManagedFlinkMessageTransport;
import java.util.*;

/** 工作端真实Kafka夹具：受管writer确认种子消息，再按固定offset读取并核对精确结果。 */
public final class BoundedMessageKafkaFixture {
    public static void main(String[] arguments) throws Exception {
        var types = new JSONObject().set("id", new JSONObject().set("sqlType", "BIGINT")).set("label", new JSONObject().set("sqlType", "STRING")).set("amount", new JSONObject().set("sqlType", "DECIMAL(38,8)"));
        var plan = new JSONObject().set("bootstrapServers", "bounded-kafka:9092").set("topic", arguments[0].equals("seed") ? "qa_bounded_input" : "qa_bounded_output")
                .set("options", new JSONObject().set("scan.startup.mode", "earliest-offset").set("scan.bounded.mode", "latest-offset").set("properties.acks", "all")).set("resultTypes", types)
                .set("maxRows", 1000).set("maxBytes", 1_048_576).set("timeoutMillis", 30_000);
        var transport = new ManagedFlinkMessageTransport();
        if (arguments[0].equals("seed")) {
            try (var writer = transport.writer("kafka", plan)) { writer.write(Map.of("id", "9007199254740993", "label", "river O'Reilly😀", "amount", "12345678901234567890.12345678"));
                var second = new LinkedHashMap<String,String>(); second.put("id", "9007199254740994"); second.put("label", null); second.put("amount", null); writer.write(second); writer.finish(); }
            System.out.println("kafka-seed-confirmed=2");
        } else {
            plan.getJSONObject("options").remove("properties.acks");
            var rows = new ArrayList<JSONObject>(); transport.read("kafka", plan, json -> rows.add(JSONUtil.parseObj(json)));
            rows.sort(Comparator.comparing(value -> value.getStr("id")));
            if (rows.size() != 2 || !"9007199254740993".equals(rows.get(0).getStr("id")) || !"RIVER O'REILLY😀".equals(rows.get(0).getStr("label"))
                    || !"12345678901234567890.12345678".equals(rows.get(0).getStr("amount")) || !"9007199254740994".equals(rows.get(1).getStr("id"))
                    || !JSONUtil.isNull(rows.get(1).get("label")) || !JSONUtil.isNull(rows.get(1).get("amount"))) throw new IllegalStateException("Spark Kafka实际结果不符：" + rows);
            System.out.println("spark-kafka-exact-rows=" + rows);
        }
    }
}
