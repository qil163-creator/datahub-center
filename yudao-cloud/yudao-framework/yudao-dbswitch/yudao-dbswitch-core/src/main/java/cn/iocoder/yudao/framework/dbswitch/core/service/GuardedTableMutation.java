package cn.iocoder.yudao.framework.dbswitch.core.service;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 单表、完整快照条件写；调用方负责目标授权和 JDBC 类型映射。 */
public record GuardedTableMutation(String schema, String table, List<String> fields, List<String> primaryKeys,
                                   Operation operation, List<Map<String, Object>> expectedRows,
                                   List<Map<String, Object>> rows) {

    public enum Operation {INSERT, UPDATE, DELETE}

    /**
     * 初始化 {@code GuardedTableMutation}。
     */
    public GuardedTableMutation {
        identifier(schema);
        identifier(table);
        if (fields == null || fields.isEmpty() || fields.size() > 200 || primaryKeys == null || primaryKeys.isEmpty()
                || operation == null || rows == null || rows.isEmpty() || rows.size() > 200
                || expectedRows == null || expectedRows.size() != rows.size()) {
            throw invalid();
        }
        fields.forEach(GuardedTableMutation::identifier);
        primaryKeys.forEach(GuardedTableMutation::identifier);
        if (new HashSet<>(fields).size() != fields.size() || new HashSet<>(primaryKeys).size() != primaryKeys.size()
                || !fields.containsAll(primaryKeys)) {
            throw invalid();
        }
        fields = List.copyOf(fields);
        primaryKeys = List.copyOf(primaryKeys);
        Set<String> fieldSet = new HashSet<>(fields);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Map<String, Object> expected = expectedRows.get(i);
            if (row == null || !fieldSet.containsAll(row.keySet()) || !row.keySet().containsAll(primaryKeys)
                    || operation != Operation.DELETE && !row.keySet().equals(fieldSet)) {
                throw invalid();
            }
            for (String key : primaryKeys) {
                if (row.get(key) == null) {
                    throw invalid();
                }
            }
            if (operation == Operation.INSERT ? expected != null : expected == null || !expected.keySet()
                    .equals(fieldSet)) {
                throw invalid();
            }
            if (expected != null) {
                for (String key : primaryKeys) {
                    if (!Objects.deepEquals(row.get(key), expected.get(key))) {
                        throw invalid();
                    }
                }
            }
            for (int j = 0; j < i; j++) {
                boolean same = true;
                for (String key : primaryKeys) {
                    same &= Objects.deepEquals(row.get(key), rows.get(j).get(key));
                }
                if (same) {
                    throw invalid();
                }
            }
        }
        expectedRows = copyRows(expectedRows);
        rows = copyRows(rows);
    }

    /**
     * 返回数据行。
     */
    @Override
    public List<Map<String, Object>> rows() {
        return copyRows(rows);
    }

    /**
     * 返回变更操作用于原值比较的数据行副本。
     */
    @Override
    public List<Map<String, Object>> expectedRows() {
        return copyRows(expectedRows);
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "GuardedTableMutation[operation=" + operation + ", rowCount=" + rows.size() + "]";
    }

    /**
     * 检查或转换当前数据库标识符。
     */
    private static void identifier(String value) {
        // 基础引号 API 不转义引号，只接受可直接安全引用的精确名称。
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 63
                || value.indexOf('"') >= 0 || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
    }

    /**
     * 复制数据行。
     */
    private static List<Map<String, Object>> copyRows(List<Map<String, Object>> source) {
        List<Map<String, Object>> result = new ArrayList<>(source.size());
        for (Map<String, Object> row : source) {
            if (row == null) {
                result.add(null);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            row.forEach((field, value) -> copy.put(field, copyCell(value)));
            result.add(Collections.unmodifiableMap(copy));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 复制当前单元格值，避免复用可变输入。
     */
    private static Object copyCell(Object value) {
        if (value instanceof java.util.Date date) {
            return date.clone();
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof java.math.BigDecimal
                || value instanceof java.math.BigInteger || value instanceof java.time.temporal.Temporal || value instanceof UUID) {
            return value;
        }
        throw invalid();
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid guarded table mutation");
    }
}
