package cn.iocoder.yudao.framework.etl.flink.rocketmq;

import cn.iocoder.yudao.framework.etl.rocketmq.shaded.org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import cn.iocoder.yudao.framework.etl.rocketmq.shaded.org.apache.rocketmq.client.consumer.PullStatus;
import cn.iocoder.yudao.framework.etl.rocketmq.shaded.org.apache.rocketmq.client.producer.DefaultMQProducer;
import cn.iocoder.yudao.framework.etl.rocketmq.shaded.org.apache.rocketmq.client.producer.SendStatus;
import cn.iocoder.yudao.framework.etl.rocketmq.shaded.org.apache.rocketmq.common.message.Message;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.util.*;

/** 只操作隔离验收主题；秘密由批准的工作端引用读取，结果不包含任何认证材料。 */
public final class RocketFixtureClient {
    public static void main(String[] args) throws Exception {
        String topic = args.length > 2 ? args[2] : "components_input";
        if (args[0].equals("publish")) {
            var resource = new ManagedRocketResource("rocket-native-namesrv:9876", topic, "components_seed", "rocket.fixture", false, true);
            var producer = new DefaultMQProducer(resource.group, resource.credentials()); resource.configure(producer);
            producer.setRetryTimesWhenSendFailed(0); producer.start();
            try {
                var result = producer.send(new Message(topic, Files.readAllBytes(Path.of(args[1]))), 5000);
                if (result.getSendStatus() != SendStatus.SEND_OK) throw new AssertionError("验收消息未获确认");
            } finally { producer.shutdown(); }
            System.out.println("{\"published\":true}"); return;
        }
        boolean offsets = args[0].equals("offsets");
        var resource = new ManagedRocketResource("rocket-native-namesrv:9876", offsets ? "components_input" : "components_output",
                offsets ? "components_read" : "components_verify", "rocket.fixture", false, false);
        var consumer = new DefaultMQPullConsumer(resource.group, resource.credentials()); resource.configure(consumer); consumer.setRegisterTopics(Set.of(resource.topic)); consumer.start();
        try {
            var mapper = new ObjectMapper(); var result = new ArrayList<Object>();
            for (var queue : consumer.fetchSubscribeMessageQueues(resource.topic)) {
                if (offsets) { result.add(Map.of("queue", queue.getQueueId(), "saved", consumer.fetchConsumeOffset(queue, true), "end", consumer.maxOffset(queue))); continue; }
                long cursor = consumer.minOffset(queue), end = consumer.maxOffset(queue);
                while (cursor < end) {
                    var pulled = consumer.pull(queue, "*", cursor, 32, 1000);
                    if (pulled.getPullStatus() == PullStatus.OFFSET_ILLEGAL || pulled.getNextBeginOffset() <= cursor) throw new AssertionError("验收读取位点无效");
                    if (pulled.getPullStatus() == PullStatus.FOUND) for (var message : pulled.getMsgFoundList()) result.add(mapper.readValue(message.getBody(), Map.class));
                    cursor = pulled.getNextBeginOffset();
                }
            }
            System.out.println(mapper.writeValueAsString(result));
        } finally { consumer.shutdown(); }
    }
}
