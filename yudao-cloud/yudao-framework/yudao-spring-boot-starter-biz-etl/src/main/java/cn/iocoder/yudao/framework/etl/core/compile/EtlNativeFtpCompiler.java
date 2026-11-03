package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedFtp;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/** FTP只把引用和无凭据执行计划交给Spark，工作节点负责认证与唯一新文件传输。 */
public final class EtlNativeFtpCompiler {
    private EtlNativeFtpCompiler() { }
    public static Set<String> supportedCodes(EtlNativeSqlCompiler.Engine engine, boolean streaming) {
        return engine != null && !streaming ? Set.of("read-ftp", "write-ftp") : Set.of();
    }
    public static EtlNativeConnectorCompiler.TablePlan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming, String identity,
            String code, Map<String, Object> values, EtlDataxConnectorCompiler.Binding binding, Set<String> functions, Set<String> secrets) {
        if (!supportedCodes(engine, streaming).contains(code)) throw invalid("FTP当前仅支持有界读取和新文件写入");
        if (binding == null || !binding.connectorCodes().contains(code) || binding.authentication() != EtlDataxConnectorCompiler.Authentication.REQUIRED
                || !binding.installedPlugins().containsAll(Set.of(engine == EtlNativeSqlCompiler.Engine.SPARK ? "spark-ftp" : "flink-bounded-ftp", "ftp-chroot")) || binding.addresses().size() != 1
                || !"/".equals(binding.rootPath())) throw invalid("FTP需要专用账户锁定的虚拟根目录、已授权执行资源、秘密引用与工作节点适配器");
        boolean output = code.equals("write-ftp");
        if (!output && engine == EtlNativeSqlCompiler.Engine.SPARK && !functions.contains("DH_READ_FTP")) throw invalid("工作节点尚未注册FTP读取函数");
        if (values == null || JSONUtil.toJsonStr(values).length() > 500000) throw invalid("FTP配置缺失或超过大小预算");
        var config = JSONUtil.parseObj(values);
        for (String key : config.keySet()) if (!Set.of("databaseId", "resourceBindingId", "path", "fileType", "fieldDelimiter", "ftpConfig", "fileName", "writeModeType", "columns", "compression", "encoding", "credentialRef", "maxBytes", "maxRows", "fieldMappings", "syncMode", "syncDelete").contains(key)) throw invalid("FTP包含未适配参数：" + key);
        String syncMode = config.getStr("syncMode", "AUTO");
        if (output && (config.containsKey("syncMode") || config.containsKey("syncDelete"))) throw invalid("FTP输出不接受输入同步选项");
        if (!Set.of("AUTO", "SINGLE_FILE", "DIRECTORY").contains(syncMode)) throw invalid("FTP同步方式无效");
        if (config.containsKey("syncDelete") && !Set.of("0", "false").contains(String.valueOf(config.get("syncDelete"))))
            throw invalid("FTP同步删除尚无提交后安全删除协议，不能开启");
        String reference = config.getStr("credentialRef", "");
        if (!reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || !secrets.contains(reference)) throw invalid("当前任务未获授权使用FTP秘密引用");
        URI endpoint = URI.create(binding.addresses().get(0));
        if (!Set.of("ftp", "ftps").contains(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !Set.of("", "/").contains(endpoint.getPath()) || endpoint.getPort() < 1 || endpoint.getPort() > 65535) throw invalid("FTP执行资源必须声明无凭据服务地址和端口");
        EtlManagedFtp.path(binding.rootPath(), true); EtlManagedFtp.path(config.getStr("path"), false);
        String root = endpoint.getScheme() + "://" + endpoint.getRawAuthority() + binding.rootPath();
        String type = config.getStr("fileType", "csv"), delimiter = config.getStr("fieldDelimiter", ","), encoding = config.getStr("encoding", "UTF-8");
        if (delimiter.equals("TAB")) delimiter = "\t";
        if (!Set.of("csv", "text").contains(type) || !Set.of("UTF-8", "GBK", "GB2312", "ISO-8859-1").contains(encoding)
                || delimiter.length() != 1 || Set.of("\r", "\n", "\0", "\"").contains(delimiter)) throw invalid("FTP文件类型、编码或分隔符无效");
        var options = config.getJSONObject("ftpConfig"); if (options == null) options = JSONUtil.createObj();
        for (String key : options.keySet()) if (!Set.of("passiveMode", "connectTimeout", "dataTimeout", "implicitTls", "hasHeader", "includeHeader", "nullLiteral").contains(key)) throw invalid("FTP客户端参数未提供明确映射：" + key);
        options.set("connectTimeout", integer(options.get("connectTimeout"), 10000, 300000)); options.set("dataTimeout", integer(options.get("dataTimeout"), 30000, 300000));
        for (String key : Set.of("passiveMode", "implicitTls", "hasHeader", "includeHeader")) if (options.containsKey(key) && !(options.get(key) instanceof Boolean)) throw invalid("FTP客户端布尔参数类型无效");
        if (endpoint.getScheme().equals("ftp") && options.getBool("implicitTls", false)) throw invalid("implicitTls仅适用于FTPS执行资源");
        if (output && options.containsKey("hasHeader") || !output && options.containsKey("includeHeader")) throw invalid("FTP表头参数与输入输出方向不符");
        String compression = config.getStr("compression", "");
        if (!Set.of("", "none", "gzip").contains(compression)) throw invalid("FTP当前支持原始或gzip流，其他压缩需要对应工作节点适配");
        String nullLiteral = options.getStr("nullLiteral", "\\N");
        if (nullLiteral.isEmpty() || nullLiteral.length() > 100 || nullLiteral.contains(delimiter) || nullLiteral.chars().anyMatch(character -> character < 32 || character == 127)) throw invalid("FTP空值标识无效或与文件格式冲突");
        var columns = config.getJSONArray("columns"); var apiColumns = EtlNativeDeclaredColumns.parse(columns);
        var indexes = new java.util.HashSet<Integer>();
        for (Object value : columns) {
            var column = JSONUtil.parseObj(value); String name = column.getStr("name", "");
            if (!output && !column.getStr("key", "").isBlank() && !column.getStr("key").equals(name)) throw invalid("FTP输入字段不接受JSON取值路径");
            if (!output) {
                if (column.get("index") == null || !indexes.add(integer(column.get("index"), -1, 1024, true))) throw invalid("FTP输入字段索引必须明确且不重复");
                if (!Set.of("", "-").contains(column.getStr("format", ""))) throw invalid("FTP输入日期时间当前使用ISO格式");
            } else if (!Set.of("", "-").contains(column.getStr("format", ""))) {
                if (!Set.of("DATE", "TIMESTAMP").contains(column.getStr("type", "").toUpperCase(java.util.Locale.ROOT)) || column.getStr("format").length() > 128) throw invalid("FTP日期格式仅适用于日期时间字段");
                try { java.time.format.DateTimeFormatter.ofPattern(column.getStr("format")); } catch (RuntimeException failure) { throw invalid("FTP日期时间格式无效"); }
            }
        }
        var plan = JSONUtil.createObj().set("rootUri", root).set("path", config.getStr("path")).set("credentialRef", reference).set("ftpConfig", options)
                .set("syncMode", syncMode).set("fileType", type).set("fieldDelimiter", delimiter).set("encoding", encoding).set("compression", compression).set("columns", columns)
                .set("hasHeader", options.getBool("hasHeader", false)).set("includeHeader", options.getBool("includeHeader", false)).set("nullLiteral", nullLiteral)
                .set("maxBytes", integer(config.get("maxBytes"), 8388608, 67108864)).set("maxRows", integer(config.get("maxRows"), 100000, 1000000));
        var source = EtlNativeHttpCompiler.rowSource(engine, identity, apiColumns, plan, "DH_READ_FTP", "ftp");
        if (!output) {
            for (String key : Set.of("fileName", "writeModeType", "fieldMappings")) if (config.containsKey(key)) throw invalid("FTP输入不接受输出参数：" + key);
            return source;
        }
        if (integer(config.get("writeModeType"), 2, 2) != 2) throw invalid("FTP只允许创建新文件，不能覆盖或清空既有文件");
        String prefix = config.getStr("fileName", "data"); if (!prefix.matches("[A-Za-z0-9_-]{1,100}")) throw invalid("FTP文件名前缀无效");
        plan.set("fileName", prefix);
        var mappings = EtlNativeOutputMappings.require(config);
        if (engine == EtlNativeSqlCompiler.Engine.FLINK) return EtlNativeFlinkConnectorSql.table(identity, "ftp", true, source.table().columns(), plan, mappings);
        String payload = Base64.getEncoder().encodeToString(plan.toString().getBytes(StandardCharsets.UTF_8));
        String alias = source.table().physicalTable();
        String sql = "CREATE TEMPORARY VIEW `" + alias + "` (" + String.join(", ", source.table().columns().entrySet().stream().map(entry -> "`" + entry.getKey() + "` " + entry.getValue()).toList())
                + ") USING cn.iocoder.yudao.framework.etl.spark.EtlFtpTextProvider OPTIONS (`datahub.plan` '" + payload + "');\n";
        return new EtlNativeConnectorCompiler.TablePlan(sql, source.table(), mappings);
    }
    private static int integer(Object value, int fallback, int maximum) { return integer(value, fallback, maximum, false); }
    private static int integer(Object value, int fallback, int maximum, boolean zero) {
        try { int parsed = value == null ? fallback : new BigDecimal(value.toString()).intValueExact(); if (parsed < (zero ? 0 : 1) || parsed > maximum) throw invalid("FTP数值参数超限"); return parsed; }
        catch (RuntimeException failure) { throw invalid("FTP数值参数无效"); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
