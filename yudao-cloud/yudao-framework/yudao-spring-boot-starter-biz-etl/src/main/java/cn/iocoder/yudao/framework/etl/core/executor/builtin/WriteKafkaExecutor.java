package cn.iocoder.yudao.framework.etl.core.executor.builtin;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.support.KafkaConfigUtils;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlRunContext;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * 写入 Kafka 节点（toolCode: write-kafka）
 *
 * 配置：bootstrapServers / topic / keyStrategy none|field|uuid / keyField /
 * acks 0|1|all / compressionType / SASL 安全配置。
 * 每行数据序列化为一条 JSON 消息，逐批 flush 确认
 *
 * @author Li
 */
public class WriteKafkaExecutor extends AbstractEtlNodeExecutor {

    public static final String TOOL_CODE = "write-kafka";

    private static final int FLUSH_BATCH = 500;

    /**
     * 初始化 {@code WriteKafkaExecutor}及其父类状态。
     */
    public WriteKafkaExecutor(EtlNodeDef node, EtlRunContext context) {
        super(node, context);
    }

    /**
     * 节点业务逻辑（子类实现）。
     */
    @Override
    protected void execute() throws Exception {
        String bootstrapServers = requireConfig("bootstrapServers", "Kafka 服务地址");
        String topic = requireConfig("topic", "Topic");
        String keyStrategy = config().getStr("keyStrategy", "none");
        String keyField = config().getStr("keyField");
        if ("field".equals(keyStrategy) && StrUtil.isBlank(keyField)) {
            throw new IllegalStateException(
                    String.format("节点【%s】选择字段作为消息键时必须配置键字段", node.getName()));
        }

        Properties props = KafkaConfigUtils.buildSecurityProps(config());
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, config().getStr("acks", "1"));
        String compression = config().getStr("compressionType", "none");
        if (!"none".equalsIgnoreCase(compression)) {
            props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, compression.toLowerCase());
        }
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "30000");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "60000");

        logLine(String.format("开始写入 Topic【%s】", topic));
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            long sinceFlush = 0;
            Map<String, Object> row;
            org.apache.kafka.clients.producer.RecordMetadata lastMeta = null;
            java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> lastFuture = null;
            while ((row = readNextRow()) != null) {
                String key = switch (keyStrategy) {
                    case "field" -> row.get(keyField) == null ? null : String.valueOf(row.get(keyField));
                    case "uuid" -> IdUtil.fastSimpleUUID();
                    default -> null;
                };
                lastFuture = producer.send(new ProducerRecord<>(topic, key, JSONUtil.toJsonStr(row)));
                writeCount.incrementAndGet();
                if (++sinceFlush >= FLUSH_BATCH) {
                    producer.flush();
                    // 校验最近一次发送结果，及时暴露 broker 异常
                    lastMeta = lastFuture.get(30, TimeUnit.SECONDS);
                    sinceFlush = 0;
                }
            }
            producer.flush();
            if (lastFuture != null) {
                lastMeta = lastFuture.get(30, TimeUnit.SECONDS);
            }
            if (lastMeta != null) {
                logLine(String.format("最后写入位点：partition=%s offset=%s",
                        lastMeta.partition(), lastMeta.offset()));
            }
        }
        logLine(String.format("Kafka 写入完成，共 %s 条", writeCount.get()));
    }

}
