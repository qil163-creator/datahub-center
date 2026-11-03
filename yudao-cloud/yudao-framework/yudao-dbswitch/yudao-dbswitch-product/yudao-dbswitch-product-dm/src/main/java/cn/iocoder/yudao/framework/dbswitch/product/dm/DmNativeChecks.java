package cn.iocoder.yudao.framework.dbswitch.product.dm;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.Token;
import cn.iocoder.yudao.framework.dbswitch.core.util.DmSqlNames;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** 表内 CHECK 条件；目录 SQL 经过解析与渲染，绝不按原始输入直接执行。 */
public final class DmNativeChecks {

    private static final int MAX_CHECKS = 256, MAX_TEXT = 16384, MAX_NODES = 512, MAX_DEPTH = 32;

    public record Entry(String sourceName, String expression, Set<String> columns, boolean enabled) {

        /**
         * 初始化 {@code Entry}。
         */
        public Entry {
            DmNativeIndexes.name(sourceName);
            columns = Set.copyOf(columns);
            var compiled = compile(expression, columns, Map.of());
            if (!compiled.columns().equals(columns)) {
                throw invalid();
            }
            expression = compiled.sql();
        }

        /**
         * 读取或构建当前关系及字段的结构信息。
         */
        String shape() {
            return expression + '\0' + enabled;
        }

        /**
         * 将当前对象或数据行按既定映射转换为目标形式。
         */
        Entry mapped(Map<String, String> mapping) {
            if (!mapping.keySet().containsAll(columns)) {
                throw new IllegalArgumentException("DM mapping must retain every CHECK column");
            }
            var compiled = compile(expression, columns, mapping);
            return new Entry(sourceName, compiled.sql(), compiled.columns(), enabled);
        }
    }

    public record Catalog(List<Entry> entries) {

        /**
         * 初始化 {@code Catalog}。
         */
        public Catalog {
            entries = List.copyOf(entries);
            if (entries.size() > MAX_CHECKS || entries.stream().map(Entry::sourceName).distinct()
                    .count() != entries.size()) {
                throw invalid();
            }
        }

        /**
         * 校验当前模式名与对象名符合原生标识符约定。
         */
        Set<String> names() {
            return new HashSet<>(entries.stream().map(Entry::sourceName).toList());
        }

        /**
         * 读取或构建当前关系及字段的结构信息。
         */
        List<String> shape() {
            return entries.stream().map(Entry::shape).sorted().toList();
        }

        /**
         * 将当前对象或数据行按既定映射转换为目标形式。
         */
        Catalog mapped(Map<String, String> mapping) {
            return new Catalog(entries.stream().map(e -> e.mapped(mapping)).toList());
        }
    }

    private record Expression(String sql, Set<String> columns) {

    }

    private record Quoted(String value, boolean literal) {

    }

    private record Input(String sql, Map<String, Quoted> quoted) {

    }

    /**
     * 初始化 {@code DmNativeChecks}。
     */
    private DmNativeChecks() {
    }

    /**
     * 读取表内 CHECK 约束及其已观测状态。
     */
    static Catalog read(Connection c, String schema, String table, Set<String> fields,
            int timeout) throws SQLException {
        var entries = new ArrayList<Entry>();
        try (var p = DmNativeIndexes.prepare(c,
                "SELECT constraint_name,status,validated,deferrable,deferred,search_condition,index_owner,index_name "
                        + "FROM ALL_CONSTRAINTS WHERE owner=? AND table_name=? AND constraint_type='C' ORDER BY constraint_name",
                timeout, schema, table); var r = p.executeQuery()) {
            while (r.next()) {
                String name = r.getString(1), status = r.getString(2), validated = r.getString(3);
                boolean enabled = "ENABLED".equals(status);
                if (entries.size() >= MAX_CHECKS || !(enabled && "VALIDATED".equals(validated) || "DISABLED".equals(
                        status) && "NOT VALIDATED".equals(validated))
                        || !"NOT DEFERRABLE".equals(r.getString(4)) || !"IMMEDIATE".equals(
                        r.getString(5)) || r.getString(7) != null || r.getString(8) != null) {
                    throw unsupported();
                }
                var columns = new HashSet<String>();
                try (var cp = DmNativeIndexes.prepare(c,
                        "SELECT column_name,position FROM ALL_CONS_COLUMNS WHERE owner=? AND table_name=? AND constraint_name=?",
                        timeout, schema, table, name); var cr = cp.executeQuery()) {
                    while (cr.next()) {
                        // DM CHECK 列引用是无序集合，POSITION 为 NULL，与主键或唯一键不同。
                        String field = cr.getString(1);
                        if (columns.size() >= 1024 || !fields.contains(field) || !columns.add(field) || cr.getObject(
                                2) != null) {
                            throw unsupported();
                        }
                    }
                }
                entries.add(new Entry(name, r.getString(6), columns, enabled));
            }
        }
        return new Catalog(entries);
    }

