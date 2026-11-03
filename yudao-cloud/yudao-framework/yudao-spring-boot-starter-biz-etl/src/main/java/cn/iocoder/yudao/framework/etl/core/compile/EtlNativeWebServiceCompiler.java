package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlHttpRows;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** WebService有界行源只编译显式合同，WSDL读取与SOAP请求在原生工作节点执行。 */
public final class EtlNativeWebServiceCompiler {
    private EtlNativeWebServiceCompiler() { }
    public static EtlNativeConnectorCompiler.TablePlan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming,
            String identity, String code, Map<String, Object> values, EtlDataxConnectorCompiler.Binding binding,
            Set<String> managedFunctions, Set<String> allowedSecretRefs) {
        if (engine == null || streaming || !code.equals("read-webservice")) throw invalid("WebService行源当前仅支持有界批处理");
        if (binding == null || !binding.connectorCodes().contains(code) || (engine == EtlNativeSqlCompiler.Engine.SPARK ? !binding.installedPlugins().contains("spark-webservice") || !managedFunctions.contains("DH_READ_SOAP") : !binding.installedPlugins().contains("flink-bounded-webservice"))) throw invalid("WebService需要当前任务获授权的执行资源和受管函数");
        var config = JSONUtil.parseObj(values);
        for (String key : config.keySet()) if (!Set.of("apiUrl", "operationName", "apiMethod", "targetNamespace", "serviceName", "portName", "requestParams", "headerParams",
                "headerSecretRefs", "returnFormat", "returnDataLine", "resourceBindingId", "columns", "maxRows", "maxResponseBytes").contains(key))
            throw invalid("WebService包含尚未适配的参数：" + key);
        EtlHttpRows.endpoint(config.getStr("apiUrl", ""), Set.copyOf(binding.addresses()));
        if (!config.getStr("operationName", "").matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")
                || !Set.of("soap11", "soap12", "httpGet", "httpPost").contains(config.getStr("apiMethod", "soap11"))
                || !Set.of("XML", "JSON对象", "JSON数组").contains(config.getStr("returnFormat", "XML"))) throw invalid("WebService操作、协议或返回格式无效");
        for (String key : Set.of("serviceName", "portName")) if (!config.getStr(key, "").isBlank() && !config.getStr(key).matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) throw invalid("WebService服务或端口名称无效");
        var plain = config.getJSONObject("headerParams");
        if (plain != null) for (var header : plain.entrySet()) {
            String name = header.getKey();
            if (!name.matches("[A-Za-z][A-Za-z0-9-]{0,99}") || forbiddenHeader(name) || sensitive(name)
                    || header.getValue() == null || header.getValue().toString().length() > 4096 || header.getValue().toString().chars().anyMatch(character -> character < 32 || character == 127))
                throw invalid("WebService普通请求头无效或包含明文认证参数");
        }
        var references = config.getJSONObject("headerSecretRefs");
        if (references == null) references = JSONUtil.createObj();
        if (references.size() > 32 || binding.authentication() == EtlDataxConnectorCompiler.Authentication.NONE && !references.isEmpty()
                || binding.authentication() == EtlDataxConnectorCompiler.Authentication.REQUIRED && references.isEmpty()
                || binding.authentication() == EtlDataxConnectorCompiler.Authentication.ANONYMOUS) throw invalid("WebService认证声明与资源绑定不一致");
        for (var header : references.entrySet()) if (!header.getKey().matches("[A-Za-z][A-Za-z0-9-]{0,99}") || forbiddenHeader(header.getKey())
                || !(header.getValue() instanceof String reference) || !reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || !allowedSecretRefs.contains(reference))
            throw invalid("WebService秘密请求头引用无效或当前任务未获授权");
        rejectSecrets(config.getJSONObject("requestParams"), 0);
        config.set("headerSecretRefs", references);
        config.set("maxRows", integer(config.get("maxRows"), 100000, 1000000));
        config.set("maxResponseBytes", integer(config.get("maxResponseBytes"), 8388608, 16777216));
        var declared = config.getJSONArray("columns"); var columns = EtlNativeDeclaredColumns.parse(declared, true);
        if (declared == null || declared.isEmpty()) throw invalid("请显式声明WebService返回字段");
        for (Object value : declared) {
            var column = JSONUtil.parseObj(value);
            for (String key : column.keySet()) if (!Set.of("name", "type", "key", "index", "comment", "length", "precision", "format", "defaultValue").contains(key)) throw invalid("WebService字段包含未适配参数");
            if (!column.getStr("key", column.getStr("name", "")).equals(column.getStr("name", "")) && !column.getStr("key", "").isBlank()) throw invalid("WebService字段键当前需与返回节点名称一致");

        }
        config.remove("columns"); config.remove("resourceBindingId");
        if (config.toString().length() > 500000) throw invalid("WebService配置超过预算");
        return EtlNativeHttpCompiler.rowSource(engine, identity, columns, config, "DH_READ_SOAP", "soap");
    }
    private static int integer(Object value, int fallback, int maximum) {
        try { int parsed = value == null ? fallback : new BigDecimal(value.toString()).intValueExact(); if (parsed < 1 || parsed > maximum) throw invalid("WebService预算超限"); return parsed; }
        catch (RuntimeException failure) { throw invalid("WebService预算无效"); }
    }
    private static boolean forbiddenHeader(String name) { return Set.of("host", "connection", "content-length", "transfer-encoding", "upgrade", "content-type", "soapaction").contains(name.toLowerCase(java.util.Locale.ROOT)); }
    private static boolean sensitive(String name) { return name.toLowerCase(java.util.Locale.ROOT).matches(".*(?:password|passwd|authorization|cookie|secret|token|api[-_]?key).*"); }
    private static void rejectSecrets(Object value, int depth) {
        if (depth > 64) throw invalid("WebService参数嵌套超过预算");
        if (value instanceof Map<?, ?> object) for (var entry : object.entrySet()) {
            if (sensitive(String.valueOf(entry.getKey()))) throw invalid("WebService请求参数不可保存明文认证信息");
            rejectSecrets(entry.getValue(), depth + 1);
        }
        else if (value instanceof Iterable<?> array) for (Object item : array) rejectSecrets(item, depth + 1);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
