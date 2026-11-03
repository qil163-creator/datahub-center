package cn.iocoder.yudao.framework.dbswitch.product.mongodb;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.MongoNativeMetadata;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeRecoveryResource;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 普通集合结构传输。要求目标的排他外部 DDL 所有权：
 * MongoDB 公开 renameCollection 命令没有目标 UUID 比较并交换参数。 */
public final class MongoNativeStructure {

    private static final String FORMAT = "mongodb-collection-v1";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> OPTIONS = Set.of("validator", "validationLevel", "validationAction",
            "collation", "changeStreamPreAndPostImages", "capped", "size", "max", "clusteredIndex",
            "expireAfterSeconds");

    private static final Set<String> INDEX_ATTRIBUTES = Set.of("v", "key", "name", "unique", "sparse",
            "expireAfterSeconds", "partialFilterExpression", "hidden", "collation", "clustered",
            "weights", "default_language", "language_override", "textIndexVersion", "2dsphereIndexVersion",
            "bits", "min", "max", "wildcardProjection");

    /**
     * 初始化 {@code MongoNativeStructure}。
     */
    private MongoNativeStructure() {
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    public static NativeTableDefinition describe(DataSource source, String schema, String table, long limit,
            int timeout) {
        names(schema, table);
        try (Connection connection = source.getConnection()) {
            var session = MongoNativeSession.open(connection, limit, timeout);
            int major = serverMajor(session);
            var special = MongoSpecialStructure.describe(session, schema, table, timeout);
            if (special != null) {
                return special;
            }
            var first = observe(session, schema, table, false, timeout);
            var second = observe(session, schema, table, false, timeout);
            if (!sameNativeEvidence(first, second)) {
                throw new SQLException("MongoDB source structure changed during capture");
            }
            validateStructure(first.path("options"), first.path("indexes"));
            ObjectNode envelope = JSON.createObjectNode();
            envelope.put("major", major);
            envelope.set("sourceIdentity", first.get("uuid"));
            envelope.set("options", first.get("options"));
            envelope.set("indexes", first.get("indexes"));
            return new NativeTableDefinition(ProductTypeEnum.MONGODB, FORMAT, envelope.toString(),
                    MongoMigrationFrame.FIELDS);
        } catch (SQLException failure) {
            throw new IllegalStateException("MongoDB source structure cannot be safely planned");
        }
    }

    /**
     * 拒绝当前实现未支持的 {@code prepare} 操作。
     */
    public static NativeTargetPlan prepare(DataSource target, NativeTableDefinition definition, String schema,
            String table,
            Map<String, String> mapping, boolean replace, long limit, int timeout) {
        names(schema, table);
        if (MongoSpecialStructure.FORMAT.equals(definition.format())) {
            return MongoSpecialStructure.prepare(target, definition, schema, table, mapping, replace, limit, timeout);
        }
        if (definition.product() != ProductTypeEnum.MONGODB || !FORMAT.equals(definition.format())
                || !definition.fields().equals(MongoMigrationFrame.FIELDS)
                || !Map.of("_id", "_id", MongoMigrationFrame.DOCUMENT, MongoMigrationFrame.DOCUMENT).equals(mapping)) {
            throw new IllegalArgumentException("MongoDB structure transfer requires the unchanged complete BSON frame");
        }
        final JsonNode envelope;
        final ObjectNode previous;
        try {
            if (2L * definition.document().length() > limit) {
                throw new IllegalArgumentException("MongoDB structure exceeds its boundary");
            }
            envelope = JSON.readTree(definition.document());
            if (!envelope.isObject() || !envelope.path("major").isInt() || envelope.path("major").intValue() != 7) {
                throw new IllegalArgumentException("MongoDB structure requires supported server version evidence");
            }
            uuid(envelope.path("sourceIdentity"));
            validateStructure(envelope.path("options"), envelope.path("indexes"));
            try (Connection connection = target.getConnection()) {
                var session = MongoNativeSession.open(connection, limit, timeout);
                if (serverMajor(session) != envelope.path("major").intValue()) {
                    throw new IllegalArgumentException(
                            "MongoDB native structure requires matching supported server major versions");
                }
                previous = observe(session, schema, table, true, timeout);
                if (previous != null) {
                    if (previous.path("uuid").equals(envelope.path("sourceIdentity"))) {
                        throw new IllegalArgumentException(
                                "MongoDB source and target must be different physical collections");
                    }
                    if (!replace) {
                        requireStructure(previous, envelope);
                    }
                }
            }
        } catch (java.io.IOException | SQLException failure) {
            throw new IllegalStateException("MongoDB native target preflight failed before any mutation");
        }
        String staging = "dbswitch_stage_" + UUID.randomUUID().toString().replace("-", "");
        String digest = hash(
                schema + "\0" + table + "\0" + replace + "\0" + staging + "\0" + envelope + "\0" + previous);
        return new NativeTargetPlan() {
            private final AtomicBoolean used = new AtomicBoolean();

            private final NativeRecoveryResource targetResource = new NativeRecoveryResource(ProductTypeEnum.MONGODB,
                    schema,
                    table, NativeRecoveryResource.Role.TARGET,
                    previous == null ? null : previous.path("uuid").toString());

            private volatile List<NativeRecoveryResource> resources = previous != null && !replace ? List.of(
                    targetResource)
                    : List.of(targetResource, new NativeRecoveryResource(ProductTypeEnum.MONGODB, schema, staging,
                    NativeRecoveryResource.Role.STAGING, null));

            /**
             * 返回摘要。
             */
            @Override
            public String digest() {
                return digest;
            }

            /**
             * 说明当前目标计划是否会替换已有对象。
             */
            @Override
            public boolean replacesExisting() {
                return previous != null && replace;
            }

            /**
             * 说明当前 DDL 路径是否要求调用方独占目标结构变更。
             */
            @Override
            public boolean requiresExclusiveDdlOwnership() {
                return replacesExisting();
            }

            /**
             * 返回资源集合。
             */
            @Override
            public List<NativeRecoveryResource> recoveryResources() {
                return resources;
            }

            /**
             * 在数据搬运前检查源端与目标端的兼容条件。
             */
            @Override
            public void preflightDataTransfer() {
                if (envelope.path("options").path("capped").asBoolean(false)) {
                    throw new UnsupportedOperationException(
                            "Capped collection order and eviction require a dedicated data-transfer plan");
                }
            }

            /**
             * 重新核对原生定义与目标状态后应用当前 MongoDB 结构操作。
             */
            @Override
            public void execute() {
                if (!used.compareAndSet(false, true)) {
                    throw new IllegalStateException("MongoDB native target plan is single use");
                }
                boolean attempted = false;
                try (Connection connection = target.getConnection()) {
                    var session = MongoNativeSession.open(connection, limit, timeout);
                    requireFresh(previous, observe(session, schema, table, true, timeout));
                    if (previous != null && !replace) {
                        return;
                    }
                    if (observe(session, schema, staging, true, timeout) != null) {
                        throw new IllegalStateException("MongoDB staging identity already exists");
                    }
                    var create = JSON.createObjectNode();
                    create.put("create", staging);
                    envelope.path("options").fields()
                            .forEachRemaining(entry -> create.set(entry.getKey(), entry.getValue().deepCopy()));
                    writeConcern(create, timeout);
                    byte[] createBytes = session.encode(create.toString());
                    var createIndexes = JSON.createObjectNode();
                    createIndexes.put("createIndexes", staging);
                    ArrayNode indexes = createIndexes.putArray("indexes");
                    for (var index : envelope.path("indexes")) {
                        if (!"_id_".equals(index.path("name").asText())) {
                            indexes.add(createIndexSpec(index, envelope.path("options")));
                        }
                    }
                    writeConcern(createIndexes, timeout);
                    // 首次修改前编码每个命令，包括所有原生 BSON 选项。
                    byte[] indexBytes = indexes.isEmpty() ? null : session.encode(createIndexes.toString());
                    attempted = true;
                    MongoReplies.noWriteErrors(MongoReplies.success(session, session.command(schema, createBytes)));
                    if (indexBytes != null) {
                        MongoReplies.noWriteErrors(MongoReplies.success(session, session.command(schema, indexBytes)));
                    }
                    var staged = observe(session, schema, staging, false, timeout);
                    resources = List.of(targetResource,
                            new NativeRecoveryResource(ProductTypeEnum.MONGODB, schema, staging,
                                    NativeRecoveryResource.Role.STAGING, staged.path("uuid").toString()));
                    requireStructure(staged, envelope);
                    // 校验器或索引构建失败不能导致已有目标被删除。
                    // 第二次检查缩小竞态窗口，不是分布式锁或服务器 UUID 锁。
                    requireFresh(previous, observe(session, schema, table, true, timeout));
                    var rename = JSON.createObjectNode();
                    rename.put("renameCollection", schema + "." + staging);
                    rename.put("to", schema + "." + table);
                    rename.put("dropTarget", previous != null && replace);
                    writeConcern(rename, timeout);
                    MongoReplies.noWriteErrors(
                            MongoReplies.success(session, session.command("admin", session.encode(rename.toString()))));
                    var actual = observe(session, schema, table, false, timeout);
                    requireStructure(actual, envelope);
                    if (!actual.path("uuid").equals(staged.path("uuid"))) {
                        throw new SQLException("MongoDB target identity differs from the prepared collection");
                    }
                } catch (SQLException | RuntimeException failure) {
                    // 不盲目重放，也不自动删除结果不确定的暂存或已重命名集合。
                    if (attempted) {
                        throw new UnconfirmedWriteException();
                    }
                    throw new IllegalStateException("MongoDB target could not be revalidated before native DDL");
                }
            }

            /**
             * 返回当前对象的文本表示。
             */
            @Override
            public String toString() {
                return "MongoDB native target plan[digest=" + digest + "]";
            }
        };
    }

    /** 普通集合演进保留其 UUID 和全部文档。索引删除或重建，
     * 以及排序规则、集群和固定大小集合变更，都是独立的破坏性操作，不能隐藏在此处。 */
    public static NativeTargetPlan evolve(DataSource target, NativeTableDefinition definition, String schema,
            String table, long limit, int timeout) {
        names(schema, table);
        if (definition.product() != ProductTypeEnum.MONGODB || !FORMAT.equals(definition.format())
                || !definition.fields().equals(MongoMigrationFrame.FIELDS)) {
            throw new IllegalArgumentException("MongoDB evolution requires complete native structure evidence");
        }
        final JsonNode desired;
        final ObjectNode previous;
        final byte[] addIndexes, modify;
        try {
            if (2L * definition.document().length() > limit) {
                throw new IllegalArgumentException("MongoDB native structure exceeds its byte budget");
            }
            desired = JSON.readTree(definition.document());
            uuid(desired.path("sourceIdentity"));
            if (desired.path("major").asInt() != 7) {
                throw new IllegalArgumentException("MongoDB native version evidence is unsupported");
            }
            validateStructure(desired.path("options"), desired.path("indexes"));
            try (var connection = target.getConnection()) {
                var session = MongoNativeSession.open(connection, limit, timeout);
                serverMajor(session);
                previous = observe(session, schema, table, false, timeout);
                var originalOptions = (ObjectNode) previous.path("options").deepCopy();
                var desiredOptions = (ObjectNode) desired.path("options").deepCopy();
                var mutable = Set.of("validator", "validationLevel", "validationAction",
                        "changeStreamPreAndPostImages");
                originalOptions.remove(mutable);
                desiredOptions.remove(mutable);
                if (!sameNativeEvidence(originalOptions, desiredOptions)) {
                    throw new IllegalArgumentException("MongoDB immutable collection options cannot evolve in place");
                }
                var indexByName = new LinkedHashMap<String, JsonNode>();
                for (var index : desired.path("indexes")) {
                    indexByName.put(index.path("name").textValue(), index);
                }
                for (var index : previous.path("indexes")) {
                    var matched = indexByName.remove(index.path("name").textValue());
                    if (!sameNativeEvidence(index, matched)) {
                        throw new IllegalArgumentException(
                                "MongoDB existing indexes cannot be removed or rebuilt by additive evolution");
                    }
                }
                var create = JSON.createObjectNode().put("createIndexes", table);
                var indexes = create.putArray("indexes");
                indexByName.values().forEach(index -> indexes.add(createIndexSpec(index, desired.path("options"))));
                writeConcern(create, timeout);
                addIndexes = indexes.isEmpty() ? null : session.encode(create.toString());
                var collMod = JSON.createObjectNode().put("collMod", table);
                for (String key : List.of("validator", "validationLevel", "validationAction",
                        "changeStreamPreAndPostImages")) {
                    JsonNode old = previous.path("options").get(key), next = desired.path("options").get(key);
                    if (!sameNativeEvidence(old, next)) {
                        if (next == null) {
                            throw new IllegalArgumentException(
                                    "MongoDB option removal requires an explicit native default");
                        }
                        collMod.set(key, next.deepCopy());
                    }
                }
                boolean change = collMod.size() > 1;
                writeConcern(collMod, timeout);
                modify = change ? session.encode(collMod.toString()) : null;
                requireFresh(previous, observe(session, schema, table, false, timeout));
            }
        } catch (java.io.IOException | SQLException failure) {
            throw new IllegalStateException("MongoDB native evolution preflight failed before mutation");
        }
        String digest = hash(schema + '\0' + table + '\0' + previous + '\0' + desired);
        return new NativeTargetPlan() {
            private final AtomicBoolean used = new AtomicBoolean();

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
                return List.of(new NativeRecoveryResource(
                        ProductTypeEnum.MONGODB, schema, table, NativeRecoveryResource.Role.TARGET,
                        previous.path("uuid").toString()));
            }

            /**
             * 重新核对原生定义与目标状态后应用当前 MongoDB 结构操作。
             */
            public void execute() {
                if (!used.compareAndSet(false, true)) {
                    throw new IllegalStateException("MongoDB native evolution plan is single use");
                }
                boolean attempted = false;
                try (var connection = target.getConnection()) {
                    var session = MongoNativeSession.open(connection, limit, timeout);
                    serverMajor(session);
                    requireFresh(previous, observe(session, schema, table, false, timeout));
                    if (addIndexes != null) {
                        attempted = true;
                        MongoReplies.noWriteErrors(MongoReplies.success(session, session.command(schema, addIndexes)));
                    }
                    if (modify != null) {
                        attempted = true;
                        MongoReplies.noWriteErrors(MongoReplies.success(session, session.command(schema, modify)));
                    }
                    var actual = observe(session, schema, table, false, timeout);
                    requireStructure(actual, desired);
                    if (!actual.path("uuid").equals(previous.path("uuid"))) {
                        throw new IllegalStateException("MongoDB evolution changed physical identity");
                    }
                } catch (SQLException | RuntimeException failure) {
                    if (attempted) {
                        throw new UnconfirmedWriteException();
                    }
                    throw new IllegalStateException("MongoDB evolution rejected before native writes");
                }
            }
        };
    }

