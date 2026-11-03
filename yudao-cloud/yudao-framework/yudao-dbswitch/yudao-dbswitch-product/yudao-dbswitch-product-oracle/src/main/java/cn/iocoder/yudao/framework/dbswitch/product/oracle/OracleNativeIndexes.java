package cn.iocoder.yudao.framework.dbswitch.product.oracle;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleSqlNames;

import java.sql.*;
import java.util.*;

/** Oracle 23 目录凭据；仅渲染列 B 树，绝不重放目录 SQL。 */
public final class OracleNativeIndexes {

    private static final int MAX_INDEXES = 256, MAX_KEYS = 32;

    public record Key(String column, boolean descending) {

        /**
         * 初始化 {@code Key}。
         */
        public Key {
            OracleSqlNames.quote(column);
        }
    }

    public record Index(String sourceName, boolean unique, boolean visible, List<Key> keys) {

        /**
         * 初始化 {@code Index}。
         */
        public Index {
            OracleSqlNames.quote(sourceName);
            keys = List.copyOf(keys);
            if (keys.isEmpty() || keys.size() > MAX_KEYS || keys.stream().map(Key::column).distinct()
                    .count() != keys.size()) {
                throw invalid();
            }
        }

        /**
         * 读取或计算当前对象的语义描述。
         */
        String semantic() {
            return JSONUtil.toJsonStr(
                    List.of(unique, visible, keys.stream().map(k -> List.of(k.column(), k.descending())).toList()));
        }
    }

    public record Constraint(String sourceName, String type, List<String> columns, String indexName) {

        /**
         * 初始化 {@code Constraint}。
         */
        public Constraint {
            OracleSqlNames.quote(sourceName);
            OracleSqlNames.quote(indexName);
            columns = List.copyOf(columns);
            if (!Set.of("P", "U").contains(type) || columns.isEmpty() || columns.size() > MAX_KEYS || new HashSet<>(
                    columns).size() != columns.size()) {
                throw invalid();
            }
            columns.forEach(OracleSqlNames::quote);
        }
    }

    public record Catalog(List<Index> indexes, List<Constraint> constraints) {

        /**
         * 初始化 {@code Catalog}。
         */
        public Catalog {
            indexes = List.copyOf(indexes);
            constraints = List.copyOf(constraints);
            if (indexes.size() > MAX_INDEXES || constraints.size() > MAX_INDEXES || indexes.stream()
                    .map(Index::sourceName).distinct().count() != indexes.size()
                    || constraints.stream().map(Constraint::sourceName).distinct()
                    .count() != constraints.size() || constraints.stream().filter(c -> c.type().equals("P"))
                    .count() > 1) {
                throw invalid();
            }
            Map<String, Index> byName = new HashMap<>();
            indexes.forEach(i -> byName.put(i.sourceName(), i));
            for (var c : constraints) {
                if (!byName.containsKey(c.indexName()) || !byName.get(c.indexName()).keys().stream().map(Key::column)
                        .toList().containsAll(c.columns())) {
                    throw invalid();
                }
            }
        }

        /**
         * 返回主键集合。
         */
        public List<String> primaryKeys() {
            return constraints.stream().filter(c -> c.type().equals("P")).findFirst().map(Constraint::columns)
                    .orElse(List.of());
        }

        /**
         * 将当前对象或数据行按既定映射转换为目标形式。
         */
        public Catalog mapped(Map<String, String> mapping) {
            for (var i : indexes) {
                for (var k : i.keys()) {
                    if (!mapping.containsKey(k.column())) {
                        throw new IllegalArgumentException("Oracle mapping must retain every indexed column");
                    }
                }
            }
            return new Catalog(indexes.stream().map(i -> new Index(i.sourceName(), i.unique(), i.visible(),
                    i.keys().stream().map(k -> new Key(mapping.get(k.column()), k.descending())).toList())).toList(),
                    constraints.stream().map(c -> new Constraint(c.sourceName(), c.type(),
                            c.columns().stream().map(mapping::get).toList(), c.indexName())).toList());
        }

