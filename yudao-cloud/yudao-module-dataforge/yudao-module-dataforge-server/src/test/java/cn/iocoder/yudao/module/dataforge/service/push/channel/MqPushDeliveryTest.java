package cn.iocoder.yudao.module.dataforge.service.push.channel;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;
import cn.iocoder.yudao.module.dataforge.service.push.PushPayload;
import com.rabbitmq.client.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MqPushDeliveryTest {
    @ParameterizedTest @ValueSource(strings = {"0", "2", "ALL", "bad"})
    void invalidAcksFailPurePreflight(String acks) {
        try (var clients = mockConstruction(KafkaProducer.class)) {
            assertThrows(IllegalArgumentException.class, () -> new MqPushChannel().validateConfiguration(kafka(acks)));
            assertTrue(clients.constructed().isEmpty());
        }
    }

    @Test void kafkaLostReceiptHasNoApplicationRetry() throws Exception {
        Future<RecordMetadata> receipt = mock(Future.class);
        when(receipt.get(anyLong(), any())).thenThrow(new TimeoutException("fixture"));
        try (var clients = mockConstruction(KafkaProducer.class,
                (mock, context) -> when(mock.send(any(ProducerRecord.class))).thenReturn(receipt))) {
            var channel = new MqPushChannel();
            try (var session = channel.openSession(kafka("all"))) {
                assertThrows(IllegalStateException.class, () -> channel.push(new PushPayload(), kafka("all"), null));
                assertThrows(IllegalStateException.class, () -> channel.push(new PushPayload(), kafka("all"), null));
                verify(clients.constructed().getFirst(), times(1)).send(any(ProducerRecord.class));
                verify(clients.constructed().getFirst()).close(Duration.ZERO);
            }
        }
    }

    @Test void kafkaInterruptionPreservesFlagAndAborts() throws Exception {
        Future<RecordMetadata> receipt = mock(Future.class);
        when(receipt.get(anyLong(), any())).thenThrow(new InterruptedException());
        try (var clients = mockConstruction(KafkaProducer.class,
                (mock, context) -> when(mock.send(any(ProducerRecord.class))).thenReturn(receipt))) {
            var channel = new MqPushChannel();
            try (var session = channel.openSession(kafka("all"))) {
                assertThrows(IllegalStateException.class, () -> channel.push(new PushPayload(), kafka("all"), null));
                assertTrue(Thread.currentThread().isInterrupted());
                verify(clients.constructed().getFirst()).close(Duration.ZERO);
            }
        } finally {
            Thread.interrupted();
        }
    }

    @Test void rabbitFailedInitializationReleasesConnection() throws Exception {
        var connection = mock(Connection.class);
        var amqp = mock(Channel.class);
        when(connection.createChannel()).thenReturn(amqp);
        doThrow(new java.io.IOException("fixture")).when(amqp).confirmSelect();
        try (var factories = mockConstruction(ConnectionFactory.class,
                (mock, context) -> when(mock.newConnection()).thenReturn(connection))) {
            assertThrows(IllegalStateException.class, () -> new MqPushChannel().openSession(rabbit()));
            verify(connection).abort(1_000);
        }
    }

    @Test void rabbitNackPoisonsSessionAndDoesNotLeakDriverMessage() throws Exception {
        var connection = mock(Connection.class);
        var amqp = mock(Channel.class);
        when(connection.createChannel()).thenReturn(amqp);
        doThrow(new java.io.IOException("private-payload-fixture")).when(amqp).waitForConfirmsOrDie(anyLong());
        try (var factories = mockConstruction(ConnectionFactory.class,
                (mock, context) -> when(mock.newConnection()).thenReturn(connection))) {
            var channel = new MqPushChannel();
            try (var session = channel.openSession(rabbit())) {
                var failure = assertThrows(IllegalStateException.class, () -> channel.push(new PushPayload(), rabbit(), null));
                assertFalse(failure.getMessage().contains("private-payload"));
                assertThrows(IllegalStateException.class, () -> channel.push(new PushPayload(), rabbit(), null));
                verify(amqp, times(1)).waitForConfirmsOrDie(anyLong());
                verify(connection).abort(1_000);
            }
        }
    }

    @Test void closeFailureIsVisibleAndOldSessionCannotDetachNewSession() throws Exception {
        var connection = mock(Connection.class);
        var amqp = mock(Channel.class);
        when(connection.createChannel()).thenReturn(amqp);
        doThrow(new java.io.IOException("fixture")).when(connection).close(5_000);
        try (var factories = mockConstruction(ConnectionFactory.class,
                (mock, context) -> when(mock.newConnection()).thenReturn(connection))) {
            var channel = new MqPushChannel();
            var old = channel.openSession(rabbit());
            assertThrows(IllegalStateException.class, old::close);
            verify(connection).abort(1_000);
            doNothing().when(connection).close(5_000);
            try (var current = channel.openSession(rabbit())) {
                old.close();
                assertThrows(IllegalStateException.class, () -> channel.openSession(rabbit()));
            }
        }
    }

    static String kafka(String acks) {
        var config=new DataPushChannelConfigVO(); var mq=new DataPushChannelConfigVO.MqConfig();
        var k=new DataPushChannelConfigVO.KafkaConfig(); k.setBootstrapServers("127.0.0.1:19092"); k.setTopic("fixture");
        k.setAcks(acks); mq.setMqType("kafka"); mq.setKafka(k); config.setMq(mq); return JsonUtils.toJsonString(config);
    }
    static String rabbit() {
        var config=new DataPushChannelConfigVO(); var mq=new DataPushChannelConfigVO.MqConfig();
        var r=new DataPushChannelConfigVO.RabbitConfig(); r.setHost("127.0.0.1"); r.setPort(15672);
        r.setExchange("fixture"); r.setRoutingKey("key"); mq.setMqType("rabbitmq"); mq.setRabbit(r); config.setMq(mq);
        return JsonUtils.toJsonString(config);
    }

    @Test void kafkaRejectsUnacknowledgedModeBeforeCreatingClient() {
        try(var clients=mockConstruction(KafkaProducer.class)) {
            var channel=new MqPushChannel();
            assertThrows(RuntimeException.class,()-> { try(var ignored=channel.openSession(kafka("0"))) { } });
            assertTrue(clients.constructed().isEmpty());
        }
    }
    @Test void kafkaDefaultRequiresIdempotentAcknowledgedRetries() {
        AtomicReference<Properties> properties=new AtomicReference<>();
        try(var clients=mockConstruction(KafkaProducer.class,(mock,context)->properties.set((Properties)context.arguments().getFirst()));
            var session=new MqPushChannel().openSession(kafka(null))) {
            var p=properties.get(); assertEquals("all",p.getProperty(ProducerConfig.ACKS_CONFIG));
            assertEquals("true",p.getProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
            assertTrue(Integer.parseInt(p.getProperty(ProducerConfig.RETRIES_CONFIG))>0);
            assertTrue(Integer.parseInt(p.getProperty(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION))<=5);
        }
    }
    @Test void kafkaLegacyLeaderAckHasNoUnsafeSdkRetry() {
        AtomicReference<Properties> properties=new AtomicReference<>();
        try(var clients=mockConstruction(KafkaProducer.class,(mock,context)->properties.set((Properties)context.arguments().getFirst()));
            var session=new MqPushChannel().openSession(kafka("1"))) {
            assertEquals("0",properties.get().getProperty(ProducerConfig.RETRIES_CONFIG));
            assertEquals("false",properties.get().getProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
        }
    }
    @Test void kafkaRejectsMissingOffsetReceiptAndPoisonsSession() {
        try(var clients=mockConstruction(KafkaProducer.class,(mock,context)->when(mock.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(new RecordMetadata(new TopicPartition("fixture",0),-1,0,0,0,0))))) {
            var channel=new MqPushChannel();
            try(var session=channel.openSession(kafka("all"))) {
                assertThrows(RuntimeException.class,()->channel.push(new PushPayload(),kafka("all"),null));
                assertThrows(RuntimeException.class,()->channel.push(new PushPayload(),kafka("all"),null));
                verify(clients.constructed().getFirst(),times(1)).send(any(ProducerRecord.class));
                verify(clients.constructed().getFirst()).close(Duration.ZERO);
            }
        }
    }
    @Test void rabbitRequiresMandatoryPersistentPublishAndDisablesRecovery() throws Exception {
        var connection=mock(Connection.class); var amqp=mock(Channel.class); when(connection.createChannel()).thenReturn(amqp);
        try(var factories=mockConstruction(ConnectionFactory.class,(mock,context)->when(mock.newConnection()).thenReturn(connection))) {
            var channel=new MqPushChannel();
            try(var session=channel.openSession(rabbit())) {
                channel.push(new PushPayload(),rabbit(),null);
                verify(amqp).basicPublish(eq("fixture"),eq("key"),eq(true),
                        argThat(p->p.getDeliveryMode()==2 && "application/json".equals(p.getContentType())),any(byte[].class));
                verify(amqp).waitForConfirmsOrDie(anyLong());
                verify(factories.constructed().getFirst()).setAutomaticRecoveryEnabled(false);
            }
        }
    }
    @Test void rabbitConfirmFailureClosesSessionAndNeverResends() throws Exception {
        var connection=mock(Connection.class); var amqp=mock(Channel.class); when(connection.createChannel()).thenReturn(amqp);
        doThrow(new TimeoutException("fixture")).when(amqp).waitForConfirmsOrDie(anyLong());
        try(var factories=mockConstruction(ConnectionFactory.class,(mock,context)->when(mock.newConnection()).thenReturn(connection))) {
            var channel=new MqPushChannel();
            try(var session=channel.openSession(rabbit())) {
                assertThrows(RuntimeException.class,()->channel.push(new PushPayload(),rabbit(),null));
                assertThrows(RuntimeException.class,()->channel.push(new PushPayload(),rabbit(),null));
                verify(amqp,times(1)).waitForConfirmsOrDie(anyLong());
                verify(connection).abort(anyInt());
            }
        }
    }
}