    /**
     * 根据已观测索引定义生成重建索引请求，保留排序规则语义。
     */
    static ObjectNode createIndexSpec(JsonNode index, JsonNode collectionOptions) {
        var spec = (ObjectNode) index.deepCopy();
        // listIndexes omits simple collation. Omitting it in createIndexes instead inherits the
        // collection default, which changes text/2d semantics or makes the index invalid.
        if (!spec.has("collation") && collectionOptions.has("collation")) {
            spec.putObject("collation").put("locale", "simple");
        }
        return spec;
    }

    /**
     * 读取当前目标对象或运行状态的最新事实。
     */
    static ObjectNode observe(MongoNativeSession session, String schema, String table, boolean allowAbsent,
            int timeout) throws SQLException {
        var request = JSON.createObjectNode();
        request.put("listCollections", 1);
        request.putObject("filter").put("name", table);
        request.put("nameOnly", false);
        request.putObject("cursor").put("batchSize", 2);
        request.put("maxTimeMS", 1000L * timeout);
        JsonNode cursor = command(session, schema, request).path("cursor");
        if (MongoReplies.integer(cursor.path("id")) != 0 || !cursor.path("firstBatch").isArray()) {
            throw new SQLException("MongoDB collection observation is incomplete");
        }
        var batch = cursor.path("firstBatch");
        if (allowAbsent && batch.isEmpty()) {
            return null;
        }
        if (batch.size() != 1) {
            throw new SQLException("MongoDB collection is absent or ambiguous");
        }
        JsonNode info = batch.get(0), options = info.path("options");
        if (!table.equals(info.path("name").asText()) || !"collection".equals(info.path("type").asText())
                || !options.isObject() || info.path("info").path("readOnly").asBoolean(false)) {
            throw new SQLException("MongoDB ordinary writable collection is required");
        }
        uuid(info.path("info").path("uuid"));
        var indexRequest = JSON.createObjectNode();
        indexRequest.put("listIndexes", table);
        indexRequest.putObject("cursor").put("batchSize", 65);
        indexRequest.put("maxTimeMS", 1000L * timeout);
        var indexCursor = command(session, schema, indexRequest).path("cursor");
        if (MongoReplies.integer(indexCursor.path("id")) != 0 || !indexCursor.path("firstBatch").isArray()
                || indexCursor.path("firstBatch").isEmpty() || indexCursor.path("firstBatch").size() > 64) {
            throw new SQLException("MongoDB complete bounded index observation is required");
        }
        var sorted = new TreeMap<String, JsonNode>();
        for (var index : indexCursor.path("firstBatch")) {
            if (!index.path("name").isTextual() || sorted.put(index.path("name").textValue(), index) != null) {
                throw new SQLException("MongoDB index identity is invalid");
            }
        }
        ObjectNode result = JSON.createObjectNode();
        result.set("uuid", info.path("info").path("uuid"));
        result.set("options", options);
        var indexes = result.putArray("indexes");
        sorted.values().forEach(indexes::add);
        validateStructure(options, indexes);
        return result;
    }

