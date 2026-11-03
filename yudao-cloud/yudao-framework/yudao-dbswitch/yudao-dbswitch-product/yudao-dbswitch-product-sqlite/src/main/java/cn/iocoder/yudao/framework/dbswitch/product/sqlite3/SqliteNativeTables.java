package cn.iocoder.yudao.framework.dbswitch.product.sqlite3;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.JdbcBatchExecutor;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.sqlite.Sqlite3WriterImpl;

import javax.sql.DataSource;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** SQLite 原生模式编译，绝不替代共享事务或迁移执行器。 */
public final class SqliteNativeTables {

    private static final String FORMAT = "SQLITE_ORDINARY_TABLE_V1";

    public record Index(String name, String ddl) {

    }

    public record Column(String name, int generated) {

    }

    public record Definition(String name, String ddl, List<Index> indexes, List<Column> columns, Long sequence) {

        /**
         * 返回字段集合。
         */
        List<String> fields() {
            return columns.stream().filter(c -> c.generated == 0).map(Column::name).toList();
        }
    }

    private record Captured(String file, long schemaVersion, long root, Definition definition) {

    }

    /**
     * 初始化 {@code SqliteNativeTables}。
     */
    private SqliteNativeTables() {
    }

    /**
     * 按SQLite方言转义并引用标识符。
     */
    public static String q(String value) {
        if (value == null || value.isEmpty() || value.length() > 512 || value.chars()
                .anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /**
     * 返回模式。
     */
    private static String schema(String value) {
        return value == null || value.isBlank() ? "main" : value;
    }

    /**
     * 生成当前对象的限定引用名。
     */
    private static String ref(String schema, String table) {
        return q(schema) + '.' + q(table);
    }

    /**
     * 配置写入。
     */
    public static void configureWrite(Connection c) throws SQLException {
        if (!c.getAutoCommit() || !"SQLite".equals(c.getMetaData().getDatabaseProductName())) {
            throw invalid();
        }
        execute(c, "PRAGMA foreign_keys=ON", 10);
        if (scalar(c, "PRAGMA foreign_keys", 10) != 1) {
            throw invalid();
        }
    }

    /**
     * 根据当前输入构建并返回 {@code DatabaseSqliteImpl}。
     */
    public static List<ColumnDescription> writableColumns(DataSource ds, String schema, String table, int timeout) {
        try (var c = ds.getConnection()) {
            if (!c.getAutoCommit()) {
                throw invalid();
            }
            c.setAutoCommit(false);
            try {
                var shape = read(c, schema(schema), table, timeout);
                if (shape == null) {
                    throw invalid();
                }
                var fields = new HashSet<>(shape.definition.fields());
                return new DatabaseSqliteImpl().queryTableColumnMeta(c, schema(schema), table).stream()
                        .filter(v -> fields.contains(v.getFieldName())).toList();
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("SQLite native writable-column capture failed");
        }
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    public static NativeTableDefinition describe(DataSource ds, String schema, String table, int timeout) {
        try (var c = ds.getConnection()) {
            if (!c.getAutoCommit()) {
                throw invalid();
            }
            c.setAutoCommit(false);
            try {
                var captured = read(c, schema(schema), table, timeout);
                if (captured == null) {
                    throw invalid();
                }
                String json = JSONUtil.createObj().set("file", captured.file).set("root", captured.root)
                        .set("sourcePath", filePath(c, schema(schema), timeout).toString())
                        .set("definition", JSONUtil.parseObj(captured.definition)).toString();
                if (json.length() > 1_048_576) {
                    throw invalid();
                }
                c.rollback();
                return new NativeTableDefinition(ProductTypeEnum.SQLITE3, FORMAT, json, captured.definition.fields());
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("SQLite native table capture failed");
        }
    }

    /**
     * 返回计划。
     */
    public static NativeTargetPlan plan(DataSource ds, NativeTableDefinition source, String schema, String name,
            Map<String, String> mapping, boolean replace, int timeout) {
        final String targetSchema = schema(schema);
        q(targetSchema);
        q(name);
        if (source.product() != ProductTypeEnum.SQLITE3 || !FORMAT.equals(source.format()) || source.document()
                .length() > 1_048_576) {
            throw invalid();
        }
        var envelope = JSONUtil.parseObj(source.document());
        String sourceFile = envelope.getStr("file"), sourcePath = envelope.getStr("sourcePath");
        Long sourceRoot = envelope.getLong("root");
        Definition original = decode(envelope.getJSONObject("definition").toString());
        if (sourceFile == null || !sourceFile.matches(
                "[0-9a-f]{64}") || sourcePath == null || sourceRoot == null || sourceRoot < 1 || !source.fields()
                .equals(original.fields())
                || !mapping.keySet().equals(new HashSet<>(source.fields())) || mapping.values().stream()
                .map(v -> v.toLowerCase(Locale.ROOT)).distinct().count() != mapping.size()) {
            throw invalid();
        }
        mapping.values().forEach(SqliteNativeTables::q);
        Definition desired;
        Captured before;
        try (var c = ds.getConnection()) {
            configureWrite(c);
            desired = compile(ds, c, original, name, mapping, timeout);
            c.setAutoCommit(false);
            try {
                before = read(c, targetSchema, name, timeout);
                if (before != null && Files.isSameFile(Path.of(sourcePath),
                        filePath(c, targetSchema, timeout)) && before.root == sourceRoot) {
                    throw new IllegalArgumentException("SQLite source and target are the same physical table");
                }
                if (before != null && !replace && !equivalent(desired, before.definition)) {
                    throw new IllegalArgumentException(
                            "SQLite existing target structure differs from the complete native source");
                }
                checkIndexNames(c, targetSchema, desired, before, timeout);
                if (before != null && replace) {
                    requireNoIncomingDependencies(c, targetSchema, name, timeout);
                }
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
        } catch (SQLException | java.io.IOException failure) {
            throw new IllegalStateException("SQLite native target preflight failed");
        }
        String digest = DigestUtil.sha256Hex(
                source.document() + '\0' + targetSchema + '\0' + name + '\0' + JSONUtil.toJsonStr(
                        desired) + '\0' + replace);
        return new NativeTargetPlan() {
            private final AtomicBoolean used = new AtomicBoolean();

            private boolean transfer;

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
                return before != null && replace;
            }

            /**
             * 说明当前 DDL 路径是否要求调用方独占目标结构变更。
             */
            @Override
            public boolean requiresExclusiveDdlOwnership() {
                return replacesExisting();
            }

            /**
             * 设置传输。
             */
            @Override
            public void preflightDataTransfer() {
                transfer = true;
            }

            /**
             * 在当前数据库连接上执行给定 SQL 语句。
             */
            @Override
            public void execute() {
                if (!used.compareAndSet(false, true)) {
                    throw new IllegalStateException("SQLite native plan is single use");
                }
                synchronized (Sqlite3WriterImpl.GLOBAL_WRITE_LOCK) {
                    JdbcBatchExecutor.execute(ds, context -> {
                        var c = context.connection();
                        var current = read(c, targetSchema, name, timeout);
                        if (!Objects.equals(before, current)) {
                            throw new IllegalStateException(
                                    "SQLite target schema or physical identity changed after preflight");
                        }
                        if (current != null && !replace) {
                            return null;
                        }
                        checkIndexNames(c, targetSchema, desired, current, timeout);
                        if (current != null) {
                            requireNoIncomingDependencies(c, targetSchema, name, timeout);
                        }
                        context.markWriteAttempted();
                        if (current != null) {
                            SqliteNativeTables.execute(c, "DROP TABLE " + ref(targetSchema, name), timeout);
                        }
                        SqliteNativeTables.execute(c,
                                SqliteDdlAst.parse(desired.ddl, false).inSchema(targetSchema, name, name), timeout);
                        for (Index index : desired.indexes) {
                            SqliteNativeTables.execute(c,
                                    SqliteDdlAst.parse(index.ddl, true).inSchema(targetSchema, index.name, name),
                                    timeout);
                        }
                        if (transfer && desired.sequence != null) {
                            try (var s = c.prepareStatement("INSERT INTO " + q(
                                    targetSchema) + ".sqlite_sequence(name,seq) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM " + q(
                                    targetSchema) + ".sqlite_sequence WHERE name=?)")) {
                                s.setQueryTimeout(timeout);
                                s.setString(1, name);
                                s.setLong(2, desired.sequence);
                                s.setString(3, name);
                                s.executeUpdate();
                            }
                            try (var s = c.prepareStatement(
                                    "UPDATE " + q(targetSchema) + ".sqlite_sequence SET seq=max(seq,?) WHERE name=?")) {
                                s.setQueryTimeout(timeout);
                                s.setLong(1, desired.sequence);
                                s.setString(2, name);
                                s.executeUpdate();
                            }
                        }
                        var after = read(c, targetSchema, name, timeout);
                        if (after == null || !equivalent(desired, after.definition)) {
                            throw new IllegalStateException(
                                    "SQLite created native structure did not match the frozen plan");
                        }
                        return null;
                    }, null, SqliteNativeTables::configureWrite);
                }
            }
        };
    }

    /**
     * 从编码数据恢复定义。
     */
    private static Definition decode(String json) {
        var object = JSONUtil.parseObj(json);
        String name = object.getStr("name"), ddl = object.getStr("ddl");
        q(name);
        SqliteDdlAst.parse(ddl, false);
        var indexes = new ArrayList<Index>();
        for (var raw : object.getJSONArray("indexes")) {
            var i = JSONUtil.parseObj(raw);
            String index = i.getStr("name"), text = i.getStr("ddl");
            q(index);
            SqliteDdlAst.parse(text, true);
            indexes.add(new Index(index, text));
            if (indexes.size() > 256) {
                throw invalid();
            }
        }
        var columns = new ArrayList<Column>();
        for (var raw : object.getJSONArray("columns")) {
            var column = JSONUtil.parseObj(raw);
            String n = column.getStr("name");
            int hidden = column.getInt("generated", -1);
            q(n);
            if (hidden != 0 && hidden != 2 && hidden != 3) {
                throw invalid();
            }
            columns.add(new Column(n, hidden));
            if (columns.size() > 2000) {
                throw invalid();
            }
        }
        Long sequence = object.getLong("sequence");
        if (sequence != null && sequence < 0 || columns.isEmpty()) {
            throw invalid();
        }
        return new Definition(name, ddl, List.copyOf(indexes), List.copyOf(columns), sequence);
    }

    /** SQLite 的 ALTER 解析器改写约束、生成表达式、外键和索引表达式，并保留字面值。 */
    private static Definition compile(DataSource ds, Connection c, Definition source, String name,
            Map<String, String> mapping, int timeout) throws SQLException {
        String scratch = "dbsw_compile_" + UUID.randomUUID().toString().replace("-", "");
        long legacy = scalar(c, "PRAGMA legacy_alter_table", timeout);
        execute(c, "ATTACH DATABASE ':memory:' AS " + q(scratch), timeout);
        try {
            execute(c, "PRAGMA legacy_alter_table=OFF", timeout);
            execute(c, SqliteDdlAst.parse(source.ddl, false).inSchema(scratch, source.name, source.name), timeout);
            for (Index index : source.indexes) {
                execute(c, SqliteDdlAst.parse(index.ddl, true).inSchema(scratch, index.name, source.name), timeout);
            }
            var original = definition(c, scratch, source.name, timeout);
            if (!original.columns.equals(source.columns)) {
                throw invalid();
            }
            var temporary = new LinkedHashMap<String, String>();
            for (var entry : mapping.entrySet()) {
                if (!entry.getKey().equals(entry.getValue())) {
                    String interim = "dbsw_column_" + UUID.randomUUID().toString().replace("-", "");
                    temporary.put(interim, entry.getValue());
                    execute(c, "ALTER TABLE " + ref(scratch, source.name) + " RENAME COLUMN " + q(
                            entry.getKey()) + " TO " + q(interim), timeout);
                }
            }
            for (var entry : temporary.entrySet()) {
                execute(c,
                        "ALTER TABLE " + ref(scratch, source.name) + " RENAME COLUMN " + q(entry.getKey()) + " TO " + q(
                                entry.getValue()), timeout);
            }
            if (!source.name.equals(name)) {
                execute(c, "ALTER TABLE " + ref(scratch, source.name) + " RENAME TO " + q(name), timeout);
            }
            var transformed = definition(c, scratch, name, timeout);
            var indexes = new ArrayList<Index>();
            for (Index index : transformed.indexes) {
                String mapped = "dbsw_ix_" + DigestUtil.sha256Hex(name + '\0' + index.name).substring(0, 32);
                indexes.add(new Index(mapped, SqliteDdlAst.parse(index.ddl, true).inSchema("main", mapped, name)));
            }
            return new Definition(name, transformed.ddl, List.copyOf(indexes), transformed.columns, source.sequence);
        } finally {
            try {
                execute(c, "DETACH DATABASE " + q(scratch), timeout);
                execute(c, "PRAGMA legacy_alter_table=" + legacy, timeout);
            } catch (SQLException cleanup) {
                // 私有模式分离失败时，不得将仍附加编译数据库的连接归还连接池。
                if (ds instanceof com.zaxxer.hikari.HikariDataSource pool) {
                    pool.evictConnection(c);
                } else {
                    c.close();
                }
                throw new SQLException("SQLite private compiler connection cleanup failed");
            }
        }
    }

    /**
     * 读取SQLite原生目录并构建表结构元数据。
     */
    private static Captured read(Connection c, String schema, String name, int timeout) throws SQLException {
        q(schema);
        q(name);
        if (!"SQLite".equals(c.getMetaData().getDatabaseProductName())) {
            throw invalid();
        }
        long root;
        try (var s = c.prepareStatement(
                "SELECT type,rootpage FROM " + q(schema) + ".sqlite_master WHERE name=? COLLATE NOCASE")) {
            s.setQueryTimeout(timeout);
            s.setString(1, name);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                if (!"table".equals(r.getString(1)) || (root = r.getLong(2)) < 1 || r.next()) {
                    throw invalid();
                }
            }
        }
        var definition = definition(c, schema, name, timeout);
        return new Captured(file(c, schema, timeout), scalar(c, "PRAGMA " + q(schema) + ".schema_version", timeout),
                root, definition);
    }

    /**
     * 返回定义。
     */
    private static Definition definition(Connection c, String schema, String name, int timeout) throws SQLException {
        String ddl, actual;
        try (var s = c.prepareStatement(
                "SELECT name,sql FROM " + q(schema) + ".sqlite_master WHERE type='table' AND name=? COLLATE NOCASE")) {
            s.setQueryTimeout(timeout);
            s.setString(1, name);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    throw invalid();
                }
                actual = r.getString(1);
                ddl = r.getString(2);
                if (r.next()) {
                    throw invalid();
                }
            }
        }
        SqliteDdlAst.parse(ddl, false);
        for (String catalog : List.of(schema, "temp")) {
            try (var s = c.prepareStatement("SELECT 1 FROM " + q(
                    catalog) + ".sqlite_master WHERE type='trigger' AND tbl_name=? COLLATE NOCASE LIMIT 1")) {
                s.setQueryTimeout(timeout);
                s.setString(1, actual);
                try (var r = s.executeQuery()) {
                    if (r.next()) {
                        throw new IllegalArgumentException(
                                "SQLite trigger migration requires an explicit dependency plan");
                    }
                }
            }
        }
        var columns = new ArrayList<Column>();
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            try (var r = s.executeQuery("PRAGMA " + q(schema) + ".table_xinfo(" + q(actual) + ")")) {
                while (r.next()) {
                    int hidden = r.getInt("hidden");
                    if (hidden != 0 && hidden != 2 && hidden != 3 || columns.size() >= 2000) {
                        throw invalid();
                    }
                    columns.add(new Column(r.getString("name"), hidden));
                }
            }
        }
        if (columns.isEmpty()) {
            throw invalid();
        }
        // 自引用由 SQLite 改写；其他表的映射需要资源级计划。
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            try (var r = s.executeQuery("PRAGMA " + q(schema) + ".foreign_key_list(" + q(actual) + ")")) {
                while (r.next()) {
                    if (!r.getString("table").equalsIgnoreCase(actual)) {
                        throw new IllegalArgumentException(
                                "SQLite external foreign keys require a complete resource-level migration plan");
                    }
                }
            }
        }
        var indexes = new ArrayList<Index>();
        try (var s = c.prepareStatement("SELECT name,sql FROM " + q(
                schema) + ".sqlite_master WHERE type='index' AND tbl_name=? COLLATE NOCASE AND sql IS NOT NULL ORDER BY name")) {
            s.setQueryTimeout(timeout);
            s.setString(1, actual);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    if (indexes.size() >= 256) {
                        throw invalid();
                    }
                    String text = r.getString(2);
                    SqliteDdlAst.parse(text, true);
                    indexes.add(new Index(r.getString(1), text));
                }
            }
        }
        Long sequence = null;
        if (SqliteDdlAst.tokens(ddl).stream().anyMatch(t -> t.word("AUTOINCREMENT"))) {
            try (var s = c.prepareStatement("SELECT seq FROM " + q(schema) + ".sqlite_sequence WHERE name=?")) {
                s.setQueryTimeout(timeout);
                s.setString(1, actual);
                try (var r = s.executeQuery()) {
                    if (r.next()) {
                        sequence = r.getLong(1);
                        if (r.wasNull() || sequence < 0 || r.next()) {
                            throw invalid();
                        }
                    } else {
                        sequence = 0L;
                    }
                }
            }
        }
        return new Definition(actual, ddl, List.copyOf(indexes), List.copyOf(columns), sequence);
    }

    /**
     * 返回文件。
     */
    private static String file(Connection c, String schema, int timeout) throws SQLException {
        Path path = filePath(c, schema, timeout);
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw invalid();
            }
            // 模式版本标记或文件观测不能作为持久恢复代次；替换还需要独占归属。
            return DigestUtil.sha256Hex(
                    "SQLITE_FILE_OBSERVATION_V1\0" + path + '\0' + attributes.creationTime() + '\0' + attributes.fileKey());
        } catch (java.io.IOException failure) {
            throw new SQLException("SQLite file observation unavailable");
        }
    }

    /**
     * 从 SQLite 数据库列表读取指定模式对应的文件路径。
     */
    private static Path filePath(Connection c, String schema, int timeout) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            try (var r = s.executeQuery("PRAGMA database_list")) {
                while (r.next()) {
                    if (schema.equals(r.getString(2))) {
                        String file = r.getString(3);
                        if (file == null || file.isBlank()) {
                            throw new IllegalArgumentException(
                                    "SQLite native migration requires a file-backed database");
                        }
                        try {
                            return Path.of(file).toRealPath();
                        } catch (java.io.IOException failure) {
                            throw new SQLException("SQLite file observation unavailable");
                        }
                    }
                }
            }
        }
        throw invalid();
    }

    /**
     * 按当前结构或类型契约比较两个定义是否等价。
     */
    private static boolean equivalent(Definition a, Definition b) {
        return a.columns.equals(b.columns) && SqliteDdlAst.parse(a.ddl, false).shape()
                .equals(SqliteDdlAst.parse(b.ddl, false).shape())
                && a.indexes.stream().map(i -> SqliteDdlAst.parse(i.ddl, true).shape()).sorted().toList()
                .equals(b.indexes.stream().map(i -> SqliteDdlAst.parse(i.ddl, true).shape()).sorted().toList());
    }

    /**
     * 检查索引名称集合。
     */
    private static void checkIndexNames(Connection c, String schema, Definition desired, Captured prior,
            int timeout) throws SQLException {
        for (Index index : desired.indexes) {
            try (var s = c.prepareStatement(
                    "SELECT tbl_name FROM " + q(schema) + ".sqlite_master WHERE name=? COLLATE NOCASE")) {
                s.setQueryTimeout(timeout);
                s.setString(1, index.name);
                try (var r = s.executeQuery()) {
                    if (r.next() && (prior == null || !r.getString(1).equals(prior.definition.name))) {
                        throw new IllegalArgumentException("SQLite index name is already owned by another object");
                    }
                }
            }
        }
    }

    /**
     * 检查目标表未被其他对象通过外键等关系依赖。
     */
    private static void requireNoIncomingDependencies(Connection c, String schema, String target,
            int timeout) throws SQLException {
        var tables = new ArrayList<String>();
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            try (var r = s.executeQuery("SELECT type,name,sql FROM " + q(
                    schema) + ".sqlite_master WHERE type IN ('table','view','trigger')")) {
                while (r.next()) {
                    if (tables.size() > 4096) {
                        throw invalid();
                    }
                    String type = r.getString(1), name = r.getString(2), sql = r.getString(3);
                    if ("table".equals(type)) {
                        tables.add(name);
                        continue;
                    }
                    if (sql != null && SqliteDdlAst.tokens(sql).stream()
                            .anyMatch(t -> t.identity().equalsIgnoreCase(target))) {
                        throw new IllegalArgumentException(
                                "SQLite incoming view or trigger dependency prevents replacement");
                    }
                }
            }
        }
        for (String table : tables) {
            if (!table.equalsIgnoreCase(target)) {
                try (var s = c.createStatement()) {
                    s.setQueryTimeout(timeout);
                    try (var r = s.executeQuery("PRAGMA " + q(schema) + ".foreign_key_list(" + q(table) + ")")) {
                        while (r.next()) {
                            if (target.equalsIgnoreCase(r.getString("table"))) {
                                throw new IllegalArgumentException("SQLite incoming foreign key prevents replacement");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 读取或转换当前标量值。
     */
    private static long scalar(Connection c, String sql, int timeout) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            try (var r = s.executeQuery(sql)) {
                if (!r.next()) {
                    throw invalid();
                }
                long value = r.getLong(1);
                if (r.wasNull() || r.next()) {
                    throw invalid();
                }
                return value;
            }
        }
    }

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    private static void execute(Connection c, String sql, int timeout) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            s.execute(sql);
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return SqliteDdlAst.invalid();
    }
}
