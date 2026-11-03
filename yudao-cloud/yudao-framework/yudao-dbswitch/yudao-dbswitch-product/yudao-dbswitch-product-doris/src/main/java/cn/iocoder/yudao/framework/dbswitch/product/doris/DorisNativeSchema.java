package cn.iocoder.yudao.framework.dbswitch.product.doris;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.olap.OlapPartitioning;

import java.sql.Types;
import java.util.*;

/** 有界 Doris SHOW CREATE 语法。目录文本解析为值，绝不作为原始 SQL 重命名。 */
public final class DorisNativeSchema {

    /**
     * 初始化 {@code DorisNativeSchema}。
     */
    private DorisNativeSchema() {
    }

    private static final Set<String> AGGREGATIONS = Set.of("SUM", "MAX", "MIN", "REPLACE", "REPLACE_IF_NOT_NULL");

    public record Column(String name, String type, boolean nullable, String defaultSql, String comment,
                         String aggregation) {

        /**
         * 使用给定参数初始化 {@code Column}。
         */
        public Column(String name, String type, boolean nullable, String defaultSql, String comment) {
            this(name, type, nullable, defaultSql, comment, null);
        }

        /**
         * 校验构造参数并初始化 {@code Column}。
         */
        public Column {
            quote(name);
            type = DorisNativeSchema.type(type);
            Objects.requireNonNull(comment);
            if (defaultSql != null && !defaultSql.equals("NULL") && !isLiteral(defaultSql)) {
                throw invalid();
            }
            if (aggregation != null && !AGGREGATIONS.contains(aggregation)) {
                throw invalid();
            }
        }

        /**
         * 根据当前输入判定类型或操作种类。
         */
        public String kind() {
            int p = type.indexOf('(');
            return p < 0 ? type : type.substring(0, p);
        }

        /**
         * 返回精度。
         */
        public int precision() {
            return type.indexOf('(') < 0 ? 0 : Integer.parseInt(
                    type.substring(type.indexOf('(') + 1, type.contains(",") ? type.indexOf(',') : type.length() - 1));
        }

        /**
         * 返回小数位数。
         */
        public int scale() {
            return kind().equals("datetime") ? precision() : type.contains(",") ? Integer.parseInt(
                    type.substring(type.indexOf(',') + 1, type.length() - 1)) : 0;
        }

        /**
         * 校验原生列报告的 JDBC 类型是否属于允许范围。
         */
        public int jdbc() {
            return switch (kind()) {
                case "boolean" -> Types.BOOLEAN;
                case "tinyint" -> Types.TINYINT;
                case "smallint" -> Types.SMALLINT;
                case "int" -> Types.INTEGER;
                case "bigint" -> Types.BIGINT;
                case "largeint", "decimal" -> Types.DECIMAL;
                case "float" -> Types.REAL;
                case "double" -> Types.DOUBLE;
                case "char" -> Types.CHAR;
                case "varchar", "string" -> Types.VARCHAR;
                case "date" -> Types.DATE;
                case "datetime" -> Types.TIMESTAMP;
                case "json" -> Types.LONGVARCHAR;
                default -> throw invalid();
            };
        }

        /**
         * 返回当前描述。
         */
        public ColumnDescription description() {
            var d = new ColumnDescription();
            d.setFieldName(name);
            d.setLabelName(name);
            d.setDbType(ProductTypeEnum.DORIS);
            d.setFieldType(jdbc());
            d.setFieldTypeName(type);
            d.setPrecisionSize(kind().equals("largeint") ? 39 : precision());
            d.setScaleSize(scale());
            d.setDisplaySize(precision());
            d.setNullable(nullable);
            d.setSigned(true);
            d.setRemarks(comment);
            d.setDefaultValue(defaultSql);
            return d;
        }
    }

    /** 仅接受服务器已验证的索引属性：SHOW CREATE 渲染值时不转义，因此自由文本（字符过滤器）无法无歧义回读。 */
    private static final Map<String, Map<String, String>> INDEX_PROPERTIES = Map.of("INVERTED",
            Map.of("parser", "[a-z_]{1,32}", "parser_mode", "[a-z_]{1,32}", "support_phrase", "true|false",
                    "lower_case", "true|false", "ignore_above", "[1-9][0-9]{0,8}"),
            "NGRAM_BF", Map.of("gram_size", "[1-9][0-9]{0,3}", "bf_size", "[1-9][0-9]{0,8}"));

