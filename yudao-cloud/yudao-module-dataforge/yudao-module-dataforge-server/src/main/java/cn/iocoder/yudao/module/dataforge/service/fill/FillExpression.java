package cn.iocoder.yudao.module.dataforge.service.fill;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 在线填报-公式与校验规则表达式（白名单小语言，v1）
 *
 * <p>前端 {@code workbench/fill-expression.ts} 实现同一套语法与语义，仅用于即时显示；提交时以服务端计算为准。
 * 不复用 Aviator 等通用引擎：表达式需要在浏览器中同语义执行，且通用引擎的内置函数与反射能力需要逐项封禁。</p>
 *
 * <pre>
 * 运算：+ - * / %，比较 = == != &lt;&gt; &lt; &lt;= &gt; &gt;=，逻辑 AND OR NOT（&amp;&amp; || !），括号，一元负号
 * 字面量：数字、'字符串' 或 "字符串"、TRUE、FALSE、NULL
 * 字段：fieldCode（字母或下划线开头）
 * 行函数：ROUND(x, n) ABS(x) MIN(a, ...) MAX(a, ...) IF(条件, 是, 否) COALESCE(a, ...) LEN(s)
 * 汇总函数（表级 / 跨期规则）：SUM(字段) COUNT(字段) AVG(字段)
 * 上期函数（跨期规则）：PREV_SUM(字段) PREV_COUNT(字段) PREV_AVG(字段)
 * </pre>
 *
 * <p>空值语义：参与算术或比较时结果为空；规则结果为空视为通过（是否为空交给必填规则）；除数为零结果为空。
 * 数值一律按 {@link BigDecimal} 计算，不经过浮点。</p>
 *
 * @author Li
 */
public final class FillExpression {

    /** 表达式长度上限 */
    public static final int MAX_LENGTH = 1000;

    /** 语法树节点上限，防止病态表达式 */
    private static final int MAX_NODES = 300;

    private static final MathContext MATH = MathContext.DECIMAL64;

    /** 使用范围 */
    public enum Scope {
        /** 行内公式：行函数，不允许汇总与上期函数 */
        FORMULA,
        /** 行级规则：同公式 */
        ROW,
        /** 表级规则：字段只能出现在汇总函数内 */
        TABLE,
        /** 跨期规则：表级规则 + 上期函数 */
        PERIOD
    }

    /** 求值上下文 */
    public interface Context {

        /** 当前行字段值（行级） */
        default Object field(String fieldCode) {
            return null;
        }

        /** 本期整列取值（表级） */
        default List<Object> column(String fieldCode) {
            return List.of();
        }

        /** 上期整列取值；没有上期数据时返回 null */
        default List<Object> previousColumn(String fieldCode) {
            return null;
        }
    }

    /** 定义错误（保存、发布时提示给模板设计人） */
    public static final class DefinitionException extends RuntimeException {

        public DefinitionException(String message) {
            super(message);
        }
    }

    private static final Set<String> ROW_FUNCTIONS = Set.of("ROUND", "ABS", "MIN", "MAX", "IF", "COALESCE", "LEN");
    private static final Set<String> AGGREGATES = Set.of("SUM", "COUNT", "AVG");
    private static final Set<String> PREVIOUS = Set.of("PREV_SUM", "PREV_COUNT", "PREV_AVG");

    private final Node root;
    private final Scope scope;
    private final Set<String> fieldRefs;

    private FillExpression(Node root, Scope scope, Set<String> fieldRefs) {
        this.root = root;
        this.scope = scope;
        this.fieldRefs = fieldRefs;
    }

    /**
     * 解析并按范围校验表达式。
     *
     * @param text 表达式
     * @param scope 使用范围
     * @return 已编译表达式
     * @throws DefinitionException 语法错误或使用了范围外的函数
     */
    public static FillExpression compile(String text, Scope scope) {
        if (text == null || text.isBlank()) {
            throw new DefinitionException("表达式不能为空");
        }
        if (text.length() > MAX_LENGTH) {
            throw new DefinitionException("表达式长度不能超过 " + MAX_LENGTH);
        }
        Parser parser = new Parser(text);
        Node root = parser.parseAll();
        Set<String> refs = new LinkedHashSet<>();
        check(root, scope, false, refs);
        return new FillExpression(root, scope, Set.copyOf(refs));
    }

