package cn.iocoder.yudao.framework.dbswitch.product.greenplum;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.Token;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** Greenplum 7 原生堆表和追加优化表定义，独立于 PostgreSQL 的目录契约。 */
public final class GreenplumNativeTables {

    private static final String FORMAT = "GREENPLUM_7_TABLE_V1";

    private static final int MAX_DOCUMENT = 1_048_576;

    private static final Set<String> STORAGE = Set.of("heap", "ao_row", "ao_column");

    private static final Set<String> OPTION_NAMES = Set.of("compresstype", "compresslevel", "blocksize", "checksum",
            "fillfactor");

    private static final Set<String> FUNCTIONS = Set.of("now", "current_timestamp", "current_date", "current_time",
            "abs", "coalesce", "nullif", "length", "char_length", "octet_length");

    public record Column(String name, String type, boolean nullable, String defaultSql, String comment,
                         Map<String, String> encoding) {

    }

    public record Constraint(String kind, List<String> columns, String expression) {

    }

    public record Definition(String storage, Map<String, String> options, String distribution,
                             List<String> distributionKeys, List<Column> columns, List<Constraint> constraints,
                             List<GreenplumIndexes.Index> indexes, String comment) {

        /**
         * 返回字段集合。
         */
        public List<String> fields() {
            return columns.stream().map(Column::name).toList();
        }
    }

    private record Captured(String cluster, long oid, long physicalFile, Definition definition) {

    }

    /**
     * 初始化 {@code GreenplumNativeTables}。
     */
    private GreenplumNativeTables() {
    }

    /**
     * 按Greenplum方言转义并引用标识符。
     */
    public static String quote(String name) {
        return cn.iocoder.yudao.framework.dbswitch.pgwriter.util.PostgreSqlUtils.quoteIdentifier(name);
    }

    /**
     * 返回表。
     */
    public static String table(String schema, String table) {
        return quote(schema) + '.' + quote(table);
    }

