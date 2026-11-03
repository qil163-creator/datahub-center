package cn.iocoder.yudao.framework.etl.flink.bounded;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONConfig;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlBoundedConnectorTransport;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlNativeRowGuard;
import cn.iocoder.yudao.framework.etl.flink.rabbitmq.ManagedRabbitConnection;
import cn.iocoder.yudao.framework.etl.flink.rocketmq.ManagedRocketResource;
import com.rabbitmq.client.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;

import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** 两种批引擎共享受管消息传输；固定结束位点，不提交读取位点，失败和取消不发布尚未完成的输出。 */
public final class ManagedFlinkMessageTransport implements EtlBoundedConnectorTransport {
    private static JSONObject object() { return new JSONObject(JSONConfig.create().setIgnoreNullValue(false).setStripTrailingZeros(false)); }
    @Override public Set<String> kinds() { return Set.of("kafka", "rabbitmq", "rocketmq", "tdengine"); }
    @Override public void read(String kind, JSONObject plan, Consumer<String> rows) throws Exception {
        Budget budget = new Budget(plan);
        Consumer<String> checked = json -> { budget.add(json); rows.accept(EtlNativeRowGuard.validate(json, plan)); };
        try {
            switch (kind) {
                case "kafka" -> kafkaRead(plan, checked, budget);
                case "rabbitmq" -> rabbitRead(plan, checked, budget);
                case "rocketmq" -> rocketRead(plan, checked, budget);
                case "tdengine" -> tdengineRead(plan, checked, budget);
                default -> throw new IllegalArgumentException("未知受管消息协议");
            }
        } catch (Exception failure) { throw new IllegalStateException("受管有界输入失败：" + kind); }
    }
    @Override public Writer writer(String kind, JSONObject plan) {
        if (!Set.of("kafka", "rabbitmq", "rocketmq").contains(kind)) throw new IllegalArgumentException("该受管协议不支持输出");
        return new BufferedWriter(kind, plan);
    }
    private static final class Budget {
        private final long deadline, maxRows, maxBytes; private long rows, bytes;
        Budget(JSONObject plan) {
            int millis = plan.getInt("timeoutMillis", 30_000);
            if (millis < 100 || millis > 60_000) throw new IllegalArgumentException("消息超时预算无效");
            maxRows = plan.getLong("maxRows", 1_000_000L); maxBytes = plan.getLong("maxBytes", 67_108_864L);
            if (maxRows < 1 || maxRows > 1_000_000 || maxBytes < 1 || maxBytes > 67_108_864) throw new IllegalArgumentException("消息行字节预算无效");
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        }
        void check() { if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new IllegalStateException("消息任务超时或取消"); }
        void add(String row) { check(); if (++rows > maxRows || (bytes += row.getBytes(StandardCharsets.UTF_8).length) > maxBytes) throw new IllegalStateException("消息超过有界行字节预算"); }
        long remainingMillis() { check(); return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())); }
    }
    private static String utf8(byte[] body) throws CharacterCodingException {
        if (body == null || body.length > 1_048_576) throw new IllegalArgumentException("消息为空或单条超过1MiB");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
    }
    private static String payload(JSONObject plan, byte[] bytes) throws Exception {
        String text = utf8(bytes); var result = object(); var types = plan.getJSONObject("resultTypes");
        if (types == null || types.isEmpty() || types.size() > 1024) throw new IllegalArgumentException("消息字段合同缺失");
        if ("STRING".equals(plan.getStr("messageFormat"))) {
            if (types.size() != 1 || !"STRING".equals(types.getJSONObject(types.keySet().iterator().next()).getStr("sqlType"))) throw new IllegalArgumentException("原始消息字段必须是单个字符串");
            result.set(types.keySet().iterator().next(), text); return result.toString();
        }
        if (!JSONUtil.isTypeJSONObject(text)) throw new IllegalArgumentException("消息必须是JSON对象");
        var source = JSONUtil.parseObj(text, JSONConfig.create().setCheckDuplicate(true).setIgnoreError(false).setIgnoreNullValue(false).setStripTrailingZeros(false)); var columns = plan.getJSONArray("columns");
        if (columns == null) for (String name : types.keySet()) { if (!source.containsKey(name)) throw new IllegalArgumentException("消息缺少声明字段"); result.set(name, source.get(name)); }
        else for (Object item : columns) { var field = JSONUtil.parseObj(item); result.set(field.getStr("name"), path(source, field.getStr("path"))); }
        return result.toString();
    }
    private static final Pattern PATH = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_]*)|\\.\"([^\"\\\\]+)\"|\\[(\\d+)\\]");
    private static Object path(JSONObject source, String expression) {
        if (expression == null || !expression.startsWith("$")) throw new IllegalArgumentException("消息字段路径无效");
        Object current = source; int position = 1; var tokens = PATH.matcher(expression);
        while (tokens.find()) {
            if (tokens.start() != position) throw new IllegalArgumentException("消息字段路径无效"); position = tokens.end();
            if (JSONUtil.isNull(current)) return null;
            if (tokens.group(3) == null) { String key = tokens.group(1) == null ? tokens.group(2) : tokens.group(1); if (!(current instanceof Map<?, ?> map) || !map.containsKey(key)) throw new IllegalArgumentException("消息路径缺少声明属性"); current = map.get(key); }
            else { int index = Integer.parseInt(tokens.group(3)); if (!(current instanceof List<?> values) || index >= values.size()) throw new IllegalArgumentException("消息路径下标超出范围"); current = values.get(index); }
        }
        if (position != expression.length() || current instanceof Map<?, ?> || current instanceof List<?>) throw new IllegalArgumentException("消息路径必须指向确定标量");
        return current;
    }
    private static Properties kafkaProperties(JSONObject plan, boolean output) {
        String servers = plan.getStr("bootstrapServers");
        if (servers == null || !servers.matches("[A-Za-z0-9.-]+:[0-9]{1,5}(,[A-Za-z0-9.-]+:[0-9]{1,5})*")) throw new IllegalArgumentException("Kafka固定地址无效");
        var result = new Properties(); result.setProperty("bootstrap.servers", servers);
        var supplied = plan.getJSONObject("options");
        Set<String> allowed = output ? Set.of("batch.size", "linger.ms", "request.timeout.ms", "delivery.timeout.ms", "max.request.size", "transaction.timeout.ms", "acks", "compression.type", "enable.idempotence")
                : Set.of("group.id", "fetch.min.bytes", "fetch.max.bytes", "fetch.max.wait.ms", "max.partition.fetch.bytes", "max.poll.records", "request.timeout.ms", "session.timeout.ms", "heartbeat.interval.ms");
        if (supplied != null) supplied.forEach((key, value) -> { if (key.startsWith("properties.") && !key.equals("properties.bootstrap.servers")) { String property = key.substring(11); if (!allowed.contains(property)) throw new IllegalArgumentException("Kafka客户端参数未授权"); result.setProperty(property, value.toString()); } });
        if (output) { result.setProperty("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer"); result.setProperty("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer"); if (!result.containsKey("acks")) result.setProperty("acks", "all"); result.setProperty("max.block.ms", "10000"); }
        else { result.setProperty("key.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer"); result.setProperty("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer"); result.setProperty("enable.auto.commit", "false"); result.setProperty("auto.offset.reset", "none"); result.setProperty("default.api.timeout.ms", "10000"); }
        return result;
    }
    private static void kafkaRead(JSONObject plan, Consumer<String> rows, Budget budget) throws Exception {
        String topic = plan.getStr("topic"); var options = plan.getJSONObject("options");
        try (var consumer = new KafkaConsumer<byte[], byte[]>(kafkaProperties(plan, false))) {
            var partitions = consumer.partitionsFor(topic, Duration.ofSeconds(10)).stream().map(p -> new TopicPartition(topic, p.partition())).sorted(Comparator.comparingInt(TopicPartition::partition)).toList();
            if (partitions.isEmpty() || partitions.size() > 64) throw new IllegalArgumentException("Kafka分区为空或超过预算");
            consumer.assign(partitions); var earliest = consumer.beginningOffsets(partitions, Duration.ofSeconds(10)); var latest = consumer.endOffsets(partitions, Duration.ofSeconds(10));
            var start = kafkaOffsets(consumer, options, "scan.startup", partitions, earliest, latest); var end = kafkaOffsets(consumer, options, "scan.bounded", partitions, earliest, latest);
            for (var partition : partitions) {
                long from = start.get(partition), until = end.get(partition);
                if (from < earliest.get(partition) || until < from || until > latest.get(partition)) throw new IllegalStateException("Kafka固定读取区间已失效或未来结束位点超出快照");
                consumer.seek(partition, from);
            }
            var active = new HashSet<>(partitions);
            while (!active.isEmpty()) {
                budget.check();
                for (var partition : List.copyOf(active)) if (consumer.position(partition) >= end.get(partition)) { consumer.pause(List.of(partition)); active.remove(partition); }
                if (active.isEmpty()) break;
                var records = consumer.poll(Duration.ofMillis(Math.min(500, budget.remainingMillis())));
                for (var record : records) if (record.offset() < end.get(new TopicPartition(record.topic(), record.partition()))) rows.accept(payload(plan, record.value()));
            }
            if (!new HashSet<>(consumer.partitionsFor(topic).stream().map(p -> new TopicPartition(topic, p.partition())).toList()).equals(new HashSet<>(partitions))) throw new IllegalStateException("Kafka读取期间分区拓扑改变");
        }
    }
    private static Map<TopicPartition, Long> kafkaOffsets(KafkaConsumer<byte[], byte[]> consumer, JSONObject options, String prefix,
            List<TopicPartition> partitions, Map<TopicPartition, Long> earliest, Map<TopicPartition, Long> latest) {
        String mode = options.getStr(prefix + ".mode", prefix.endsWith("startup") ? "earliest-offset" : "latest-offset"); var result = new LinkedHashMap<TopicPartition, Long>();
        switch (mode) {
            case "earliest-offset" -> result.putAll(earliest);
            case "latest-offset" -> result.putAll(latest);
            case "group-offsets" -> { var offsets = consumer.committed(new HashSet<>(partitions)); for (var partition : partitions) { var offset = offsets.get(partition); if (offset == null) throw new IllegalStateException("Kafka消费组未保存明确位点"); result.put(partition, offset.offset()); } }
            case "timestamp" -> { long timestamp = new BigDecimal(options.getStr(prefix + ".timestamp-millis")).longValueExact(); var requested = new LinkedHashMap<TopicPartition, Long>(); partitions.forEach(p -> requested.put(p, timestamp)); var offsets = consumer.offsetsForTimes(requested); for (var p : partitions) result.put(p, offsets.get(p) == null ? latest.get(p) : offsets.get(p).offset()); }
            case "specific-offsets" -> {
                var declared = new HashMap<Integer, Long>();
                for (String pair : options.getStr(prefix + ".specific-offsets", "").split(";")) { if (!pair.matches("partition:[0-9]+,offset:[0-9]+")) throw new IllegalArgumentException("Kafka固定位点无效"); String[] values = pair.split(","); if (declared.put(Integer.parseInt(values[0].substring(10)), Long.parseLong(values[1].substring(7))) != null) throw new IllegalArgumentException("Kafka位点分区重复"); }
                if (declared.size() != partitions.size()) throw new IllegalArgumentException("Kafka固定位点必须覆盖全部真实分区");
                for (var p : partitions) { if (!declared.containsKey(p.partition())) throw new IllegalArgumentException("Kafka固定位点分区不一致"); result.put(p, declared.get(p.partition())); }
            }
            default -> throw new IllegalArgumentException("Kafka有界位点模式无效");
        }
        return result;
    }
    private static ManagedRabbitConnection rabbit(JSONObject plan, boolean output) { return new ManagedRabbitConnection(plan.getStr("host"), plan.getInt("port"), plan.getStr("virtualHost"), plan.getStr("queue"), plan.getStr("credentialRef"), plan.getBool("useSsl"), output); }
    private static void rabbitRead(JSONObject plan, Consumer<String> rows, Budget budget) throws Exception {
        try (var connection = rabbit(plan, false).openFactory().newConnection("datahub-bounded-browse"); var channel = connection.createChannel()) {
            var declaration = channel.queueDeclarePassive(plan.getStr("queue")); int count = declaration.getMessageCount();
            if (declaration.getConsumerCount() != 0 || count > 65535 || count > budget.maxRows) throw new IllegalStateException("RabbitMQ有界浏览需要无其他消费者且启动条数不超过65535");
            if (count == 0) return;
            channel.basicQos(count); var deliveries = new ArrayBlockingQueue<Delivery>(count + 1); var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            String consumer = channel.basicConsume(plan.getStr("queue"), false, "dh_bounded_" + UUID.randomUUID(), false, true, Map.of(),
                    (tag, delivery) -> { if (!deliveries.offer(delivery)) { cancelled.set(true); } }, tag -> cancelled.set(true));
            try {
                for (int index = 0; index < count; index++) {
                    budget.check(); if (cancelled.get()) throw new IllegalStateException("RabbitMQ独占浏览被取消");
                    Delivery delivery = deliveries.poll(Math.min(1000, budget.remainingMillis()), TimeUnit.MILLISECONDS);
                    if (delivery == null) { index--; continue; }
                    rows.accept(payload(plan, delivery.getBody()));
                }
            } finally { if (channel.isOpen()) channel.basicCancel(consumer); }
            // 从不确认浏览消息，关闭通道后重新入队；生产者并发时只保证启动条数边界，不冒称点时快照。
        }
    }
    private static ManagedRocketResource rocket(JSONObject plan, boolean output) { return new ManagedRocketResource(plan.getStr("nameservers"), plan.getStr("topic"), plan.getStr("group"), plan.getStr("credentialRef"), plan.getBool("useTls"), output); }
    private static List<MessageQueue> rocketQueues(DefaultMQPullConsumer consumer, String topic) throws Exception {
        var queues = consumer.fetchSubscribeMessageQueues(topic);
        if (queues == null || queues.isEmpty() || queues.size() > 64 || queues.stream().anyMatch(q -> !topic.equals(q.getTopic()) || q.getQueueId() < 0 || q.getBrokerName() == null || !q.getBrokerName().matches("[A-Za-z0-9_.-]{1,128}"))) throw new IllegalStateException("RocketMQ队列拓扑无效或超限");
        return queues.stream().sorted(Comparator.comparing(MessageQueue::getBrokerName).thenComparingInt(MessageQueue::getQueueId)).toList();
    }
    private static void rocketRead(JSONObject plan, Consumer<String> rows, Budget budget) throws Exception {
        var resource = rocket(plan, false); var consumer = new DefaultMQPullConsumer(plan.getStr("group"), resource.credentials()); resource.configure(consumer);
        try {
            consumer.setRegisterTopics(Set.of(plan.getStr("topic"))); consumer.start(); var queues = rocketQueues(consumer, plan.getStr("topic"));
            var starts = new LinkedHashMap<MessageQueue, Long>(); var ends = new LinkedHashMap<MessageQueue, Long>();
            for (var queue : queues) {
                long end = consumer.maxOffset(queue), earliest = consumer.minOffset(queue), saved = consumer.fetchConsumeOffset(queue, true);
                starts.put(queue, rocketStart(earliest, end, saved, plan.getJSONObject("properties").getStr("offset.reset", "earliest"))); ends.put(queue, end);
            }
            for (var entry : starts.entrySet()) {
                long offset = entry.getValue(), end = ends.get(entry.getKey());
                while (offset < end) {
                    budget.check(); var result = consumer.pull(entry.getKey(), "*", offset, (int) Math.min(32, end - offset), Math.min(plan.getJSONObject("properties").getLong("pull.timeout.ms", 1000L), budget.remainingMillis()));
                    if (result.getPullStatus() == PullStatus.OFFSET_ILLEGAL || result.getNextBeginOffset() < offset || result.getPullStatus() == PullStatus.NO_NEW_MSG) throw new IllegalStateException("RocketMQ固定位点已失效，不能截短成功");
                    if (result.getPullStatus() == PullStatus.FOUND) for (var message : result.getMsgFoundList()) { if (!plan.getStr("topic").equals(message.getTopic())) throw new IllegalStateException("RocketMQ返回未授权主题"); if (message.getQueueOffset() < end) rows.accept(payload(plan, message.getBody())); }
                    if (result.getNextBeginOffset() <= offset) throw new IllegalStateException("RocketMQ有界拉取未推进"); offset = result.getNextBeginOffset();
                }
            }
            if (!new HashSet<>(queues).equals(new HashSet<>(rocketQueues(consumer, plan.getStr("topic"))))) throw new IllegalStateException("RocketMQ读取期间队列拓扑改变");
            // 有界读取不调用 updateConsumeOffset/persist，任务重试可重新读取相同的批准区间。
        } finally { consumer.shutdown(); }
    }
    private static long rocketStart(long earliest, long end, long saved, String reset) {
        if (earliest < 0 || end < earliest || !Set.of("earliest", "latest").contains(reset)) throw new IllegalStateException("RocketMQ快照位点无效");
        long start = saved < 0 ? reset.equals("latest") ? end : earliest : saved;
        if (start < earliest || start > end) throw new IllegalStateException("RocketMQ已保存消费位点不属于当前有限区间，不能隐式跳过");
        return start;
    }
    private static String ordinary(String value) { if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("TDengine物理标识无效"); return "`" + value + "`"; }
    private static void tdengineRead(JSONObject plan, Consumer<String> rows, Budget budget) throws Exception {
        String url = plan.getStr("jdbcUrl"), schema = plan.getStr("schema"), table = plan.getStr("tableName");
        if (url == null || !url.matches("jdbc:TAOS-(WS|RS)://[A-Za-z0-9.-]+:[0-9]{1,5}/[A-Za-z_][A-Za-z0-9_]{0,62}")) throw new IllegalArgumentException("TDengine受管JDBC地址无效");
        var secret = new Properties(); secret.load(new StringReader(EtlManagedSecrets.readFromEnvironment(plan.getStr("credentialRef"))));
        if (!secret.stringPropertyNames().equals(Set.of("jdbcUrl", "schema", "sourceTables", "username", "password")) || !url.equals(secret.getProperty("jdbcUrl")) || !schema.equals(secret.getProperty("schema"))
                || !Arrays.asList(secret.getProperty("sourceTables", "").split(",", -1)).contains(table)) throw new IllegalArgumentException("TDengineJDBC资源和秘密授权不一致");
        String username = secret.getProperty("username"), password = secret.getProperty("password");
        if (username == null || !username.matches("[A-Za-z0-9_.-]{1,64}") || password == null || password.isEmpty() || password.length() > 1024 || password.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("TDengineJDBC凭据无效");
        var types = plan.getJSONObject("resultTypes"); String query = "SELECT " + String.join(", ", types.keySet().stream().map(ManagedFlinkMessageTransport::ordinary).toList()) + " FROM " + ordinary(schema) + "." + ordinary(table);
        if (!query.equals(plan.getStr("query"))) throw new IllegalArgumentException("TDengine查询必须等于固定表的声明投影");
        Class.forName(url.startsWith("jdbc:TAOS-WS:") ? "com.taosdata.jdbc.ws.WebSocketDriver" : "com.taosdata.jdbc.rs.RestfulDriver");
        var properties = new Properties(); properties.setProperty("user", username); properties.setProperty("password", password); properties.setProperty("connectTimeout", "10000"); properties.setProperty("messageWaitTimeout", "10000");
        try (var connection = DriverManager.getConnection(url, properties); var statement = connection.createStatement()) {
            statement.setQueryTimeout(30); statement.setMaxRows((int) budget.maxRows + 1);
            try (var result = statement.executeQuery(query)) {
                var metadata = result.getMetaData(); if (metadata.getColumnCount() != types.size()) throw new IllegalStateException("TDengine运行字段数量变化");
                for (int i = 0; i < types.size(); i++) if (!new ArrayList<>(types.keySet()).get(i).equals(metadata.getColumnLabel(i + 1))) throw new IllegalStateException("TDengine运行投影字段顺序变化");
                while (result.next()) { budget.check(); var row = object(); int index = 0;
                    for (String name : types.keySet()) { String type = types.getJSONObject(name).getStr("sqlType"); Object value;
                        if (type.startsWith("TIMESTAMP")) { var timestamp = result.getTimestamp(++index, Calendar.getInstance(TimeZone.getTimeZone("UTC"))); value = timestamp == null ? null : timestamp.toInstant().toString(); }
                        else value = result.getString(++index); row.set(name, value); }
                    rows.accept(row.toString());
                }
            }
        }
    }
    private static final class BufferedWriter implements Writer {
        private final String kind; private final JSONObject plan; private final List<byte[]> rows = new ArrayList<>(); private long bytes; private boolean closed, finished;
        BufferedWriter(String kind, JSONObject plan) { this.kind = kind; this.plan = JSONUtil.parseObj(plan.toString()); }
        @Override public void write(Map<String, String> values) {
            if (closed || finished || Thread.currentThread().isInterrupted()) throw new IllegalStateException("有界消息输出已结束或取消");
            var types = plan.getJSONObject("resultTypes"); if (!types.keySet().equals(values.keySet())) throw new IllegalArgumentException("消息输出字段不一致");
            EtlNativeRowGuard.validate(JSONUtil.toJsonStr(values), plan);
            var row = object(); values.forEach((name, value) -> { String type = types.getJSONObject(name).getStr("sqlType"); row.set(name, value == null ? null : type.equals("BOOLEAN") ? Boolean.valueOf(value) : type.matches("(TINYINT|SMALLINT|INT|BIGINT|FLOAT|DOUBLE)") || type.startsWith("DECIMAL(") ? new BigDecimal(value) : value); });
            EtlNativeRowGuard.validate(row.toString(), plan); byte[] encoded = row.toString().getBytes(StandardCharsets.UTF_8);
            if ("STRING".equals(plan.getStr("messageFormat"))) { if (values.size() != 1 || values.values().iterator().next() == null) throw new IllegalArgumentException("原始消息输出必须是非空单字段"); encoded = values.values().iterator().next().getBytes(StandardCharsets.UTF_8); }
            if (encoded.length > 1_048_576 || rows.size() >= plan.getInt("maxRows", 1_000_000) || (bytes += encoded.length) > plan.getLong("maxBytes", 67_108_864L)) throw new IllegalArgumentException("消息输出超过行字节预算"); rows.add(encoded);
        }
        @Override public void finish() throws Exception {
            if (closed || finished) throw new IllegalStateException("消息输出不能重复提交");
            var budget = new Budget(plan);
            try {
                switch (kind) {
                    case "kafka" -> { try (var producer = new KafkaProducer<byte[], byte[]>(kafkaProperties(plan, true))) { for (var row : rows) { budget.check(); producer.send(new ProducerRecord<>(plan.getStr("topic"), row)).get(Math.min(10_000, budget.remainingMillis()), TimeUnit.MILLISECONDS); } producer.flush(); } }
                    case "rabbitmq" -> { try (var connection = rabbit(plan, true).openFactory().newConnection("datahub-bounded-publish"); var channel = connection.createChannel()) { channel.queueDeclarePassive(plan.getStr("queue")); channel.confirmSelect(); var returned = new java.util.concurrent.atomic.AtomicBoolean(); channel.addReturnListener((ReturnListener) (code, text, exchange, routing, properties, body) -> returned.set(true)); for (var row : rows) { budget.check(); channel.basicPublish("", plan.getStr("queue"), true, MessageProperties.PERSISTENT_TEXT_PLAIN, row); channel.waitForConfirmsOrDie(Math.min(plan.getJSONObject("properties").getLong("confirm.timeout.ms", 10_000L), budget.remainingMillis())); if (returned.get()) throw new IllegalStateException("RabbitMQ消息未路由"); } } }
                    case "rocketmq" -> { var resource = rocket(plan, true); var producer = new DefaultMQProducer(plan.getStr("group"), resource.credentials()); resource.configure(producer); try { producer.start(); for (var row : rows) { budget.check(); var result = producer.send(new Message(plan.getStr("topic"), row), Math.min(plan.getJSONObject("properties").getLong("send.timeout.ms", 10_000L), budget.remainingMillis())); if (result.getSendStatus() != SendStatus.SEND_OK) throw new IllegalStateException("RocketMQ消息未持久确认"); } } finally { producer.shutdown(); } }
                    default -> throw new IllegalArgumentException("消息输出协议无效");
                }
                finished = true;
            } catch (Exception failure) { throw new IllegalStateException("受管有界输出失败，已确认部分采用至少一次语义：" + kind); }
            finally { rows.clear(); }
        }
        @Override public void close() { closed = true; rows.clear(); }
    }
}
