package cn.iocoder.yudao.framework.dbswitch.product.starrocks;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchValueConverter;
import cn.iocoder.yudao.framework.dbswitch.common.util.JdbcValueConversions;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.util.StarrocksRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.NonTransactionalWriteExecution;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.PartialBatchWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.olap.OlapPartitioning;

import javax.sql.DataSource;
import java.sql.*;
import java.math.*;
import java.lang.reflect.*;
import java.util.*;

/** 原生 StarRocks 目录和值；MySQL 传输类型不是原生类型证据。 */
public final class StarrocksJdbcSupport {

    /** 固定原生配置中，无长度参数的 VARBINARY 上限为 1 MiB。 */
    public static final int MAX_VARBINARY_BYTES = 1048576;

    /**
     * 初始化 {@code StarrocksJdbcSupport}。
     */
    private StarrocksJdbcSupport() {
    }

    public record Column(String name, String nativeType, int jdbcType, int precision, int scale, boolean nullable,
                         String comment) {

        /**
         * 校验构造参数并初始化 {@code Column}。
         */
        public Column {
            quote(name);
            nativeType = nativeSql(nativeType);
            if (comment == null) {
                comment = "";
            }
            String kind = nativeType.contains("(") ? nativeType.substring(0, nativeType.indexOf('(')) : nativeType;
            if (jdbcType != StarrocksJdbcSupport.jdbcType(kind)) {
                throw new IllegalArgumentException("StarRocks native/JDBC types disagree");
            }
            if (kind.equals("decimal") && !nativeType.equals("decimal(" + precision + "," + scale + ")")) {
                throw new IllegalArgumentException("StarRocks decimal precision disagrees");
            }
        }

        /**
         * 返回当前描述。
         */
        public ColumnDescription description() {
            var c = new ColumnDescription();
            c.setFieldName(name);
            c.setLabelName(name);
            c.setDbType(ProductTypeEnum.STARROCKS);
            c.setFieldType(jdbcType);
            c.setFieldTypeName(nativeType);
            c.setPrecisionSize(precision);
            c.setScaleSize(scale);
            c.setDisplaySize(precision);
            c.setSigned(true);
            c.setNullable(nullable);
            c.setRemarks(comment);
            return c;
        }
    }

    public record Index(String name, String column, String type, String comment) {

