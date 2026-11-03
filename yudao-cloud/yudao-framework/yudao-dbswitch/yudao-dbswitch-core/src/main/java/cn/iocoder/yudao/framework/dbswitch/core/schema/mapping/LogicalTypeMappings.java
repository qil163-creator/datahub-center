package cn.iocoder.yudao.framework.dbswitch.core.schema.mapping;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 由 {@code DbSwitchProductRegistry} 发布的不可变产品与映射器目录，以及跨产品规划器。
 * 列按“源事实 -> 逻辑值域 -> 目标原生类型”映射；列保真度取两个方向中的
 * 较低值。没有映射器的产品沿用现有拒绝行为：其列为 UNSUPPORTED。
 */
public final class LogicalTypeMappings {

    private static final LogicalTypeMappings EMPTY = new LogicalTypeMappings(Map.of());

    private final Map<ProductTypeEnum, LogicalTypeMapper> mappers;

    /**
     * 初始化 {@code LogicalTypeMappings}。
     */
    private LogicalTypeMappings(Map<ProductTypeEnum, LogicalTypeMapper> mappers) {
        this.mappers = mappers;
    }

    /**
     * 返回空。
     */
    public static LogicalTypeMappings empty() {
        return EMPTY;
    }

    /** 重复产品会失败；不允许由最后一次写入覆盖。 */
    public static LogicalTypeMappings of(Collection<? extends LogicalTypeMapper> mappers) {
        var snapshot = new EnumMap<ProductTypeEnum, LogicalTypeMapper>(ProductTypeEnum.class);
        for (var mapper : Objects.requireNonNull(mappers, "mappers")) {
            var product = Objects.requireNonNull(Objects.requireNonNull(mapper, "mapper").product(), "mapper product");
            if (product == ProductTypeEnum.UNKNOWN || snapshot.putIfAbsent(product, mapper) != null) {
                throw new IllegalArgumentException("Duplicate or invalid type mapper " + product);
            }
        }
        return snapshot.isEmpty() ? EMPTY : new LogicalTypeMappings(Map.copyOf(snapshot));
    }

    /**
     * 随此组件安装的提供方目录，用于早于显式注册表的静态入口。
     * 仅发现一次，不打开连接且保持不可变；发现失败后状态仍为失败。
     * 由上下文持有的调用方应显式传入 {@code DbSwitchProductRegistry#typeMappings()}。
     */
    public static LogicalTypeMappings installed() {
        return Installed.MAPPINGS;
    }

    private static final class Installed {

        static final LogicalTypeMappings MAPPINGS = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry
                .discover(LogicalTypeMappings.class.getClassLoader()).typeMappings();
    }

    /**
     * 返回当前映射器。
     */
    public Optional<LogicalTypeMapper> mapper(ProductTypeEnum product) {
        return Optional.ofNullable(mappers.get(product));
    }

    /**
     * 返回当前实现适用的数据库产品集合。
     */
    public Set<ProductTypeEnum> products() {
        return mappers.keySet();
    }

    /** 仅在两个不同产品均提供映射器时为 true；不能据此判断单个类型是否支持。 */
    public boolean supports(ProductTypeEnum source, ProductTypeEnum target) {
        return source != target && mappers.containsKey(source) && mappers.containsKey(target);
    }

