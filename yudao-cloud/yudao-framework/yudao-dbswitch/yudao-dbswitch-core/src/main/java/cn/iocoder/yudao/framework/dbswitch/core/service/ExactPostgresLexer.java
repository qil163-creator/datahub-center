package cn.iocoder.yudao.framework.dbswitch.core.service;

import com.alibaba.druid.sql.dialect.postgresql.parser.PGLexer;
import com.alibaba.druid.sql.parser.SQLParserFeature;
import com.alibaba.druid.sql.parser.Token;

/** 保留原生双引号标识符标记；Druid 默认别名词法分析器会改变双写引号。 */
final class ExactPostgresLexer extends PGLexer {

    private final boolean dollarStrings;

    private String dollarTag;

    private int dollarEnd = -1;

    /**
     * 使用给定参数初始化 {@code ExactPostgresLexer}。
     */
    ExactPostgresLexer(String sql) {
        this(sql, false);
    }

    /**
     * 初始化 {@code ExactPostgresLexer}及其父类状态。
     */
    ExactPostgresLexer(String sql, boolean dollarStrings) {
        super(sql, SQLParserFeature.KeepNameQuotes);
        this.dollarStrings = dollarStrings;
    }

    /** 仅由脚本启用；受限投影仍保留原有的美元符号字符串拒绝规则。 */
    @Override
    public void scanVariable() {
        if (dollarStrings && ch == '$') {
            int tagEnd = text.indexOf('$', pos + 1);
            if (tagEnd > pos && tagEnd - pos <= 65) {
                String name = text.substring(pos + 1, tagEnd);
                if (name.isEmpty() || name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    String tag = text.substring(pos, tagEnd + 1);
                    int end = text.indexOf(tag, tagEnd + 1);
                    if (end < 0) {
                        throw new IllegalArgumentException("Unclosed PostgreSQL dollar string");
                    }
                    String value = text.substring(tagEnd + 1, end);
                    if (value.indexOf('\0') >= 0) {
                        throw new IllegalArgumentException("SQL string cannot contain NUL");
                    }
                    while (pos < end + tag.length()) {
                        scanChar();
                    }
                    stringVal = value;
                    token = Token.LITERAL_CHARS;
                    dollarTag = tag;
                    dollarEnd = pos;
                    return;
                }
            }
        }
        super.scanVariable();
    }

    /**
     * 识别 PostgreSQL 美元引用字符串的标签。
     */
    String dollarStringTag() {
        return token == Token.LITERAL_CHARS && pos == dollarEnd ? dollarTag : null;
    }

    /**
     * 扫描别名。
     */
    @Override
    protected void scanAlias() {
        if (ch != '"') {
            super.scanAlias();
            return;
        }
        int start = pos;
        scanChar();
        while (pos < text.length()) {
            if (ch == '\0') {
                throw new IllegalArgumentException("标识符不允许 NUL");
            }
            if (ch == '"') {
                scanChar();
                if (ch != '"') {
                    stringVal = text.substring(start, pos);
                    token = Token.LITERAL_ALIAS;
                    return;
                }
            }
            scanChar();
        }
        throw new IllegalArgumentException("标识符引号未闭合");
    }
}
