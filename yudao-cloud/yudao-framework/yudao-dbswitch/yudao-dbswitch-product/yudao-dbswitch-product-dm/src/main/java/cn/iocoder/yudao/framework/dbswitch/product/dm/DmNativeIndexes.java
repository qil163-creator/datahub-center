package cn.iocoder.yudao.framework.dbswitch.product.dm;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;

import java.sql.*;
import java.util.*;

/** DM 行表列索引及其主键、唯一约束；Oracle 目录结构相似不代表语义相同。 */
public final class DmNativeIndexes {

    private static final int MAX_INDEXES = 256, MAX_KEYS = 32;

    public record Key(String column, boolean descending) {

        /**
         * 初始化 {@code Key}。
         */
        public Key {
            name(column);
        }
    }

    public record Entry(String sourceName, boolean clustered, boolean unique, List<Key> keys,
                        String constraintName, String constraintType) {

        /**
         * 初始化 {@code Entry}。
         */
        public Entry {
            name(sourceName);
            keys = List.copyOf(keys);
            if (keys.isEmpty() || keys.size() > MAX_KEYS || keys.stream().map(Key::column).distinct()
                    .count() != keys.size()) {
                throw invalid();
            }
            if ((constraintName == null) != (constraintType == null)) {
                throw invalid();
            }
            if (constraintName != null) {
                name(constraintName);
                if (!unique || !Set.of("P", "U").contains(constraintType) || keys.stream().anyMatch(Key::descending)) {
                    throw invalid();
                }
            }
        }

        /**
         * 读取或构建当前关系及字段的结构信息。
         */
        String shape() {
            return JSONUtil.toJsonStr(Arrays.asList(clustered, unique,
                    keys.stream().map(k -> List.of(k.column(), k.descending())).toList(), constraintType));
        }
    }

    public record Catalog(List<Entry> entries) {

        /**
         * 初始化 {@code Catalog}。
         */
        public Catalog {
            entries = List.copyOf(entries);
            if (entries.size() > MAX_INDEXES || entries.stream().map(Entry::sourceName).distinct()
                    .count() != entries.size()
                    || entries.stream().filter(Entry::clustered).count() > 1 || entries.stream()
                    .filter(e -> "P".equals(e.constraintType())).count() > 1
                    || entries.stream().filter(e -> e.constraintName() != null).map(Entry::constraintName).distinct()
                    .count() != entries.stream().filter(e -> e.constraintName() != null).count()) {
                throw invalid();
            }
        }

        /**
         * 返回键集合。
         */
        public List<String> keys() {
            return entries.stream().filter(e -> "P".equals(e.constraintType())).findFirst()
                    .map(e -> e.keys().stream().map(Key::column).toList()).orElse(List.of());
        }

        /**
         * 读取或构建当前关系及字段的结构信息。
         */
        List<String> shape() {
            return entries.stream().map(Entry::shape).sorted().toList();
        }

        /**
         * 将当前对象或数据行按既定映射转换为目标形式。
         */
        Catalog mapped(Map<String, String> mapping) {
            if (entries.stream().flatMap(e -> e.keys().stream()).anyMatch(k -> !mapping.containsKey(k.column()))) {
                throw new IllegalArgumentException("DM mapping must retain every indexed column");
            }
            return new Catalog(entries.stream().map(e -> new Entry(e.sourceName(), e.clustered(), e.unique(),
                    e.keys().stream().map(k -> new Key(mapping.get(k.column()), k.descending())).toList(),
                    e.constraintName(), e.constraintType())).toList());
        }
    }

    private record Constraint(String name, String type, List<String> fields) {

    }

    /**
     * 初始化 {@code DmNativeIndexes}。
     */
    private DmNativeIndexes() {
    }

