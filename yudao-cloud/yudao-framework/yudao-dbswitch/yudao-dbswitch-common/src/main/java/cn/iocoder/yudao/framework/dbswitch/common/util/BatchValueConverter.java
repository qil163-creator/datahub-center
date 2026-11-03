package cn.iocoder.yudao.framework.dbswitch.common.util;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 在调用线程中完成整批校验和转换，不修改输入行。 */
public final class BatchValueConverter {

    /**
     * 初始化 {@code BatchValueConverter}。
     */
    private BatchValueConverter() {
    }

    @FunctionalInterface
    public interface ColumnConverter {

        /**
         * 按目标类型约定转换当前输入值。
         */
        Object convert(Object value, int column);
    }

    /**
     * 估算当前数据行占用的字节数。
     */
    public static long estimatedRowBytes(Object[] row) {
        long bytes = 16L + 8L * row.length;
        for (Object value : row) {
            bytes = Math.addExact(bytes, JdbcTypesUtils.getObjectSize(java.sql.Types.OTHER, value));
        }
        return bytes;
    }

    /**
     * 按目标类型约定转换当前输入值。
     */
    public static List<Object[]> convert(List<Object[]> records, int columns) {
        return convert(records, columns, BatchLimits.defaults());
    }

    /**
     * 按目标类型约定转换当前输入值。
     */
    public static List<Object[]> convert(List<Object[]> records, int columns, ColumnConverter converter) {
        return convert(records, columns, BatchLimits.defaults(), converter);
    }

    /**
     * 按目标类型约定转换当前输入值。
     */
    public static List<Object[]> convert(List<Object[]> records, int columns, BatchLimits limits) {
        return convert(records, columns, limits, (value, column) -> value);
    }

    /**
     * 按目标类型约定转换当前输入值。
     */
    public static List<Object[]> convert(List<Object[]> records, int columns, BatchLimits limits,
            ColumnConverter converter) {
        if (records == null || columns < 0 || converter == null) {
            throw new BatchRejectedException("Invalid batch conversion arguments");
        }
        if (limits == null || records.size() > limits.maxRows()) {
            throw new BatchRejectedException("Batch exceeds the row limit");
        }
        for (Object[] row : records) {
            if (row == null || row.length != columns) {
                throw new BatchRejectedException("Batch column count mismatch");
            }
        }
        List<Object[]> converted = new ArrayList<>(records.size());
        Map<Object, Object> materialized = new IdentityHashMap<>();
        long batchBytes = 0;
        for (int rowIndex = 0; rowIndex < records.size(); rowIndex++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Batch conversion interrupted before write");
            }
            Object[] row = records.get(rowIndex);
            Object[] copy = new Object[columns];
            batchBytes = Math.addExact(batchBytes, 16L + 8L * columns);
            for (int column = 0; column < columns; column++) {
                try {
                    Object value = row[column];
                    if (value instanceof java.sql.Clob || value instanceof java.sql.Blob
                            || value instanceof java.sql.SQLXML || value instanceof java.sql.Array) {
                        value = materialized.computeIfAbsent(value,
                                locator -> JdbcValueConversions.normalize(locator, limits.maxCellBytes()));
                    }
                    value = JdbcValueConversions.normalize(value, limits.maxCellBytes());
                    long inputBytes = JdbcTypesUtils.getObjectSize(java.sql.Types.OTHER, value);
                    copy[column] = converter.convert(value, column);
                    if (value != null && copy[column] == null) {
                        throw new BatchRejectedException("Non-null JDBC value converted to null");
                    }
                    long bytes = Math.max(inputBytes, JdbcTypesUtils.getObjectSize(java.sql.Types.OTHER, copy[column]));
                    if (bytes > limits.maxCellBytes()) {
                        throw new BatchRejectedException("Cell exceeds the value byte limit");
                    }
                    batchBytes = Math.addExact(batchBytes, bytes);
                    if (batchBytes > limits.maxBatchBytes()) {
                        throw new BatchRejectedException("Batch exceeds the value byte limit");
                    }
                } catch (RuntimeException failure) {
                    throw new BatchRejectedException("Batch conversion failed before write at row "
                            + (rowIndex + 1) + ", column " + (column + 1));
                }
            }
            if (batchBytes > limits.maxBatchBytes()) {
                throw new BatchRejectedException("Batch exceeds the value byte limit");
            }
            converted.add(copy);
        }
        return converted;
    }
}
