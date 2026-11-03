package cn.iocoder.yudao.framework.dolphinscheduler.model;

import cn.hutool.json.JSONUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** DS 3.4.2 内置 DataX 参数，凭据由 DS 数据源资源解析。 */
public final class DataxTaskParameters {
    private DataxTaskParameters() { }

    public record Options(int sourceId, String sourceType, int targetId, String targetType, String sql,
                          String targetTable, int initialMemoryMb, int maximumMemoryMb, int channels,
                          long byteSpeed, long recordSpeed, long errorLimit, int batchSize) { }

    public record RuntimeOptions(int initialMemoryMb, int maximumMemoryMb, int channels,
                                 long byteSpeed, long recordSpeed, long errorLimit) { }

    /** 必须配合独立 DATAX_UPSERT 任务类型，旧 Worker 缺少插件时拒绝执行，不能退化成 INSERT。 */
    public static Map<String, Object> upsert(Options options, String schema, String table, List<String> keys) {
        if (!"POSTGRESQL".equals(options.targetType()) || keys == null || keys.isEmpty() || keys.size() > 32
                || new java.util.HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("DataX 幂等写入仅支持 PostgreSQL 和明确的非空唯一键");
        for (String identifier : java.util.stream.Stream.concat(java.util.stream.Stream.of(schema, table), keys.stream()).toList()) {
            if (identifier == null || identifier.isBlank() || identifier.indexOf('\0') >= 0
                    || identifier.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 63
                    || identifier.contains("${") || identifier.contains("$[") || identifier.contains("!{"))
                throw new IllegalArgumentException("DataX 幂等写入标识符无效");
        }
        var params = build(options);
        params.put("writeContract", "POSTGRESQL_UPSERT_V1");
        params.put("upsertSchema", schema); params.put("upsertTable", table); params.put("upsertKeys", List.copyOf(keys));
        return params;
    }

    /** SQL 已由调用方按目标作用域验证；由独立 V2 插件原子发布钩子与数据。 */
    public static Map<String, Object> upsertWithHooks(Options options, String schema, String table, List<String> keys,
                                                     List<String> preStatements, List<String> postStatements) {
        var params = upsert(options, schema, table, keys);
        if (preStatements == null || postStatements == null || preStatements.size() + postStatements.size() > 32)
            throw new IllegalArgumentException("前后置 SQL 数量无效");
        for (String sql : java.util.stream.Stream.concat(preStatements.stream(), postStatements.stream()).toList()) {
            if (sql == null || sql.isBlank() || sql.length() > 4000 || sql.indexOf('\0') >= 0
                    || sql.contains("${") || sql.contains("$[") || sql.contains("!{"))
                throw new IllegalArgumentException("前后置 SQL 为空、过长或包含不支持的展开模板");
        }
        if (!preStatements.isEmpty() || !postStatements.isEmpty()) {
            params.put("writeContract", "POSTGRESQL_UPSERT_HOOKS_V2");
            params.put("preStatements", List.copyOf(preStatements));
            params.put("postStatements", List.copyOf(postStatements));
        }
        return params;
    }

    /** 仅接受服务端已验证的插件参数；调用方负责受管连接、路径授权及实际插件安装检查。 */
    public static Map<String, Object> custom(Map<String, Object> reader, Map<String, Object> writer,
                                            RuntimeOptions options, List<NativeTaskParameters.Resource> resources) {
        validateRuntime(options);
        validatePlugin(reader, true); validatePlugin(writer, false);
        var speed = new LinkedHashMap<String, Object>(); speed.put("channel", options.channels());
        if (options.byteSpeed() > 0) speed.put("byte", options.byteSpeed());
        if (options.recordSpeed() > 0) speed.put("record", options.recordSpeed());
        var job = Map.of("job", Map.of("setting", Map.of("speed", speed, "errorLimit", Map.of("record", 0)),
                "content", List.of(Map.of("reader", reader, "writer", writer))));
        String json = JSONUtil.toJsonStr(job);
        if (json.length() > 1_000_000) throw new IllegalArgumentException("DataX 配置超过大小限制");
        rejectUnsafe(job, 0);
        var result = common(options);
        result.put("customConfig", 1); result.put("json", json);
        if (resources == null || resources.size() > 32) throw new IllegalArgumentException("DataX 受管资源数量无效");
        var staged = new ArrayList<Map<String, Object>>(); var names = new java.util.HashSet<String>();
        for (var resource : resources) {
            if (resource == null || !names.add(resource.resourceName())) throw new IllegalArgumentException("DataX 受管资源重复");
            staged.add(resource.taskParameter());
        }
        result.put("resourceList", List.copyOf(staged));
        return result;
    }

    public static Map<String, Object> build(Options options) {
        var types = Set.of("MYSQL", "POSTGRESQL", "ORACLE", "SQLSERVER");
        if (options.sourceId() <= 0 || options.targetId() <= 0 || !types.contains(options.sourceType())
                || !types.contains(options.targetType()) || options.sql() == null || options.sql().isBlank()
                || options.targetTable() == null || options.targetTable().isBlank()) {
            throw new IllegalArgumentException("DataX 数据源或查询配置无效");
        }
        var runtime = new RuntimeOptions(options.initialMemoryMb(), options.maximumMemoryMb(), options.channels(),
                options.byteSpeed(), options.recordSpeed(), options.errorLimit());
        validateRuntime(runtime);
        if (options.batchSize() < 1 || options.batchSize() > 100000) throw new IllegalArgumentException("DataX 批量大小无效");
        Map<String, Object> params = common(runtime);
        params.put("customConfig", 0);
        params.put("dataSource", options.sourceId()); params.put("dsType", options.sourceType());
        params.put("dataTarget", options.targetId()); params.put("dtType", options.targetType());
        params.put("sql", options.sql()); params.put("targetTable", options.targetTable());
        params.put("batchSize", options.batchSize());
        params.put("preStatements", List.of()); params.put("postStatements", List.of());
        return params;
    }

    private static void validateRuntime(RuntimeOptions options) {
        if (options == null) throw new IllegalArgumentException("DataX 运行参数缺失");
        // DS 3.4.2 原生插件仅接受整数 GiB；禁止把页面 MB 静默解释成 GiB。
        if (options.initialMemoryMb() < 1024 || options.maximumMemoryMb() < options.initialMemoryMb()
                || options.maximumMemoryMb() > 524288 || options.initialMemoryMb() % 1024 != 0
                || options.maximumMemoryMb() % 1024 != 0 || options.channels() < 1 || options.channels() > 1024
                || options.byteSpeed() < 0 || options.byteSpeed() > Integer.MAX_VALUE
                || options.recordSpeed() < 0 || options.recordSpeed() > Integer.MAX_VALUE
                || options.errorLimit() != 0) {
            throw new IllegalArgumentException("DS 原生 DataX 要求内存为 1024 MB 的倍数、限速不超过整数上限、脏数据上限为 0");
        }
    }

    private static Map<String, Object> common(RuntimeOptions options) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("xms", options.initialMemoryMb() / 1024); params.put("xmx", options.maximumMemoryMb() / 1024);
        params.put("jobChannel", options.channels()); params.put("jobSpeedByte", options.byteSpeed());
        params.put("jobSpeedRecord", options.recordSpeed());
        params.put("localParams", List.of()); params.put("resourceList", List.of());
        return params;
    }

