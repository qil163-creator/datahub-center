package cn.iocoder.yudao.module.dataforge.service.push.channel;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;
import cn.iocoder.yudao.module.dataforge.service.push.PushPayload;
import com.rabbitmq.client.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated local broker only; no application database or pre-existing queue is touched. */
@EnabledIfSystemProperty(named = "dataforge.test.rabbit.port", matches = "[0-9]+")
@Timeout(45)
class MqPushRabbitIntegrationTest {
    private Connection connection;
    private Channel control;
    private String exchange;
    private String queue;
    private int port;

    @BeforeEach void prepare() throws Exception {
        port = Integer.parseInt(System.getProperty("dataforge.test.rabbit.port"));
        var factory = new ConnectionFactory();
        factory.setHost("127.0.0.1");
        factory.setPort(port);
        factory.setAutomaticRecoveryEnabled(false);
        connection = factory.newConnection();
        control = connection.createChannel();
        exchange = "codex.q12." + UUID.randomUUID();
        queue = exchange + ".queue";
        control.exchangeDeclare(exchange, "direct", true);
        control.queueDeclare(queue, true, false, false, null);
        control.queueBind(queue, exchange, "key");
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (control != null && control.isOpen()) {
                control.queueDelete(queue);
                control.exchangeDelete(exchange);
            }
        } finally {
            if (connection != null) connection.abort(1_000);
        }
    }

    private String config(int targetPort) {
        var config = JsonUtils.parseObject(MqPushDeliveryTest.rabbit(), DataPushChannelConfigVO.class);
        config.getMq().getRabbit().setPort(targetPort);
        config.getMq().getRabbit().setExchange(exchange);
        return JsonUtils.toJsonString(config);
    }

    private PushPayload payload() {
        return new PushPayload("fixture", List.of(Map.of("id", "9007199254740993")));
    }

    @Test void routedPublishHasPersistentJsonReceipt() throws Exception {
        var channel = new MqPushChannel();
        String config = config(port);
        try (var session = channel.openSession(config)) {
            channel.push(payload(), config, null);
        }
        var received = control.basicGet(queue, true);
        assertNotNull(received);
        assertEquals(2, received.getProps().getDeliveryMode());
        assertEquals("application/json", received.getProps().getContentType());
        assertEquals(JsonUtils.toJsonString(payload()), new String(received.getBody(), StandardCharsets.UTF_8));
        assertNull(control.basicGet(queue, true));
    }

    @Test void unroutableAckMustFailAndSessionCannotResumeAfterRebinding() throws Exception {
        var channel = new MqPushChannel();
        String config = config(port);
        try (var session = channel.openSession(config)) {
            channel.push(payload(), config, null);
            control.queueUnbind(queue, exchange, "key");
            assertThrows(IllegalStateException.class, () -> channel.push(payload(), config, null));
            control.queueBind(queue, exchange, "key");
            assertThrows(IllegalStateException.class, () -> channel.push(payload(), config, null));
        }
        assertNotNull(control.basicGet(queue, true));
        assertNull(control.basicGet(queue, true), "only the initial confirmed batch may exist");
    }

    @Test void missingExchangeClosesProtocolChannelWithoutRetry() throws Exception {
        control.exchangeDelete(exchange);
        var channel = new MqPushChannel();
        String config = config(port);
        try (var session = channel.openSession(config)) {
            assertThrows(IllegalStateException.class, () -> channel.push(payload(), config, null));
            assertThrows(IllegalStateException.class, () -> channel.push(payload(), config, null));
        }
        assertNull(control.basicGet(queue, true));
        // Restore this test's exchange so the idempotent teardown does not close its control channel.
        control.exchangeDeclare(exchange, "direct", true);
    }

    @Test void crossThreadAbortPreventsAnyPublish() throws Exception {
        var channel = new MqPushChannel();
        String config = config(port);
        try (var session = channel.openSession(config)) {
            Thread aborter = Thread.ofVirtual().start(session::abort);
            aborter.join(5_000);
            assertFalse(aborter.isAlive());
            assertThrows(IllegalStateException.class, () -> channel.push(payload(), config, null));
        }
        assertNull(control.basicGet(queue, true));
    }

    @Test void lostConfirmAfterBrokerAcceptedDoesNotResendOrReportSuccess() throws Exception {
        try (var proxy = new ConfirmDropProxy(port)) {
            var channel = new MqPushChannel();
            String config = config(proxy.port());
            try (var session = channel.openSession(config)) {
                assertThrows(IllegalStateException.class, () -> channel.push(payload(), config, null));
                assertTrue(proxy.dropped.await(5, TimeUnit.SECONDS), "must drop an actual broker publisher ACK");
                assertThrows(IllegalStateException.class, () -> channel.push(payload(), config, null));
            }
        }
        assertNotNull(control.basicGet(queue, true), "broker accepted despite missing client receipt");
        assertNull(control.basicGet(queue, true), "no hidden replay after uncertain delivery");
    }

    /** Test-only TCP fault injector: suppress the first AMQP Basic.Ack and sever the client socket. */
    private static final class ConfirmDropProxy implements AutoCloseable {
        private final ServerSocket listener;
        private volatile Socket client;
        private volatile Socket broker;
        private final CountDownLatch dropped = new CountDownLatch(1);
        private final Thread worker;

        ConfirmDropProxy(int brokerPort) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            worker = Thread.ofVirtual().start(() -> {
                try {
                    client = listener.accept();
                    broker = new Socket("127.0.0.1", brokerPort);
                    Thread upstream = Thread.ofVirtual().start(() -> {
                        try { client.getInputStream().transferTo(broker.getOutputStream()); }
                        catch (IOException ignored) { }
                    });
                    try {
                        var input = new DataInputStream(broker.getInputStream());
                        var output = new DataOutputStream(client.getOutputStream());
                        while (true) {
                            int type = input.readUnsignedByte();
                            int channel = input.readUnsignedShort();
                            int size = input.readInt();
                            if (size < 0 || size > 1_048_576) throw new IOException("unexpected frame size");
                            byte[] body = input.readNBytes(size);
                            int end = input.readUnsignedByte();
                            if (type == 1 && body.length >= 4 && body[0] == 0 && body[1] == 60
                                    && body[2] == 0 && body[3] == 80) {
                                dropped.countDown();
                                client.close();
                                break;
                            }
                            output.writeByte(type); output.writeShort(channel); output.writeInt(size);
                            output.write(body); output.writeByte(end); output.flush();
                        }
                    } finally {
                        client.close(); broker.close(); upstream.join(2_000);
                    }
                } catch (IOException ignored) {
                    // A failed injector cannot pass: the test requires the dropped-confirm latch and queue evidence.
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        int port() { return listener.getLocalPort(); }

        @Override public void close() throws Exception {
            listener.close();
            if (client != null) client.close();
            if (broker != null) broker.close();
            worker.join(5_000);
            assertFalse(worker.isAlive(), "fault injector must release its sockets and thread");
        }
    }
}