    /** 引用到的字段码 */
    public Set<String> fieldRefs() {
        return fieldRefs;
    }

    public Scope scope() {
        return scope;
    }

    /** 求值，返回 BigDecimal / String / Boolean / null */
    public Object evaluate(Context context) {
        return eval(root, context);
    }

    /**
     * 规则求值：只有明确为假才算违反；空值视为通过。
     */
    public boolean violated(Context context) {
        Object value = evaluate(context);
        return Boolean.FALSE.equals(toBoolean(value));
    }

    // ================================================================ 语法树

    private sealed interface Node permits Literal, FieldRef, Unary, Binary, Call {
    }

    private record Literal(Object value) implements Node {
    }

    private record FieldRef(String code) implements Node {
    }

    private record Unary(String op, Node operand) implements Node {
    }

    private record Binary(String op, Node left, Node right) implements Node {
    }

    private record Call(String name, List<Node> args) implements Node {
    }

    private static void check(Node node, Scope scope, boolean insideAggregate, Set<String> refs) {
        switch (node) {
            case Literal ignored -> {
            }
            case FieldRef ref -> {
                if ((scope == Scope.TABLE || scope == Scope.PERIOD) && !insideAggregate) {
                    throw new DefinitionException("表级规则中字段【" + ref.code() + "】必须放在 SUM/COUNT/AVG 等汇总函数内");
                }
                refs.add(ref.code());
            }
            case Unary unary -> check(unary.operand(), scope, insideAggregate, refs);
            case Binary binary -> {
                check(binary.left(), scope, insideAggregate, refs);
                check(binary.right(), scope, insideAggregate, refs);
            }
            case Call call -> checkCall(call, scope, insideAggregate, refs);
        }
    }

    private static void checkCall(Call call, Scope scope, boolean insideAggregate, Set<String> refs) {
        String name = call.name();
        int argc = call.args().size();
        boolean aggregate = AGGREGATES.contains(name);
        boolean previous = PREVIOUS.contains(name);
        if (aggregate || previous) {
            if (scope == Scope.FORMULA || scope == Scope.ROW) {
                throw new DefinitionException(name + " 只能用于表级或跨期规则");
            }
            if (previous && scope != Scope.PERIOD) {
                throw new DefinitionException(name + " 只能用于跨期规则");
            }
            if (insideAggregate) {
                throw new DefinitionException(name + " 不能嵌套在其他汇总函数内");
            }
            if (argc != 1 || !(call.args().getFirst() instanceof FieldRef ref)) {
                throw new DefinitionException(name + " 的参数必须是一个字段");
            }
            refs.add(ref.code());
            return;
        }
        if (!ROW_FUNCTIONS.contains(name)) {
            throw new DefinitionException("不支持的函数 " + name);
        }
        boolean arityOk = switch (name) {
            case "ROUND" -> argc == 1 || argc == 2;
            case "ABS", "LEN" -> argc == 1;
            case "IF" -> argc == 3;
            default -> argc >= 1;
        };
        if (!arityOk) {
            throw new DefinitionException(name + " 的参数个数不正确");
        }
        for (Node arg : call.args()) {
            check(arg, scope, insideAggregate, refs);
        }
    }

    // ================================================================ 求值

    private Object eval(Node node, Context context) {
        return switch (node) {
            case Literal literal -> literal.value();
            case FieldRef ref -> normalize(context.field(ref.code()));
            case Unary unary -> evalUnary(unary, context);
            case Binary binary -> evalBinary(binary, context);
            case Call call -> evalCall(call, context);
        };
    }

    private Object evalUnary(Unary unary, Context context) {
        Object value = eval(unary.operand(), context);
        if ("NOT".equals(unary.op())) {
            Boolean bool = toBoolean(value);
            return bool == null ? null : !bool;
        }
        BigDecimal number = toNumber(value);
        return number == null ? null : number.negate();
    }

