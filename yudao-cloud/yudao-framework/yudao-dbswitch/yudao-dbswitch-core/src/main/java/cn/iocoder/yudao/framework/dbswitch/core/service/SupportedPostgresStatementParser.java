package cn.iocoder.yudao.framework.dbswitch.core.service;

import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGUpdateStatement;
import com.alibaba.druid.sql.dialect.postgresql.parser.PGExprParser;
import com.alibaba.druid.sql.dialect.postgresql.parser.PGSQLStatementParser;
import com.alibaba.druid.sql.parser.Lexer;
import com.alibaba.druid.sql.parser.Token;
import com.alibaba.druid.sql.ast.statement.SQLCreateSequenceStatement;
import com.alibaba.druid.sql.ast.statement.SQLAlterSequenceStatement;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;

/**
 * Druid 1.2.24 会渲染 PGUpdateStatement.only，但不消费 ONLY 关键字。
 * 扩展其标记接收点，表与表达式仍交由 Druid 解析，并将
 * ONLY 保留在 AST 中。不对标识符、字符串或注释进行源文本替换。
 */
final class SupportedPostgresStatementParser extends PGSQLStatementParser {

    static final String GREENPLUM_DISTRIBUTION_KEYS = "dbswitch.greenplum.distributionKeys";

    private boolean acceptingUpdate;

    private boolean updateOnly;

    private boolean greenplumDistribution;

    /**
     * 初始化 {@code SupportedPostgresStatementParser}及其父类状态。
     */
    SupportedPostgresStatementParser(PGExprParser expressionParser) {
        super(expressionParser);
    }

    /**
     * 初始化 {@code SupportedPostgresStatementParser}及其父类状态。
     */
    SupportedPostgresStatementParser(PGExprParser expressionParser, boolean greenplumDistribution) {
        super(expressionParser);
        this.greenplumDistribution = greenplumDistribution;
    }

    /**
     * 初始化 {@code SupportedPostgresStatementParser}及其父类状态。
     */
    SupportedPostgresStatementParser(Lexer lexer) {
        super(lexer);
    }

    /**
     * 创建当前方言的建表语句解析器。
     */
    @Override
    public com.alibaba.druid.sql.dialect.postgresql.parser.PGCreateTableParser getSQLCreateTableParser() {
        if (!greenplumDistribution) {
            return super.getSQLCreateTableParser();
        }
        return new com.alibaba.druid.sql.dialect.postgresql.parser.PGCreateTableParser(exprParser) {
            /**
             * 解析建表语句的后续产品专属定义。
             */
            @Override
            protected void parseCreateTableRest(com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement table) {
                super.parseCreateTableRest(table);
                if (!lexer.identifierEquals("DISTRIBUTED")) {
                    return;
                }
                lexer.nextToken();
                accept(Token.BY);
                accept(Token.LPAREN);
                var columns = new java.util.HashSet<String>();
                for (var element : table.getTableElementList()) {
                    if (element instanceof com.alibaba.druid.sql.ast.statement.SQLColumnDefinition column) {
                        columns.add(distributionName(column.getName()));
                    }
                }
                var keys = new java.util.LinkedHashSet<String>();
                do {
                    // 分布表达式、运算符类和参数超出现有
                    // 生成键契约。钩子保留校验后的原始 SQL 文本。
                    String name = distributionName(exprParser.name());
                    if (!columns.contains(name) || !keys.add(name)) {
                        throw new IllegalArgumentException("Unsupported Greenplum distribution key");
                    }
                    if (lexer.token() != Token.COMMA) {
                        break;
                    }
                    lexer.nextToken();
                } while (true);
                accept(Token.RPAREN);
                table.putAttribute(GREENPLUM_DISTRIBUTION_KEYS, java.util.List.copyOf(keys));
            }
        };
    }

    /**
     * 读取当前表分布策略所使用的名称。
     */
    private static String distributionName(SQLName name) {
        if (!(name instanceof SQLIdentifierExpr identifier)) {
            throw new IllegalArgumentException("Unsupported Greenplum distribution key");
        }
        String spelling = identifier.getName();
        return spelling.startsWith("\"") && spelling.endsWith("\"")
                ? spelling.substring(1, spelling.length() - 1).replace("\"\"", "\"")
                : spelling.toLowerCase(java.util.Locale.ROOT);
    }

    static final class SequenceCreate extends SQLCreateSequenceStatement {

        String options;

        cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata definition;
    }

    static final class SequenceAlter extends SQLAlterSequenceStatement {

        String operation;

        SQLName ownedBy;
    }

