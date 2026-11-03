package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.*;

/** ETL 文件和文档连接器到官方 DataX 插件的无凭据编译，不执行网络访问或读取工作节点文件。 */
public final class EtlDataxConnectorCompiler {
    private EtlDataxConnectorCompiler() { }

    public enum Authentication { REQUIRED, NONE, ANONYMOUS }
    public enum Failure { UNBOUND_RESOURCE, INVALID_CONFIG, UNSUPPORTED_PLUGIN, SECURE_BINDING_REQUIRED }
    public static final class ConfigurationException extends IllegalArgumentException {
        private final Failure failure;
        public ConfigurationException(Failure failure, String message) { super(message); this.failure = failure; }
        public Failure failure() { return failure; }
    }

    /** 仅由管理员配置解析。rootPath 必须由运行环境以最小权限挂载，并禁止任务用户建立越界符号链接。 */
    public record Binding(Set<String> connectorCodes, String rootPath, String defaultFs, List<String> addresses,
                          String database, Authentication authentication, Set<String> installedPlugins) {
        public Binding {
            connectorCodes = connectorCodes == null ? Set.of() : Set.copyOf(connectorCodes);
            addresses = addresses == null ? List.of() : List.copyOf(addresses);
            installedPlugins = installedPlugins == null ? Set.of() : Set.copyOf(installedPlugins);
            authentication = authentication == null ? Authentication.REQUIRED : authentication;
        }
    }
    public record Plugin(String name, Map<String, Object> parameter) {
        public Plugin { parameter = Collections.unmodifiableMap(new LinkedHashMap<>(parameter)); }
        public Map<String, Object> taskParameter() { return Map.of("name", name, "parameter", parameter); }
    }

    /** 可编译不表示目标服务已联通；调用方仍须验证绑定授权、工作组和实际文件/网络隔离。 */
    public static Set<String> supportedCodes() {
        return Set.of("read-hdfs", "write-hdfs", "read-csv", "write-csv", "read-mongodb", "write-mongodb");
    }

    /** DataX 行按位置传递，不能把目标字段重新排序或跳过而仍显示同名映射成功。 */
    public static void validatePair(Map<String, Object> source, Map<String, Object> target) {
        var left = columns(JSONUtil.parseObj(source), false);
        var right = columns(JSONUtil.parseObj(target), false);
        if (!left.equals(right)) throw invalid("DataX 输入输出字段的数量、顺序、名称和类型必须一致；字段转换需要独立原生适配");
    }

    public static Plugin compile(String code, Map<String, Object> config, Binding binding) {
        if (config == null) throw invalid("连接器配置缺失");
        String serialized = JSONUtil.toJsonStr(config);
        if (serialized.length() > 500_000) throw invalid("连接器配置超过大小限制");
        JSONObject values = JSONUtil.parseObj(serialized);
        rejectSecrets(values, 0);
        if (!supportedCodes().contains(code)) {
            if (Set.of("read-ftp", "write-ftp", "read-odps", "write-odps").contains(code))
                throw new ConfigurationException(Failure.SECURE_BINDING_REQUIRED,
                        "官方 DataX " + code + " 插件需要内联凭据；当前 DS 自定义 JSON 不提供安全凭据引用，尚未绑定安全执行适配器");
            throw new ConfigurationException(Failure.UNSUPPORTED_PLUGIN,
                    "当前受管 DataX 发行包没有已验证的 " + code + " 插件或编译适配，请改用已配置的原生引擎连接器");
        }
        if (binding == null || !binding.connectorCodes().contains(code))
            throw new ConfigurationException(Failure.UNBOUND_RESOURCE, "连接器尚未绑定允许该组件的管理员受管资源");
        boolean output = code.startsWith("write-");
        rejectIgnored(values, output);
        String name = switch (code) {
            case "read-hdfs" -> "hdfsreader";
            case "write-hdfs" -> "hdfswriter";
            case "read-csv" -> "txtfilereader";
            case "write-csv" -> "txtfilewriter";
            case "read-mongodb" -> "mongodbreader";
            case "write-mongodb" -> "mongodbwriter";
            default -> throw invalid("未知插件");
        };
        if (!binding.installedPlugins().contains(name))
            throw new ConfigurationException(Failure.UNSUPPORTED_PLUGIN, "管理员未确认工作节点安装插件：" + name);
        var parameters = code.endsWith("mongodb") ? mongo(values, binding, output)
                : files(values, binding, output, code.endsWith("hdfs"));
        return new Plugin(name, parameters);
    }