    /**
     * 读取 DM 索引、主键及唯一约束，并关联已读取的检查约束。
     */
    static Catalog read(Connection connection, String schema, String table, Set<String> fields,
            DmNativeChecks.Catalog checks, int timeout) throws SQLException {
        Map<String, Constraint> constraints = new HashMap<>();
        Set<String> remainingChecks = checks.names();
        int constraintCount = 0;
        try (var p = prepare(connection,
                "SELECT constraint_name,constraint_type,status,validated,deferrable,deferred,index_owner,index_name,search_condition "
                        + "FROM ALL_CONSTRAINTS WHERE owner=? AND table_name=? ORDER BY constraint_name", timeout,
                schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                if (++constraintCount > MAX_INDEXES * 2) {
                    throw unsupported();
                }
                if ("C".equals(r.getString(2))) {
                    String checkName = r.getString(1);
                    if (!remainingChecks.remove(checkName)) {
                        throw unsupported();
                    }
                    var expected = checks.entries().stream().filter(e -> e.sourceName().equals(checkName)).findFirst()
                            .orElseThrow(DmNativeIndexes::invalid);
                    if (!Objects.equals(r.getString(3), expected.enabled() ? "ENABLED" : "DISABLED")
                            || !Objects.equals(r.getString(4), expected.enabled() ? "VALIDATED" : "NOT VALIDATED")
                            || !"NOT DEFERRABLE".equals(r.getString(5)) || !"IMMEDIATE".equals(
                            r.getString(6)) || r.getString(7) != null || r.getString(8) != null
                            || !expected.equals(
                            new DmNativeChecks.Entry(expected.sourceName(), r.getString(9), expected.columns(),
                                    expected.enabled()))) {
                        throw unsupported();
                    }
                    continue;
                }
                if (constraints.size() >= MAX_INDEXES || !Set.of("P", "U")
                        .contains(r.getString(2)) || !"ENABLED".equals(r.getString(3))
                        || !"VALIDATED".equals(r.getString(4)) || !"NOT DEFERRABLE".equals(
                        r.getString(5)) || !"IMMEDIATE".equals(r.getString(6))
                        || !schema.equals(r.getString(7)) || r.getString(9) != null) {
                    throw unsupported();
                }
                var keys = new ArrayList<String>();
                try (var k = prepare(connection,
                        "SELECT column_name,position FROM ALL_CONS_COLUMNS WHERE owner=? AND table_name=? AND constraint_name=? ORDER BY position",
                        timeout, schema, table, r.getString(1)); var kr = k.executeQuery()) {
                    while (kr.next()) {
                        if (keys.size() >= MAX_KEYS || kr.getInt(2) != keys.size() + 1 || !fields.contains(
                                kr.getString(1))) {
                            throw unsupported();
                        }
                        keys.add(kr.getString(1));
                    }
                }
                if (keys.isEmpty() || constraints.put(r.getString(8),
                        new Constraint(r.getString(1), r.getString(2), List.copyOf(keys))) != null) {
                    throw unsupported();
                }
            }
        }
        if (!remainingChecks.isEmpty()) {
            throw unsupported();
        }
        List<Entry> entries = new ArrayList<>();
        int clusterCount = 0, indexCount = 0;
        try (var p = prepare(connection,
                "SELECT owner,index_name,index_type,uniqueness,status,partitioned,temporary,compression,visibility,generated "
                        + "FROM ALL_INDEXES WHERE table_owner=? AND table_name=? ORDER BY index_name", timeout, schema,
                table); var r = p.executeQuery()) {
            while (r.next()) {
                String name = r.getString(2), type = r.getString(3), unique = r.getString(4), generated = r.getString(
                        10);
                if (++indexCount > MAX_INDEXES || !schema.equals(r.getString(1)) || !Set.of("NORMAL", "CLUSTER")
                        .contains(type)
                        || !Set.of("UNIQUE", "NONUNIQUE").contains(unique) || !"VALID".equals(
                        r.getString(5)) || !"NO".equals(r.getString(6))
                        || !"N".equals(r.getString(7)) || !"DISABLED".equals(r.getString(8)) || !"VISIBLE".equals(
                        r.getString(9))
                        || !Set.of("Y", "N").contains(generated)) {
                    throw unsupported();
                }
                boolean cluster = type.equals("CLUSTER");
                if (cluster && ++clusterCount > 1) {
                    throw unsupported();
                }
                List<Key> keys = new ArrayList<>();
                try (var k = prepare(connection,
                        "SELECT column_name,column_position,descend FROM ALL_IND_COLUMNS WHERE index_owner=? AND index_name=? ORDER BY column_position",
                        timeout, schema, name); var kr = k.executeQuery()) {
                    while (kr.next()) {
                        if (keys.size() >= MAX_KEYS || kr.getInt(2) != keys.size() + 1 || !fields.contains(
                                kr.getString(1)) || !Set.of("ASC", "DESC").contains(kr.getString(3))) {
                            throw unsupported();
                        }
                        keys.add(new Key(kr.getString(1), "DESC".equals(kr.getString(3))));
                    }
                }
                Constraint constraint = constraints.remove(name);
                // DM 自动生成的行编号 CLUSTER 索引没有用户键列。
                // 仅将这一完整观测特征视为隐式对象；未知索引或表达式索引绝不能被省略。
                if (keys.isEmpty() && cluster && "NONUNIQUE".equals(unique) && "Y".equals(
                        generated) && constraint == null) {
                    continue;
                }
                if (constraint != null && !constraint.fields().equals(keys.stream().map(Key::column).toList())
                        || constraint == null && "Y".equals(generated)) {
                    throw unsupported();
                }
                entries.add(new Entry(name, cluster, "UNIQUE".equals(unique), keys,
                        constraint == null ? null : constraint.name(), constraint == null ? null : constraint.type()));
            }
        }
        if (clusterCount != 1 || !constraints.isEmpty()) {
            throw unsupported();
        }
        return new Catalog(entries);
    }

    /**
     * 读取当前表的主键、唯一键及相关约束定义。
     */
    static List<String> tableConstraints(Catalog catalog, String schema, String table) {
        return catalog.entries().stream().filter(e -> e.constraintType() != null)
                .map(e -> "CONSTRAINT " + DmSqlNames.quote(targetName(e, schema, table)) + " "
                        + (e.constraintType()
                        .equals("P") ? (e.clustered() ? "CLUSTER PRIMARY KEY" : "NOT CLUSTER PRIMARY KEY") : (e.clustered() ? "CLUSTER UNIQUE KEY" : "UNIQUE")) + "(" + DmSqlNames.columns(
                        e.keys().stream().map(Key::column).toList()) + ")").toList();
    }

