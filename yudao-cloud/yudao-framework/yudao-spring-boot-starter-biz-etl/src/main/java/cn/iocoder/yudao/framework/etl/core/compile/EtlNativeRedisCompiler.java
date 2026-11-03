package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Redis 三种原生数据结构的有界 Spark 编译；地址、Key 范围及凭据由管理员绑定。 */
public final class EtlNativeRedisCompiler {
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([\\p{L}_][\\p{L}\\p{N}_]{0,127})}");
    private EtlNativeRedisCompiler() { }

    public record Binding(String host, int port, int database, boolean useSsl, String credentialRef,
                          Set<String> sourcePatterns, Set<String> sinkPrefixes, boolean sparkConnectorInstalled, boolean flinkConnectorInstalled) {
        public Binding(String host, int port, int database, boolean useSsl, String credentialRef, Set<String> sourcePatterns,
                       Set<String> sinkPrefixes, boolean sparkConnectorInstalled) {
            this(host, port, database, useSsl, credentialRef, sourcePatterns, sinkPrefixes, sparkConnectorInstalled, false);
        }
        public Binding {
            sourcePatterns = sourcePatterns == null ? Set.of() : Set.copyOf(sourcePatterns);
            sinkPrefixes = sinkPrefixes == null ? Set.of() : Set.copyOf(sinkPrefixes);
        }
    }
    public static Set<String> supportedCodes(EtlNativeSqlCompiler.Engine engine, boolean streaming) {
        return engine != null && !streaming ? Set.of("read-redis", "write-redis") : Set.of();
    }
    public static EtlNativeConnectorCompiler.TablePlan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming,
            String identity, String code, Map<String, Object> input, Binding binding, Set<String> functions, Set<String> secrets) {
        if (!supportedCodes(engine, streaming).contains(code)) throw invalid("Redis SCAN 仅支持有界批处理，不能标记为持续采集");
        if (binding == null || (engine == EtlNativeSqlCompiler.Engine.SPARK ? !binding.sparkConnectorInstalled() : !binding.flinkConnectorInstalled())) throw invalid("工作环境尚未安装并授权 Redis 原生连接器");
        if (binding.host() == null || !binding.host().matches("[A-Za-z0-9.-]{1,253}") || binding.port() < 1 || binding.port() > 65535
                || binding.database() < 0 || binding.database() > 15) throw invalid("Redis 受管地址或数据库编号无效");
        if (binding.credentialRef() == null || !binding.credentialRef().matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || !secrets.contains(binding.credentialRef()))
            throw invalid("Redis 凭据引用未获当前任务、引擎和工作组授权");
        if (input == null || JSONUtil.toJsonStr(input).length() > 500000) throw invalid("Redis 配置缺失或超限");
        var config = JSONUtil.parseObj(input); boolean output = code.startsWith("write-");
        Set<String> allowed = output ? Set.of("databaseId", "dataType", "keyPattern", "columns", "batchSize", "fieldMappings")
                : Set.of("databaseId", "dataType", "keyPattern", "columns", "maxKeys", "maxRows", "maxBytes", "scanCount", "readMode");
        for (String key : config.keySet()) if (!allowed.contains(key)) throw invalid("Redis 参数尚未提供等价映射：" + key);
        var mappings = output ? EtlNativeOutputMappings.require(config) : List.<Map<String, Object>>of();
        if (!"FULL".equals(config.getStr("readMode", "FULL"))) throw invalid("Redis 增量读取尚未提供持久游标");
        String model = config.getStr("dataType", "string"), keyPattern = config.getStr("keyPattern", "");
        if (!Set.of("string", "hash", "list").contains(model)) throw invalid("Redis 数据类型必须为 string、hash 或 list");
        if (keyPattern.isBlank() || keyPattern.length() > 1024 || keyPattern.contains(",") || keyPattern.codePoints().anyMatch(Character::isISOControl)) throw invalid("Redis Key 模式无效");
        var declared = config.getJSONArray("columns");
        var normalized = JSONUtil.createArray(); var selectors = JSONUtil.createObj(); var fieldNames = new LinkedHashSet<String>();
        if (declared == null || declared.isEmpty() || declared.size() > 1024) throw invalid("Redis 需要完整的显式字段合同");
        for (Object value : declared) {
            var field = JSONUtil.parseObj(value);
            for (String key : field.keySet()) if (!Set.of("name", "type", "redisField", "key", "index", "comment", "length", "precision").contains(key))
                throw invalid("Redis 字段参数尚未适配：" + key);
            String name = field.getStr("name", "");
            if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,127}") || !fieldNames.add(name)) throw invalid("Redis 字段名称无效或重复");
            if (field.containsKey("index") && integer(field.get("index"), -1, 0, 1023) != fieldNames.size() - 1)
                throw invalid("Redis 字段序号必须与声明顺序一致");
            String selector = field.getStr("redisField", field.getStr("key", name));
            if (field.containsKey("redisField") && field.containsKey("key") && !Objects.equals(field.getStr("redisField"), field.getStr("key")))
                throw invalid("Redis 字段解析值配置不一致");
            if (output && field.containsKey("redisField") && !field.getStr("redisField", "").isBlank() && !field.getStr("redisField").equals(name)) throw invalid("Redis 输出不接受输入解析值");
            if (output) selector = name;
            if (!output && !(Set.of("@key", "@value", "@index").contains(selector) || selector.matches("[\\p{L}_][\\p{L}\\p{N}_-]{0,127}")
                    || !model.equals("hash") && selector.matches("\\$\\.[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*")))
                throw invalid("Redis 解析值必须为字段名、确定 JSON 属性路径或 @key/@value/@index");
            if (selector.equals("@index") && !model.equals("list") || selector.equals("@value") && model.equals("hash")) throw invalid("Redis 解析值与数据结构不一致");
            selectors.set(name, selector); field.remove("redisField"); field.remove("key"); field.remove("index"); normalized.add(field);
        }
        String ref = binding.credentialRef();
        var plan = JSONUtil.createObj().set("host", binding.host()).set("port", binding.port()).set("database", binding.database())
                .set("useSsl", binding.useSsl()).set("credentialRef", ref).set("dataType", model).set("keyPattern", keyPattern).set("selectors", selectors);
        if (!output) {
            if (!binding.sourcePatterns().contains(keyPattern)) throw invalid("Redis Key 读取模式未被管理员精确授权");
            if (engine == EtlNativeSqlCompiler.Engine.SPARK && !functions.contains("DH_READ_REDIS")) throw invalid("工作环境尚未注册受管 Redis 读取函数");
            plan.set("maxKeys", integer(config.get("maxKeys"), 100000, 1, 1000000)).set("maxRows", integer(config.get("maxRows"), 100000, 1, 1000000))
                    .set("maxBytes", integer(config.get("maxBytes"), 16777216, 1, 67108864)).set("scanCount", integer(config.get("scanCount"), 1000, 1, 10000));
            return EtlNativeHttpCompiler.rowSource(engine, identity, EtlNativeDeclaredColumns.parse(normalized), plan, "DH_READ_REDIS", "redis");
        }
        var token = TOKEN.matcher(keyPattern); int first = keyPattern.length();
        while (token.find()) { first = Math.min(first, token.start()); if (!fieldNames.contains(token.group(1))) throw invalid("Redis Key 模板引用了不存在的字段"); }
        String fixed = keyPattern.substring(0, first);
        if (TOKEN.matcher(keyPattern).replaceAll("").contains("$") || fixed.isEmpty() || fixed.indexOf('*') >= 0 || fixed.indexOf('?') >= 0
                || fixed.indexOf('[') >= 0 || fixed.indexOf(']') >= 0 || binding.sinkPrefixes().stream().noneMatch(prefix -> prefix != null && !prefix.isBlank() && fixed.startsWith(prefix)))
            throw invalid("Redis 输出 Key 模板必须位于授权前缀，不能包含未解析表达式或通配符");
        plan.set("sinkPrefixes", binding.sinkPrefixes()).set("batchSize", integer(config.get("batchSize"), 1000, 1, 10000));
        // 复用输入类型合同转换，输出采用同一严格标量结构；函数本身不会被执行。
        var shape = EtlNativeHttpCompiler.rowSource(identity, EtlNativeDeclaredColumns.parse(normalized), plan, "DH_READ_REDIS", "redis").table().columns();
        if (engine == EtlNativeSqlCompiler.Engine.FLINK) return EtlNativeFlinkConnectorSql.table(identity, "redis", true, shape, plan, mappings);
        String alias = "dh_native_redis_" + DigestUtil.sha256Hex(identity).substring(0, 24);
        String payload = Base64.getEncoder().encodeToString(plan.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String sql = "CREATE TEMPORARY VIEW `" + alias + "` (" + String.join(", ", shape.entrySet().stream().map(field -> "`" + field.getKey() + "` " + field.getValue()).toList())
                + ") USING cn.iocoder.yudao.framework.etl.spark.EtlRedisProvider OPTIONS (plan '" + payload + "');\n";
        return new EtlNativeConnectorCompiler.TablePlan(sql, new EtlNativeSqlCompiler.Table(List.of(alias), shape, ProductTypeEnum.UNKNOWN, null, alias, false), mappings);
    }
    private static int integer(Object value, int fallback, int min, int max) {
        try { int parsed = value == null ? fallback : new BigDecimal(value.toString()).intValueExact(); if (parsed < min || parsed > max) throw new ArithmeticException(); return parsed; }
        catch (RuntimeException failure) { throw invalid("Redis 数值参数必须为范围内整数"); }
    }
    private static IllegalArgumentException invalid(String value) { return new IllegalArgumentException(value); }
}
