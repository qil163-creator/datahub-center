package cn.iocoder.yudao.framework.dbswitch.product.oracle;

import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleSqlNames;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 同产品 Oracle 堆表定义；原生目录事实通过封闭类型语法渲染。 */
public final class OracleNativeTables {

    private static final String FORMAT = "ORACLE_NATIVE_HEAP_V2";

    private static final int MAX_COLUMNS = 1000, MAX_DOCUMENT = 1_048_576;

    /**
     * 初始化 {@code OracleNativeTables}。
     */
    private OracleNativeTables() {
    }

    private record Column(String name, String kind, Integer precision, Integer scale, int length, String semantics,
                          boolean nullable, String comment) {

        /**
         * 初始化 {@code Column}。
         */
        Column {
            OracleSqlNames.quote(name);
            type(kind, precision, scale, length, semantics);
            boundedComment(comment);
        }

        /**
         * 根据当前Oracle方言生成 SQL 语句或片段。
         */
        String sql() {
            return OracleSqlNames.quote(name) + " " + type(kind, precision, scale, length,
                    semantics) + (nullable ? "" : " NOT NULL");
        }

        /**
         * 将当前对象或数据行按既定映射转换为目标形式。
         */
        Column mapped(String name) {
            return new Column(name, kind, precision, scale, length, semantics, nullable, comment);
        }

        /**
         * 读取或构建当前关系及字段的结构信息。
         */
        List<Object> shape() {
            return Arrays.asList(name, kind, precision, scale, length, semantics, nullable);
        }
    }

