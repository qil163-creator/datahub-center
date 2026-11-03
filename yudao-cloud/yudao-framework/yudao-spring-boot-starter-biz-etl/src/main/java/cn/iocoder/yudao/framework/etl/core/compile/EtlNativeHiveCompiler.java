package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.math.BigDecimal;
import java.util.*;

/** Hive 平台目录到原生引擎受管同物理表绑定的有界读写，不创建连接或变更 Hive 会话参数。 */
public final class EtlNativeHiveCompiler {
    private EtlNativeHiveCompiler() { }
    private static final Set<String> KEYS = Set.of("databaseId", "tableName", "schemaName", "columns", "selectedColumns",
            "ifSql", "sql", "readMode", "whereCondition", "writeModeType", "writeMode", "fieldMappings", "preSql", "postSql",
            "primaryKeys", "batchSize", "dataSyncType", "targetTruncate", "name", "nodeName", "description");

    public record Plan(String initializationSql, EtlNativeSqlCompiler.Table table, Map<String, Object> nodeConfig, boolean output) {
        public Plan { nodeConfig = Collections.unmodifiableMap(new LinkedHashMap<>(nodeConfig)); }
    }
    public static Set<String> supportedCodes(EtlNativeSqlCompiler.Engine engine, boolean streaming) {
        return engine != null && !streaming ? Set.of("read-hive", "write-hive") : Set.of();
    }

