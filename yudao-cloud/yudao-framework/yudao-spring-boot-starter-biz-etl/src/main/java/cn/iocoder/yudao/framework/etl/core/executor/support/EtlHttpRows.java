package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.script.EtlScriptAuthenticatedHttp;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/** Java17工作节点的有界HTTP行读取；地址授权来自执行环境，不跟随重定向。 */
public final class EtlHttpRows {
    private EtlHttpRows() { }

    public static String endpoint(String address, Set<String> allowed) {
        URI uri;
        try { uri = URI.create(address); } catch (RuntimeException failure) { throw invalid("接口地址无效"); }
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null || !allowed.contains(uri.toASCIIString()))
            throw invalid("接口地址未获工作节点授权或包含禁止的地址属性");
        return uri.toASCIIString();
    }

    public static void read(JSONObject plan, Set<String> allowed, Consumer<String> receiver) {
        read(plan, allowed, reference -> { throw invalid("执行环境未提供接口秘密适配"); }, receiver);
    }

    public static void read(JSONObject plan, Set<String> allowed, Function<String, String> secrets, Consumer<String> receiver) {
        read(plan, allowed, secrets, null, receiver);
    }

    /** 仅取首个有界响应中的前32行供控制面字段推导，不将此采样入口用于任务执行。调用方须先完成原生编译准入。 */
    public static List<String> sampleRows(JSONObject plan, Set<String> allowed) {
        JSONObject api = plan.getJSONObject("apiConfig");
        if (api == null || !"GET".equals(api.getStr("requestMethod")) || !"none".equals(api.getStr("bodyType", "none"))
                || !api.getStr("bodyRaw", "").isBlank() || plan.getJSONObject("headerSecretRefs") != null && !plan.getJSONObject("headerSecretRefs").isEmpty()
                || plan.getJSONObject("headerContextRefs") != null && !plan.getJSONObject("headerContextRefs").isEmpty())
            throw invalid("字段采样仅支持无请求体且无需认证的GET请求");
        var rows = new ArrayList<String>();
        read(plan, allowed, reference -> { throw invalid("字段采样不读取运行凭据"); }, null,
                row -> { if (rows.size() < 32) rows.add(row); }, true);
        return List.copyOf(rows);
    }

    public static void read(JSONObject plan, Set<String> allowed, Function<String, String> secrets, String runtimeDescriptor, Consumer<String> receiver) {
        read(plan, allowed, secrets, runtimeDescriptor, receiver, false);
    }

    private static void read(JSONObject plan, Set<String> allowed, Function<String, String> secrets, String runtimeDescriptor, Consumer<String> receiver, boolean sample) {
        JSONObject api = plan.getJSONObject("apiConfig");
        String address = endpoint(api.getStr("requestUrl"), allowed);
        int size = plan.getInt("fetchSize", 500), pages = plan.getInt("maxPages", 10), bytes = plan.getInt("maxResponseBytes", 8388608);
        int rowsLimit = plan.getInt("maxRows", 100000);
        if (size < 1 || size > 10000 || pages < 1 || pages > 1000 || bytes < 1 || bytes > 16777216 || rowsLimit < 1 || rowsLimit > 1000000)
            throw invalid("接口读取预算无效");
        boolean paged = api.getInt("ifPage", 0) == 1;
        int read = 0;
        for (int page = 1; page <= pages; page++) {
            if (Thread.currentThread().isInterrupted()) throw invalid("接口读取已取消");
            var query = parameters(api.getJSONArray("requestParam"));
            var form = parameters(api.getJSONArray("bodyFormData"));
            var encoded = parameters(api.getJSONArray("bodyUrlencoded"));
            String raw = api.getStr("bodyRaw", "");
            if (paged) {
                for (Map<String, String> values : List.of(query, form, encoded)) {
                    values.put(api.getStr("pageKey"), String.valueOf(page)); values.put(api.getStr("pageSizeKey"), String.valueOf(size));
                }
                if ("raw".equals(api.getStr("bodyType"))) raw = JSONUtil.parseObj(raw.isBlank() ? "{}" : raw)
                        .set(api.getStr("pageKey"), page).set(api.getStr("pageSizeKey"), size).toString();
            }
            var requestHeaders = new LinkedHashMap<String, String>();
            String body = "";
            String method = api.getStr("requestMethod", "GET");
            if ("POST".equals(method)) {
                String bodyType = api.getStr("bodyType", "none");
                body = switch (bodyType) {
                    case "none" -> "";
                    case "raw" -> { requestHeaders.put("Content-Type", "application/json; charset=UTF-8"); yield raw; }
                    case "urlencoded" -> { requestHeaders.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8"); yield form(encoded); }
                    case "form-data" -> {
                        String boundary = "datahub_" + UUID.randomUUID().toString().replace("-", "");
                        requestHeaders.put("Content-Type", "multipart/form-data; boundary=" + boundary);
                        var bodyText = new StringBuilder();
                        for (var entry : form.entrySet()) {
                            if (!entry.getKey().matches("[A-Za-z0-9_.-]{1,128}")) throw invalid("表单参数名无效");
                            bodyText.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"")
                                    .append(entry.getKey()).append("\"\r\n\r\n").append(entry.getValue()).append("\r\n");
                        }
                        yield bodyText.append("--").append(boundary).append("--\r\n").toString();
                    }
                    default -> throw invalid("接口请求体类型无效");
                };
                if (body.getBytes(StandardCharsets.UTF_8).length > bytes) throw invalid("接口请求体超过预算");
            } else if (!"GET".equals(method)) throw invalid("接口请求方式无效");
            parameters(api.getJSONArray("requestHeader")).forEach((name, value) -> {
                if (Set.of("host", "connection", "content-length", "transfer-encoding", "upgrade", "cookie", "authorization", "proxy-authorization", "x-api-key").contains(name.toLowerCase(Locale.ROOT)))
                    throw invalid("敏感或协议请求头必须通过受管适配器提供");
                requestHeaders.put(name, value);
            });
            JSONObject references = plan.getJSONObject("headerSecretRefs");
            if (references != null) references.forEach((name, reference) -> {
                if (!name.matches("[A-Za-z][A-Za-z0-9-]{0,99}") || Set.of("host", "connection", "content-length", "transfer-encoding", "upgrade").contains(name.toLowerCase(Locale.ROOT)))
                    throw invalid("秘密请求头名称无效");
                requestHeaders.put(name, secrets.apply(String.valueOf(reference)));
            });
            EtlManagedHttp.Response response;
            var contextRefs = plan.getJSONObject("headerContextRefs");
            if (contextRefs == null || contextRefs.isEmpty()) response = EtlManagedHttp.exchange(address, allowed, method, query, requestHeaders, body.getBytes(StandardCharsets.UTF_8), bytes);
            else {
                var protectedHeaders = new LinkedHashMap<String, EtlScriptAuthenticatedHttp.HeaderReference>();
                contextRefs.forEach((name, value) -> {
                    var reference = JSONUtil.parseObj(value);
                    protectedHeaders.put(name, new EtlScriptAuthenticatedHttp.HeaderReference(reference.getStr("tokenKey"), reference.getStr("prefix", "")));
                });
                var request = new EtlScriptAuthenticatedHttp.Request(plan.getStr("resourceBindingId", ""), address, method, query, requestHeaders, body.getBytes(StandardCharsets.UTF_8), bytes, 30_000);
                var authenticated = EtlScriptAuthenticatedHttp.exchange(runtimeDescriptor, request, protectedHeaders);
                if (authenticated.status() < 200 || authenticated.status() >= 300) throw invalid("接口认证代理返回异常状态");
                response = new EtlManagedHttp.Response(authenticated.body(), authenticated.contentType());
            }
            String text;
            try {
                String charset = "UTF-8";
                for (String parameter : response.contentType().split(";")) if (parameter.strip().toLowerCase(Locale.ROOT).startsWith("charset="))
                    charset = parameter.strip().substring(8).replace("\"", "").toUpperCase(Locale.ROOT);
                if (!Set.of("UTF-8", "GBK", "GB2312", "ISO-8859-1").contains(charset)) throw invalid("接口字符编码未支持");
                text = Charset.forName(charset).newDecoder().decode(java.nio.ByteBuffer.wrap(response.bytes())).toString();
            } catch (Exception failure) { throw invalid("接口字符编码无效"); }
            Object root;
            try { root = JSONUtil.parse(text, cn.hutool.json.JSONConfig.create().setCheckDuplicate(true).setIgnoreError(false).setIgnoreNullValue(false).setStripTrailingZeros(false)); }
            catch (RuntimeException failure) { throw invalid("接口响应不是有效JSON或包含重复字段"); }
            var rows = new ArrayList<JSONObject>();
            for (String path : dataPaths(api)) {
                Object data = resolve(root, path);
                if (api.getInt("dataType", 2) == 1) {
                    if (data instanceof JSONObject object) rows.add(object); else throw invalid("接口单对象响应形状不符");
                } else if (data instanceof JSONArray array) {
                    for (Object value : array) {
                        if (!(value instanceof JSONObject object)) throw invalid("接口集合必须仅包含对象");
                        rows.add(object);
                    }
                } else throw invalid("接口响应集合节点必须为数组，不能把缺失字段或单对象当作空结果");
            }
            for (JSONObject row : rows) {
                if (++read > rowsLimit) throw invalid("接口行数超过读取预算");
                receiver.accept(EtlNativeRowGuard.normalizeDeclared(row.toString(), plan));
            }
            if (sample || !paged || rows.size() < size) return;
            if (page == pages) throw invalid("接口分页未在最大页数内结束，拒绝截断结果");
        }
    }

    /** 多路径按声明顺序拼接；相同路径拒绝重复，旧的单路径配置仍保持兼容。 */
    public static List<String> dataPaths(JSONObject api) {
        var configured = api.getJSONArray("dataKeys");
        var paths = new ArrayList<String>();
        if (configured == null || configured.isEmpty()) paths.add(api.getStr("dataKey", ""));
        else {
            if (configured.size() > 32) throw invalid("接口数据路径超过预算");
            for (Object value : configured) {
                if (!(value instanceof String path)) throw invalid("接口数据路径必须为字符串");
                paths.add(path);
            }
        }
        var unique = new java.util.HashSet<String>();
        for (String path : paths) {
            String clean = path.startsWith("$.") ? path.substring(2) : path;
            if (path.length() > 1024 || !(clean.isBlank() || clean.equals("$") || clean.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*"))) throw invalid("接口数据路径无效");
            String key = clean.isBlank() || clean.equals("$") ? "$" : clean;
            if (!unique.add(key)) throw invalid("接口数据路径不能重复");
        }
        return List.copyOf(paths);
    }

    static Object resolve(Object node, String path) {
        if (path.isBlank() || "$".equals(path)) return node;
        String clean = path.startsWith("$.") ? path.substring(2) : path;
        if (!clean.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) throw invalid("接口数据路径无效");
        if (!clean.contains(".") && !path.startsWith("$.")) {
            if (node instanceof JSONObject object && object.containsKey(clean)) return object.get(clean);
            var found = new ArrayList<Object>(); search(node, clean, 0, found);
            if (found.size() != 1) throw invalid("接口数据字段缺失或匹配不唯一，请填写完整数据路径");
            return found.get(0);
        }
        Object current = node;
        for (String name : clean.split("\\.")) current = current instanceof JSONObject object ? object.get(name) : null;
        return current;
    }

    private static void search(Object node, String name, int depth, List<Object> found) {
        if (depth > 64) throw invalid("接口JSON嵌套超过预算");
        if (node instanceof JSONObject object) {
            if (object.containsKey(name)) found.add(object.get(name));
            for (Object child : object.values()) search(child, name, depth + 1, found);
        } else if (node instanceof JSONArray array) for (Object child : array) search(child, name, depth + 1, found);
    }

    private static Map<String, String> parameters(JSONArray rows) {
        var result = new LinkedHashMap<String, String>();
        if (rows != null) for (Object row : rows) {
            var value = JSONUtil.parseObj(row); String name = value.getStr("name", "");
            if (name.isBlank() || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0 || result.containsKey(name)) throw invalid("接口参数名无效或重复");
            String text = value.getStr("value", "");
            if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) throw invalid("接口参数不接受换行符");
            result.put(name, text);
        }
        return result;
    }

    private static String form(Map<String, String> values) {
        return String.join("&", values.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).toList());
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
