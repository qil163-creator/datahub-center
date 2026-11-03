package cn.iocoder.yudao.module.dataforge.service.push.channel;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;
import cn.iocoder.yudao.module.dataforge.enums.push.PushChannelTypeEnum;
import cn.iocoder.yudao.module.dataforge.service.push.DataPushChannel;
import cn.iocoder.yudao.module.dataforge.service.push.DataPushSession;
import cn.iocoder.yudao.module.dataforge.service.push.DataPushSslContexts;
import cn.iocoder.yudao.module.dataforge.service.push.PushPayload;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.serialization.StringSerializer;

import javax.net.ssl.SSLContext;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kafka 同步确认 / Rabbit Publisher Confirm。
 * 一次执行共用 Producer / Connection，停止时关闭连接以打断阻塞发送。
 *
 * @author Li
 */
@Component
public class MqPushChannel implements DataPushChannel {

    public static final String MQ_TYPE_KAFKA = "kafka";

    public static final String MQ_TYPE_RABBIT = "rabbitmq";

    private static final int TIMEOUT_MS = 30_000;

    private static final ThreadLocal<MqSession> SESSION = new ThreadLocal<>();

    /**
     * 返回当前实现对应的类型标识。
     *
     * @return 类型
     */
    @Override
    public Integer getType() {
        return PushChannelTypeEnum.MQ.getType();
    }

    /**
     * 打开会话。
     *
     * @param channelJson 通道JSON
     * @return 数据推送会话
     */
    @Override
    public DataPushSession openSession(String channelJson) {
        validateConfiguration(channelJson);
        if (SESSION.get() != null) {
            throw new IllegalStateException("当前推送会话尚未关闭");
        }
        DataPushChannelConfigVO config = JsonUtils.parseObject(channelJson, DataPushChannelConfigVO.class);
        DataPushChannelConfigVO.MqConfig mq = config == null ? null : config.getMq();
        if (mq == null || StrUtil.isBlank(mq.getMqType())) {
            throw new IllegalStateException("未配置消息队列");
        }
        MqSession session;
        if (MQ_TYPE_KAFKA.equals(mq.getMqType())) {
            session = MqSession.kafka(mq.getKafka());
        } else if (MQ_TYPE_RABBIT.equals(mq.getMqType())) {
            session = MqSession.rabbit(mq.getRabbit());
        } else {
            throw new IllegalStateException("队列类型仅支持 kafka / rabbitmq");
        }
        SESSION.set(session);
        return session;
    }

    /**
     * 校验配置。
     *
     * @param channelJson 通道JSON
     */
    @Override
    public void validateConfiguration(String channelJson) {
        DataPushChannelConfigVO config = JsonUtils.parseObject(channelJson, DataPushChannelConfigVO.class);
        DataPushChannelConfigVO.MqConfig mq = config == null ? null : config.getMq();
        if (mq == null) {
            throw new IllegalStateException("未配置消息队列");
        }
        if (MQ_TYPE_KAFKA.equals(mq.getMqType())) {
            var kafka = mq.getKafka();
            if (kafka == null || StrUtil.isBlank(kafka.getBootstrapServers()) || StrUtil.isBlank(kafka.getTopic())) {
                throw new IllegalStateException("Kafka 配置不完整");
            }
            normalizeKafkaAcks(kafka.getAcks());
        } else if (MQ_TYPE_RABBIT.equals(mq.getMqType())) {
            var rabbit = mq.getRabbit();
            if (rabbit == null || StrUtil.isBlank(rabbit.getHost()) || rabbit.getPort() == null
                    || rabbit.getPort() < 1 || rabbit.getPort() > 65535) {
                throw new IllegalStateException("RabbitMQ 配置不完整");
            }
        } else {
            throw new IllegalStateException("队列类型仅支持 kafka / rabbitmq");
        }
    }

