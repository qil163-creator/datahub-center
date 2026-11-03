package cn.iocoder.yudao.module.dataforge.service.push.channel;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;
import cn.iocoder.yudao.module.dataforge.service.push.PushPayload;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "dataforge.test.kafka.port", matches = "[0-9]+")
@Timeout(45)
class MqPushKafkaIntegrationTest {
    private Admin admin;
    private String address;
    private String topic;

    @BeforeEach void prepare() throws Exception {
        address = "127.0.0.1:" + System.getProperty("dataforge.test.kafka.port");
        topic = "codex-q12-" + UUID.randomUUID();
        admin = Admin.create(Map.of("bootstrap.servers", address));
        admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
    }

    @AfterEach void cleanup() throws Exception {
        if (admin == null) return;
        try { admin.deleteTopics(List.of(topic)).all().get(20, TimeUnit.SECONDS); }
        finally { admin.close(Duration.ofSeconds(5)); }
    }

    private String config(String acks) {
        var config = JsonUtils.parseObject(MqPushDeliveryTest.kafka(acks), DataPushChannelConfigVO.class);
        config.getMq().getKafka().setBootstrapServers(address);
        config.getMq().getKafka().setTopic(topic);
        return JsonUtils.toJsonString(config);
    }

    @ParameterizedTest @ValueSource(strings = {"all", "1", "-1"})
    void acknowledgedBatchesAreReadableOnceInOrder(String acks) {
        var channel = new MqPushChannel();
        String config = config(acks);
        List<String> expected = new ArrayList<>();
        try (var session = channel.openSession(config)) {
            for (int i = 0; i < 2; i++) {
                var payload = new PushPayload("fixture", List.of(Map.of("id", "900719925474099" + i)));
                expected.add(JsonUtils.toJsonString(payload));
                channel.push(payload, config, null);
            }
        }
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, address);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            var partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            assertEquals(2L, consumer.endOffsets(List.of(partition)).get(partition));
            List<String> actual = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (actual.size() < 2 && System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(250))) actual.add(record.value());
            }
            assertEquals(expected, actual);
        }
    }

    @Test void abortAndRejectedAcksCannotAppendRecords() throws Exception {
        var channel = new MqPushChannel();
        assertThrows(IllegalArgumentException.class, () -> channel.openSession(config("0")));
        try (var session = channel.openSession(config("all"))) {
            var aborter = Thread.ofVirtual().start(session::abort);
            aborter.join(5_000);
            assertFalse(aborter.isAlive());
            assertThrows(IllegalStateException.class, () -> channel.push(new PushPayload(), config("all"), null));
        }
        var offset = admin.listOffsets(Map.of(new TopicPartition(topic, 0), OffsetSpec.latest()))
                .all().get(20, TimeUnit.SECONDS);
        assertEquals(0L, offset.get(new TopicPartition(topic, 0)).offset());
    }
}
