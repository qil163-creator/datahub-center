package cn.iocoder.yudao.module.governance.service.master;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Single local Kafka broker; does not prove multi-replica failover or consumer business completion. */
@EnabledIfSystemProperty(named = "governance.test.kafka.port", matches = "[0-9]+")
@Timeout(70)
class MasterDistributeMqKafkaIntegrationTest {
    private Admin admin;
    private String address;
    private String topic;

    @BeforeEach void prepare(TestInfo test) throws Exception {
        address = "127.0.0.1:" + System.getProperty("governance.test.kafka.port");
        topic = "codex-master-" + UUID.randomUUID(); admin = Admin.create(Map.of("bootstrap.servers", address));
        var definition = new NewTopic(topic, 1, (short) 1);
        if (test.getTestMethod().orElseThrow().getName().startsWith("brokerReject")) definition.configs(Map.of("max.message.bytes", "1"));
        admin.createTopics(List.of(definition)).all().get(15, TimeUnit.SECONDS);
    }

    @AfterEach void cleanup() throws Exception {
        if (admin == null) return;
        try { admin.deleteTopics(List.of(topic)).all().get(15, TimeUnit.SECONDS); }
        finally { admin.close(Duration.ofSeconds(5)); }
    }

    @Test void acknowledgedBatchesAreReadableOnceWithUnchangedJsonArrayContract() throws Exception {
        try (var fixture = new MasterMqFixture(MasterMqFixture.kafka(address, topic))) {
            fixture.execute(); fixture.outcome(true, 3); assertEquals(2L, fixture.run.getDataCount());
        }
        var props = new Properties(); props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, address);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            var partition = new TopicPartition(topic, 0); consumer.assign(List.of(partition)); consumer.seekToBeginning(List.of(partition));
            assertEquals(2L, consumer.endOffsets(List.of(partition)).get(partition));
            List<String> received = new ArrayList<>(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (received.size() < 2 && System.nanoTime() < deadline) for (var row : consumer.poll(Duration.ofMillis(200))) received.add(row.value());
            assertEquals(List.of("[{\"id\":1}]", "[{\"id\":2}]"), received);
        }
    }

    @Test void brokerRejectedBatchRemainsUnknownAndDoesNotSendFollowingBatch() throws Exception {
        try (var fixture = new MasterMqFixture(MasterMqFixture.kafka(address, topic))) {
            fixture.execute(); fixture.outcome(true, 5); assertEquals(0L, fixture.run.getDataCount());
        }
        var partition = new TopicPartition(topic, 0);
        assertEquals(0L, admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all().get(10, TimeUnit.SECONDS).get(partition).offset());
    }
}
