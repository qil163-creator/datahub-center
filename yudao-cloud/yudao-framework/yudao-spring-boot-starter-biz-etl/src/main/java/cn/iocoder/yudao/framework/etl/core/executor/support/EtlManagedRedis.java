package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;

import javax.net.ssl.HttpsURLConnection;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Redis 官方 Jedis SDK 的有界行读写；秘密只在执行节点建立连接时读取。 */
public final class EtlManagedRedis {
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([\\p{L}_][\\p{L}\\p{N}_]{0,127})}");
    private static final String READ = "local v={} local n=0 local limit=tonumber(ARGV[1]) "
            + "if ARGV[2]=='string' then v={redis.call('GET',KEYS[1])} "
            + "elseif ARGV[2]=='hash' then for i=3,#ARGV do v[#v+1]=redis.call('HGET',KEYS[1],ARGV[i]) end "
            + "else v=redis.call('LRANGE',KEYS[1],ARGV[3],ARGV[4]) end "
            + "for _,s in ipairs(v) do if s then n=n+string.len(s) if n>limit then return redis.error_reply('ROW_BYTE_BUDGET') end end end return v";
    private EtlManagedRedis() { }

    public static Jedis connect(JSONObject plan, boolean output, Function<String, String> resolver) {
        try {
            var secret = new Properties(); secret.load(new StringReader(resolver.apply(plan.getStr("credentialRef"))));
            if (!secret.stringPropertyNames().equals(Set.of("host", "port", "database", "useSsl", "sourcePatterns", "sinkPrefixes", "username", "password"))) throw new IllegalArgumentException();
            for (String key : List.of("host", "port", "database", "useSsl")) if (!Objects.equals(plan.getStr(key), secret.getProperty(key))) throw new IllegalArgumentException();
            if (!output && !Arrays.asList(secret.getProperty("sourcePatterns").split(",", -1)).contains(plan.getStr("keyPattern"))) throw new IllegalArgumentException();
            if (output) {
                var actual = Set.copyOf(Arrays.asList(secret.getProperty("sinkPrefixes").split(",", -1)));
                var requested = plan.getJSONArray("sinkPrefixes");
                if (requested == null || requested.isEmpty() || !actual.containsAll(requested.toList(String.class))) throw new IllegalArgumentException();
            }
            String username = secret.getProperty("username"), password = secret.getProperty("password");
            if (username == null || !username.matches("[A-Za-z0-9_.-]{1,64}") || password == null || password.isEmpty() || password.length() > 1024
                    || password.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            var options = DefaultJedisClientConfig.builder().user(username).password(password).database(plan.getInt("database"))
                    .ssl(plan.getBool("useSsl")).connectionTimeoutMillis(10000).socketTimeoutMillis(10000)
                    .hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier()).build();
            var jedis = new Jedis(new HostAndPort(plan.getStr("host"), plan.getInt("port")), options);
            try { jedis.ping(); return jedis; } catch (RuntimeException failure) { jedis.close(); throw failure; }
        } catch (Exception failure) { throw new IllegalStateException("Redis 受管认证或资源范围校验失败"); }
    }

    public static void read(JSONObject plan, Function<String, String> resolver, Consumer<String> rows) {
        try (var connection = connect(plan, false, resolver)) { read(plan, connection, rows); }
        catch (Exception failure) { throw new IllegalStateException("Redis 有界读取失败，请检查受管认证、类型、解析值和预算"); }
    }
    static void read(JSONObject plan, Jedis connection, Consumer<String> rows) {
        var seen = new HashSet<String>(); long[] budget = {0, 0}; String cursor = ScanParams.SCAN_POINTER_START;
        var params = new ScanParams().match(plan.getStr("keyPattern")).count(plan.getInt("scanCount"));
        do {
            var batch = connection.scan(cursor.getBytes(StandardCharsets.US_ASCII), params); cursor = batch.getCursor();
            for (byte[] rawKey : batch.getResult()) {
                String key = decode(rawKey);
                if (!seen.add(key)) continue;
                if (seen.size() > plan.getInt("maxKeys")) throw new IllegalArgumentException("Redis Key 数量超过预算");
                String model = connection.type(key);
                if (model.equals("none")) continue;
                if (!model.equals(plan.getStr("dataType"))) throw new IllegalArgumentException("Redis Key 实际类型与声明不一致");
                int remaining = Math.toIntExact(plan.getInt("maxBytes") - budget[1]);
                if (model.equals("hash")) {
                    var selected = plan.getJSONObject("selectors").values().stream().map(Object::toString).filter(value -> !value.startsWith("@")).distinct().toList();
                    var values = bounded(connection, key, remaining, "hash", selected); var fields = new LinkedHashMap<String, String>();
                    for (int i = 0; i < selected.size(); i++) fields.put(selected.get(i), values.get(i));
                    emit(plan, key, -1, null, fields, budget, rows);
                } else if (model.equals("string")) emit(plan, key, -1, bounded(connection, key, remaining, "string", List.of()).get(0), null, budget, rows);
                else {
                    long length = connection.llen(key);
                    if (length > plan.getInt("maxRows") - budget[0]) throw new IllegalArgumentException("Redis List 行数超过预算");
                    for (long offset = 0; offset < length; offset += 1000) {
                        var values = bounded(connection, key, Math.toIntExact(plan.getInt("maxBytes") - budget[1]), "list", List.of(Long.toString(offset), Long.toString(Math.min(length - 1, offset + 999))));
                        for (int index = 0; index < values.size(); index++) emit(plan, key, offset + index, values.get(index), null, budget, rows);
                    }
                }
            }
        } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
    }
    private static List<String> bounded(Jedis connection, String key, int maximum, String model, List<String> fields) {
        if (maximum < 1) throw new IllegalArgumentException("Redis 字节预算已耗尽");
        var arguments = new ArrayList<byte[]>(); arguments.add(Integer.toString(maximum).getBytes(StandardCharsets.US_ASCII)); arguments.add(model.getBytes(StandardCharsets.US_ASCII));
        fields.forEach(value -> arguments.add(value.getBytes(StandardCharsets.UTF_8)));
        Object raw = connection.evalReadonly(READ.getBytes(StandardCharsets.US_ASCII), List.of(key.getBytes(StandardCharsets.UTF_8)), arguments);
        if (!(raw instanceof List<?> values)) throw new IllegalArgumentException("Redis 响应形状无效");
        var result = new ArrayList<String>();
        for (Object value : values) {
            if (value == null || Boolean.FALSE.equals(value)) result.add(null);
            else if (value instanceof byte[] bytes) result.add(decode(bytes)); else throw new IllegalArgumentException("Redis 字符串响应无效");
        }
        return result;
    }
    private static String decode(byte[] value) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString(); }
        catch (Exception failure) { throw new IllegalArgumentException("Redis 字符串必须为无损 UTF-8"); }
    }
    private static void emit(JSONObject plan, String key, long index, String raw, Map<String, String> hash, long[] budget, Consumer<String> rows) {
        if (++budget[0] > plan.getInt("maxRows")) throw new IllegalArgumentException("Redis 行数超过预算");
        var result = JSONUtil.createObj(); JSONObject json = null;
        for (var field : plan.getJSONObject("selectors").entrySet()) {
            String selector = String.valueOf(field.getValue()); Object value;
            if (selector.equals("@key")) value = key;
            else if (selector.equals("@index")) value = index;
            else if (selector.equals("@value")) value = raw;
            else if (hash != null) value = hash.get(selector);
            else {
                if (raw == null) value = null;
                else {
                    if (json == null) { if (!JSONUtil.isTypeJSONObject(raw)) throw new IllegalArgumentException("Redis 字段解析需要 JSON 对象"); json = JSONUtil.parseObj(raw); }
                    Object current = json;
                    for (String part : (selector.startsWith("$.") ? selector.substring(2) : selector).split("\\.")) {
                        if (current == null || JSONUtil.isNull(current)) break;
                        if (!(current instanceof JSONObject object)) throw new IllegalArgumentException("Redis JSON 路径穿过非对象值");
                        current = object.get(part);
                    }
                    value = current;
                    if (value instanceof Map<?, ?> || value instanceof Collection<?>) throw new IllegalArgumentException("Redis 标量字段不能读取对象或数组");
                }
            }
            result.set(field.getKey(), value);
        }
        String row = result.toString(); budget[1] += row.getBytes(StandardCharsets.UTF_8).length;
        if (budget[1] > plan.getInt("maxBytes")) throw new IllegalArgumentException("Redis 行数据超过字节预算");
        rows.accept(EtlNativeRowGuard.validate(row, plan));
    }

    public static String key(JSONObject plan, Map<String, String> row) {
        String pattern = plan.getStr("keyPattern"); var matcher = TOKEN.matcher(pattern); var result = new StringBuilder();
        while (matcher.find()) {
            String value = row.get(matcher.group(1));
            if (value == null || value.isEmpty() || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Redis Key 字段不能为 NULL、空字符串或控制字符");
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result); String key = result.toString();
        var prefixes = plan.getJSONArray("sinkPrefixes");
        if (key.isEmpty() || key.getBytes(StandardCharsets.UTF_8).length > 1024 || prefixes == null || prefixes.toList(String.class).stream().noneMatch(prefix -> !prefix.isEmpty() && key.startsWith(prefix)))
            throw new IllegalArgumentException("Redis Key 超出授权前缀或长度预算");
        return key;
    }

    /** 每行命令明确同步到 Redis，pipeline 批次并不声明事务或恰好一次。 */
    public static void write(JSONObject plan, redis.clients.jedis.Pipeline pipeline, Map<String, String> row) {
        String key = key(plan, row), model = plan.getStr("dataType");
        if (model.equals("hash")) {
            var fields = new LinkedHashMap<String, String>(); var absent = new ArrayList<String>();
            row.forEach((field, value) -> { if (value == null) absent.add(field); else fields.put(field, value); });
            if (fields.isEmpty()) throw new IllegalArgumentException("Redis Hash 无法保存全部为 NULL 的记录");
            if (!fields.isEmpty()) pipeline.hset(key, fields);
            if (!absent.isEmpty()) pipeline.hdel(key, absent.toArray(String[]::new));
        } else if (model.equals("string")) pipeline.set(key, JSONUtil.parseObj(row, false).toString());
        else if (model.equals("list")) pipeline.rpush(key, JSONUtil.parseObj(row, false).toString());
        else throw new IllegalArgumentException("Redis 输出类型无效");
    }
}
