package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.MongoNativeMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 原生创建视图和时序集合，两者都不能像普通
 * 集合一样重命名；绝不删除已有目标，原生视图依赖必须已存在于
 * 所选目标数据库中；数据传输属于独立协议，当前尚不支持。 */
final class MongoSpecialStructure {

    static final String FORMAT = "mongodb-special-structure-v1";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 初始化 {@code MongoSpecialStructure}。
     */
    private MongoSpecialStructure() {
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    static NativeTableDefinition describe(MongoNativeSession session, String schema, String table,
            int timeout) throws SQLException {
        var info = info(session, schema, table, timeout);
        if (info == null || "collection".equals(info.path("type").asText())) {
            return null;
        }
        var first = observe(session, schema, table, timeout);
        if (!first.toString().equals(observe(session, schema, table, timeout).toString())) {
            throw new SQLException("MongoDB special structure changed during capture");
        }
        var envelope = JSON.createObjectNode().put("major", 7).put("kind", first.path("kind").asText());
        envelope.set("options", first.get("options"));
        envelope.set("indexes", first.get("indexes"));
        envelope.set("sourceIdentity", first.path("identity"));
        return new NativeTableDefinition(ProductTypeEnum.MONGODB, FORMAT, envelope.toString(),
                MongoMigrationFrame.FIELDS);
    }

    /**
     * 拒绝当前实现未支持的 {@code prepare} 操作。
     */
    static NativeTargetPlan prepare(DataSource target, NativeTableDefinition definition, String schema, String table,
            Map<String, String> mapping, boolean replace, long limit, int timeout) {
        if (definition.product() != ProductTypeEnum.MONGODB || !definition.fields().equals(MongoMigrationFrame.FIELDS)
                || !Map.of("_id", "_id", MongoMigrationFrame.DOCUMENT, MongoMigrationFrame.DOCUMENT).equals(mapping)) {
            throw new IllegalArgumentException("MongoDB native special structure cannot rename the transfer frame");
        }
        final ObjectNode desired;
        final JsonNode dependency;
        final byte[] create, indexes;
        try {
            if (2L * definition.document().length() > limit) {
                throw new IllegalArgumentException("MongoDB native definition exceeds its byte budget");
            }
            desired = (ObjectNode) JSON.readTree(definition.document());
            validate(desired);
            try (var connection = target.getConnection()) {
                var session = MongoNativeSession.open(connection, limit, timeout);
                MongoNativeStructure.serverMajor(session);
                if (info(session, schema, table, timeout) != null) {
                    throw new IllegalArgumentException(
                            "MongoDB special native creation requires an absent target; replacement is not supported");
                }
                dependency = dependency(session, schema, desired, timeout);
                var command = JSON.createObjectNode().put("create", table);
                desired.path("options").fields()
                        .forEachRemaining(e -> command.set(e.getKey(), e.getValue().deepCopy()));
                MongoNativeStructure.writeConcern(command, timeout);
                create = session.encode(command.toString());
                var indexCommand = JSON.createObjectNode().put("createIndexes", table);
                var specs = indexCommand.putArray("indexes");
                desired.path("indexes").forEach(
                        index -> specs.add(MongoNativeStructure.createIndexSpec(index, desired.path("options"))));
                MongoNativeStructure.writeConcern(indexCommand, timeout);
                indexes = desired.path("indexes").isEmpty() ? null : session.encode(indexCommand.toString());
            }
        } catch (java.io.IOException | SQLException failure) {
            throw new IllegalStateException("MongoDB special structure preflight rejected before any mutation");
        }
        String digest = hash(schema + '\0' + table + '\0' + desired + '\0' + dependency);
        return new NativeTargetPlan() {
            private final AtomicBoolean used = new AtomicBoolean();

            private volatile String createdIdentity;

            /**
             * 返回摘要。
             */
            public String digest() {
                return digest;
            }

            /**
             * 说明当前目标计划是否会替换已有对象。
             */
            public boolean replacesExisting() {
                return false;
            }

            /**
             * 说明当前 DDL 路径是否要求调用方独占目标结构变更。
             */
            public boolean requiresExclusiveDdlOwnership() {
                return true;
            }

            /**
             * 返回当前操作恢复时需要关联的资源信息。
             */
            public List<NativeRecoveryResource> recoveryResources() {
                var resources = new ArrayList<NativeRecoveryResource>();
                resources.add(new NativeRecoveryResource(ProductTypeEnum.MONGODB, schema, table,
                        NativeRecoveryResource.Role.TARGET, null));
                if (!dependency.isNull()) {
                    resources.add(new NativeRecoveryResource(ProductTypeEnum.MONGODB, schema,
                            desired.path("options").path("viewOn").asText(), NativeRecoveryResource.Role.TARGET,
                            dependency.path("uuid").toString()));
                }
                if (createdIdentity != null) {
                    resources.add(new NativeRecoveryResource(ProductTypeEnum.MONGODB, schema, table,
                            NativeRecoveryResource.Role.STAGING, createdIdentity));
                }
                return List.copyOf(resources);
            }

            /**
             * 在数据搬运前检查源端与目标端的兼容条件。
             */
            public void preflightDataTransfer() {
                throw new UnsupportedOperationException(
                        "MongoDB views and time-series require an explicit data-transfer protocol; native structure creation is available");
            }

            /**
             * 根据已验证原生定义应用特殊集合或视图结构操作。
             */
            public void execute() {
                if (!used.compareAndSet(false, true)) {
                    throw new IllegalStateException("MongoDB special native plan is single use");
                }
                boolean attempted = false;
                try (var connection = target.getConnection()) {
                    var session = MongoNativeSession.open(connection, limit, timeout);
                    MongoNativeStructure.serverMajor(session);
                    if (info(session, schema, table, timeout) != null || !dependency.toString()
                            .equals(dependency(session, schema, desired, timeout).toString())) {
                        throw new IllegalStateException("MongoDB target or dependency changed after preparation");
                    }
                    attempted = true;
                    MongoReplies.noWriteErrors(MongoReplies.success(session, session.command(schema, create)));
                    if (indexes != null) {
                        MongoReplies.noWriteErrors(MongoReplies.success(session, session.command(schema, indexes)));
                    }
                    var actual = observe(session, schema, table, timeout);
                    if (!actual.path("identity").isNull()) {
                        createdIdentity = actual.path("identity").toString();
                    }
                    if (!desired.path("kind").equals(actual.path("kind")) || !desired.path("options").toString()
                            .equals(actual.path("options").toString())
                            || !desired.path("indexes").toString().equals(actual.path("indexes").toString())
                            || !dependency.toString()
                            .equals(dependency(session, schema, desired, timeout).toString())) {
                        throw new IllegalStateException("MongoDB special native read-back disagrees with the plan");
                    }
                } catch (SQLException | RuntimeException failure) {
                    if (attempted) {
                        throw new UnconfirmedWriteException();
                    }
                    throw new IllegalStateException("MongoDB special structure changed before execution");
                }
            }
        };
    }

    /**
     * 读取 MongoDB 集合完整定义和选项。
     */
    private static JsonNode info(MongoNativeSession session, String schema, String table,
            int timeout) throws SQLException {
        var request = JSON.createObjectNode().put("listCollections", 1).put("nameOnly", false)
                .put("maxTimeMS", 1000L * timeout);
        request.putObject("filter").put("name", table);
        request.putObject("cursor").put("batchSize", 2);
        var cursor = MongoNativeStructure.command(session, schema, request).path("cursor");
        if (MongoReplies.integer(cursor.path("id")) != 0 || !cursor.path("firstBatch").isArray() || cursor.path(
                "firstBatch").size() > 1) {
            throw new SQLException("MongoDB special collection observation is incomplete");
        }
        if (cursor.path("firstBatch").isEmpty()) {
            return null;
        }
        var info = cursor.path("firstBatch").get(0);
        if (!table.equals(info.path("name").asText())) {
            throw new SQLException("MongoDB collection identity is ambiguous");
        }
        return info;
    }

    /**
     * 读取当前目标对象或运行状态的最新事实。
     */
    private static ObjectNode observe(MongoNativeSession session, String schema, String table,
            int timeout) throws SQLException {
        var info = info(session, schema, table, timeout);
        if (info == null) {
            throw new SQLException("MongoDB special collection is absent");
        }
        String kind = info.path("type").asText();
        var result = JSON.createObjectNode().put("kind", kind).put("major", 7);
        result.set("options", info.path("options"));
        var indexes = result.putArray("indexes");
        result.putNull("identity");
        if (kind.equals("timeseries")) {
            var bucket = info(session, schema, "system.buckets." + table, timeout);
            if (bucket == null || !"collection".equals(bucket.path("type").asText())) {
                throw new SQLException("MongoDB time-series backing collection is absent");
            }
            MongoNativeStructure.uuid(bucket.path("info").path("uuid"));
            result.set("identity", bucket.path("info").path("uuid"));
            var request = JSON.createObjectNode().put("listIndexes", table).put("maxTimeMS", 1000L * timeout);
            request.putObject("cursor").put("batchSize", 65);
            var cursor = MongoNativeStructure.command(session, schema, request).path("cursor");
            if (MongoReplies.integer(cursor.path("id")) != 0 || !cursor.path("firstBatch").isArray() || cursor.path(
                    "firstBatch").size() > 64) {
                throw new SQLException("MongoDB time-series index observation is incomplete");
            }
            var sorted = new TreeMap<String, JsonNode>();
            for (var index : cursor.path("firstBatch")) {
                if (sorted.put(index.path("name").asText(), index) != null) {
                    throw new SQLException("MongoDB duplicate index evidence");
                }
            }
            sorted.values().forEach(indexes::add);
        }
        validate(result);
        dependency(session, schema, result, timeout);
        return result;
    }

    /**
     * 显式指定一个已有普通源集合；嵌套视图和外部流水线依赖。
     */
    private static JsonNode dependency(MongoNativeSession session, String schema, JsonNode desired,
            int timeout) throws SQLException {
        if (!desired.path("kind").asText().equals("view")) {
            return JSON.nullNode();
        }
        String dependency = desired.path("options").path("viewOn").asText();
        MongoNativeMetadata.identifier(dependency);
        // 显式指定一个已有普通源集合；嵌套视图和外部流水线依赖
        // 需要独立的依赖图，此处予以拒绝，不能隐式重新绑定。
        return MongoNativeStructure.observe(session, schema, dependency, false, timeout);
    }

    /**
     * 校验特殊集合或视图定义中的选项及外部依赖边界。
     */
    private static void validate(JsonNode desired) {
        String kind = desired.path("kind").asText();
        var options = desired.path("options");
        var indexes = desired.path("indexes");
        if (desired.path("major").asInt() != 7 || !options.isObject() || !indexes.isArray() || indexes.size() > 64) {
            throw new IllegalArgumentException("MongoDB special structure is incomplete");
        }
        if (kind.equals("timeseries")) {
            allowed(options, Set.of("timeseries", "expireAfterSeconds", "collation"));
            var series = options.path("timeseries");
            if (!series.isObject() || !series.path("timeField").isTextual()) {
                throw new IllegalArgumentException("MongoDB time-series options are incomplete");
            }
            allowed(series,
                    Set.of("timeField", "metaField", "granularity", "bucketMaxSpanSeconds", "bucketRoundingSeconds"));
            var names = new HashSet<String>();
            for (var index : indexes) {
                allowed(index,
                        Set.of("v", "key", "name", "sparse", "hidden", "expireAfterSeconds", "partialFilterExpression",
                                "collation", "2dsphereIndexVersion"));
                if (!index.path("key").isObject() || index.path("key").isEmpty() || !index.path("name")
                        .isTextual() || !names.add(index.path("name").textValue())) {
                    throw new IllegalArgumentException("MongoDB time-series index is incomplete");
                }
            }
        } else if (kind.equals("view")) {
            allowed(options, Set.of("viewOn", "pipeline", "collation"));
            MongoNativeMetadata.identifier(options.path("viewOn").asText());
            if (!options.path("pipeline").isArray() || options.path("pipeline").size() > 100 || !indexes.isEmpty()) {
                throw new IllegalArgumentException("MongoDB view pipeline is incomplete");
            }
            rejectExternalPipeline(options.path("pipeline"));
        } else {
            throw new IllegalArgumentException("MongoDB special structure type is unsupported");
        }
    }

    /**
     * 拒绝当前 MongoDB 管道访问外部集合或产生外部写入。
     */
    private static void rejectExternalPipeline(JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (Set.of("$lookup", "$graphLookup", "$unionWith", "$out", "$merge", "$changeStream", "$function",
                        "$accumulator").contains(e.getKey())) {
                    throw new IllegalArgumentException(
                            "MongoDB view external dependencies or server functions require a separate plan");
                }
                rejectExternalPipeline(e.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(MongoSpecialStructure::rejectExternalPipeline);
        }
    }

    /**
     * 检查原生定义或配置只包含当前允许的属性。
     */
    private static void allowed(JsonNode object, Set<String> keys) {
        if (!object.isObject()) {
            throw new IllegalArgumentException("MongoDB native options must be an object");
        }
        object.fieldNames().forEachRemaining(key -> {
            if (!keys.contains(key)) {
                throw new IllegalArgumentException("MongoDB native option requires explicit adaptation");
            }
        });
    }

    /**
     * 根据当前内容计算摘要，供一致性比较使用。
     */
    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
