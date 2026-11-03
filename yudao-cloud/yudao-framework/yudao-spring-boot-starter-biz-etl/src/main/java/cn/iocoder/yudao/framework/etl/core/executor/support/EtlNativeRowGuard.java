package cn.iocoder.yudao.framework.etl.core.executor.support;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/** 在JSON行进入Spark之前保留精确数值，拒绝整数截断、小数舍入、溢出和不满足声明的空值。 */
public final class EtlNativeRowGuard {
    private EtlNativeRowGuard() { }
    /** 仅显式声明的接口字段允许缺失或空值使用默认值；转换失败仍按原有无损规则拒绝。 */
    public static void declaredOptions(JSONObject column, JSONObject guard) {
        String format = column.getStr("format", "");
        String type = guard.getStr("sqlType", "");
        if (!format.isBlank() && !format.equals("-")) {
            if (!type.equals("DATE") && !type.equals("TIMESTAMP") || format.length() > 128) throw new IllegalArgumentException("仅日期时间字段接受有界格式声明");
            formatter(format); guard.set("format", format);
        }
        Object fallback = column.get("defaultValue");
        if (!JSONUtil.isNull(fallback) && !(fallback instanceof String text && text.isEmpty())) {
            if (!(fallback instanceof String || fallback instanceof Number || fallback instanceof Boolean) || fallback.toString().length() > 65536)
                throw new IllegalArgumentException("接口字段默认值必须为有界标量");
            guard.set("defaultValue", fallback);
            var check = JSONUtil.createObj().set("resultTypes", JSONUtil.createObj().set("value", guard));
            normalizeDeclared(JSONUtil.createObj().set("value", fallback).toString(), check);
        }
    }

    public static String normalizeDeclared(String json, JSONObject plan) {
        var types = plan.getJSONObject("resultTypes");
        if (types == null) return json;
        var row = JSONUtil.parseObj(json, cn.hutool.json.JSONConfig.create().setIgnoreNullValue(false));
        for (String name : types.keySet()) {
            var field = types.getJSONObject(name);
            Object value = row.get(name);
            if (JSONUtil.isNull(value) && field.containsKey("defaultValue")) { value = field.get("defaultValue"); row.set(name, value); }
            String format = field.getStr("format", "");
            if (JSONUtil.isNull(value) || format.isBlank()) continue;
            try {
                var parsed = formatter(format).parse(value.toString());
                if (field.getStr("sqlType").equals("DATE")) row.set(name, LocalDate.from(parsed).toString());
                else {
                    java.time.LocalDateTime timestamp;
                    try { timestamp = java.time.OffsetDateTime.from(parsed).withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime(); }
                    catch (java.time.DateTimeException ignored) { timestamp = java.time.LocalDateTime.from(parsed); }
                    row.set(name, timestamp.toString());
                }
            } catch (RuntimeException failure) { throw invalid(name); }
        }
        return validate(row.toString(), plan);
    }

    private static java.time.format.DateTimeFormatter formatter(String pattern) {
        try { return new java.time.format.DateTimeFormatterBuilder().appendPattern(pattern)
                .parseDefaulting(java.time.temporal.ChronoField.ERA, 1).toFormatter(java.util.Locale.ROOT)
                .withResolverStyle(java.time.format.ResolverStyle.STRICT); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("接口日期时间格式无效"); }
    }

    public static String validate(String json, JSONObject plan) {
        JSONObject types = plan.getJSONObject("resultTypes");
        if (types == null) return json;
        JSONObject row = JSONUtil.parseObj(json);
        for (String name : types.keySet()) {
            JSONObject field = types.getJSONObject(name); Object value = row.get(name);
            if (JSONUtil.isNull(value)) {
                if (Boolean.FALSE.equals(field.getBool("nullable"))) throw invalid(name);
                continue;
            }
            try {
                String type = field.getStr("sqlType"), text = value.toString();
                if (type.equals("BIGINT")) row.set(name, new BigDecimal(text).longValueExact() + "");
                else if (type.equals("INT")) row.set(name, new BigDecimal(text).intValueExact() + "");
                else if (type.startsWith("DECIMAL(")) {
                    String[] shape = type.substring(8, type.length() - 1).split(","); int precision = Integer.parseInt(shape[0]), scale = Integer.parseInt(shape[1]);
                    BigDecimal decimal = new BigDecimal(text);
                    if ((long) decimal.precision() - decimal.scale() > precision - scale || decimal.stripTrailingZeros().scale() > scale) throw invalid(name);
                    decimal = decimal.setScale(scale, RoundingMode.UNNECESSARY);
                    if (decimal.precision() > precision) throw invalid(name);
                    row.set(name, decimal.toPlainString());
                } else if (type.equals("DOUBLE")) {
                    double parsed = Double.parseDouble(text);
                    if (!Double.isFinite(parsed) || parsed == 0 && new BigDecimal(text).signum() != 0) throw invalid(name);
                } else if (type.equals("BOOLEAN")) {
                    if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) throw invalid(name);
                    row.set(name, text.toLowerCase(java.util.Locale.ROOT));
                } else if (type.equals("DATE")) row.set(name, LocalDate.parse(text).toString());
                else if (type.equals("TIMESTAMP")) {
                    String normalized = text.replace(' ', 'T');
                    try {
                        var timestamp = java.time.OffsetDateTime.parse(normalized);
                        if (timestamp.getNano() % 1000 != 0) throw invalid(name);
                        row.set(name, timestamp.toInstant().toString());
                    } catch (java.time.format.DateTimeParseException ignored) {
                        var timestamp = java.time.LocalDateTime.parse(normalized);
                        if (timestamp.getNano() % 1000 != 0) throw invalid(name);
                        row.set(name, timestamp.toString());
                    }
                }
                else if (type.equals("STRING")) {
                    if (field.getInt("length", 0) > 0 && text.codePointCount(0, text.length()) > field.getInt("length")) throw invalid(name);
                    row.set(name, text);
                }
            } catch (RuntimeException failure) { throw invalid(name); }
        }
        return row.toString();
    }
    private static IllegalArgumentException invalid(String field) { return new IllegalArgumentException("原生源字段不满足已声明的无损类型：" + field); }
}
