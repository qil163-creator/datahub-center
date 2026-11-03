package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 原生结构依据；不授予平面数据搬运能力。 */
final class ElasticsearchStructureContract {

    static final String FORMAT = "elasticsearch-index-v2";

    static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Set<String> GENERATED = Set.of("index.creation_date", "index.uuid", "index.provided_name",
            "index.version.created", "index.version.upgraded", "index.resize.source.name", "index.resize.source.uuid",
            "index.routing.allocation.initial_recovery._id");

    record Evidence(ObjectNode body, JsonNode profile, String identity, boolean routingNone) {

    }

    /**
     * 返回定义。
     */
    static Evidence definition(NativeTableDefinition definition, long limit) {
        if (definition == null || definition.product() != ProductTypeEnum.ELASTICSEARCH || !FORMAT.equals(
                definition.format())) {
            throw new IllegalArgumentException("Elasticsearch requires current native structure evidence");
        }
        if (definition.document().getBytes(StandardCharsets.UTF_8).length > limit) {
            throw new IllegalArgumentException("Native structure exceeds the configured byte boundary");
        }
        try {
            JsonNode envelope = JSON.readTree(definition.document());
            if (!(envelope.path("body") instanceof ObjectNode body) || !envelope.path("profile").isObject()
                    || !envelope.path("sourceIdentity").isTextual() || envelope.path("sourceIdentity").asText()
                    .isBlank()) {
                throw new IllegalArgumentException("Native index evidence is incomplete");
            }
            validate(body);
            if (!fields(body).equals(definition.fields())) {
                throw new IllegalArgumentException("Native fields disagree with their mapping evidence");
            }
            return new Evidence(body.deepCopy(), envelope.path("profile"), envelope.path("sourceIdentity").asText(),
                    envelope.path("routingNone").asBoolean(false));
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Invalid native structure document");
        }
    }

    /**
     * 返回请求体。
     */
    static ObjectNode body(JsonNode physical) {
        ObjectNode body = JSON.createObjectNode();
        body.set("mappings", physical.path("mappings").deepCopy());
        ObjectNode settings = JSON.createObjectNode();
        physical.path("settings").fields().forEachRemaining(e -> {
            if (!GENERATED.contains(e.getKey())) {
                settings.set(e.getKey(), e.getValue().deepCopy());
            }
        });
        body.set("settings", settings);
        body.set("aliases",
                physical.path("aliases").isObject() ? physical.path("aliases").deepCopy() : JSON.createObjectNode());
        return body;
    }

    /**
     * 返回字段集合。
     */
    static List<String> fields(ObjectNode body) {
        List<String> names = new ArrayList<>();
        names.add("_id");
        body.path("mappings").path("properties").fieldNames().forEachRemaining(name -> {
            if (name.equals("_id") || name.isBlank() || name.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid native field name");
            }
            names.add(name);
        });
        return List.copyOf(names);
    }

    /**
     * 检查 Elasticsearch 原生结构文档只包含支持的映射、设置及别名定义。
     */
    static void validate(ObjectNode body) {
        if (body.size() != 3 || !body.path("mappings").isObject() || !body.path("settings").isObject() || !body.path(
                "aliases").isObject()
                || body.path("mappings").has("properties") && !body.path("mappings").path("properties").isObject()) {
            throw new IllegalArgumentException("Native index definition is incomplete");
        }
        body.path("aliases").fields().forEachRemaining(e -> {
            ElasticsearchNativeSession.index(e.getKey());
            if (!e.getValue().isObject() || e.getValue().path("is_hidden").asBoolean(false)) {
                throw new IllegalArgumentException("Hidden or invalid aliases require a separate ownership contract");
            }
        });
        body.path("settings").fieldNames().forEachRemaining(name -> {
            if (!name.startsWith("index.") || GENERATED.contains(name) || name.startsWith("index.lifecycle.")
                    || name.equals("index.default_pipeline") || name.equals("index.final_pipeline") || name.startsWith(
                    "index.store.snapshot.")
                    || name.startsWith("index.routing.allocation.") && !name.equals(
                    "index.routing.allocation.include._tier_preference")
                    || name.endsWith("_path") || name.endsWith(".synonyms_set")
                    || name.endsWith(".user_dictionary") || body.path("settings").path(name).asText().equals("hunspell")
                    || name.equals("index.hidden") && body.path("settings").path(name).asText().equals("true")) {
                throw new IllegalArgumentException("Native settings reference external deployment resources");
            }
        });
        external(body);
    }

