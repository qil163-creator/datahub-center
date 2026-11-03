package cn.iocoder.yudao.framework.dbswitch.core.model;

import java.util.List;

/** 列和主键快照。对额外对象给出诊断，不静默声称已重建。 */
public record TableSchemaMetadata(List<ColumnDescription> columns, List<String> primaryKeys,
                                  MetadataProvenance provenance, MetadataValue<Boolean> existence,
                                  MetadataValue<List<String>> additionalObjects) {

    /**
     * 校验构造参数并初始化 {@code TableSchemaMetadata}。
     */
    public TableSchemaMetadata {
        columns = columns.stream().map(ColumnDescription::copy).toList();
        primaryKeys = List.copyOf(primaryKeys);
        java.util.Objects.requireNonNull(provenance);
        java.util.Objects.requireNonNull(existence);
        java.util.Objects.requireNonNull(additionalObjects);
        if (additionalObjects.state() == MetadataValue.State.VALUE) {
            additionalObjects = MetadataValue.value(List.copyOf(additionalObjects.value()));
        }
    }

    /**
     * 使用给定参数初始化 {@code TableSchemaMetadata}。
     */
    public TableSchemaMetadata(List<ColumnDescription> columns, List<String> primaryKeys) {
        this(columns, primaryKeys, MetadataProvenance.unknown(), MetadataValue.unknown(), MetadataValue.unknown());
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    @Override
    public List<ColumnDescription> columns() {
        return columns.stream().map(ColumnDescription::copy).toList();
    }
}