    private record Definition(String identity, String databaseCharset, String nationalCharset, List<Column> columns,
                              List<String> keys, String comment, OracleNativeIndexes.Catalog indexes) {

        /**
         * 校验构造参数并初始化 {@code Definition}。
         */
        Definition {
            columns = List.copyOf(columns);
            keys = List.copyOf(keys);
            boundedComment(comment);
            Objects.requireNonNull(indexes);
            Set<String> knownColumns = new HashSet<>(columns.stream().map(Column::name).toList());
            if (!keys.equals(indexes.primaryKeys()) || indexes.indexes().stream().flatMap(i -> i.keys().stream())
                    .anyMatch(k -> !knownColumns.contains(k.column()))
                    || columns.stream().anyMatch(c -> c.nullable() && indexes.primaryKeys().contains(c.name()))) {
                throw new IllegalArgumentException("Oracle index fields differ from columns");
            }
            if (identity == null || !identity.matches("[1-9][0-9]{0,19}:[1-9][0-9]{0,19}")
                    || databaseCharset == null || !databaseCharset.matches(
                    "[A-Z0-9_]{1,30}") || nationalCharset == null || !nationalCharset.matches(
                    "[A-Z0-9_]{1,30}") || columns.isEmpty() || columns.size() > MAX_COLUMNS
                    || columns.stream().map(Column::name).distinct().count() != columns.size() || new HashSet<>(
                    keys).size() != keys.size()
                    || !columns.stream().map(Column::name).toList().containsAll(keys)) {
                throw new IllegalArgumentException("Invalid Oracle native definition");
            }
        }

        /**
         * 将当前对象或数据行按既定映射转换为目标形式。
         */
        Definition mapped(Map<String, String> mapping) {
            if (mapping.isEmpty() || !columns.stream().map(Column::name).toList()
                    .containsAll(mapping.keySet()) || !mapping.keySet().containsAll(keys)) {
                throw new IllegalArgumentException("Oracle mapping must retain each primary key and known fields");
            }
            return new Definition(identity, databaseCharset, nationalCharset,
                    columns.stream().filter(c -> mapping.containsKey(c.name()))
                            .map(c -> c.mapped(mapping.get(c.name()))).toList(),
                    keys.stream().map(mapping::get).toList(), comment, indexes.mapped(mapping));
        }

        /**
         * 读取或构建当前关系及字段的结构信息。
         */
        List<Object> shape() {
            return List.of(columns.stream().map(Column::shape).toList(), keys, indexes.semantic());
        }
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    public static NativeTableDefinition describe(DataSource source, String schema, String table, int timeout) {
        try (var connection = source.getConnection()) {
            Definition first = read(connection, schema, table, timeout), second = read(connection, schema, table,
                    timeout);
            if (first == null || !first.equals(second)) {
                throw new SQLException("Oracle source changed during capture");
            }
            return new NativeTableDefinition(ProductTypeEnum.ORACLE, FORMAT, encode(first),
                    first.columns().stream().map(Column::name).toList());
        } catch (Exception failure) {
            throw new IllegalStateException("Oracle native source structure could not be captured");
        }
    }

    /**
     * 返回计划。
     */
    public static NativeTargetPlan plan(DataSource target, NativeTableDefinition evidence, String schema, String table,
            Map<String, String> mapping, boolean replace, int timeout) {
        OracleSqlNames.relation(schema, table);
        if (evidence.product() != ProductTypeEnum.ORACLE || !FORMAT.equals(evidence.format())) {
            throw new IllegalArgumentException("Oracle native structure evidence required");
        }
        Definition source = decode(evidence.document());
        if (!evidence.fields().equals(source.columns().stream().map(Column::name).toList())) {
            throw new IllegalArgumentException("Oracle native fields do not match evidence");
        }
        Definition desired = source.mapped(Map.copyOf(mapping)), observed;
        try (var connection = target.getConnection()) {
            requireCharacterSets(connection, source, timeout);
            observed = read(connection, schema, table, timeout);
            if (observed != null && source.identity().equals(observed.identity())) {
                throw new SQLException("Oracle target is its physical source");
            }
            if (observed != null && !replace && !observed.shape().equals(desired.shape())) {
                throw new SQLException("Oracle target definition differs from source");
            }
            if (observed == null || replace) {
                OracleNativeIndexes.assertNamesAvailable(connection, desired.indexes(), schema, table, observed != null,
                        timeout);
            }
        } catch (Exception failure) {
            throw new IllegalStateException("Oracle native target preflight failed");
        }
        String ddl = createSql(schema, table, desired);
        String digest = cn.hutool.crypto.digest.DigestUtil.sha256Hex(evidence.document() + "\0" + ddl + "\0" + replace);
        return new NativeTargetPlan() {
            private final AtomicBoolean executed = new AtomicBoolean();

            private volatile String currentIdentity = observed == null ? null : observed.identity();

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
                return replace && observed != null;
            }

            /**
             * 说明当前 DDL 路径是否要求调用方独占目标结构变更。
             */
            @Override
            public boolean requiresExclusiveDdlOwnership() {
                return replacesExisting();
            }

            /**
             * 返回当前操作恢复时需要关联的资源信息。
             */
            @Override
            public List<NativeRecoveryResource> recoveryResources() {
                return List.of(new NativeRecoveryResource(ProductTypeEnum.ORACLE, schema, table,
                        NativeRecoveryResource.Role.TARGET, currentIdentity));
            }

            /**
             * 在当前数据库连接上执行给定 SQL 语句。
             */
            @Override
            public void execute() {
                if (!executed.compareAndSet(false, true)) {
                    throw new IllegalStateException("Oracle native plan is single use");
                }
                NonTransactionalWriteExecution.execute(target, (connection, attempt) -> {
                    if (!connection.getAutoCommit()) {
                        throw new SQLException("Oracle DDL cannot take over a caller transaction");
                    }
                    requireCharacterSets(connection, source, timeout);
                    Definition current = read(connection, schema, table, timeout);
                    if (!Objects.equals(observed, current)) {
                        throw new SQLException("Oracle target changed after preflight");
                    }
                    if (current == null || replace) {
                        OracleNativeIndexes.assertNamesAvailable(connection, desired.indexes(), schema, table,
                                current != null, timeout);
                    }
                    if (current != null && replace) {
                        executeDdl(connection, attempt, "DROP TABLE " + OracleSqlNames.relation(schema, table),
                                timeout);
                        currentIdentity = null; // 已确认 DROP 使旧标识失效，即使后续 CREATE 确认丢失也不例外。
                    }
                    if (current == null || replace) {
                        executeDdl(connection, attempt, ddl, timeout);
                        Definition created = read(connection, schema, table, timeout);
                        if (created == null) {
                            throw new SQLException("Oracle CREATE acknowledgement could not be verified");
                        }
                        currentIdentity = created.identity();
                        if (!created.columns().stream().map(Column::shape).toList()
                                .equals(desired.columns().stream().map(Column::shape).toList())) {
                            throw new SQLException("Oracle native type definition changed during CREATE");
                        }
                        for (String statement : OracleNativeIndexes.createSql(desired.indexes(), schema, table)) {
                            executeDdl(connection, attempt, statement, timeout);
                        }
                        if (desired.comment() != null) {
                            executeDdl(connection, attempt,
                                    "COMMENT ON TABLE " + OracleSqlNames.relation(schema, table) + " IS " + literal(
                                            desired.comment()), timeout);
                        }
                        for (var column : desired.columns()) {
                            if (column.comment() != null) {
                                executeDdl(connection, attempt, "COMMENT ON COLUMN " + OracleSqlNames.relation(schema,
                                        table) + '.' + OracleSqlNames.quote(column.name()) + " IS " + literal(
                                        column.comment()), timeout);
                            }
                        }
                        if (!desired.shape()
                                .equals(Objects.requireNonNull(read(connection, schema, table, timeout)).shape())) {
                            throw new SQLException("Oracle final structure not confirmed");
                        }
                    }
                    return 0;
                });
            }
        };
    }

