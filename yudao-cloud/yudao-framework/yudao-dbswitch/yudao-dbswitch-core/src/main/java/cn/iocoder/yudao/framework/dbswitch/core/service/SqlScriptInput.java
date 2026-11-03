package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 有界的整脚本输入。词法状态跨所有 Reader 边界保留；EOF 绝不补全未闭合标记。 */
final class SqlScriptInput {

    private enum State {NORMAL, SINGLE, DOUBLE, BACKTICK, BRACKET, LINE_COMMENT, BLOCK_COMMENT, DOLLAR}

    private static final Pattern DIRECTIVE = Pattern.compile("(?im)^\\s*(?:DELIMITER\\b.*|GO|/)\\s*$");

    private final StringBuilder text = new StringBuilder();

    private final StringBuilder code = new StringBuilder();

    private final List<Separator> separators = new ArrayList<>();

    private final String statementSeparator;

    private int statementChars;

    private record Separator(int start, int end) {

    }

    /**
     * 使用给定参数初始化 {@code SqlScriptInput}。
     */
    private SqlScriptInput() {
        this(";");
    }

    /**
     * 初始化 {@code SqlScriptInput}。
     */
    private SqlScriptInput(String statementSeparator) {
        this.statementSeparator = normalizeSeparator(statementSeparator);
    }

    /**
     * 按产品字符串语法读取完整脚本，并检查整体输入及单语句大小限制。
     */
    static String read(ProductTypeEnum product, Reader input) {
        boolean postgres = SupportedSqlParser.usesPostgresSyntax(product);
        return new SqlScriptInput().consume(input, postgres, !postgres, false);
    }

    record HookInput(String script, String executableText, List<String> statements) {

    }

    /** 为钩子保留精确 SQL 标记；Druid 校验不得重写厂商 SQL。 */
    static HookInput readMigrationHook(Reader input, boolean postgres, boolean mysqlComments, boolean brackets) {
        var reader = new SqlScriptInput();
        String script = reader.consume(input, postgres, mysqlComments, brackets);
        return reader.segments(script, false);
    }

    /** 只读入口复用同一词法边界，仅返回可执行原文段，纯尾注释不视为第二条语句。 */
    static HookInput readReadOnlyStatements(Reader input, boolean postgres, boolean mysqlComments, boolean brackets) {
        var reader = new SqlScriptInput();
        String script = reader.consume(input, postgres, mysqlComments, brackets);
        return reader.segments(script, true);
    }

    /** 只做词法分段；不把厂商过程体误认为已验证的可执行计划。 */
    static HookInput readStatements(ProductTypeEnum product, Reader input, String statementSeparator) {
        if (product == null) {
            throw new IllegalArgumentException("SQL product is required");
        }
        boolean postgres = switch (product) {
            case POSTGRESQL, GREENPLUM, HIGHGO, KINGBASE, OPENGAUSS, UXDB -> true;
            default -> false;
        };
        boolean mysqlComments = switch (product) {
            case MYSQL, MARIADB, TIDB, OCEANBASE, GBASE8A, DORIS, STARROCKS -> true;
            default -> false;
        };
        boolean brackets = product == ProductTypeEnum.SQLSERVER || product == ProductTypeEnum.SQLSERVER2000
                || product == ProductTypeEnum.SYBASE || product == ProductTypeEnum.SQLITE3;
        switch (product) {
            case UNKNOWN, MONGODB, ELASTICSEARCH, INFLUXDB3, GBASE8C, GAUSSDB, PHOENIX, SAPHANA, PRESTO, ODPS, NEO4J,
                 MILVUS, INFLUXDB2 -> throw new UnsupportedOperationException(
                    "SQL statements require a JDBC SQL product");
            default -> {
            }
        }
        var reader = new SqlScriptInput(statementSeparator);
        if (postgres && reader.statementSeparator.equals("$$")) {
            throw new IllegalArgumentException("SQL separator conflicts with PostgreSQL dollar quoting");
        }
        return reader.segments(reader.consume(input, postgres, mysqlComments, brackets), true);
    }

    /**
     * 根据当前输入构建并返回 {@code HookInput}。
     */
    private HookInput segments(String script, boolean executableOnly) {
        var statements = new ArrayList<String>();
        int start = 0;
        for (Separator separator : separators) {
            addSegment(statements, script, start, separator.start(), executableOnly);
            start = separator.end();
        }
        addSegment(statements, script, start, script.length(), executableOnly);
        if (executableOnly && statements.isEmpty()) {
            throw invalid();
        }
        return new HookInput(script, code.toString(), List.copyOf(statements));
    }

    /**
     * 记录当前脚本解析出的一个语句片段。
     */
    private void addSegment(List<String> statements, String script, int start, int end, boolean executableOnly) {
        if (!executableOnly || !code.substring(start, end).isBlank()) {
            if (executableOnly && statements.size() >= 500) {
                throw invalid();
            }
            String segment = script.substring(start, end);
            statements.add(executableOnly ? segment.strip() : segment);
        }
    }

