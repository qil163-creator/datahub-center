package cn.iocoder.yudao.framework.dbswitch.product.greenplum;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.Token;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/** GP 7 的 btree 目录；原始 pg_get_indexdef 文本仅用于计算证据哈希，绝不改写或执行。 */
public final class GreenplumIndexes {

    public record Collation(String name, String provider, String locale, String ctype) {

    }

    public record Key(String column, String expression, String operatorClass, Collation collation, boolean descending,
                      boolean nullsFirst) {

    }

    public record Index(String sourceName, String sourceDefinitionHash, boolean unique, List<Key> keys,
                        List<String> included,
                        String predicate, Map<String, String> options, String comment) {

        /**
         * 初始化 {@code Index}。
         */
        public Index {
            keys = List.copyOf(keys);
            included = List.copyOf(included);
            options = Collections.unmodifiableMap(new TreeMap<>(options));
        }
    }

    private static final Set<String> OPCLASSES = Set.of("int2_ops", "int4_ops", "int8_ops", "bool_ops", "float4_ops",
            "float8_ops", "numeric_ops",
            "text_ops", "varchar_ops", "bpchar_ops", "text_pattern_ops", "varchar_pattern_ops", "bpchar_pattern_ops",
            "bytea_ops",
            "date_ops", "time_ops", "timestamp_ops", "timestamptz_ops", "timetz_ops", "array_ops");

    private static final Map<String, String> CASTS = Map.ofEntries(Map.entry("numeric", "numeric"),
            Map.entry("decimal", "numeric"),
            Map.entry("smallint", "int2"), Map.entry("integer", "int4"), Map.entry("bigint", "int8"),
            Map.entry("real", "float4"),
            Map.entry("double precision", "float8"), Map.entry("text", "text"),
            Map.entry("character varying", "varchar"), Map.entry("varchar", "varchar"),
            Map.entry("character", "bpchar"), Map.entry("char", "bpchar"), Map.entry("boolean", "bool"),
            Map.entry("bytea", "bytea"),
            Map.entry("date", "date"), Map.entry("timestamp", "timestamp"),
            Map.entry("timestamp without time zone", "timestamp"),
            Map.entry("timestamp with time zone", "timestamptz"), Map.entry("time", "time"),
            Map.entry("time without time zone", "time"),
            Map.entry("time with time zone", "timetz"));

    private static final Map<String, Map<String, String>> FUNCTIONS = Map.of(
            "lower", Map.of("text", "lower"), "upper", Map.of("text", "upper"),
            "abs", Map.of("smallint", "int2abs", "integer", "int4abs", "bigint", "int8abs", "real", "float4abs",
                    "double precision", "float8abs", "numeric", "numeric_abs"));

    /**
     * 初始化 {@code GreenplumIndexes}。
     */
    private GreenplumIndexes() {
    }