    /**
     * 返回当前语句集合。
     */
    static List<String> statements(Catalog catalog, String schema, String table) {
        return catalog.entries().stream().filter(e -> e.constraintType() == null)
                .sorted(Comparator.comparing(Entry::clustered).reversed().thenComparing(Entry::sourceName))
                .map(e -> "CREATE " + (e.clustered() ? "CLUSTER " : "") + (e.unique() ? "UNIQUE " : "") + "INDEX " + DmSqlNames.relation(
                        schema, targetName(e, schema, table))
                        + " ON " + DmSqlNames.relation(schema, table) + "(" + String.join(",",
                        e.keys().stream().map(k -> DmSqlNames.quote(k.column()) + (k.descending() ? " DESC" : " ASC"))
                                .toList()) + ")").toList();
    }

    /**
     * 按目标配置生成当前对象的名称。
     */
    public static String targetName(Entry e, String schema, String table) {
        return (e.constraintType() == null ? "DBSWIX_" : "DBSWCT_") + DigestUtil.sha256Hex(
                        schema + '\0' + table + '\0' + (e.constraintType() == null ? e.sourceName() : e.constraintName()))
                .substring(0, 40).toUpperCase(Locale.ROOT);
    }

    /**
     * 检查名称集合可用。
     */
    static void assertNamesAvailable(Connection c, Catalog catalog, String schema, String table, boolean owns,
            int timeout) throws SQLException {
        for (var e : catalog.entries()) {
            String query = e.constraintType() == null ? "SELECT table_name FROM ALL_INDEXES WHERE owner=? AND index_name=?" : "SELECT table_name FROM ALL_CONSTRAINTS WHERE owner=? AND constraint_name=?";
            try (var p = prepare(c, query, timeout, schema, targetName(e, schema, table)); var r = p.executeQuery()) {
                if (r.next() && (!owns || !table.equals(r.getString(1)) || r.next())) {
                    throw new SQLException("DM planned index or constraint name is occupied");
                }
            }
        }
    }

    /**
     * 将当前数据编码为JSON对象。
     */
    static JSONObject encode(Catalog catalog) {
        return new JSONObject().set("entries", catalog.entries().stream()
                .map(e -> new JSONObject().set("name", e.sourceName()).set("clustered", e.clustered())
                        .set("unique", e.unique())
                        .set("constraintName", e.constraintName()).set("constraintType", e.constraintType()).set("keys",
                                e.keys().stream().map(k -> new JSONObject().set("column", k.column())
                                        .set("descending", k.descending())).toList())).toList());
    }

    /**
     * 从编码数据恢复目录。
     */
    static Catalog decode(JSONObject root) {
        if (root == null || root.getJSONArray("entries") == null || root.getJSONArray("entries").size() > MAX_INDEXES) {
            throw invalid();
        }
        List<Entry> entries = new ArrayList<>();
        for (Object o : root.getJSONArray("entries")) {
            var e = (JSONObject) o;
            if (!(e.get("clustered") instanceof Boolean) || !(e.get("unique") instanceof Boolean) || e.getJSONArray(
                    "keys") == null || e.getJSONArray("keys").size() > MAX_KEYS) {
                throw invalid();
            }
            List<Key> keys = new ArrayList<>();
            for (Object key : e.getJSONArray("keys")) {
                var k = (JSONObject) key;
                if (!(k.get("descending") instanceof Boolean)) {
                    throw invalid();
                }
                keys.add(new Key(k.getStr("column"), k.getBool("descending")));
            }
            entries.add(new Entry(e.getStr("name"), e.getBool("clustered"), e.getBool("unique"), keys,
                    e.getStr("constraintName"), e.getStr("constraintType")));
        }
        return new Catalog(entries);
    }

    /**
     * 创建带查询超时和参数绑定的目录查询语句。
     */
    static PreparedStatement prepare(Connection c, String sql, int timeout, String... parameters) throws SQLException {
        if (timeout < 1) {
            throw new IllegalArgumentException("Positive DM query timeout required");
        }
        var p = c.prepareStatement(sql);
        try {
            p.setQueryTimeout(timeout);
            for (int i = 0; i < parameters.length; i++) {
                p.setString(i + 1, parameters[i]);
            }
            return p;
        } catch (SQLException | RuntimeException failure) {
            p.close();
            throw failure;
        }
    }

    /**
     * 返回名称。
     */
    static void name(String value) {
        DmSqlNames.quote(value);
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128) {
            throw invalid();
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isSurrogate(value.charAt(i))) {
                if (!Character.isHighSurrogate(value.charAt(i)) || i + 1 == value.length() || !Character.isLowSurrogate(
                        value.charAt(++i))) {
                    throw invalid();
                }
            }
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid DM index evidence");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLException unsupported() {
        return new SQLException("DM index or constraint requires a verified structure strategy");
    }
}