    /**
     * 返回字段集合。
     */
    public static String fields(List<String> fields) {
        if (fields == null || fields.isEmpty() || new HashSet<>(fields).size() != fields.size()) {
            throw invalid();
        }
        return fields.stream().map(GreenplumNativeTables::quote).collect(Collectors.joining(","));
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    public static NativeTableDefinition describe(DataSource source, String schema, String table, int timeout) {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "LOCK TABLE " + table(schema, table) + " IN ACCESS SHARE MODE", timeout);
                Captured captured = read(connection, schema, table, timeout);
                if (captured == null) {
                    throw invalid();
                }
                var document = JSONUtil.createObj().set("cluster", captured.cluster())
                        .set("oid", Long.toString(captured.oid()))
                        .set("definition", JSONUtil.parseObj(captured.definition()));
                String encoded = document.toString();
                if (encoded.length() > MAX_DOCUMENT) {
                    throw invalid();
                }
                connection.commit();
                return new NativeTableDefinition(ProductTypeEnum.GREENPLUM, FORMAT, encoded,
                        captured.definition().fields());
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        } catch (Exception error) {
            throw new IllegalStateException("Greenplum native table capture failed");
        }
    }

    /**
     * 返回计划。
     */
    public static NativeTargetPlan plan(DataSource target, NativeTableDefinition source, String schema, String name,
            Map<String, String> mapping, boolean replace, int timeout) {
        table(schema, name);
        Definition original = decode(source);
        var envelope = JSONUtil.parseObj(source.document());
        String sourceCluster = envelope.getStr("cluster");
        long sourceOid = envelope.getLong("oid", 0L);
        if (!mapping.keySet().equals(new HashSet<>(source.fields()))
                || new HashSet<>(mapping.values()).size() != mapping.size()) {
            throw invalid();
        }
        Definition desired = remap(original, mapping);
        String create = createSql(desired, schema, name);
        Captured before;
        try (var connection = target.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Captured observed = read(connection, schema, name, timeout);
                if (observed != null) {
                    execute(connection, "LOCK TABLE " + table(schema, name) + " IN ACCESS SHARE MODE", timeout);
                    observed = read(connection, schema, name, timeout);
                    if (observed == null) {
                        throw invalid();
                    }
                }
                GreenplumIndexes.validateTarget(connection, desired.indexes(), timeout);
                GreenplumIndexes.assertNamesAvailable(connection, desired.indexes(), schema, name,
                        observed == null ? 0 : observed.oid(), timeout);
                before = observed;
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        } catch (Exception error) {
            throw new IllegalStateException("Greenplum target structure preflight failed");
        }
        if (before != null && before.cluster().equals(sourceCluster) && before.oid() == sourceOid) {
            throw new IllegalArgumentException("Greenplum source and target resolve to the same physical table");
        }
        if (before != null && !replace && !equivalent(desired, before.definition())) {
            throw new IllegalArgumentException(
                    "Greenplum existing target structure differs from the frozen source definition");
        }
        String digest = DigestUtil.sha256Hex(
                source.document() + '\0' + schema + '\0' + name + '\0' + JSONUtil.toJsonStr(desired) + '\0' + replace);
        return new NativeTargetPlan() {
            private final AtomicBoolean used = new AtomicBoolean();

            /**
             * 返回摘要。
             */
            @Override
            public String digest() {
                return digest;
            }

            /**
             * 说明当前目标计划是否会替换已有对象。
             */
            @Override
            public boolean replacesExisting() {
                return replace && before != null;
            }

            /**
             * 在当前数据库连接上执行给定 SQL 语句。
             */
            @Override
            public void execute() {
                if (!used.compareAndSet(false, true)) {
                    throw new IllegalStateException("Greenplum native plan is single use");
                }
                JdbcBatchExecutor.execute(target, context -> {
                    Connection connection = context.connection();
                    GreenplumNativeTables.execute(connection, "SET LOCAL standard_conforming_strings=on", timeout);
                    GreenplumNativeTables.execute(connection, "SET LOCAL search_path=pg_catalog", timeout);
                    if (before != null) {
                        GreenplumNativeTables.execute(connection,
                                "LOCK TABLE " + table(schema, name) + " IN ACCESS EXCLUSIVE MODE", timeout);
                    }
                    Captured current = read(connection, schema, name, timeout);
                    if (!Objects.equals(before, current)) {
                        throw new IllegalStateException(
                                "Greenplum target identity or structure changed after preflight");
                    }
                    if (current != null && !replace) {
                        return null;
                    }
                    GreenplumIndexes.validateTarget(connection, desired.indexes(), timeout);
                    GreenplumIndexes.assertNamesAvailable(connection, desired.indexes(), schema, name,
                            current == null ? 0 : current.oid(), timeout);
                    context.markWriteAttempted();
                    if (current != null) {
                        GreenplumNativeTables.execute(connection, "DROP TABLE " + table(schema, name),
                                timeout); // 不使用 CASCADE：存在依赖对象时中止事务
                    }
                    GreenplumNativeTables.execute(connection, create, timeout);
                    comments(connection, desired, schema, name, timeout);
                    for (String indexSql : GreenplumIndexes.createSql(desired.indexes(), schema, name)) {
                        GreenplumNativeTables.execute(connection, indexSql, timeout);
                    }
                    Captured after = read(connection, schema, name, timeout);
                    if (after == null || !equivalent(desired, after.definition())) {
                        throw new IllegalStateException("Greenplum created structure verification failed");
                    }
                    return null;
                });
            }
        };
    }

    /** 同一产品渲染器也用于元数据 DDL 预览；实际执行仍使用上方原生计划。 */
    public static String ddl(Connection connection, String schema, String name, int timeout) {
        try {
            var captured = read(connection, schema, name, timeout);
            if (captured == null) {
                throw invalid();
            }
            var statements = new ArrayList<String>();
            statements.add(createSql(captured.definition(), schema, name));
            statements.addAll(GreenplumIndexes.createSql(captured.definition().indexes(), schema, name));
            return String.join(";\n", statements);
        } catch (SQLException error) {
            throw new IllegalStateException("Greenplum native DDL capture failed");
        }
    }

    /**
     * 读取Greenplum原生目录并构建表结构元数据。
     */
    private static Captured read(Connection connection, String schema, String name, int timeout) throws SQLException {
        String cluster;
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery(
                    "SELECT version(),current_database(),system_identifier::text FROM pg_catalog.pg_control_system()")) {
                if (!rows.next() || !rows.getString(1).contains("Greenplum Database 7.")) {
                    throw invalid();
                }
                cluster = DigestUtil.sha256Hex(rows.getString(2) + '\0' + rows.getString(3));
            }
        }
        long oid, physicalFile;
        String storage, distribution, comment;
        Map<String, String> options;
        List<Integer> dist;
        String query = "SELECT c.oid,a.amname,c.reloptions,p.policytype,p.distkey::text,obj_description(c.oid,'pg_class'),"
                + "c.relkind='r' AND c.relpersistence='p' AND c.reltablespace=0 AND NOT c.relispartition AND NOT c.relhassubclass AND NOT c.relhasrules AND NOT c.relrowsecurity "
                + "AND c.relpartbound IS NULL AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_inherits i WHERE i.inhrelid=c.oid OR i.inhparent=c.oid) "
                + "AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_trigger t WHERE t.tgrelid=c.oid AND NOT t.tgisinternal) "
                + "AND NOT EXISTS(SELECT 1 FROM pg_catalog.pg_policy x WHERE x.polrelid=c.oid),"
                + "NOT EXISTS(SELECT 1 FROM unnest(p.distclass) v(opc) JOIN pg_catalog.pg_opclass o ON o.oid=v.opc WHERE NOT o.opcdefault),c.relfilenode "
                + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace JOIN pg_catalog.pg_am a ON a.oid=c.relam "
                + "JOIN pg_catalog.gp_distribution_policy p ON p.localoid=c.oid WHERE n.nspname=? AND c.relname=?";
        try (var statement = connection.prepareStatement(query)) {
            statement.setQueryTimeout(timeout);
            statement.setString(1, schema);
            statement.setString(2, name);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    // 没有分布策略的视图或外部表不能被误判为对象不存在。
                    try (var exists = connection.prepareStatement(
                            "SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?")) {
                        exists.setQueryTimeout(timeout);
                        exists.setString(1, schema);
                        exists.setString(2, name);
                        try (var found = exists.executeQuery()) {
                            if (found.next()) {
                                throw invalid();
                            }
                        }
                    }
                    return null;
                }
                oid = rows.getLong(1);
                storage = rows.getString(2);
                options = options(rows.getArray(3));
                distribution = rows.getString(4);
                dist = ordinals(rows.getString(5));
                comment = rows.getString(6);
                physicalFile = rows.getLong(9);
                if (!rows.getBoolean(7) || !rows.getBoolean(8) || physicalFile < 1 || rows.next()) {
                    throw invalid();
                }
            }
        }
        var columns = new ArrayList<Column>();
        var byOrdinal = new HashMap<Integer, String>();
        query = "SELECT a.attnum,a.attname,pg_catalog.format_type(a.atttypid,a.atttypmod),a.attnotnull,pg_catalog.pg_get_expr(d.adbin,d.adrelid),"
                + "pg_catalog.col_description(a.attrelid,a.attnum),a.attidentity,a.attgenerated,n.nspname,a.attcollation=t.typcollation,e.attoptions "
                + "FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid=a.atttypid JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace "
                + "LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum "
                + "LEFT JOIN pg_catalog.pg_attribute_encoding e ON e.attrelid=a.attrelid AND e.attnum=a.attnum "
                + "WHERE a.attrelid=? AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum";
        try (var statement = connection.prepareStatement(query)) {
            statement.setQueryTimeout(timeout);
            statement.setLong(1, oid);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (columns.size() >= 1600 || !rows.getString(7).isEmpty() || !rows.getString(8).isEmpty()
                            || !"pg_catalog".equals(rows.getString(9)) || !rows.getBoolean(10)) {
                        throw invalid();
                    }
                    String column = rows.getString(2);
                    byOrdinal.put(rows.getInt(1), column);
                    columns.add(new Column(column, rows.getString(3), !rows.getBoolean(4), rows.getString(5),
                            rows.getString(6), options(rows.getArray(11))));
                }
            }
        }
        var constraints = new ArrayList<Constraint>();
        try (var statement = connection.prepareStatement(
                "SELECT contype,conkey,pg_catalog.pg_get_expr(conbin,conrelid),convalidated,condeferrable,condeferred FROM pg_catalog.pg_constraint WHERE conrelid=? ORDER BY contype,oid")) {
            statement.setQueryTimeout(timeout);
            statement.setLong(1, oid);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (constraints.size() >= 256 || !rows.getBoolean(4) || rows.getBoolean(5) || rows.getBoolean(6)) {
                        throw invalid();
                    }
                    String kind = rows.getString(1);
                    var key = new ArrayList<String>();
                    Array array = rows.getArray(2);
                    if (array != null) {
                        try {
                            for (Object ordinal : (Object[]) array.getArray()) {
                                key.add(Objects.requireNonNull(byOrdinal.get(((Number) ordinal).intValue())));
                            }
                        } finally {
                            array.free();
                        }
                    }
                    constraints.add(new Constraint(kind, List.copyOf(key), rows.getString(3)));
                }
            }
        }
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class x ON x.oid=i.indexrelid WHERE i.indrelid=? AND EXISTS(SELECT 1 FROM pg_catalog.pg_constraint c WHERE c.conindid=i.indexrelid) AND ("
                        + "i.indnkeyatts<>i.indnatts OR i.indexprs IS NOT NULL OR i.indpred IS NOT NULL "
                        + "OR x.reltablespace<>0 OR x.reloptions IS NOT NULL OR EXISTS(SELECT 1 FROM unnest(i.indclass) v(opc) JOIN pg_catalog.pg_opclass o ON o.oid=v.opc WHERE NOT o.opcdefault) "
                        + "OR EXISTS(SELECT 1 FROM unnest(i.indoption) v(option) WHERE v.option<>0)) LIMIT 1")) {
            statement.setQueryTimeout(timeout);
            statement.setLong(1, oid);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw invalid();
                }
            }
        }
        var keys = dist.stream().map(byOrdinal::get).toList();
        var indexes = GreenplumIndexes.read(connection, oid, new HashSet<>(byOrdinal.values()), timeout);
        Definition definition = new Definition(storage, options, distribution, keys, List.copyOf(columns),
                List.copyOf(constraints), indexes, comment);
        validate(definition);
        return new Captured(cluster, oid, physicalFile, definition);
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
            for (Object option : (Object[]) array.getArray()) {
                String[] parts = option.toString().split("=", 2);
                if (parts.length != 2 || result.put(parts[0], parts[1]) != null) {
                    throw invalid();
                }
            }
            return Collections.unmodifiableMap(result);
        } finally {
            array.free();
        }
    }

    /**
     * 返回当前列序号集合。
     */
    private static List<Integer> ordinals(String text) {
        return text == null || text.isBlank() ? List.of() : Arrays.stream(text.trim().split(" +")).map(Integer::valueOf)
                .toList();
    }

    /**
     * 检查 Greenplum 原生表定义及其列、分布和存储属性满足当前契约。
     */
    private static void validate(Definition definition) {
        if (!STORAGE.contains(definition.storage()) || !Set.of("p", "r").contains(definition.distribution())
                || definition.columns().isEmpty() || definition.columns().size() > 1600) {
            throw invalid();
        }
        var names = new HashSet<>(definition.fields());
        if (names.size() != definition.columns().size()) {
            throw invalid();
        }
        definition.fields().forEach(GreenplumNativeTables::quote);
        optionSql(definition.options());
        for (Column column : definition.columns()) {
            if (!validType(column.type())) {
                throw invalid();
            }
            optionSql(column.encoding());
            if (!column.encoding().isEmpty() && !definition.storage().equals("ao_column")) {
                throw invalid();
            }
            if (column.defaultSql() != null) {
                expression(column.defaultSql(), Set.of());
            }
        }
        if (!names.containsAll(definition.distributionKeys()) || new HashSet<>(
                definition.distributionKeys()).size() != definition.distributionKeys().size()
                || definition.distribution().equals("r") && !definition.distributionKeys().isEmpty()) {
            throw invalid();
        }
        for (Constraint constraint : definition.constraints()) {
            if (!Set.of("p", "u", "c").contains(constraint.kind()) || !names.containsAll(constraint.columns())) {
                throw invalid();
            }
            if (constraint.kind().equals("c")) {
                expression(constraint.expression(), names);
            } else if (constraint.columns().isEmpty() || new HashSet<>(
                    constraint.columns()).size() != constraint.columns().size() || constraint.expression() != null) {
                throw invalid();
            }
        }
        if (definition.indexes().size() > 256) {
            throw invalid();
        }
        for (var index : definition.indexes()) {
            GreenplumIndexes.validate(index, names);
        }
    }

    /**
     * 判断当前类型声明是否属于已验证的原生类型集合。
     */
    private static boolean validType(String type) {
        return type != null && type.matches(
                "(?:smallint|integer|bigint|boolean|real|double precision|text|bytea|date|numeric(?:\\([0-9]{1,4}(?:,[0-9]{1,4})?\\))?|character(?: varying)?(?:\\([0-9]{1,7}\\))?|(?:timestamp|time)(?:\\([0-6]\\))? (?:with|without) time zone)(?:\\[\\])?");
    }

    /**
     * 返回表达式。
     */
    private static String expression(String sql, Set<String> columns) {
        if (sql == null || sql.length() > 65_536) {
            throw invalid();
        }
        try {
            var parser = SQLParserUtils.createExprParser(sql, DbType.postgresql);
            var expression = parser.expr();
            if (parser.getLexer().token() != Token.EOF) {
                throw invalid();
            }
            expression.accept(new SQLASTVisitorAdapter() {
                /**
                 * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
                 */
                @Override
                public void preVisit(SQLObject object) {
                    if (object instanceof SQLMethodInvokeExpr method && (method.getOwner() != null || !FUNCTIONS.contains(
                            method.getMethodName().toLowerCase(Locale.ROOT)))) {
                        throw invalid();
                    }
                    if (object instanceof SQLIdentifierExpr identifier && !columns.contains(
                            identifier.getName().replace("\"\"", "\"").replaceAll("^\"|\"$", ""))) {
                        throw invalid();
                    }
                    if (object instanceof SQLPropertyExpr || object instanceof SQLQueryExpr || object instanceof SQLVariantRefExpr) {
                        throw invalid();
                    }
                    if (object instanceof SQLCastExpr cast && !validType(
                            cast.getDataType().toString().toLowerCase(Locale.ROOT))) {
                        throw invalid();
                    }
                }
            });
            return sql;
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    /**
     * 按当前列映射替换字段或关系引用。
     */
    private static Definition remap(Definition source, Map<String, String> mapping) {
        boolean renamed = source.fields().stream().anyMatch(field -> !field.equals(mapping.get(field)));
        if (renamed && source.constraints().stream().anyMatch(c -> c.kind().equals("c"))) {
            throw new IllegalArgumentException("Greenplum CHECK column rename needs a verified expression rewrite");
        }
        var columns = source.columns().stream()
                .map(c -> new Column(mapping.get(c.name()), c.type(), c.nullable(), c.defaultSql(), c.comment(),
                        c.encoding())).toList();
        var constraints = source.constraints().stream()
                .map(c -> new Constraint(c.kind(), c.columns().stream().map(mapping::get).toList(), c.expression()))
                .toList();
        var definition = new Definition(source.storage(), source.options(), source.distribution(),
                source.distributionKeys().stream().map(mapping::get).toList(), columns, constraints,
                GreenplumIndexes.remap(source.indexes(), mapping), source.comment());
        validate(definition);
        return definition;
    }

    /** 解码冻结且有界的原生模型，同时用于 DDL 规划和写入器准入。 */
    public static Definition decode(NativeTableDefinition source) {
        try {
            if (source == null || source.product() != ProductTypeEnum.GREENPLUM || !FORMAT.equals(source.format())
                    || source.document().length() > MAX_DOCUMENT) {
                throw invalid();
            }
            var envelope = JSONUtil.parseObj(source.document());
            String cluster = envelope.getStr("cluster");
            if (cluster == null || !cluster.matches("[0-9a-f]{64}") || envelope.getLong("oid", 0L) < 1) {
                throw invalid();
            }
            Definition definition = decode(envelope.getJSONObject("definition").toString());
            if (!source.fields().equals(definition.fields())) {
                throw invalid();
            }
            return definition;
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    /**
     * 从编码数据恢复定义。
     */
    private static Definition decode(String document) {
        try {
            var json = JSONUtil.parseObj(document);
            var columns = new ArrayList<Column>();
            for (var item : json.getJSONArray("columns")) {
                var c = JSONUtil.parseObj(item);
                columns.add(
                        new Column(c.getStr("name"), c.getStr("type"), c.getBool("nullable"), c.getStr("defaultSql"),
                                c.getStr("comment"), stringMap(c.getJSONObject("encoding"))));
            }
            var constraints = new ArrayList<Constraint>();
            for (var item : json.getJSONArray("constraints")) {
                var c = JSONUtil.parseObj(item);
                constraints.add(new Constraint(c.getStr("kind"), c.getJSONArray("columns").toList(String.class),
                        c.getStr("expression")));
            }
            var indexes = new ArrayList<GreenplumIndexes.Index>();
            for (var item : json.getJSONArray("indexes")) {
                var index = JSONUtil.parseObj(item);
                var keys = new ArrayList<GreenplumIndexes.Key>();
                for (var keyItem : index.getJSONArray("keys")) {
                    var key = JSONUtil.parseObj(keyItem);
                    var collation = key.getJSONObject("collation");
                    keys.add(new GreenplumIndexes.Key(key.getStr("column"), key.getStr("expression"),
                            key.getStr("operatorClass"), collation == null ? null :
                            new GreenplumIndexes.Collation(collation.getStr("name"), collation.getStr("provider"),
                                    collation.getStr("locale"), collation.getStr("ctype")), key.getBool("descending"),
                            key.getBool("nullsFirst")));
                }
                indexes.add(new GreenplumIndexes.Index(index.getStr("sourceName"), index.getStr("sourceDefinitionHash"),
                        index.getBool("unique"), keys, index.getJSONArray("included").toList(String.class),
                        index.getStr("predicate"), stringMap(index.getJSONObject("options")), index.getStr("comment")));
            }
            var result = new Definition(json.getStr("storage"), stringMap(json.getJSONObject("options")),
                    json.getStr("distribution"), json.getJSONArray("distributionKeys").toList(String.class),
                    List.copyOf(columns), List.copyOf(constraints), List.copyOf(indexes), json.getStr("comment"));
            validate(result);
            return result;
        } catch (RuntimeException error) {
            throw invalid();
        }
    }

    /**
     * 将属性值转换为按键排序的不可变字符串映射。
     */
    private static Map<String, String> stringMap(cn.hutool.json.JSONObject json) {
        var result = new TreeMap<String, String>();
        json.forEach((key, value) -> result.put(key, String.valueOf(value)));
        return Collections.unmodifiableMap(result);
    }

    /**
     * 按当前结构或类型契约比较两个定义是否等价。
     */
    private static boolean equivalent(Definition left, Definition right) {
        return left.storage().equals(right.storage()) && left.options().equals(right.options()) && left.distribution()
                .equals(right.distribution())
                && left.distributionKeys().equals(right.distributionKeys()) && left.columns()
                .equals(right.columns()) && left.constraints().equals(right.constraints())
                && Objects.equals(left.comment(), right.comment())
                && left.indexes().stream().map(GreenplumIndexes::semantic).sorted().toList()
                .equals(right.indexes().stream().map(GreenplumIndexes::semantic).sorted().toList());
    }

    /**
     * 校验对象属性名称和值后生成选项 SQL。
     */
    private static String optionSql(Map<String, String> options) {
        var values = new ArrayList<String>();
        for (var item : new TreeMap<>(options).entrySet()) {
            if (!OPTION_NAMES.contains(item.getKey()) || !item.getValue().matches("[A-Za-z0-9_]+")) {
                throw invalid();
            }
            boolean valid = switch (item.getKey()) {
                case "compresstype" -> Set.of("none", "zlib", "zstd", "rle_type").contains(item.getValue());
                case "checksum" -> Set.of("true", "false").contains(item.getValue());
                case "compresslevel" -> boundedNumber(item.getValue(), 0, 19, 1);
                case "blocksize" -> boundedNumber(item.getValue(), 8192, 2097152, 8192);
                case "fillfactor" -> boundedNumber(item.getValue(), 10, 100, 1);
                default -> false;
            };
            if (!valid) {
                throw invalid();
            }
            values.add(item.getKey() + "=" + item.getValue());
        }
        return String.join(",", values);
    }

    /**
     * 检查当前文本表示的整数是否处于允许范围。
     */
    private static boolean boundedNumber(String text, int min, int max, int multiple) {
        if (!text.matches("[0-9]{1,7}")) {
            return false;
        }
        int number = Integer.parseInt(text);
        return number >= min && number <= max && number % multiple == 0;
    }

    /**
     * 生成原生对象的创建 SQL。
     */
    private static String createSql(Definition definition, String schema, String name) {
        validate(definition);
        var parts = new ArrayList<String>();
        for (Column column : definition.columns()) {
            String sql = quote(column.name()) + ' ' + column.type();
            if (column.defaultSql() != null) {
                sql += " DEFAULT " + expression(column.defaultSql(), Set.of());
            }
            if (!column.nullable()) {
                sql += " NOT NULL";
            }
            if (!column.encoding().isEmpty()) {
                sql += " ENCODING (" + optionSql(column.encoding()) + ")";
            }
            parts.add(sql);
        }
        for (Constraint constraint : definition.constraints()) {
            parts.add(switch (constraint.kind()) {
                case "p" -> "PRIMARY KEY (" + fields(constraint.columns()) + ")";
                case "u" -> "UNIQUE (" + fields(constraint.columns()) + ")";
                case "c" -> "CHECK (" + expression(constraint.expression(), new HashSet<>(definition.fields())) + ")";
                default -> throw invalid();
            });
        }
        String sql = "CREATE TABLE " + table(schema, name) + " (" + String.join(",",
                parts) + ") USING " + definition.storage();
        if (!definition.options().isEmpty()) {
            sql += " WITH (" + optionSql(definition.options()) + ")";
        }
        return sql + (definition.distribution().equals("r") ? " DISTRIBUTED REPLICATED" : definition.distributionKeys()
                .isEmpty() ? " DISTRIBUTED RANDOMLY" : " DISTRIBUTED BY (" + fields(
                definition.distributionKeys()) + ")");
    }

    /**
     * 返回注释集合。
     */
    private static void comments(Connection connection, Definition definition, String schema, String name,
            int timeout) throws SQLException {
        if (definition.comment() != null) {
            execute(connection, "COMMENT ON TABLE " + table(schema, name) + " IS " + literal(definition.comment()),
                    timeout);
        }
        for (Column column : definition.columns()) {
            if (column.comment() != null) {
                execute(connection,
                        "COMMENT ON COLUMN " + table(schema, name) + '.' + quote(column.name()) + " IS " + literal(
                                column.comment()), timeout);
            }
        }
    }

    /**
     * 返回当前字面量。
     */
    public static String literal(String value) {
        if (value.indexOf('\0') >= 0) {
            throw invalid();
        }
        return "E'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    private static void execute(Connection connection, String sql, int timeout) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.execute(sql);
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Greenplum native structure contains unsupported or incomplete semantics");
    }
}
