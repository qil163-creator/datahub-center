package cn.iocoder.yudao.framework.dbswitch.core.model;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable, structured Doris table layout. Values are data; no member accepts SQL. */
public record DorisCreateTableOptions(Partition partition, String distributionType,
                                      List<String> distributionColumns, String bucketMode, Integer bucketCount,
                                      Integer replicationNum) {

    public static final String TABLE_PROPERTY = "doris.createOptions";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 校验构造参数并初始化 {@code DorisCreateTableOptions}。
     */
    public DorisCreateTableOptions {
        if (distributionType == null || bucketMode == null || !Set.of("HASH", "RANDOM").contains(distributionType)
                || !Set.of("AUTO", "FIXED").contains(bucketMode)
                || replicationNum == null || replicationNum < 1 || replicationNum > 32767
                || "FIXED".equals(bucketMode) && (bucketCount == null || bucketCount < 1 || bucketCount > 65536)
                || "AUTO".equals(bucketMode) && bucketCount != null) {
            throw new IllegalArgumentException("Invalid Doris distribution, bucket or replication options");
        }
        distributionColumns = names(distributionColumns, 1024);
        if ("HASH".equals(distributionType) == distributionColumns.isEmpty()) {
            throw new IllegalArgumentException("HASH requires columns; RANDOM must not carry columns");
        }
    }

    public record Partition(String kind, List<String> columns, List<PartitionEntry> partitions) {

        /**
         * 校验构造参数并初始化 {@code Partition}。
         */
        public Partition {
            if (kind == null || !Set.of("RANGE", "LIST").contains(kind)) {
                throw new IllegalArgumentException("Doris partition kind must be RANGE or LIST");
            }
            columns = names(columns, 16);
            if (partitions == null || partitions.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("Doris partitions must not be null");
            }
            partitions = List.copyOf(partitions);
            if (columns.isEmpty() || partitions.isEmpty() || partitions.size() > 1024
                    || new HashSet<>(partitions.stream().map(entry -> entry.name().toLowerCase(java.util.Locale.ROOT))
                    .toList()).size() != partitions.size()) {
                throw new IllegalArgumentException("Doris partitions require distinct names and columns");
            }
            long valueCount = 0;
            for (var entry : partitions) {
                if ("RANGE".equals(kind) && entry.values().size() != 2) {
                    throw new IllegalArgumentException("RANGE requires lower and upper tuples");
                }
                for (var tuple : entry.values()) {
                    if (tuple.size() != columns.size()) {
                        throw new IllegalArgumentException("Partition tuple arity differs from columns");
                    }
                    valueCount += tuple.size();
                }
            }
            if (valueCount > 65536) {
                throw new IllegalArgumentException("Too many partition values");
            }
        }
    }

    public record PartitionEntry(String name, List<List<String>> values) {

        /**
         * 校验构造参数并初始化 {@code PartitionEntry}。
         */
        public PartitionEntry {
            if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || values == null
                    || values.isEmpty() || values.size() > 65536) {
                throw new IllegalArgumentException("Invalid Doris partition entry");
            }
            values = values.stream().map(tuple -> {
                if (tuple == null || tuple.stream().anyMatch(java.util.Objects::isNull)) {
                    throw new IllegalArgumentException("Doris partition tuples and values must not be null");
                }
                var copy = List.copyOf(tuple);
                if (copy.stream().anyMatch(value -> value.length() > 65536 || value.indexOf('\0') >= 0)) {
                    throw new IllegalArgumentException("Invalid Doris partition value");
                }
                return copy;
            }).toList();
        }
    }

    /**
     * 校验列集合。
     */
    public void validateColumns(Collection<String> columns) {
        if (!columns.containsAll(distributionColumns) || partition != null && !columns.containsAll(
                partition.columns())) {
            throw new IllegalArgumentException("Doris partition or distribution columns do not exist in the target");
        }
    }

    /**
     * 转换为JSON。
     */
    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Doris options cannot be encoded");
        }
    }

    /**
     * 从 JSON 内容解析当前数据结构。
     */
    public static DorisCreateTableOptions fromJson(String json) {
        if (json == null || json.length() > 1_048_576) {
            throw new IllegalArgumentException("Invalid Doris options document");
        }
        try {
            return java.util.Objects.requireNonNull(JSON.readValue(json, DorisCreateTableOptions.class));
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid Doris options document");
        }
    }

    /**
     * 校验当前模式名与对象名符合原生标识符约定。
     */
    private static List<String> names(List<String> values, int maximum) {
        if (values != null && values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Doris column names must not be null");
        }
        var names = values == null ? List.<String>of() : List.copyOf(values);
        if (names.size() > maximum || new HashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException("Doris columns must be distinct and bounded");
        }
        names.forEach(name -> RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DORIS, name));
        return names;
    }
}