    /**
     * 校验结构。
     */
    private static void validateStructure(JsonNode options, JsonNode indexes) {
        if (!options.isObject() || !indexes.isArray() || indexes.isEmpty() || indexes.size() > 64) {
            throw new IllegalArgumentException("MongoDB collection structure evidence is incomplete");
        }
        options.fieldNames().forEachRemaining(name -> {
            if (!OPTIONS.contains(name)) {
                throw new IllegalArgumentException("MongoDB collection option requires explicit adaptation");
            }
        });
        if (options.has("validator") && !options.path("validator").isObject()
                || options.has("validationLevel") && !Set.of("off", "strict", "moderate")
                .contains(options.path("validationLevel").asText())
                || options.has("validationAction") && !Set.of("error", "warn")
                .contains(options.path("validationAction").asText())) {
            throw new IllegalArgumentException("MongoDB validator options are invalid");
        }
        if (options.has("collation") && !options.path("collation").isObject()
                || options.has("changeStreamPreAndPostImages") && (!options.path("changeStreamPreAndPostImages")
                .isObject()
                || options.path("changeStreamPreAndPostImages").size() != 1 || !options.path(
                "changeStreamPreAndPostImages").path("enabled").isBoolean())) {
            throw new IllegalArgumentException("MongoDB native collection options are invalid");
        }
        try {
            if (options.has("capped") && !options.path("capped").isBoolean()
                    || options.path("capped").asBoolean(false) && (!options.has("size") || MongoReplies.integer(
                    options.path("size")) < 1)
                    || options.has("max") && MongoReplies.integer(options.path("max")) < 1
                    || options.has("expireAfterSeconds") && MongoReplies.integer(
                    options.path("expireAfterSeconds")) < 0) {
                throw new IllegalArgumentException("MongoDB bounded collection options are invalid");
            }
        } catch (SQLException invalid) {
            throw new IllegalArgumentException("MongoDB collection numeric option is invalid");
        }
        if (options.has("clusteredIndex") && (!options.path("clusteredIndex").isObject()
                || !options.path("clusteredIndex").path("unique").asBoolean(false)
                || !options.path("clusteredIndex").path("key").isObject()
                || options.path("clusteredIndex").path("key").size() != 1
                || !options.path("clusteredIndex").path("key").has("_id"))) {
            throw new IllegalArgumentException("MongoDB clustered identity is invalid");
        }
        Set<String> names = new HashSet<>();
        boolean id = false;
        for (var index : indexes) {
            if (!index.isObject()) {
                throw new IllegalArgumentException("MongoDB index evidence is invalid");
            }
            index.fieldNames().forEachRemaining(name -> {
                if (!INDEX_ATTRIBUTES.contains(name)) {
                    throw new IllegalArgumentException("MongoDB index option requires explicit adaptation");
                }
            });
            String name = index.path("name").asText();
            if (name.isBlank() || name.indexOf('\0') >= 0 || name.length() > 255 || !names.add(name)
                    || !index.path("key").isObject() || index.path("key").isEmpty()) {
                throw new IllegalArgumentException("MongoDB index identity or keys are invalid");
            }
            try {
                if (MongoReplies.integer(index.path("v")) != 2) {
                    throw new IllegalArgumentException("MongoDB index version requires explicit adaptation");
                }
                var keyFields = index.path("key").fields();
                while (keyFields.hasNext()) {
                    var key = keyFields.next();
                    if (key.getKey().isBlank() || key.getKey().length() > 1024 || key.getKey().indexOf('\0') >= 0
                            || Arrays.stream(key.getKey().split("\\.", -1))
                            .anyMatch(part -> part.isEmpty() || part.startsWith("$") && !part.equals("$**"))) {
                        throw new IllegalArgumentException("MongoDB native index path is outside its supported scope");
                    }
                    if (key.getValue().isTextual()) {
                        if (!Set.of("hashed", "text", "2d", "2dsphere").contains(key.getValue().textValue())) {
                            throw new IllegalArgumentException("MongoDB index kind requires explicit adaptation");
                        }
                    } else {
                        long direction = MongoReplies.integer(key.getValue());
                        if (direction != 1 && direction != -1) {
                            throw new IllegalArgumentException("MongoDB index kind requires explicit adaptation");
                        }
                    }
                }
                if (index.has("expireAfterSeconds") && MongoReplies.integer(index.path("expireAfterSeconds")) < 0) {
                    throw new IllegalArgumentException("MongoDB TTL index duration is invalid");
                }
            } catch (SQLException invalid) {
                throw new IllegalArgumentException("MongoDB index numeric attribute is invalid");
            }
            for (String booleanOption : List.of("unique", "sparse", "hidden", "clustered")) {
                if (index.has(booleanOption) && !index.path(booleanOption).isBoolean()) {
                    throw new IllegalArgumentException("MongoDB index boolean attribute is invalid");
                }
            }
            if (index.has("partialFilterExpression") && !index.path("partialFilterExpression").isObject()) {
                throw new IllegalArgumentException("MongoDB partial index filter is invalid");
            }
            if (name.equals("_id_")) {
                if (index.path("key").size() != 1 || !index.path("key").has("_id")
                        || index.has("collation") && !sameNativeEvidence(index.path("collation"),
                        options.path("collation"))
                        || index.path("clustered").asBoolean(false) != options.has("clusteredIndex")
                        || index.has("expireAfterSeconds") || index.has("partialFilterExpression") || index.has(
                        "sparse") || index.has("hidden")) {
                    throw new IllegalArgumentException("MongoDB default identity index is required");
                }
                id = true;
            }
        }
        if (!id) {
            throw new IllegalArgumentException("MongoDB identity index evidence is missing");
        }
    }

