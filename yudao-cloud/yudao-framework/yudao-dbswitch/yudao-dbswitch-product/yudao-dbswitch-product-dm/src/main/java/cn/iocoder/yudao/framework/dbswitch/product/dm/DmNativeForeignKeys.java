package cn.iocoder.yudao.framework.dbswitch.product.dm;

import cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmDriverCompatibility;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** 原生外键凭据；DM 的 Oracle 兼容 DELETE_RULE 丢失 SET DEFAULT，且没有 UPDATE 动作。 */
public final class DmNativeForeignKeys {

    public enum Action {
        NO_ACTION(' ', "NO ACTION"), CASCADE('C', "CASCADE"), SET_NULL('N', "SET NULL"), SET_DEFAULT('D',
                "SET DEFAULT");

        final char code;

        final String sql;

        /**
         * 初始化 {@code Action}。
         */
        Action(char code, String sql) {
            this.code = code;
            this.sql = sql;
        }

        /**
         * 返回SQL 文本。
         */
        public String sql() {
            return sql;
        }

        /**
         * 从编码数据恢复动作。
         */
        static Action decode(char code) {
            for (var value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
            throw invalid();
        }
    }

    public record Entry(String name, Relation child, Relation parent, List<String> childColumns,
                        List<String> parentColumns,
                        Action onUpdate, Action onDelete, boolean enabled, long constraintId, long supportingIndexId,
                        String supportingIndexName, String supportingIndexType, long referencedIndexId,
                        long actionTriggerId, String parentIdentity) {

        /**
         * 校验构造参数并初始化 {@code Entry}。
         */
        public Entry {
            DmNativeIndexes.name(name);
            relation(child);
            relation(parent);
            childColumns = List.copyOf(childColumns);
            parentColumns = List.copyOf(parentColumns);
            Objects.requireNonNull(onUpdate);
            Objects.requireNonNull(onDelete);
            DmNativeIndexes.name(supportingIndexName);
            if (childColumns.isEmpty() || childColumns.size() > 32 || childColumns.size() != parentColumns.size()
                    || childColumns.stream().distinct().count() != childColumns.size() || parentColumns.stream()
                    .distinct().count() != parentColumns.size()
                    || constraintId <= 0 || supportingIndexId <= 0 || referencedIndexId <= 0 || actionTriggerId == 0 || actionTriggerId < -1
                    || !Objects.equals(supportingIndexType, child.equals(parent) ? "NORMAL" : "VIRTUAL")
                    || parentIdentity == null || !parentIdentity.matches("[0-9a-f]{64}")) {
                throw invalid();
            }
            childColumns.forEach(DmNativeIndexes::name);
            parentColumns.forEach(DmNativeIndexes::name);
        }
    }

    public record Catalog(Relation relation, String identity, List<Entry> entries) {

        /**
         * 初始化 {@code Catalog}。
         */
        public Catalog {
            DmNativeForeignKeys.relation(relation);
            entries = List.copyOf(entries);
            if (identity == null || !identity.matches("[0-9a-f]{64}") || entries.size() > 256
                    || entries.stream().map(Entry::name).distinct().count() != entries.size()
                    || entries.stream().anyMatch(e -> !e.child().equals(relation))) {
                throw invalid();
            }
        }

        /** 出向引用必须位于选定迁移资源内，绝不隐式扩展选择范围。 */
        public void requireClosedSelection(Set<Relation> selected) {
            if (!selected.contains(relation) || entries.stream().anyMatch(e -> !selected.contains(e.parent()))) {
                throw new IllegalArgumentException("DM foreign-key selection must include every referenced relation");
            }
        }
    }