    /** 单列 INVERTED/NGRAM_BF 索引；其他索引族没有已验证的重建能力。 */
    public record Index(String name, List<String> columns, String type, Map<String, String> properties,
                        String comment) {

        /**
         * 校验构造参数并初始化 {@code Index}。
         */
        public Index {
            columns = List.copyOf(columns);
            properties = Collections.unmodifiableMap(new TreeMap<>(properties));
            Objects.requireNonNull(comment);
            if (name == null || !name.matches(
                    "[A-Za-z_][A-Za-z0-9_]{0,63}") || columns.size() != 1 || type == null || !INDEX_PROPERTIES.containsKey(
                    type)) {
                throw invalid();
            }
            for (var e : properties.entrySet()) {
                String pattern = INDEX_PROPERTIES.get(type).get(e.getKey());
                if (pattern == null || !e.getValue().matches(pattern)) {
                    throw invalid();
                }
            }
        }

        /**
         * 根据当前数据库对象定义生成 DDL 语句。
         */
        String ddl() {
            return "INDEX " + name + " (" + names(
                    columns) + ") USING " + type + (properties.isEmpty() ? "" : " PROPERTIES(" + String.join(", ",
                    properties.entrySet().stream().map(e -> literal(e.getKey()) + " = " + literal(e.getValue()))
                            .toList()) + ")") + " COMMENT " + literal(comment);
        }
    }