        /**
         * 读取或计算当前对象的语义描述。
         */
        public List<Object> semantic() {
            Map<String, Index> byName = new HashMap<>();
            indexes.forEach(i -> byName.put(i.sourceName(), i));
            return List.of(indexes.stream().map(Index::semantic).sorted().toList(), constraints.stream()
                    .map(c -> JSONUtil.toJsonStr(List.of(c.type(), c.columns(), byName.get(c.indexName()).semantic())))
                    .sorted().toList());
        }
    }

    record Read(Catalog catalog, Map<String, String> descendingHiddenColumns) {

    }

    /**
     * 初始化 {@code OracleNativeIndexes}。
     */
    private OracleNativeIndexes() {
    }

    /**
     * 从 Oracle 原生目录读取索引与关联约束定义。
     */
    static Read read(Connection connection, String schema, String table, Set<String> fields, Set<String> notNull,
            int timeout) throws SQLException {
        List<Index> indexes = new ArrayList<>();
        Map<String, String> hidden = new HashMap<>();
        String sql = "SELECT owner,index_name,index_type,uniqueness,status,partitioned,temporary,secondary,compression,visibility,funcidx_status,join_index,indexing,auto "
                + "FROM sys.all_indexes WHERE table_owner=? AND table_name=? ORDER BY index_name";
        try (var p = prepare(connection, sql, timeout, schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                String owner = r.getString(1), name = r.getString(2), type = r.getString(3), unique = r.getString(
                        4), visibility = r.getString(10);
                if (indexes.size() >= MAX_INDEXES || !schema.equals(owner) || !Set.of("NORMAL", "FUNCTION-BASED NORMAL")
                        .contains(type)
                        || !Set.of("UNIQUE", "NONUNIQUE").contains(unique) || !"VALID".equals(
                        r.getString(5)) || !"NO".equals(r.getString(6))
                        || !"N".equals(r.getString(7)) || !"N".equals(r.getString(8)) || !"DISABLED".equals(
                        r.getString(9))
                        || !Set.of("VISIBLE", "INVISIBLE").contains(visibility) || !"NO".equals(
                        r.getString(12)) || !"FULL".equals(r.getString(13)) || !"NO".equals(r.getString(14))
                        || (type.equals("FUNCTION-BASED NORMAL") && !"ENABLED".equals(r.getString(11)))) {
                    throw unsupported();
                }
                Map<Integer, String> expressions = new HashMap<>();
                try (var ep = prepare(connection,
                        "SELECT column_position,column_expression FROM sys.all_ind_expressions WHERE index_owner=? AND index_name=? ORDER BY column_position",
                        timeout, owner, name); var er = ep.executeQuery()) {
                    while (er.next()) {
                        if (expressions.put(er.getInt(1), er.getString(2)) != null || expressions.size() > MAX_KEYS) {
                            throw unsupported();
                        }
                    }
                }
                List<Key> keys = new ArrayList<>();
                try (var kp = prepare(connection,
                        "SELECT column_name,column_position,descend FROM sys.all_ind_columns WHERE index_owner=? AND index_name=? ORDER BY column_position",
                        timeout, owner, name); var kr = kp.executeQuery()) {
                    while (kr.next()) {
                        String physical = kr.getString(1), order = kr.getString(3);
                        int position = kr.getInt(2);
                        if (position != keys.size() + 1 || keys.size() >= MAX_KEYS || !Set.of("ASC", "DESC")
                                .contains(order)) {
                            throw unsupported();
                        }
                        String column = physical, expression = expressions.remove(position);
                        if (order.equals("DESC")) {
                            // DESC 通过系统隐藏列表示；仅接受一个带引号的
                            // 现有列，绝不接受任意函数、表达式或用户虚拟列。
                            column = null;
                            for (String field : fields) {
                                if (OracleSqlNames.quote(field).equals(expression)) {
                                    column = field;
                                    break;
                                }
                            }
                            if (column == null || fields.contains(physical)) {
                                throw unsupported();
                            }
                            String prior = hidden.putIfAbsent(physical, column);
                            if (prior != null && !prior.equals(column)) {
                                throw unsupported();
                            }
                        } else if (expression != null || !fields.contains(column)) {
                            throw unsupported();
                        }
                        keys.add(new Key(column, order.equals("DESC")));
                    }
                }
                if (!expressions.isEmpty() || type.equals("FUNCTION-BASED NORMAL") != keys.stream()
                        .anyMatch(Key::descending)) {
                    throw unsupported();
                }
                indexes.add(new Index(name, unique.equals("UNIQUE"), visibility.equals("VISIBLE"), keys));
            }
        }
        List<Constraint> constraints = new ArrayList<>();
        sql = "SELECT constraint_name,constraint_type,status,validated,deferrable,deferred,rely,index_owner,index_name,search_condition_vc "
                + "FROM sys.all_constraints WHERE owner=? AND table_name=? ORDER BY constraint_name";
        try (var p = prepare(connection, sql, timeout, schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                String name = r.getString(1), type = r.getString(2);
                if (!"ENABLED".equals(r.getString(3)) || !"VALIDATED".equals(
                        r.getString(4)) || !"NOT DEFERRABLE".equals(r.getString(5))
                        || !"IMMEDIATE".equals(r.getString(6)) || r.getString(7) != null) {
                    throw unsupported();
                }
                List<String> columns = new ArrayList<>();
                try (var cp = prepare(connection,
                        "SELECT column_name,position FROM sys.all_cons_columns WHERE owner=? AND table_name=? AND constraint_name=? ORDER BY position",
                        timeout, schema, table, name); var cr = cp.executeQuery()) {
                    while (cr.next()) {
                        if (columns.size() >= MAX_KEYS || (!type.equals("C") && cr.getInt(
                                2) != columns.size() + 1) || !fields.contains(cr.getString(1))) {
                            throw unsupported();
                        }
                        columns.add(cr.getString(1));
                    }
                }
                if (type.equals("C")) {
                    String expression = r.getString(10);
                    if (columns.size() != 1 || !notNull.contains(columns.getFirst()) || !Objects.equals(
                            OracleSqlNames.quote(columns.getFirst()) + " IS NOT NULL",
                            expression == null ? null : expression.trim())) {
                        throw unsupported();
                    }
                } else {
                    if (!Set.of("P", "U").contains(type) || !schema.equals(
                            r.getString(8)) || constraints.size() >= MAX_INDEXES) {
                        throw unsupported();
                    }
                    constraints.add(new Constraint(name, type, columns, r.getString(9)));
                }
            }
        }
        return new Read(new Catalog(indexes, constraints), Map.copyOf(hidden));
    }