    /**
     * 初始化 {@code DmNativeForeignKeys}。
     */
    private DmNativeForeignKeys() {
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    public static Catalog describe(DataSource source, String schema, String table, int timeout) {
        try (var c = source.getConnection()) {
            Catalog first = read(c, schema, table, timeout), second = read(c, schema, table, timeout);
            if (!first.equals(second)) {
                throw unavailable();
            }
            return first;
        } catch (SQLException | RuntimeException failure) {
            throw new IllegalStateException("DM foreign-key catalog evidence is unavailable or changed");
        }
    }

    /**
     * 读取 DM 原生外键定义并关联物理表身份。
     */
    static Catalog read(Connection c, String schema, String table, int timeout) throws SQLException {
        relation(new Relation(schema, table));
        DmDriverCompatibility.require(c);
        String identity = DmRelationIdentity.physicalFingerprint(c, schema, table, timeout);
        var entries = new ArrayList<Entry>();
        String sql = "SELECT co.name,fk.id,fk.indexid,ci.name,fk.findexid,fk.faction,fk.trigid,fk.valid,ps.name,pt.name,pc.name,"
                + "a.status,a.validated,a.deferrable,a.deferred,a.r_owner,a.r_constraint_name,a.search_condition,"
                + "tr.pid,tr.type$,tr.subtype$,pt.id,ct.id,ci.pid,pi.pid "
                + "FROM SYS.SYSCONS fk JOIN SYS.SYSOBJECTS co ON co.id=fk.id JOIN SYS.SYSOBJECTS ct ON ct.id=fk.tableid "
                + "JOIN SYS.SYSOBJECTS cs ON cs.id=ct.schid JOIN SYS.SYSOBJECTS ci ON ci.id=fk.indexid "
                + "JOIN SYS.SYSOBJECTS pi ON pi.id=fk.findexid JOIN SYS.SYSOBJECTS pt ON pt.id=pi.pid "
                + "JOIN SYS.SYSOBJECTS ps ON ps.id=pt.schid JOIN SYS.SYSCONS pk ON pk.indexid=fk.findexid AND pk.type$ IN('P','U') "
                + "JOIN SYS.SYSOBJECTS pc ON pc.id=pk.id LEFT JOIN SYS.SYSOBJECTS tr ON tr.id=fk.trigid "
                + "LEFT JOIN ALL_CONSTRAINTS a ON a.owner=cs.name AND a.table_name=ct.name AND a.constraint_name=co.name AND a.constraint_type='R' "
                + "WHERE cs.name=? AND ct.name=? AND fk.type$='F' ORDER BY co.name";
        try (var p = DmNativeIndexes.prepare(c, sql, timeout, schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                if (entries.size() >= 256) {
                    throw unavailable();
                }
                String name = r.getString(1), actions = r.getString(6), valid = r.getString(8);
                boolean enabled = "Y".equals(valid);
                if (!enabled && !"N".equals(valid) || actions == null || actions.length() != 2) {
                    throw unavailable();
                }
                var update = Action.decode(actions.charAt(0));
                var delete = Action.decode(actions.charAt(1));
                String parentSchema = r.getString(9), parentTable = r.getString(10), parentConstraint = r.getString(11);
                if (!Objects.equals(enabled ? "ENABLED" : "DISABLED", r.getString(12)) || !Objects.equals(
                        enabled ? "VALIDATED" : "NOT VALIDATED", r.getString(13))
                        || !"NOT DEFERRABLE".equals(r.getString(14)) || !"IMMEDIATE".equals(r.getString(15))
                        || !parentSchema.equals(r.getString(16)) || !parentConstraint.equals(
                        r.getString(17)) || r.getString(18) != null
                        || positive(r, 23) != positive(r, 24) || positive(r, 22) != positive(r, 25)) {
                    throw unavailable();
                }
                long trigger = r.getLong(7);
                if (r.wasNull()) {
                    throw unavailable();
                }
                if (update == Action.NO_ACTION && delete == Action.NO_ACTION) {
                    if (trigger != -1 || r.getObject(19) != null) {
                        throw unavailable();
                    }
                } else if (trigger <= 0 || positive(r, 19) != positive(r, 22) || !"SCHOBJ".equals(
                        r.getString(20)) || !"TRIG".equals(r.getString(21))) {
                    throw unavailable();
                }
                var childColumns = constraintColumns(c, schema, table, name, timeout);
                var parentColumns = constraintColumns(c, parentSchema, parentTable, parentConstraint, timeout);
                String supportingType = schema.equals(parentSchema) && table.equals(parentTable) ? "NORMAL" : "VIRTUAL";
                verifyIndex(c, schema, table, r.getString(4), childColumns, supportingType, timeout);
                try (var parentIndex = DmNativeIndexes.prepare(c, "SELECT name FROM SYS.SYSOBJECTS WHERE id=?",
                        timeout)) {
                    parentIndex.setLong(1, positive(r, 5));
                    try (var parentRow = parentIndex.executeQuery()) {
                        if (!parentRow.next()) {
                            throw unavailable();
                        }
                        verifyIndex(c, parentSchema, parentTable, parentRow.getString(1), parentColumns, null, timeout);
                        if (parentRow.next()) {
                            throw unavailable();
                        }
                    }
                }
                entries.add(new Entry(name, new Relation(schema, table), new Relation(parentSchema, parentTable),
                        childColumns, parentColumns, update, delete, enabled,
                        positive(r, 2), positive(r, 3), r.getString(4), supportingType, positive(r, 5), trigger,
                        DmRelationIdentity.physicalFingerprint(c, parentSchema, parentTable, timeout)));
            }
        }
        // 内连接绝不能将缺失的原生或公开凭据转为空关系图。
        try (var p = DmNativeIndexes.prepare(c,
                "SELECT COUNT(*) FROM SYS.SYSCONS fk JOIN SYS.SYSOBJECTS t ON t.id=fk.tableid JOIN SYS.SYSOBJECTS s ON s.id=t.schid WHERE s.name=? AND t.name=? AND fk.type$='F'",
                timeout, schema, table); var r = p.executeQuery()) {
            if (!r.next() || r.getLong(1) != entries.size() || r.wasNull() || r.next()) {
                throw unavailable();
            }
        }
        if (!identity.equals(DmRelationIdentity.physicalFingerprint(c, schema, table, timeout))) {
            throw unavailable();
        }
        return new Catalog(new Relation(schema, table), identity, entries);
    }

    /**
     * 按约束中的列顺序读取约束列名。
     */
    private static List<String> constraintColumns(Connection c, String schema, String table, String constraint,
            int timeout) throws SQLException {
        var fields = new ArrayList<String>();
        try (var p = DmNativeIndexes.prepare(c,
                "SELECT k.column_name,k.position FROM ALL_CONS_COLUMNS k JOIN ALL_TAB_COLS col ON col.owner=k.owner AND col.table_name=k.table_name AND col.column_name=k.column_name WHERE k.owner=? AND k.table_name=? AND k.constraint_name=? ORDER BY k.position",
                timeout, schema, table, constraint); var r = p.executeQuery()) {
            while (r.next()) {
                if (fields.size() >= 32 || r.getInt(2) != fields.size() + 1 || r.wasNull()) {
                    throw unavailable();
                }
                fields.add(r.getString(1));
            }
        }
        if (fields.isEmpty()) {
            throw unavailable();
        }
        return List.copyOf(fields);
    }

    /**
     * 校验索引。
     */
    private static void verifyIndex(Connection c, String schema, String table, String index, List<String> keys,
            String supportingType, int timeout) throws SQLException {
        boolean supporting = supportingType != null;
        try (var p = DmNativeIndexes.prepare(c,
                "SELECT table_name,index_type,uniqueness,generated,status,partitioned,temporary FROM ALL_INDEXES WHERE owner=? AND index_name=?",
                timeout, schema, index); var r = p.executeQuery()) {
            if (!r.next() || !table.equals(r.getString(1)) || !(supporting ? supportingType.equals(
                    r.getString(2)) : Set.of("NORMAL", "CLUSTER").contains(r.getString(2)))
                    || !Objects.equals(supporting ? "NONUNIQUE" : "UNIQUE",
                    r.getString(3)) || supporting && !"Y".equals(r.getString(4))
                    || !"VALID".equals(r.getString(5)) || !"NO".equals(r.getString(6)) || !"N".equals(
                    r.getString(7)) || r.next()) {
                throw unavailable();
            }
        }
        var fields = new ArrayList<String>();
        try (var p = DmNativeIndexes.prepare(c,
                "SELECT column_name,column_position,descend FROM ALL_IND_COLUMNS WHERE index_owner=? AND index_name=? ORDER BY column_position",
                timeout, schema, index); var r = p.executeQuery()) {
            while (r.next()) {
                if (fields.size() >= 32 || r.getInt(2) != fields.size() + 1 || r.wasNull() || !"ASC".equals(
                        r.getString(3))) {
                    throw unavailable();
                }
                fields.add(r.getString(1));
            }
        }
        if (!keys.equals(fields)) {
            throw unavailable();
        }
    }

    /**
     * 返回当前正数。
     */
    private static long positive(ResultSet row, int index) throws SQLException {
        long value = row.getLong(index);
        if (row.wasNull() || value <= 0) {
            throw unavailable();
        }
        return value;
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    private static void relation(Relation relation) {
        Objects.requireNonNull(relation);
        DmSqlNames.relation(relation.schema(), relation.table());
        DmNativeIndexes.name(relation.schema());
        DmNativeIndexes.name(relation.table());
    }

    /**
     * 根据当前输入构建并返回 {@code SQLException}。
     */
    private static SQLException unavailable() {
        return new SQLException("DM foreign-key native catalog is incomplete or inconsistent");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid DM foreign-key native evidence");
    }
}
