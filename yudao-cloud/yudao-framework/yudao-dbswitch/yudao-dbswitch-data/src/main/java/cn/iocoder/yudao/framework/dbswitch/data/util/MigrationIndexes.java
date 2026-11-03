package cn.iocoder.yudao.framework.dbswitch.data.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.IndexDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.IndexFieldMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把源表索引的键映射为目标列名。部分索引、函数/表达式键和被列映射排除的键会让整个索引跳过：
 * 去掉条件或某个键后重建，会把唯一约束改成范围不同的另一个约束。
 */
public final class MigrationIndexes {

    /**
     * 初始化 {@code MigrationIndexes}。
     */
    private MigrationIndexes() {
    }

    /** skipped 中每项是“索引名：原因”，调用方必须记录，不能静默丢弃。 */
    public record Plan(List<IndexDescription> indexes, List<String> skipped) {

        /**
         * 初始化 {@code Plan}。
         */
        public Plan {
            indexes = List.copyOf(indexes);
            skipped = List.copyOf(skipped);
        }
    }

    /**
     * 按当前映射定义转换字段、类型或数据结构。
     */
    public static Plan map(List<IndexDescription> sourceIndexes, Map<String, String> mappedColumns) {
        var indexes = new ArrayList<IndexDescription>(sourceIndexes.size());
        var skipped = new ArrayList<String>();
        for (var index : sourceIndexes) {
            if (index.filterCondition() != null) {
                skipped.add(index.indexName() + "：部分索引的条件不能随普通索引重建");
                continue;
            }
            var fields = new ArrayList<IndexFieldMeta>(index.indexFields().size());
            String unmapped = null;
            for (var field : index.indexFields()) {
                String target = field.fieldName() == null ? null : mappedColumns.get(field.fieldName());
                if (target == null || target.isEmpty()) {
                    unmapped = String.valueOf(field.fieldName());
                    break;
                }
                fields.add(new IndexFieldMeta(target, field.ordinalPosition(), field.ascending()));
            }
            if (unmapped != null) {
                skipped.add(index.indexName() + "：键 " + unmapped + " 不是源表列（函数/表达式键）或已被列映射排除");
                continue;
            }
            indexes.add(new IndexDescription(index.indexName(), index.indexType(), fields, null));
        }
        return new Plan(indexes, skipped);
    }
}