    /**
     * 规范化分隔符。
     */
    static String normalizeSeparator(String separator) {
        if (separator == null || separator.isEmpty()) {
            return ";";
        }
        if (separator.length() > 16) {
            throw new IllegalArgumentException("SQL separator exceeds its budget");
        }
        if (separator.isBlank()) {
            return ";";
        }
        if (!separator.equals(separator.strip())
                || separator.chars().anyMatch(ch -> ch < 33 || ch > 126 || Character.isLetterOrDigit(ch)
                || ch == '\'' || ch == '"' || ch == '`' || ch == '[' || ch == ']'
                || ch == '(' || ch == ')' || ch == '{' || ch == '}' || ch == '\\')
                || !separator.equals(";") && separator.indexOf(';') >= 0
                || separator.contains("--") || separator.contains("/*") || separator.contains("*/")
                || separator.indexOf('#') >= 0) {
            throw new IllegalArgumentException("SQL separator must be at most 16 unambiguous punctuation characters");
        }
        return separator;
    }

    /**
     * 独立行分隔符探测最多回退一个受单句预算约束的行；不关闭调用方 Reader。
     */
    private String consume(Reader input, boolean postgres, boolean mysqlComments, boolean brackets) {
        if (input == null) {
            throw new IllegalArgumentException("SQL reader is required");
        }
        // 独立行分隔符探测最多回退一个受单句预算约束的行；不关闭调用方 Reader。
        PushbackReader reader = new PushbackReader(input, statementSeparator.equals(";") ? 128 : 25_017);
        State state = State.NORMAL;
        int nesting = 0, comments = 0;
        String dollarTag = null;
        int dollarBodyStart = 0;
        try {
            int raw;
            while ((raw = reader.read()) != -1) {
                char ch = (char) raw;
                if (state == State.NORMAL && nesting == 0 && !statementSeparator.equals(";")
                        && consumeLineSeparator(reader, ch)) {
                    continue;
                }
                append(ch, state == State.NORMAL);
                if (state == State.LINE_COMMENT) {
                    if (ch == '\n' || ch == '\r') {
                        state = State.NORMAL;
                    }
                    continue;
                }
                if (state == State.BLOCK_COMMENT) {
                    if (ch == '/' && peek(reader) == '*') {
                        append((char) reader.read(), false);
                        if (++comments > 16) {
                            throw invalid();
                        }
                    } else if (ch == '*' && peek(reader) == '/') {
                        append((char) reader.read(), false);
                        if (--comments == 0) {
                            state = State.NORMAL;
                        }
                    }
                    continue;
                }
                if (state == State.DOLLAR) {
                    if (ch == '$' && text.length() >= dollarBodyStart + dollarTag.length() && endsWith(text,
                            dollarTag)) {
                        state = State.NORMAL;
                    }
                    continue;
                }
                if (state == State.BRACKET) {
                    if (ch == ']') {
                        if (peek(reader) == ']') {
                            append((char) reader.read(), false);
                        } else {
                            state = State.NORMAL;
                        }
                    }
                    continue;
                }
                if (state == State.SINGLE || state == State.DOUBLE || state == State.BACKTICK) {
                    if (ch == '\\' && !(state == State.DOUBLE && postgres)) {
                        throw new IllegalArgumentException("Backslash SQL quoting requires a verified session mode");
                    }
                    char quote = state == State.SINGLE ? '\'' : state == State.DOUBLE ? '"' : '`';
                    if (ch == quote) {
                        if (peek(reader) == quote) {
                            append((char) reader.read(), false);
                        } else {
                            state = State.NORMAL;
                        }
                    }
                    continue;
                }
                if (ch == '\'') {
                    state = State.SINGLE;
                } else if (ch == '"') {
                    state = State.DOUBLE;
                } else if (ch == '`') {
                    state = State.BACKTICK;
                } else if (ch == '[' && brackets) {
                    state = State.BRACKET;
                } else if (ch == '-' && peek(reader) == '-' && dashCommentFollows(reader, mysqlComments)) {
                    code.setCharAt(code.length() - 1, ' ');
                    append((char) reader.read(), false);
                    state = State.LINE_COMMENT;
                } else if (ch == '#' && mysqlComments) {
                    code.setCharAt(code.length() - 1, ' ');
                    state = State.LINE_COMMENT;
                } else if (ch == '/' && peek(reader) == '*') {
                    code.setCharAt(code.length() - 1, ' ');
                    append((char) reader.read(), false);
                    if (peek(reader) == '!') {
                        throw new IllegalArgumentException("Executable comments are unsupported");
                    }
                    if (mysqlComments && (peek(reader) == 'M' || peek(reader) == 'm')) {
                        int marker = reader.read();
                        if (peek(reader) == '!') {
                            throw new IllegalArgumentException("Executable comments are unsupported");
                        }
                        reader.unread(marker);
                    }
                    state = State.BLOCK_COMMENT;
                    comments = 1;
                } else if (ch == '$' && postgres) {
                    StringBuilder tag = new StringBuilder("$");
                    int next;
                    while ((next = reader.read()) != -1) {
                        if (tag.length() > 64) {
                            throw invalid();
                        }
                        if (next == '$') {
                            tag.append('$');
                            break;
                        }
                        if (!(next == '_' || next >= 'a' && next <= 'z' || next >= 'A' && next <= 'Z'
                                || tag.length() > 1 && next >= '0' && next <= '9')) {
                            reader.unread(next);
                            break;
                        }
                        tag.append((char) next);
                    }
                    if (tag.length() > 1 && tag.charAt(tag.length() - 1) == '$') {
                        for (int i = 1; i < tag.length(); i++) {
                            append(tag.charAt(i), false);
                        }
                        dollarTag = tag.toString();
                        dollarBodyStart = text.length();
                        state = State.DOLLAR;
                    } else {
                        for (int i = tag.length() - 1; i > 0; i--) {
                            reader.unread(tag.charAt(i));
                        }
                    }
                } else if (ch == '(') {
                    if (++nesting > 64) {
                        throw invalid();
                    }
                } else if (ch == ')') {
                    if (--nesting < 0) {
                        throw invalid();
                    }
                } else if (ch == ';') {
                    if (statementSeparator.equals(";") && nesting == 0) {
                        separators.add(new Separator(text.length() - 1, text.length()));
                        statementChars = 0;
                    }
                }
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("SQL input could not be read");
        }
        if ((state != State.NORMAL && state != State.LINE_COMMENT) || nesting != 0
                || text.isEmpty() || DIRECTIVE.matcher(code).find()) {
            throw invalid();
        }
        return text.toString();
    }

    /** 自定义分隔符必须独占一行；引号、注释和 dollar 体不会进入此路径。 */
    private boolean consumeLineSeparator(PushbackReader reader, char first) throws IOException {
        if (first != statementSeparator.charAt(0)) {
            return false;
        }
        int lineStart = text.length();
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && text.charAt(lineStart - 1) != '\r') {
            char previous = text.charAt(lineStart - 1);
            if (previous != ' ' && previous != '\t') {
                return false;
            }
            lineStart--;
        }
        StringBuilder lookahead = new StringBuilder();
        boolean matched = true;
        for (int i = 1; i < statementSeparator.length(); i++) {
            int next = reader.read();
            if (next == -1) {
                matched = false;
                break;
            }
            lookahead.append((char) next);
            if (next != statementSeparator.charAt(i)) {
                matched = false;
                break;
            }
        }
        if (matched) {
            int next;
            while ((next = reader.read()) != -1) {
                lookahead.append((char) next);
                if (next == '\n' || next == '\r') {
                    break;
                }
                if (next != ' ' && next != '\t') {
                    matched = false;
                    break;
                }
                if (lookahead.length() > 25_000) {
                    throw invalid();
                }
            }
        }
        if (!matched) {
            for (int i = lookahead.length() - 1; i >= 0; i--) {
                reader.unread(lookahead.charAt(i));
            }
            return false;
        }
        int length = lookahead.length();
        if (length > 0 && (lookahead.charAt(length - 1) == '\n' || lookahead.charAt(length - 1) == '\r')) {
            reader.unread(lookahead.charAt(--length));
        }
        append(first, false);
        for (int i = 0; i < length; i++) {
            append(lookahead.charAt(i), false);
        }
        separators.add(new Separator(lineStart, text.length()));
        statementChars = 0;
        return true;
    }

