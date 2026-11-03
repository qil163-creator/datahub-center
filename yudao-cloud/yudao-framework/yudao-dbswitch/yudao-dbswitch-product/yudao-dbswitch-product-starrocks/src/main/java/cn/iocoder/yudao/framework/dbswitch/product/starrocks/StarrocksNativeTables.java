package cn.iocoder.yudao.framework.dbswitch.product.starrocks;

import cn.hutool.json.*;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.product.olap.OlapPartitioning;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksJdbcSupport.*;

/** 结构化 PRIMARY/DUPLICATE OLAP 定义；绝不执行任意 SHOW CREATE 文本。 */
public final class StarrocksNativeTables {

    private static final String FORMAT_V1 = "STARROCKS_35_NATIVE_HEAP_V1", FORMAT = "STARROCKS_35_NATIVE_TABLE_V2";

    /**
     * 初始化 {@code StarrocksNativeTables}。
     */
    private StarrocksNativeTables() {
    }

    /** 使用与结构计划相同的原生解码器，不涉及连接或 DDL。 */
    public static void preflightStreamLoad(NativeTableDefinition definition, Map<String, String> mapping) {
        if (definition.product() != ProductTypeEnum.STARROCKS || !FORMAT.equals(
                definition.format()) && !FORMAT_V1.equals(definition.format())) {
            throw new IllegalArgumentException("StarRocks native source definition required");
        }
        Table source = decode(definition.document(), FORMAT.equals(definition.format()));
        if (!source.columns().stream().map(Column::name).toList().equals(definition.fields()) || mapping.isEmpty()
                || !definition.fields().containsAll(mapping.keySet()) || !mapping.keySet().containsAll(source.keys())
                || !mapping.keySet().containsAll(source.distribution()) || new HashSet<>(
                mapping.values()).size() != mapping.size()) {
            throw new IllegalArgumentException("StarRocks native Stream Load fields disagree");
        }
        mapping.values().forEach(StarrocksJdbcSupport::quote);
        if (source.partitioning() != null) {
            source.partitioning().map(mapping);
        }
        for (Index index : source.indexes()) {
            mapped(mapping, index.column());
        }
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    public static NativeTableDefinition describe(DataSource source, String schema, String table, int timeout) {
        try (var c = source.getConnection()) {
            var first = metadata(c, schema, table, timeout);
            String comment = comment(c, schema, table, timeout);
            var second = metadata(c, schema, table, timeout);
            if (!first.equals(second) || !comment.equals(comment(c, schema, table, timeout))) {
                throw new SQLException("StarRocks structure changed during capture");
            }
            return new NativeTableDefinition(ProductTypeEnum.STARROCKS, FORMAT, encode(first, comment),
                    first.columns().stream().map(Column::name).toList());
        } catch (Exception failure) {
            throw new IllegalStateException("StarRocks native structure capture failed");
        }
    }

    /**
     * 返回计划。
     */
    public static NativeTargetPlan plan(DataSource target, NativeTableDefinition definition, String schema,
            String table, Map<String, String> mapping, boolean replace, int timeout) {
        table(schema, table);
        if (definition.product() != ProductTypeEnum.STARROCKS || !FORMAT.equals(
                definition.format()) && !FORMAT_V1.equals(definition.format())) {
            throw new IllegalArgumentException("StarRocks native source definition required");
        }
        Table source = decode(definition.document(), FORMAT.equals(definition.format()));
        // V2 捕获精确表注释；旧版 V1 计划从未携带表注释，保持原比较范围。
        String comment = FORMAT.equals(definition.format()) ? documentComment(definition.document()) : null;
        if (!source.columns().stream().map(Column::name).toList().equals(definition.fields())) {
            throw new IllegalArgumentException("StarRocks native definition fields disagree");
        }
        if (mapping.isEmpty() || !definition.fields().containsAll(mapping.keySet()) || !mapping.keySet()
                .containsAll(source.keys()) || !mapping.keySet().containsAll(source.distribution())) {
            throw new IllegalArgumentException("StarRocks native keys and distribution columns must be mapped");
        }
        var columns = source.columns().stream().filter(c -> mapping.containsKey(c.name()))
                .map(c -> new Column(mapping.get(c.name()), c.nativeType(), c.jdbcType(), c.precision(), c.scale(),
                        c.nullable(), c.comment())).toList();
        var properties = new TreeMap<>(source.properties());
        String bloom = properties.get("bloom_filter_columns");
        if (bloom != null) {
            properties.put("bloom_filter_columns", bloomColumns(
                    String.join(", ", Arrays.stream(bloom.split(", ")).map(name -> mapped(mapping, name)).toList()),
                    columns.stream().map(Column::name).toList()));
        }
        Table desired = new Table(source.identity(), source.physicalIdentity(), source.model(), columns,
                source.keys().stream().map(mapping::get).toList(),
                source.distribution().stream().map(mapping::get).toList(), source.buckets(), properties,
                source.partitioning() == null ? null : source.partitioning().map(mapping), source.indexes().stream()
                .map(i -> new Index(i.name(), mapped(mapping, i.column()), i.type(), i.comment())).toList());
        Table observed;
        String observedComment;
        try (var c = target.getConnection()) {
            observed = existing(c, schema, table, timeout);
            observedComment = observed == null ? null : comment(c, schema, table, timeout);
            if (observed != null && (source.physicalIdentity()
                    .equals(observed.physicalIdentity()) || !replace && (!observed.shape()
                    .equals(desired.shape()) || comment != null && !comment.equals(observedComment)))) {
                throw new SQLException("StarRocks target is source or differs from planned shape");
            }
        } catch (Exception failure) {
            throw new IllegalStateException("StarRocks target structure preflight failed");
        }
        String ddl = ddl(schema, table, desired, comment), digest = cn.hutool.crypto.digest.DigestUtil.sha256Hex(
                definition.document() + "\0" + ddl + "\0" + replace);
        return new NativeTargetPlan() {
            private final AtomicBoolean used = new AtomicBoolean();

            private volatile String identity = observed == null ? null : observed.physicalIdentity();

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
                return observed != null && replace;
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
                return List.of(new NativeRecoveryResource(ProductTypeEnum.STARROCKS, schema, table,
                        NativeRecoveryResource.Role.TARGET, identity));
            }

            /**
             * 在当前数据库连接上执行给定 SQL 语句。
             */
            @Override
            public void execute() {
                if (!used.compareAndSet(false, true)) {
                    throw new IllegalStateException("StarRocks native plan is single-use");
                }
                NonTransactionalWriteExecution.execute(target, (c, a) -> {
                    if (!c.getAutoCommit()) {
                        throw new SQLException("StarRocks native DDL requires idle connection");
                    }
                    var current = existing(c, schema, table, timeout);
                    if (!Objects.equals(observed, current) || !Objects.equals(observedComment,
                            current == null ? null : comment(c, schema, table, timeout))) {
                        throw new SQLException("StarRocks target changed after preflight");
                    }
                    if (current != null && replace) {
                        StarrocksNativeTables.execute(c, a, "DROP TABLE " + table(schema, table), timeout);
                    }
                    if (current == null || replace) {
                        StarrocksNativeTables.execute(c, a, ddl, timeout);
                        var created = metadata(c, schema, table, timeout);
                        identity = created.physicalIdentity();
                        if (!desired.shape().equals(created.shape()) || comment != null && !comment.equals(
                                comment(c, schema, table, timeout))) {
                            throw new SQLException("StarRocks created structure differs from native source");
                        }
                    }
                    return 0;
                });
            }
        };
    }

