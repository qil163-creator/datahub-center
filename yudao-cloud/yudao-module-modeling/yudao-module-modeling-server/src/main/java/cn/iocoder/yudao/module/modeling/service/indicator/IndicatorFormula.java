package cn.iocoder.yudao.module.modeling.service.indicator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.function.Function;

/**
 * 复合指标公式：只允许十进制数字、{@code + - * /}、括号和 {@code {指标编码}} 引用。
 * <p>
 * 公式只在服务端用 {@link BigDecimal} 求值，从不渲染为 SQL。任一引用值为空或除数为 0 时结果为空。
 * 实例不可变、线程安全。
 */
public final class IndicatorFormula {

    /** 公式长度上限。 */
    public static final int MAX_LENGTH = 2000;

    /** 单个公式内不同引用数量上限。 */
    public static final int MAX_REFERENCES = 20;

    /** 括号与一元运算嵌套深度上限，防止栈溢出。 */
    private static final int MAX_DEPTH = 32;

    /** 除法使用的精度；结果去掉尾随零后以十进制字符串输出。 */
    public static final MathContext DIVISION = MathContext.DECIMAL128;

    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,99}");

    private final String source;

    private final Node root;

    private final List<String> references;

    private final List<Token> tokens;

    /**
     * 初始化公式。
     */
    private IndicatorFormula(String source, Node root, List<String> references, List<Token> tokens) {
        this.source = source;
        this.root = root;
        this.references = references;
        this.tokens = tokens;
    }

    /**
     * 解析公式。
     *
     * @param formula 公式文本
     * @return 解析后的公式
     * @throws IllegalArgumentException 公式为空、超长、包含不允许的字符或语法错误
     */
    public static IndicatorFormula parse(String formula) {
        if (formula == null || formula.isBlank()) {
            throw new IllegalArgumentException("公式不能为空");
        }
        if (formula.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("公式长度不能超过 " + MAX_LENGTH + " 个字符");
        }
        List<Token> tokens = tokenize(formula);
        Parser parser = new Parser(tokens);
        Node root = parser.expression(0);
        if (parser.position != tokens.size()) {
            throw new IllegalArgumentException("第 " + (tokens.get(parser.position).offset() + 1) + " 个字符附近存在多余内容");
        }
        LinkedHashMap<String, String> distinct = new LinkedHashMap<>();
        for (Token token : tokens) {
            if (token.type() == TokenType.REFERENCE) {
                distinct.putIfAbsent(token.text().toLowerCase(Locale.ROOT), token.text());
            }
        }
        if (distinct.isEmpty()) {
            throw new IllegalArgumentException("公式至少需要引用一个指标");
        }
        if (distinct.size() > MAX_REFERENCES) {
            throw new IllegalArgumentException("公式引用的指标不能超过 " + MAX_REFERENCES + " 个");
        }
        return new IndicatorFormula(formula, root, List.copyOf(distinct.values()), List.copyOf(tokens));
    }

    /**
     * 引用的指标编码（按首次出现顺序、忽略大小写去重，保留首次拼写）。
     */
    public List<String> references() {
        return references;
    }

    /**
     * 原始公式。
     */
    public String source() {
        return source;
    }

    /**
     * 把引用替换为规范编码后的公式；其余字符保持用户原样。
     *
     * @param canonical 忽略大小写的编码 → 规范编码
     */
    public String normalize(Function<String, String> canonical) {
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        for (Token token : tokens) {
            if (token.type() != TokenType.REFERENCE) {
                continue;
            }
            result.append(source, cursor, token.offset());
            String code = canonical.apply(token.text());
            result.append('{').append(code == null ? token.text() : code).append('}');
            cursor = token.end();
        }
        result.append(source.substring(cursor));
        return result.toString().trim();
    }

    /**
     * 按引用值求值。
     *
     * @param values 引用编码（忽略大小写）到值的取值函数；返回空表示该维度键下没有值
     * @return 结果；任一引用为空或出现除以 0 时为空
     */
    public BigDecimal evaluate(Function<String, BigDecimal> values) {
        return root.evaluate(values);
    }

    /**
     * 引用值被解析为公式外层可见的规范字符串。
     */
    public static String format(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.signum() == 0 ? "0" : stripped.toPlainString();
    }

    private enum TokenType {
        NUMBER, REFERENCE, PLUS, MINUS, MULTIPLY, DIVIDE, LEFT, RIGHT
    }

    private record Token(TokenType type, String text, int offset, int end) {
    }

    /**
     * 词法分析；任何不在白名单中的字符直接拒绝。
     */
    private static List<Token> tokenize(String formula) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < formula.length()) {
            char current = formula.charAt(index);
            if (current == ' ' || current == '\t' || current == '\n' || current == '\r') {
                index++;
                continue;
            }
            int start = index;
            switch (current) {
                case '+' -> tokens.add(new Token(TokenType.PLUS, "+", start, ++index));
                case '-' -> tokens.add(new Token(TokenType.MINUS, "-", start, ++index));
                case '*' -> tokens.add(new Token(TokenType.MULTIPLY, "*", start, ++index));
                case '/' -> tokens.add(new Token(TokenType.DIVIDE, "/", start, ++index));
                case '(' -> tokens.add(new Token(TokenType.LEFT, "(", start, ++index));
                case ')' -> tokens.add(new Token(TokenType.RIGHT, ")", start, ++index));
                case '{' -> {
                    int close = formula.indexOf('}', start + 1);
                    if (close < 0) {
                        throw new IllegalArgumentException("第 " + (start + 1) + " 个字符处的指标引用缺少 }");
                    }
                    String code = formula.substring(start + 1, close);
                    if (!CODE.matcher(code).matches()) {
                        throw new IllegalArgumentException("指标引用 {" + code + "} 必须是字母开头的英文编码");
                    }
                    index = close + 1;
                    tokens.add(new Token(TokenType.REFERENCE, code, start, index));
                }
                default -> {
                    if (current >= '0' && current <= '9') {
                        while (index < formula.length() && Character.isDigit(formula.charAt(index))
                                && formula.charAt(index) <= '9') {
                            index++;
                        }
                        if (index < formula.length() && formula.charAt(index) == '.') {
                            index++;
                            int fraction = index;
                            while (index < formula.length() && formula.charAt(index) >= '0'
                                    && formula.charAt(index) <= '9') {
                                index++;
                            }
                            if (fraction == index) {
                                throw new IllegalArgumentException("第 " + (start + 1) + " 个字符处的数字格式不正确");
                            }
                        }
                        if (index - start > 38) {
                            throw new IllegalArgumentException("数字常量不能超过 38 位");
                        }
                        tokens.add(new Token(TokenType.NUMBER, formula.substring(start, index), start, index));
                    } else {
                        throw new IllegalArgumentException("第 " + (start + 1) + " 个字符“" + current
                                + "”不允许出现在公式中，只能使用数字、+ - * /、括号和 {指标编码}");
                    }
                }
            }
        }
        return tokens;
    }

    /** 递归下降：expression := term (('+'|'-') term)*；term := unary (('*'|'/') unary)*。 */
    private static final class Parser {

        private final List<Token> tokens;

        private int position;

        private Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        private Node expression(int depth) {
            Node left = term(depth);
            while (peek(TokenType.PLUS) || peek(TokenType.MINUS)) {
                TokenType operator = tokens.get(position++).type();
                left = new Binary(operator, left, term(depth));
            }
            return left;
        }

        private Node term(int depth) {
            Node left = unary(depth);
            while (peek(TokenType.MULTIPLY) || peek(TokenType.DIVIDE)) {
                TokenType operator = tokens.get(position++).type();
                left = new Binary(operator, left, unary(depth));
            }
            return left;
        }

        private Node unary(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("公式嵌套层级不能超过 " + MAX_DEPTH);
            }
            if (peek(TokenType.MINUS)) {
                position++;
                return new Negate(unary(depth + 1));
            }
            if (peek(TokenType.PLUS)) {
                position++;
                return unary(depth + 1);
            }
            return primary(depth);
        }

        private Node primary(int depth) {
            if (position >= tokens.size()) {
                throw new IllegalArgumentException("公式不完整，结尾缺少数字、指标引用或右括号");
            }
            Token token = tokens.get(position++);
            return switch (token.type()) {
                case NUMBER -> new Constant(new BigDecimal(token.text()));
                case REFERENCE -> new Reference(token.text());
                case LEFT -> {
                    Node inner = expression(depth + 1);
                    if (!peek(TokenType.RIGHT)) {
                        throw new IllegalArgumentException("第 " + (token.offset() + 1) + " 个字符处的左括号未闭合");
                    }
                    position++;
                    yield inner;
                }
                default -> throw new IllegalArgumentException("第 " + (token.offset() + 1) + " 个字符“"
                        + token.text() + "”处缺少数字、指标引用或左括号");
            };
        }

        private boolean peek(TokenType type) {
            return position < tokens.size() && tokens.get(position).type() == type;
        }
    }

    private interface Node {
        BigDecimal evaluate(Function<String, BigDecimal> values);
    }

    private record Constant(BigDecimal value) implements Node {
        @Override
        public BigDecimal evaluate(Function<String, BigDecimal> values) {
            return value;
        }
    }

    private record Reference(String code) implements Node {
        @Override
        public BigDecimal evaluate(Function<String, BigDecimal> values) {
            return values.apply(code);
        }
    }

    private record Negate(Node inner) implements Node {
        @Override
        public BigDecimal evaluate(Function<String, BigDecimal> values) {
            BigDecimal value = inner.evaluate(values);
            return value == null ? null : value.negate();
        }
    }

    private record Binary(TokenType operator, Node left, Node right) implements Node {
        @Override
        public BigDecimal evaluate(Function<String, BigDecimal> values) {
            BigDecimal a = left.evaluate(values);
            BigDecimal b = right.evaluate(values);
            if (a == null || b == null) {
                return null;
            }
            return switch (operator) {
                case PLUS -> a.add(b);
                case MINUS -> a.subtract(b);
                case MULTIPLY -> a.multiply(b);
                case DIVIDE -> b.signum() == 0 ? null : a.divide(b, DIVISION);
                default -> throw new IllegalStateException("unexpected operator " + operator);
            };
        }
    }
}