    /**
     * 将给定内容追加到当前缓冲区，并更新累计大小。
     */
    private void append(char ch, boolean executable) {
        if (text.length() >= 100_000 || ++statementChars > 25_000 || ch == '\0') {
            throw invalid();
        }
        text.append(ch);
        code.append(executable || ch == '\n' || ch == '\r' ? ch : ' ');
    }

    /**
     * 读取当前位置字符或词元而不推进游标。
     */
    private static int peek(PushbackReader reader) throws IOException {
        int next = reader.read();
        if (next != -1) {
            reader.unread(next);
        }
        return next;
    }

    /**
     * MySQL/MariaDB 的 -- 注释后必须跟空白或控制字符；否则。
     */
    private static boolean dashCommentFollows(PushbackReader reader, boolean mysqlComments) throws IOException {
        if (!mysqlComments) {
            return true;
        }
        int dash = reader.read();
        int next = reader.read();
        if (next != -1) {
            reader.unread(next);
        }
        reader.unread(dash);
        // MySQL/MariaDB 的 -- 注释后必须跟空白或控制字符；否则
        // 它属于算术表达式，钩子准入必须仍能看见变量及参数。
        return next == -1 || Character.isWhitespace((char) next) || Character.isISOControl((char) next);
    }

    /**
     * 检查当前文本或语句是否以指定内容结尾。
     */
    private static boolean endsWith(StringBuilder value, String suffix) {
        if (value.length() < suffix.length()) {
            return false;
        }
        for (int i = 0; i < suffix.length(); i++) {
            if (value.charAt(value.length() - suffix.length() + i) != suffix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "SQL input has incomplete syntax, unsupported directives or exceeds its budget");
    }
}
