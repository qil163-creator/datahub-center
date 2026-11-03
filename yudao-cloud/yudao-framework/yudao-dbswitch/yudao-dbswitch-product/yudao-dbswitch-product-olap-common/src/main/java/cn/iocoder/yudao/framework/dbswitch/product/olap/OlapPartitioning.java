package cn.iocoder.yudao.framework.dbswitch.product.olap;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Doris 4.0 和 StarRocks 3.5 共用的静态 RANGE/LIST 分区。边界是规范类型化值，
 * 绝不复制 SQL。两个厂商在 SHOW CREATE 中渲染分区字面值时都不转义，因此含有
 * 分隔符的单个值可能看起来像多个值；每次解析都必须由独立目录
 * 见证（{@link #witnessed}）确认。引号、反斜杠、逗号、']' 和控制字符均拒绝，因为
 * 没有见证能无歧义渲染它们。RANGE 元组中的保留文本 MAXVALUE 是关键字；LIST 元组中的
 * {@code null} 是 NULL 分区值。
 */
public record OlapPartitioning(String kind, List<String> columns, List<Partition> partitions) {

    public static final String MAXVALUE = "MAXVALUE";

    private static final int MAX_PARTITIONS = 1024, MAX_VALUES = 65536;

    private static final Pattern WITNESS_TUPLE = Pattern.compile(
            "types: \\[[A-Za-z0-9_(), ]{1,4096}\\]; keys: \\[([^\\]]*)\\]; ");

    public record Partition(String name, List<List<String>> values) {

        /**
         * 初始化 {@code Partition}。
         */
        public Partition {
            if (name == null || !name.matches(
                    "[A-Za-z][A-Za-z0-9_]{0,63}") || values == null || values.isEmpty() || values.stream()
                    .anyMatch(Objects::isNull)) {
                throw invalid();
            }
            values = values.stream().map(t -> Collections.unmodifiableList(new ArrayList<>(t))).toList();
        }
    }

    public record Parsed(OlapPartitioning partitioning, int end) {

    }

    /**
     * 初始化 {@code OlapPartitioning}。
     */
    public OlapPartitioning {
        columns = List.copyOf(columns);
        partitions = List.copyOf(partitions);
        boolean range = "RANGE".equals(kind);
        if (!range && !"LIST".equals(kind) || columns.isEmpty() || columns.size() > 16 || new HashSet<>(
                columns).size() != columns.size()
                || partitions.isEmpty() || partitions.size() > MAX_PARTITIONS
                || new HashSet<>(
                partitions.stream().map(p -> p.name().toLowerCase(Locale.ROOT)).toList()).size() != partitions.size()) {
            throw invalid();
        }
        long values = 0;
        for (var partition : partitions) {
            if (range && partition.values().size() != 2) {
                throw invalid();
            }
            for (var tuple : partition.values()) {
                if (tuple.size() != columns.size() || range && tuple.stream().anyMatch(Objects::isNull)) {
                    throw invalid();
                }
                values += tuple.size();
            }
        }
        if (values > MAX_VALUES) {
            throw invalid();
        }
    }

    /** 按确定顺序排列的规范类型化字面值：RANGE 按下界排序且不重叠；LIST 按名称排序，元组有序且全局唯一。 */
    public static OlapPartitioning of(String kind, List<String> columns, List<Partition> partitions,
            Function<String, String> typeOf) {
        var domains = columns.stream().map(c -> domain(typeOf.apply(c), kind)).toList();
        boolean range = "RANGE".equals(kind);
        var canonical = new ArrayList<Partition>();
        for (var partition : partitions) {
            var tuples = new ArrayList<List<String>>();
            for (int t = 0; t < partition.values().size(); t++) {
                var raw = partition.values().get(t);
                if (raw.size() != columns.size()) {
                    throw invalid();
                }
                var tuple = new ArrayList<String>();
                for (int i = 0; i < raw.size(); i++) {
                    String text = raw.get(i);
                    if (text == null) {
                        if (range) {
                            throw invalid();
                        }
                        tuple.add(null);
                    } else if (range && MAXVALUE.equals(text)) {
                        if (t == 0) {
                            throw invalid();
                        }
                        tuple.add(MAXVALUE);
                    } else {
                        tuple.add(canonical(domains.get(i), text));
                    }
                }
                tuples.add(tuple);
            }
            if (range) {
                if (tuples.size() != 2 || compare(domains, tuples.get(0), tuples.get(1)) >= 0) {
                    throw invalid();
                }
            } else {
                tuples.sort(OlapPartitioning::compareText);
                for (int i = 1; i < tuples.size(); i++) {
                    if (tuples.get(i).equals(tuples.get(i - 1))) {
                        throw invalid();
                    }
                }
            }
            canonical.add(new Partition(partition.name(), tuples));
        }
        canonical.sort(
                range ? (a, b) -> compare(domains, a.values().getFirst(), b.values().getFirst()) : Comparator.comparing(
                        Partition::name));
        if (range) {
            for (int i = 1; i < canonical.size(); i++) {
                if (compare(domains, canonical.get(i - 1).values().get(1), canonical.get(i).values().getFirst()) > 0) {
                    throw invalid();
                }
            }
        } else {
            var seen = new HashSet<List<String>>();
            for (var partition : canonical) {
                for (var tuple : partition.values()) {
                    if (!seen.add(tuple)) {
                        throw invalid();
                    }
                }
            }
        }
        return new OlapPartitioning(kind, columns, canonical);
    }

    /**
     * 按当前映射定义转换字段、类型或数据结构。
     */
    public OlapPartitioning map(Map<String, String> mapping) {
        if (!mapping.keySet().containsAll(columns)) {
            throw invalid();
        }
        return new OlapPartitioning(kind, columns.stream().map(mapping::get).toList(), partitions);
    }

    /**
     * 校验当前模式名与对象名符合原生标识符约定。
     */
    public Set<String> names() {
        var names = new LinkedHashSet<String>();
        partitions.forEach(p -> names.add(p.name()));
        return names;
    }

    /**
     * 根据当前数据库对象定义生成 DDL 语句。
     */
    public String ddl(Function<String, String> quote) {
        boolean range = "RANGE".equals(kind);
        String items = String.join(", ", partitions.stream().map(p -> "PARTITION " + p.name() + " VALUES " + (range
                ? "[" + tuple(p.values().get(0), true) + ", " + tuple(p.values().get(1), true) + ")"
                : "IN (" + String.join(", ",
                p.values().stream().map(t -> columns.size() == 1 ? literal(t.getFirst(), false) : tuple(t, false))
                        .toList()) + ")")).toList());
        return "PARTITION BY " + kind + "(" + String.join(", ",
                columns.stream().map(quote).toList()) + ") (" + items + ")";
    }

    /**
     * 返回当前元组。
     */
    private static String tuple(List<String> values, boolean range) {
        return "(" + String.join(", ", values.stream().map(v -> literal(v, range)).toList()) + ")";
    }

    /**
     * 返回当前字面量。
     */
    private static String literal(String value, boolean range) {
        return value == null ? "NULL" : range && MAXVALUE.equals(value) ? MAXVALUE : "'" + value + "'";
    }

    /** 发送前路由。NULL RANGE 键按值域最小值排序，与两个服务器一致；NULL LIST 键需要 NULL 分区值。 */
    public String partitionOf(List<Object> values, Function<String, String> typeOf) {
        if (values.size() != columns.size()) {
            throw invalid();
        }
        var domains = columns.stream().map(c -> domain(typeOf.apply(c), kind)).toList();
        boolean range = "RANGE".equals(kind);
        var key = new ArrayList<Object>();
        for (int i = 0; i < columns.size(); i++) {
            Object value = rowValue(domains.get(i), values.get(i));
            key.add(value == null && range ? minimum(domains.get(i)) : value);
        }
        String found = null;
        for (var partition : partitions) {
            boolean member = range ? compareKey(domains, key, partition.values().get(0)) >= 0 && compareKey(domains,
                    key, partition.values().get(1)) < 0
                    : partition.values().stream().anyMatch(t -> sameKey(domains, key, t));
            if (member) {
                if (found != null) {
                    throw new IllegalArgumentException("OLAP partition definitions overlap");
                }
                found = partition.name();
            }
        }
        if (found == null) {
            throw noPartition();
        }
        return found;
    }

    /**
     * 单个分区的独立目录渲染必须精确表示其规范边界或值。
     * 见证文本 NULL 可以表示 NULL 值或相同文本；由主解析决定。
     */
    public boolean witnessed(String name, List<List<String>> witness, Function<String, String> typeOf) {
        var partition = partitions.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
        if (partition == null || witness == null || witness.size() != partition.values().size()) {
            return false;
        }
        var domains = columns.stream().map(c -> domain(typeOf.apply(c), kind)).toList();
        boolean range = "RANGE".equals(kind);
        var expected = new ArrayList<List<String>>();
        var actual = new ArrayList<List<String>>();
        for (var tuple : partition.values()) {
            expected.add(tuple.stream().map(v -> v == null ? "NULL" : v).toList());
        }
        try {
            for (var tuple : witness) {
                if (tuple == null || tuple.size() != columns.size()) {
                    return false;
                }
                var rendered = new ArrayList<String>();
                for (int i = 0; i < tuple.size(); i++) {
                    String text = tuple.get(i);
                    if (text == null) {
                        return false;
                    }
                    rendered.add(range && MAXVALUE.equals(text) || !range && "NULL".equals(text) ? text : canonical(
                            domains.get(i), text));
                }
                actual.add(rendered);
            }
        } catch (IllegalArgumentException failure) {
            return false;
        }
        if (!range) {
            expected.sort(OlapPartitioning::compareText);
            actual.sort(OlapPartitioning::compareText);
        }
        return expected.equals(actual);
    }

    /** SHOW PARTITIONS 的 "[types: [..]; keys: [..]; , ...]"（LIST）或 "[types: ..; keys: [..]; ..types: ..; keys: [..]; )"（RANGE）原始文本。 */
    public static List<List<String>> keysWitness(String text, String kind) {
        boolean range = "RANGE".equals(kind);
        if (text == null || text.length() > 4_194_304 || !text.startsWith("[") || !text.endsWith(range ? ")" : "]")) {
            throw invalid();
        }
        var result = new ArrayList<List<String>>();
        var matcher = WITNESS_TUPLE.matcher(text);
        int at = 1, limit = text.length() - 1;
        while (true) {
            matcher.region(at, limit);
            if (!matcher.lookingAt() || result.size() >= MAX_VALUES) {
                throw invalid();
            }
            result.add(List.of(matcher.group(1).split(", ", -1)));
            at = matcher.end();
            if (at == limit) {
                break;
            }
            String separator = range ? ".." : ", ";
            if (!text.startsWith(separator, at)) {
                throw invalid();
            }
            at += separator.length();
        }
        if (range && result.size() != 2) {
            throw invalid();
        }
        return result;
    }

    /**
     * 解析前缀。
     */
    public static Parsed parsePrefix(String text, int start, Function<String, String> typeOf) {
        return parsePrefix(text, start, typeOf, false);
    }

    /** quotedNull：厂商将 NULL LIST 值渲染为文本 'NULL'（StarRocks）；文本值域中存在歧义，因此拒绝。 */
    public static Parsed parsePrefix(String text, int start, Function<String, String> typeOf, boolean quotedNull) {
        var lexer = new Lexer(text, start);
        lexer.word("PARTITION");
        lexer.word("BY");
        String kind = lexer.bare().toUpperCase(Locale.ROOT);
        if (!"RANGE".equals(kind) && !"LIST".equals(kind)) {
            throw invalid();
        }
        var columns = new ArrayList<String>();
        lexer.require("(");
        do {
            columns.add(lexer.identifier());
            if (columns.size() > 16) {
                throw invalid();
            }
        } while (lexer.take(","));
        lexer.require(")");
        lexer.require("(");
        var partitions = new ArrayList<Partition>();
        do {
            lexer.word("PARTITION");
            String name = lexer.bare();
            lexer.word("VALUES");
            var tuples = new ArrayList<List<String>>();
            if ("RANGE".equals(kind)) {
                lexer.require("[");
                tuples.add(lexer.tuple(true));
                lexer.require(",");
                tuples.add(lexer.tuple(true));
                lexer.require(")");
            } else {
                lexer.word("IN");
                lexer.require("(");
                do {
                    List<String> tuple;
                    if (columns.size() == 1) {
                        tuple = new ArrayList<>();
                        tuple.add(lexer.listValue());
                    } else {
                        tuple = lexer.tuple(false);
                    }
                    tuples.add(tuple);
                    if (tuples.size() > MAX_VALUES) {
                        throw invalid();
                    }
                } while (lexer.take(","));
                lexer.require(")");
            }
            if (quotedNull && "LIST".equals(kind)) {
                for (var tuple : tuples) {
                    for (int i = 0; i < tuple.size(); i++) {
                        if ("NULL".equals(tuple.get(i))) {
                            if ("varchar".equals(domain(typeOf.apply(columns.get(i)), kind))) {
                                throw invalid();
                            }
                            tuple.set(i, null);
                        }
                    }
                }
            }
            partitions.add(new Partition(name, tuples));
            if (partitions.size() > MAX_PARTITIONS) {
                throw invalid();
            }
        } while (lexer.take(","));
        lexer.require(")");
        return new Parsed(of(kind, columns, partitions, typeOf), lexer.at);
    }

    /**
     * 根据当前类型及元数据证据确定允许的值域。
     */
    private static String domain(String type, String kind) {
        if (type == null) {
            throw invalid();
        }
        String base = type.toLowerCase(Locale.ROOT);
        int open = base.indexOf('(');
        if (open >= 0) {
            base = base.substring(0, open);
        }
        if (Set.of("tinyint", "smallint", "int", "bigint", "largeint", "date", "datetime")
                .contains(base) || "LIST".equals(kind) && "varchar".equals(base)) {
            return base;
        }
        throw invalid();
    }

    /**
     * 将{@code domain}整理为当前协议使用的规范形式。
     */
    private static String canonical(String domain, String text) {
        if (text == null || text.length() > 65533 || text.chars()
                .anyMatch(c -> c < 0x20 || c == 0x7f || c == '\'' || c == '"' || c == '\\' || c == ',' || c == ']')) {
            throw invalid();
        }
        return switch (domain) {
            case "date" -> date(text).toString();
            case "datetime" -> format(datetime(text));
            case "varchar" -> text;
            default -> integer(domain, text).toString();
        };
    }

    /**
     * 返回值。
     */
    private static Comparable<?> value(String domain, String canonical) {
        return switch (domain) {
            case "date" -> date(canonical);
            case "datetime" -> datetime(canonical);
            case "varchar" -> canonical;
            default -> integer(domain, canonical);
        };
    }

    /**
     * 按当前值域约定解析或转换日期。
     */
    private static LocalDate date(String text) {
        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw invalid();
        }
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 按当前产品类型约定解析日期时间值。
     */
    private static LocalDateTime datetime(String text) {
        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?")) {
            throw invalid();
        }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T'));
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    /**
     * 返回格式。
     */
    private static String format(LocalDateTime time) {
        String text = "%04d-%02d-%02d %02d:%02d:%02d".formatted(time.getYear(), time.getMonthValue(),
                time.getDayOfMonth(), time.getHour(), time.getMinute(), time.getSecond());
        if (time.getNano() == 0) {
            return text;
        }
        String fraction = "%09d".formatted(time.getNano()).replaceFirst("0+$", "");
        return text + "." + fraction;
    }

    /**
     * 按当前值域约定解析或转换整数。
     */
    private static BigInteger integer(String domain, String text) {
        if (!text.matches("-?[0-9]{1,40}")) {
            throw invalid();
        }
        BigInteger value = new BigInteger(text);
        int bits = bits(domain);
        if (value.compareTo(BigInteger.ONE.shiftLeft(bits).negate()) < 0 || value.compareTo(
                BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)) > 0) {
            throw invalid();
        }
        return value;
    }

    /**
     * 返回当前位数。
     */
    private static int bits(String domain) {
        return switch (domain) {
            case "tinyint" -> 7;
            case "smallint" -> 15;
            case "int" -> 31;
            case "bigint" -> 63;
            default -> 127;
        };
    }

    /**
     * 返回当前最小值。
     */
    private static Comparable<?> minimum(String domain) {
        return switch (domain) {
            case "date" -> LocalDate.of(0, 1, 1);
            case "datetime" -> LocalDateTime.of(0, 1, 1, 0, 0);
            case "varchar" -> throw noPartition();
            default -> BigInteger.ONE.shiftLeft(bits(domain)).negate();
        };
    }

    /**
     * 根据分区键值域转换当前数据行中的字段值。
     */
    private static Object rowValue(String domain, Object value) {
        if (value == null) {
            return null;
        }
        switch (domain) {
            case "date" -> {
                if (value instanceof LocalDate day) {
                    return day;
                }
                if (value instanceof java.sql.Date day) {
                    return day.toLocalDate();
                }
            }
            case "datetime" -> {
                if (value instanceof LocalDateTime time) {
                    return time;
                }
                if (value instanceof java.sql.Timestamp time) {
                    return time.toLocalDateTime();
                }
            }
            case "varchar" -> {
                if (value instanceof String text) {
                    return text;
                }
            }
            default -> {
                if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                    return BigInteger.valueOf(((Number) value).longValue());
                }
                if (value instanceof BigInteger integer) {
                    return integer;
                }
                if (value instanceof BigDecimal decimal) {
                    try {
                        return decimal.toBigIntegerExact();
                    } catch (ArithmeticException ignored) {
                    }
                }
            }
        }
        throw new IllegalArgumentException("OLAP partition key value type is not verified");
    }

    /**
     * 比较键。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareKey(List<String> domains, List<Object> key, List<String> bound) {
        for (int i = 0; i < key.size(); i++) {
            if (MAXVALUE.equals(bound.get(i)) && !"varchar".equals(domains.get(i))) {
                return -1;
            }
            int result = ((Comparable) key.get(i)).compareTo(value(domains.get(i), bound.get(i)));
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    /**
     * 按分区键类型比较当前数据键与边界元组是否相同。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean sameKey(List<String> domains, List<Object> key, List<String> tuple) {
        for (int i = 0; i < key.size(); i++) {
            Object value = key.get(i);
            String bound = tuple.get(i);
            if (value == null || bound == null) {
                if (value != null || bound != null) {
                    return false;
                }
                continue;
            }
            if (((Comparable) value).compareTo(value(domains.get(i), bound)) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 返回当前比较。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(List<String> domains, List<String> left, List<String> right) {
        for (int i = 0; i < left.size(); i++) {
            boolean leftMax = MAXVALUE.equals(left.get(i)), rightMax = MAXVALUE.equals(right.get(i));
            if (leftMax || rightMax) {
                if (leftMax && rightMax) {
                    continue;
                }
                return leftMax ? 1 : -1;
            }
            int result = ((Comparable) value(domains.get(i), left.get(i))).compareTo(
                    value(domains.get(i), right.get(i)));
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    /**
     * 比较文本。
     */
    private static int compareText(List<String> left, List<String> right) {
        for (int i = 0; i < left.size(); i++) {
            String a = left.get(i), b = right.get(i);
            if (Objects.equals(a, b)) {
                continue;
            }
            if (a == null || b == null) {
                return a == null ? -1 : 1;
            }
            return a.compareTo(b);
        }
        return 0;
    }

    /**
     * 根据当前输入构建并返回 {@code IllegalArgumentException}。
     */
    private static IllegalArgumentException noPartition() {
        return new IllegalArgumentException("OLAP row has no native target partition");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("OLAP partitions require a verified static RANGE/LIST definition");
    }

    private static final class Lexer {

        private final String text;

        private int at;

        /**
         * 初始化 {@code Lexer}。
         */
        Lexer(String text, int start) {
            if (text == null || text.length() > 1_048_576 || start < 0 || start > text.length()) {
                throw invalid();
            }
            this.text = text;
            this.at = start;
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
         * 按当前解析位置读取或匹配一个词元。
         */
        void word(String word) {
            if (!bare().equalsIgnoreCase(word)) {
                throw invalid();
            }
        }

        /**
         * 检查或转换当前数据库标识符。
         */
        String identifier() {
            space();
            if (at < text.length() && text.charAt(at) == '`') {
                return quoted('`');
            }
            return bare();
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

        /** LIST 值：带引号字面值，或表示 NULL 值的未加引号 NULL 关键字。 */
        String listValue() {
            space();
            if (at < text.length() && (text.charAt(at) == '\'' || text.charAt(at) == '"')) {
                return string();
            }
            if (!"NULL".equalsIgnoreCase(bare())) {
                throw invalid();
            }
            return null;
        }

        /**
         * 返回当前元组。
         */
        List<String> tuple(boolean range) {
            require("(");
            var values = new ArrayList<String>();
            do {
                if (range) {
                    space();
                    int save = at;
                    String value = null;
                    try {
                        if ("MAXVALUE".equalsIgnoreCase(bare())) {
                            value = MAXVALUE;
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                    if (value == null) {
                        at = save;
                        value = string();
                    }
                    values.add(value);
                } else {
                    values.add(listValue());
                }
                if (values.size() > 16) {
                    throw invalid();
                }
            } while (take(","));
            require(")");
            return values;
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
                if (c == '\0' || result.length() > 65536) {
                    throw invalid();
                }
                result.append(c);
            }
            throw invalid();
        }
    }
}