    private Object evalBinary(Binary binary, Context context) {
        String op = binary.op();
        if ("AND".equals(op) || "OR".equals(op)) {
            Boolean left = toBoolean(eval(binary.left(), context));
            if ("AND".equals(op) && Boolean.FALSE.equals(left)) {
                return false;
            }
            if ("OR".equals(op) && Boolean.TRUE.equals(left)) {
                return true;
            }
            Boolean right = toBoolean(eval(binary.right(), context));
            if ("AND".equals(op)) {
                return Boolean.FALSE.equals(right) ? Boolean.FALSE : (left == null || right == null ? null : true);
            }
            return Boolean.TRUE.equals(right) ? Boolean.TRUE : (left == null || right == null ? null : false);
        }
        Object left = eval(binary.left(), context);
        Object right = eval(binary.right(), context);
        if (left == null || right == null) {
            return null;
        }
        switch (op) {
            case "+", "-", "*", "/", "%" -> {
                BigDecimal a = toNumber(left);
                BigDecimal b = toNumber(right);
                if (a == null || b == null) {
                    return null;
                }
                return switch (op) {
                    case "+" -> a.add(b, MATH);
                    case "-" -> a.subtract(b, MATH);
                    case "*" -> a.multiply(b, MATH);
                    case "/" -> b.signum() == 0 ? null : a.divide(b, MATH);
                    default -> b.signum() == 0 ? null : a.remainder(b, MATH);
                };
            }
            default -> {
                int cmp = compare(left, right);
                return switch (op) {
                    case "=" -> cmp == 0;
                    case "!=" -> cmp != 0;
                    case "<" -> cmp < 0;
                    case "<=" -> cmp <= 0;
                    case ">" -> cmp > 0;
                    default -> cmp >= 0;
                };
            }
        }
    }

    private Object evalCall(Call call, Context context) {
        String name = call.name();
        if (AGGREGATES.contains(name)) {
            return aggregate(name, context.column(((FieldRef) call.args().getFirst()).code()));
        }
        if (PREVIOUS.contains(name)) {
            List<Object> previous = context.previousColumn(((FieldRef) call.args().getFirst()).code());
            return previous == null ? null : aggregate(name.substring("PREV_".length()), previous);
        }
        List<Node> args = call.args();
        switch (name) {
            case "IF" -> {
                Boolean condition = toBoolean(eval(args.get(0), context));
                return Boolean.TRUE.equals(condition) ? eval(args.get(1), context) : eval(args.get(2), context);
            }
            case "COALESCE" -> {
                for (Node arg : args) {
                    Object value = eval(arg, context);
                    if (value != null) {
                        return value;
                    }
                }
                return null;
            }
            case "LEN" -> {
                Object value = eval(args.getFirst(), context);
                return value == null ? null : BigDecimal.valueOf(stringOf(value).length());
            }
            case "ABS" -> {
                BigDecimal value = toNumber(eval(args.getFirst(), context));
                return value == null ? null : value.abs();
            }
            case "ROUND" -> {
                BigDecimal value = toNumber(eval(args.getFirst(), context));
                BigDecimal scale = args.size() > 1 ? toNumber(eval(args.get(1), context)) : BigDecimal.ZERO;
                if (value == null || scale == null) {
                    return null;
                }
                return value.setScale(Math.max(0, Math.min(10, scale.intValue())), RoundingMode.HALF_UP);
            }
            default -> {
                // MIN / MAX：忽略空值，全部为空时结果为空
                BigDecimal best = null;
                for (Node arg : args) {
                    BigDecimal value = toNumber(eval(arg, context));
                    if (value != null && (best == null
                            || ("MIN".equals(name) ? value.compareTo(best) < 0 : value.compareTo(best) > 0))) {
                        best = value;
                    }
                }
                return best;
            }
        }
    }

    private static Object aggregate(String name, List<Object> values) {
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (Object raw : values) {
            Object value = normalize(raw);
            if (value == null) {
                continue;
            }
            count++;
            if (!"COUNT".equals(name)) {
                BigDecimal number = toNumber(value);
                if (number != null) {
                    sum = sum.add(number, MATH);
                }
            }
        }
        return switch (name) {
            case "COUNT" -> BigDecimal.valueOf(count);
            case "SUM" -> sum;
            default -> count == 0 ? null : sum.divide(BigDecimal.valueOf(count), MATH);
        };
    }

