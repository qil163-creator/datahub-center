package cn.iocoder.yudao.framework.dbswitch.dbcommon.domain;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;

import java.util.List;

/** 新快照回退到最后一组水位：已崩溃的快照无法恢复。 */
public record CursorReadRequest(String schema, String table, List<String> fields, String watermark,
                                List<String> primaryKeys, TypedCursor lower, TypedCursor fixedUpper,
                                String expectedSchemaFingerprint, BatchLimits limits, boolean upperBoundFrozen,
                                cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin sourceGenerationPin) {

    /**
     * 使用给定参数初始化 {@code CursorReadRequest}。
     */
    public CursorReadRequest(String schema, String table, List<String> fields, String watermark,
            List<String> primaryKeys, TypedCursor lower, TypedCursor fixedUpper, String expectedSchemaFingerprint,
            BatchLimits limits, boolean upperBoundFrozen) {
        this(schema, table, fields, watermark, primaryKeys, lower, fixedUpper, expectedSchemaFingerprint, limits,
                upperBoundFrozen, null);
    }

    /**
     * 使用给定参数初始化 {@code CursorReadRequest}。
     */
    public CursorReadRequest(String schema, String table, List<String> fields, String watermark,
            List<String> primaryKeys, TypedCursor lower, TypedCursor fixedUpper, String expectedSchemaFingerprint,
            BatchLimits limits) {
        this(schema, table, fields, watermark, primaryKeys, lower, fixedUpper, expectedSchemaFingerprint, limits,
                fixedUpper != null);
    }

    /**
     * 校验构造参数并初始化 {@code CursorReadRequest}。
     */
    public CursorReadRequest {
        if (schema == null || schema.isBlank() || table == null || table.isBlank() || watermark == null || watermark.isBlank()
                || fields == null || primaryKeys == null || primaryKeys.isEmpty() || primaryKeys.size() > 32 || limits == null) {
            throw new IllegalArgumentException("Cursor request is incomplete");
        }
        fields = List.copyOf(fields);
        primaryKeys = List.copyOf(primaryKeys);
        if (!fields.contains(watermark) || !fields.containsAll(primaryKeys)
                || new java.util.HashSet<>(fields).size() != fields.size()
                || new java.util.HashSet<>(primaryKeys).size() != primaryKeys.size()) {
            throw new IllegalArgumentException("Cursor fields are inconsistent");
        }
    }

    /**
     * 排列字段集合。
     */
    public List<String> orderFields() {
        var fields = new java.util.ArrayList<String>();
        fields.add(watermark);
        primaryKeys.stream().filter(key -> !key.equals(watermark)).forEach(fields::add);
        return List.copyOf(fields);
    }
}
