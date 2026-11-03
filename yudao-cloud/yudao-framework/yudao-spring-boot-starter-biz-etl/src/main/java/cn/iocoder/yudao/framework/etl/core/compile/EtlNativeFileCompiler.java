package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.*;

/** 将管理员授权的工作节点文件编译为 Spark 会话内文件视图，不读取平台附件或解析凭据。 */
public final class EtlNativeFileCompiler {
    private EtlNativeFileCompiler() { }

    public record TablePlan(String initializationSql, EtlNativeSqlCompiler.Table table) { }
    private record Column(String source, String target, String type) { }

    public static Set<String> supportedCodes() { return Set.of("read-csv", "read-excel", "read-json"); }

    /** 根目录挂载、任务绑定授权和禁止越界符号链接由调用方的管理员资源边界保证。 */
    public static TablePlan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming, String nodeId,
                                    String code, Map<String, Object> config, EtlDataxConnectorCompiler.Binding binding) {
        return compile(engine, streaming, nodeId, code, config, binding, Set.of());
    }

    public static TablePlan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming, String nodeId,
                                    String code, Map<String, Object> config, EtlDataxConnectorCompiler.Binding binding,
                                    Set<String> managedFunctions) {
        if (engine == null || streaming)
            throw invalid("当前文件输入仅支持有界批处理，不能作为持续采集源");
        if (!supportedCodes().contains(code)) throw invalid("当前原生文件输入只支持 CSV、Excel 和逐行 JSONL");
        if (binding == null || !binding.connectorCodes().contains(code)) throw invalid("文件输入尚未绑定允许当前组件的管理员受管资源");
        if (binding.authentication() != EtlDataxConnectorCompiler.Authentication.NONE)
            throw invalid("工作节点本地文件资源必须由管理员显式声明无凭据访问");
        if (config == null || JSONUtil.toJsonStr(config).length() > 500_000) throw invalid("文件输入配置缺失或超过大小限制");
        JSONObject values = JSONUtil.parseObj(config);
        rejectUnsupported(values, code);
        String path = boundPath(binding.rootPath(), first(values, "relativePath", "path"));
        if (engine == EtlNativeSqlCompiler.Engine.FLINK && !binding.installedPlugins().contains("flink-bounded-file")) throw invalid("工作节点尚未安装 Flink 有界文件适配器");
        if ("read-excel".equals(code)) return excel(engine, nodeId, values, binding, path, managedFunctions);
        boolean csv = "read-csv".equals(code);
        String extension = path.toLowerCase(Locale.ROOT);
        if (csv ? !extension.endsWith(".csv") : !extension.endsWith(".jsonl") && !extension.endsWith(".ndjson"))
            throw invalid("文件扩展名与当前组件不一致，请绑定单个 CSV 或 JSONL 文件");
        String encoding = values.getStr("encoding", values.getStr("charset", "UTF-8"));
        if (!Set.of("UTF-8", "GBK", "GB2312", "ISO-8859-1").contains(encoding) || !Charset.isSupported(encoding))
            throw invalid("当前文件编码尚未支持");
        boolean header = bool(values, "hasHeader", false);
        int row = integer(values, "startRow", header ? 2 : 1, 1, 10_000);
        if (row != (header ? 2 : 1)) throw invalid("当前原生读取只能从首条记录或表头后的首条记录开始，不支持跳过任意行");
        int startColumn = integer(values, "startColumn", 1, 1, 1024);
        var options = new LinkedHashMap<String, String>();
        options.put("path", path); options.put("encoding", encoding); options.put("mode", "FAILFAST");
        options.put("inferSchema", "false"); options.put("timeZone", "UTC");
        options.put("enableDateTimeParsingFallback", "false");
        if (csv) {
            String delimiter = values.getStr("fieldDelimiter", values.getStr("delimiter", ","));
            if ("TAB".equals(delimiter)) delimiter = "\t";
            options.put("delimiter", character(delimiter, "分隔符", false));
            options.put("header", String.valueOf(header));
            // 完整源字段按位置声明；表头名称与声明不一致时拒绝，避免列错位。
            options.put("enforceSchema", "false");
            options.put("quote", character(values.getStr("quote", "\""), "包裹符", true));
            options.put("escape", character(values.getStr("escape", "\""), "转义符", false));
            options.put("multiLine", String.valueOf(bool(values, "multiLine", true)));
            String nullValue = values.getStr("nullValue", "\\N");
            if (nullValue.isEmpty() || nullValue.length() > 64 || nullValue.codePoints().anyMatch(Character::isISOControl))
                throw invalid("CSV 空值标记必须为 1 至 64 个非控制字符，不能用空字符串混淆空文本与 null");
            options.put("nullValue", nullValue); options.put("emptyValue", "");
            options.put("ignoreLeadingWhiteSpace", "false"); options.put("ignoreTrailingWhiteSpace", "false");
            options.put("maxColumns", "1024"); options.put("maxCharsPerColumn", "1048576");
        } else {
            if (values.containsKey("nullValue")) throw invalid("JSONL 使用 JSON null，不接受 CSV 空值标记");
            if (header || startColumn != 1 || row != 1) throw invalid("JSONL 不支持表头或起始行列偏移，请使用显式 JSON 字段映射");
            for (String key : List.of("delimiter", "fieldDelimiter", "quote", "escape"))
                if (values.containsKey(key)) throw invalid("JSONL 不接受 CSV 专用配置：" + key);
            if (bool(values, "multiLine", false)) throw invalid("JSONL 必须每行一个完整 JSON 对象，不能启用跨行解析");
            options.put("multiLine", "false"); options.put("lineSep", "\n");
            options.put("allowSingleQuotes", "false"); options.put("allowNonNumericNumbers", "false");
        }
        var columns = columns(values, csv, options);
        if (startColumn > columns.size()) throw invalid("起始列超出完整源字段声明");
        String alias = "dh_native_file_" + stableAlias(nodeId);
        String rawAlias = alias + "_raw";
        var shape = new LinkedHashMap<String, String>();
        var projection = new ArrayList<String>();
        for (int index = startColumn - 1; index < columns.size(); index++) {
            var column = columns.get(index);
            shape.put(column.target(), column.type());
            projection.add(quote(column.source()) + " AS " + quote(column.target()));
        }
        if (engine == EtlNativeSqlCompiler.Engine.FLINK) {
            var plan = new JSONObject().set("fileFormat", csv ? "CSV" : "JSONL").set("rootPath", binding.rootPath()).set("relativePath", first(values, "relativePath", "path"))
                    .set("encoding", encoding).set("hasHeader", header).set("startColumn", startColumn).set("delimiter", options.getOrDefault("delimiter", ","))
                    .set("quote", options.getOrDefault("quote", "\"")).set("escape", options.getOrDefault("escape", "\"")).set("multiLine", csv && bool(values, "multiLine", true))
                    .set("nullValue", values.getStr("nullValue", "\\N")).set("maxRows", 100000).set("maxBytes", 52428800)
                    .set("fields", columns.stream().map(column -> Map.of("source", column.source(), "target", column.target(), "type", column.type())).toList());
            if (options.containsKey("dateFormat")) plan.set("dateFormat", options.get("dateFormat"));
            if (options.containsKey("timestampFormat")) plan.set("timestampFormat", options.get("timestampFormat"));
            var nativePlan = EtlNativeFlinkConnectorSql.table(nodeId, "file", false, shape, plan, List.of());
            return new TablePlan(nativePlan.initializationSql(), nativePlan.table());
        }
        String ddl = "CREATE TEMPORARY VIEW " + quote(rawAlias) + " ("
                + String.join(", ", columns.stream().map(column -> quote(column.source()) + " " + column.type()).toList())
                + ") USING " + (csv ? "csv" : "json") + " OPTIONS ("
                + String.join(", ", options.entrySet().stream().map(entry -> quote(entry.getKey()) + " " + literal(entry.getValue())).toList())
                + ");\nCREATE TEMPORARY VIEW " + quote(alias) + " AS SELECT " + String.join(", ", projection) + " FROM " + quote(rawAlias) + ";\n";
        return new TablePlan(ddl, new EtlNativeSqlCompiler.Table(List.of(alias), Collections.unmodifiableMap(shape),
                ProductTypeEnum.UNKNOWN, null, alias, false));
    }

    private static TablePlan excel(EtlNativeSqlCompiler.Engine engine, String nodeId, JSONObject config, EtlDataxConnectorCompiler.Binding binding,
                                   String path, Set<String> managedFunctions) {
        if (engine == EtlNativeSqlCompiler.Engine.SPARK && (managedFunctions == null || !managedFunctions.contains("DH_READ_EXCEL"))) throw invalid("工作节点尚未注册受管 Excel 读取函数");
        if (!path.toLowerCase(Locale.ROOT).endsWith(".xls") && !path.toLowerCase(Locale.ROOT).endsWith(".xlsx")) throw invalid("请选择受管 XLS 或 XLSX 文件");
        for (String key : List.of("encoding", "charset", "hasHeader", "fieldDelimiter", "delimiter", "quote", "escape", "multiLine", "nullValue"))
            if (config.containsKey(key)) throw invalid("Excel 不接受文本文件专用配置：" + key);
        int header = integer(config, "headerRow", 1, 0, 100_000);
        int start = integer(config, "startRow", header > 0 ? header + 1 : 1, 1, 100_000);
        int end = integer(config, "endRow", 0, 0, 100_000);
        int startColumn = integer(config, "startColumn", 1, 1, 256);
        if (end > 0 && end < start) throw invalid("Excel 结束行不能早于起始行");
        String sheet = config.getStr("sheetName", "");
        if (sheet.length() > 128 || sheet.codePoints().anyMatch(Character::isISOControl)) throw invalid("Excel 工作表名称无效");
        String formulas = config.getStr("formulaMode", "LITERAL");
        if (!Set.of("LITERAL", "CACHED").contains(formulas)) throw invalid("Excel 公式模式必须为文本或缓存值，不能在工作节点求值");
        var options = new LinkedHashMap<String, String>();
        var columns = columns(config, true, options);
        if ((long) startColumn + columns.size() - 1 > 256) throw invalid("Excel 起始列与字段数量超出列数预算");
        var expressions = new ArrayList<String>(); var shape = new LinkedHashMap<String, String>();
        for (int index = 0; index < columns.size(); index++) {
            Column column = columns.get(index); String raw = "cells[" + index + "]";
            String converted = "TRY_CAST(" + raw + " AS " + column.type() + ")";
            String valid = converted + " IS NOT NULL";
            if ("STRING".equals(column.type())) converted = raw;
            else if ("BIGINT".equals(column.type())) {
                converted = "TRY_CAST(TRY_CAST(" + raw + " AS DECIMAL(38,0)) AS BIGINT)";
                valid = converted + " IS NOT NULL AND " + raw + " RLIKE " + literal("^[+-]?[0-9]+(\\.0+)?$");
            } else if ("BOOLEAN".equals(column.type())) valid += " AND LOWER(" + raw + ") IN ('true', 'false')";
            else if (Set.of("DATE", "TIMESTAMP").contains(column.type())) {
                String format = options.get("DATE".equals(column.type()) ? "dateFormat" : "timestampFormat");
                if (format != null) {
                    String parsed = "COALESCE(TRY_CAST(" + raw + " AS TIMESTAMP), TRY_TO_TIMESTAMP(" + raw + ", " + literal(format) + "))";
                    converted = "TRY_CAST(" + parsed + " AS " + column.type() + ")"; valid = converted + " IS NOT NULL";
                }
            } else if (Set.of("DOUBLE", "FLOAT").contains(column.type()))
                valid += " AND NOT ISNAN(" + converted + ") AND ABS(" + converted + ") <> CAST('Infinity' AS DOUBLE)";
            String expression = "STRING".equals(column.type()) ? raw : "CASE WHEN " + raw + " IS NULL THEN CAST(NULL AS " + column.type()
                    + ") WHEN " + valid + " THEN " + converted + " ELSE RAISE_ERROR('Excel 字段类型转换失败') END";
            expressions.add(expression + " AS " + quote(column.target())); shape.put(column.target(), column.type());
        }
        String alias = "dh_native_file_" + stableAlias(nodeId);
        String root = binding.rootPath().endsWith("/") ? binding.rootPath().substring(0, binding.rootPath().length() - 1) : binding.rootPath();
        String relative = first(config, "relativePath", "path");
        if (engine == EtlNativeSqlCompiler.Engine.FLINK) {
            var plan = new JSONObject().set("fileFormat", "EXCEL").set("rootPath", root).set("relativePath", relative).set("sheetName", sheet)
                    .set("headerRow", header).set("startRow", start).set("endRow", end).set("startColumn", startColumn).set("skipEmptyRows", bool(config, "skipEmptyRows", true)).set("formulaMode", formulas)
                    .set("fields", columns.stream().map(column -> Map.of("source", column.source(), "target", column.target(), "type", column.type())).toList());
            if (options.containsKey("dateFormat")) plan.set("dateFormat", options.get("dateFormat"));
            if (options.containsKey("timestampFormat")) plan.set("timestampFormat", options.get("timestampFormat"));
            var nativePlan = EtlNativeFlinkConnectorSql.table(nodeId, "excel", false, shape, plan, List.of());
            return new TablePlan(nativePlan.initializationSql(), nativePlan.table());
        }
        String sql = "CREATE TEMPORARY VIEW " + quote(alias) + " AS SELECT " + String.join(", ", expressions)
                + " FROM (SELECT 1 AS seed) dh_excel_seed LATERAL VIEW DH_READ_EXCEL(" + literal(root) + ", " + literal(relative) + ", " + literal(sheet)
                + ", " + header + ", " + start + ", " + end + ", " + startColumn + ", " + columns.size() + ", "
                + bool(config, "skipEmptyRows", true) + ", " + literal(formulas) + ") dh_excel AS cells;\n";
        return new TablePlan(sql, new EtlNativeSqlCompiler.Table(List.of(alias), Collections.unmodifiableMap(shape), ProductTypeEnum.UNKNOWN, null, alias, false));
    }

    private static List<Column> columns(JSONObject config, boolean csv, Map<String, String> options) {
        var fields = config.getJSONArray("columns");
        if (fields == null || fields.isEmpty() || fields.size() > 1024) throw invalid("请声明 1 至 1024 个完整源字段及明确类型");
        var names = new HashSet<String>(); var outputs = new HashSet<String>(); var result = new ArrayList<Column>();
        int position = 0;
        for (Object field : fields) {
            JSONObject column = JSONUtil.parseObj(field);
            for (String key : column.keySet()) if (!Set.of("name", "alias", "type", "dataType", "key", "jsonPath", "index", "format", "length", "precision", "scale", "comment").contains(key))
                throw invalid("当前文件字段尚未支持配置：" + key);
            if (column.containsKey("type") && column.containsKey("dataType") && !Objects.equals(column.getStr("type"), column.getStr("dataType")))
                throw invalid("字段类型声明不一致");
            if (column.containsKey("key") && column.containsKey("jsonPath") && !Objects.equals(column.getStr("key"), column.getStr("jsonPath")))
                throw invalid("JSON 字段路径声明不一致");
            String target = identifier(column.getStr("alias", column.getStr("name")), "输出字段");
            String source = column.getStr("name");
            String key = first(column, "key", "jsonPath");
            if (!csv && key != null && !key.isBlank()) source = key.startsWith("$.") ? key.substring(2) : key;
            source = identifier(source, "源字段");
            if (!names.add(source) || !outputs.add(target)) throw invalid("源字段或输出字段重复");
            if (csv && integer(column, "index", position, 0, 1023) != position)
                throw invalid("CSV 必须按位置声明从 0 开始连续的完整源字段，选择起始列请使用 startColumn");
            if (csv && key != null && !key.isBlank() && !key.equals(source)) throw invalid("CSV 不接受 JSON 字段路径");
            String declared = column.getStr("type", column.getStr("dataType", "")).toUpperCase(Locale.ROOT);
            boolean decimalMetadata = Set.of("BIGNUMBER", "DECIMAL", "NUMERIC").contains(declared);
            if (decimalMetadata) {
                int precision = integer(column, "length", -1, 1, 38);
                int scale = integer(column, "precision", integer(column, "scale", 0, 0, 38), 0, 38);
                if (scale > precision || !blank(column.getStr("scale")) && scale != integer(column, "scale", 0, 0, 38)) throw invalid("小数字段总长度或小数精度不一致");
                declared = "DECIMAL(" + precision + "," + scale + ")";
            } else for (String attribute : List.of("length", "precision", "scale")) {
                if (!blank(column.getStr(attribute))) throw invalid("当前文件输入尚未支持该字段类型的长度或小数精度调整");
            }
            String type = switch (declared) {
                case "STRING", "VARCHAR", "CHAR", "TEXT" -> "STRING";
                case "INTEGER", "INT", "LONG", "BIGINT", "SMALLINT", "TINYINT" -> "BIGINT";
                case "NUMBER", "DOUBLE" -> "DOUBLE";
                case "FLOAT" -> "FLOAT";
                case "BOOLEAN" -> "BOOLEAN";
                case "DATE" -> "DATE";
                case "TIMESTAMP" -> "TIMESTAMP";
                default -> {
                    if (!declared.matches("DECIMAL\\([1-9][0-9]?,[0-9]{1,2}\\)")) throw invalid("字段类型尚未支持或未明确声明：" + source);
                    String[] sizes = declared.substring(8, declared.length() - 1).split(",");
                    int precision = Integer.parseInt(sizes[0]), scale = Integer.parseInt(sizes[1]);
                    if (precision > 38 || scale > precision) throw invalid("DECIMAL 精度或小数位无效");
                    yield declared;
                }
            };
            String format = column.getStr("format");
            if (!blank(format)) {
                String option = switch (type) { case "DATE" -> "dateFormat"; case "TIMESTAMP" -> "timestampFormat"; default -> throw invalid("只有日期或时间戳字段可以声明格式"); };
                if (!format.matches("[A-Za-z0-9 :._/'T+\\-]{1,128}")) throw invalid("日期格式无效");
                if (options.containsKey(option) && !format.equals(options.get(option))) throw invalid("同类型日期字段必须使用相同格式");
                options.put(option, format);
            }
            result.add(new Column(source, target, type)); position++;
        }
        return result;
    }

    private static void rejectUnsupported(JSONObject config, String code) {
        if (config.containsKey("fileId") || !blank(config.getStr("fileUrl")))
            throw invalid("平台上传附件不自动获得工作节点文件授权，请使用管理员受管资源绑定和相对路径");
        String expected = "read-csv".equals(code) ? "CSV" : "read-excel".equals(code) ? "EXCEL" : "JSONL";
        if (!expected.equals(config.getStr("fileFormat", expected))) throw invalid("文件格式与组件不一致");
        if (!"FULL".equals(config.getStr("readMode", "FULL"))) throw invalid("文件增量读取尚未提供受管游标");
        if (!"read-excel".equals(code) && !bool(config, "skipEmptyRows", true)) throw invalid("原生文件源不能保证保留空行，请启用跳过空行");
        var allowed = new HashSet<>(Set.of("columns", "resourceBindingId", "relativePath", "path", "encoding", "charset", "fileFormat", "hasHeader",
                "startRow", "startColumn", "fieldDelimiter", "delimiter", "quote", "escape", "multiLine", "nullValue", "readMode", "skipEmptyRows", "name", "nodeName", "description"));
        if ("read-excel".equals(code)) allowed.addAll(Set.of("sheetName", "headerRow", "endRow", "formulaMode"));
        for (String key : config.keySet()) if (!allowed.contains(key)) throw invalid("当前原生文件输入尚未支持配置：" + key);
        if (config.containsKey("path") && config.containsKey("relativePath") && !Objects.equals(config.getStr("path"), config.getStr("relativePath")))
            throw invalid("相对路径配置不一致");
        if (config.containsKey("encoding") && config.containsKey("charset") && !Objects.equals(config.getStr("encoding"), config.getStr("charset")))
            throw invalid("文件编码配置不一致");
    }

    private static String boundPath(String root, String relative) {
        if (root == null || !root.startsWith("/") || root.equals("/")) throw invalid("管理员受管文件根目录无效");
        checkPath(root, true); checkPath(relative, false);
        return (root.endsWith("/") ? root.substring(0, root.length() - 1) : root) + "/" + relative;
    }
    private static void checkPath(String value, boolean root) {
        if (value == null || value.isBlank() || value.length() > 1024 || !value.matches("[A-Za-z0-9_./-]+")
                || !root && value.startsWith("/") || value.endsWith("/") && !root || value.contains("//")
                || Arrays.stream(value.split("/", -1)).anyMatch(segment -> segment.equals(".") || segment.equals("..")))
            throw invalid("文件路径必须位于受管根目录，且不含 URL、通配符、模板或目录跳转");
    }
    private static String stableAlias(String node) {
        if (node == null || node.isBlank() || node.length() > 256) throw invalid("节点编号无效");
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(node).substring(0, 24);
    }
    private static String identifier(String value, String label) {
        if (value == null || !value.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,127}")) throw invalid(label + "必须为普通字段名，不接受嵌套 JSON 路径");
        return value;
    }
    private static String character(String value, String label, boolean emptyAllowed) {
        if (value == null || !emptyAllowed && value.isEmpty() || value.length() > 1 || value.equals("\r") || value.equals("\n"))
            throw invalid(label + "必须为单个非换行字符");
        return value;
    }
    private static int integer(JSONObject values, String key, int fallback, int min, int max) {
        try {
            int number = values.get(key) == null ? fallback : new BigDecimal(values.get(key).toString()).intValueExact();
            if (number < min || number > max) throw invalid(key + "超出允许范围");
            return number;
        } catch (RuntimeException exception) { throw invalid(key + "必须为范围内的整数"); }
    }
    private static boolean bool(JSONObject values, String key, boolean fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (Boolean.TRUE.equals(value) || "true".equals(value)) return true;
        if (Boolean.FALSE.equals(value) || "false".equals(value)) return false;
        throw invalid(key + "必须为布尔值");
    }
    private static String first(JSONObject values, String left, String right) {
        return blank(values.getStr(left)) ? values.getStr(right) : values.getStr(left);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String quote(String value) { return "`" + value.replace("`", "``") + "`"; }
    private static String literal(String value) {
        if (value.codePoints().anyMatch(code -> Character.isISOControl(code) && code != '\n' && code != '\t')) throw invalid("文件配置包含控制字符");
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\t", "\\t") + "'";
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