    /**
     * 读取Oracle原生目录并构建表结构元数据。
     */
    private static Definition read(Connection connection, String schema, String table,
            int timeout) throws SQLException {
        OracleSqlNames.relation(schema, table);
        if (!"Oracle".equals(connection.getMetaData().getDatabaseProductName()) || connection.getMetaData()
                .getDatabaseMajorVersion() != 23) {
            throw new SQLException("Native Oracle 23 structure profile required");
        }
        String identity, comment;
        try (var p = prepare(connection,
                "SELECT SYS.STANDARD.SYS_CONTEXT('USERENV','CON_DBID'),o.object_id,t.temporary,t.partitioned,t.iot_type,t.nested,t.cluster_name,t.duration,m.comments "
                        + "FROM sys.all_objects o LEFT JOIN sys.all_tables t ON t.owner=o.owner AND t.table_name=o.object_name "
                        + "LEFT JOIN sys.all_tab_comments m ON m.owner=o.owner AND m.table_name=o.object_name AND m.table_type='TABLE' "
                        + "WHERE o.owner=? AND o.object_name=? AND o.subobject_name IS NULL AND o.object_type IN ('TABLE','VIEW','MATERIALIZED VIEW','SYNONYM','SEQUENCE')",
                timeout, schema, table); var r = p.executeQuery()) {
            if (!r.next()) {
                return null;
            }
            if (!"N".equals(r.getString(3)) || !"NO".equals(r.getString(4)) || r.getString(5) != null || !"NO".equals(
                    r.getString(6)) || r.getString(7) != null || r.getString(8) != null) {
                throw new SQLException("Oracle native migration requires an ordinary permanent heap");
            }
            identity = r.getString(1) + ':' + r.getString(2);
            comment = r.getString(9);
            if (r.next()) {
                throw new SQLException("Ambiguous Oracle object");
            }
        }
        for (String query : List.of("SELECT 1 FROM sys.all_external_tables WHERE owner=? AND table_name=?",
                "SELECT 1 FROM sys.all_mviews WHERE owner=? AND mview_name=?",
                "SELECT 1 FROM sys.all_policies WHERE object_owner=? AND object_name=?",
                "SELECT 1 FROM sys.all_triggers WHERE table_owner=? AND table_name=?")) {
            try (var p = prepare(connection, query, timeout, schema, table); var r = p.executeQuery()) {
                if (r.next()) {
                    throw new SQLException(
                            "Oracle external/materialized/policy/trigger table requires a separate structure strategy");
                }
            }
        }
        Set<String> visibleFields = new HashSet<>(), notNullFields = new HashSet<>();
        try (var p = prepare(connection,
                "SELECT column_name,nullable FROM sys.all_tab_cols WHERE owner=? AND table_name=? AND hidden_column='NO'",
                timeout, schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                if (visibleFields.size() >= MAX_COLUMNS || !visibleFields.add(r.getString(1))) {
                    throw new SQLException("Oracle column budget exceeded");
                }
                if ("N".equals(r.getString(2))) {
                    notNullFields.add(r.getString(1));
                }
            }
        }
        var nativeIndexes = OracleNativeIndexes.read(connection, schema, table, visibleFields, notNullFields, timeout);
        Set<String> hiddenSeen = new HashSet<>();
        List<Column> columns = new ArrayList<>();
        String sql = "SELECT c.column_name,c.data_type,c.data_type_owner,c.data_length,c.data_precision,c.data_scale,c.char_length,c.char_used,c.nullable,c.identity_column,c.virtual_column,c.hidden_column,c.default_on_null,m.comments,c.collation,c.user_generated,c.data_default "
                + "FROM sys.all_tab_cols c LEFT JOIN sys.all_col_comments m ON m.owner=c.owner AND m.table_name=c.table_name AND m.column_name=c.column_name "
                + "WHERE c.owner=? AND c.table_name=? ORDER BY c.column_id";
        try (var p = prepare(connection, sql, timeout, schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                String name = r.getString(1), nativeType = r.getString(2), owner = r.getString(3);
                int bytes = r.getInt(4);
                Integer precision = integer(r, 5), scale = integer(r, 6);
                int chars = r.getInt(7);
                String semantics = r.getString(8), nullable = r.getString(9);
                String identityColumn = r.getString(10), virtual = r.getString(11), hidden = r.getString(
                        12), defaultOnNull = r.getString(13), remarks = r.getString(14);
                String collation = r.getString(15);
                String generated = r.getString(16);
                String defaultSql = r.getString(17); // Oracle LONG 必须最后读取，且仅读一次。
                if ("YES".equals(hidden) && "YES".equals(virtual) && "NO".equals(
                        generated) && nativeIndexes.descendingHiddenColumns().containsKey(name)
                        && Objects.equals(defaultSql,
                        OracleSqlNames.quote(nativeIndexes.descendingHiddenColumns().get(name)))) {
                    hiddenSeen.add(name);
                    continue;
                }
                if (collation != null && !collation.equals("USING_NLS_COMP")) {
                    throw new SQLException("Oracle explicit collation requires a verified collation plan");
                }
                if (owner != null || !"NO".equals(identityColumn) || !"NO".equals(virtual) || !"NO".equals(
                        hidden) || "YES".equals(defaultOnNull)
                        || defaultSql != null && !defaultSql.trim().equalsIgnoreCase("NULL")) {
                    throw new SQLException(
                            "Oracle identity, generated or dependent default requires a dependency plan");
                }
                if (!Set.of("Y", "N").contains(nullable) || columns.size() >= MAX_COLUMNS) {
                    throw new SQLException("Oracle column evidence incomplete");
                }
                String kind = nativeType;
                if (nativeType.matches("TIMESTAMP\\([0-9]\\)")) {
                    kind = "TIMESTAMP";
                    if (scale == null || scale != Character.digit(nativeType.charAt(10), 10)) {
                        throw new SQLException("Oracle timestamp scale disagrees");
                    }
                }
                int length = Set.of("NVARCHAR2", "NCHAR", "VARCHAR2", "CHAR").contains(kind) ? chars : kind.equals(
                        "RAW") ? bytes : 0;
                if (Set.of("VARCHAR2", "CHAR").contains(kind) && "B".equals(semantics)) {
                    length = bytes;
                }
                columns.add(new Column(name, kind, precision, scale, length, semantics, "Y".equals(nullable), remarks));
            }
        }
        if (!hiddenSeen.equals(nativeIndexes.descendingHiddenColumns().keySet())) {
            throw new SQLException("Oracle descending index evidence incomplete");
        }
        List<String> keys = nativeIndexes.catalog().primaryKeys();
        var characterSets = characterSets(connection, timeout);
        return new Definition(identity, characterSets.get("NLS_CHARACTERSET"),
                characterSets.get("NLS_NCHAR_CHARACTERSET"), columns, keys, comment, nativeIndexes.catalog());
    }