    /**
     * 拒绝当前原生定义依赖的外部索引或查询能力。
     */
    private static void external(JsonNode node) {
        if (node.path("type").asText().equals("lookup")) {
            throw new IllegalArgumentException("Runtime lookup requires external index ownership");
        }
        if (node.isObject() && node.has("index") && node.has("id") && node.has("path")) {
            throw new IllegalArgumentException("Alias lookup references an external index");
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (e.getKey().equals("_meta")) {
                    return;
                }
                if (e.getKey().equals("script") && e.getValue().isObject() && e.getValue().has("id")
                        || e.getKey().equals("synonyms_path") || e.getKey().equals("synonyms_set")
                        || e.getKey().equals("indexed_shape") || e.getKey().equals("inference_id") || e.getKey()
                        .equals("search_inference_id")) {
                    throw new IllegalArgumentException(
                            "Native structure references an external script, lookup or analyzer resource");
                }
                external(e.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(ElasticsearchStructureContract::external);
        }
    }

    /**
     * 校验原生结构文档中的映射能够按当前数据协议解析。
     */
    static void dataFrame(ObjectNode body) {
        try {
            ElasticsearchMapping.parse(body.path("mappings"));
        } catch (SQLException failure) {
            throw new IllegalArgumentException("Native structure is not a supported flat transfer frame");
        }
        if (references(body.path("mappings"))) {
            throw new IllegalArgumentException("Flat data transfer cannot reproduce native derived or filtered fields");
        }
        if (body.path("settings").path("index.routing_partition_size").asInt(1) != 1
                || body.path("settings").has("index.routing_path")
                || !Set.of("", "standard").contains(body.path("settings").path("index.mode").asText())) {
            throw new IllegalArgumentException("Flat data transfer does not carry custom document routing");
        }
        body.path("settings").fields().forEachRemaining(e -> {
            if (e.getKey().startsWith("index.blocks.") && e.getValue().asText().equals("true")) {
                throw new IllegalArgumentException("Blocked index is structure-only");
            }
        });
    }

    /**
     * 检查映射定义中不存在未支持的外部引用或脚本结构。
     */
    private static boolean references(JsonNode node) {
        if (node.isObject()) {
            if (node.has("copy_to") || node.has("script") || node.has("runtime") || node.has("dynamic_templates")
                    || node.path("type").asText().equals("alias") || node.has("includes") || node.has("excludes")) {
                return true;
            }
            var iterator = node.fields();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (entry.getKey().equals("_meta")) {
                    continue;
                }
                if (entry.getKey().equals("properties") || entry.getKey().equals("fields")) {
                    for (JsonNode field : entry.getValue()) {
                        if (references(field)) {
                            return true;
                        }
                    }
                } else if (references(entry.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) {
                if (references(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 按当前映射定义转换字段、类型或数据结构。
     */
    static ObjectNode map(ObjectNode body, List<String> fields, Map<String, String> mapping) {
        if (mapping == null || !Objects.equals(mapping.get("_id"), "_id") || !fields.containsAll(mapping.keySet())
                || mapping.values().stream().anyMatch(Objects::isNull) || new HashSet<>(
                mapping.values()).size() != mapping.size()) {
            throw new IllegalArgumentException("Native mapping must preserve _id and distinct fields");
        }
        if (mapping.size() == fields.size() && mapping.entrySet().stream()
                .allMatch(e -> e.getKey().equals(e.getValue()))) {
            return body;
        }
        dataFrame(body);
        if (!body.path("aliases").isEmpty()) {
            throw new IllegalArgumentException("Aliases require identity field mapping");
        }
        ObjectNode renamed = JSON.createObjectNode();
        for (String field : fields) {
            if (field.equals("_id") || !mapping.containsKey(field)) {
                continue;
            }
            String name = mapping.get(field);
            if (!name.matches("[A-Za-z][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid target field name");
            }
            renamed.set(name, body.path("mappings").path("properties").get(field).deepCopy());
        }
        ((ObjectNode) body.get("mappings")).set("properties", renamed);
        return body;
    }

    /**
     * 比较实际回读定义与当前计划定义是否完全一致。
     */
    static void exact(ObjectNode actual, ObjectNode expected) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("Native readback differs from the planned definition");
        }
    }

    /**
     * 判断当前类型或结构能否按目标契约兼容处理。
     */
    static void compatible(ObjectNode actual, ObjectNode expected) {
        if (!contains(actual, expected)) {
            throw new IllegalArgumentException("Existing target is incompatible with the native structure");
        }
    }

    /**
     * 检查当前集合或结构是否包含指定对象。
     */
    private static boolean contains(JsonNode actual, JsonNode expected) {
        if (!expected.isObject()) {
            return actual.equals(expected);
        }
        if (!actual.isObject()) {
            return false;
        }
        var fields = expected.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            if (!contains(actual.path(e.getKey()), e.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验本次结构变更只追加定义且保留已有设置及别名。
     */
    static void additive(ObjectNode before, ObjectNode after) {
        if (!before.path("settings").equals(after.path("settings")) || !before.path("aliases")
                .equals(after.path("aliases"))) {
            throw new IllegalArgumentException("Mapping evolution cannot change settings or aliases");
        }
        additiveFields(before.path("mappings"), after.path("mappings"), true);
    }

    /**
     * 递归检查已有字段映射属性未被追加操作修改。
     */
    private static void additiveFields(JsonNode before, JsonNode after, boolean root) {
        if (!before.isObject() || !after.isObject()) {
            if (!before.equals(after)) {
                throw new IllegalArgumentException("Existing mapping attributes are immutable");
            }
            return;
        }
        var attributes = before.fields();
        while (attributes.hasNext()) {
            var e = attributes.next();
            String name = e.getKey();
            if (!after.has(name)) {
                throw new IllegalArgumentException("Mapping evolution cannot remove an attribute");
            }
            if (name.equals("properties") || !root && name.equals("fields")) {
                if (!after.path(name).isObject()) {
                    throw new IllegalArgumentException("Invalid additive field mapping");
                }
                e.getValue().fields().forEachRemaining(f -> {
                    if (!after.path(name).has(f.getKey())) {
                        throw new IllegalArgumentException("Mapping evolution cannot remove a field");
                    }
                    additiveFields(f.getValue(), after.path(name).path(f.getKey()), false);
                });
            } else if (!e.getValue().equals(after.path(name))) {
                throw new IllegalArgumentException("Mapping evolution cannot alter an existing attribute");
            }
        }
        after.fieldNames().forEachRemaining(name -> {
            if (!before.has(name) && !(name.equals("properties") || !root && name.equals("fields"))) {
                throw new IllegalArgumentException("Mapping evolution only adds properties or multi-fields");
            }
        });
    }

    /**
     * 将值整理为当前协议使用的规范形式。
     */
    static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            value.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
            sorted.forEach((key, node) -> result.set(key, canonical(node)));
            return result;
        }
        if (value.isArray()) {
            var result = JSON.createArrayNode();
            value.forEach(node -> result.add(canonical(node)));
            return result;
        }
        return value;
    }

    /**
     * 根据当前内容计算结构指纹，供一致性比较使用。
     */
    static String fingerprint(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(value).toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