    /** 空分布表示 RANDOM；零分桶数表示 AUTO。不重现动态分区调度器。 */
    public record Table(List<Column> columns, String model, List<String> keys, List<String> distribution, int buckets,
                        Map<String, String> properties, String comment, OlapPartitioning partitioning,
                        List<Index> indexes) {

        /**
         * 使用给定参数初始化 {@code Table}。
         */
        public Table(List<Column> columns, String model, List<String> keys, List<String> distribution, int buckets,
                Map<String, String> properties, String comment) {
            this(columns, model, keys, distribution, buckets, properties, comment, null, List.of());
        }

        /**
         * 校验构造参数并初始化 {@code Table}。
         */
        public Table {
            columns = List.copyOf(columns);
            keys = List.copyOf(keys);
            distribution = List.copyOf(distribution);
            indexes = List.copyOf(indexes);
            var props = new TreeMap<>(properties);
            Objects.requireNonNull(comment);
            if (columns.isEmpty() || columns.size() > 1024 || new HashSet<>(
                    columns.stream().map(Column::name).toList()).size() != columns.size() || !Set.of("UNIQUE",
                    "DUPLICATE", "AGGREGATE").contains(model) || keys.isEmpty() || buckets < 0 || buckets > 65536) {
                throw invalid();
            }
            if (keys.size() > columns.size() || !columns.stream().map(Column::name).toList().subList(0, keys.size())
                    .equals(keys)) {
                throw invalid();
            }
            var names = columns.stream().map(Column::name).toList();
            for (String key : distribution) {
                if (!names.contains(key)) {
                    throw invalid();
                }
            }
            if (model.equals("UNIQUE") && (distribution.isEmpty() || !keys.containsAll(distribution))) {
                throw invalid();
            }
            for (int i = 0; i < columns.size(); i++) {
                if (model.equals("AGGREGATE") ? (i < keys.size()) == (columns.get(i)
                        .aggregation() != null) : columns.get(i).aggregation() != null) {
                    throw invalid();
                }
            }
            if (partitioning != null) {
                for (String key : partitioning.columns()) {
                    if (!names.contains(key) || !model.equals("DUPLICATE") && !keys.contains(key)) {
                        throw invalid();
                    }
                }
            }
            if (partitioning != null) {
                for (var partition : partitioning.partitions()) {
                    for (var tuple : partition.values()) {
                        for (int i = 0; i < tuple.size(); i++) {
                            if (tuple.get(i) == null && !columns.get(names.indexOf(partitioning.columns().get(i)))
                                    .nullable()) {
                                throw invalid();
                            }
                        }
                    }
                }
            }
            for (Index index : indexes) {
                if (!names.containsAll(index.columns())) {
                    throw invalid();
                }
            }
            if (new HashSet<>(
                    indexes.stream().map(i -> i.name().toLowerCase(Locale.ROOT)).toList()).size() != indexes.size()) {
                throw invalid();
            }
            for (var e : props.entrySet()) {
                if (!e.getKey().matches("[a-z][a-z0-9_.]{0,127}") || e.getValue().length() > 65536 || e.getKey()
                        .startsWith("dynamic_partition.")) {
                    throw invalid();
                }
            }
            String bloom = props.get("bloom_filter_columns");
            if (bloom != null) {
                props.put("bloom_filter_columns", bloomColumns(bloom, names));
            }
            properties = Collections.unmodifiableMap(props);
        }

        /**
         * 返回列。
         */
        public Column column(String name) {
            return columns.stream().filter(c -> c.name().equals(name)).findFirst()
                    .orElseThrow(DorisNativeSchema::invalid);
        }

        /**
         * 返回当前唯一。
         */
        public boolean unique() {
            return model.equals("UNIQUE");
        }

        /**
         * 按当前映射定义转换字段、类型或数据结构。
         */
        public Table map(Map<String, String> mapping) {
            if (!mapping.keySet().containsAll(keys) || !mapping.keySet().containsAll(distribution) || !columns.stream()
                    .map(Column::name).toList().containsAll(mapping.keySet())) {
                throw invalid();
            }
            var props = new TreeMap<>(properties);
            String sequence = props.get("function_column.sequence_col");
            if (sequence != null) {
                if (!mapping.containsKey(sequence)) {
                    throw invalid();
                }
                props.put("function_column.sequence_col", mapping.get(sequence));
            }
            String bloom = props.get("bloom_filter_columns");
            if (bloom != null) {
                props.put("bloom_filter_columns", String.join(", ",
                        Arrays.stream(bloom.split(", ")).map(name -> required(mapping, name)).toList()));
            }
            return new Table(columns.stream().filter(c -> mapping.containsKey(c.name()))
                    .map(c -> new Column(mapping.get(c.name()), c.type(), c.nullable(), c.defaultSql(), c.comment(),
                            c.aggregation())).toList(), model, keys.stream().map(mapping::get).toList(),
                    distribution.stream().map(mapping::get).toList(), buckets, props, comment,
                    partitioning == null ? null : partitioning.map(mapping), indexes.stream()
                    .map(i -> new Index(i.name(), i.columns().stream().map(name -> required(mapping, name)).toList(),
                            i.type(), i.properties(), i.comment())).toList());
        }

        /**
         * 根据当前数据库对象定义生成 DDL 语句。
         */
        public String ddl(String schema, String name) {
            var definitions = new ArrayList<>(columns.stream().map(c -> quote(
                    c.name()) + " " + c.type() + (c.aggregation() == null ? "" : " " + c.aggregation()) + (c.nullable() ? " NULL" : " NOT NULL") + (c.defaultSql() == null ? "" : " DEFAULT " + c.defaultSql()) + " COMMENT " + columnCommentLiteral(
                    c.comment())).toList());
            indexes.forEach(i -> definitions.add(i.ddl()));
            String text = "CREATE TABLE " + relation(schema, name) + " (" + String.join(",",
                    definitions) + ") ENGINE=OLAP " + model + " KEY(" + names(keys) + ") COMMENT " + literal(
                    comment) + (partitioning == null ? "" : " " + partitioning.ddl(DorisNativeSchema::quote))
                    + " DISTRIBUTED BY " + (distribution.isEmpty() ? "RANDOM" : "HASH(" + names(
                    distribution) + ")") + " BUCKETS " + (buckets == 0 ? "AUTO" : Integer.toString(
                    buckets)) + " PROPERTIES(" + String.join(",",
                    properties.entrySet().stream().map(e -> literal(e.getKey()) + "=" + literal(e.getValue()))
                            .toList()) + ")";
            if (text.length() > 1_048_576) {
                throw invalid();
            }
            return text;
        }
    }

