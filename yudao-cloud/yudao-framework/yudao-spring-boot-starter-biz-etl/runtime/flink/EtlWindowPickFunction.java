package cn.iocoder.yudao.framework.etl.flink;

import org.apache.flink.table.annotation.DataTypeHint;
import org.apache.flink.table.annotation.FunctionHint;
import org.apache.flink.table.functions.AggregateFunction;
import org.apache.flink.types.Row;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 有限原生窗口的确定首/末行；所有状态都在结构化Flink accumulator中，由窗口算子checkpoint和清理。 */
@FunctionHint(input = {@DataTypeHint("ARRAY<STRING>"), @DataTypeHint("STRING")},
        accumulator = @DataTypeHint("ROW<best ARRAY<STRING>, seen MAP<STRING, STRING>, row_count BIGINT, byte_count BIGINT>"),
        output = @DataTypeHint("ARRAY<STRING>"))
public final class EtlWindowPickFunction extends AggregateFunction<String[], Row> {
    @Override public Row createAccumulator() { return Row.of(null, new LinkedHashMap<String, String>(), 0L, 0L); }
    @Override public String[] getValue(Row accumulator) { return (String[]) accumulator.getField(0); }

    public void accumulate(Row accumulator, String[] row, String declaration) {
        if (row == null) return;
        Plan plan = Plan.parse(declaration, row.length);
        String payload = encode(row, null), unique = encode(row, plan.unique);
        long count = (Long) accumulator.getField(2) + 1;
        long bytes = (Long) accumulator.getField(3) + payload.getBytes(StandardCharsets.UTF_8).length;
        if (count > plan.maxRows || bytes > plan.maxBytes) throw invalid("窗口组超出明确的行数或字节预算");
        accumulator.setField(2, count); accumulator.setField(3, bytes);
        @SuppressWarnings("unchecked") Map<String, String> seen = (Map<String, String>) accumulator.getField(1);
        String previous = seen.putIfAbsent(unique, payload);
        if (previous != null) {
            if (!previous.equals(payload)) throw invalid("窗口组内唯一排序字段存在重复值");
            return; // 完全相同的行保持既有去重语义。
        }
        String[] best = (String[]) accumulator.getField(0);
        if (best == null || (plan.last ? compare(row, best, plan) > 0 : compare(row, best, plan) < 0))
            accumulator.setField(0, row.clone());
    }

    private static int compare(String[] left, String[] right, Plan plan) {
        for (String descriptor : plan.order) {
            String[] values = descriptor.split(":", -1); int index = Integer.parseInt(values[0]);
            String a = left[index], b = right[index]; boolean ascending = values[1].equals("A");
            int result;
            if (a == null || b == null) result = a == b ? 0 : a == null ? 1 : -1;
            else result = switch (values[2]) {
                case "N" -> new BigDecimal(a).compareTo(new BigDecimal(b));
                case "B" -> Boolean.compare(booleanValue(a), booleanValue(b));
                case "D" -> LocalDate.parse(a).compareTo(LocalDate.parse(b));
                case "T" -> LocalDateTime.parse(a.replace(' ', 'T')).compareTo(LocalDateTime.parse(b.replace(' ', 'T')));
                case "S" -> compareCodePoints(a, b);
                default -> throw invalid("窗口排序类型无效");
            };
            if (result != 0) return ascending ? result : -result;
        }
        return 0;
    }
    private static boolean booleanValue(String value) {
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw invalid("窗口布尔值无效");
        return Boolean.parseBoolean(value);
    }
    private static int compareCodePoints(String left, String right) {
        int a = 0, b = 0;
        while (a < left.length() && b < right.length()) {
            int x = left.codePointAt(a), y = right.codePointAt(b);
            if (x != y) return Integer.compare(x, y);
            a += Character.charCount(x); b += Character.charCount(y);
        }
        return Integer.compare(left.length() - a, right.length() - b);
    }
    private static String encode(String[] row, int[] indexes) {
        StringBuilder text = new StringBuilder();
        if (indexes == null) { for (String value : row) append(text, value); }
        else for (int index : indexes) append(text, row[index]);
        return text.toString();
    }
    private static void append(StringBuilder text, String value) {
        if (value == null) text.append("-1:"); else text.append(value.length()).append(':').append(value);
    }
    private record Plan(boolean last, int maxRows, int maxBytes, String[] order, int[] unique) {
        private static Plan parse(String declaration, int columns) {
            if (declaration == null || declaration.length() > 32768 || columns < 1 || columns > 256) throw invalid("窗口选择声明无效");
            String[] parts = declaration.split("\\|", -1);
            if (parts.length != 6 || !parts[0].equals("1") || !parts[1].matches("F|L")) throw invalid("窗口选择版本无效");
            int rows = Integer.parseInt(parts[2]), bytes = Integer.parseInt(parts[3]);
            if (rows < 1 || rows > 10000 || bytes < 1024 || bytes > 16777216) throw invalid("窗口预算无效");
            String[] order = parts[4].isEmpty() ? new String[0] : parts[4].split(",");
            for (String entry : order) {
                if (!entry.matches("[0-9]+:[AD]:[NBDTS]")) throw invalid("窗口排序声明无效");
                if (Integer.parseInt(entry.substring(0, entry.indexOf(':'))) >= columns) throw invalid("窗口排序列越界");
            }
            String[] keys = parts[5].split(","); int[] unique = new int[keys.length];
            for (int i = 0; i < keys.length; i++) {
                unique[i] = Integer.parseInt(keys[i]);
                if (unique[i] < 0 || unique[i] >= columns) throw invalid("窗口唯一列越界");
            }
            return new Plan(parts[1].equals("L"), rows, bytes, order, unique);
        }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