    private static Map<String, Object> files(JSONObject config, Binding binding, boolean output, boolean hdfs) {
        if (binding.authentication() != Authentication.NONE)
            throw new ConfigurationException(Failure.SECURE_BINDING_REQUIRED,
                    "文件资源必须由管理员显式声明无凭据访问；Kerberos/FTP 密钥尚无安全引用适配");
        String relative = first(config, "path", output ? "filePath" : "relativePath");
        // fileUrl 仅可由业务层解析为受管文件授权，不能作为节点到工作机的任意下载通道。
        if (!blank(config.getStr("fileUrl"))) throw invalid("DataX 文件输入请使用受管资源绑定和相对路径，不能使用文件 URL");
        String path = boundPath(binding.rootPath(), relative);
        String type = config.getStr("fileType", hdfs ? "text" : "csv").toLowerCase(Locale.ROOT);
        if (!Set.of("csv", "text").contains(type)) throw invalid("当前已验证的文件格式仅支持 csv/text");
        if (hdfs && output && "csv".equals(type))
            throw invalid("官方 HDFS writer 的 text 格式不提供 CSV 引号转义，请选择 text 或使用 CSV 文件输出");
        String charset = config.getStr("encoding", config.getStr("charset", "UTF-8"));
        if (!Set.of("UTF-8", "GBK", "GB2312", "ISO-8859-1").contains(charset) || !Charset.isSupported(charset))
            throw invalid("文件编码尚未验证");
        String delimiter = config.getStr("fieldDelimiter", config.getStr("delimiter", ","));
        if ("TAB".equals(delimiter)) delimiter = "\t";
        if (delimiter.length() != 1 || delimiter.equals("\r") || delimiter.equals("\n")) throw invalid("分隔符必须为单个非换行字符");
        var result = new LinkedHashMap<String, Object>();
        result.put("path", output || hdfs ? path : List.of(path));
        result.put("encoding", charset); result.put("fieldDelimiter", delimiter);
        if (hdfs) {
            validateDefaultFs(binding.defaultFs()); result.put("defaultFS", binding.defaultFs());
            result.put("fileType", type); result.put("haveKerberos", false);
        }
        var columns = columns(config, !output);
        if (output) {
            requireCreateNew(config);
            result.put("fileName", filename(config.getStr("fileName", "data")));
            result.put("writeMode", "nonConflict");
            if (hdfs) {
                result.put("column", columns.stream().map(column -> Map.of("name", column.get("name"),
                        "type", hiveType((String) column.get("type")))).toList());
            } else {
                result.put("fileFormat", type);
                if (config.getBool("includeHeader", true)) result.put("header", columns.stream().map(column -> column.get("name")).toList());
            }
        } else {
            result.put("column", columns.stream().map(column -> Map.of("index", column.get("index"), "type", column.get("type"))).toList());
            result.put("skipHeader", config.getBool("hasHeader", false));
            if (config.getBool("skipEmptyRows", true) == false)
                throw invalid("DataX 文本插件不能保证保留空行语义，请启用跳过空行");
        }
        String compression = config.getStr("compression", "").toLowerCase(Locale.ROOT);
        if (!compression.isBlank()) {
            Set<String> allowed = output ? Set.of("gzip", "bzip2") : Set.of("gzip", "bzip2", "zip");
            if (!allowed.contains(compression)) throw invalid("当前文件插件不支持该压缩方式");
            result.put("compress", compression);
        }
        return result;
    }

    private static Map<String, Object> mongo(JSONObject config, Binding binding, boolean output) {
        if (binding.authentication() != Authentication.ANONYMOUS)
            throw new ConfigurationException(Failure.SECURE_BINDING_REQUIRED,
                    "MongoDB 只有管理员显式绑定 ANONYMOUS 才允许无凭据任务，缺失密码不能解释为匿名访问");
        if (binding.addresses().isEmpty() || binding.addresses().size() > 16) throw invalid("MongoDB 受管地址缺失或过多");
        for (String address : binding.addresses()) {
            if (address == null || !address.matches("[A-Za-z0-9.-]+:[0-9]{1,5}")) throw invalid("MongoDB 受管地址格式无效");
            int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
            if (port < 1 || port > 65535) throw invalid("MongoDB 受管端口无效");
        }
        String database = identifier(binding.database(), "MongoDB 数据库");
        String collection = identifier(config.getStr("tableName"), "MongoDB 集合");
        if (config.getInt("ifSql", 0) != 0 || !blank(config.getStr("sql")) || !blank(config.getStr("whereCondition")))
            throw invalid("MongoDB 连接器不接受关系型 SQL/where，请使用全量集合读取");
        var result = new LinkedHashMap<String, Object>();
        result.put("address", binding.addresses()); result.put("dbName", database); result.put("collectionName", collection);
        result.put("column", columns(config, false).stream().map(column -> Map.of("name", column.get("name"), "type", column.get("type"))).toList());
        if (output) {
            requireCreateNew(config);
            result.put("writeMode", Map.of("isReplace", false));
            // 已核实官方 MongoDB writer 固定批大小 1000，传入任意值会被插件忽略。
            if (integer(config, "batchSize", 1000, 1, 100_000) != 1000)
                throw invalid("官方 MongoDB writer 固定批量大小 1000，不能应用其他单次写入数据配置");
        }
        return result;
    }

