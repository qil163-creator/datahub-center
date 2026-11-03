package cn.iocoder.yudao.framework.dbswitch.data.util;

import cn.iocoder.yudao.framework.dbswitch.common.constant.MapperType;
import cn.iocoder.yudao.framework.dbswitch.common.entity.MapperConfig;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.PatterNameUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 预览与实际迁移共用的列名、主键和默认值映射；不修改来源元数据或请求配置。 */
public final class MigrationColumnMappings {

    /**
     * 初始化 {@code MigrationColumnMappings}。
     */
    private MigrationColumnMappings() {
    }

    /** columns 与来源列保持相同顺序，空名称代表明确排除；retainedColumns 供类型/DDL 计划使用。 */
    public record Plan(List<ColumnDescription> columns, List<String> primaryKeys) {

        /**
         * 初始化 {@code Plan}。
         */
        public Plan {
            columns = List.copyOf(columns);
            primaryKeys = List.copyOf(primaryKeys);
        }

        /**
         * 返回当前结果需要保留的列集合。
         */
        public List<ColumnDescription> retainedColumns() {
            return columns.stream().filter(column -> hasName(column.getFieldName())).toList();
        }
    }

    /**
     * 根据当前配置和输入类型解析匹配的实现或定义。
     */
    public static Plan resolve(SourceDataSourceProperties source, TargetDataSourceProperties target, String table,
            List<ColumnDescription> columns, List<String> observedPrimaryKeys) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(observedPrimaryKeys, "observedPrimaryKeys");
        if (!MapperType.ALL.name().equals(source.getMapperType())
                && !MapperType.SINGLE.name().equals(source.getMapperType())) {
            throw new IllegalArgumentException("列映射模式必须为 ALL 或 SINGLE");
        }
        var mappings = source.getRegexColumnMapper();
        var configuredKeys = source.getSourcePrimaryKeys();
        if (!MapperType.ALL.name().equals(source.getMapperType())) {
            MapperConfig config = source.getConfigMap() == null ? new MapperConfig()
                    : source.getConfigMap().getOrDefault(table, new MapperConfig());
            mappings = config.getRegexColumnMapper();
            configuredKeys = config.getSourcePrimaryKeys();
        }
        var keys = configuredKeys == null || configuredKeys.isEmpty() ? observedPrimaryKeys : configuredKeys;
        var sourceNames = columns.stream().map(ColumnDescription::getFieldName)
                .collect(java.util.stream.Collectors.toSet());
        if (!sourceNames.containsAll(keys)) {
            throw new IllegalArgumentException("来源主键必须属于真实来源字段");
        }
        var mapped = new ArrayList<ColumnDescription>(columns.size());
        var names = new HashSet<String>();
        for (var column : columns) {
            var description = column.copy();
            var name = convertCase(PatterNameUtils.getFinalName(column.getFieldName(), mappings), target);
            description.setFieldName(name);
            description.setLabelName(name);
            var facts = description.getMetadataEvidence();
            boolean preserveSequenceDefault = Boolean.TRUE.equals(target.getPreserveIdentityValues())
                    && facts.sequence().value() != null
                    && facts.sequence().value().kind() == ColumnSequenceMetadata.Kind.OWNED_DEFAULT;
            if (!Boolean.TRUE.equals(target.getSyncDefaultVal()) && !preserveSequenceDefault) {
                description.setDefaultValue(null);
                if (facts.defaultExpression().known()) {
                    description.setMetadataEvidence(new ColumnMetadataEvidence(
                            facts.provenance(), facts.nativeType(), MetadataValue.absent(), facts.nullable(),
                            facts.numericPrecision(), facts.numericScale(), facts.characterLength(), facts.identity(),
                            facts.generatedExpression(), facts.charset(), facts.collation(), facts.sequence()));
                }
            }
            if (hasName(name) && !names.add(name)) {
                throw new IllegalArgumentException("多个来源字段不能映射到同一目标字段");
            }
            mapped.add(description);
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("列映射不能排除全部字段");
        }
        var mappedKeys = new ArrayList<String>(keys.size());
        for (var key : keys) {
            var name = convertCase(PatterNameUtils.getFinalName(key, mappings), target);
            if (!hasName(name) || !names.contains(name)) {
                throw new IllegalArgumentException("列映射不能排除主键字段");
            }
            mappedKeys.add(name);
        }
        if (new HashSet<>(mappedKeys).size() != mappedKeys.size()) {
            throw new IllegalArgumentException("目标主键不能重复");
        }
        return new Plan(mapped, mappedKeys);
    }

    /**
     * 判断是否具有名称。
     */
    private static boolean hasName(String name) {
        return name != null && !name.isEmpty();
    }

    /**
     * 转换大小写。
     */
    private static String convertCase(String name, TargetDataSourceProperties target) {
        if (!hasName(name)) {
            return name;
        }
        boolean hive = ProductTypeEnum.HIVE == target.getTargetProductType();
        if (hive) {
            name = name.toLowerCase(Locale.ROOT);
        } else if (target.getColumnNameCase() != null) {
            name = target.getColumnNameCase().convert(name);
        } else {
            if (Boolean.TRUE.equals(target.getLowercase())) {
                name = name.toLowerCase(Locale.ROOT);
            }
            if (Boolean.TRUE.equals(target.getUppercase())) {
                name = name.toUpperCase(Locale.ROOT);
            }
        }
        // 只有显式正则排除规则才能移除列；命名模式不得隐式丢弃列。
        if (!hasName(name)) {
            throw new IllegalArgumentException("目标字段名转换后不能为空");
        }
        return name;
    }
}