    /**
     * 返回计划。
     */
    public TableTypeMappingPlan plan(ProductTypeEnum target, List<ColumnDescription> columns, List<String> primaryKeys,
            LossyColumnAcceptance acceptance) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(acceptance, "acceptance");
        Set<String> keys = Set.copyOf(primaryKeys);
        List<ColumnTypeMapping> mapped = new ArrayList<>();
        for (var column : columns) {
            mapped.add(map(target, column, keys.contains(column.getFieldName())));
        }
        Set<String> accepted = mapped.stream()
                .filter(column -> column.fidelity() == TypeFidelity.LOSSY && acceptance.accepts(column))
                .map(ColumnTypeMapping::column).collect(Collectors.toSet());
        return new TableTypeMappingPlan(target, mapped, accepted);
    }

    /**
     * 按当前映射定义转换字段、类型或数据结构。
     */
    public ColumnTypeMapping map(ProductTypeEnum target, ColumnDescription column, boolean key) {
        String name = column.getFieldName();
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Column name is required");
        }
        var facts = column.getMetadataEvidence();
        ProductTypeEnum source = facts.provenance().product();
        String sourceType = facts.nativeType().state() == MetadataValue.State.VALUE ? facts.nativeType().value()
                : "<" + facts.nativeType().state() + ">";
        List<TypeMappingIssue> issues = new ArrayList<>();
        if (facts.nativeType().state() != MetadataValue.State.VALUE) {
            issues.add(incomplete("native type"));
        }
        if (source == target) {
            issues.add(TypeMappingIssue.blocker("SAME_PRODUCT_REQUIRES_NATIVE_PROVENANCE",
                    "same-product columns use the verified native path; logical mapping is never a fallback"));
        }
        var sourceMapper = mappers.get(source);
        var targetMapper = mappers.get(target);
        if (sourceMapper == null) {
            issues.add(TypeMappingIssue.blocker("SOURCE_MAPPER_UNAVAILABLE", source.name()));
        }
        if (targetMapper == null) {
            issues.add(TypeMappingIssue.blocker("TARGET_MAPPER_UNAVAILABLE", target.name()));
        }
        if (facts.nullable().state() != MetadataValue.State.VALUE) {
            issues.add(incomplete("nullable"));
        } else if (facts.nullable().value() != column.isNullable()) {
            issues.add(TypeMappingIssue.blocker("NULLABILITY_INCONSISTENT", "catalog and driver nullability differ"));
        }
        semantic(issues, facts.defaultExpression(), "DEFAULT_UNMAPPED", "default");
        if (facts.defaultExpression().state() == MetadataValue.State.ABSENT && column.getDefaultValue() != null) {
            issues.add(TypeMappingIssue.blocker("DEFAULT_UNMAPPED", "default"));
        }
        semantic(issues, facts.identity(), "IDENTITY_UNMAPPED", "identity");
        if (facts.identity().state() == MetadataValue.State.ABSENT && column.isAutoIncrement()) {
            issues.add(TypeMappingIssue.blocker("IDENTITY_UNMAPPED", "auto increment"));
        }
        semantic(issues, facts.generatedExpression(), "GENERATED_UNMAPPED", "generated expression");
        if (!facts.charset().known()) {
            issues.add(incomplete("charset"));
        }
        if (!facts.collation().known()) {
            issues.add(incomplete("collation"));
        }
        if (TypeMappingResult.fidelityOf(issues) == TypeFidelity.UNSUPPORTED) {
            return new ColumnTypeMapping(name, source, sourceType, null, target, null, TypeFidelity.UNSUPPORTED, issues,
                    null);
        }
        var logical = sourceMapper.toLogical(column);
        issues.addAll(logical.issues());
        if (logical.fidelity() == TypeFidelity.UNSUPPORTED) {
            return new ColumnTypeMapping(name, source, sourceType, null, target, null, TypeFidelity.UNSUPPORTED, issues,
                    null);
        }
        var nativeType = targetMapper.toNative(
                new LogicalColumn(name, logical.value(), column.isNullable(), key, source, sourceType));
        issues.addAll(nativeType.issues());
        var fidelity = TypeMappingResult.fidelityOf(issues);
        if (fidelity == TypeFidelity.UNSUPPORTED) {
            return new ColumnTypeMapping(name, source, sourceType, logical.value().canonical(), target,
                    nativeType.value() == null ? null : nativeType.value().typeSql(), fidelity, issues, null);
        }
        String quoted = RestrictedSelectGuard.quotePhysicalIdentifier(target, name);
        String check = nativeType.value().check(quoted);
        // Sybase ASE 裸列默认 NOT NULL，所以允许空值的目标列需要显式关键字。
        String definition = quoted + " " + nativeType.value().typeSql()
                + (column.isNullable() ? (target == ProductTypeEnum.SYBASE ? " NULL" : "") : " NOT NULL")
                + (check == null ? "" : " CHECK (" + check + ")");
        return new ColumnTypeMapping(name, source, sourceType, logical.value().canonical(), target,
                nativeType.value().typeSql(), fidelity, issues, definition);
    }

    /**
     * 读取或计算当前对象的语义描述。
     */
    private static void semantic(List<TypeMappingIssue> issues, MetadataValue<?> value, String code, String property) {
        if (value.state() == MetadataValue.State.VALUE) {
            issues.add(TypeMappingIssue.blocker(code, property + " has no verified cross-product mapping"));
        } else if (value.state() != MetadataValue.State.ABSENT) {
            issues.add(incomplete(property));
        }
    }

    /**
     * 返回元数据证据不完整时的类型映射结果。
     */
    private static TypeMappingIssue incomplete(String property) {
        return TypeMappingIssue.blocker("INCOMPLETE_METADATA", property + " was not observed; UNKNOWN cannot be EXACT");
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "LogicalTypeMappings" + mappers.keySet();
    }
}
