package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;

import java.sql.*;
import java.util.*;

/** 连接范围内的 PostgreSQL 继承图，元数据列表仍保持完整。 */
public final class PostgresTableReads {

    /**
     * 初始化 {@code PostgresTableReads}。
     */
    private PostgresTableReads() {
    }

    private static final String GRAPH = """
            SELECT c.oid, n.nspname, c.relname, c.relispartition, i.inhparent
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            LEFT JOIN pg_catalog.pg_inherits i ON i.inhrelid=c.oid
            WHERE c.relkind IN ('r','p','v','m','f') ORDER BY c.oid,i.inhseqno
            """;

    /**
     * 拒绝当前实现未支持的 {@code plan} 操作。
     */
    public static TableReadPlan plan(Connection connection, Collection<Relation> selected,
            TableReadPlan.ReadMode mode) throws SQLException {
        Objects.requireNonNull(mode, "mode");
        var requests = new LinkedHashSet<>(selected);
        if (requests.isEmpty() || requests.size() > 10_000) {
            throw new IllegalArgumentException("Invalid relation selection size");
        }
        Map<Long, Relation> names = new LinkedHashMap<>();
        Map<Relation, Long> ids = new HashMap<>();
        Map<Long, Set<Long>> children = new HashMap<>();
        Set<Long> partitions = new HashSet<>();
        Map<Long, Integer> parentCounts = new HashMap<>();
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            statement.setMaxRows(20_001);
            try (ResultSet rows = statement.executeQuery(GRAPH)) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 20_000) {
                        throw new SQLException("PostgreSQL relation graph exceeds its budget");
                    }
                    long oid = rows.getLong(1);
                    Relation relation = new Relation(rows.getString(2), rows.getString(3));
                    Relation previous = names.putIfAbsent(oid, relation);
                    if (previous != null && !previous.equals(relation)) {
                        throw new SQLException("Relation identity changed during graph read");
                    }
                    ids.put(relation, oid);
                    if (rows.getBoolean(4)) {
                        partitions.add(oid);
                    }
                    long parent = rows.getLong(5);
                    if (!rows.wasNull()) {
                        children.computeIfAbsent(parent, ignored -> new LinkedHashSet<>()).add(oid);
                        parentCounts.merge(oid, 1, Integer::sum);
                    }
                }
            }
        }
        for (Long partition : partitions) {
            if (parentCounts.getOrDefault(partition, 0) != 1) {
                throw new SQLException("Incomplete PostgreSQL partition ancestry");
            }
        }
        for (Long parent : children.keySet()) {
            if (!names.containsKey(parent)) {
                throw new SQLException("Incomplete PostgreSQL inheritance graph");
            }
        }
        for (Relation relation : requests) {
            if (!ids.containsKey(relation)) {
                throw new IllegalArgumentException(
                        "Selected PostgreSQL relation is unavailable or is not a physical table");
            }
        }
        Map<Relation, Set<Long>> coverage = new LinkedHashMap<>();
        for (Relation relation : requests) {
            coverage.put(relation, mode == TableReadPlan.ReadMode.ONLY
                    ? Set.of(ids.get(relation)) : descendants(ids.get(relation), children));
        }
        var roots = new LinkedHashSet<>(requests);
        if (mode == TableReadPlan.ReadMode.INCLUDE_DESCENDANTS) {
            for (Relation candidate : requests) {
                for (Relation other : requests) {
                    if (!candidate.equals(other) && coverage.get(other).contains(ids.get(candidate))) {
                        roots.remove(candidate);
                    }
                }
            }
        }
        Map<Long, Relation> owners = new HashMap<>();
        List<TableReadPlan.Read> reads = new ArrayList<>();
        for (Relation root : roots) {
            List<Relation> covered = new ArrayList<>();
            for (Long oid : coverage.get(root)) {
                if (owners.putIfAbsent(oid, root) != null) {
                    throw new UnsupportedOperationException(
                            "Selected inheritance parents overlap; use explicit ONLY reads or select one ancestor");
                }
                covered.add(names.get(oid));
            }
            String from = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.POSTGRESQL, root.schema(),
                    root.table());
            reads.add(
                    new TableReadPlan.Read(root, mode == TableReadPlan.ReadMode.ONLY ? "ONLY " + from : from, covered));
        }
        Map<Relation, Relation> omitted = new LinkedHashMap<>();
        for (Relation request : requests) {
            if (!roots.contains(request)) {
                omitted.put(request, owners.get(ids.get(request)));
            }
        }
        return new TableReadPlan(mode, reads, omitted);
    }

    /**
     * 返回当前后代关系。
     */
    private static Set<Long> descendants(long start, Map<Long, Set<Long>> children) {
        Set<Long> result = new LinkedHashSet<>();
        Set<Long> active = new HashSet<>();
        walk(start, children, result, active, 0);
        return result;
    }

    /**
     * 遍历关系依赖图，并拒绝循环或超过深度上限的结构。
     */
    private static void walk(long relation, Map<Long, Set<Long>> children, Set<Long> result, Set<Long> active,
            int depth) {
        if (depth > 64 || !active.add(relation)) {
            throw new IllegalArgumentException("Cyclic or excessively nested relation graph");
        }
        if (result.add(relation)) {
            for (Long child : children.getOrDefault(relation, Set.of())) {
                walk(child, children, result, active, depth + 1);
            }
        }
        active.remove(relation);
    }
}
