package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.math.BigDecimal;
import java.net.URI;
import java.util.*;

/** 受管 HDFS CSV/分隔文本到 Spark 数据源的编译，路径、字段、格式与客户端参数逐项消费。 */
public final class EtlNativeHdfsCompiler {
    private static final String PROVIDER = "cn.iocoder.yudao.framework.etl.spark.EtlHdfsTextProvider";
    private static final Set<String> KEYS = Set.of("databaseId", "resourceBindingId", "path", "relativePath", "filePath", "fileType", "encoding", "charset",
            "fieldDelimiter", "delimiter", "columns", "hadoopConfig", "compression", "fileName", "writeModeType", "writeMode",
            "hasHeader", "includeHeader", "nullFormat", "readMode", "name", "description", "fieldMappings");
    private EtlNativeHdfsCompiler() { }

    public static Set<String> supportedCodes(EtlNativeSqlCompiler.Engine engine, boolean streaming) {
        return engine != null && !streaming ? Set.of("read-hdfs", "write-hdfs") : Set.of();
    }

    public static EtlNativeConnectorCompiler.TablePlan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming,
            String identity, String code, Map<String, Object> input, EtlDataxConnectorCompiler.Binding binding) {
        if (!supportedCodes(engine, streaming).contains(code)) throw invalid("HDFS 原生文件连接器当前仅支持有界批处理");
        if (binding == null || !binding.connectorCodes().contains(code) || !binding.installedPlugins().contains(engine == EtlNativeSqlCompiler.Engine.SPARK ? "spark-hdfs" : "flink-bounded-hdfs"))
            throw invalid("当前任务未绑定已安装 spark-hdfs 数据源的工作环境");
        if (binding.authentication() != EtlDataxConnectorCompiler.Authentication.NONE) throw invalid("HDFS Kerberos 需要受管工作节点身份，不能写入任务凭据");
        if (input == null || JSONUtil.toJsonStr(input).length() > 500_000) throw invalid("HDFS 配置缺失或超过大小限制");
        var config = JSONUtil.parseObj(input);
        for (String key : config.keySet()) if (!KEYS.contains(key)) throw invalid("HDFS 参数尚未提供等价原生映射：" + key);
        if (!"FULL".equals(config.getStr("readMode", "FULL"))) throw invalid("HDFS 文件增量采集尚未提供持久游标");
        URI fs;
        try { fs = URI.create(binding.defaultFs()); } catch (RuntimeException malformed) { throw invalid("HDFS 受管 defaultFS 无效"); }
        if (!"hdfs".equals(fs.getScheme()) || fs.getHost() == null || fs.getUserInfo() != null || fs.getRawQuery() != null
                || fs.getRawFragment() != null || fs.getPort() == 0 || fs.getPort() > 65535 || !Set.of("", "/").contains(fs.getPath()))
            throw invalid("HDFS defaultFS 必须为无凭据的受管 HDFS 服务地址");
        String base = "hdfs://" + fs.getRawAuthority();
        String root = path(binding.rootPath(), true), relative = null;
        for (String key : List.of("path", "relativePath", "filePath")) if (config.containsKey(key)) {
            String candidate = config.getStr(key);
            if (relative != null && !relative.equals(candidate)) throw invalid("HDFS 路径配置不一致"); relative = candidate;
        }
        relative = path(relative, false);
        boolean output = code.startsWith("write-");
        if (!output && config.containsKey("fieldMappings")) throw invalid("HDFS 输入不接受输出字段映射");
        var mappings = output ? EtlNativeOutputMappings.require(config) : List.<Map<String, Object>>of();
        String type = config.getStr("fileType", output ? "text" : "csv").toLowerCase(Locale.ROOT);
        if (!Set.of("text", "csv").contains(type)) throw invalid("当前 HDFS 原生格式只支持 CSV 和分隔文本");
        var options = new LinkedHashMap<String, String>();
        options.put("path", base + root + "/" + relative); options.put("datahub.root", base + root);
        options.put("datahub.mode", output ? "write" : "read"); options.put("datahub.filetype", type);
        String encoding = config.getStr("encoding", config.getStr("charset", "UTF-8"));
        if (config.containsKey("encoding") && config.containsKey("charset") && !Objects.equals(config.getStr("encoding"), config.getStr("charset")))
            throw invalid("HDFS 编码配置不一致");
        if (!Set.of("UTF-8", "GBK", "GB2312", "ISO-8859-1").contains(encoding)) throw invalid("HDFS 文件编码尚未支持");
        options.put("encoding", encoding);
        String delimiter = config.getStr("fieldDelimiter", config.getStr("delimiter", ",")); if (delimiter.equals("TAB")) delimiter = "\t";
        if (config.containsKey("fieldDelimiter") && config.containsKey("delimiter") && !Objects.equals(config.getStr("fieldDelimiter"), config.getStr("delimiter")))
            throw invalid("HDFS 分隔符配置不一致");
        if (delimiter.length() != 1 || Set.of("\n", "\r", "\0").contains(delimiter)) throw invalid("HDFS 分隔符必须为单个非换行字符");
        String nullValue = config.getStr("nullFormat", "\\N");
        if (nullValue == null || nullValue.isEmpty() || nullValue.length() > 128 || nullValue.indexOf(delimiter) >= 0
                || nullValue.indexOf('\r') >= 0 || nullValue.indexOf('\n') >= 0 || nullValue.indexOf('\0') >= 0)
            throw invalid("HDFS NULL 标记必须非空且不含分隔符或换行，以保留空字符串与 NULL 的区别");
        options.put("delimiter", delimiter); options.put("nullValue", nullValue);
        applyHadoop(config.get("hadoopConfig"), options);
        if (output) {
            if (integer(config.get("writeModeType") == null ? 2 : config.get("writeModeType"), 1, 2, "HDFS写入模式") != 2
                    || !Set.of("INSERT", "nonConflict", "CREATE_NEW").contains(config.getStr("writeMode", "INSERT")))
                throw invalid("HDFS 输出只允许创建新文件，不能清空或覆盖已有文件");
            String filename = config.getStr("fileName", "data");
            if (!filename.matches("[A-Za-z0-9_-]{1,100}")) throw invalid("HDFS 文件名前缀无效");
            options.put("datahub.filename", filename);
            String compression = config.getStr("compression", "").toLowerCase(Locale.ROOT);
            if (!Set.of("", "none", "gzip", "bzip2", "snappy", "lzo").contains(compression)) throw invalid("HDFS 压缩格式未知");
            if (Set.of("snappy", "lzo").contains(compression) && !binding.installedPlugins().contains("hadoop-" + compression))
                throw invalid("HDFS " + compression + " 需要管理员确认工作节点已安装对应官方/原生编解码器");
            options.put("compression", compression);
            if (config.containsKey("hasHeader")) throw invalid("HDFS 输出请使用 includeHeader，不能携带输入表头参数");
            if (bool(config, "includeHeader", false)) throw invalid("当前 HDFS 新文件输出未声明写入表头，请保持关闭");
        } else {
            for (String key : List.of("writeMode", "writeModeType", "fileName", "compression", "includeHeader")) if (config.containsKey(key))
                throw invalid("HDFS 输入不接受输出参数：" + key);
            options.put("header", String.valueOf(bool(config, "hasHeader", false))); options.put("enforceSchema", "false");
            options.put("mode", "FAILFAST"); options.put("multiLine", String.valueOf(type.equals("csv")));
            options.put("quote", type.equals("csv") ? "\"" : ""); options.put("escape", "\"");
            options.put("timeZone", "UTC"); options.put("enableDateTimeParsingFallback", "false");
            options.put("ignoreLeadingWhiteSpace", "false"); options.put("ignoreTrailingWhiteSpace", "false");
        }
        var columns = columns(config, output, options); String alias = "dh_native_hdfs_" + DigestUtil.sha256Hex(identity).substring(0, 24);
        if (engine == EtlNativeSqlCompiler.Engine.FLINK) {
            var plan = new JSONObject().set("options", options).set("columns", config.getJSONArray("columns")).set("maxRows", 100000).set("maxBytes", 67108864);
            return EtlNativeFlinkConnectorSql.table(identity, "hdfs", output, columns, plan, mappings);
        }
        String sql = "CREATE TEMPORARY VIEW " + quote(alias) + " (" + String.join(", ", columns.entrySet().stream()
                .map(entry -> quote(entry.getKey()) + " " + entry.getValue()).toList()) + ") USING " + PROVIDER + " OPTIONS ("
                + String.join(", ", options.entrySet().stream().map(entry -> quote(entry.getKey()) + " " + literal(entry.getValue())).toList()) + ");\n";
        return new EtlNativeConnectorCompiler.TablePlan(sql, new EtlNativeSqlCompiler.Table(List.of(alias), columns, ProductTypeEnum.UNKNOWN, null, alias, false), mappings);
    }

    private static Map<String, String> columns(JSONObject config, boolean output, Map<String, String> options) {
        var fields = config.getJSONArray("columns"); if (fields == null || fields.isEmpty() || fields.size() > 1024) throw invalid("HDFS 需要1至1024个明确类型的完整字段");
        var result = new LinkedHashMap<String, String>(); int position = 0;
        for (Object value : fields) {
            var field = JSONUtil.parseObj(value);
            for (String key : field.keySet()) if (!Set.of("name", "type", "index", "key", "comment", "format", "length", "precision").contains(key))
                throw invalid("HDFS 字段参数尚未支持：" + key);
            String name = field.getStr("name", "");
            if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,127}") || result.containsKey(name)) throw invalid("HDFS 字段名称无效或重复");
            if ((!output || field.containsKey("index")) && integer(field.get("index"), 0, 1023, "HDFS字段序号") != position)
                throw invalid("HDFS 完整源字段必须按从0开始的连续序号声明");
            if (!output && !field.getStr("key", "").isBlank() && !field.getStr("key").equals(name)) throw invalid("HDFS 不接受 JSON 字段取值路径");
            String type = switch (field.getStr("type", "").toUpperCase(Locale.ROOT)) {
                case "STRING", "VARCHAR", "CHAR", "TEXT", "INTERNETADDRESS" -> "STRING";
                case "INTEGER", "INT", "LONG", "BIGINT", "SMALLINT", "TINYINT" -> "BIGINT";
                case "NUMBER", "DOUBLE" -> "DOUBLE"; case "FLOAT" -> "FLOAT"; case "BOOLEAN" -> "BOOLEAN";
                case "DATE" -> "DATE"; case "TIMESTAMP" -> "TIMESTAMP";
                case "BIGNUMBER", "DECIMAL", "NUMERIC" -> {
                    int precision = integer(field.get("length") == null || field.getStr("length").isBlank() ? 38 : field.get("length"), 1, 38, "Decimal总位数");
                    int scale = integer(field.get("precision") == null || field.getStr("precision").isBlank() ? 18 : field.get("precision"), 0, precision, "Decimal小数位数");
                    yield "DECIMAL(" + precision + "," + scale + ")";
                }
                default -> throw invalid("HDFS 字段类型尚未明确或不支持：" + name);
            };
            if (!type.startsWith("DECIMAL(") && (!field.getStr("length", "").isBlank() || !field.getStr("precision", "").isBlank()))
                throw invalid("HDFS 非 Decimal 字段长度或精度约束尚未适配：" + name);
            String format = field.getStr("format", "");
            if (!format.isBlank()) {
                if (!Set.of("DATE", "TIMESTAMP").contains(type) || !format.matches("[A-Za-z0-9 :._/'T+\\-]{1,128}")) throw invalid("HDFS 字段日期格式无效");
                if (output) options.put("datahub.format." + name, format);
                else {
                    String key = type.equals("DATE") ? "dateFormat" : "timestampFormat";
                    if (options.containsKey(key) && !options.get(key).equals(format)) throw invalid("HDFS 输入同类型日期字段必须声明相同格式");
                    options.put(key, format);
                }
            }
            result.put(name, type); position++;
        }
        return result;
    }
    private static void applyHadoop(Object raw, Map<String, String> options) {
        if (raw == null || raw instanceof String text && text.isBlank()) return;
        if (raw instanceof String text && !JSONUtil.isTypeJSONObject(text) || !(raw instanceof String) && !(raw instanceof Map<?, ?>))
            throw invalid("HDFS 配置参数必须为 JSON 对象");
        var values = JSONUtil.parseObj(raw);
        values.forEach((key, value) -> {
            if (!Set.of("dfs.client.socket-timeout", "dfs.client.failover.max.attempts", "dfs.replication").contains(key))
                throw invalid("HDFS 客户端参数尚未提供安全等价映射：" + key);
            options.put("datahub.hadoop." + key, String.valueOf(integer(value, 1, key.equals("dfs.client.socket-timeout") ? 300_000 : 100, key)));
        });
    }
    private static String path(String value, boolean root) {
        if (value == null || value.isBlank() || value.length() > 1024 || !value.matches("[\\p{L}\\p{N}_./-]+")
                || root != value.startsWith("/") || root && value.equals("/") || value.contains("//")
                || Arrays.stream(value.split("/", -1)).anyMatch(part -> part.equals(".") || part.equals(".."))) throw invalid("HDFS 路径必须位于受管根目录且不包含 URL、跳转或通配符");
        return value.replaceAll("/+$", "");
    }
    private static int integer(Object value, int min, int max, String name) {
        try { int number = new BigDecimal(String.valueOf(value)).intValueExact(); if (number < min || number > max) throw new ArithmeticException(); return number; }
        catch (RuntimeException invalid) { throw invalid(name + "必须为范围内整数"); }
    }
    private static boolean bool(JSONObject config, String key, boolean fallback) {
        Object value = config.get(key); if (value == null) return fallback;
        if (Boolean.TRUE.equals(value) || "true".equals(value)) return true; if (Boolean.FALSE.equals(value) || "false".equals(value)) return false;
        throw invalid(key + "必须为布尔值");
    }
    private static String quote(String value) { return "`" + value.replace("`", "``") + "`"; }
    private static String literal(String value) { return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\t", "\\t") + "'"; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
