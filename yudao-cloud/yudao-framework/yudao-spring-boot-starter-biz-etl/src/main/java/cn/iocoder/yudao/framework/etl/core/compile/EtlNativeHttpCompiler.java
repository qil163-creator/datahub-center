package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlHttpRows;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将独立API配置编译为受管Spark行源，不在平台服务内替代原生引擎执行。 */
public final class EtlNativeHttpCompiler {
    private static final Set<String> API_KEYS = Set.of("apiName", "requestUrl", "requestMethod", "requestHeader", "requestParam",
            "bodyType", "bodyFormData", "bodyUrlencoded", "bodyRaw", "dataType", "dataColumns", "description", "ifPage", "pageKey", "pageSizeKey", "dataKey", "dataKeys");
    private EtlNativeHttpCompiler() { }

    public static Set<String> supportedCodes(EtlNativeSqlCompiler.Engine engine, boolean streaming) {
        return engine != null && !streaming ? Set.of("rest-api", "read-webservice") : Set.of();
    }

    public static EtlNativeConnectorCompiler.TablePlan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming,
            String identity, String code, Map<String, Object> values, EtlDataxConnectorCompiler.Binding binding,
            Set<String> managedFunctions, Set<String> allowedSecretRefs) {
        return compile(engine, streaming, identity, code, values, binding, managedFunctions, allowedSecretRefs, Set.of());
    }

    public static EtlNativeConnectorCompiler.TablePlan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming,
            String identity, String code, Map<String, Object> values, EtlDataxConnectorCompiler.Binding binding,
            Set<String> managedFunctions, Set<String> allowedSecretRefs, Set<String> allowedContextKeys) {
        if ("read-webservice".equals(code)) return EtlNativeWebServiceCompiler.compile(engine, streaming, identity, code, values, binding, managedFunctions, allowedSecretRefs);
        if (!supportedCodes(engine, streaming).contains(code)) throw invalid("接口行源当前仅支持有界批处理");
        if (binding == null || !binding.connectorCodes().contains(code) || !Set.of(EtlDataxConnectorCompiler.Authentication.NONE, EtlDataxConnectorCompiler.Authentication.REQUIRED).contains(binding.authentication()))
            throw invalid("接口行源需要当前任务获授权的执行资源绑定");
        if (engine == EtlNativeSqlCompiler.Engine.SPARK ? !binding.installedPlugins().contains("spark-http") || !managedFunctions.contains("DH_READ_API") : !binding.installedPlugins().contains("flink-bounded-http"))
            throw invalid("工作环境尚未安装并注册受管接口读取函数");
        JSONObject config = JSONUtil.parseObj(values);
        rejectUnknown(config.keySet(), Set.of("apiConfig", "fetchSize", "maxPages", "maxRows", "maxResponseBytes", "resourceBindingId", "headerSecretRefs", "headerContextRefs"), "接口节点");
        JSONObject references = config.getJSONObject("headerSecretRefs");
        if (references == null) references = JSONUtil.createObj();
        JSONObject contextReferences = config.getJSONObject("headerContextRefs");
        if (contextReferences == null) contextReferences = JSONUtil.createObj();
        if (references.size() + contextReferences.size() > 32 || binding.authentication() == EtlDataxConnectorCompiler.Authentication.REQUIRED && references.isEmpty() && contextReferences.isEmpty()
                || binding.authentication() == EtlDataxConnectorCompiler.Authentication.NONE && (!references.isEmpty() || !contextReferences.isEmpty()))
            throw invalid("接口秘密请求头与管理员资源认证声明不一致");
        var authenticationHeaders = new java.util.HashSet<String>();
        for (var entry : references.entrySet()) {
            String name = entry.getKey(), reference = String.valueOf(entry.getValue());
            if (!name.matches("[A-Za-z][A-Za-z0-9-]{0,99}") || Set.of("host", "connection", "content-length", "transfer-encoding", "upgrade").contains(name.toLowerCase(java.util.Locale.ROOT))
                    || !reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || !allowedSecretRefs.contains(reference) || !authenticationHeaders.add(name.toLowerCase(java.util.Locale.ROOT)))
                throw invalid("当前任务未获授权使用接口秘密引用或请求头名称无效");
        }
        for (var entry : contextReferences.entrySet()) {
            String name = entry.getKey();
            if (!(entry.getValue() instanceof JSONObject reference)) throw invalid("接口认证变量必须使用结构化引用");
            rejectUnknown(reference.keySet(), Set.of("tokenKey", "prefix"), "接口认证变量");
            String key = reference.getStr("tokenKey", ""), prefix = reference.getStr("prefix", "");
            if (!name.matches("[A-Za-z][A-Za-z0-9-]{0,99}") || Set.of("host", "connection", "content-length", "transfer-encoding", "upgrade").contains(name.toLowerCase(java.util.Locale.ROOT))
                    || !authenticationHeaders.add(name.toLowerCase(java.util.Locale.ROOT)) || !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || !allowedContextKeys.contains(key)
                    || !(prefix.isEmpty() || prefix.matches("[A-Za-z][A-Za-z0-9_-]{0,31} ")))
                throw invalid("接口认证变量未获上游脚本阶段授权、请求头冲突或认证前缀无效");
        }
        if (!contextReferences.isEmpty() && !config.getStr("resourceBindingId", "").matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))
            throw invalid("接口认证变量需要明确的受管资源编号");
        JSONObject api = config.getJSONObject("apiConfig");
        if (api == null || JSONUtil.toJsonStr(api).length() > 500000) throw invalid("接口配置缺失或超过预算");
        rejectUnknown(api.keySet(), API_KEYS, "接口配置");
        EtlHttpRows.endpoint(api.getStr("requestUrl", ""), Set.copyOf(binding.addresses()));
        String query = java.net.URI.create(api.getStr("requestUrl")).getRawQuery();
        if (query != null) for (String parameter : query.split("&")) {
            String name = java.net.URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8);
            if (sensitive(name)) throw invalid("接口地址不得包含明文认证参数，请使用受管秘密请求头");
        }
        if (!Set.of("GET", "POST").contains(api.getStr("requestMethod", ""))) throw invalid("接口方式必须为GET或POST");
        if (!Set.of("none", "raw", "urlencoded", "form-data").contains(api.getStr("bodyType", "none"))) throw invalid("接口请求体类型无效");
        EtlHttpRows.dataPaths(api);
        int paged = integer(api.get("ifPage"), 0, 0, 1);
        integer(api.get("dataType"), 2, 1, 2);
        if (paged == 1) {
            String key = api.getStr("pageKey", ""), sizeKey = api.getStr("pageSizeKey", "");
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || !sizeKey.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || key.equals(sizeKey))
                throw invalid("分页必须配置不同的页码和每页条数参数名");
        }
        for (String key : List.of("requestHeader", "requestParam", "bodyFormData", "bodyUrlencoded")) {
            var entries = api.getJSONArray(key); var names = new java.util.HashSet<String>();
            if (entries == null) continue;
            if (entries.size() > 256) throw invalid("接口参数数量超过预算");
            for (Object entry : entries) {
                var parameter = JSONUtil.parseObj(entry);
                rejectUnknown(parameter.keySet(), Set.of("name", "value", "description"), "接口参数");
                String name = parameter.getStr("name", "");
                if (!name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}") || !names.add(name)) throw invalid("接口参数名无效或重复");
                if (sensitive(name))
                    throw invalid("接口认证参数需要受管秘密适配，不得写入任务配置");
                if (key.equals("requestHeader") && authenticationHeaders.contains(name.toLowerCase(java.util.Locale.ROOT))) throw invalid("普通请求头与认证引用冲突");
                String value = parameter.getStr("value", "");
                if (value.length() > 65536 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw invalid("接口参数值无效或超限");
            }
        }
        String bodyType = api.getStr("bodyType", "none");
        if ("raw".equals(bodyType)) {
            try {
                Object body = paged == 1 ? JSONUtil.parseObj(api.getStr("bodyRaw", "{}")) : JSONUtil.parse(api.getStr("bodyRaw", "{}"));
                rejectBodySecrets(body, 0);
            }
            catch (RuntimeException failure) { throw invalid("接口JSON请求体无效，分页请求体必须为对象"); }
        }
        if ("GET".equals(api.getStr("requestMethod")) && (!"none".equals(bodyType) || !api.getStr("bodyRaw", "").isBlank()))
            throw invalid("GET接口不接受请求体");
        var plan = JSONUtil.createObj().set("apiConfig", api).set("headerSecretRefs", references).set("headerContextRefs", contextReferences)
                .set("resourceBindingId", config.getStr("resourceBindingId", "")).set("fetchSize", integer(config.get("fetchSize"), 500, 1, 10000))
                .set("maxPages", integer(config.get("maxPages"), 10, 1, 1000)).set("maxRows", integer(config.get("maxRows"), 100000, 1, 1000000))
                .set("maxResponseBytes", integer(config.get("maxResponseBytes"), 8388608, 1, 16777216));
        return rowSource(engine, identity, api.getJSONArray("dataColumns"), plan, "DH_READ_API", "api");
    }

    static EtlNativeConnectorCompiler.TablePlan rowSource(EtlNativeSqlCompiler.Engine engine, String identity, cn.hutool.json.JSONArray columns,
            JSONObject plan, String function, String kind) {
        var validated = rowSource(identity, columns, plan, function, kind);
        return engine == EtlNativeSqlCompiler.Engine.SPARK ? validated : EtlNativeFlinkConnectorSql.table(identity, kind, false, validated.table().columns(), plan, List.of());
    }

    static EtlNativeConnectorCompiler.TablePlan rowSource(String identity, cn.hutool.json.JSONArray columns,
            JSONObject plan, String function, String kind) {
        if (columns == null || columns.isEmpty() || columns.size() > 1024) throw invalid("请声明1至1024个API返回字段");
        var types = new LinkedHashMap<String, String>(); var projections = new java.util.ArrayList<String>(); var guards = JSONUtil.createObj();
        for (Object entry : columns) {
            var field = JSONUtil.parseObj(entry);
            rejectUnknown(field.keySet(), Set.of("fieldName", "fieldType", "displaySize", "precisionSize", "nullable", "pk", "description", "format", "defaultValue"), "接口返回字段");
            String name = field.getStr("fieldName", "");
            if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,127}") || types.containsKey(name)) throw invalid("接口字段名称无效或重复");
            int jdbcType = integer(field.get("fieldType"), 12, -10, 100);
            String type = switch (jdbcType) {
                case -5 -> "BIGINT"; case 4 -> "INT"; case 8, 6 -> "DOUBLE"; case 12, 1 -> "STRING";
                case 16, -7 -> "BOOLEAN"; case 91 -> "DATE"; case 93 -> "TIMESTAMP";
                case 2, 3 -> "DECIMAL(" + integer(field.get("displaySize"), 38, 1, 38) + "," + integer(field.get("precisionSize"), 0, 0, 38) + ")";
                default -> throw invalid("接口字段类型尚未适配");
            };
            int stringLength = type.equals("STRING") ? integer(field.get("displaySize"), 0, 0, 1048576) : 0;
            String raw = "GET_JSON_OBJECT(`dh_api_json`, '$." + name + "')";
            if (type.startsWith("DECIMAL(")) {
                String[] shape = type.substring(8, type.length() - 1).split(",");
                if (Integer.parseInt(shape[1]) > Integer.parseInt(shape[0])) throw invalid("小数精度不得大于总位数");
            }
            var guard = JSONUtil.createObj().set("sqlType", type).set("nullable", field.get("nullable")).set("length", stringLength);
            cn.iocoder.yudao.framework.etl.core.executor.support.EtlNativeRowGuard.declaredOptions(field, guard);
            guards.set(name, guard);
            String expression = "TRY_CAST(" + raw + " AS " + type + ")";
            String invalid = expression + " IS NULL AND " + raw + " IS NOT NULL";
            if (type.equals("INT") || type.equals("BIGINT")) invalid += " OR (TRY_CAST(" + raw + " AS DECIMAL(38,18)) <> FLOOR(TRY_CAST(" + raw + " AS DECIMAL(38,18))))";
            if (stringLength > 0) invalid += " OR CHAR_LENGTH(" + raw + ") > " + stringLength;
            if (Boolean.FALSE.equals(field.getBool("nullable"))) invalid += " OR " + raw + " IS NULL";
            expression = "CASE WHEN (" + invalid + ") THEN CAST(RAISE_ERROR('API字段不满足声明类型') AS " + type + ") ELSE " + expression + " END";
            projections.add(expression + " AS `" + name + "`"); types.put(name, type);
        }
        plan.set("resultTypes", guards);
        String payload = Base64.getEncoder().encodeToString(plan.toString().getBytes(StandardCharsets.UTF_8));
        String alias = "dh_native_" + kind + "_" + DigestUtil.sha256Hex(identity).substring(0, 24);
        String sql = "CREATE TEMPORARY VIEW `" + alias + "` AS SELECT " + String.join(", ", projections)
                + " FROM (SELECT 1) seed LATERAL VIEW " + function + "('" + payload + "') rows AS dh_api_json;\n";
        return new EtlNativeConnectorCompiler.TablePlan(sql, new EtlNativeSqlCompiler.Table(List.of(alias), types, ProductTypeEnum.UNKNOWN, null, alias, false));
    }

    private static int integer(Object value, int fallback, int minimum, int maximum) {
        try {
            int parsed = value == null ? fallback : new BigDecimal(value.toString()).intValueExact();
            if (parsed < minimum || parsed > maximum) throw invalid("接口数值参数超限");
            return parsed;
        } catch (RuntimeException failure) { throw invalid("接口数值参数无效"); }
    }
    private static void rejectUnknown(Set<String> keys, Set<String> allowed, String label) {
        for (String key : keys) if (!allowed.contains(key)) throw invalid(label + "包含尚未适配的参数：" + key);
    }
    private static boolean sensitive(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).matches(".*(?:password|passwd|authorization|cookie|secret|token|api[-_]?key).*");
    }
    private static void rejectBodySecrets(Object value, int depth) {
        if (depth > 64) throw invalid("接口请求体嵌套超过预算");
        if (value instanceof Map<?, ?> object) for (var entry : object.entrySet()) {
            if (sensitive(String.valueOf(entry.getKey()))) throw invalid("接口请求体不得保存明文认证参数");
            rejectBodySecrets(entry.getValue(), depth + 1);
        }
        else if (value instanceof Iterable<?> array) for (Object item : array) rejectBodySecrets(item, depth + 1);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