        /**
         * 校验构造参数并初始化 {@code Index}。
         */
        public Index {
            quote(column);
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || !"BITMAP".equals(
                    type) || comment == null || comment.length() > 1024) {
                throw new IllegalArgumentException("StarRocks index requires a verified rebuild");
            }
        }
    }

    /** 空分布表示 RANDOM；零分桶数表示自动分桶。 */
    public record Table(String identity, String physicalIdentity, String model, List<Column> columns, List<String> keys,
                        List<String> distribution, int buckets, Map<String, String> properties,
                        OlapPartitioning partitioning, List<Index> indexes) {

        /**
         * 使用给定参数初始化 {@code Table}。
         */
        public Table(String identity, String physicalIdentity, String model, List<Column> columns, List<String> keys,
                List<String> distribution, int buckets, Map<String, String> properties) {
            this(identity, physicalIdentity, model, columns, keys, distribution, buckets, properties, null, List.of());
        }

        /**
         * 校验构造参数并初始化 {@code Table}。
         */
        public Table {
            columns = List.copyOf(columns);
            keys = List.copyOf(keys);
            distribution = List.copyOf(distribution);
            properties = Map.copyOf(properties);
            indexes = List.copyOf(indexes);
            if (identity == null || !identity.matches(
                    "[0-9a-f]{64}") || physicalIdentity == null || !physicalIdentity.matches(
                    "[1-9][0-9]{0,18}:[1-9][0-9]{0,18}") || !Set.of("PRIMARY_KEYS", "DUP_KEYS").contains(
                    model) || columns.isEmpty() || columns.size() > 1024 || keys.isEmpty() || distribution.isEmpty() && !model.equals(
                    "DUP_KEYS") || buckets < 0 || buckets > 65536
                    || new HashSet<>(
                    columns.stream().map(Column::name).toList()).size() != columns.size() || new HashSet<>(
                    indexes.stream().map(i -> i.name().toLowerCase(Locale.ROOT)).toList()).size() != indexes.size()) {
                throw new IllegalArgumentException("Invalid StarRocks table shape");
            }
            for (String key : keys) {
                columnOf(columns, key);
            }
            for (String key : distribution) {
                columnOf(columns, key);
            }
            for (Index index : indexes) {
                columnOf(columns, index.column());
            }
            if (partitioning != null) {
                for (String key : partitioning.columns()) {
                    columnOf(columns, key);
                    if (model.equals("PRIMARY_KEYS") && !keys.contains(key)) {
                        throw new IllegalArgumentException("Invalid StarRocks table shape");
                    }
                }
            }
            if (partitioning != null) {
                for (var partition : partitioning.partitions()) {
                    for (var tuple : partition.values()) {
                        for (int i = 0; i < tuple.size(); i++) {
                            if (tuple.get(i) == null && !columnOf(columns, partitioning.columns().get(i)).nullable()) {
                                throw new IllegalArgumentException("Invalid StarRocks table shape");
                            }
                        }
                    }
                }
            }
        }

        /**
         * 返回列。
         */
        public Column column(String name) {
            return columnOf(columns, name);
        }

        /**
         * 读取或构建当前关系及字段的结构信息。
         */
        public Object shape() {
            return Arrays.asList(model, columns, keys, distribution, buckets, properties, partitioning, indexes);
        }
    }

    /**
     * 根据字段引用取得当前列定义。
     */
    static Column columnOf(List<Column> columns, String name) {
        return columns.stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown StarRocks field"));
    }

    /**
     * 按StarRocks方言转义并引用标识符。
     */
    public static String quote(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.STARROCKS, name);
    }

    /**
     * 返回表。
     */
    public static String table(String schema, String table) {
        return quote(schema) + '.' + quote(table);
    }

    /**
     * 读取或组装当前操作所使用的列定义。
     */
    public static String columns(List<String> names) {
        if (names.isEmpty() || names.size() > 1024 || new HashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException("Invalid StarRocks projection");
        }
        return String.join(",", names.stream().map(StarrocksJdbcSupport::quote).toList());
    }

    /**
     * 返回元数据。
     */
    public static Table metadata(Connection c, String schema, String table, int timeout) throws SQLException {
        String identity = StarrocksRelationIdentity.fingerprint(c, schema + '.' + table);
        String model, tableId, partitionKey, bloom = null;
        List<String> distribution, keys;
        int buckets;
        var props = new TreeMap<String, String>();
        try (var p = c.prepareStatement(
                "SELECT TABLE_MODEL,PARTITION_KEY,DISTRIBUTE_KEY,DISTRIBUTE_TYPE,DISTRIBUTE_BUCKET,PROPERTIES,SORT_KEY,PRIMARY_KEY,TABLE_ID FROM information_schema.tables_config WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                if (!r.next() || !Set.of("HASH", "RANDOM").contains(r.getString(4)) || "RANDOM".equals(
                        r.getString(4)) && !r.getString(3).isEmpty()) {
                    throw new SQLException("StarRocks native partition/distribution plan required");
                }
                model = r.getString(1);
                partitionKey = r.getString(2);
                distribution = "RANDOM".equals(r.getString(4)) ? List.of() : identifiers(r.getString(3));
                buckets = r.getInt(5);
                keys = identifiers(r.getString(model.equals("PRIMARY_KEYS") ? 8 : 7));
                tableId = r.getString(9);
                if (model.equals("PRIMARY_KEYS") && !keys.equals(identifiers(r.getString(7)))) {
                    throw new SQLException("StarRocks custom sort keys require explicit preservation");
                }
                var json = cn.hutool.json.JSONUtil.parseObj(r.getString(6));
                for (String key : json.keySet()) {
                    String value = json.getStr(key);
                    if (value != null && key.equals("bloom_filter_columns")) {
                        bloom = value;
                        continue;
                    }
                    if (value == null || (key.equals("bucket_size") ? !value.matches(
                            "[1-9][0-9]{0,18}") : !PROPERTIES.contains(key) || !value.matches("[A-Za-z0-9_]{1,32}"))) {
                        throw new SQLException("StarRocks property requires explicit preservation");
                    }
                    props.put(key, value);
                }
                if (r.next()) {
                    throw new SQLException("Ambiguous StarRocks native table");
                }
            }
        }
        var columns = new ArrayList<Column>();
        // MySQL 兼容的 information_schema 将 LARGEINT 暴露为 bigint unsigned。
        // DESCRIBE 提供原生值域；比较两种观测结果，不作猜测。
        var nativeTypes = new LinkedHashMap<String, String>();
        try (var statement = c.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(1025);
            try (var fields = statement.executeQuery("DESC " + table(schema, table))) {
                while (fields.next()) {
                    if (nativeTypes.size() >= 1024 || nativeTypes.put(fields.getString("Field"),
                            nativeSql(fields.getString("Type"))) != null) {
                        throw new SQLException("StarRocks native type observation is incomplete");
                    }
                }
            }
        }
        var indexes = new ArrayList<Index>();
        try (var statement = c.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(65);
            try (var rows = statement.executeQuery("SHOW INDEX FROM " + table(schema, table))) {
                while (rows.next()) {
                    if (indexes.size() >= 64) {
                        throw new SQLException("StarRocks index budget exceeded");
                    }
                    indexes.add(new Index(rows.getString("Key_name"), rows.getString("Column_name"),
                            rows.getString("Index_type"), Objects.requireNonNullElse(rows.getString("Comment"), "")));
                }
            }
        }
        try (var p = c.prepareStatement(
                "SELECT COLUMN_NAME,DATA_TYPE,COLUMN_TYPE,NUMERIC_PRECISION,NUMERIC_SCALE,CHARACTER_MAXIMUM_LENGTH,DATETIME_PRECISION,IS_NULLABLE,COLUMN_KEY,COLUMN_DEFAULT,EXTRA,GENERATION_EXPRESSION,COLUMN_COMMENT FROM information_schema.columns WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
            p.setQueryTimeout(timeout);
            p.setString(1, schema);
            p.setString(2, table);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    if (columns.size() >= 1024 || r.getString(10) != null || !r.getString(11).isEmpty() || r.getString(
                            12) != null && !r.getString(12).isEmpty()) {
                        throw new SQLException("StarRocks dependent/generated defaults require a native plan");
                    }
                    String name = r.getString(1), wireKind = r.getString(2)
                            .toLowerCase(Locale.ROOT), wireType = r.getString(3).replace(" ", "")
                            .toLowerCase(Locale.ROOT);
                    String type = nativeTypes.get(name);
                    if (type == null) {
                        throw new SQLException("StarRocks native column witness is absent");
                    }
                    String kind = type.contains("(") ? type.substring(0, type.indexOf('(')) : type;
                    boolean largeintAlias = kind.equals("largeint") && wireKind.equals(
                            "bigint unsigned") && wireType.equals("bigint(20)unsigned") && r.getInt(4) == 39;
                    // 只有 BOOLEAN 对应 DESC boolean/目录 tinyint(1)，且数值精度缺失。
                    // 普通 TINYINT(1) 对应 DESC tinyint/目录 tinyint(4)；仍为整数。
                    boolean booleanAlias = kind.equals("boolean") && wireKind.equals("tinyint") && wireType.equals(
                            "tinyint(1)")
                            && r.getObject(4) == null && r.getObject(5) != null && r.getInt(5) == 0 && r.getObject(
                            6) == null;
                    if (!largeintAlias && !booleanAlias && (!wireKind.equals(kind) || !nativeSql(wireType).equals(
                            type))) {
                        throw new SQLException("StarRocks native and wire type observations disagree");
                    }
                    int jdbc = jdbcType(kind), precision = r.getInt(4), scale = r.getInt(5);
                    if (kind.equals("decimal") && (!type.equals(
                            "decimal(" + precision + "," + scale + ")") || precision < 1 || precision > 38 || scale < 0 || scale > precision)) {
                        throw new SQLException("StarRocks decimal evidence disagrees");
                    }
                    if (kind.equals("varchar") || kind.equals("char")) {
                        precision = r.getInt(6);
                        if (!type.equals(kind + '(' + Integer.toString(precision) + ')')) {
                            throw new SQLException("StarRocks character evidence disagrees");
                        }
                    }
                    if (kind.equals("datetime")) {
                        scale = r.getInt(7);
                        if (scale != 6) {
                            throw new SQLException("StarRocks DATETIME precision is not verified");
                        }
                    }
                    if (!Set.of("YES", "NO").contains(r.getString(8))) {
                        throw new SQLException("Unknown StarRocks nullability");
                    }
                    columns.add(new Column(name, nativeSql(type), jdbc, precision, scale, "YES".equals(r.getString(8)),
                            r.getString(13)));
                    if (model.equals("PRIMARY_KEYS") && keys.contains(name) != "PRI".equals(r.getString(9))) {
                        throw new SQLException("StarRocks key evidence disagrees");
                    }
                }
            }
        }
        if (nativeTypes.size() != columns.size()) {
            throw new SQLException("StarRocks native column observations disagree");
        }
        if (bloom != null) {
            props.put("bloom_filter_columns", bloomColumns(bloom, columns.stream().map(Column::name).toList()));
        }
        OlapPartitioning partitioning = partitionKey.isEmpty() ? null : partitioning(c, schema, table, timeout,
                columns);
        if (partitioning != null && !partitioning.columns().equals(identifiers(partitionKey))) {
            throw new SQLException("StarRocks partition observations disagree");
        }
        // SHOW CREATE 仅渲染默认分桶数和未转义字面值；SHOW PARTITIONS 提供
        // 每个分区的分桶数及精确 JSON LIST 值（类型化 RANGE 键），必须与解析结果一致。
        try (var statement = c.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(1026);
            try (var rows = statement.executeQuery("SHOW PARTITIONS FROM " + table(schema, table))) {
                var witnesses = new HashMap<String, String>();
                while (rows.next()) {
                    String name = rows.getString("PartitionName");
                    String witness = partitioning == null ? "" : Objects.requireNonNullElse(
                            rows.getString(partitioning.kind().equals("LIST") ? "List" : "Range"), "");
                    if (witnesses.size() >= 1025 || !"NORMAL".equals(
                            rows.getString("State")) || name == null || witnesses.put(name,
                            witness) != null || buckets > 0 && !Integer.toString(buckets)
                            .equals(rows.getString("Buckets"))) {
                        throw new SQLException("StarRocks partition observations disagree");
                    }
                }
                if (!witnesses.keySet().equals(partitioning == null ? Set.of(table) : partitioning.names())) {
                    throw new SQLException("StarRocks partition observations disagree");
                }
                if (partitioning != null) {
                    for (String name : partitioning.names()) {
                        if (!partitioning.witnessed(name, partitioning.kind().equals("LIST") ? listWitness(
                                        witnesses.get(name)) : OlapPartitioning.keysWitness(witnesses.get(name), "RANGE"),
                                column -> columnOf(columns, column).nativeType())) {
                            throw new SQLException("StarRocks partition values disagree with the catalog witness");
                        }
                    }
                }
            }
        }
        if (!identity.equals(StarrocksRelationIdentity.fingerprint(c, schema + '.' + table))) {
            throw new SQLException("StarRocks table changed while reading native types");
        }
        String cluster = null;
        int nodes = 0;
        try (var statement = c.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(65);
            try (var r = statement.executeQuery("SHOW FRONTENDS")) {
                while (r.next()) {
                    String current = r.getString("ClusterId");
                    if (++nodes > 64 || current == null || cluster != null && !cluster.equals(current)) {
                        throw new SQLException("StarRocks cluster identity is ambiguous");
                    }
                    cluster = current;
                }
            }
        }
        return new Table(identity, cluster + ":" + tableId, model, columns, keys, distribution, buckets, props,
                partitioning, indexes);
    }

    static final Set<String> PROPERTIES = Set.of("compression", "enable_persistent_index", "fast_schema_evolution",
            "replicated_storage", "replication_num");

    /** StarRocks 按请求顺序渲染此集合；列顺序是规范且可比较的形式。 */
    static String bloomColumns(String value, List<String> names) {
        var selected = new HashSet<String>();
        for (String part : value.split(",", -1)) {
            String name = part.trim();
            if (name.isEmpty() || !names.contains(name) || !selected.add(name)) {
                throw new IllegalArgumentException("StarRocks bloom filter columns are not verified");
            }
        }
        return String.join(", ", names.stream().filter(selected::contains).toList());
    }

    /** 仅读取静态 RANGE/LIST 子句；拒绝表达式或自动分区，不执行任何内容。
     * StarRocks 将 NULL LIST 值渲染为 'NULL'：类型化列读为 NULL，文本列有歧义，因此拒绝。 */
    private static OlapPartitioning partitioning(Connection c, String schema, String table, int timeout,
            List<Column> columns) throws SQLException {
        String ddl;
        try (var statement = c.createStatement()) {
            statement.setQueryTimeout(timeout);
            statement.setMaxRows(2);
            try (var rows = statement.executeQuery("SHOW CREATE TABLE " + table(schema, table))) {
                if (!rows.next() || !table.equals(rows.getString(1)) || (ddl = rows.getString(
                        2)) == null || rows.next()) {
                    throw new SQLException("StarRocks native relation is ambiguous");
                }
            }
        }
        int at = ddl.indexOf("\nPARTITION BY ");
        if (at < 0 || ddl.indexOf("\nPARTITION BY ", at + 1) >= 0) {
            throw new SQLException("StarRocks partition clause is ambiguous");
        }
        var parsed = OlapPartitioning.parsePrefix(ddl, at + 1, name -> columnOf(columns, name).nativeType(), true);
        if (!ddl.substring(parsed.end()).stripLeading().startsWith("DISTRIBUTED BY ")) {
            throw new SQLException("StarRocks partition clause is ambiguous");
        }
        return parsed.partitioning();
    }

    /** SHOW PARTITIONS LIST 值是已转义的 JSON 字符串元组。 */
    static List<List<String>> listWitness(String text) {
        try {
            if (text.length() > 4_194_304) {
                throw new IllegalArgumentException();
            }
            var node = new com.fasterxml.jackson.databind.ObjectMapper().enable(
                            com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            if (node == null || !node.isArray() || node.size() > 65536) {
                throw new IllegalArgumentException();
            }
            var result = new ArrayList<List<String>>();
            for (var tuple : node) {
                if (!tuple.isArray() || tuple.size() > 16) {
                    throw new IllegalArgumentException();
                }
                var values = new ArrayList<String>();
                for (var value : tuple) {
                    if (!value.isTextual()) {
                        throw new IllegalArgumentException();
                    }
                    values.add(value.textValue());
                }
                result.add(values);
            }
            return result;
        } catch (Exception failure) {
            throw new IllegalArgumentException("StarRocks partition witness is not an exact value list");
        }
    }

    /**
     * 返回当前标识符集合。
     */
    private static List<String> identifiers(String value) {
        if (value == null || value.length() > 131072) {
            throw new IllegalArgumentException("StarRocks identifier list absent");
        }
        var result = new ArrayList<String>();
        var matcher = java.util.regex.Pattern.compile("`([^`]+)`(?:,\\s*|$)").matcher(value);
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) {
                throw new IllegalArgumentException("Invalid StarRocks identifier list");
            }
            quote(matcher.group(1));
            result.add(matcher.group(1));
            end = matcher.end();
        }
        if (end != value.length() || result.isEmpty()) {
            throw new IllegalArgumentException("Invalid StarRocks identifier list");
        }
        return List.copyOf(result);
    }

    /**
     * 确定当前列或值对应的 JDBC 类型。
     */
    public static int jdbcType(String kind) {
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "boolean" -> Types.BOOLEAN;
            case "tinyint" -> Types.TINYINT;
            case "smallint" -> Types.SMALLINT;
            case "int" -> Types.INTEGER;
            case "bigint" -> Types.BIGINT;
            case "largeint", "decimal" -> Types.DECIMAL;
            case "float" -> Types.REAL;
            case "double" -> Types.DOUBLE;
            case "char" -> Types.CHAR;
            case "varchar" -> Types.VARCHAR;
            case "varbinary" -> Types.VARBINARY;
            case "date" -> Types.DATE;
            case "datetime" -> Types.TIMESTAMP;
            default -> throw new IllegalArgumentException("StarRocks native value type requires a verified codec");
        };
    }

    /**
     * 根据当前产品的原生结构定义生成 SQL。
     */
    public static String nativeSql(String type) {
        String value = type.toLowerCase(Locale.ROOT).replace(" ", "");
        if (value.matches("(?:tinyint|smallint|int|bigint)\\([0-9]{1,2}\\)")) {
            value = value.substring(0, value.indexOf('('));
        }
        if (!value.matches(
                "boolean|tinyint|smallint|int|bigint|largeint|float|double|date|datetime|varbinary|(?:varchar|char)\\([1-9][0-9]{0,6}\\)|decimal\\([1-9][0-9]?,[0-9]{1,2}\\)")) {
            throw new IllegalArgumentException("Unverified StarRocks native type");
        }
        if (value.startsWith("decimal(")) {
            String[] parts = value.substring(8, value.length() - 1).split(",");
            int p = Integer.parseInt(parts[0]), s = Integer.parseInt(parts[1]);
            if (p > 38 || s > p) {
                throw new IllegalArgumentException("StarRocks decimal domain invalid");
            }
        }
        if (value.startsWith("varchar(") || value.startsWith("char(")) {
            int length = Integer.parseInt(value.substring(value.indexOf('(') + 1, value.length() - 1));
            if (length > (value.startsWith("char(") ? 255 : 1048576)) {
                throw new IllegalArgumentException("StarRocks character domain invalid");
            }
        }
        return value;
    }

    /**
     * Connector/J 在 getBigDecimal() 中通过有符号 long 解码 LARGEINT 文本，导致合法的。
     */
    public static ResultSet typedRows(ResultSet rows, List<Column> columns) throws SQLException {
        ResultSetMetaData actual = rows.getMetaData();
        if (actual.getColumnCount() != columns.size()) {
            throw new SQLException("StarRocks projection shape changed");
        }
        for (int i = 0; i < columns.size(); i++) {
            if (!columns.get(i).name().equals(actual.getColumnLabel(i + 1))) {
                throw new SQLException("StarRocks projection order changed");
            }
        }
        var metadata = (ResultSetMetaData) Proxy.newProxyInstance(ResultSetMetaData.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class}, (p, m, a) -> {
                    if (a != null && a.length == 1 && a[0] instanceof Integer i) {
                        Column c = columns.get(i - 1);
                        switch (m.getName()) {
                            case "getColumnType":
                                return c.jdbcType();
                            case "getColumnTypeName":
                                return c.nativeType();
                            case "getPrecision":
                                return c.precision();
                            case "getScale":
                                return c.scale();
                            case "getColumnClassName":
                                if (c.jdbcType() == Types.VARBINARY) {
                                    return byte[].class.getName();
                                }
                        }
                    }
                    return invoke(m, actual, a);
                });
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (p, m, a) -> {
                    if (m.getName().equals("getMetaData")) {
                        return metadata;
                    }
                    if (m.getName().equals("getObject") && a != null && a.length == 1) {
                        int index = a[0] instanceof Integer i ? i : rows.findColumn((String) a[0]);
                        Column col = columns.get(index - 1);
                        if (col.jdbcType() == Types.VARBINARY) {
                            return rows.getBytes(index);
                        }
                        // Connector/J 在 getBigDecimal() 中通过有符号 long 解码 LARGEINT 文本，导致合法的
                        // 低于 Long.MIN_VALUE 的值被拒绝；原生类型选择对未修改文本进行精确且检查范围的解析。
                        if (col.nativeType().equals("largeint")) {
                            String text = rows.getString(index);
                            if (text == null) {
                                return null;
                            }
                            if (!text.matches("-?[0-9]{1,39}")) {
                                throw new SQLException("StarRocks LARGEINT wire representation is invalid");
                            }
                            return convertValue(new BigDecimal(text), col);
                        }
                    }
                    return invoke(m, rows, a);
                });
    }

    /**
     * 调用当前受控运行时的目标方法并传播调用结果。
     */
    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** 借用任何连接或发送任何请求前，先转换整个批次并完成原生分区路由。 */
    public static List<Object[]> convert(List<Object[]> rows, List<String> fields, Table table, BatchLimits limits) {
        columns(fields);
        fields.forEach(table::column);
        var converted = BatchValueConverter.convert(rows, fields.size(), limits,
                (value, index) -> convertValue(value, table.column(fields.get(index))));
        var partitioning = table.partitioning();
        if (partitioning == null) {
            return converted;
        }
        int[] positions = partitioning.columns().stream().mapToInt(name -> {
            int position = fields.indexOf(name);
            if (position < 0) {
                throw new IllegalArgumentException("StarRocks partition columns must be written");
            }
            return position;
        }).toArray();
        for (Object[] row : converted) {
            var key = new ArrayList<Object>(positions.length);
            for (int position : positions) {
                key.add(row[position]);
            }
            partitioning.partitionOf(key, name -> table.column(name).nativeType());
        }
        return converted;
    }

    /**
     * 转换值。
     */
    private static Object convertValue(Object value, Column col) {
        if (value == null) {
            if (!col.nullable()) {
                throw new IllegalArgumentException("Null StarRocks required value");
            }
            return null;
        }
        if (col.jdbcType() == Types.VARBINARY) {
            if (!(value instanceof byte[] bytes)) {
                throw new IllegalArgumentException("StarRocks binary value must be bytes");
            }
            if (bytes.length > MAX_VARBINARY_BYTES) {
                throw new IllegalArgumentException("StarRocks binary value length exceeded");
            }
            return bytes.clone();
        }
        try {
            if (col.nativeType().equals("largeint")) {
                BigInteger integer = JdbcValueConversions.toDecimal(value).toBigIntegerExact();
                if (integer.compareTo(BigInteger.ONE.shiftLeft(127).negate()) < 0 || integer.compareTo(
                        BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE)) > 0) {
                    throw new ArithmeticException();
                }
                return new BigDecimal(integer);
            }
            if (col.nativeType().startsWith("decimal(")) {
                BigDecimal decimal = JdbcValueConversions.toDecimal(value)
                        .setScale(col.scale(), RoundingMode.UNNECESSARY);
                if (decimal.precision() > col.precision()) {
                    throw new ArithmeticException();
                }
                return decimal;
            }
            return switch (col.jdbcType()) {
                case Types.TINYINT -> JdbcValueConversions.toByte(value);
                case Types.SMALLINT -> JdbcValueConversions.toShort(value);
                case Types.INTEGER -> JdbcValueConversions.toInteger(value);
                case Types.BIGINT -> JdbcValueConversions.toLong(value);
                case Types.BOOLEAN -> JdbcValueConversions.toBoolean(value);
                case Types.REAL -> exactFloat(value);
                case Types.DOUBLE -> exactDouble(value);
                case Types.CHAR, Types.VARCHAR -> {
                    if (!(value instanceof String text)) {
                        throw new IllegalArgumentException(
                                "StarRocks character values require explicit text conversion");
                    }
                    var encoder = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
                    int bound = Integer.parseInt(col.nativeType()
                            .substring(col.nativeType().indexOf('(') + 1, col.nativeType().length() - 1));
                    if (encoder.encode(java.nio.CharBuffer.wrap(text)).remaining() > bound) {
                        throw new IllegalArgumentException("StarRocks character byte length exceeded");
                    }
                    yield text;
                }
                case Types.TIMESTAMP -> {
                    java.time.LocalDateTime time = value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : value instanceof java.time.LocalDateTime local ? local : null;
                    if (time == null) {
                        throw new IllegalArgumentException(
                                "StarRocks DATETIME requires a local timestamp with an explicit timezone conversion");
                    }
                    if (time.getNano() % 1000 != 0 || time.getYear() < 1 || time.getYear() > 9999) {
                        throw new IllegalArgumentException("StarRocks DATETIME range or fractional precision exceeded");
                    }
                    yield time;
                }
                case Types.DATE -> {
                    java.time.LocalDate date = value instanceof java.sql.Date day ? day.toLocalDate() : value instanceof java.time.LocalDate local ? local : null;
                    if (date == null || date.getYear() < 1 || date.getYear() > 9999) {
                        throw new IllegalArgumentException("StarRocks DATE requires a native date in range");
                    }
                    yield date;
                }
                default -> throw new IllegalArgumentException("StarRocks native value codec is absent");
            };
        } catch (ArithmeticException | java.nio.charset.CharacterCodingException failure) {
            throw new IllegalArgumentException("StarRocks value cannot be represented without loss");
        }
    }

    /**
     * 检查并转换可由单精度浮点数精确表示的值。
     */
    private static float exactFloat(Object value) {
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException("StarRocks nonfinite FLOAT is unsupported");
            }
            return number;
        }
        if (value instanceof Double number) {
            float result = number.floatValue();
            if (!Float.isFinite(result) || (double) result != number) {
                throw new IllegalArgumentException("StarRocks FLOAT would lose precision");
            }
            return result;
        }
        BigDecimal decimal = JdbcValueConversions.toDecimal(value);
        float result = decimal.floatValue();
        if (!Float.isFinite(result) || new BigDecimal((double) result).compareTo(decimal) != 0) {
            throw new IllegalArgumentException("StarRocks FLOAT requires explicit approximate conversion");
        }
        return result;
    }

    /**
     * 检查并转换可由双精度浮点数精确表示的值。
     */
    private static double exactDouble(Object value) {
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("StarRocks nonfinite DOUBLE is unsupported");
            }
            return number;
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException("StarRocks nonfinite DOUBLE is unsupported");
            }
            return number.doubleValue();
        }
        BigDecimal decimal = JdbcValueConversions.toDecimal(value);
        double result = decimal.doubleValue();
        if (!Double.isFinite(result) || new BigDecimal(result).compareTo(decimal) != 0) {
            throw new IllegalArgumentException("StarRocks DOUBLE requires explicit approximate conversion");
        }
        return result;
    }

    /** 一次确认一个请求；不虚构 JDBC commit/rollback，也不自动重试。 */
    public static long write(DataSource source, String sql, List<Object[]> rows, List<Column> columns, int timeout,
            BatchLimits limits) {
        return write(source, sql, rows, columns, timeout, limits, null);
    }

    /** FULL 追加：捕获的物理表、结构和分区在发送连接上必须仍有效。 */
    public static long write(DataSource source, String sql, List<Object[]> rows, List<Column> columns, int timeout,
            BatchLimits limits, Table captured, String schema, String table) {
        if (!sql.startsWith("INSERT INTO " + table(schema, table) + " ")) {
            throw new IllegalArgumentException("StarRocks insert SQL does not match its native target");
        }
        return write(source, sql, rows, columns, timeout, limits, (connection, prepared) -> {
            var current = metadata(connection, schema, table, timeout);
            if (!captured.physicalIdentity().equals(current.physicalIdentity()) || !captured.shape()
                    .equals(current.shape())) {
                throw new SQLException("StarRocks target changed after prepare");
            }
        });
    }

    /** 在同一连接上确认原生主键 UPDATE/DELETE 的可见状态。 */
    public static long writeMutation(DataSource source, String sql, List<Object[]> rows, List<Column> columns,
            int timeout, BatchLimits limits,
            Table captured, String schema, String table, boolean delete) {
        if (!sql.startsWith((delete ? "DELETE FROM " : "UPDATE ") + table(schema, table) + " ")) {
            throw new IllegalArgumentException("StarRocks mutation SQL does not match its native target");
        }
        return write(source, sql, rows, columns, timeout, limits,
                new MutationVerification(captured, schema, table, columns, delete ? Mutation.DELETE : Mutation.UPDATE,
                        timeout, limits));
    }

    /** DIFF 插入：键必须仍不存在（主键 INSERT 会默默替换），且每行都必须精确回读。 */
    public static long writeDifferenceInsert(DataSource source, String sql, List<Object[]> rows, List<Column> columns,
            int timeout, BatchLimits limits, Table captured, String schema, String table) {
        if (!sql.startsWith("INSERT INTO " + table(schema, table) + " ")) {
            throw new IllegalArgumentException("StarRocks insert SQL does not match its native target");
        }
        return write(source, sql, rows, columns, timeout, limits,
                new MutationVerification(captured, schema, table, columns, Mutation.INSERT, timeout, limits));
    }

    private interface Verification {

        /**
         * 在写入下一批数据前检查当前连接和目标状态。
         */
        void beforeBatch(Connection connection, List<Object[]> rows) throws SQLException;

        /**
         * 重新检查当前对象事实并确认操作结果。
         */
        default void confirm(Connection connection, Object[] expected) throws SQLException {
        }
    }

    private enum Mutation {INSERT, UPDATE, DELETE}

    /**
     * 按当前列结构转换数据，并执行 StarRocks JDBC 写入。
     */
    private static long write(DataSource source, String sql, List<Object[]> rows, List<Column> columns, int timeout,
            BatchLimits limits, Verification verification) {
        var prepared = BatchValueConverter.convert(rows, columns.size(), limits,
                (value, index) -> convertValue(value, columns.get(index)));
        limits.requireStatementParameters(columns.size());
        long[] ack = {0}, sent = {0};
        try {
            return NonTransactionalWriteExecution.execute(source, (connection, attempt) -> {
                if (!connection.getAutoCommit()) {
                    throw new SQLException("StarRocks writer requires its own idle connection");
                }
                try (var control = connection.createStatement()) {
                    control.setQueryTimeout(timeout);
                    control.execute("SET enable_insert_strict=true");
                }
                if (verification != null) {
                    verification.beforeBatch(connection, prepared);
                }
                boolean insert = sql.startsWith("INSERT INTO ");
                int capacity = insert ? limits.maxMultiValueRows(columns.size(), 0) : 1;
                for (int start = 0; start < prepared.size(); start += capacity) {
                    int size = Math.min(capacity, prepared.size() - start);
                    String statementSql = sql;
                    if (insert && size > 1) {
                        int position = sql.indexOf(" VALUES ");
                        String tuple = sql.substring(position + 8);
                        statementSql = sql.substring(0, position + 8) + String.join(",",
                                Collections.nCopies(size, tuple));
                    }
                    try (var p = connection.prepareStatement(statementSql)) {
                        p.setQueryTimeout(timeout);
                        for (int row = 0; row < size; row++) {
                            for (int i = 0; i < columns.size(); i++) {
                                Object value = prepared.get(start + row)[i];
                                int index = row * columns.size() + i + 1;
                                if (value == null) {
                                    p.setNull(index, columns.get(i).jdbcType());
                                } else if (value instanceof byte[] bytes) {
                                    p.setBytes(index, bytes);
                                } else {
                                    p.setObject(index, value, columns.get(i).jdbcType());
                                }
                            }
                        }
                        attempt.sending();
                        sent[0] = size;
                        int count = p.executeUpdate();
                        if (count < 0 || count > size || insert && count != size) {
                            throw new SQLException("StarRocks request acknowledgement mismatch");
                        }
                        // 确认请求中的每一行，不只确认多行 INSERT 的第一行。
                        if (verification != null) {
                            for (int row = 0; row < size; row++) {
                                verification.confirm(connection, prepared.get(start + row));
                            }
                        }
                        ack[0] += size;
                        sent[0] = 0;
                    }
                }
                return ack[0];
            });
        } catch (UnconfirmedWriteException failure) {
            throw new PartialBatchWriteException(ack[0], rows.size() - ack[0] - sent[0], sent[0], false);
        }
    }

    private record MutationVerification(Table captured, String schema, String table, List<Column> columns,
                                        Mutation mutation, int timeout, BatchLimits limits) implements Verification {

        /**
         * 校验构造参数并初始化 {@code MutationVerification}。
         */
        private MutationVerification {
            columns = List.copyOf(columns);
            StarrocksJdbcSupport.table(schema, table);
            if (!captured.model().equals("PRIMARY_KEYS") || !columns.stream().map(Column::name).toList()
                    .containsAll(captured.keys())) {
                throw new IllegalArgumentException("StarRocks mutation requires all native primary keys");
            }
            for (Column column : columns) {
                if (!column.equals(captured.column(column.name()))) {
                    throw new IllegalArgumentException("StarRocks mutation type evidence disagrees");
                }
            }
        }

        /**
         * 在写入下一批数据前检查当前连接和目标状态。
         */
        @Override
        public void beforeBatch(Connection connection, List<Object[]> rows) throws SQLException {
            if (!captured.equals(metadata(connection, schema, table, timeout))) {
                throw new SQLException("StarRocks target identity or structure changed before mutation");
            }
            var seen = new HashSet<List<Object>>();
            for (Object[] row : rows) {
                var key = captured.keys().stream().map(name -> {
                    Object value = row[index(name)];
                    if (value == null) {
                        throw new IllegalArgumentException("Null StarRocks primary key");
                    }
                    return value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value;
                }).toList();
                if (!seen.add(key) || (read(connection, row) == null) != (mutation == Mutation.INSERT)) {
                    throw new SQLException("StarRocks target mutation keys changed or are duplicated");
                }
            }
        }

        /**
         * 重新检查当前对象事实并确认操作结果。
         */
        @Override
        public void confirm(Connection connection, Object[] expected) throws SQLException {
            Object[] actual = read(connection, expected);
            if (mutation == Mutation.DELETE ? actual != null : actual == null || !Arrays.deepEquals(expected, actual)) {
                throw new SQLException("StarRocks mutation is not visible with exact expected values");
            }
        }

        /**
         * 返回索引。
         */
        private int index(String name) {
            for (int index = 0; index < columns.size(); index++) {
                if (columns.get(index).name().equals(name)) {
                    return index;
                }
            }
            throw new IllegalArgumentException("StarRocks key not bound");
        }

        /**
         * 按目标键条件读取当前写入相关的数据行。
         */
        private Object[] read(Connection connection, Object[] key) throws SQLException {
            String selected = StarrocksJdbcSupport.columns(columns.stream().map(Column::name).toList());
            String where = String.join(" AND ", captured.keys().stream().map(name -> quote(name) + "=?").toList());
            try (var statement = connection.prepareStatement(
                    "SELECT " + selected + " FROM " + StarrocksJdbcSupport.table(schema,
                            table) + " WHERE " + where + " LIMIT 2")) {
                statement.setQueryTimeout(timeout);
                statement.setMaxRows(2);
                for (int index = 0; index < captured.keys().size(); index++) {
                    int source = index(captured.keys().get(index));
                    Object value = key[source];
                    if (value instanceof byte[] bytes) {
                        statement.setBytes(index + 1, bytes);
                    } else {
                        statement.setObject(index + 1, value, columns.get(source).jdbcType());
                    }
                }
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        return null;
                    }
                    Object[] values = new Object[columns.size()];
                    for (int index = 0; index < values.length; index++) {
                        values[index] = columns.get(index).jdbcType() == Types.VARBINARY ? rows.getBytes(
                                index + 1) : rows.getObject(index + 1);
                    }
                    if (rows.next()) {
                        throw new SQLException("StarRocks primary key matched multiple rows");
                    }
                    return BatchValueConverter.convert(Collections.singletonList(values), columns.size(), limits,
                            (value, index) -> convertValue(value, columns.get(index))).getFirst();
                }
            }
        }
    }
}