    /**
     * 读取当前表的主键、唯一键及相关约束定义。
     */
    static List<String> tableConstraints(Catalog catalog, String schema, String table) {
        return catalog.entries().stream().map(e -> "CONSTRAINT " + DmSqlNames.quote(
                        targetName(e, schema, table)) + " CHECK (" + e.expression() + ")" + (e.enabled() ? "" : " DISABLE"))
                .toList();
    }

    /**
     * 按目标配置生成当前对象的名称。
     */
    public static String targetName(Entry e, String schema, String table) {
        return "DBSWCK_" + DigestUtil.sha256Hex(schema + '\0' + table + '\0' + e.sourceName()).substring(0, 40)
                .toUpperCase(Locale.ROOT);
    }

    /**
     * 检查名称集合可用。
     */
    static void assertNamesAvailable(Connection c, Catalog catalog, String schema, String table, boolean owns,
            int timeout) throws SQLException {
        for (var e : catalog.entries()) {
            try (var p = DmNativeIndexes.prepare(c,
                    "SELECT table_name FROM ALL_CONSTRAINTS WHERE owner=? AND constraint_name=?", timeout, schema,
                    targetName(e, schema, table)); var r = p.executeQuery()) {
                if (r.next() && (!owns || !table.equals(r.getString(1)) || r.next())) {
                    throw new SQLException("DM planned CHECK name is occupied");
                }
            }
        }
    }

    /**
     * 将当前数据编码为JSON对象。
     */
    static JSONObject encode(Catalog catalog) {
        return new JSONObject().set("entries", catalog.entries().stream()
                .map(e -> new JSONObject().set("name", e.sourceName()).set("expression", e.expression())
                        .set("columns", e.columns().stream().sorted().toList()).set("enabled", e.enabled())).toList());
    }

    /**
     * 从编码数据恢复目录。
     */
    static Catalog decode(JSONObject root) {
        if (root == null || root.getJSONArray("entries") == null || root.getJSONArray("entries").size() > MAX_CHECKS) {
            throw invalid();
        }
        var entries = new ArrayList<Entry>();
        for (Object value : root.getJSONArray("entries")) {
            if (!(value instanceof JSONObject e) || !(e.get("name") instanceof String name) || !(e.get(
                    "expression") instanceof String expression)
                    || !(e.get("enabled") instanceof Boolean enabled) || e.getJSONArray(
                    "columns") == null || e.getJSONArray("columns").size() > 1024) {
                throw invalid();
            }
            var columns = new HashSet<String>();
            for (Object column : e.getJSONArray("columns")) {
                if (!(column instanceof String field) || !columns.add(field)) {
                    throw invalid();
                }
            }
            entries.add(new Entry(name, expression, columns, enabled));
        }
        return new Catalog(entries);
    }

    /**
     * 返回当前编译。
     */
    private static Expression compile(String source, Set<String> fields, Map<String, String> mapping) {
        Input input = quotedInput(source);
        var parser = SQLParserUtils.createExprParser(input.sql(), DbType.dm);
        SQLExpr ast = parser.expr();
        if (parser.getLexer().token() != Token.EOF) {
            throw invalid();
        }
        var renderer = new Renderer(input.quoted(), fields, mapping);
        String sql = renderer.predicate(ast, 0);
        if (sql.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT) {
            throw invalid();
        }
        return new Expression(sql, Set.copyOf(renderer.columns));
    }

