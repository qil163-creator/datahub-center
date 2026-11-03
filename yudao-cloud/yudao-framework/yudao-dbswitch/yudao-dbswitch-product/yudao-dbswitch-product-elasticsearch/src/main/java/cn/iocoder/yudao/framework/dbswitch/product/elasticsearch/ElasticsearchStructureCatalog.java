package cn.iocoder.yudao.framework.dbswitch.product.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.SQLException;
import java.util.*;

import static cn.iocoder.yudao.framework.dbswitch.product.elasticsearch.ElasticsearchStructureContract.*;

/** 所有目录调用沿用调用方连接、响应容量与超时限制，不创建管理端旁路。 */
final class ElasticsearchStructureCatalog {

    /**
     * 初始化 {@code ElasticsearchStructureCatalog}。
     */
    private ElasticsearchStructureCatalog() {
    }

    /**
     * 检查 Elasticsearch 索引中不存在自定义路由的数据。
     */
    static boolean routingNone(ElasticsearchNativeSession session, String index) {
        try {
            JsonNode result = session.request("POST", "/" + index + "/_count",
                    "{\"query\":{\"exists\":{\"field\":\"_routing\"}}}").requireSuccess();
            JsonNode shards = result.path("_shards");
            return result.path("count").isIntegralNumber() && result.path("count").bigIntegerValue().signum() == 0
                    && shards.path("failed").isIntegralNumber() && shards.path("failed").longValue() == 0
                    && shards.path("total").isIntegralNumber() && shards.path("total").longValue() > 0
                    && shards.path("successful").equals(shards.path("total"));
        } catch (SQLException unavailable) {
            return false;
        }
    }

    /**
     * 返回当前配置档案。
     */
    static ObjectNode profile(ElasticsearchNativeSession session) throws SQLException {
        JsonNode info = session.request("GET", "/", null).requireSuccess();
        String version = info.path("version").path("number").asText();
        if (!version.matches("[78]\\.[0-9]+\\.[0-9]+")) {
            throw new SQLException("Native structure requires a released Elasticsearch 7 or 8 server");
        }
        JsonNode nodes = session.request("GET", "/_nodes/plugins?filter_path=nodes.*.plugins", null).requireSuccess()
                .path("nodes");
        if (!nodes.isObject() || nodes.isEmpty()) {
            throw new SQLException("Plugin deployment evidence is unavailable");
        }
        String plugins = null;
        for (JsonNode node : nodes) {
            if (!node.path("plugins").isArray()) {
                throw new SQLException("Plugin evidence is incomplete");
            }
            List<String> entries = new ArrayList<>();
            node.path("plugins").forEach(p -> entries.add(canonical(p).toString()));
            Collections.sort(entries);
            String value = entries.toString();
            if (plugins != null && !plugins.equals(value)) {
                throw new SQLException("Heterogeneous plugin deployments require explicit mapping");
            }
            plugins = value;
        }
        return JSON.createObjectNode().put("version", version).put("plugins", plugins);
    }

    /**
     * 检查目标、暂存及备份索引所处的模板与部署约束。
     */
    static void deployment(ElasticsearchNativeSession session, String target, String stage, String backup,
            ObjectNode body) throws SQLException {
        Set<String> names = Set.of(target, stage, backup);
        JsonNode modern = session.request("GET",
                "/_index_template?filter_path=index_templates.index_template.index_patterns", null).requireSuccess();
        if (!modern.isObject() || modern.has("index_templates") && !modern.path("index_templates").isArray()) {
            throw new SQLException("Invalid template evidence");
        }
        for (JsonNode template : modern.path("index_templates")) {
            rejectMatching(names, template.path("index_template").path("index_patterns"));
        }
        JsonNode legacy = session.request("GET", "/_template?filter_path=*.index_patterns,*.template", null)
                .requireSuccess();
        if (!legacy.isObject()) {
            throw new SQLException("Invalid legacy template evidence");
        }
        for (JsonNode template : legacy) {
            if (template.has("index_patterns")) {
                rejectMatching(names, template.path("index_patterns"));
            } else if (template.path("template").isTextual()) {
                rejectMatching(names, JSON.createArrayNode().add(template.path("template").asText()));
            } else {
                throw new SQLException("Invalid legacy template evidence");
            }
        }
        var aliases = body.path("aliases").fieldNames();
        while (aliases.hasNext()) {
            String alias = aliases.next();
            if (alias.equals(target)) {
                throw new IllegalArgumentException("Index and alias names must differ");
            }
            var existing = session.request("GET", "/_alias/" + alias, null);
            if (existing.status() != 404) {
                JsonNode owners = existing.requireSuccess();
                if (owners.size() != 1 || !owners.has(target)) {
                    throw new IllegalArgumentException("Alias is owned by another physical index");
                }
            }
            var physical = session.request("GET", "/" + alias + "?flat_settings=true", null);
            if (physical.status() != 404 && physical.requireSuccess().has(alias)) {
                throw new IllegalArgumentException("Alias name is an existing physical index");
            }
        }
    }