    private static List<Map<String, Object>> columns(JSONObject config, boolean indexRequired) {
        JSONArray columns = config.getJSONArray("columns");
        if (columns == null || columns.isEmpty() || columns.size() > 1024) throw invalid("请配置 1 至 1024 个明确的属性字段及类型");
        var names = new HashSet<String>(); var indexes = new HashSet<Integer>(); var result = new ArrayList<Map<String, Object>>();
        for (Object raw : columns) {
            JSONObject column = JSONUtil.parseObj(raw);
            String name = identifier(column.getStr("name"), "属性字段");
            if (!names.add(name)) throw invalid("属性字段重复");
            String declaredType = column.getStr("type");
            if (blank(declaredType)) throw invalid("字段类型尚未确定，请显式选择类型：" + column.getStr("name"));
            if (!blank(column.getStr("format"))) throw invalid("当前 DataX 连接器尚未支持字段格式转换：" + column.getStr("name"));
            String type = declaredType.toUpperCase(Locale.ROOT);
            String dataxType = switch (type) {
                case "STRING", "VARCHAR", "CHAR" -> "string";
                case "INTEGER", "INT", "LONG", "BIGINT", "SMALLINT", "TINYINT" -> "long";
                case "DOUBLE", "FLOAT" -> "double";
                case "BOOLEAN" -> "boolean";
                // DataX 的 date 不保留高精度/时区语义；TIMESTAMP 不能静默降精度。
                case "DATE" -> "date";
                default -> throw invalid("DataX 尚未验证字段类型的无损映射：" + type);
            };
            var value = new LinkedHashMap<String, Object>(); value.put("name", name); value.put("type", dataxType);
            if (indexRequired) {
                int index = integer(column, "index", -1, 0, 100_000);
                if (!indexes.add(index)) throw invalid("属性字段序号重复");
                value.put("index", index);
            }
            String key = column.getStr("key", "");
            if (!key.isBlank() && !key.equals(name)) throw invalid("当前 DataX 连接器不支持 JSONPath 或字段重命名，请使用同名字段");
            result.add(value);
        }
        return result;
    }

    private static void rejectIgnored(JSONObject config, boolean output) {
        if (config.containsKey("fileId") || Set.of("CSV", "EXCEL", "JSONL").contains(config.getStr("fileFormat", "")))
            throw invalid("上传附件尚未绑定到DS工作节点受管资源，不能用已有路径代替当前上传文件执行");
        if (!"FULL".equals(config.getStr("readMode", "FULL"))) throw invalid("增量读取需要受管游标，当前 DataX 连接器仅支持全量");
        if (config.getInt("ifSql", 0) != 0 || !blank(config.getStr("sql"))) throw invalid("DataX 文件/文档连接器不接受关系型 SQL");
        if (config.containsKey("selectedColumns") || config.containsKey("maxRows"))
            throw invalid("DataX 文件/文档连接器不支持额外字段选择或最大行数选项，请使用完整属性字段");
        for (String key : List.of("hadoopConfig", "ftpConfig", "config")) {
            Object value = config.get(key);
            if (value != null && !JSONUtil.parseObj(value).isEmpty()) throw invalid("连接器扩展参数必须由管理员受管环境提供，不能保存在流程中：" + key);
        }
        for (String key : List.of("preSql", "postSql", "query", "primaryKeys", "whereCondition"))
            if (!blank(config.getStr(key))) throw invalid("当前 DataX 连接器尚未支持该选项：" + key);
        if (config.getBool("targetTruncate", false)) throw invalid("DataX 输出不能清空目标");
        if (config.containsKey("fieldMappings")) {
            JSONArray mappings;
            try { mappings = config.getJSONArray("fieldMappings"); }
            catch (RuntimeException failure) { throw invalid("字段映射必须为数组"); }
            if (mappings == null || mappings.isEmpty()) throw invalid("字段映射不能为空");
            List<String> fields = columns(config, false).stream().map(column -> String.valueOf(column.get("name"))).toList();
            var mapped = new ArrayList<String>();
            for (Object raw : mappings) {
                var mapping = JSONUtil.parseObj(raw);
                if (!mapping.getBool("enabled", true) || !Objects.equals(mapping.getStr("source"), mapping.getStr("target")))
                    throw invalid("DataX 文件/文档输出当前仅支持完整同名字段，不能忽略或重排映射");
                mapped.add(mapping.getStr("source"));
            }
            if (!fields.equals(mapped)) throw invalid("字段映射必须与属性字段完整同序对应");
        }
        if (output && config.getInt("dataSyncType", 1) != 1) throw invalid("DataX 输出当前仅支持追加插入");
    }

