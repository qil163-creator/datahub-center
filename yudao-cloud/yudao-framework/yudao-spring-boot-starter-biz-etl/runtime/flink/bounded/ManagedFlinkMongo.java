package cn.iocoder.yudao.framework.etl.flink.bounded;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlBoundedConnectorTransport;
import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.types.*;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** MongoDB 官方同步 SDK 的受管有界传输，读取超额和部分写入失败均明确失败。 */
final class ManagedFlinkMongo {
    private ManagedFlinkMongo() { }
    private static MongoClient connect(JSONObject plan, boolean output) {
        String uri = plan.getStr("uri", ""), database = plan.getStr("database", ""), collection = plan.getStr("collection", "");
        if (!uri.matches("mongodb://[A-Za-z0-9.,:-]+/") || !database.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,127}") || !collection.matches("[\\p{L}_][\\p{L}\\p{N}_.-]{0,119}") || collection.startsWith("system."))
            throw new IllegalArgumentException("MongoDB 受管资源标识无效");
        String allowlist = System.getenv("DATAHUB_ETL_MONGO_RESOURCES");
        var resources = allowlist == null ? JSONUtil.createArray() : JSONUtil.parseArray(allowlist);
        long matches = resources.stream().map(JSONUtil::parseObj).filter(resource -> uri.equals(resource.getStr("uri")) && database.equals(resource.getStr("database"))
                && collection.equals(resource.getStr("collection")) && resource.getBool(output ? "sink" : "source", false)).count();
        if (matches != 1) throw new IllegalArgumentException("工作节点未唯一授权 MongoDB 资源或方向");
        var settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri)).retryReads(false).retryWrites(false)
                .applyToSocketSettings(config -> config.connectTimeout(10000, TimeUnit.MILLISECONDS).readTimeout(30000, TimeUnit.MILLISECONDS))
                .applyToClusterSettings(config -> config.serverSelectionTimeout(10000, TimeUnit.MILLISECONDS)).build();
        return MongoClients.create(settings);
    }
    static void read(JSONObject plan, Consumer<String> rows) {
        try (var client = connect(plan, false)) {
            var collection = client.getDatabase(plan.getStr("database")).getCollection(plan.getStr("collection"));
            Document filter = plan.getStr("where", "").isBlank() ? new Document() : Document.parse(plan.getStr("where"));
            var projection = new Document();
            for (Object raw : plan.getJSONArray("fields")) projection.put(JSONUtil.parseObj(raw).getStr("source"), 1);
            if (!projection.containsKey("_id")) projection.put("_id", 0);
            long count = 0, bytes = 0, deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
            try (var cursor = collection.find(filter).projection(projection).maxTime(30000, TimeUnit.MILLISECONDS).limit(plan.getInt("maxRows", 100000) + 1).batchSize(1000).cursor()) {
                while (cursor.hasNext()) {
                    if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new IllegalArgumentException("MongoDB 读取已取消或超过时间预算");
                    var source = cursor.next(); var output = JSONUtil.createObj();
                    for (Object raw : plan.getJSONArray("fields")) {
                        var field = JSONUtil.parseObj(raw); Object value = source.get(field.getStr("source"));
                        output.set(field.getStr("target"), text(value, field.getStr("type")));
                    }
                    String row = output.toString();
                    if (++count > plan.getInt("maxRows", 100000) || (bytes += row.getBytes(StandardCharsets.UTF_8).length) > plan.getLong("maxBytes", 67108864L)) throw new IllegalArgumentException("MongoDB 读取超过行数或字节预算");
                    rows.accept(row);
                }
            }
        }
    }
    private static String text(Object value, String type) {
        if (value == null) return null;
        if (value instanceof String text) return text;
        if (value instanceof ObjectId object && type.equals("STRING")) return object.toHexString();
        if (value instanceof Decimal128 decimal) return decimal.bigDecimalValue().toPlainString();
        if (value instanceof java.util.Date date && type.startsWith("TIMESTAMP")) return date.toInstant().toString();
        if (value instanceof Binary binary && type.equals("BINARY")) return Base64.getEncoder().encodeToString(binary.getData());
        if (value instanceof byte[] binary && type.equals("BINARY")) return Base64.getEncoder().encodeToString(binary);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("MongoDB 字段不是已声明的标量结构");
    }
    static EtlBoundedConnectorTransport.Writer writer(JSONObject plan) {
        var client = connect(plan, true); var collection = client.getDatabase(plan.getStr("database")).getCollection(plan.getStr("collection"));
        return new EtlBoundedConnectorTransport.Writer() {
            private final List<WriteModel<Document>> batch = new ArrayList<>(); private long bytes;
            private void flush() {
                if (batch.isEmpty()) return;
                var result = collection.bulkWrite(batch, new BulkWriteOptions().ordered(true));
                if (!result.wasAcknowledged()) throw new IllegalStateException("MongoDB 批次未确认");
                batch.clear(); bytes = 0;
            }
            @Override public void write(Map<String, String> row) {
                var document = new Document();
                for (Object raw : plan.getJSONArray("fields")) { var field = JSONUtil.parseObj(raw); document.put(field.getStr("target"), value(row.get(field.getStr("target")), field.getStr("type"))); }
                String operation = plan.getStr("operation", "insert");
                if (operation.equals("insert")) batch.add(new InsertOneModel<>(document));
                else {
                    var filter = new Document();
                    for (String key : plan.getStr("primaryKeys", "").split(",", -1)) {
                        if (key.isEmpty() || document.get(key) == null) throw new IllegalArgumentException("MongoDB 更新主键缺失或为空");
                        filter.put(key, document.get(key));
                    }
                    if (operation.equals("replace")) batch.add(new ReplaceOneModel<>(filter, document, new ReplaceOptions().upsert(true)));
                    else if (operation.equals("update")) { var update = new Document(document); update.remove("_id"); batch.add(new UpdateOneModel<>(filter, new Document("$set", update), new UpdateOptions().upsert(true))); }
                    else throw new IllegalArgumentException("MongoDB 输出模式无效");
                }
                bytes += row.toString().length() * 4L;
                if (batch.size() >= plan.getInt("batchSize", 1000) || bytes >= 8_388_608) flush();
            }
            @Override public void finish() { flush(); }
            @Override public void close() { batch.clear(); client.close(); }
        };
    }
    private static Object value(String text, String type) {
        if (text == null) return null;
        if (type.equals("STRING")) return text;
        if (type.equals("BIGINT")) return new BigDecimal(text).longValueExact();
        if (type.equals("INT")) return new BigDecimal(text).intValueExact();
        if (type.equals("BOOLEAN")) { if (!Set.of("true", "false").contains(text)) throw new IllegalArgumentException(); return Boolean.parseBoolean(text); }
        if (type.equals("FLOAT") || type.equals("DOUBLE")) { double value = Double.parseDouble(text); if (!Double.isFinite(value)) throw new IllegalArgumentException(); return value; }
        if (type.startsWith("DECIMAL(")) return new Decimal128(new BigDecimal(text));
        if (type.startsWith("TIMESTAMP")) {
            Instant instant = LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
            if (instant.getNano() % 1_000_000 != 0) throw new IllegalArgumentException("MongoDB 原生时间只支持毫秒，不能丢弃小数精度");
            return java.util.Date.from(instant);
        }
        if (type.equals("BINARY")) return new Binary(Base64.getDecoder().decode(text));
        throw new IllegalArgumentException("MongoDB 输出字段类型无效");
    }
}
