package cn.iocoder.yudao.framework.dbswitch.product.tdengine;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.TdengineRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTableDefinition;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeTargetPlan;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.NativeRecoveryResource;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** 原生表族结构。父表名称保持不变；选中的叶表名称可以映射。 */
public final class TdengineNativeTables {

    private static final String FORMAT = "TDENGINE_TABLE_FAMILY_V1";

    private static final int MAX_SELECTED_TABLES = 100_000;

    private static final Object[] LOCKS = java.util.stream.IntStream.range(0, 64).mapToObj(ignored -> new Object())
            .toArray();

    /**
     * 初始化 {@code TdengineNativeTables}。
     */
    private TdengineNativeTables() {
    }

    private record Column(String name, String type, boolean key, boolean tag, String encoding, String compression,
                          String level) {

    }

    private record Tag(String encoding, String value) {

    }

    private record Properties(String comment, int ttl) {

    }

    private record Definition(String kind, String parent, String precision, List<Column> columns, List<Tag> tags,
                              Properties properties, Properties parentProperties) {

        /**
         * 返回字段集合。
         */
        List<String> fields() {
            return columns.stream().filter(column -> !column.tag()).map(Column::name).toList();
        }

        /**
         * 返回当前 TDengine 表包含的标签列。
         */
        List<Column> tagColumns() {
            return columns.stream().filter(Column::tag).toList();
        }

        /**
         * 将当前对象或数据行按既定映射转换为目标形式。
         */
        Definition mapped(Map<String, String> mapping) {
            if (!mapping.keySet().equals(new HashSet<>(fields())) || mapping.values().stream().distinct()
                    .count() != mapping.size()) {
                throw new IllegalArgumentException(
                        "TDengine native mapping must retain every data column exactly once");
            }
            mapping.values().forEach(TdengineJdbcSupport::quote);
            return checked(new Definition(kind, parent, precision, columns.stream().map(column -> new Column(
                    column.tag() ? column.name() : mapping.get(column.name()), column.type(), column.key(),
                    column.tag(),
                    column.encoding(), column.compression(), column.level())).toList(), tags, properties,
                    parentProperties));
        }

        /**
         * 根据对象结构和键列定义生成稳定的结构键。
         */
        String structuralKey() {
            return precision + columns.toString();
        }
    }

    private record Identity(String kind, String uid) {

    }

    /** 恢复使用的同连接物理代次及完整原生结构、TAG 证据。 */
    public record RecoveryIdentity(String logicalResource, String physical, String structure) {

    }