    /**
     * 读取并校验当前 MongoDB 服务端主版本。
     */
    static int serverMajor(MongoNativeSession session) throws SQLException {
        var build = command(session, "admin", JSON.createObjectNode().put("buildInfo", 1));
        if (!build.path("version").asText().matches("7\\.[0-9]+\\.[0-9]+.*")
                || !build.path("modules").isArray() || !build.path("modules").isEmpty()) {
            throw new SQLException("MongoDB native structure requires community server major 7");
        }
        var hello = command(session, "admin", JSON.createObjectNode().put("hello", 1));
        if ("isdbgrid".equals(hello.path("msg").asText())) {
            throw new SQLException("MongoDB sharded structure requires explicit planning");
        }
        return 7;
    }

    /**
     * 返回当前命令。
     */
    static JsonNode command(MongoNativeSession session, String schema, ObjectNode request) throws SQLException {
        return MongoReplies.success(session, session.command(schema, session.encode(request.toString())));
    }

    /**
     * 按当前值域约定解析或转换UUID 值。
     */
    static void uuid(JsonNode uuid) throws SQLException {
        var binary = uuid.path("$binary");
        try {
            if (!"04".equals(binary.path("subType").asText()) || Base64.getDecoder()
                    .decode(binary.path("base64").asText()).length != 16) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("MongoDB native physical UUID is unavailable");
        }
    }

