package cn.iocoder.yudao.framework.etl.core.script;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 原生 stage 的无损标量边界；不推断字段、不执行源码，日期统一为 java.time。 */
public final class EtlNativeScriptValues {
    private EtlNativeScriptValues() { }

    public static Map<String, Object> input(List<?> values, List<EtlNativeScriptProgram.Field> fields) {
        if (values == null || values.size() != fields.size()) throw invalid();
        var row = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.size(); i++) {
            var field = fields.get(i);
            if (row.containsKey(field.name())) throw invalid();
            row.put(field.name(), scalar(values.get(i), field));
        }
        return Collections.unmodifiableMap(row);
    }

    public static List<Object> output(Map<String, Object> row, List<EtlNativeScriptProgram.Field> fields) {
        if (row == null || row.size() != fields.size()) throw invalid();
        var values = new ArrayList<Object>(fields.size());
        for (var field : fields) {
            if (!row.containsKey(field.name())) throw invalid();
            values.add(scalar(row.get(field.name()), field));
        }
        return Collections.unmodifiableList(values);
    }

    public static Object scalar(Object value, EtlNativeScriptProgram.Field field) {
        if (value == null) {
            if (Boolean.FALSE.equals(field.nullable())) throw invalid();
            return null;
        }
        String type = field.sqlType();
        if (type.equals("STRING")) { if (!(value instanceof String)) throw invalid(); return value; }
        if (type.equals("BOOLEAN")) { if (!(value instanceof Boolean)) throw invalid(); return value; }
        if (Set.of("TINYINT", "SMALLINT", "INT", "BIGINT").contains(type)) {
            BigDecimal number = exact(value);
            try {
                return switch (type) {
                    case "TINYINT" -> number.byteValueExact();
                    case "SMALLINT" -> number.shortValueExact();
                    case "INT" -> number.intValueExact();
                    default -> number.longValueExact();
                };
            } catch (ArithmeticException failure) { throw invalid(); }
        }
        if (type.equals("DOUBLE") || type.equals("FLOAT")) {
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || type.equals("FLOAT") && !Float.isFinite(number.floatValue())) throw invalid();
            if (type.equals("DOUBLE")) return number.doubleValue();
            return number.floatValue();
        }
        if (type.startsWith("DECIMAL(")) {
            String[] shape = type.substring(8, type.length() - 1).split(",");
            BigDecimal decimal;
            try { decimal = exact(value).setScale(Integer.parseInt(shape[1]), RoundingMode.UNNECESSARY); }
            catch (ArithmeticException failure) { throw invalid(); }
            if (decimal.precision() > Integer.parseInt(shape[0])) throw invalid();
            return decimal;
        }
        if (type.equals("DATE")) {
            if (value instanceof LocalDate) return value;
            if (value instanceof java.sql.Date day) return day.toLocalDate();
            throw invalid();
        }
        if (type.startsWith("TIMESTAMP(")) {
            LocalDateTime time = value instanceof LocalDateTime local ? local : value instanceof java.sql.Timestamp sql ? sql.toLocalDateTime() : null;
            if (time == null) throw invalid();
            int precision = Integer.parseInt(type.substring(10, 11)), divisor = 1;
            for (int i = precision; i < 9; i++) divisor *= 10;
            if (time.getNano() % divisor != 0) throw invalid();
            return time;
        }
        throw invalid();
    }

    private static BigDecimal exact(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof BigInteger integer) return new BigDecimal(integer);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return BigDecimal.valueOf(((Number) value).longValue());
        throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("脚本 stage 的行形状、空值或字段精度不符合已发布合同"); }
}
