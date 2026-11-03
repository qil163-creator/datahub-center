package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.core.model.DorisCreateTableOptions;
import cn.iocoder.yudao.framework.dbswitch.product.olap.OlapPartitioning;

import java.util.List;
import java.util.TreeMap;

/** Applies explicit layout values to an already verified Doris column/key definition. */
public final class DorisTableLayouts {

    /**
     * 初始化 {@code DorisTableLayouts}。
     */
    private DorisTableLayouts() {
    }

    /**
     * 将分布、属性和注释配置合并到 Doris 原生表定义。
     */
    public static DorisNativeSchema.Table apply(DorisNativeSchema.Table table, String comment,
            DorisCreateTableOptions options) {
        if (options == null) {
            return comment == null ? table : new DorisNativeSchema.Table(table.columns(), table.model(),
                    table.keys(), table.distribution(), table.buckets(), table.properties(), comment,
                    table.partitioning(), table.indexes());
        }
        options.validateColumns(table.columns().stream().map(DorisNativeSchema.Column::name).toList());
        if ("RANDOM".equals(options.distributionType()) && !"DUPLICATE".equals(table.model())) {
            throw new IllegalArgumentException("RANDOM distribution requires the Doris DUPLICATE model");
        }
        if (!"DUPLICATE".equals(table.model()) && !table.keys().containsAll(options.distributionColumns())) {
            throw new IllegalArgumentException("Doris UNIQUE/AGGREGATE distribution columns must be key columns");
        }
        OlapPartitioning partitioning = null;
        if (options.partition() != null) {
            var partition = options.partition();
            partitioning = OlapPartitioning.of(partition.kind(), partition.columns(), partition.partitions().stream()
                            .map(entry -> new OlapPartitioning.Partition(entry.name(), entry.values())).toList(),
                    column -> table.column(column).type());
        }
        var properties = new TreeMap<>(table.properties());
        properties.remove("replication_num");
        properties.put("replication_allocation", "tag.location.default: " + options.replicationNum());
        return new DorisNativeSchema.Table(table.columns(), table.model(), table.keys(),
                "RANDOM".equals(options.distributionType()) ? List.of() : options.distributionColumns(),
                "AUTO".equals(options.bucketMode()) ? 0 : options.bucketCount(), properties,
                comment == null ? table.comment() : comment, partitioning, table.indexes());
    }
}
