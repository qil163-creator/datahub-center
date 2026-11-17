package cn.iocoder.yudao.framework.common.util.json;

import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 在日志边界移除凭据，转发及业务处理仍使用原始输入。 */
public final class LogSanitizer {
    private static final Set<String> SECRET_KEYS = Set.of("password", "token", "accesstoken", "refreshtoken",
            "authorization", "proxyauthorization", "cookie", "setcookie", "clientsecret", "apikey",
            "xapikey", "secret", "appsecret", "plainsecret", "xsign");

    private LogSanitizer() {}

    private static boolean isSensitive(String key, String[] extraKeys) {
        String normalized = key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return SECRET_KEYS.contains(normalized) || (extraKeys != null && Arrays.asList(extraKeys).contains(key));
    }

    public static Map<String, String> sanitizeQuery(Map<String, String> query, String[] extraKeys) {
        if (query == null || query.isEmpty()) return query;
        Map<String, String> copy = new LinkedHashMap<>(query);
        copy.keySet().removeIf(key -> isSensitive(key, extraKeys));
        return copy;
    }

    /** 无法解析的请求体不能安全脱敏，省略原文及可能包含输入片段的解析异常。 */
    public static String sanitizeJson(String json, String[] extraKeys) {
        JsonNode tree = sanitizeTree(json, extraKeys);
        return tree == null ? null : JsonUtils.toJsonString(tree);
    }

    public static JsonNode sanitizeTree(String json, String[] extraKeys) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode tree = JsonUtils.getObjectMapper().readTree(json);
            removeSecrets(tree, extraKeys);
            return tree;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void removeSecrets(JsonNode node, String[] extraKeys) {
        if (node.isArray()) {
            node.forEach(child -> removeSecrets(child, extraKeys));
        } else if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (isSensitive(field.getKey(), extraKeys)) {
                    fields.remove();
                } else {
                    removeSecrets(field.getValue(), extraKeys);
                }
            }
        }
    }

}
