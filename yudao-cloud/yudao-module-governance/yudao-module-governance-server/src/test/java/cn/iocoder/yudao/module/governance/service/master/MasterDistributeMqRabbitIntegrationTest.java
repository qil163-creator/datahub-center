package cn.iocoder.yudao.module.governance.service.master;

import com.rabbitmq.client.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Real RabbitMQ + real executor; the source and coordinator are deterministic fixtures. */
@EnabledIfSystemProperty(named = "governance.test.rabbit.port", matches = "[0-9]+")
@Timeout(45)
class MasterDistributeMqRabbitIntegrationTest {
    private Connection connection;
    private Channel control;
    private String exchange;
    private String queue;
    private int port;

    @BeforeEach void prepare() throws Exception {
        port = Integer.parseInt(System.getProperty("governance.test.rabbit.port"));
        var factory = new ConnectionFactory(); factory.setHost("127.0.0.1"); factory.setPort(port);
        factory.setAutomaticRecoveryEnabled(false);
        connection = factory.newConnection(); control = connection.createChannel();
        exchange = "codex.master." + UUID.randomUUID(); queue = exchange + ".queue";
        control.exchangeDeclare(exchange, "direct", true);
        control.queueDeclare(queue, true, false, false, null); control.queueBind(queue, exchange, "key");
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (control != null && control.isOpen()) { control.queueDelete(queue); control.exchangeDelete(exchange); }
        } finally { if (connection != null) connection.abort(1000); }
    }

    @Test void confirmedBatchesHavePersistentJsonBodies() throws Exception {
        try (var fixture = new MasterMqFixture(MasterMqFixture.rabbit(port, exchange, "key"))) {
            fixture.execute(); fixture.outcome(true, 3); assertEquals(2L, fixture.run.getDataCount());
        }
        for (int id = 1; id <= 2; id++) {
            var message = control.basicGet(queue, true); assertNotNull(message);
            assertEquals(2, message.getProps().getDeliveryMode());
            assertEquals("application/json", message.getProps().getContentType());
            assertEquals("UTF-8", message.getProps().getContentEncoding());
            assertEquals("[{\"id\":" + id + "}]", new String(message.getBody(), StandardCharsets.UTF_8));
        }
        assertNull(control.basicGet(queue, true));
    }

    @Test void unroutableBrokerAckIsUnknownNotSuccess() throws Exception {
        try (var fixture = new MasterMqFixture(MasterMqFixture.rabbit(port, exchange, "unbound"))) {
            fixture.execute(); fixture.outcome(true, 5); assertEquals(0L, fixture.run.getDataCount());
        }
        assertNull(control.basicGet(queue, true));
    }

    @Test void missingExchangeDoesNotResumeOnNewChannel() throws Exception {
        try (var fixture = new MasterMqFixture(MasterMqFixture.rabbit(port, exchange + ".missing", "key"))) {
            fixture.execute(); fixture.outcome(true, 5); assertEquals(0L, fixture.run.getDataCount());
        }
        assertNull(control.basicGet(queue, true));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void lostAckKeepsOnlyConfirmedPrefixAndNeverResends(int dropAt) throws Exception {
        try (var proxy = new AckFaultProxy(port, dropAt, false);
             var fixture = new MasterMqFixture(MasterMqFixture.rabbit(proxy.port(), exchange, "key"))) {
            fixture.execute(); fixture.outcome(true, 5);
            assertTrue(proxy.dropped.await(3, TimeUnit.SECONDS));
            assertEquals(dropAt - 1L, fixture.run.getDataCount());
        }
        for (int i = 0; i < dropAt; i++) assertNotNull(control.basicGet(queue, true), "broker already accepted the unconfirmed batch");
        assertNull(control.basicGet(queue, true), "no retry or following batch after unknown");
    }

    @Test void interruptionDuringRealConfirmWaitStopsFollowingBatch() throws Exception {
        try (var proxy = new AckFaultProxy(port, 1, true)) {
            var done = new CompletableFuture<Void>(); var interrupted = new AtomicBoolean();
            Thread worker = new Thread(() -> {
                try (var fixture = new MasterMqFixture(MasterMqFixture.rabbit(proxy.port(), exchange, "key"))) {
                    fixture.execute(); fixture.outcome(true, 5); assertEquals(0L, fixture.run.getDataCount());
                    interrupted.set(Thread.currentThread().isInterrupted()); done.complete(null);
                } catch (Throwable failure) { done.completeExceptionally(failure); }
            }, "master-mq-rabbit-cancel-fixture");
            try {
                worker.start(); assertTrue(proxy.dropped.await(10, TimeUnit.SECONDS));
                worker.interrupt(); done.get(5, TimeUnit.SECONDS); assertTrue(interrupted.get());
            } finally {
                proxy.release.countDown(); worker.interrupt(); worker.join(5000); assertFalse(worker.isAlive());
            }
        }
        assertNotNull(control.basicGet(queue, true)); assertNull(control.basicGet(queue, true));
    }

    /** Suppresses an actual AMQP Basic.Ack; no synthetic successful receipt is injected. */
    private static final class AckFaultProxy implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        private volatile Socket client;
        private volatile Socket broker;
        private final CountDownLatch dropped = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread worker;

        AckFaultProxy(int brokerPort, int dropAt, boolean stall) throws IOException {
            worker = Thread.ofVirtual().start(() -> {
                try {
                    client = listener.accept(); broker = new Socket("127.0.0.1", brokerPort);
                    Thread upstream = Thread.ofVirtual().start(() -> {
                        try { client.getInputStream().transferTo(broker.getOutputStream()); } catch (IOException ignored) { }
                    });
                    try {
                        var input = new DataInputStream(broker.getInputStream());
                        var output = new DataOutputStream(client.getOutputStream()); int confirms = 0;
                        while (true) {
                            int type = input.readUnsignedByte(); int channel = input.readUnsignedShort(); int length = input.readInt();
                            if (length < 0 || length > 1_048_576) throw new IOException("invalid fixture frame");
                            byte[] body = input.readNBytes(length); int end = input.readUnsignedByte();
                            if (type == 1 && body.length >= 4 && body[0] == 0 && body[1] == 60 && body[2] == 0 && body[3] == 80
                                    && ++confirms == dropAt) {
                                dropped.countDown(); if (stall) release.await(10, TimeUnit.SECONDS); break;
                            }
                            output.writeByte(type); output.writeShort(channel); output.writeInt(length);
                            output.write(body); output.writeByte(end); output.flush();
                        }
                    } finally { client.close(); broker.close(); upstream.join(2000); }
                } catch (IOException ignored) {
                    // Tests require the actual ACK latch and independently inspect the broker queue.
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
        }

        int port() { return listener.getLocalPort(); }
        @Override public void close() throws Exception {
            release.countDown(); listener.close(); if (client != null) client.close(); if (broker != null) broker.close();
            worker.join(5000); assertFalse(worker.isAlive());
        }
    }
}