    /**
     * 读取数据库字符集及国家字符集配置。
     */
    private static Map<String, String> characterSets(Connection connection, int timeout) throws SQLException {
        var values = new HashMap<String, String>();
        try (var p = prepare(connection,
                "SELECT parameter,value FROM sys.nls_database_parameters WHERE parameter IN ('NLS_CHARACTERSET','NLS_NCHAR_CHARACTERSET')",
                timeout); var r = p.executeQuery()) {
            while (r.next()) {
                if (values.put(r.getString(1), r.getString(2)) != null) {
                    throw new SQLException("Oracle character set evidence ambiguous");
                }
            }
        }
        if (values.size() != 2) {
            throw new SQLException("Oracle character set evidence absent");
        }
        return values;
    }

    /**
     * 检查目标数据库字符集与源端已声明字符集一致。
     */
    private static void requireCharacterSets(Connection connection, Definition source,
            int timeout) throws SQLException {
        var target = characterSets(connection, timeout);
        if (!source.databaseCharset().equals(target.get("NLS_CHARACTERSET")) || !source.nationalCharset()
                .equals(target.get("NLS_NCHAR_CHARACTERSET"))) {
            throw new SQLException("Oracle character domain conversion requires a verified mapping");
        }
    }

    /**
     * 返回类型。
     */
    private static String type(String kind, Integer precision, Integer scale, int length, String semantics) {
        if (kind == null) {
            throw new IllegalArgumentException("Oracle native type absent");
        }
        return switch (kind) {
            case "NUMBER" -> {
                if (precision != null && (precision < 1 || precision > 38) || scale != null && (scale < -84 || scale > 127)) {
                    throw new IllegalArgumentException("Oracle NUMBER domain invalid");
                }
                yield precision == null ? (scale == null ? "NUMBER" : "NUMBER(*," + scale + ")") : "NUMBER(" + precision + "," + (scale == null ? 0 : scale) + ")";
            }
            case "FLOAT" -> {
                if (precision == null || precision < 1 || precision > 126) {
                    throw new IllegalArgumentException("Oracle FLOAT precision invalid");
                }
                yield "FLOAT(" + precision + ")";
            }
            case "TIMESTAMP" -> {
                if (scale == null || scale < 0 || scale > 9) {
                    throw new IllegalArgumentException("Oracle TIMESTAMP scale invalid");
                }
                yield "TIMESTAMP(" + scale + ")";
            }
            case "VARCHAR2", "CHAR" -> {
                if (length < 1 || length > 32767 || !Set.of("B", "C").contains(semantics)) {
                    throw new IllegalArgumentException("Oracle character length semantics invalid");
                }
                yield kind + '(' + Integer.toString(length) + (semantics.equals("B") ? " BYTE)" : " CHAR)");
            }
            case "NVARCHAR2", "NCHAR", "RAW" -> {
                if (length < 1 || length > 32767) {
                    throw new IllegalArgumentException("Oracle native length invalid");
                }
                yield kind + '(' + Integer.toString(length) + ')';
            }
            case "DATE", "BINARY_FLOAT", "BINARY_DOUBLE", "BLOB", "CLOB", "NCLOB" -> kind;
            default -> throw new IllegalArgumentException("Oracle native type requires a verified renderer");
        };
    }

