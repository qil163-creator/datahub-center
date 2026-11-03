package cn.iocoder.yudao.framework.dbswitch.product.sqlite3;

import java.util.*;

/** 此处只编辑声明节点；表达式和引用的改写使用 SQLite 自身的 ALTER 解析器。 */
final class SqliteDdlAst {

    record Token(String text, int start, int end, char quote) {

        /**
         * 按当前解析位置读取或匹配一个词元。
         */
        boolean word(String value) {
            return quote == 0 && text.equalsIgnoreCase(value);
        }

        /**
         * 返回文本。
         */
        String identity() {
            return text;
        }
    }

    record Declaration(String sql, boolean index, boolean unique, Token name, Token table, int bodyStart) {

        /**
         * 生成当前模式限定下的建表或索引 SQL。
         */
        String inSchema(String schema, String objectName, String tableName) {
            if (!index) {
                return "CREATE TABLE " + SqliteNativeTables.q(schema) + '.' + SqliteNativeTables.q(
                        objectName) + sql.substring(bodyStart);
            }
            return "CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + SqliteNativeTables.q(
                    schema) + '.' + SqliteNativeTables.q(objectName)
                    + " ON " + SqliteNativeTables.q(tableName) + sql.substring(bodyStart);
        }

        /**
         * 读取或构建当前关系及字段的结构信息。
         */
        String shape() {
            return (unique ? "UNIQUE " : "") + canonical(sql.substring(bodyStart));
        }
    }

    /**
     * 返回当前解析。
     */
    static Declaration parse(String sql, boolean index) {
        var tokens = tokens(sql);
        int p = 0;
        require(tokens, p++, "CREATE");
        boolean unique = index && tokens.get(p).word("UNIQUE");
        if (unique) {
            p++;
        }
        require(tokens, p++, index ? "INDEX" : "TABLE");
        if (tokens.get(p).word("IF")) {
            require(tokens, p++, "IF");
            require(tokens, p++, "NOT");
            require(tokens, p++, "EXISTS");
        }
        Token name = name(tokens, p++);
        if (tokens.get(p).word(".")) {
            p++;
            name = name(tokens, p++);
        }
        Token table = name;
        if (index) {
            require(tokens, p++, "ON");
            table = name(tokens, p++);
        }
        if (!tokens.get(p).word("(")) {
            throw invalid();
        }
        return new Declaration(sql, index, unique, name, table, tokens.get(p).start);
    }

    /**
     * 返回名称。
     */
    private static Token name(List<Token> tokens, int index) {
        if (index >= tokens.size()) {
            throw invalid();
        }
        Token token = tokens.get(index);
        if (token.quote == 0 && !Character.isUnicodeIdentifierStart(token.text.codePointAt(0)) && token.text.charAt(
                0) != '_') {
            throw invalid();
        }
        SqliteNativeTables.q(token.text);
        return token;
    }

    /**
     * 检查词元集合、索引、值是否满足当前操作的约定。
     */
    private static void require(List<Token> tokens, int index, String value) {
        if (index >= tokens.size() || !tokens.get(index).word(value)) {
            throw invalid();
        }
    }

    /**
     * 将文本整理为当前协议使用的规范形式。
     */
    static String canonical(String text) {
        var out = new StringBuilder();
        for (Token t : tokens(text)) {
            if (t.word(";")) {
                continue;
            }
            String value = t.quote == '\'' ? "L:" + t.text : "I:" + t.text.toLowerCase(Locale.ROOT);
            out.append(value.length()).append(':').append(value);
        }
        return out.toString();
    }

    /**
     * 将当前 SQL 或表达式拆解为语法词元。
     */
    static List<Token> tokens(String sql) {
        if (sql == null || sql.length() > 1_048_576 || sql.indexOf('\0') >= 0) {
            throw invalid();
        }
        var out = new ArrayList<Token>();
        int i = 0;
        boolean ended = false;
        while (i < sql.length()) {
            char ch = sql.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    throw invalid();
                }
                i = end + 2;
                continue;
            }
            if (ended) {
                throw invalid();
            }
            int start = i;
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                char close = ch == '[' ? ']' : ch;
                i++;
                var value = new StringBuilder();
                boolean closed = false;
                while (i < sql.length()) {
                    char next = sql.charAt(i++);
                    if (next == close) {
                        if (close != ']' && i < sql.length() && sql.charAt(i) == close) {
                            i++;
                            value.append(close);
                            continue;
                        }
                        closed = true;
                        break;
                    }
                    value.append(next);
                }
                if (!closed) {
                    throw invalid();
                }
                out.add(new Token(value.toString(), start, i, ch));
            } else if (Character.isUnicodeIdentifierPart(ch) || ch == '$') {
                i++;
                while (i < sql.length() && (Character.isUnicodeIdentifierPart(sql.charAt(i)) || sql.charAt(i) == '$')) {
                    i++;
                }
                out.add(new Token(sql.substring(start, i), start, i, (char) 0));
            } else {
                i++;
                out.add(new Token(String.valueOf(ch), start, i, (char) 0));
                if (ch == ';') {
                    ended = true;
                }
            }
            if (out.size() > 100_000) {
                throw invalid();
            }
        }
        if (out.isEmpty()) {
            throw invalid();
        }
        return List.copyOf(out);
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    static IllegalArgumentException invalid() {
        return new IllegalArgumentException("SQLite native DDL is outside the supported ordinary-table grammar");
    }
}