    /** resolvedTable 由调用方经对象授权、HiveNativeCatalog 普通存储验证和管理员同物理表 Catalog 绑定解析。 */
    public static Plan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming, String identity, String code,
                               Map<String, Object> input, EtlNativeSqlCompiler.Table resolvedTable) {
        if (!supportedCodes(engine, streaming).contains(code)) throw invalid("Hive 当前仅支持已绑定原生 Catalog 的有界表读取和追加写入");
        if (resolvedTable == null || resolvedTable.product() != ProductTypeEnum.HIVE) throw invalid("Hive 组件必须绑定真实 Hive 产品的受管目录表");
        if (resolvedTable.continuousSource() || resolvedTable.path() == null || resolvedTable.path().size() < 2 || resolvedTable.path().size() > 3)
            throw invalid("Hive 组件需要明确限定的有界 Catalog 表绑定");
        resolvedTable.path().forEach(EtlNativeHiveCompiler::identifier);
        identifier(resolvedTable.schema()); identifier(resolvedTable.physicalTable());
        if (resolvedTable.columns() == null || resolvedTable.columns().isEmpty() || resolvedTable.columns().size() > 1024)
            throw invalid("Hive 物理字段元数据为空或超过预算");
        resolvedTable.columns().keySet().forEach(EtlNativeHiveCompiler::fieldIdentifier);
        if (input == null || JSONUtil.toJsonStr(input).length() > 500_000) throw invalid("Hive 配置缺失或超过大小限制");
        var config = JSONUtil.parseObj(input);
        for (String key : config.keySet()) if (!KEYS.contains(key)) throw invalid("Hive 参数尚未提供等价原生执行映射：" + key);
        if (!Objects.equals(config.getStr("tableName"), resolvedTable.physicalTable())) throw invalid("Hive 节点表名与已授权物理表不一致");
        if (!blank(config.getStr("schemaName")) && !Objects.equals(config.getStr("schemaName"), resolvedTable.schema())) throw invalid("Hive Schema 与已授权目录不一致");
        if (integer(config, "ifSql", 0) != 0 || !blank(config.getStr("sql"))) throw invalid("当前 Hive 输入仅使用受管物理表和独立过滤条件，不执行任意 HiveQL");
        if (!"FULL".equals(config.getStr("readMode", "FULL"))) throw invalid("Hive 增量读取尚未提供持久游标，不能把全量读取当作增量");
        for (String key : List.of("preSql", "postSql", "primaryKeys")) if (!blank(config.getStr(key)))
            throw invalid("Hive 当前尚未支持该运行参数：" + key);
        if (config.get("batchSize") != null && !blank(config.getStr("batchSize"))) throw invalid("Hive 原生写入由引擎管理批次，请移除尚未适配的单次写入条数");
        boolean output = "write-hive".equals(code);
        var declarations = declarations(config, resolvedTable, output);
        var node = new LinkedHashMap<String, Object>();
        Object databaseId = config.get("databaseId");
        if (databaseId == null || !databaseId.toString().matches("[1-9][0-9]{0,18}")) throw invalid("请选择已授权 Hive 数据连接");
        node.put("databaseId", databaseId);
        if (output) {
            if (integer(config, "writeModeType", 2) != 2 || integer(config, "dataSyncType", 1) != 1
                    || !"INSERT".equalsIgnoreCase(config.getStr("writeMode", "INSERT")) || config.getBool("targetTruncate", false))
                throw invalid("Hive 当前只允许追加写入，不能清空、覆盖或执行无界更新");
            if (!blank(config.getStr("whereCondition"))) throw invalid("Hive 追加输出不接受覆盖或删除条件");
            if (config.containsKey("selectedColumns")) throw invalid("Hive 输出请选择明确的字段映射，而非输入字段选择");
            var expected = EtlNativeOutputMappings.require(config);
            node.put("tableName", resolvedTable.physicalTable()); node.put("schemaName", resolvedTable.schema());
            node.put("fieldMappings", expected); node.put("writeMode", "INSERT"); node.put("dataSyncType", 1);
            return new Plan("", resolvedTable, node, true);
        }
        for (String key : List.of("writeModeType", "writeMode", "fieldMappings", "dataSyncType", "targetTruncate"))
            if (config.containsKey(key)) throw invalid("Hive 输入不能包含输出参数：" + key);
        var selected = declarations.stream().map(column -> column.getStr("name")).toList();
        var wanted = config.getJSONArray("selectedColumns");
        if (wanted != null && !selected.equals(wanted.toList(String.class))) throw invalid("Hive 字段选择与属性字段的数量和顺序不一致");
        String where = config.getStr("whereCondition", "");
        if (!where.isBlank()) {
            if (where.codePoints().anyMatch(Character::isISOControl) || where.contains("\"") || where.contains("`") || where.contains("\\") || where.contains("''"))
                throw invalid("Hive 跨引擎过滤仅支持普通字段和未转义标准字面值，请使用过滤组件表达复杂规则");
            // 守卫按已授权真实目录验证所有谓词引用；只改变执行 Catalog，不改写数据值。
            RestrictedSelectGuard.restrictProjection(ProductTypeEnum.HIVE,
                    "SELECT * FROM " + RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.HIVE, resolvedTable.schema(), resolvedTable.physicalTable()) + " WHERE " + where,
                    resolvedTable.schema(), resolvedTable.physicalTable(), new ArrayList<>(resolvedTable.columns().keySet()), resolvedTable.columns().keySet());
        }
        String alias = "dh_native_hive_" + DigestUtil.sha256Hex(Objects.requireNonNull(identity)).substring(0, 24);
        var shape = new LinkedHashMap<String, String>(); var expressions = new ArrayList<String>();
        for (var field : declarations) {
            String name = field.getStr("name"), target = blank(field.getStr("alias")) ? name : fieldIdentifier(field.getStr("alias"));
            if (shape.put(target, resolvedTable.columns().get(name)) != null) throw invalid("Hive 输出字段别名重复");
            expressions.add(quoteField(name) + " AS " + quoteField(target));
        }
        String sql = "CREATE TEMPORARY VIEW " + quote(alias) + " AS SELECT " + String.join(", ", expressions) + " FROM "
                + String.join(".", resolvedTable.path().stream().map(EtlNativeHiveCompiler::quote).toList()) + " AS " + quote(resolvedTable.physicalTable())
                + (where.isBlank() ? "" : " WHERE " + where) + ";\n";
        var table = new EtlNativeSqlCompiler.Table(List.of(alias), Collections.unmodifiableMap(shape), ProductTypeEnum.UNKNOWN, null, alias, false);
        node.put("tableName", alias);
        return new Plan(sql, table, node, false);
    }

    private static List<JSONObject> declarations(JSONObject config, EtlNativeSqlCompiler.Table table, boolean output) {
        var values = config.getJSONArray("columns");
        if (values == null || values.isEmpty() || values.size() > 1024) throw invalid("Hive 需要明确选择 1 至 1024 个物理字段并声明类型");
        var result = new ArrayList<JSONObject>(); var names = new HashSet<String>();
        for (Object value : values) {
            var column = JSONUtil.parseObj(value);
            for (String key : column.keySet()) if (!Set.of("name", "type", "dataType", "key", "alias", "index", "comment", "length", "precision", "scale", "format").contains(key))
                throw invalid("Hive 字段参数尚未提供等价执行：" + key);
            String name = fieldIdentifier(column.getStr("name"));
            if (!table.columns().containsKey(name) || !names.add(name)) throw invalid("Hive 字段未出现在授权目录或重复");
            String declared = column.getStr("type", column.getStr("dataType", ""));
            String actual = table.columns().get(name);
            if (blank(declared) || blank(actual) || !sameType(declared, actual)) throw invalid("Hive 字段类型声明与真实目录不一致：" + name);
            if (output && !blank(column.getStr("alias")) && !column.getStr("alias").equals(name)) throw invalid("Hive 输出别名请使用目标字段名与源字段映射");
            if (!output && !blank(column.getStr("key")) && !name.equals(column.getStr("key"))) throw invalid("Hive 表输入不是 JSON 字段路径，不能忽略解析取值");
            if (column.get("index") != null && integer(column, "index", result.size()) != result.size()) throw invalid("Hive 字段序号必须与当前字段顺序一致");
            if (!blank(column.getStr("format"))) throw invalid("Hive 原生字段保留目录类型，日期或文本格式转换请使用独立转换组件");
            validateMetadata(column, actual);
            if (output) source(column);
            result.add(column);
        }
        return result;
    }
    private static boolean sameType(String declared, String actual) {
        String left = declared.toUpperCase(Locale.ROOT).replace(" ", ""), right = actual.toUpperCase(Locale.ROOT).replace(" ", "");
        if (left.equals(right)) return true;
        if (Set.of("STRING", "VARCHAR", "CHAR", "TEXT").contains(left)) return right.equals("STRING") || right.startsWith("VARCHAR(") || right.startsWith("CHAR(");
        if ("LONG".equals(left)) return "BIGINT".equals(right);
        if ("INTEGER".equals(left)) return "INT".equals(right);
        if ("NUMBER".equals(left)) return "DOUBLE".equals(right);
        return Set.of("BIGNUMBER", "DECIMAL", "NUMERIC").contains(left) && right.startsWith("DECIMAL(");
    }
    private static void validateMetadata(JSONObject column, String actual) {
        String type = actual.toUpperCase(Locale.ROOT).replace(" ", "");
        var decimal = java.util.regex.Pattern.compile("DECIMAL\\(([0-9]+),([0-9]+)\\)").matcher(type);
        var text = java.util.regex.Pattern.compile("(?:VAR)?CHAR\\(([0-9]+)\\)").matcher(type);
        Integer length = null, scale = null;
        if (decimal.matches()) { length = Integer.valueOf(decimal.group(1)); scale = Integer.valueOf(decimal.group(2)); }
        else if (text.matches()) length = Integer.valueOf(text.group(1));
        if (!blank(column.getStr("length")) && (length == null || integer(column, "length", -1) != length)) throw invalid("Hive 字段长度与真实目录不一致，不能隐式转换");
        for (String key : List.of("precision", "scale")) if (!blank(column.getStr(key)) && (scale == null || integer(column, key, -1) != scale))
            throw invalid("Hive 小数精度与真实目录不一致，不能隐式转换");
    }
    private static String source(JSONObject column) { return fieldIdentifier(blank(column.getStr("key")) ? column.getStr("name") : column.getStr("key")); }
    private static String fieldIdentifier(String value) {
        if (value == null || !value.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,127}")) throw invalid("Hive 字段必须是可明确引用的普通名称");
        return value;
    }
    private static String quoteField(String value) { return "`" + fieldIdentifier(value) + "`"; }
    private static String identifier(String value) {
        if (value == null) throw invalid("Hive 物理名称缺失");
        RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.HIVE, value);
        return value;
    }
    private static String quote(String name) { return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.HIVE, name); }
    private static int integer(JSONObject config, String key, int fallback) {
        try { return config.get(key) == null ? fallback : new BigDecimal(config.get(key).toString()).intValueExact(); }
        catch (RuntimeException invalid) { throw invalid(key + "必须为整数"); }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