    /**
     * 生成原生对象的创建 SQL。
     */
    private static String createSql(String schema, String table, Definition definition) {
        var fields = new ArrayList<>(definition.columns().stream().map(Column::sql).toList());
        return "CREATE TABLE " + OracleSqlNames.relation(schema, table) + "(" + String.join(",", fields) + ")";
    }

    /**
     * 将当前数据编码为字符串。
     */
    private static String encode(Definition definition) {
        var root = new JSONObject().set("identity", definition.identity())
                .set("databaseCharset", definition.databaseCharset())
                .set("nationalCharset", definition.nationalCharset()).set("keys", definition.keys())
                .set("comment", definition.comment()).set("indexes", OracleNativeIndexes.encode(definition.indexes()));
        var fields = new ArrayList<JSONObject>();
        for (var c : definition.columns()) {
            fields.add(new JSONObject().set("name", c.name()).set("kind", c.kind()).set("precision", c.precision())
                    .set("scale", c.scale()).set("length", c.length()).set("semantics", c.semantics())
                    .set("nullable", c.nullable()).set("comment", c.comment()));
        }
        String json = root.set("columns", fields).toString();
        if (json.length() > MAX_DOCUMENT) {
            throw new IllegalArgumentException("Oracle definition budget exceeded");
        }
        return json;
    }