    /**
     * 读取当前输入中的数据并按约定结构返回。
     */
    public static List<Index> read(Connection connection, long tableOid, Set<String> fields,
            int timeout) throws SQLException {
        var result = new ArrayList<Index>();
        String sql = "SELECT i.indexrelid,c.relname,i.indisunique,i.indnkeyatts,i.indnatts,i.indkey::text,i.indclass::text,i.indcollation::text,i.indoption::text,"
                + "pg_catalog.pg_get_expr(i.indexprs,i.indrelid),pg_catalog.pg_get_expr(i.indpred,i.indrelid),c.reloptions,pg_catalog.obj_description(c.oid,'pg_class'),"
                + "pg_catalog.pg_get_indexdef(i.indexrelid),a.amname,i.indisvalid AND i.indisready AND i.indislive AND NOT i.indisclustered AND NOT i.indisreplident AND c.reltablespace=0 "
                + "FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c ON c.oid=i.indexrelid JOIN pg_catalog.pg_am a ON a.oid=c.relam "
                + "WHERE i.indrelid=? AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_constraint x WHERE x.conindid=i.indexrelid) ORDER BY c.relname";
        var attributes = new HashMap<Integer, String>();
        try (var statement = connection.prepareStatement(
                "SELECT attnum,attname FROM pg_catalog.pg_attribute WHERE attrelid=? AND attnum>0 AND NOT attisdropped")) {
            statement.setQueryTimeout(timeout);
            statement.setLong(1, tableOid);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    attributes.put(rows.getInt(1), rows.getString(2));
                }
            }
        }
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeout);
            statement.setLong(1, tableOid);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() >= 256 || !"btree".equals(rows.getString(15)) || !rows.getBoolean(16)) {
                        throw unsupported();
                    }
                    long indexOid = rows.getLong(1);
                    int count = rows.getInt(4), total = rows.getInt(5);
                    if (count < 1 || total < count || total > 32) {
                        throw unsupported();
                    }
                    var ordinals = vector(rows.getString(6));
                    var classes = vector(rows.getString(7));
                    var collations = vector(rows.getString(8));
                    var orders = vector(rows.getString(9));
                    if (ordinals.size() != total || classes.size() != count || collations.size() != count || orders.size() != count) {
                        throw unsupported();
                    }
                    var expressions = expressions(rows.getString(10));
                    int expressionIndex = 0;
                    rejectUserFunctionDependencies(connection, indexOid, timeout);
                    var keys = new ArrayList<Key>();
                    var included = new ArrayList<String>();
                    for (int index = 0; index < total; index++) {
                        long ordinal = ordinals.get(index);
                        String column = ordinal > 0 ? attributes.get(Math.toIntExact(ordinal)) : null;
                        if (index >= count) {
                            if (column == null) {
                                throw unsupported();
                            }
                            included.add(column);
                            continue;
                        }
                        String expression = null;
                        if (ordinal == 0) {
                            if (expressionIndex >= expressions.size()) {
                                throw unsupported();
                            }
                            expression = normalize(expressions.get(expressionIndex++), identity(fields));
                        } else if (column == null) {
                            throw unsupported();
                        }
                        long order = orders.get(index);
                        if (order < 0 || order > 3) {
                            throw unsupported();
                        }
                        keys.add(new Key(column, expression, operatorClass(connection, classes.get(index), timeout),
                                collation(connection, collations.get(index), timeout), (order & 1) != 0,
                                (order & 2) != 0));
                    }
                    if (expressionIndex != expressions.size()) {
                        throw unsupported();
                    }
                    String predicate = rows.getString(11);
                    if (predicate != null) {
                        predicate = normalize(predicate, identity(fields));
                    }
                    String definition = rows.getString(14);
                    if (definition == null || definition.length() > 65_536) {
                        throw unsupported();
                    }
                    Index item = new Index(rows.getString(2), DigestUtil.sha256Hex(definition), rows.getBoolean(3),
                            keys, included, predicate, options(rows.getArray(12)), rows.getString(13));
                    validate(item, fields);
                    validateTarget(connection, List.of(item), timeout);
                    result.add(item);
                }
            }
        }
        result.sort(Comparator.comparing(GreenplumIndexes::semantic).thenComparing(Index::sourceName));
        return List.copyOf(result);
    }

    /**
     * 按当前列映射替换字段或关系引用。
     */
    public static List<Index> remap(List<Index> indexes, Map<String, String> fields) {
        var result = new ArrayList<Index>();
        for (Index index : indexes) {
            validate(index, fields.keySet());
            var keys = index.keys().stream().map(key -> new Key(key.column() == null ? null : fields.get(key.column()),
                    key.expression() == null ? null : normalize(key.expression(), fields), key.operatorClass(),
                    key.collation(), key.descending(), key.nullsFirst())).toList();
            var item = new Index(index.sourceName(), index.sourceDefinitionHash(), index.unique(), keys,
                    index.included().stream().map(fields::get).toList(),
                    index.predicate() == null ? null : normalize(index.predicate(), fields), index.options(),
                    index.comment());
            validate(item, new HashSet<>(fields.values()));
            result.add(item);
        }
        result.sort(Comparator.comparing(GreenplumIndexes::semantic).thenComparing(Index::sourceName));
        return List.copyOf(result);
    }

    /**
     * 校验目标端。
     */
    public static void validateTarget(Connection connection, List<Index> indexes, int timeout) throws SQLException {
        var methods = new HashSet<String>();
        for (Index index : indexes) {
            for (Key key : index.keys()) {
                if (!OPCLASSES.contains(key.operatorClass())) {
                    throw unsupported();
                }
                try (var statement = connection.prepareStatement(
                        "SELECT o.oid FROM pg_catalog.pg_opclass o JOIN pg_catalog.pg_namespace n ON n.oid=o.opcnamespace JOIN pg_catalog.pg_am a ON a.oid=o.opcmethod WHERE n.nspname='pg_catalog' AND a.amname='btree' AND o.opcname=?")) {
                    statement.setQueryTimeout(timeout);
                    statement.setString(1, key.operatorClass());
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next()) {
                            throw unsupported();
                        }
                        operatorClass(connection, rows.getLong(1), timeout);
                        if (rows.next()) {
                            throw unsupported();
                        }
                    }
                }
                if (key.collation() != null) {
                    try (var statement = connection.prepareStatement(
                            "SELECT c.oid FROM pg_catalog.pg_collation c JOIN pg_catalog.pg_namespace n ON n.oid=c.collnamespace WHERE n.nspname='pg_catalog' AND c.collname=? AND c.collencoding IN(-1,pg_char_to_encoding(current_setting('server_encoding')))")) {
                        statement.setQueryTimeout(timeout);
                        statement.setString(1, key.collation().name());
                        try (var rows = statement.executeQuery()) {
                            if (!rows.next() || !key.collation()
                                    .equals(collation(connection, rows.getLong(1), timeout)) || rows.next()) {
                                throw unsupported();
                            }
                        }
                    }
                }
                if (key.expression() != null) {
                    collectFunctions(key.expression(), methods);
                }
            }
            if (index.predicate() != null) {
                collectFunctions(index.predicate(), methods);
            }
        }
        for (String method : methods) {
            for (var signature : FUNCTIONS.get(method).entrySet()) {
                String sql = "SELECT p.provolatile='i' AND p.prokind='f' AND NOT p.prosecdef AND p.proisstrict AND p.proconfig IS NULL AND n.nspname='pg_catalog' AND l.lanname='internal',p.prosrc,"
                        + "EXISTS(SELECT 1 FROM pg_catalog.pg_depend d WHERE d.refclassid='pg_catalog.pg_proc'::regclass AND d.refobjid=p.oid AND d.deptype='p') "
                        + "FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace JOIN pg_catalog.pg_language l ON l.oid=p.prolang WHERE p.oid=CAST(? AS pg_catalog.regprocedure)";
                try (var statement = connection.prepareStatement(sql)) {
                    statement.setQueryTimeout(timeout);
                    statement.setString(1, "pg_catalog." + method + '(' + signature.getKey() + ')');
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next() || !rows.getBoolean(1) || !signature.getValue()
                                .equals(rows.getString(2)) || !rows.getBoolean(3) || rows.next()) {
                            throw unsupported();
                        }
                    }
                }
            }
        }
    }

    /**
     * 检查名称集合可用。
     */
    public static void assertNamesAvailable(Connection connection, List<Index> indexes, String schema, String table,
            long ownedTableOid, int timeout) throws SQLException {
        for (int index = 0; index < indexes.size(); index++) {
            try (var statement = connection.prepareStatement(
                    "SELECT i.indrelid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_catalog.pg_index i ON i.indexrelid=c.oid WHERE n.nspname=? AND c.relname=?")) {
                statement.setQueryTimeout(timeout);
                statement.setString(1, schema);
                statement.setString(2, targetName(indexes.get(index), schema, table, index));
                try (var rows = statement.executeQuery()) {
                    if (rows.next() && (ownedTableOid < 1 || rows.getLong(1) != ownedTableOid || rows.wasNull())) {
                        throw new IllegalStateException("Greenplum target index name belongs to another relation");
                    }
                }
            }
        }
    }

    /**
     * 生成原生对象的创建 SQL。
     */
    public static List<String> createSql(List<Index> indexes, String schema, String table) {
        var result = new ArrayList<String>();
        for (int index = 0; index < indexes.size(); index++) {
            Index item = indexes.get(index);
            var keys = new ArrayList<String>();
            for (Key key : item.keys()) {
                String sql = key.column() != null ? q(key.column()) : '(' + key.expression() + ')';
                if (key.collation() != null) {
                    sql += " COLLATE pg_catalog." + q(key.collation().name());
                }
                sql += " pg_catalog." + q(
                        key.operatorClass()) + (key.descending() ? " DESC" : " ASC") + (key.nullsFirst() ? " NULLS FIRST" : " NULLS LAST");
                keys.add(sql);
            }
            String name = targetName(item, schema, table, index);
            String sql = "CREATE " + (item.unique() ? "UNIQUE " : "") + "INDEX " + q(
                    name) + " ON " + GreenplumNativeTables.table(schema, table) + " USING btree (" + String.join(",",
                    keys) + ')';
            if (!item.included().isEmpty()) {
                sql += " INCLUDE (" + GreenplumNativeTables.fields(item.included()) + ')';
            }
            if (!item.options().isEmpty()) {
                sql += " WITH (fillfactor=" + item.options().get("fillfactor") + ')';
            }
            if (item.predicate() != null) {
                sql += " WHERE " + item.predicate();
            }
            result.add(sql);
            if (item.comment() != null) {
                result.add("COMMENT ON INDEX " + q(schema) + '.' + q(name) + " IS " + GreenplumNativeTables.literal(
                        item.comment()));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 读取或计算当前对象的语义描述。
     */
    public static String semantic(Index index) {
        return JSONUtil.createObj().set("unique", index.unique()).set("keys", index.keys())
                .set("included", index.included())
                .set("predicate", index.predicate()).set("options", index.options()).set("comment", index.comment())
                .toString();
    }

    /**
     * 按目标配置生成当前对象的名称。
     */
    public static String targetName(Index index, String schema, String table, int ordinal) {
        return "dbswix_" + DigestUtil.sha256Hex(schema + '\0' + table + '\0' + semantic(index) + '\0' + ordinal)
                .substring(0, 40);
    }

    /**
     * 校验索引名称、键列及运算符类属于当前支持的原生结构范围。
     */
    public static void validate(Index index, Set<String> fields) {
        q(index.sourceName());
        if (index.sourceDefinitionHash() == null || !index.sourceDefinitionHash()
                .matches("[0-9a-f]{64}") || index.keys().isEmpty() || index.keys().size() + index.included()
                .size() > 32) {
            throw unsupported();
        }
        for (Key key : index.keys()) {
            if ((key.column() == null) == (key.expression() == null) || !OPCLASSES.contains(key.operatorClass())) {
                throw unsupported();
            }
            if (key.column() != null && !fields.contains(key.column())) {
                throw unsupported();
            }
            if (key.expression() != null) {
                normalize(key.expression(), identity(fields));
            }
            if (key.collation() != null && !Set.of("default", "C", "POSIX").contains(key.collation().name())) {
                throw unsupported();
            }
        }
        if (!fields.containsAll(index.included()) || new HashSet<>(index.included()).size() != index.included()
                .size()) {
            throw unsupported();
        }
        if (index.predicate() != null) {
            normalize(index.predicate(), identity(fields));
        }
        if (!index.options().isEmpty() && (!index.options().keySet().equals(Set.of("fillfactor")) || !index.options()
                .get("fillfactor").matches("(?:[1-9][0-9]|100)"))) {
            throw unsupported();
        }
    }

    /**
     * 将SQL 文本整理为当前协议使用的规范形式。
     */
    public static String normalize(String sql, Map<String, String> fields) {
        if (sql == null || sql.length() > 65_536 || sql.indexOf('\\') >= 0) {
            throw unsupported();
        }
        try {
            var parser = SQLParserUtils.createExprParser(sql, DbType.postgresql);
            SQLExpr expression = parser.expr();
            if (parser.getLexer().token() != Token.EOF) {
                throw unsupported();
            }
            return normalize(expression, fields);
        } catch (RuntimeException error) {
            throw unsupported();
        }
    }

    /**
     * 将表达式整理为当前协议使用的规范形式。
     */
    private static String normalize(SQLExpr expression, Map<String, String> fields) {
        try {
            expression.accept(new SQLASTVisitorAdapter() {
                /**
                 * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
                 */
                @Override
                public void preVisit(SQLObject object) {
                    if (object instanceof SQLSelect || object instanceof SQLQueryExpr || object instanceof SQLVariantRefExpr || object instanceof SQLPropertyExpr || object instanceof SQLAggregateExpr) {
                        throw unsupported();
                    }
                    if (object instanceof SQLMethodInvokeExpr method) {
                        String name = method.getMethodName();
                        if (!FUNCTIONS.containsKey(name) || method.getArguments()
                                .size() != 1 || method.getOwner() != null && !"pg_catalog".equals(
                                method.getOwner().toString())) {
                            throw unsupported();
                        }
                        var owner = new SQLIdentifierExpr("pg_catalog");
                        owner.setParent(method);
                        method.setOwner(owner);
                    }
                    if (object instanceof SQLIdentifierExpr identifier) {
                        if (identifier.getParent() instanceof SQLMethodInvokeExpr method && method.getOwner() == identifier) {
                            return;
                        }
                        String name = identifier.getName();
                        // Druid 将 PostgreSQL 双引号转义保存为反斜杠加引号；解析前拒绝
                        // 含反斜杠的表达式输入，以保证此转换无歧义。
                        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                            name = name.substring(1, name.length() - 1).replace("\\\"", "\"");
                        } else {
                            name = name.toLowerCase(Locale.ROOT);
                        }
                        String mapped = fields.get(name);
                        if (mapped == null || mapped.indexOf('\\') >= 0) {
                            throw unsupported();
                        }
                        identifier.setName(q(mapped));
                    }
                    if (object instanceof SQLCastExpr cast) {
                        String name = cast.getDataType().getName().toLowerCase(Locale.ROOT);
                        if (name.startsWith("pg_catalog.")) {
                            name = name.substring(11);
                        }
                        String nativeName = CASTS.get(name);
                        if (nativeName == null && CASTS.containsValue(name)) {
                            nativeName = name;
                        }
                        if (nativeName == null) {
                            throw unsupported();
                        }
                        if (cast.getDataType().getArguments().stream().anyMatch(
                                value -> !(value instanceof SQLIntegerExpr integer) || integer.getNumber()
                                        .longValue() < 0 || integer.getNumber().longValue() > 1_000_000)) {
                            throw unsupported();
                        }
                        cast.getDataType().setName("pg_catalog." + nativeName);
                    }
                }
            });
            return SQLUtils.toSQLString(expression, DbType.postgresql);
        } catch (RuntimeException error) {
            throw unsupported();
        }
    }

    /**
     * 返回当前表达式集合。
     */
    private static List<SQLExpr> expressions(String sql) {
        if (sql == null) {
            return List.of();
        }
        if (sql.length() > 65_536 || sql.indexOf('\\') >= 0) {
            throw unsupported();
        }
        var parser = SQLParserUtils.createExprParser(sql, DbType.postgresql);
        var result = new ArrayList<SQLExpr>();
        while (true) {
            result.add(parser.expr());
            if (parser.getLexer().token() == Token.EOF) {
                break;
            }
            if (parser.getLexer().token() != Token.COMMA || result.size() > 32) {
                throw unsupported();
            }
            parser.getLexer().nextToken();
        }
        return result;
    }

    /**
     * 收集函数集合。
     */
    private static void collectFunctions(String sql, Set<String> methods) {
        SQLUtils.toSQLExpr(sql, DbType.postgresql).accept(new SQLASTVisitorAdapter() {
            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public boolean visit(SQLMethodInvokeExpr method) {
                if (!FUNCTIONS.containsKey(method.getMethodName())) {
                    throw unsupported();
                }
                methods.add(method.getMethodName());
                return true;
            }
        });
    }

    /**
     * 为给定字段名建立名称到同名字段的一一映射。
     */
    private static Map<String, String> identity(Set<String> fields) {
        return fields.stream().collect(Collectors.toMap(field -> field, field -> field));
    }

    /**
     * 返回当前向量。
     */
    private static List<Long> vector(String text) {
        return text == null || text.isBlank() ? List.of() : Arrays.stream(text.trim().split(" +")).map(Long::valueOf)
                .toList();
    }

    /**
     * 返回配置选项。
     */
    private static Map<String, String> options(Array array) throws SQLException {
        if (array == null) {
            return Map.of();
        }
        try {
            var result = new TreeMap<String, String>();
            for (Object value : (Object[]) array.getArray()) {
                String[] parts = value.toString().split("=", 2);
                if (parts.length != 2 || result.put(parts[0], parts[1]) != null) {
                    throw unsupported();
                }
            }
            return result;
        } finally {
            array.free();
        }
    }

    /**
     * 读取索引运算符类及其所属模式和访问方法。
     */
    private static String operatorClass(Connection connection, long oid, int timeout) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT o.opcname,n.nspname,a.amname,EXISTS(SELECT 1 FROM pg_catalog.pg_depend d WHERE d.refclassid='pg_catalog.pg_opclass'::regclass AND d.refobjid=o.oid AND d.deptype='p') FROM pg_catalog.pg_opclass o JOIN pg_catalog.pg_namespace n ON n.oid=o.opcnamespace JOIN pg_catalog.pg_am a ON a.oid=o.opcmethod WHERE o.oid=?")) {
            statement.setQueryTimeout(timeout);
            statement.setLong(1, oid);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw unsupported();
                }
                String name = rows.getString(1);
                if (!OPCLASSES.contains(name) || !"pg_catalog".equals(rows.getString(2)) || !"btree".equals(
                        rows.getString(3)) || !rows.getBoolean(4) || rows.next()) {
                    throw unsupported();
                }
                return name;
            }
        }
    }

    /**
     * 返回当前排序规则。
     */
    private static Collation collation(Connection connection, long oid, int timeout) throws SQLException {
        if (oid == 0) {
            return null;
        }
        try (var statement = connection.prepareStatement(
                "SELECT c.collname,n.nspname,c.collprovider,c.collisdeterministic,CASE WHEN c.collprovider='d' THEN d.datcollate ELSE c.collcollate END,CASE WHEN c.collprovider='d' THEN d.datctype ELSE c.collctype END FROM pg_catalog.pg_collation c JOIN pg_catalog.pg_namespace n ON n.oid=c.collnamespace CROSS JOIN pg_catalog.pg_database d WHERE c.oid=? AND d.datname=current_database()")) {
            statement.setQueryTimeout(timeout);
            statement.setLong(1, oid);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !Set.of("default", "C", "POSIX")
                        .contains(rows.getString(1)) || !"pg_catalog".equals(rows.getString(2)) || !rows.getBoolean(
                        4)) {
                    throw unsupported();
                }
                var result = new Collation(rows.getString(1), rows.getString(3), rows.getString(5), rows.getString(6));
                if (rows.next()) {
                    throw unsupported();
                }
                return result;
            }
        }
    }

    /**
     * 拒绝依赖用户自定义函数的索引或数据库对象。
     */
    private static void rejectUserFunctionDependencies(Connection connection, long oid,
            int timeout) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM pg_catalog.pg_depend d JOIN pg_catalog.pg_proc p ON p.oid=d.refobjid JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace WHERE d.classid='pg_catalog.pg_class'::regclass AND d.objid=? AND d.refclassid='pg_catalog.pg_proc'::regclass AND (n.nspname<>'pg_catalog' OR p.provolatile<>'i') LIMIT 1")) {
            statement.setQueryTimeout(timeout);
            statement.setLong(1, oid);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw unsupported();
                }
            }
        }
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM pg_catalog.pg_depend d JOIN pg_catalog.pg_operator o ON o.oid=d.refobjid JOIN pg_catalog.pg_namespace n ON n.oid=o.oprnamespace WHERE d.classid='pg_catalog.pg_class'::regclass AND d.objid=? AND d.refclassid='pg_catalog.pg_operator'::regclass AND (n.nspname<>'pg_catalog' OR NOT EXISTS(SELECT 1 FROM pg_catalog.pg_depend p WHERE p.refclassid='pg_catalog.pg_operator'::regclass AND p.refobjid=o.oid AND p.deptype='p')) LIMIT 1")) {
            statement.setQueryTimeout(timeout);
            statement.setLong(1, oid);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw unsupported();
                }
            }
        }
    }

    /**
     * 按Greenplum方言转义并引用标识符。
     */
    private static String q(String name) {
        return GreenplumNativeTables.quote(name);
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException unsupported() {
        return new IllegalArgumentException("Greenplum index semantics are unsupported or unverifiable");
    }
}