    /**
     * 根据当前输入构建并返回 {@code RecoveryIdentity}。
     */
    public static RecoveryIdentity recoveryIdentity(java.sql.Connection connection, String schema, String table,
            int timeout) throws Exception {
        String cluster = TdengineRelationIdentity.clusterFingerprint(connection, timeout);
        Identity before = identity(connection, schema, table, timeout);
        if (before == null || "SUPER_TABLE".equals(before.kind())) {
            throw new IllegalStateException("Recovery requires a physical leaf table");
        }
        Definition definition = read(connection, schema, table, timeout);
        if (!before.equals(identity(connection, schema, table, timeout)) || !cluster.equals(
                TdengineRelationIdentity.clusterFingerprint(connection, timeout))) {
            throw new IllegalStateException("TDengine physical generation changed during recovery capture");
        }
        return new RecoveryIdentity(cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.GroupRewriteMaterial.sha256(
                ("TDENGINE_GROUP_LOGICAL_V1\0" + cluster + "\0" + schema.toLowerCase(
                        Locale.ROOT) + "\0" + table.toLowerCase(Locale.ROOT)).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)),
                cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.GroupRewriteMaterial.sha256(
                        ("TDENGINE_GROUP_RESOURCE_V1\0" + cluster + "\0" + before.kind() + "\0" + before.uid()).getBytes(
                                java.nio.charset.StandardCharsets.UTF_8)),
                cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.GroupRewriteMaterial.sha256(
                        definition.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * 根据绑定上下文展开当前 ETL 子流程。
     */
    public static List<TableDescription> expand(DataSource source, List<TableDescription> selected, int timeout) {
        var output = new LinkedHashMap<String, TableDescription>();
        try (var connection = source.getConnection()) {
            TdengineJdbcSupport.requireValueDriver(connection);
            for (var table : selected) {
                String schema = table.getSchemaName(), name = table.getTableName();
                String kind = kind(connection, schema, name, timeout);
                if (!kind.equals("SUPER_TABLE")) {
                    retain(output, table);
                    continue;
                }
                boolean found = false;
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(timeout);
                    try (var rows = statement.executeQuery(
                            "SELECT table_name FROM information_schema.ins_tables WHERE db_name="
                                    + literal(schema) + " AND stable_name=" + literal(
                                    name) + " ORDER BY table_name LIMIT 100001")) {
                        int count = 0;
                        while (rows.next()) {
                            if (++count > MAX_SELECTED_TABLES) {
                                throw new IllegalArgumentException("TDengine table-family selection exceeds its bound");
                            }
                            var child = new TableDescription();
                            child.setSchemaName(schema);
                            child.setTableName(rows.getString(1));
                            child.setTableType("TABLE");
                            retain(output, child);
                            found = true;
                        }
                    }
                }
                // 空表族仍携带仅含结构的父表；其查询没有测量行。
                if (!found) {
                    retain(output, table);
                }
            }
            return List.copyOf(output.values());
        } catch (Exception failure) {
            throw new IllegalStateException("TDengine table-family selection failed");
        }
    }

    /**
     * 保留当前输出或资源，供后续阶段继续使用。
     */
    private static void retain(Map<String, TableDescription> output, TableDescription table) {
        output.put(table.getSchemaName() + "\0" + table.getTableName(), table);
        if (output.size() > MAX_SELECTED_TABLES) {
            throw new IllegalArgumentException("TDengine complete table-family selection exceeds its bound");
        }
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    public static NativeTableDefinition describe(DataSource source, String schema, String table, int timeout) {
        try (var connection = source.getConnection()) {
            String cluster = TdengineRelationIdentity.clusterFingerprint(connection, timeout);
            Identity before = identity(connection, schema, table, timeout);
            Definition definition = read(connection, schema, table, timeout);
            if (before == null || !before.equals(identity(connection, schema, table, timeout))) {
                throw new IllegalStateException("TDengine source changed during native structure capture");
            }
            var document = JSONUtil.parseObj(encode(definition));
            document.set("sourceCluster", cluster);
            document.set("sourceUid", before.uid());
            document.set("sourceKind", before.kind());
            return new NativeTableDefinition(ProductTypeEnum.TDENGINE, FORMAT, document.toString(),
                    definition.fields());
        } catch (Exception failure) {
            throw new IllegalStateException("TDengine native structure could not be captured");
        }
    }

    /**
     * 返回计划。
     */
    public static NativeTargetPlan plan(DataSource source, NativeTableDefinition evidence, String schema, String table,
            Map<String, String> mapping, boolean replace, int timeout) {
        if (evidence.product() != ProductTypeEnum.TDENGINE || !FORMAT.equals(evidence.format())) {
            throw new IllegalArgumentException("TDengine requires native table-family structure evidence");
        }
        TdengineJdbcSupport.table(schema, table);
        Definition desired = decode(evidence.document());
        var envelope = JSONUtil.parseObj(evidence.document());
        String sourceCluster = envelope.getStr("sourceCluster"), sourceUid = envelope.getStr(
                "sourceUid"), sourceKind = envelope.getStr("sourceKind");
        if (sourceCluster == null || !sourceCluster.matches("[0-9a-f]{64}") || sourceUid == null
                || !sourceUid.matches("[1-9][0-9]{0,19}") || !desired.kind().equals(sourceKind)) {
            throw new IllegalArgumentException("TDengine native source physical identity is required");
        }
        if (!desired.fields().equals(evidence.fields())) {
            throw new IllegalArgumentException("TDengine native fields differ from their evidence");
        }
        desired = desired.mapped(mapping);
        if (desired.kind().equals("CHILD_TABLE") && desired.parent().equals(table)) {
            throw new IllegalArgumentException("TDengine child mapping collides with its preserved parent name");
        }
        Definition target;
        Identity targetIdentity;
        Identity parentIdentity;
        String targetCluster;
        try (var connection = source.getConnection()) {
            requirePrecision(connection, schema, desired.precision(), timeout);
            targetCluster = TdengineRelationIdentity.clusterFingerprint(connection, timeout);
            target = existing(connection, schema, table, timeout);
            targetIdentity = identity(connection, schema, table, timeout);
            parentIdentity = desired.kind().equals("CHILD_TABLE") ? identity(connection, schema, desired.parent(),
                    timeout) : null;
            if (replace && targetIdentity != null && targetCluster.equals(sourceCluster) && targetIdentity.uid()
                    .equals(sourceUid)) {
                throw new IllegalArgumentException("TDengine native replacement cannot drop its physical source");
            }
            validateTarget(connection, schema, table, desired, target, replace, timeout);
        } catch (Exception failure) {
            throw new IllegalStateException("TDengine native target preflight failed");
        }
        Definition planned = desired, observed = target;
        Identity observedIdentity = targetIdentity;
        var resources = new ArrayList<NativeRecoveryResource>();
        resources.add(
                new NativeRecoveryResource(ProductTypeEnum.TDENGINE, schema, table, NativeRecoveryResource.Role.TARGET,
                        targetIdentity == null ? null : targetIdentity.uid()));
        if (planned.kind().equals("CHILD_TABLE")) {
            resources.add(new NativeRecoveryResource(ProductTypeEnum.TDENGINE, schema, planned.parent(),
                    NativeRecoveryResource.Role.TARGET,
                    parentIdentity == null ? null : parentIdentity.uid()));
        }
        List<NativeRecoveryResource> recovery = List.copyOf(resources);
        String digest = cn.hutool.crypto.digest.DigestUtil.sha256Hex(
                evidence.document() + "\0" + schema + "\0" + table + "\0" + mapping + "\0" + replace);
        return new NativeTargetPlan() {
            private final AtomicBoolean executed = new AtomicBoolean();

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

            // UID 观测不代表服务器端比较并删除操作。替换已有物理关系前，调用方必须
            // 在外部排除并发 DDL。

            /**
             * 说明当前 DDL 路径是否要求调用方独占目标结构变更。
             */
            @Override
            public boolean requiresExclusiveDdlOwnership() {
                return replacesExisting();
            }

            /**
             * 返回恢复。
             */
            @Override
            public List<NativeRecoveryResource> recoveryResources() {
                return recovery;
            }

            /**
             * 在当前数据库连接上执行给定 SQL 语句。
             */
            @Override
            public void execute() {
                if (!executed.compareAndSet(false, true)) {
                    throw new IllegalStateException("Native TDengine plan is single use");
                }
                Object lock = LOCKS[Math.floorMod(
                        Objects.hash(schema, planned.parent().isEmpty() ? table : planned.parent()), LOCKS.length)];
                synchronized (lock) {
                    NonTransactionalWriteExecution.execute(source, (connection, attempt) -> {
                        if (!targetCluster.equals(TdengineRelationIdentity.clusterFingerprint(connection, timeout))) {
                            throw new IllegalStateException("TDengine target cluster changed after planning");
                        }
                        Definition current = existing(connection, schema, table, timeout);
                        if (!Objects.equals(observed, current) || !Objects.equals(observedIdentity,
                                identity(connection, schema, table, timeout))) {
                            throw new IllegalStateException("TDengine native target changed after planning");
                        }
                        requirePrecision(connection, schema, planned.precision(), timeout);
                        validateTarget(connection, schema, table, planned, current, replace, timeout);
                        if (planned.kind().equals("CHILD_TABLE")) {
                            String parent = TdengineJdbcSupport.table(schema, planned.parent());
                            if (existing(connection, schema, planned.parent(), timeout) == null) {
                                TdengineNativeTables.execute(connection, attempt,
                                        "CREATE STABLE IF NOT EXISTS " + parent
                                                + columnsSql(planned) + propertiesSql(planned.parentProperties(), true),
                                        timeout);
                            }
                            Definition actualParent = read(connection, schema, planned.parent(), timeout);
                            if (!planned.structuralKey()
                                    .equals(actualParent.structuralKey()) || !planned.parentProperties()
                                    .equals(actualParent.properties())) {
                                throw new IllegalStateException("TDengine parent structure was not confirmed");
                            }
                        }
                        if (current != null && replace) {
                            TdengineNativeTables.execute(connection, attempt,
                                    "DROP TABLE " + TdengineJdbcSupport.table(schema, table), timeout);
                        }
                        if (current == null || replace) {
                            String ddl = planned.kind()
                                    .equals("SUPER_TABLE") ? "CREATE STABLE " + TdengineJdbcSupport.table(schema,
                                    table) + columnsSql(planned)
                                    : planned.kind()
                                    .equals("CHILD_TABLE") ? "CREATE TABLE " + TdengineJdbcSupport.table(schema, table)
                                    + " USING " + TdengineJdbcSupport.table(schema, planned.parent()) + " TAGS ("
                                    + planned.tags().stream().map(TdengineNativeTables::tagLiteral)
                                    .collect(Collectors.joining(",")) + ")"
                                    : "CREATE TABLE " + TdengineJdbcSupport.table(schema, table) + columnsSql(planned);
                            TdengineNativeTables.execute(connection, attempt,
                                    ddl + propertiesSql(planned.properties(), planned.kind().equals("SUPER_TABLE")),
                                    timeout);
                        }
                        if (!planned.equals(read(connection, schema, table, timeout))) {
                            throw new IllegalStateException(
                                    "TDengine native structure or tag values were not confirmed");
                        }
                        return 0;
                    });
                }
            }
        };
    }

    /**
     * 校验目标端。
     */
    private static void validateTarget(Connection connection, String schema, String table, Definition desired,
            Definition current,
            boolean replace, int timeout) throws Exception {
        if (current != null && current.kind().equals("SUPER_TABLE") && replace) {
            throw new IllegalArgumentException("Replacing a TDengine supertable would cascade to unselected children");
        }
        if (current != null && !replace && !current.equals(desired)) {
            throw new IllegalArgumentException(
                    "TDengine existing native structure and tag values must match before row synchronization");
        }
        if (desired.kind().equals("CHILD_TABLE")) {
            Definition parent = existing(connection, schema, desired.parent(), timeout);
            if (parent != null && (!parent.kind().equals("SUPER_TABLE") || !parent.structuralKey()
                    .equals(desired.structuralKey())
                    || !parent.properties().equals(desired.parentProperties()))) {
                throw new IllegalArgumentException("TDengine preserved parent name has an incompatible structure");
            }
        }
        columnsSql(desired);
        desired.tags().forEach(TdengineNativeTables::tagLiteral);
    }

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    private static void execute(Connection connection, NonTransactionalWriteExecution.Attempt attempt, String sql,
            int timeout) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            attempt.sending();
            statement.execute(sql);
        }
    }