    /**
     * 检查名称集合可用。
     */
    static void assertNamesAvailable(Connection connection, Catalog catalog, String schema, String table,
            boolean ownsExisting, int timeout) throws SQLException {
        for (var index : catalog.indexes()) {
            try (var p = prepare(connection,
                    "SELECT table_owner,table_name FROM sys.all_indexes WHERE owner=? AND index_name=?", timeout,
                    schema, indexName(index, schema, table)); var r = p.executeQuery()) {
                if (r.next() && (!ownsExisting || !schema.equals(r.getString(1)) || !table.equals(r.getString(2)))) {
                    throw new SQLException("Oracle planned index name belongs to another table");
                }
            }
        }
        for (var constraint : catalog.constraints()) {
            try (var p = prepare(connection,
                    "SELECT table_name FROM sys.all_constraints WHERE owner=? AND constraint_name=?", timeout, schema,
                    constraintName(constraint, schema, table)); var r = p.executeQuery()) {
                if (r.next() && (!ownsExisting || !table.equals(r.getString(1)))) {
                    throw new SQLException("Oracle planned constraint name belongs to another table");
                }
            }
        }
    }

    /**
     * 生成原生对象的创建 SQL。
     */
    static List<String> createSql(Catalog catalog, String schema, String table) {
        var result = new ArrayList<String>();
        Map<String, Index> indexes = new HashMap<>();
        catalog.indexes().forEach(i -> indexes.put(i.sourceName(), i));
        for (var index : catalog.indexes()) {
            result.add("CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + OracleSqlNames.relation(schema,
                    indexName(index, schema, table)) + " ON " + OracleSqlNames.relation(schema, table)
                    + "(" + String.join(",", index.keys().stream()
                    .map(k -> OracleSqlNames.quote(k.column()) + (k.descending() ? " DESC" : " ASC"))
                    .toList()) + ")" + (index.visible() ? " VISIBLE" : " INVISIBLE"));
        }
        for (var c : catalog.constraints()) {
            result.add(
                    "ALTER TABLE " + OracleSqlNames.relation(schema, table) + " ADD CONSTRAINT " + OracleSqlNames.quote(
                            constraintName(c, schema, table)) + ' '
                            + (c.type().equals("P") ? "PRIMARY KEY" : "UNIQUE") + '(' + OracleSqlNames.columns(
                            c.columns()) + ") USING INDEX " + OracleSqlNames.relation(schema,
                            indexName(indexes.get(c.indexName()), schema, table)) + " ENABLE VALIDATE");
        }
        return List.copyOf(result);
    }

    /**
     * 按当前对象定义生成或读取索引名称。
     */
    public static String indexName(Index index, String schema, String table) {
        return "DBSWIX_" + DigestUtil.sha256Hex(schema + '\0' + table + '\0' + index.sourceName()).substring(0, 40)
                .toUpperCase(Locale.ROOT);
    }

    /**
     * 按当前对象定义生成或读取约束名称。
     */
    public static String constraintName(Constraint constraint, String schema, String table) {
        return "DBSWCT_" + DigestUtil.sha256Hex(schema + '\0' + table + '\0' + constraint.sourceName()).substring(0, 40)
                .toUpperCase(Locale.ROOT);
    }

    /**
     * 将当前数据编码为JSON对象。
     */
    static JSONObject encode(Catalog catalog) {
        return new JSONObject().set("indexes", catalog.indexes().stream()
                        .map(i -> new JSONObject().set("name", i.sourceName()).set("unique", i.unique())
                                .set("visible", i.visible())
                                .set("keys", i.keys().stream()
                                        .map(k -> new JSONObject().set("column", k.column()).set("descending", k.descending()))
                                        .toList())).toList())
                .set("constraints", catalog.constraints().stream()
                        .map(c -> new JSONObject().set("name", c.sourceName()).set("type", c.type())
                                .set("columns", c.columns()).set("index", c.indexName())).toList());
    }

    /**
     * 从编码数据恢复目录。
     */
    static Catalog decode(JSONObject root) {
        if (root == null || root.getJSONArray("indexes") == null || root.getJSONArray(
                "constraints") == null || root.getJSONArray("indexes").size() > MAX_INDEXES || root.getJSONArray(
                "constraints").size() > MAX_INDEXES) {
            throw invalid();
        }
        var indexes = new ArrayList<Index>();
        var constraints = new ArrayList<Constraint>();
        for (Object object : root.getJSONArray("indexes")) {
            var i = (JSONObject) object;
            if (!(i.get("unique") instanceof Boolean) || !(i.get("visible") instanceof Boolean) || i.getJSONArray(
                    "keys") == null || i.getJSONArray("keys").size() > MAX_KEYS) {
                throw invalid();
            }
            var keys = new ArrayList<Key>();
            for (Object entry : i.getJSONArray("keys")) {
                var k = (JSONObject) entry;
                if (!(k.get("descending") instanceof Boolean)) {
                    throw invalid();
                }
                keys.add(new Key(k.getStr("column"), k.getBool("descending")));
            }
            indexes.add(new Index(i.getStr("name"), i.getBool("unique"), i.getBool("visible"), keys));
        }
        for (Object object : root.getJSONArray("constraints")) {
            var c = (JSONObject) object;
            constraints.add(
                    new Constraint(c.getStr("name"), c.getStr("type"), c.getJSONArray("columns").toList(String.class),
                            c.getStr("index")));
        }
        return new Catalog(indexes, constraints);
    }

    /**
     * 创建带查询超时和参数绑定的目录查询语句。
     */
    private static PreparedStatement prepare(Connection c, String sql, int timeout,
            String... args) throws SQLException {
        var p = c.prepareStatement(sql);
        try {
            p.setQueryTimeout(timeout);
            for (int i = 0; i < args.length; i++) {
                p.setString(i + 1, args[i]);
            }
            return p;
        } catch (SQLException failure) {
            p.close();
            throw failure;
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid Oracle column index evidence");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLException unsupported() {
        return new SQLException("Oracle index or constraint requires a verified structure strategy");
    }
}