    /**
     * 写入确认策略。
     */
    static void writeConcern(ObjectNode command, int timeout) {
        command.putObject("writeConcern").put("w", "majority").put("j", true).put("wtimeout", 1000L * timeout);
    }

    /**
     * 校验并取得结构。
     */
    private static void requireStructure(JsonNode actual, JsonNode expected) {
        // BSON 文档顺序对复合索引键和嵌入式校验值具有意义。
        // JsonNode.equals 忽略对象成员顺序，无法在此证明原生等价性。
        if (!sameNativeEvidence(actual.path("options"), expected.path("options"))
                || !sameNativeEvidence(actual.path("indexes"), expected.path("indexes"))) {
            throw new IllegalStateException("MongoDB target native options or indexes disagree with the plan");
        }
    }

    /**
     * 校验并取得新鲜。
     */
    private static void requireFresh(JsonNode previous, JsonNode actual) {
        if (!sameNativeEvidence(previous, actual)) {
            throw new IllegalStateException("MongoDB target identity or structure changed after planning");
        }
    }

    /**
     * 比较两份原生元数据证据的规范表示是否相同。
     */
    private static boolean sameNativeEvidence(JsonNode first, JsonNode second) {
        return first == null || second == null ? first == second : first.toString().equals(second.toString());
    }

    /**
     * 校验当前模式名与对象名符合原生标识符约定。
     */
    private static void names(String schema, String table) {
        MongoNativeMetadata.identifier(schema);
        MongoNativeMetadata.identifier(table);
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