    /**
     * 返回当前必填。
     */
    private static String required(Map<String, String> mapping, String name) {
        String mapped = mapping.get(name);
        if (mapped == null) {
            throw invalid();
        }
        return mapped;
    }

    /** Doris 按哈希顺序渲染此集合；列顺序是规范且可比较的形式。 */
    private static String bloomColumns(String value, List<String> names) {
        var selected = new HashSet<String>();
        for (String part : value.split(",", -1)) {
            String name = part.trim();
            if (name.isEmpty() || !names.contains(name) || !selected.add(name)) {
                throw invalid();
            }
        }
        return String.join(", ", names.stream().filter(selected::contains).toList());
    }

    /**
     * 按Doris方言转义并引用标识符。
     */
    public static String quote(String value) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DORIS, value);
    }

    /**
     * 生成模式与表组成的限定关系名。
     */
    public static String relation(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }

    /**
     * 校验当前模式名与对象名符合原生标识符约定。
     */
    public static String names(List<String> names) {
        if (names.isEmpty() || names.size() > 1024 || new HashSet<>(names).size() != names.size()) {
            throw invalid();
        }
        return String.join(",", names.stream().map(DorisNativeSchema::quote).toList());
    }

    /**
     * 返回当前字面量。
     */
    public static String literal(String value) {
        if (value == null || value.length() > 65536 || value.indexOf('\0') >= 0) {
            throw invalid();
        }
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t") + "'";
    }

    /** Doris 列 COMMENT 会移除每个反斜杠；控制字符必须按字面写入。 */
    public static String columnCommentLiteral(String value) {
        if (value == null || value.length() > 65536 || value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0) {
            throw invalid();
        }
        return "'" + value.replace("'", "\\'") + "'";
    }

    /**
     * 判断字面量。
     */
    private static boolean isLiteral(String value) {
        try {
            var p = new Parser(value);
            p.string();
            return p.end();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 返回类型。
     */
    public static String type(String value) {
        String result = Objects.requireNonNull(value).toLowerCase(Locale.ROOT).replace(" ", "")
                .replaceFirst("^decimalv3", "decimal").replaceFirst("^datetimev2", "datetime")
                .replaceFirst("^datev2$", "date");
        if (result.equals("datetime")) {
            result = "datetime(0)";
        }
        // Doris 4.0 SHOW CREATE 和 DESC 将物理 STRING 列渲染为 TEXT。
        if (result.equals("text")) {
            result = "string";
        }
        if (!result.matches(
                "boolean|tinyint|smallint|int|bigint|largeint|float|double|string|date|datetime\\([0-6]\\)|json|(?:char|varchar)\\([1-9][0-9]{0,7}\\)|decimal\\([1-9][0-9]?,[0-9]{1,2}\\)")) {
            throw invalid();
        }
        if (result.startsWith("decimal(")) {
            String[] sizes = result.substring(8, result.length() - 1).split(",");
            int precision = Integer.parseInt(sizes[0]), scale = Integer.parseInt(sizes[1]);
            if (precision > 38 || scale > precision) {
                throw invalid();
            }
        }
        if (result.startsWith("char(") || result.startsWith("varchar(")) {
            int size = Integer.parseInt(result.substring(result.indexOf('(') + 1, result.length() - 1));
            if (size > (result.startsWith("char(") ? 255 : 65533)) {
                throw invalid();
            }
        }
        return result;
    }

    /**
     * 返回当前解析。
     */
    public static Table parse(String ddl) {
        return parse(ddl, null, null);
    }

    /** Doris SHOW CREATE 渲染部分注释时不做 SQL 转义。独立目录值
     * 界定这些精确数据片段；不执行任意原始 SQL 替换或重放。 */
    public static Table parseCatalog(String ddl, String tableComment, Map<String, String> columnComments) {
        Objects.requireNonNull(tableComment);
        Objects.requireNonNull(columnComments);
        return parse(ddl, tableComment, Map.copyOf(columnComments));
    }

    /**
     * 返回当前解析。
     */
    private static Table parse(String ddl, String tableComment, Map<String, String> columnComments) {
        var p = new Parser(ddl);
        p.word("CREATE");
        p.word("TABLE");
        p.identifier();
        if (p.take(".")) {
            p.identifier();
        }
        p.require("(");
        var cols = new ArrayList<Column>();
        var indexes = new ArrayList<Index>();
        do {
            if (p.takeWord("INDEX")) {
                indexes.add(p.index());
                if (indexes.size() > 64) {
                    throw invalid();
                }
                continue;
            }
            if (!indexes.isEmpty()) {
                throw invalid();
            }
            String name = p.identifier(), type = p.bare();
            if (p.take("(")) {
                type += "(" + p.number();
                if (p.take(",")) {
                    type += "," + p.number();
                }
                p.require(")");
                type += ")";
            }
            String aggregation = null;
            for (String candidate : AGGREGATIONS) {
                if (p.takeWord(candidate)) {
                    aggregation = candidate;
                    break;
                }
            }
            boolean nullable = true;
            if (p.takeWord("NOT")) {
                p.word("NULL");
                nullable = false;
            } else {
                p.takeWord("NULL");
            }
            String defaultSql = null;
            if (p.takeWord("DEFAULT")) {
                defaultSql = p.takeWord("NULL") ? "NULL" : literal(p.string());
            }
            String comment = p.takeWord("COMMENT") ? (columnComments == null ? p.string() : p.catalogString(
                    columnComments.get(name))) : "";
            if (columnComments != null && !comment.equals(columnComments.get(name))) {
                throw invalid();
            }
            cols.add(new Column(name, type, nullable, defaultSql, comment, aggregation));
            if (cols.size() > 1024) {
                throw invalid();
            }
        } while (p.take(","));
        if (columnComments != null && columnComments.size() != cols.size()) {
            throw invalid();
        }
        p.require(")");
        p.word("ENGINE");
        p.require("=");
        p.word("OLAP");
        String model = p.bare().toUpperCase(Locale.ROOT);
        p.word("KEY");
        var keys = p.identifiers();
        String comment = p.takeWord("COMMENT") ? (tableComment == null ? p.string() : p.catalogString(
                tableComment)) : "";
        if (tableComment != null && !comment.equals(tableComment)) {
            throw invalid();
        }
        OlapPartitioning partitioning = null;
        if (p.peekWord("PARTITION")) {
            var parsed = OlapPartitioning.parsePrefix(p.text, p.at,
                    name -> cols.stream().filter(c -> c.name().equals(name)).findFirst()
                            .orElseThrow(DorisNativeSchema::invalid).type());
            p.at = parsed.end();
            partitioning = parsed.partitioning();
        }
        p.word("DISTRIBUTED");
        p.word("BY");
        List<String> distribution = List.of();
        if (!p.takeWord("RANDOM")) {
            p.word("HASH");
            distribution = p.identifiers();
        }
        p.word("BUCKETS");
        int buckets = 0;
        if (!p.takeWord("AUTO") && (buckets = p.number()) < 1) {
            throw invalid();
        }
        p.word("PROPERTIES");
        p.require("(");
        var properties = new TreeMap<String, String>();
        do {
            String key = p.string();
            p.require("=");
            String value = p.string();
            if (properties.put(key, value) != null || properties.size() > 128) {
                throw invalid();
            }
        } while (p.take(","));
        p.require(")");
        p.take(";");
        if (!p.end()) {
            throw invalid();
        }
        return new Table(cols, model, keys, distribution, buckets, properties, comment, partitioning, indexes);
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Doris native structure requires a verified bounded definition");
    }

    private static final class Parser {

        private final String text;

        private int at;

        /**
         * 初始化 {@code Parser}。
         */
        Parser(String text) {
            if (text == null || text.length() > 1_048_576) {
                throw invalid();
            }
            this.text = text;
        }

        /**
         * 跳过当前解析位置的空白字符。
         */
        void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        /**
         * 返回当前终点。
         */
        boolean end() {
            space();
            return at == text.length();
        }

        /**
         * 消费当前解析位置要求的字符或词元。
         */
        boolean take(String token) {
            space();
            if (text.startsWith(token, at)) {
                at += token.length();
                return true;
            }
            return false;
        }

        /**
         * 检查令牌是否满足当前操作的约定。
         */
        void require(String token) {
            if (!take(token)) {
                throw invalid();
            }
        }

        /**
         * 读取不带引号的标识符词元。
         */
        String bare() {
            space();
            int start = at;
            while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) {
                at++;
            }
            if (start == at) {
                throw invalid();
            }
            return text.substring(start, at);
        }

        /**
         * 尝试匹配并消费指定词元。
         */
        boolean takeWord(String word) {
            int save = at;
            try {
                if (bare().equalsIgnoreCase(word)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
            }
            at = save;
            return false;
        }

        /**
         * 探测下一个词元是否匹配，随后恢复解析位置。
         */
        boolean peekWord(String word) {
            int save = at;
            boolean found = takeWord(word);
            at = save;
            return found;
        }

        /**
         * 按当前解析位置读取或匹配一个词元。
         */
        void word(String word) {
            if (!takeWord(word)) {
                throw invalid();
            }
        }

        /**
         * 按当前值域约定解析或转换数值。
         */
        int number() {
            String value = bare();
            if (!value.matches("[0-9]{1,8}")) {
                throw invalid();
            }
            return Integer.parseInt(value);
        }

        /**
         * 检查或转换当前数据库标识符。
         */
        String identifier() {
            space();
            String result;
            if (at < text.length() && text.charAt(at) == '`') {
                result = quoted('`');
            } else {
                result = bare();
            }
            quote(result);
            return result;
        }

        /**
         * 按当前值域约定解析或转换字符串。
         */
        String string() {
            space();
            if (at >= text.length() || text.charAt(at) != '\'' && text.charAt(at) != '"') {
                throw invalid();
            }
            return quoted(text.charAt(at));
        }

        /**
         * SHOW CREATE 即使在单引号表注释内也会转义双引号，。
         */
        String catalogString(String known) {
            if (known == null || known.length() > 65536) {
                throw invalid();
            }
            space();
            if (at >= text.length() || text.charAt(at) != '\'' && text.charAt(at) != '"') {
                throw invalid();
            }
            char delimiter = text.charAt(at);
            // SHOW CREATE 即使在单引号表注释内也会转义双引号，
            // 而单引号、反斜杠和换行保留原样。仅接受独立读取的完整值。
            for (String body : List.of(known, known.replace("\"", "\\\""))) {
                String raw = delimiter + body + delimiter;
                if (text.startsWith(raw, at)) {
                    at += raw.length();
                    return known;
                }
            }
            String parsed = string();
            if (!known.equals(parsed)) {
                throw invalid();
            }
            return parsed;
        }

        /**
         * 返回按当前方言加引号后的标识符。
         */
        String quoted(char delimiter) {
            at++;
            var result = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == delimiter) {
                    if (at < text.length() && text.charAt(at) == delimiter) {
                        at++;
                        result.append(c);
                        continue;
                    }
                    return result.toString();
                }
                if (c == '\\') {
                    if (at == text.length()) {
                        throw invalid();
                    }
                    char next = text.charAt(at++);
                    c = switch (next) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '\\', '\'', '"', '`' -> next;
                        default -> throw invalid();
                    };
                }
                if (c == '\0') {
                    throw invalid();
                }
                result.append(c);
            }
            throw invalid();
        }

        /**
         * 返回当前标识符集合。
         */
        List<String> identifiers() {
            require("(");
            var result = new ArrayList<String>();
            do {
                result.add(identifier());
                if (result.size() > 1024) {
                    throw invalid();
                }
            } while (take(","));
            require(")");
            names(result);
            return result;
        }

        /**
         * 返回索引。
         */
        Index index() {
            String name = bare();
            var columns = identifiers();
            word("USING");
            String type = bare().toUpperCase(Locale.ROOT);
            var properties = new TreeMap<String, String>();
            if (takeWord("PROPERTIES")) {
                require("(");
                do {
                    String key = string();
                    require("=");
                    if (properties.put(key, string()) != null || properties.size() > 64) {
                        throw invalid();
                    }
                } while (take(","));
                require(")");
            }
            return new Index(name, columns, type, properties, takeWord("COMMENT") ? string() : "");
        }
    }
}