    private static void requireCreateNew(JSONObject config) {
        if (config.getInt("writeModeType", 2) != 2 || !Set.of("INSERT", "nonConflict").contains(config.getStr("writeMode", "INSERT")))
            throw invalid("当前 DataX 输出禁止覆盖/清空/更新；文件以 nonConflict 创建新文件，MongoDB 仅插入");
    }

    private static String hiveType(String type) {
        return switch (type) { case "long" -> "BIGINT"; case "double" -> "DOUBLE"; case "string" -> "STRING";
            case "boolean" -> "BOOLEAN"; case "date" -> "DATE"; default -> throw invalid("HDFS 字段类型无效"); };
    }
    private static String boundPath(String root, String relative) {
        if (root == null || !root.startsWith("/") || root.equals("/") || root.length() > 1024) throw invalid("受管文件根目录无效");
        checkPath(root, true); checkPath(relative, false);
        return (root.endsWith("/") ? root.substring(0, root.length() - 1) : root) + "/" + relative;
    }
    private static void checkPath(String value, boolean root) {
        if (value == null || value.isBlank() || value.length() > 1024 || !value.matches("[A-Za-z0-9_./-]+")
                || !root && value.startsWith("/") || Arrays.stream(value.split("/", -1)).anyMatch(segment -> segment.equals(".") || segment.equals(".."))
                || value.contains("//")) throw invalid("路径必须位于受管根目录，且不含通配符、URL、模板或目录跳转");
    }
    private static void validateDefaultFs(String value) {
        try {
            URI uri = URI.create(value);
            if (!"hdfs".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || uri.getPath() != null && !uri.getPath().isEmpty())
                throw invalid("受管 HDFS defaultFS 无效");
        } catch (RuntimeException failure) { throw invalid("受管 HDFS defaultFS 无效"); }
    }
    private static String filename(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}") || value.contains("..")) throw invalid("输出文件名前缀无效");
        return value;
    }
    private static String identifier(String value, String label) {
        if (value == null || !value.matches("[\\p{L}_][\\p{L}\\p{N}_.-]{0,127}") || value.contains("..")) throw invalid(label + "名称无效");
        return value;
    }
    private static int integer(JSONObject config, String key, int fallback, int min, int max) {
        Object value = config.get(key); int number;
        try { number = value == null ? fallback : new java.math.BigDecimal(value.toString()).intValueExact(); }
        catch (RuntimeException failure) { throw invalid(key + "必须为整数"); }
        if (number < min || number > max) throw invalid(key + "超过允许范围"); return number;
    }
    private static String first(JSONObject config, String left, String right) {
        String value = config.getStr(left); return blank(value) ? config.getStr(right) : value;
    }
    private static void rejectSecrets(Object value, int depth) {
        if (depth > 16) throw invalid("连接器配置嵌套过深");
        if (value instanceof Map<?, ?> map) for (var entry : map.entrySet()) {
            if (String.valueOf(entry.getKey()).matches("(?i).*?(password|passwd|token|secret|credential|accesskey|accessid|authorization|username|keytab|principal).*"))
                throw invalid("连接器配置不能保存凭据");
            rejectSecrets(entry.getValue(), depth + 1);
        } else if (value instanceof Iterable<?> values) for (Object child : values) rejectSecrets(child, depth + 1);
        else if (value instanceof String text && (text.contains("${") || text.contains("$[") || text.contains("!{") || text.indexOf('\0') >= 0))
            throw invalid("连接器配置不能包含引擎展开模板");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ConfigurationException invalid(String message) { return new ConfigurationException(Failure.INVALID_CONFIG, message); }
}