    private static void validatePlugin(Map<String, Object> plugin, boolean reader) {
        var allowed = reader ? Set.of("hdfsreader", "txtfilereader", "mongodbreader")
                : Set.of("hdfswriter", "txtfilewriter", "mongodbwriter");
        if (plugin == null || !plugin.keySet().equals(Set.of("name", "parameter"))
                || !allowed.contains(plugin.get("name")) || !(plugin.get("parameter") instanceof Map)) {
            throw new IllegalArgumentException("DataX 插件尚未提供无凭据安全编译");
        }
    }

    private static void rejectUnsafe(Object value, int depth) {
        if (depth > 16) throw new IllegalArgumentException("DataX 配置嵌套过深");
        if (value instanceof Map<?, ?> map) for (var item : map.entrySet()) {
            String key = String.valueOf(item.getKey());
            if (key.matches("(?i).*?(password|passwd|secret|token|credential|accesskey|accessid|username|principal|keytab).*"))
                throw new IllegalArgumentException("DataX 自定义参数不得包含连接凭据");
            rejectUnsafe(item.getValue(), depth + 1);
        } else if (value instanceof Iterable<?> items) for (Object item : items) rejectUnsafe(item, depth + 1);
        else if (value instanceof String text && (text.contains("${") || text.contains("$[") || text.contains("!{")
                || text.indexOf('\0') >= 0)) throw new IllegalArgumentException("DataX 配置不得包含 DS 展开模板");
    }
}
