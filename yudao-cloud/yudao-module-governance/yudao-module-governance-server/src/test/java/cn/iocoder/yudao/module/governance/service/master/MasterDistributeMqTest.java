package cn.iocoder.yudao.module.governance.service.master;

import com.rabbitmq.client.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeConfigVO;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterDistributeMqTest {
    @ParameterizedTest @ValueSource(strings = {"", "bad host:9092", "localhost", "localhost:0", "localhost:65536", "localhost:9092,", "user:secret@localhost:9092", "localhost:9092/path", "localhost:9092?q=x"})
    void invalidKafkaAddressIsRejectedBeforeOpeningProducer(String address) {
        assertThrows(IllegalArgumentException.class, () -> MasterDistributeExecutor.validateMq(MasterMqFixture.kafka(address, "fixture")));
    }

    @ParameterizedTest @ValueSource(strings = {"", ".", "..", "bad topic", "bad/topic"})
    void invalidTopicIsRejected(String topic) {
        assertThrows(IllegalArgumentException.class, () -> MasterDistributeExecutor.validateMq(MasterMqFixture.kafka("localhost:9092", topic)));
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 10001})
    void invalidBatchSizeIsRejectedOnSaveAndExecute(int size) throws Exception {
        var config = MasterMqFixture.kafka("localhost:9092", "fixture"); config.setFetchSize(size);
        assertThrows(IllegalArgumentException.class, () -> MasterDistributeExecutor.validateMq(config));
        try (var fixture = new MasterMqFixture(config)) { fixture.execute(); fixture.outcome(false, 4); verify(fixture.coordinator, never()).begin(any()); }
        var service = new MasterDistributeServiceImpl();
        var models = mock(cn.iocoder.yudao.module.governance.dal.mysql.master.MasterModelMapper.class);
        var tasks = mock(cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeMapper.class);
        var model = new cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO(); model.setStatus(1);
        when(models.selectById(20L)).thenReturn(model);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "modelMapper", models);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "distributeMapper", tasks);
        var request = new cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterDistributeSaveReqVO();
        request.setMasterModelId(20L); request.setDistributeType(3);
        var vo = new MasterDistributeConfigVO(); vo.setMq(config); request.setConfig(vo);
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class, () -> service.createDistribute(request)); verifyNoInteractions(tasks);
    }

    @Test void validDefaultsAndIpv6RemainCompatible() {
        var config = MasterMqFixture.kafka("localhost:9092, [::1]:9093", "fixture"); config.setFetchSize(null);
        assertDoesNotThrow(() -> MasterDistributeExecutor.validateMq(config));
        config.setFetchSize(10000); assertDoesNotThrow(() -> MasterDistributeExecutor.validateMq(config));
        assertDoesNotThrow(() -> MasterDistributeExecutor.validateMq(MasterMqFixture.rabbit(5672, "交换机", "key")));
        assertThrows(IllegalArgumentException.class, () -> MasterDistributeExecutor.validateMq(MasterMqFixture.rabbit(5672, "中".repeat(86), "key")));
        assertThrows(IllegalArgumentException.class, () -> MasterDistributeExecutor.validateMq(MasterMqFixture.rabbit(65536, "fixture", "key")));
    }

    @Test void kafkaMustUseIdempotentAllAcknowledgement() throws Exception {
        var properties = new AtomicReference<Properties>();
        try (var producers = mockConstruction(KafkaProducer.class, (producer, context) -> {
            properties.set((Properties) context.arguments().getFirst());
            when(producer.send(any())).thenReturn(CompletableFuture.completedFuture(
                    new RecordMetadata(new TopicPartition("fixture", 0), 0, 0, 0, 0, 1)));
        }); var fixture = new MasterMqFixture(MasterMqFixture.kafka("127.0.0.1:19092", "fixture"))) {
            fixture.execute(); fixture.outcome(true, 3);
            assertEquals("all", properties.get().getProperty(ProducerConfig.ACKS_CONFIG));
            assertEquals("true", properties.get().getProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
            assertEquals("1", properties.get().getProperty(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION));
        }
    }

    @Test void noOffsetMustBeUnknownAndProducerMustNotDrainOrSendNextBatch() throws Exception {
        try (var producers = mockConstruction(KafkaProducer.class, (producer, context) ->
                when(producer.send(any())).thenReturn(CompletableFuture.completedFuture(
                        new RecordMetadata(new TopicPartition("fixture", 0), -1, -1, 0, 0, 1))));
             var fixture = new MasterMqFixture(MasterMqFixture.kafka("127.0.0.1:19092", "fixture"))) {
            fixture.execute(); fixture.outcome(true, 5);
            verify(producers.constructed().getFirst(), times(1)).send(any());
            verify(producers.constructed().getFirst()).close(Duration.ZERO);
            assertEquals(0L, fixture.run.getDataCount());
        }
    }

    @Test void invalidMqMustFailBeforeSendingState() throws Exception {
        try (var fixture = new MasterMqFixture(MasterMqFixture.rabbit(0, "fixture", "key"))) {
            fixture.execute(); verify(fixture.coordinator, never()).begin(any()); fixture.outcome(false, 4);
        }
    }

    @Test void rabbitUnroutableConfirmMustNotCountAsSuccess() throws Exception {
        var connection = mock(Connection.class); var channel = mock(Channel.class);
        var returned = new AtomicReference<ReturnListener>(); when(connection.createChannel()).thenReturn(channel);
        doAnswer(call -> { returned.set(call.getArgument(0)); return null; }).when(channel).addReturnListener(any(ReturnListener.class));
        doAnswer(call -> { if (returned.get() != null) returned.get().handleReturn(312, "fixture", "fixture", "key", null, new byte[0]); return null; })
                .when(channel).waitForConfirmsOrDie(anyLong());
        try (var factories = mockConstruction(ConnectionFactory.class, (factory, context) -> when(factory.newConnection()).thenReturn(connection));
             var fixture = new MasterMqFixture(MasterMqFixture.rabbit(5672, "fixture", "key"))) {
            fixture.execute(); fixture.outcome(true, 5); assertEquals(0L, fixture.run.getDataCount());
            verify(connection).abort(1000);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"timeout", "interrupt", "sdk-interrupt", "wrong-topic", "null", "broker-error"})
    void kafkaUncertainSecondBatchStopsAndRetainsConfirmedPrefix(String failure) throws Exception {
        try (var producers = mockConstruction(KafkaProducer.class, (producer, context) -> {
            Future<RecordMetadata> first = CompletableFuture.completedFuture(new RecordMetadata(new TopicPartition("fixture", 0), 0, 0, 0, 0, 1));
            Future<RecordMetadata> second = mock(Future.class);
            switch (failure) {
                case "timeout" -> when(second.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("fixture-secret"));
                case "interrupt" -> when(second.get(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException("fixture-secret"));
                case "sdk-interrupt" -> when(second.get(anyLong(), any(TimeUnit.class))).thenAnswer(call -> { throw new org.apache.kafka.common.errors.InterruptException("fixture-secret"); });
                case "wrong-topic" -> when(second.get(anyLong(), any(TimeUnit.class))).thenReturn(new RecordMetadata(new TopicPartition("other", 0), 0, 0, 0, 0, 1));
                case "broker-error" -> when(second.get(anyLong(), any(TimeUnit.class))).thenThrow(new java.util.concurrent.ExecutionException(new IllegalStateException("fixture-secret")));
                default -> { }
            }
            when(producer.send(any())).thenReturn(first, second);
        }); var fixture = new MasterMqFixture(MasterMqFixture.kafka("localhost:9092", "fixture"))) {
            try {
                fixture.execute(); fixture.outcome(true, 5); assertEquals(1L, fixture.run.getDataCount());
                assertEquals(failure.contains("interrupt"), Thread.currentThread().isInterrupted());
                var producer = producers.constructed().getFirst(); verify(producer, times(2)).send(any());
                verify(producer).close(Duration.ZERO); verify(producer, never()).close(); verify(producer, never()).flush();
                verify(fixture.source).close();
            } finally { Thread.interrupted(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"null-channel", "confirm-init", "timeout", "nack", "interrupt", "success"})
    void rabbitClosesOwnedConnectionOnEveryExit(String mode) throws Exception {
        var connection = mock(Connection.class); var channel = mock(Channel.class);
        when(connection.createChannel()).thenReturn(mode.equals("null-channel") ? null : channel);
        if (mode.equals("confirm-init")) when(channel.confirmSelect()).thenThrow(new java.io.IOException("fixture-secret"));
        if (mode.equals("timeout")) doThrow(new TimeoutException("fixture-secret")).when(channel).waitForConfirmsOrDie(anyLong());
        if (mode.equals("nack")) doThrow(new java.io.IOException("fixture-secret")).when(channel).waitForConfirmsOrDie(anyLong());
        if (mode.equals("interrupt")) doThrow(new InterruptedException("fixture-secret")).when(channel).waitForConfirmsOrDie(anyLong());
        try (var factories = mockConstruction(ConnectionFactory.class, (factory, context) -> when(factory.newConnection()).thenReturn(connection));
             var fixture = new MasterMqFixture(MasterMqFixture.rabbit(5672, "fixture", "key"))) {
            try {
                fixture.execute(); fixture.outcome(true, mode.equals("success") ? 3 : 5);
                assertEquals(mode.equals("interrupt"), Thread.currentThread().isInterrupted());
                verify(connection).abort(1000); verify(connection, never()).close();
                var factory = factories.constructed().getFirst(); verify(factory).setAutomaticRecoveryEnabled(false);
                verify(factory).setTopologyRecoveryEnabled(false); verify(factory).setChannelRpcTimeout(30000);
                int sent = mode.equals("success") ? 2 : mode.equals("null-channel") || mode.equals("confirm-init") ? 0 : 1;
                verify(channel, times(sent)).basicPublish(eq("fixture"), eq("key"), eq(true), argThat(p -> p.getDeliveryMode() == 2 && "application/json".equals(p.getContentType())), any(byte[].class));
            } finally { Thread.interrupted(); }
        }
    }
}
