package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;

import java.math.BigDecimal;
import java.util.*;

/** 原生消息表复用的顶层 JSON 字段合同，不读取数据或推断未声明的类型。 */
public final class EtlNativeMessageSchema {
    private static final Set<String> KEYS = Set.of("name", "type", "key", "comment", "index", "length", "precision", "format");
    private EtlNativeMessageSchema() { }
    public static Map<String, String> columns(List<?> declarations) {
        return columns(declarations, false);
    }
    public static Map<String, String> columns(List<?> declarations, boolean output) {
        if (declarations == null || declarations.isEmpty() || declarations.size() > 1024) throw invalid("需要明确的 JSON 消息字段合同");
        var fields = new LinkedHashMap<String, String>();
        for (Object item : declarations) {
            var field = JSONUtil.parseObj(item);
            if (!KEYS.containsAll(field.keySet())) throw invalid("消息字段参数尚未提供等价执行");
            String name = field.getStr("name", "");
            if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,127}")) throw invalid("消息字段标识符无效");
            if (field.get("index") != null && integer(field.get("index"), 0, 1023) != fields.size()) throw invalid("消息字段序号与顺序不一致");
            String key = field.getStr("key", name);
            if (output) {
                if (!key.isBlank() && !key.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,127}")) throw invalid("消息输出映射需要明确的源字段名");
            } else if (!key.isBlank() && !name.equals(key) && !("$." + name).equals(key) && !("$[\"" + name + "\"]").equals(key))
                throw invalid("消息按 JSON 顶层同名属性读取，嵌套取值和重命名请使用转换组件");
            if (!Set.of("", "-", "ISO").contains(field.getStr("format", ""))) throw invalid("消息日期格式仅支持 ISO 内置语义");
            String declared = field.getStr("type", "").toUpperCase(Locale.ROOT).replace(" ", "");
            var explicitDecimal = java.util.regex.Pattern.compile("(?:DECIMAL|NUMERIC)\\(([0-9]+),([0-9]+)\\)").matcher(declared);
            String type;
            if (explicitDecimal.matches()) {
                int precision = integer(explicitDecimal.group(1), 1, 38), scale = integer(explicitDecimal.group(2), 0, precision);
                if ((!field.getStr("length", "").isBlank() && integer(field.get("length"), 1, 38) != precision)
                        || (!field.getStr("precision", "").isBlank() && integer(field.get("precision"), 0, precision) != scale))
                    throw invalid("消息 Decimal 类型与长度或精度声明冲突");
                type = "DECIMAL(" + precision + "," + scale + ")";
            } else type = switch (declared) {
                case "STRING", "TEXT", "VARCHAR", "CHAR" -> "STRING";
                case "LONG", "BIGINT" -> "BIGINT";
                case "INTEGER", "INT" -> "INT";
                case "DOUBLE", "NUMBER" -> "DOUBLE";
                case "BOOLEAN" -> "BOOLEAN";
                case "DATE" -> "DATE";
                case "TIMESTAMP", "TIMESTAMP(3)" -> "TIMESTAMP(3)";
                case "DECIMAL", "BIGNUMBER", "NUMERIC" -> {
                    int precision = integer(field.get("length") == null || field.getStr("length").isBlank() ? 38 : field.get("length"), 1, 38);
                    int scale = integer(field.get("precision") == null || field.getStr("precision").isBlank() ? 18 : field.get("precision"), 0, precision);
                    yield "DECIMAL(" + precision + "," + scale + ")";
                }
                default -> throw invalid("消息字段类型尚未适配");
            };
            if (!type.startsWith("DECIMAL(") && (!field.getStr("length", "").isBlank() || !field.getStr("precision", "").isBlank()))
                throw invalid("消息非 Decimal 字段的长度和精度约束尚未适配");
            if (fields.putIfAbsent(name, type) != null) throw invalid("消息字段名称重复");
        }
        return Collections.unmodifiableMap(fields);
    }
    private static int integer(Object value, int min, int max) {
        try { int result = new BigDecimal(value.toString()).intValueExact(); if (result < min || result > max) throw new ArithmeticException(); return result; }
        catch (RuntimeException failure) { throw invalid("消息字段数值超出已支持范围"); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