    /** 已验证的序列语法刻意小于 PostgreSQL 的完整语法。 */
    @Override
    public SQLCreateSequenceStatement parseCreateSequence() {
        accept(Token.CREATE);
        accept(Token.SEQUENCE);
        var result = new SequenceCreate();
        result.setName(qualifiedName(2));
        word("AS");
        String type = currentWord().toLowerCase(java.util.Locale.ROOT);
        lexer.nextToken();
        if (!java.util.Set.of("smallint", "integer", "bigint").contains(type)) {
            throw invalidSequence();
        }
        word("START");
        word("WITH");
        long start = number();
        word("INCREMENT");
        word("BY");
        long increment = number();
        word("MINVALUE");
        long minimum = number();
        word("MAXVALUE");
        long maximum = number();
        word("CACHE");
        long cache = number();
        boolean cycle = !"NO".equals(currentWord());
        if (!cycle) {
            word("NO");
        }
        word("CYCLE");
        var metadata = new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata(
                cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata.Kind.OWNED_DEFAULT,
                "validated", "validated", type, start, increment, minimum, maximum, cache, cycle,
                cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue.unknown());
        result.options = "AS " + type + " " + metadata.optionsSql();
        result.definition = metadata;
        return result;
    }

    /**
     * 解析或构建序列属性变更操作。
     */
    @Override
    public SQLAlterSequenceStatement alterSequence() {
        accept(Token.ALTER);
        accept(Token.SEQUENCE);
        var result = new SequenceAlter();
        result.setName(qualifiedName(2));
        String operation = currentWord();
        lexer.nextToken();
        if ("OWNED".equals(operation)) {
            word("BY");
            result.ownedBy = qualifiedName(3);
            result.operation = "OWNED BY " + result.ownedBy;
        } else if ("RESTART".equals(operation)) {
            word("WITH");
            result.operation = "RESTART WITH " + number();
        } else if ("CACHE".equals(operation)) {
            long value = number();
            if (value < 1) {
                throw invalidSequence();
            }
            result.operation = "CACHE " + value;
        } else {
            throw invalidSequence();
        }
        return result;
    }

    /**
     * 根据模式及对象名称生成限定名称。
     */
    private SQLName qualifiedName(int parts) {
        SQLName name = exprParser.name();
        com.alibaba.druid.sql.ast.SQLExpr cursor = name;
        for (int i = 1; i < parts; i++) {
            if (!(cursor instanceof SQLPropertyExpr property) || property.getName().isBlank()) {
                throw invalidSequence();
            }
            cursor = property.getOwner();
        }
        if (!(cursor instanceof SQLIdentifierExpr identifier) || identifier.getName().isBlank()) {
            throw invalidSequence();
        }
        return name;
    }

    /**
     * 读取当前解析器位置的词元。
     */
    private String currentWord() {
        return lexer.token() == Token.IDENTIFIER ? lexer.stringVal().toUpperCase(java.util.Locale.ROOT) : lexer.token()
                .name();
    }

    /**
     * 按当前解析位置读取或匹配一个词元。
     */
    private void word(String value) {
        if (!value.equals(currentWord())) {
            throw invalidSequence();
        }
        lexer.nextToken();
    }

    /**
     * 按当前值域约定解析或转换数值。
     */
    private long number() {
        boolean negative = lexer.token() == Token.SUB;
        if (negative) {
            lexer.nextToken();
        }
        if (lexer.token() != Token.LITERAL_INT) {
            throw invalidSequence();
        }
        var value = new java.math.BigInteger(lexer.integerValue().toString());
        lexer.nextToken();
        return (negative ? value.negate() : value).longValueExact();
    }

    /**
     * 根据当前输入构建并返回 {@code IllegalArgumentException}。
     */
    private static IllegalArgumentException invalidSequence() {
        return new IllegalArgumentException("Unsupported sequence structure");
    }

    /**
     * 解析当前 UPDATE 语句。
     */
    @Override
    public SQLUpdateStatement parseUpdateStatement() {
        boolean previousAccepting = acceptingUpdate;
        boolean previousOnly = updateOnly;
        acceptingUpdate = true;
        updateOnly = false;
        try {
            PGUpdateStatement statement = (PGUpdateStatement) super.parseUpdateStatement();
            statement.setOnly(updateOnly);
            return statement;
        } finally {
            acceptingUpdate = previousAccepting;
            updateOnly = previousOnly;
        }
    }

    /**
     * 接收当前输入并更新当前处理器的状态。
     */
    @Override
    public void accept(Token token) {
        super.accept(token);
        if (acceptingUpdate && token == Token.UPDATE) {
            acceptingUpdate = false;
            if (lexer.token() == Token.ONLY) {
                lexer.nextToken();
                updateOnly = true;
            }
        }
    }
}