    /**
     * 返回当前已有。
     */
    private static Definition existing(Connection connection, String schema, String table,
            int timeout) throws Exception {
        return kind(connection, schema, table, timeout).isEmpty() ? null : read(connection, schema, table, timeout);
    }

    /**
     * 返回身份信息。
     */
    private static Identity identity(Connection connection, String schema, String table, int timeout) throws Exception {
        String kind = kind(connection, schema, table, timeout);
        if (kind.isEmpty()) {
            return null;
        }
        boolean stable = kind.equals("SUPER_TABLE");
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery(
                    "SELECT uid FROM information_schema." + (stable ? "ins_stables" : "ins_tables")
                            + " WHERE db_name=" + literal(
                            schema) + " AND " + (stable ? "stable_name" : "table_name") + "=" + literal(table))) {
                if (!rows.next()) {
                    throw new IllegalStateException("TDengine physical identity disappeared");
                }
                String uid = rows.getString(1);
                if (uid == null || uid.isBlank() || rows.next()) {
                    throw new IllegalStateException("TDengine physical identity is ambiguous");
                }
                return new Identity(kind, uid);
            }
        }
    }

    /**
     * 返回属性配置。
     */
    private static Properties properties(Connection connection, String schema, String table, boolean stable,
            int timeout) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery(
                    "SELECT table_comment," + (stable ? "`keep`,`watermark`,`max_delay`,`rollup`,`isvirtual`" : "`ttl`")
                            + " FROM information_schema." + (stable ? "ins_stables" : "ins_tables")
                            + " WHERE db_name=" + literal(
                            schema) + " AND " + (stable ? "stable_name" : "table_name") + "=" + literal(table))) {
                if (!rows.next()) {
                    throw new IllegalStateException("TDengine native properties disappeared");
                }
                String comment = Objects.toString(rows.getString(1), "");
                int ttl = stable ? 0 : rows.getInt(2);
                if (stable && (rows.getLong(2) != -1 || !"5000a,5000a".equals(rows.getString(3))
                        || !"-1a,-1a".equals(rows.getString(4)) || !Objects.toString(rows.getString(5), "")
                        .isEmpty() || rows.getBoolean(6))) {
                    throw new IllegalArgumentException(
                            "TDengine custom supertable retention, rollup or virtual semantics require a native plan");
                }
                if (rows.next()) {
                    throw new IllegalStateException("TDengine native properties are ambiguous");
                }
                Properties result = new Properties(comment, ttl);
                propertiesSql(result, stable);
                return result;
            }
        }
    }

    /**
     * 根据受支持属性生成数据库对象选项 SQL。
     */
    private static String propertiesSql(Properties properties, boolean stable) {
        if (properties.ttl() < 0 || (stable && properties.ttl() != 0) || properties.comment().length() > 1024) {
            throw new IllegalArgumentException("Invalid TDengine table properties");
        }
        return " COMMENT " + commentLiteral(properties.comment()) + (stable ? "" : " TTL " + properties.ttl());
    }

    /**
     * 根据当前输入判定类型或操作种类。
     */
    private static String kind(Connection connection, String schema, String table, int timeout) throws Exception {
        TdengineJdbcSupport.table(schema, table);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery(
                    "SELECT `type` FROM information_schema.ins_tables WHERE db_name=" + literal(schema)
                            + " AND table_name=" + literal(table) + " LIMIT 2")) {
                if (rows.next()) {
                    String kind = rows.getString(1);
                    if (rows.next()) {
                        throw new IllegalStateException("Ambiguous TDengine relation");
                    }
                    return kind;
                }
            }
            try (var rows = statement.executeQuery(
                    "SELECT stable_name FROM information_schema.ins_stables WHERE db_name=" + literal(schema)
                            + " AND stable_name=" + literal(table) + " LIMIT 2")) {
                if (!rows.next()) {
                    return "";
                }
                if (rows.next()) {
                    throw new IllegalStateException("Ambiguous TDengine supertable");
                }
                return "SUPER_TABLE";
            }
        }
    }

    /**
     * 读取TDengine原生目录并构建表结构元数据。
     */
    private static Definition read(Connection connection, String schema, String table, int timeout) throws Exception {
        TdengineJdbcSupport.requireValueDriver(connection);
        String kind = kind(connection, schema, table, timeout);
        if (!Set.of("NORMAL_TABLE", "CHILD_TABLE", "SUPER_TABLE").contains(kind)) {
            throw new IllegalArgumentException("Unsupported TDengine relation kind");
        }
        String parent = "", precision;
        var columns = new ArrayList<Column>();
        var tags = new ArrayList<Tag>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery(
                    "SELECT `precision` FROM information_schema.ins_databases WHERE name=" + literal(schema))) {
                if (!rows.next()) {
                    throw new IllegalStateException("TDengine database precision is missing");
                }
                precision = rows.getString(1);
            }
            if (kind.equals("CHILD_TABLE")) {
                try (var rows = statement.executeQuery(
                        "SELECT stable_name FROM information_schema.ins_tables WHERE db_name="
                                + literal(schema) + " AND table_name=" + literal(table))) {
                    if (!rows.next()) {
                        throw new IllegalStateException("TDengine parent is missing");
                    }
                    parent = rows.getString(1);
                    TdengineJdbcSupport.quote(parent);
                }
            }
            try (var rows = statement.executeQuery("DESCRIBE " + TdengineJdbcSupport.table(schema, table))) {
                while (rows.next()) {
                    if (columns.size() >= 4096) {
                        throw new IllegalArgumentException("TDengine native column bound exceeded");
                    }
                    String name = rows.getString(1), type = rows.getString(2).replace(" ", "").toUpperCase(Locale.ROOT);
                    // 规范化 DECIMAL 的可选空白时，保留 UNSIGNED 中的空格。
                    type = type.replace("UNSIGNED", " UNSIGNED");
                    if (Set.of("NCHAR", "BINARY", "VARCHAR", "VARBINARY").contains(type)) {
                        type += "(" + rows.getInt(3) + ")";
                    }
                    Column column = new Column(name, type, "COMPOSITE KEY".equals(rows.getString(4)),
                            "TAG".equals(rows.getString(4)),
                            rows.getString(5), rows.getString(6), rows.getString(7));
                    columnSql(column);
                    columns.add(column);
                }
            }
            if (kind.equals("CHILD_TABLE")) {
                var tagColumns = columns.stream().filter(Column::tag).toList();
                try (var rows = statement.executeQuery(
                        "SELECT TAGS " + TdengineJdbcSupport.columns(tagColumns.stream().map(Column::name).toList())
                                + " FROM " + TdengineJdbcSupport.table(schema, table))) {
                    if (!rows.next()) {
                        throw new IllegalStateException("TDengine child tags are missing");
                    }
                    for (int index = 0; index < tagColumns.size(); index++) {
                        tags.add(tag(rows.getObject(index + 1), precision, tagColumns.get(index).type()));
                    }
                    if (rows.next()) {
                        throw new IllegalStateException("TDengine child tags are ambiguous");
                    }
                }
            }
        }
        return checked(new Definition(kind, parent, precision, List.copyOf(columns), List.copyOf(tags),
                properties(connection, schema, table, kind.equals("SUPER_TABLE"), timeout),
                kind.equals("CHILD_TABLE") ? properties(connection, schema, parent, true, timeout) : new Properties("",
                        0)));
    }

    /**
     * 返回当前标签。
     */
    private static Tag tag(Object value, String precision, String type) {
        if (value == null) {
            return new Tag("NULL", "");
        }
        if (value instanceof byte[] bytes) {
            return new Tag(type.startsWith("VARBINARY(") ? "VARBINARY" : "BYTES", HexFormat.of().formatHex(bytes));
        }
        if (value instanceof Boolean bool) {
            return new Tag("BOOLEAN", bool.toString());
        }
        if (value instanceof Timestamp timestamp) {
            return new Tag("NUMBER", Long.toString(TdengineJdbcSupport.timestamp(timestamp,
                    precision.equals("ns") ? 9 : precision.equals("us") ? 6 : 3)));
        }
        if (value instanceof Number number) {
            return new Tag("NUMBER", numberLiteral(number.toString()));
        }
        return new Tag("TEXT", JdbcValueConversions.toText(value));
    }

    /**
     * 校验并编码当前标签的 SQL 字面量。
     */
    private static String tagLiteral(Tag tag) {
        return switch (tag.encoding()) {
            case "NULL" -> "NULL";
            case "BOOLEAN" -> {
                if (!Set.of("true", "false").contains(tag.value())) {
                    throw new IllegalArgumentException("Invalid boolean tag");
                }
                yield tag.value();
            }
            case "NUMBER" -> numberLiteral(tag.value());
            case "TEXT" -> literal(tag.value());
            case "BYTES" -> literal(JdbcValueConversions.toText(HexFormat.of().parseHex(tag.value())));
            case "VARBINARY" -> {
                HexFormat.of().parseHex(tag.value());
                yield "'\\x" + tag.value() + "'";
            }
            default -> throw new IllegalArgumentException("Unsupported TDengine tag encoding");
        };
    }

    /** 规范 TAG 值证据，与类型化游标子表路由共享。 */
    public static String tagEvidence(Object value, String precision, String type) {
        Tag tag = tag(value, precision, type);
        return tag.encoding() + ":" + tag.value();
    }

    /** 已验证 TAG 字面值，与类型化游标子表路由共享。 */
    public static String tagLiteral(Object value, String precision, String type) {
        return tagLiteral(tag(value, precision, type));
    }

    /**
     * 原生数值标签适合整数或 IEEE 范围。toPlainString 前限制指数：。
     */
    private static String numberLiteral(String text) {
        // 原生数值标签适合整数或 IEEE 范围。toPlainString 前限制指数：
        // 否则，即使 1e2147483647 这样的短手工证据值也可能分配数 GB 内存。
        if (text == null || text.length() > 1024) {
            throw new IllegalArgumentException("TDengine numeric tag exceeds its bound");
        }
        var number = new java.math.BigDecimal(text);
        if (number.precision() > 400 || number.scale() < -400 || number.scale() > 400) {
            throw new IllegalArgumentException("TDengine numeric tag exponent exceeds its bound");
        }
        return number.toPlainString();
    }

    /** 原生表计划与旧表 DDL 扩展钩子使用相同注释编码。 */
    public static String commentLiteral(String value) {
        // COMMENT 与字符串数据值不同，逐字存储标记正文：转义的引号和反斜杠
        // 会保留在 table_comment 中。选择未出现的分隔符，绝不转换请求的注释。
        if (value == null || value.length() > 1024 || value.indexOf('\0') >= 0
                || value.indexOf('\'') >= 0 && value.indexOf('"') >= 0) {
            throw new IllegalArgumentException("TDengine comment requires a representable raw quoted token");
        }
        int trailingSlashes = 0;
        for (int i = value.length() - 1; i >= 0 && value.charAt(i) == '\\'; i--) {
            trailingSlashes++;
        }
        if (trailingSlashes % 2 != 0) {
            throw new IllegalArgumentException("TDengine comment cannot end in an unmatched escape");
        }
        String delimiter = value.indexOf('\'') >= 0 ? "\"" : "'";
        return delimiter + value + delimiter;
    }

    /**
     * 返回当前字面量。
     */
    private static String literal(String value) {
        if (value == null || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid TDengine string literal");
        }
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t") + "'";
    }

    /**
     * 根据列定义生成完整列 SQL。
     */
    private static String columnSql(Column column) {
        TdengineJdbcSupport.quote(column.name());
        if (!column.type().matches(
                "(?:TIMESTAMP|BOOL|TINYINT|SMALLINT|INT|BIGINT|FLOAT|DOUBLE|(?:TINYINT|SMALLINT|INT|BIGINT) UNSIGNED|(?:NCHAR|BINARY|VARCHAR|VARBINARY)\\([1-9][0-9]{0,5}\\)|DECIMAL\\([1-9][0-9]?,[0-9]{1,2}\\))")) {
            throw new IllegalArgumentException("TDengine native type needs a verified structure codec");
        }
        var decimal = java.util.regex.Pattern.compile("DECIMAL\\(([0-9]+),([0-9]+)\\)").matcher(column.type());
        if (decimal.matches()) {
            int precision = Integer.parseInt(decimal.group(1)), scale = Integer.parseInt(decimal.group(2));
            if (precision > 38 || scale > precision || column.tag()) {
                throw new IllegalArgumentException("Invalid TDengine decimal structure");
            }
        }
        var length = java.util.regex.Pattern.compile("(NCHAR|BINARY|VARCHAR|VARBINARY)\\(([0-9]+)\\)")
                .matcher(column.type());
        if (length.matches() && Integer.parseInt(length.group(2)) > (length.group(1).equals("NCHAR") ? 16379 : 65517)) {
            throw new IllegalArgumentException("TDengine physical byte capacity exceeds the native bound");
        }
        String sql = TdengineJdbcSupport.quote(
                column.name()) + " " + column.type() + (column.key() ? " COMPOSITE KEY" : "");
        if (!column.tag()) {
            for (String option : List.of(column.encoding(), column.compression(), column.level())) {
                if (!option.matches("[a-z][a-z0-9-]{0,31}")) {
                    throw new IllegalArgumentException("Invalid TDengine column encoding option");
                }
            }
            sql += " ENCODE '" + column.encoding() + "' COMPRESS '" + column.compression() + "' LEVEL '" + column.level() + "'";
        }
        return sql;
    }

    /**
     * 按列顺序生成列定义或列投影 SQL。
     */
    private static String columnsSql(Definition definition) {
        String data = " (" + definition.columns().stream().filter(column -> !column.tag())
                .map(TdengineNativeTables::columnSql).collect(Collectors.joining(",")) + ")";
        return definition.tagColumns().isEmpty() ? data : data + " TAGS (" + definition.tagColumns().stream()
                .map(TdengineNativeTables::columnSql).collect(Collectors.joining(",")) + ")";
    }

    /**
     * 校验并取得精度。
     */
    private static void requirePrecision(Connection connection, String schema, String expected,
            int timeout) throws Exception {
        TdengineJdbcSupport.requireValueDriver(connection);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(timeout);
            try (var rows = statement.executeQuery(
                    "SELECT `precision` FROM information_schema.ins_databases WHERE name=" + literal(schema))) {
                if (!rows.next() || !expected.equals(rows.getString(1)) || rows.next()) {
                    throw new IllegalArgumentException(
                            "TDengine target database must retain source timestamp precision");
                }
            }
        }
    }

    /**
     * 将当前数据编码为字符串。
     */
    private static String encode(Definition definition) {
        return JSONUtil.toJsonStr(
                Map.of("kind", definition.kind(), "parent", definition.parent(), "precision", definition.precision(),
                        "columns", definition.columns().stream()
                                .map(column -> Map.of("name", column.name(), "type", column.type(), "key", column.key(),
                                        "tag", column.tag(),
                                        "encoding", column.encoding(), "compression", column.compression(), "level",
                                        column.level())).toList(),
                        "tags",
                        definition.tags().stream().map(tag -> Map.of("encoding", tag.encoding(), "value", tag.value()))
                                .toList(),
                        "properties",
                        Map.of("comment", definition.properties().comment(), "ttl", definition.properties().ttl()),
                        "parentProperties", Map.of("comment", definition.parentProperties().comment(), "ttl",
                                definition.parentProperties().ttl())));
    }

    /**
     * 从编码数据恢复定义。
     */
    private static Definition decode(String document) {
        if (document.length() > 2_000_000) {
            throw new IllegalArgumentException("TDengine native definition exceeds its bound");
        }
        var json = JSONUtil.parseObj(document);
        var columns = new ArrayList<Column>();
        var tags = new ArrayList<Tag>();
        for (Object entry : json.getJSONArray("columns")) {
            var column = JSONUtil.parseObj(entry);
            columns.add(new Column(column.getStr("name"), column.getStr("type"), column.getBool("key"),
                    column.getBool("tag"),
                    column.getStr("encoding"), column.getStr("compression"), column.getStr("level")));
        }
        for (Object entry : json.getJSONArray("tags")) {
            var tag = JSONUtil.parseObj(entry);
            tags.add(new Tag(tag.getStr("encoding"), tag.getStr("value")));
        }
        var properties = json.getJSONObject("properties");
        var parentProperties = json.getJSONObject("parentProperties");
        return checked(new Definition(json.getStr("kind"), json.getStr("parent"), json.getStr("precision"),
                List.copyOf(columns), List.copyOf(tags),
                new Properties(properties.getStr("comment"), properties.getInt("ttl")),
                new Properties(parentProperties.getStr("comment"), parentProperties.getInt("ttl"))));
    }

    /**
     * 检查定义是否满足当前操作的约定。
     */
    private static Definition checked(Definition definition) {
        if (!Set.of("NORMAL_TABLE", "CHILD_TABLE", "SUPER_TABLE").contains(definition.kind()) || !Set.of("ms", "us",
                "ns").contains(definition.precision())
                || definition.columns().isEmpty() || definition.columns().size() > 4096
                || !definition.columns().getFirst().type().equals("TIMESTAMP") || definition.columns().getFirst().tag()
                || definition.columns().stream().map(Column::name).distinct().count() != definition.columns().size()
                || definition.tagColumns().size() > 128 || definition.columns().stream().filter(Column::key)
                .count() > 1) {
            throw new IllegalArgumentException("Invalid TDengine native table definition");
        }
        if (definition.kind().equals("CHILD_TABLE")) {
            TdengineJdbcSupport.quote(definition.parent());
            if (definition.tags().size() != definition.tagColumns().size()) {
                throw new IllegalArgumentException("TDengine child tag arity differs");
            }
        } else if (!definition.tags().isEmpty() || !definition.parent().isEmpty()) {
            throw new IllegalArgumentException("Invalid TDengine parent/tag ownership");
        }
        if (definition.kind().equals("NORMAL_TABLE") != definition.tagColumns().isEmpty()) {
            throw new IllegalArgumentException("TDengine table kind differs from tag ownership");
        }
        for (int index = 0; index < definition.columns().size(); index++) {
            Column column = definition.columns().get(index);
            if (column.key() && (index != 1 || column.tag() || !column.type()
                    .matches("(?:TINYINT|SMALLINT|INT|BIGINT)(?: UNSIGNED)?|(?:BINARY|VARCHAR)\\([0-9]+\\)"))) {
                throw new IllegalArgumentException("Invalid TDengine composite key column");
            }
        }
        propertiesSql(definition.properties(), definition.kind().equals("SUPER_TABLE"));
        propertiesSql(definition.parentProperties(), true);
        definition.columns().forEach(TdengineNativeTables::columnSql);
        definition.tags().forEach(TdengineNativeTables::tagLiteral);
        return definition;
    }
}