    /**
     * 拒绝会匹配目标名称的外部索引模板。
     */
    private static void rejectMatching(Set<String> names, JsonNode patterns) throws SQLException {
        if (!patterns.isArray() || patterns.isEmpty()) {
            throw new SQLException("Template patterns are unavailable");
        }
        for (JsonNode item : patterns) {
            if (!item.isTextual()) {
                throw new SQLException("Invalid template pattern");
            }
            if (item.asText().length() > 1024) {
                throw new SQLException("Template pattern exceeds the native planning boundary");
            }
            StringBuilder regex = new StringBuilder();
            for (char character : item.asText().toCharArray()) {
                if (character == '*') {
                    regex.append(".*");
                } else if (character == '?') {
                    regex.append('.');
                } else {
                    regex.append(java.util.regex.Pattern.quote(String.valueOf(character)));
                }
            }
            for (String name : names) {
                if (name.matches(regex.toString())) {
                    throw new IllegalArgumentException("Target or recovery name matches an external template");
                }
            }
        }
    }

    /**
     * 读取当前目标对象或运行状态的最新事实。
     */
    static JsonNode observe(ElasticsearchNativeSession session, String index, boolean absent) throws SQLException {
        var response = session.request("GET", "/" + index + "?flat_settings=true", null);
        if (response.status() == 404 && absent) {
            return null;
        }
        JsonNode json = response.requireSuccess();
        if (json.size() != 1 || !json.has(index) || !json.path(index).path("mappings").isObject()
                || !json.path(index).path("settings").isObject()) {
            throw new SQLException("One ordinary concrete physical index is required");
        }
        JsonNode value = json.get(index);
        identity(value);
        if (value.path("data_stream").isTextual() || value.path("settings").path("index.hidden").asText()
                .equals("true")) {
            throw new SQLException("Data streams and hidden indices require separate ownership");
        }
        JsonNode resolved = session.request("GET", "/_resolve/index/" + index + "?expand_wildcards=all", null)
                .requireSuccess();
        if (resolved.path("indices").size() != 1 || !resolved.path("indices").get(0).path("name").asText().equals(index)
                || !resolved.path("indices").get(0).path("attributes").isArray()
                || !resolved.path("aliases").isEmpty() || !resolved.path("data_streams").isEmpty()) {
            throw new SQLException("Native structure requires one resolved ordinary index");
        }
        Set<String> attributes = new HashSet<>();
        resolved.path("indices").get(0).path("attributes").forEach(a -> attributes.add(a.asText()));
        if (!attributes.contains("open") || attributes.contains("closed") || attributes.contains(
                "hidden") || attributes.contains("system")
                || resolved.path("indices").get(0).has("data_stream")) {
            throw new SQLException("Closed, system and data-stream indices need separate structure contracts");
        }
        return value;
    }

    /**
     * 重新观测目标结构并核对其指纹仍与预期一致。
     */
    static void fresh(ElasticsearchNativeSession session, String index, String expected) throws SQLException {
        JsonNode now = observe(session, index, true);
        if (!Objects.equals(expected, now == null ? null : fingerprint(now))) {
            throw new IllegalStateException("Physical target changed after planning");
        }
    }

    /**
     * 检查原索引在添加写阻塞后未发生其他结构变化。
     */
    static void blockedOriginal(JsonNode before, JsonNode after) throws SQLException {
        ObjectNode expected = before.deepCopy();
        ((ObjectNode) expected.path("settings")).put("index.blocks.write", "true");
        if (!expected.equals(after)) {
            throw new SQLException("Target changed while obtaining its recovery clone");
        }
    }

    /**
     * 返回身份信息。
     */
    static String identity(JsonNode physical) {
        String id = physical.path("settings").path("index.uuid").asText();
        if (id.isBlank()) {
            throw new IllegalArgumentException("Physical UUID is unavailable");
        }
        return id;
    }

    /**
     * 要求 Elasticsearch DDL 响应明确确认成功。
     */
    static void ack(JsonNode result) throws SQLException {
        if (!result.path("acknowledged").asBoolean(false)) {
            throw new SQLException("DDL acknowledgement is unavailable");
        }
    }

    /**
     * 检查 Elasticsearch 分片响应未报告失败。
     */
    static void shards(JsonNode result) throws SQLException {
        if (!result.path("_shards").path("failed").isIntegralNumber() || result.path("_shards").path("failed")
                .intValue() != 0) {
            throw new SQLException("Not every shard operation was confirmed");
        }
    }

    /**
     * 返回数量。
     */
    static long count(ElasticsearchNativeSession session, String name) throws SQLException {
        JsonNode result = session.request("GET", "/" + name + "/_count", null).requireSuccess();
        shards(result);
        if (!result.path("count").isIntegralNumber() || !result.path("count").canConvertToLong() || result.path("count")
                .longValue() < 0) {
            throw new SQLException("Recovery document count is unavailable");
        }
        return result.path("count").longValue();
    }
}