    // ================================================================ 值转换

    /** 空串视为空值；数值统一为 BigDecimal */
    private static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static BigDecimal toNumber(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private static Boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof BigDecimal number) {
            return number.signum() != 0;
        }
        return null;
    }

    private static int compare(Object left, Object right) {
        BigDecimal a = toNumber(left);
        BigDecimal b = toNumber(right);
        if (a != null && b != null) {
            return a.compareTo(b);
        }
        // 日期、日期时间按统一格式的字符串比较
        return stringOf(left).compareTo(stringOf(right));
    }

    private static String stringOf(Object value) {
        return value instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value);
    }

    /**
     * 把计算结果写回字段：数值按字段小数位四舍五入，输出十进制字符串（不经过浮点）。
     */
    public static Object toFieldValue(Object value, Integer fieldType, Integer scale) {
        Object normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        boolean numericField = fieldType != null && (fieldType == 1 || fieldType == 5 || fieldType == 6);
        if (numericField) {
            BigDecimal number = toNumber(normalized);
            if (number == null) {
                return null;
            }
            int targetScale = fieldType == 1 ? (scale == null ? 2 : scale) : 0;
            return number.setScale(targetScale, RoundingMode.HALF_UP).toPlainString();
        }
        if (normalized instanceof Boolean bool) {
            return bool ? "是" : "否";
        }
        return stringOf(normalized);
    }

    // ================================================================ 解析器

    private static final class Parser {

        private final String text;
        private int pos;
        private int nodes;

        Parser(String text) {
            this.text = text;
        }

        Node parseAll() {
            Node node = parseOr();
            skipSpaces();
            if (pos < text.length()) {
                throw error("无法识别的内容");
            }
            return node;
        }

        private Node parseOr() {
            Node left = parseAnd();
            while (matchWord("OR") || match("||")) {
                left = count(new Binary("OR", left, parseAnd()));
            }
            return left;
        }

        private Node parseAnd() {
            Node left = parseNot();
            while (matchWord("AND") || match("&&")) {
                left = count(new Binary("AND", left, parseNot()));
            }
            return left;
        }

        private Node parseNot() {
            if (matchWord("NOT") || (peekChar() == '!' && !peek("!=") && match("!"))) {
                return count(new Unary("NOT", parseNot()));
            }
            return parseComparison();
        }

        private Node parseComparison() {
            Node left = parseAdditive();
            String op = null;
            for (String candidate : new String[]{"==", "!=", "<>", "<=", ">=", "=", "<", ">"}) {
                if (match(candidate)) {
                    op = switch (candidate) {
                        case "==" -> "=";
                        case "<>" -> "!=";
                        default -> candidate;
                    };
                    break;
                }
            }
            return op == null ? left : count(new Binary(op, left, parseAdditive()));
        }

        private Node parseAdditive() {
            Node left = parseMultiplicative();
            while (true) {
                if (match("+")) {
                    left = count(new Binary("+", left, parseMultiplicative()));
                } else if (match("-")) {
                    left = count(new Binary("-", left, parseMultiplicative()));
                } else {
                    return left;
                }
            }
        }

        private Node parseMultiplicative() {
            Node left = parseUnary();
            while (true) {
                if (match("*")) {
                    left = count(new Binary("*", left, parseUnary()));
                } else if (match("/")) {
                    left = count(new Binary("/", left, parseUnary()));
                } else if (match("%")) {
                    left = count(new Binary("%", left, parseUnary()));
                } else {
                    return left;
                }
            }
        }

        private Node parseUnary() {
            if (match("-")) {
                return count(new Unary("-", parseUnary()));
            }
            if (match("+")) {
                return parseUnary();
            }
            return parsePrimary();
        }

        private Node parsePrimary() {
            skipSpaces();
            if (pos >= text.length()) {
                throw error("表达式不完整");
            }
            char c = text.charAt(pos);
            if (c == '(') {
                pos++;
                Node inner = parseOr();
                expect(")");
                return inner;
            }
            if (c == '\'' || c == '"') {
                return count(new Literal(readString(c)));
            }
            if (Character.isDigit(c) || (c == '.' && pos + 1 < text.length()
                    && Character.isDigit(text.charAt(pos + 1)))) {
                return count(new Literal(readNumber()));
            }
            if (Character.isLetter(c) || c == '_') {
                String word = readWord();
                String upper = word.toUpperCase(Locale.ROOT);
                switch (upper) {
                    case "TRUE" -> {
                        return count(new Literal(Boolean.TRUE));
                    }
                    case "FALSE" -> {
                        return count(new Literal(Boolean.FALSE));
                    }
                    case "NULL" -> {
                        return count(new Literal(null));
                    }
                    default -> {
                    }
                }
                if (match("(")) {
                    List<Node> args = new ArrayList<>();
                    if (!match(")")) {
                        do {
                            args.add(parseOr());
                        } while (match(","));
                        expect(")");
                    }
                    return count(new Call(upper, List.copyOf(args)));
                }
                return count(new FieldRef(word));
            }
            throw error("无法识别的字符 '" + c + "'");
        }

        private Node count(Node node) {
            if (++nodes > MAX_NODES) {
                throw new DefinitionException("表达式过于复杂");
            }
            return node;
        }

        private String readString(char quote) {
            int start = ++pos;
            StringBuilder builder = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == quote) {
                    if (pos + 1 < text.length() && text.charAt(pos + 1) == quote) {
                        builder.append(quote);
                        pos += 2;
                        continue;
                    }
                    pos++;
                    return builder.toString();
                }
                builder.append(c);
                pos++;
            }
            pos = start;
            throw error("字符串缺少结束引号");
        }

        private BigDecimal readNumber() {
            int start = pos;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.')) {
                pos++;
            }
            try {
                return new BigDecimal(text.substring(start, pos));
            } catch (NumberFormatException ex) {
                pos = start;
                throw error("数字格式不正确");
            }
        }

        private String readWord() {
            int start = pos;
            while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) {
                pos++;
            }
            return text.substring(start, pos);
        }

        private boolean matchWord(String word) {
            skipSpaces();
            int end = pos + word.length();
            if (end <= text.length() && text.regionMatches(true, pos, word, 0, word.length())
                    && (end == text.length() || !(Character.isLetterOrDigit(text.charAt(end))
                    || text.charAt(end) == '_'))) {
                pos = end;
                return true;
            }
            return false;
        }

        private boolean match(String token) {
            skipSpaces();
            if (text.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        private boolean peek(String token) {
            skipSpaces();
            return text.startsWith(token, pos);
        }

        private char peekChar() {
            skipSpaces();
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        private void expect(String token) {
            if (!match(token)) {
                throw error("缺少 '" + token + "'");
            }
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private DefinitionException error(String message) {
            return new DefinitionException(message + "（第 " + (pos + 1) + " 个字符）");
        }
    }

    /**
     * 按依赖关系排序公式字段（公式可以引用其他公式字段），存在循环引用时抛出定义错误。
     *
     * @param formulas fieldCode → 已编译公式
     * @return 计算顺序
     */
    public static List<String> evaluationOrder(Map<String, FillExpression> formulas) {
        List<String> order = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> done = new LinkedHashSet<>();
        for (String code : formulas.keySet()) {
            visit(code, formulas, visiting, done, order);
        }
        return order;
    }

    private static void visit(String code, Map<String, FillExpression> formulas, Set<String> visiting,
                              Set<String> done, List<String> order) {
        if (done.contains(code)) {
            return;
        }
        if (!visiting.add(code)) {
            throw new DefinitionException("公式存在循环引用：" + String.join(" → ", visiting) + " → " + code);
        }
        for (String ref : formulas.get(code).fieldRefs()) {
            if (formulas.containsKey(ref)) {
                visit(ref, formulas, visiting, done, order);
            }
        }
        visiting.remove(code);
        done.add(code);
        order.add(code);
    }

}