    /** 保存配置与执行旧配置共用；禁止无回执发送或静默降级。 */
    public static String normalizeKafkaAcks(String value) {
        String acks = StrUtil.blankToDefault(value, "all").trim();
        if ("all".equals(acks) || "-1".equals(acks)) {
            return "all";
        }
        if ("1".equals(acks)) {
            return "1";
        }
        throw new IllegalArgumentException("Kafka acks 仅支持 all / 1；0 无投递回执，禁止使用");
    }

    /**
     * 推送MQ推送通道。
     *
     * @param payload 载荷
     * @param channelJson 通道JSON
     * @param log 日志
     */
    @Override
    public void push(PushPayload payload, String channelJson, StringBuilder log) {
        DataPushChannelConfigVO config = JsonUtils.parseObject(channelJson, DataPushChannelConfigVO.class);
        DataPushChannelConfigVO.MqConfig mq = config == null ? null : config.getMq();
        if (mq == null || StrUtil.isBlank(mq.getMqType())) {
            throw new IllegalStateException("未配置消息队列");
        }
        String message = JsonUtils.toJsonString(payload);
        MqSession session = SESSION.get();
        boolean closeAfter = false;
        if (session == null) {
            session = (MqSession) openSession(channelJson);
            closeAfter = true;
        }
        try {
            session.send(message);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("任务已被手动停止", ex);
        } finally {
            if (closeAfter) {
                session.close();
            }
        }
        if (log == null) {
            return;
        }
        if (MQ_TYPE_KAFKA.equals(mq.getMqType())) {
            log.append("Kafka topic=").append(mq.getKafka().getTopic())
                    .append(" rows=").append(size(payload)).append('\n');
        } else {
            log.append("RabbitMQ exchange=").append(mq.getRabbit().getExchange())
                    .append(" rows=").append(size(payload)).append('\n');
        }
    }

    /**
     * 获取MQ推送通道的大小。
     *
     * @param payload 载荷
     * @return 大小数值
     */
    private static int size(PushPayload payload) {
        return payload.getDataList() == null ? 0 : payload.getDataList().size();
    }