    /** Druid 的 DM 访问器将标识符中的双重引号改为反斜杠；语法解析之前
     * 先保留带引号令牌，仅按 DM 标识符及字面量规则渲染已解码令牌。 */
    private static Input quotedInput(String source) {
        if (source == null || source.isBlank() || source.getBytes(
                StandardCharsets.UTF_8).length > MAX_TEXT || source.indexOf('\0') >= 0) {
            throw invalid();
        }
        String prefix = "DBSW_CHECK_TOKEN_";
        while (source.toUpperCase(Locale.ROOT).contains(prefix)) {
            prefix += 'X';
        }
        var tokens = new HashMap<String, Quoted>();
        var sql = new StringBuilder();
        int depth = 0, lexemes = 0;
        for (int i = 0; i < source.length(); ) {
            char ch = source.charAt(i++);
            if (Character.isWhitespace(ch)) {
                sql.append(ch);
                continue;
            }
            if (++lexemes > MAX_NODES) {
                throw invalid();
            }
            if (ch == '\'' || ch == '"') {
                var value = new StringBuilder();
                boolean closed = false;
                while (i < source.length()) {
                    char next = source.charAt(i++);
                    if (next == ch) {
                        if (i < source.length() && source.charAt(i) == ch) {
                            value.append(ch);
                            i++;
                        } else {
                            closed = true;
                            break;
                        }
                    } else {
                        value.append(next);
                    }
                }
                if (!closed) {
                    throw invalid();
                }
                if (ch == '"') {
                    DmNativeIndexes.name(value.toString());
                }
                String key = prefix + tokens.size();
                tokens.put(key, new Quoted(value.toString(), ch == '\''));
                sql.append(' ').append(key).append(' ');
                continue;
            }
            // 此适配器不实现 SQL 语法；交给 Druid 之前先拒绝非令牌修饰。
            if (ch == ';' || ch == '\\' || ch == '`' || ch == '[' || ch == ']' || ch == '?' || ch == ':' || ch == '#'
                    || ch == '/' && i < source.length() && source.charAt(
                    i) == '*' || ch == '-' && i < source.length() && source.charAt(i) == '-') {
                throw invalid();
            }
            if (ch == '(' && ++depth > MAX_DEPTH || ch == ')' && --depth < 0) {
                throw invalid();
            }
            sql.append(ch);
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '$') {
                while (i < source.length()) {
                    char next = source.charAt(i);
                    if (!Character.isLetterOrDigit(next) && next != '_' && next != '$') {
                        break;
                    }
                    sql.append(next);
                    i++;
                }
            }
        }
        if (depth != 0) {
            throw invalid();
        }
        return new Input(sql.toString(), Map.copyOf(tokens));
    }

    private static final class Renderer {

        final Map<String, Quoted> quoted;

        final Set<String> fields, columns = new HashSet<>();

        final Map<String, String> mapping;

        int nodes;

        /**
         * 初始化 {@code Renderer}。
         */
        Renderer(Map<String, Quoted> quoted, Set<String> fields, Map<String, String> mapping) {
            this.quoted = quoted;
            this.fields = fields;
            this.mapping = mapping;
        }

        /**
         * 增加当前解析深度和节点计数，并拒绝超过语法预算的输入。
         */
        void enter(int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
                throw invalid();
            }
        }

        /**
         * 返回谓词。
         */
        String predicate(SQLExpr expr, int depth) {
            enter(depth);
            if (expr instanceof SQLBinaryOpExpr binary) {
                var op = binary.getOperator();
                if (op == SQLBinaryOperator.BooleanAnd || op == SQLBinaryOperator.BooleanOr) {
                    return '(' + predicate(binary.getLeft(),
                            depth + 1) + (op == SQLBinaryOperator.BooleanAnd ? " AND " : " OR ") + predicate(
                            binary.getRight(), depth + 1) + ')';
                }
                String text = switch (op) {
                    case Equality -> " = ";
                    case NotEqual, LessThanOrGreater -> " <> ";
                    case GreaterThan -> " > ";
                    case GreaterThanOrEqual -> " >= ";
                    case LessThan -> " < ";
                    case LessThanOrEqual -> " <= ";
                    case Is -> " IS ";
                    case IsNot -> " IS NOT ";
                    default -> throw invalid();
                };
                if ((op == SQLBinaryOperator.Is || op == SQLBinaryOperator.IsNot) && !(binary.getRight() instanceof SQLNullExpr)) {
                    throw invalid();
                }
                return '(' + scalar(binary.getLeft(), depth + 1) + text + scalar(binary.getRight(), depth + 1) + ')';
            }
            if (expr instanceof SQLNotExpr not) {
                return "(NOT " + predicate(not.getExpr(), depth + 1) + ')';
            }
            if (expr instanceof SQLUnaryExpr unary && unary.getOperator() == SQLUnaryOperator.Not) {
                return "(NOT " + predicate(unary.getExpr(), depth + 1) + ')';
            }
            if (expr instanceof SQLInListExpr list) {
                if (list.getTargetList().isEmpty() || list.getTargetList().size() > 256) {
                    throw invalid();
                }
                return '(' + scalar(list.getExpr(), depth + 1) + (list.isNot() ? " NOT IN (" : " IN (") + String.join(
                        ",", list.getTargetList().stream().map(e -> scalar(e, depth + 1)).toList()) + "))";
            }
            if (expr instanceof SQLBetweenExpr between) {
                return '(' + scalar(between.getTestExpr(),
                        depth + 1) + (between.isNot() ? " NOT BETWEEN " : " BETWEEN ")
                        + scalar(between.getBeginExpr(), depth + 1) + " AND " + scalar(between.getEndExpr(),
                        depth + 1) + ')';
            }
            throw invalid();
        }

        /**
         * 读取或转换当前标量值。
         */
        String scalar(SQLExpr expr, int depth) {
            enter(depth);
            if (expr instanceof SQLIdentifierExpr identifier) {
                String raw = identifier.getName(), name = raw.toUpperCase(Locale.ROOT);
                Quoted token = quoted.get(name);
                if (token != null && token.literal()) {
                    return '\'' + token.value().replace("'", "''") + '\'';
                }
                if (token != null) {
                    name = token.value();
                }
                if (!fields.contains(name)) {
                    throw invalid();
                }
                String target = mapping.isEmpty() ? name : mapping.get(name);
                DmNativeIndexes.name(target);
                columns.add(target);
                return DmSqlNames.quote(target);
            }
            if (expr instanceof SQLNullExpr) {
                return "NULL";
            }
            if (expr instanceof SQLIntegerExpr integer) {
                return number(integer.getNumber());
            }
            if (expr instanceof SQLNumberExpr number) {
                return number(number.getNumber());
            }
            if (expr instanceof SQLUnaryExpr unary && (unary.getOperator() == SQLUnaryOperator.Negative || unary.getOperator() == SQLUnaryOperator.Plus)) {
                SQLExpr value = unary.getExpr();
                Number number = value instanceof SQLIntegerExpr integer ? integer.getNumber() : value instanceof SQLNumberExpr decimal ? decimal.getNumber() : null;
                if (number == null) {
                    throw invalid();
                }
                return (unary.getOperator() == SQLUnaryOperator.Negative ? "-" : "") + number(number);
            }
            throw invalid();
        }

        /**
         * 按当前值域约定解析或转换数值。
         */
        String number(Number value) {
            if (!(value instanceof BigDecimal || value instanceof BigInteger || value instanceof Long || value instanceof Integer)) {
                throw invalid();
            }
            var decimal = new BigDecimal(value.toString());
            if (decimal.precision() > 128 || Math.abs(decimal.scale()) > 128) {
                throw invalid();
            }
            return decimal.stripTrailingZeros().toPlainString();
        }
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("DM CHECK requires a verified bounded table-local predicate");
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static SQLException unsupported() {
        return new SQLException("DM CHECK state or column evidence is unverified");
    }
}