    /**
     * 从编码数据恢复定义。
     */
    private static Definition decode(String document) {
        if (document == null || document.length() > MAX_DOCUMENT) {
            throw new IllegalArgumentException("Oracle definition budget exceeded");
        }
        var root = JSONUtil.parseObj(document);
        var array = root.getJSONArray("columns");
        if (array == null || array.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("Oracle columns absent");
        }
        List<Column> columns = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            var c = array.getJSONObject(i);
            if (!(c.get("nullable") instanceof Boolean)) {
                throw new IllegalArgumentException("Oracle nullability absent");
            }
            columns.add(new Column(c.getStr("name"), c.getStr("kind"), c.getInt("precision"), c.getInt("scale"),
                    c.getInt("length"), c.getStr("semantics"), c.getBool("nullable"), c.getStr("comment")));
        }
        return new Definition(root.getStr("identity"), root.getStr("databaseCharset"), root.getStr("nationalCharset"),
                columns, root.getJSONArray("keys").toList(String.class), root.getStr("comment"),
                OracleNativeIndexes.decode(root.getJSONObject("indexes")));
    }

    /**
     * 从 JDBC 结果集读取并转换整数。
     */
    private static Integer integer(ResultSet r, int index) throws SQLException {
        int value = r.getInt(index);
        return r.wasNull() ? null : value;
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
     * 在当前连接上执行给定的 DDL 语句。
     */
    private static void executeDdl(Connection c, NonTransactionalWriteExecution.Attempt attempt, String sql,
            int timeout) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            attempt.sending();
            s.execute(sql);
        }
    }

    /**
     * 检查注释未包含零字符且未超过编码字节上限。
     */
    private static void boundedComment(String value) {
        if (value != null && (value.indexOf('\0') >= 0 || value.getBytes(
                java.nio.charset.StandardCharsets.UTF_8).length > 4000)) {
            throw new IllegalArgumentException("Oracle comment budget exceeded");
        }
    }

    /**
     * 返回当前字面量。
     */
    private static String literal(String value) {
        return '\'' + value.replace("'", "''") + '\'';
    }
}