    /**
     * 应用KafkaSSL。
     *
     * @param props 配置
     * @param kafka Kafka
     */
    public static void applyKafkaSsl(Properties props, DataPushChannelConfigVO.KafkaConfig kafka) {
        if (kafka == null || props == null) {
            return;
        }
        if (StrUtil.isNotBlank(kafka.getTruststorePath())) {
            props.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG,
                    DataPushSslContexts.requireReadableFile(kafka.getTruststorePath()).toString());
            if (StrUtil.isNotBlank(kafka.getTruststorePassword())) {
                props.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, kafka.getTruststorePassword());
            }
        }
        if (StrUtil.isNotBlank(kafka.getKeystorePath())) {
            props.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG,
                    DataPushSslContexts.requireReadableFile(kafka.getKeystorePath()).toString());
            if (StrUtil.isNotBlank(kafka.getKeystorePassword())) {
                props.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, kafka.getKeystorePassword());
            }
        }
        if (Boolean.TRUE.equals(kafka.getDisableHostnameVerification())) {
            props.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "");
        }
    }

    private static final class MqSession implements DataPushSession {

        private final KafkaProducer<String, String> producer;

        private final String topic;

        private final Connection connection;

        private final Channel channel;

        private final String exchange;

        private final String routingKey;

        private final AtomicBoolean closed = new AtomicBoolean();

        private final AtomicBoolean returned = new AtomicBoolean();

        /**
         * 创建MQ会话实例。
         *
         * @param producer 生产者
         * @param topic 主题
         * @param connection 数据库连接
         * @param channel 通道
         * @param exchange 交换机
         * @param routingKey 路由键
         */
        private MqSession(KafkaProducer<String, String> producer, String topic,
                Connection connection, Channel channel, String exchange, String routingKey) {
            this.producer = producer;
            this.topic = topic;
            this.connection = connection;
            this.channel = channel;
            this.exchange = exchange;
            this.routingKey = routingKey;
            if (channel != null) {
                // Rabbit 客户端按帧顺序同步分发 return，再处理 confirm；不保存消息体以免泄露业务数据。
                channel.addReturnListener((replyCode, replyText, returnedExchange, returnedKey, properties, body)
                        -> returned.set(true));
            }
        }

        /**
         * 获取MQ会话的Kafka。
         *
         * @param kafka Kafka
         * @return MQ会话
         */
        private static MqSession kafka(DataPushChannelConfigVO.KafkaConfig kafka) {
            if (kafka == null || StrUtil.isBlank(kafka.getBootstrapServers()) || StrUtil.isBlank(kafka.getTopic())) {
                throw new IllegalStateException("Kafka 配置不完整");
            }
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            String acks = normalizeKafkaAcks(kafka.getAcks());
            props.put(ProducerConfig.ACKS_CONFIG, acks);
            boolean idempotent = "all".equals(acks);
            props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, String.valueOf(idempotent));
            props.put(ProducerConfig.RETRIES_CONFIG, idempotent ? String.valueOf(Integer.MAX_VALUE) : "0");
            props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1");
            props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, String.valueOf(TIMEOUT_MS));
            props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, String.valueOf(2 * TIMEOUT_MS));
            props.put(ProducerConfig.LINGER_MS_CONFIG, "0");
            applyKafkaSecurity(props, kafka);
            return new MqSession(new KafkaProducer<>(props), kafka.getTopic(),
                    null, null, null, null);
        }

        /**
         * 应用Kafka安全。
         *
         * @param props 配置
         * @param kafka Kafka
         */
        private static void applyKafkaSecurity(Properties props, DataPushChannelConfigVO.KafkaConfig kafka) {
            String protocol = StrUtil.blankToDefault(kafka.getSecurityProtocol(), "PLAINTEXT")
                    .trim().toUpperCase();
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, protocol);
            MqPushChannel.applyKafkaSsl(props, kafka);
            if (!protocol.contains("SASL")) {
                return;
            }
            String mechanism = StrUtil.blankToDefault(kafka.getSaslMechanism(), "PLAIN").trim();
            props.put(SaslConfigs.SASL_MECHANISM, mechanism);
            if (StrUtil.isBlank(kafka.getSaslUsername()) || kafka.getSaslPassword() == null) {
                throw new IllegalStateException("Kafka SASL 需要用户名和密码");
            }
            String loginModule = mechanism.toUpperCase().startsWith("SCRAM")
                    ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                    : "org.apache.kafka.common.security.plain.PlainLoginModule";
            props.put(SaslConfigs.SASL_JAAS_CONFIG, loginModule + " required username=\""
                    + escapeJaas(kafka.getSaslUsername()) + "\" password=\""
                    + escapeJaas(kafka.getSaslPassword()) + "\";");
        }

        /**
         * 获取MQ会话的转义JAAS。
         *
         * @param value 待处理的值
         * @return MQ会话的转义JAAS文本
         */
        private static String escapeJaas(String value) {
            return StrUtil.nullToEmpty(value).replace("\\", "\\\\").replace("\"", "\\\"");
        }

        /**
         * 获取MQ会话的Rabbit。
         *
         * @param rabbit Rabbit
         * @return MQ会话
         */
        private static MqSession rabbit(DataPushChannelConfigVO.RabbitConfig rabbit) {
            if (rabbit == null || StrUtil.isBlank(rabbit.getHost()) || rabbit.getPort() == null) {
                throw new IllegalStateException("RabbitMQ 配置不完整");
            }
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(rabbit.getHost());
            factory.setPort(rabbit.getPort());
            if (StrUtil.isNotBlank(rabbit.getUsername())) {
                factory.setUsername(rabbit.getUsername());
            }
            if (rabbit.getPassword() != null) {
                factory.setPassword(rabbit.getPassword());
            }
            factory.setVirtualHost(StrUtil.blankToDefault(rabbit.getVirtualHost(), "/"));
            factory.setConnectionTimeout(TIMEOUT_MS);
            factory.setHandshakeTimeout(TIMEOUT_MS);
            factory.setChannelRpcTimeout(TIMEOUT_MS);
            factory.setAutomaticRecoveryEnabled(false);
            factory.setTopologyRecoveryEnabled(false);
            SSLContext sslContext = DataPushSslContexts.create(
                    rabbit.getTruststorePath(), rabbit.getTruststorePassword(),
                    rabbit.getKeystorePath(), rabbit.getKeystorePassword());
            boolean useSsl = Boolean.TRUE.equals(rabbit.getUseSsl()) || sslContext != null;
            if (useSsl) {
                try {
                    if (sslContext != null) {
                        factory.useSslProtocol(sslContext);
                    } else {
                        factory.useSslProtocol();
                    }
                } catch (Exception ex) {
                    throw new IllegalStateException("RabbitMQ TLS 初始化失败", ex);
                }
            }
            Connection connection = null;
            try {
                connection = factory.newConnection();
                Channel channel = connection.createChannel();
                if (channel == null) {
                    throw new IllegalStateException("RabbitMQ 通道不可用");
                }
                channel.confirmSelect();
                return new MqSession(null, null, connection, channel,
                        StrUtil.nullToEmpty(rabbit.getExchange()),
                        StrUtil.nullToEmpty(rabbit.getRoutingKey()));
            } catch (Exception ex) {
                if (connection != null) {
                    try {
                        connection.abort(1_000);
                    } catch (Exception ignored) {
                    }
                }
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("RabbitMQ 连接或确认模式初始化失败", ex);
            }
        }

        /**
         * 发送MQ会话消息。
         *
         * @param message 消息
         */
        private void send(String message) throws InterruptedException {
            if (closed.get()) {
                throw new IllegalStateException("MQ 会话已终止，禁止继续发送");
            }
            try {
                if (producer != null) {
                    RecordMetadata receipt = producer.send(new ProducerRecord<>(topic, message))
                            .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (receipt == null || !receipt.hasOffset() || !topic.equals(receipt.topic())) {
                        throw new IllegalStateException("Kafka 未返回有效投递回执");
                    }
                } else {
                    AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                            .deliveryMode(2).contentType("application/json").contentEncoding("UTF-8").build();
                    channel.basicPublish(exchange, routingKey, true, properties,
                            message.getBytes(StandardCharsets.UTF_8));
                    channel.waitForConfirmsOrDie(TIMEOUT_MS);
                    if (returned.get()) {
                        throw new IllegalStateException("RabbitMQ 消息未路由到队列");
                    }
                }
                if (closed.get()) {
                    throw new IllegalStateException("确认期间 MQ 会话已终止，结果需核查");
                }
            } catch (InterruptedException ex) {
                abort();
                throw ex;
            } catch (Exception ex) {
                abort();
                if (ex.getCause() instanceof InterruptedException interrupted) {
                    throw interrupted;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("任务已被手动停止");
                }
                throw new IllegalStateException(
                        "MQ 未获得完整投递确认，禁止继续发送或自动重跑", ex);
            }
        }

        /**
         * 中止MQ会话。
         */
        @Override
        public void abort() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            abortResources();
        }

        /**
         * 中止资源。
         */
        private void abortResources() {
            if (producer != null) {
                try {
                    producer.close(Duration.ZERO);
                } catch (Exception ignored) {
                }
            }
            if (connection != null) {
                try {
                    connection.abort(1_000);
                } catch (Exception ignored) {
                }
            }
        }

        /**
         * 关闭当前实例持有的连接、会话或执行资源。
         */
        @Override
        public void close() {
            try {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                if (producer != null) {
                    producer.close(Duration.ofSeconds(5));
                }
                if (connection != null) {
                    connection.close(5_000);
                }
            } catch (Exception ex) {
                abortResources();
                throw new IllegalStateException("MQ 会话关闭失败，结果需核查", ex);
            } finally {
                if (SESSION.get() == this) {
                    SESSION.remove();
                }
            }
        }
    }

}
