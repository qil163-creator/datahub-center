package cn.iocoder.yudao.framework.dbswitch.core.model;

import cn.iocoder.yudao.framework.dbswitch.common.type.TableIndexEnum;

import java.util.List;
import java.util.Objects;

/**
 * 表上的一个索引（移植自 dbswitch 2.0.2）。键按 ORDINAL_POSITION 连续排列，唯一性属于整个索引而不是单列。
 * <p>
 * filterCondition 非空表示部分索引。部分索引与函数键都不能退化为普通列索引重建：
 * 去掉条件或键会改变唯一约束的范围。
 */
public record IndexDescription(String indexName, TableIndexEnum indexType, List<IndexFieldMeta> indexFields,
                               String filterCondition) {

    /**
     * 校验构造参数并初始化 {@code IndexDescription}。
     */
    public IndexDescription {
        if (indexName == null || indexName.isEmpty()) {
            throw new IllegalArgumentException("Index name is required");
        }
        Objects.requireNonNull(indexType, "indexType");
        indexFields = List.copyOf(indexFields);
        if (indexFields.isEmpty()) {
            throw new IllegalArgumentException("Index keys are required");
        }
        for (int i = 0; i < indexFields.size(); i++) {
            if (indexFields.get(i).ordinalPosition() != i + 1) {
                throw new IllegalArgumentException("Index key ordinals must be contiguous from 1");
            }
        }
        if (filterCondition != null && filterCondition.isBlank()) {
            filterCondition = null;
        }
    }

    /**
     * 返回当前唯一。
     */
    public boolean unique() {
        return indexType.isUnique();
    }

    /** 按键顺序返回 COLUMN_NAME；表达式键可能为 null。 */
    public List<String> fieldNames() {
        return indexFields.stream().map(IndexFieldMeta::fieldName).toList();
    }
}