    /**
     * 将当前对象或数据行按既定映射转换为目标形式。
     */
    private static String mapped(Map<String, String> mapping, String name) {
        String value = mapping.get(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "StarRocks native partition, index and bloom filter columns must be mapped");
        }
        return value;
    }

    /**
     * 返回当前已有。
     */
    private static Table existing(Connection c, String schema, String table, int timeout) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT TABLE_TYPE,ENGINE FROM information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    return null;
                }
                if (!"BASE TABLE".equals(r.getString(1)) || !"StarRocks".equals(r.getString(2)) || r.next()) {
                    throw new SQLException("StarRocks target namespace is not a native table");
                }
            }
        }
        return metadata(c, schema, table, timeout);
    }

    /** 精确表注释；不使用 SHOW CREATE，因为它渲染注释时不转义。 */
    private static String comment(Connection c, String schema, String table, int timeout) throws SQLException {
        try (var p = c.prepareStatement(
                "SELECT TABLE_COMMENT FROM information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            p.setQueryTimeout(timeout);
            p.setMaxRows(2);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                if (!r.next()) {
                    throw new SQLException("StarRocks table comment is absent");
                }
                String comment = Objects.requireNonNullElse(r.getString(1), "");
                if (r.next() || comment.length() > 65536) {
                    throw new SQLException("StarRocks table comment is ambiguous");
                }
                return comment;
            }
        }
    }

    /**
     * 根据当前数据库对象定义生成 DDL 语句。
     */
    private static String ddl(String schema, String table, Table definition, String comment) {
        var definitions = new ArrayList<>(definition.columns().stream().map(c -> quote(c.name()) + ' ' + nativeSql(
                c.nativeType()) + (c.nullable() ? " NULL" : " NOT NULL") + " COMMENT " + text(c.comment())).toList());
        definition.indexes().forEach(i -> definitions.add(
                "INDEX " + i.name() + " (" + quote(i.column()) + ") USING " + i.type() + " COMMENT " + text(
                        i.comment())));
        String properties = String.join(",",
                definition.properties().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(e -> text(e.getKey()) + '=' + text(e.getValue())).toList());
        String sql = "CREATE TABLE " + table(schema, table) + "(" + String.join(",",
                definitions) + ") ENGINE=OLAP " + (definition.model()
                .equals("PRIMARY_KEYS") ? "PRIMARY" : "DUPLICATE") + " KEY(" + columns(
                definition.keys()) + ")" + (comment == null || comment.isEmpty() ? "" : " COMMENT " + text(comment))
                + (definition.partitioning() == null ? "" : " " + definition.partitioning()
                .ddl(StarrocksJdbcSupport::quote)) + " DISTRIBUTED BY " + (definition.distribution()
                .isEmpty() ? "RANDOM" : "HASH(" + columns(definition.distribution()) + ")")
                + (definition.buckets() == 0 ? "" : " BUCKETS " + definition.buckets()) + " PROPERTIES(" + properties + ")";
        if (sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1_048_576) {
            throw new IllegalArgumentException("StarRocks DDL exceeds byte budget");
        }
        return sql;
    }

    /**
     * 按当前值域约定解析或转换文本。
     */
    private static String text(String text) {
        if (text == null || text.indexOf('\0') >= 0 || text.indexOf('\\') >= 0 || text.length() > 65536) {
            throw new IllegalArgumentException("StarRocks DDL text requires verified literal encoding");
        }
        return '\'' + text.replace("'", "''") + '\'';
    }

    /**
     * 在当前数据库连接上执行给定 SQL 语句。
     */
    private static void execute(Connection c, NonTransactionalWriteExecution.Attempt a, String sql,
            int timeout) throws SQLException {
        try (var s = c.createStatement()) {
            s.setQueryTimeout(timeout);
            a.sending();
            s.execute(sql);
        }
    }

    /** NULL LIST 分区值是 JSON null；默认配置会默默将其从元组移除。 */
    private static JSONConfig nulls() {
        return JSONConfig.create().setIgnoreNullValue(false);
    }

    /**
     * 将当前数据编码为字符串。
     */
    private static String encode(Table table, String comment) {
        var json = new JSONObject(nulls()).set("comment", Objects.requireNonNull(comment))
                .set("identity", table.identity()).set("physicalIdentity", table.physicalIdentity())
                .set("model", table.model()).set("keys", table.keys()).set("distribution", table.distribution())
                .set("buckets", table.buckets()).set("properties", table.properties());
        var fields = table.columns().stream()
                .map(c -> new JSONObject(nulls()).set("name", c.name()).set("type", c.nativeType())
                        .set("jdbc", c.jdbcType()).set("precision", c.precision()).set("scale", c.scale())
                        .set("nullable", c.nullable()).set("comment", c.comment())).toList();
        var partitioning = table.partitioning();
        if (partitioning != null) {
            json.set("partitioning",
                    new JSONObject(nulls()).set("kind", partitioning.kind()).set("columns", partitioning.columns())
                            .set("partitions", partitioning.partitions().stream()
                                    .map(p -> new JSONObject(nulls()).set("name", p.name()).set("values", p.values()))
                                    .toList()));
        }
        json.set("indexes", table.indexes().stream()
                .map(i -> new JSONObject(nulls()).set("name", i.name()).set("column", i.column()).set("type", i.type())
                        .set("comment", i.comment())).toList());
        String result = json.set("columns", fields).toString();
        if (result.length() > 1_048_576) {
            throw new IllegalArgumentException("StarRocks native definition exceeds budget");
        }
        return result;
    }

    /**
     * 生成原生结构文档中保存注释所需的定义。
     */
    private static String documentComment(String text) {
        Object comment = JSONUtil.parseObj(text, nulls()).get("comment");
        if (!(comment instanceof String value) || value.length() > 65536) {
            throw new IllegalArgumentException("StarRocks native table comment is absent");
        }
        return value;
    }

    /**
     * 从编码数据恢复表。
     */
    private static Table decode(String text, boolean extended) {
        if (text == null || text.length() > 1_048_576) {
            throw new IllegalArgumentException("StarRocks native definition exceeds budget");
        }
        var json = JSONUtil.parseObj(text, nulls());
        var fields = json.getJSONArray("columns");
        if (fields == null || fields.isEmpty() || fields.size() > 1024) {
            throw new IllegalArgumentException("StarRocks native columns absent");
        }
        if (!extended && (json.containsKey("partitioning") || json.containsKey("indexes"))) {
            throw new IllegalArgumentException("StarRocks native definition format disagrees");
        }
        var columns = new ArrayList<Column>();
        for (int i = 0; i < fields.size(); i++) {
            var c = fields.getJSONObject(i);
            String type = c.getStr("type"), kind = type.contains("(") ? type.substring(0, type.indexOf('(')) : type;
            if (!(c.get("nullable") instanceof Boolean) || c.getInt("jdbc") != jdbcType(kind)) {
                throw new IllegalArgumentException("StarRocks native column type disagrees");
            }
            columns.add(new Column(c.getStr("name"), type, c.getInt("jdbc"), c.getInt("precision"), c.getInt("scale"),
                    c.getBool("nullable"), c.getStr("comment")));
        }
        var names = columns.stream().map(Column::name).toList();
        var props = new TreeMap<String, String>();
        var properties = json.getJSONObject("properties");
        for (String key : properties.keySet()) {
            String value = properties.getStr(key);
            boolean verified = extended && key.equals("bloom_filter_columns") ? value != null && bloomColumns(value,
                    names).equals(value) : extended && key.equals("bucket_size") ? value != null && value.matches(
                    "[1-9][0-9]{0,18}") : PROPERTIES.contains(key) && value != null && value.matches(
                    "[A-Za-z0-9_]{1,32}");
            if (!verified) {
                throw new IllegalArgumentException("Unverified StarRocks property");
            }
            props.put(key, value);
        }
        OlapPartitioning partitioning = null;
        var partitionJson = json.getJSONObject("partitioning");
        if (partitionJson != null) {
            var partitions = new ArrayList<OlapPartitioning.Partition>();
            var items = partitionJson.getJSONArray("partitions");
            if (items == null || items.size() > 1024) {
                throw new IllegalArgumentException("StarRocks native partitions absent");
            }
            for (int i = 0; i < items.size(); i++) {
                var item = items.getJSONObject(i);
                var tuples = item.getJSONArray("values");
                var values = new ArrayList<List<String>>();
                for (int t = 0; t < tuples.size(); t++) {
                    values.add(tuples.getJSONArray(t).toList(String.class));
                }
                partitions.add(new OlapPartitioning.Partition(item.getStr("name"), values));
            }
            partitioning = OlapPartitioning.of(partitionJson.getStr("kind"),
                    partitionJson.getJSONArray("columns").toList(String.class), partitions,
                    name -> columnOf(columns, name).nativeType());
        }
        var indexes = new ArrayList<Index>();
        var indexJson = json.getJSONArray("indexes");
        if (indexJson != null) {
            if (indexJson.size() > 64) {
                throw new IllegalArgumentException("StarRocks index budget exceeded");
            }
            for (int i = 0; i < indexJson.size(); i++) {
                var item = indexJson.getJSONObject(i);
                indexes.add(new Index(item.getStr("name"), item.getStr("column"), item.getStr("type"),
                        item.getStr("comment")));
            }
        }
        return new Table(json.getStr("identity"), json.getStr("physicalIdentity"), json.getStr("model"), columns,
                json.getJSONArray("keys").toList(String.class), json.getJSONArray("distribution").toList(String.class),
                json.getInt("buckets"), props, partitioning, indexes);
    }
}
