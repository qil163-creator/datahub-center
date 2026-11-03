package cn.iocoder.yudao.framework.dbswitch.product.dm;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;

import java.sql.Connection;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 纯逻辑且有数量上限的计划；执行这些语句需要由资源所有者确认全部目标标识。 */
public final class DmForeignKeyPlan {

    public record Target(Relation relation, Map<String, String> columns) {

        /**
         * 初始化 {@code Target}。
         */
        public Target {
            DmForeignKeyPlan.relation(relation);
            columns = Map.copyOf(columns);
            if (columns.isEmpty() || columns.size() > 1024 || columns.values().stream().distinct()
                    .count() != columns.size()) {
                throw invalid();
            }
            columns.forEach((source, target) -> {
                DmNativeIndexes.name(source);
                DmNativeIndexes.name(target);
            });
        }
    }

    public record Constraint(String name, Relation child, Relation parent, List<String> childColumns,
                             List<String> parentColumns,
                             DmNativeForeignKeys.Action onUpdate, DmNativeForeignKeys.Action onDelete,
                             boolean enabled) {

        /**
         * 校验构造参数并初始化 {@code Constraint}。
         */
        public Constraint {
            DmNativeIndexes.name(name);
            relation(child);
            relation(parent);
            childColumns = List.copyOf(childColumns);
            parentColumns = List.copyOf(parentColumns);
            if (childColumns.isEmpty() || childColumns.size() > 32 || childColumns.size() != parentColumns.size()
                    || childColumns.stream().distinct().count() != childColumns.size() || parentColumns.stream()
                    .distinct().count() != parentColumns.size()) {
                throw invalid();
            }
            childColumns.forEach(DmNativeIndexes::name);
            parentColumns.forEach(DmNativeIndexes::name);
            Objects.requireNonNull(onUpdate);
            Objects.requireNonNull(onDelete);
            // DM8 的内联 CREATE 约束接受这些标识符，但 ALTER ADD/ENABLE
            // 校验路径会返回 -2007；不能等复制整个资源后才发现。
            // 要支持此场景，仍需内联依赖阶段，不能仅重命名字段。
            if (enabled && (childColumns.stream().anyMatch(n -> n.indexOf('"') >= 0) || parentColumns.stream()
                    .anyMatch(n -> n.indexOf('"') >= 0))) {
                throw new IllegalArgumentException("DM quoted FK columns require a verified inline dependency phase");
            }
        }

        /**
         * 根据当前DM方言生成 SQL 语句或片段。
         */
        public String sql() {
            return "ALTER TABLE " + DmSqlNames.relation(child.schema(),
                    child.table()) + " ADD CONSTRAINT " + DmSqlNames.quote(name)
                    + " FOREIGN KEY(" + DmSqlNames.columns(childColumns) + ") REFERENCES " + DmSqlNames.relation(
                    parent.schema(), parent.table())
                    + "(" + DmSqlNames.columns(
                    parentColumns) + ")" + (onUpdate == DmNativeForeignKeys.Action.NO_ACTION ? "" : " ON UPDATE " + onUpdate.sql())
                    + (onDelete == DmNativeForeignKeys.Action.NO_ACTION ? "" : " ON DELETE " + onDelete.sql()) + (enabled ? "" : " DISABLE");
        }
    }

    private final List<Constraint> constraints;

    private final String digest;

    /**
     * 校验构造参数并初始化 {@code DmForeignKeyPlan}。
     */
    private DmForeignKeyPlan(List<Constraint> constraints) {
        this.constraints = List.copyOf(constraints);
        if (constraints.size() > 1024) {
            throw invalid();
        }
        long bytes = 0;
        var names = new HashSet<Relation>();
        var material = new StringBuilder();
        for (var entry : constraints) {
            if (!names.add(new Relation(entry.child().schema(), entry.name()))) {
                throw invalid();
            }
            String sql = entry.sql();
            bytes += sql.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 1_048_576) {
                throw new IllegalArgumentException("DM foreign-key DDL byte budget exceeded");
            }
            material.append(sql.length()).append(':').append(sql);
        }
        this.digest = DigestUtil.sha256Hex(material.toString());
    }

    /**
     * 返回约束集合。
     */
    public List<Constraint> constraints() {
        return constraints;
    }

    /**
     * 返回摘要。
     */
    public String digest() {
        return digest;
    }

    /**
     * 返回当前编译。
     */
    public static DmForeignKeyPlan compile(Map<Relation, DmNativeForeignKeys.Catalog> sources,
            Map<Relation, Target> targets) {
        sources = Map.copyOf(sources);
        targets = Map.copyOf(targets);
        if (sources.isEmpty() || sources.size() > 256 || !sources.keySet().equals(targets.keySet())
                || targets.values().stream().map(Target::relation).distinct().count() != targets.size()) {
            throw invalid();
        }
        var sorted = new ArrayList<>(sources.keySet());
        sorted.sort(Comparator.comparing(Relation::schema).thenComparing(Relation::table));
        var constraints = new ArrayList<Constraint>();
        for (var relation : sorted) {
            var catalog = sources.get(relation);
            if (!catalog.relation().equals(relation)) {
                throw invalid();
            }
            catalog.requireClosedSelection(sources.keySet());
            var child = targets.get(relation);
            for (var entry : catalog.entries().stream().sorted(Comparator.comparing(DmNativeForeignKeys.Entry::name))
                    .toList()) {
                var parent = targets.get(entry.parent());
                if (!entry.parentIdentity().equals(sources.get(entry.parent()).identity())) {
                    throw new IllegalArgumentException("DM referenced relation changed during dependency capture");
                }
                constraints.add(
                        new Constraint(targetName(entry.name(), child.relation()), child.relation(), parent.relation(),
                                mapped(entry.childColumns(), child.columns()),
                                mapped(entry.parentColumns(), parent.columns()), entry.onUpdate(), entry.onDelete(),
                                entry.enabled()));
            }
        }
        return new DmForeignKeyPlan(constraints);
    }

    /** 仅读取；在完整资源预检时执行，并在每个依赖 DDL 阶段之前立即再次执行。 */
    public void assertNamesAvailable(Connection c, int timeout) throws SQLException {
        for (var entry : constraints) {
            try (var p = DmNativeIndexes.prepare(c, "SELECT 1 FROM ALL_CONSTRAINTS WHERE owner=? AND constraint_name=?",
                    timeout, entry.child().schema(), entry.name()); var r = p.executeQuery()) {
                if (r.next()) {
                    throw new SQLException("DM planned foreign-key name is occupied");
                }
            }
        }
    }

    /**
     * 按目标配置生成当前对象的名称。
     */
    public static String targetName(String sourceConstraint, Relation target) {
        DmNativeIndexes.name(sourceConstraint);
        relation(target);
        return "DBSWFK_" + DigestUtil.sha256Hex(target.schema() + '\0' + target.table() + '\0' + sourceConstraint)
                .substring(0, 40).toUpperCase(Locale.ROOT);
    }

    /**
     * 将当前对象或数据行按既定映射转换为目标形式。
     */
    private static List<String> mapped(List<String> fields, Map<String, String> mapping) {
        if (!mapping.keySet().containsAll(fields)) {
            throw new IllegalArgumentException("DM mapping must retain all referenced foreign-key columns");
        }
        return fields.stream().map(mapping::get).toList();
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    private static void relation(Relation relation) {
        Objects.requireNonNull(relation);
        DmNativeIndexes.name(relation.schema());
        DmNativeIndexes.name(relation.table());
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid DM foreign-key dependency mapping");
    }
}
