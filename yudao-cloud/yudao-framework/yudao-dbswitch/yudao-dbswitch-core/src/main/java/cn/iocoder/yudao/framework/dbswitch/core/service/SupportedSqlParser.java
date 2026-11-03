package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;
import com.alibaba.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlOutputVisitor;
import com.alibaba.druid.sql.dialect.postgresql.parser.PGExprParser;
import com.alibaba.druid.sql.dialect.postgresql.visitor.PGOutputVisitor;
import com.alibaba.druid.sql.parser.Token;
import com.alibaba.druid.sql.parser.SQLParserFeature;
import com.alibaba.druid.sql.parser.SQLParserUtils;

import java.io.StringReader;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;

/** 使用 Druid 解析器扩展点处理有界 PostgreSQL 标识生成序列选项。 */
final class SupportedSqlParser {

    private static final Pattern READ_ONLY_CONTROL = Pattern.compile(
            "(?i)\\b(?:INTO|OUTFILE|DUMPFILE|UPDLOCK|XLOCK|HOLDLOCK|TABLOCKX|SETTINGS|PROCEDURE)\\b"
                    + "|\\bFOR\\s+(?:UPDATE|SHARE|NO\\s+KEY\\s+UPDATE|KEY\\s+SHARE|BROWSE)\\b"
                    + "|\\bLOCK\\s+IN\\s+SHARE\\s+MODE\\b");

    /**
     * 初始化 {@code SupportedSqlParser}。
     */
    private SupportedSqlParser() {
    }

    /**
     * 判断当前产品是否采用 PostgreSQL 语法。
     */
    static boolean usesPostgresSyntax(ProductTypeEnum product) {
        return product == ProductTypeEnum.POSTGRESQL || product == ProductTypeEnum.OPENGAUSS;
    }

    /**
     * 返回当前解析。
     */
    static List<SQLStatement> parse(ProductTypeEnum product, String sql) {
        if (!usesPostgresSyntax(product)) {
            return SQLUtils.parseStatements(sql, DbType.mysql);
        }
        var parser = new SupportedPostgresStatementParser(new IdentityParser(sql));
        var statements = parser.parseStatementList();
        if (parser.getLexer().token() != Token.EOF) {
            throw new IllegalArgumentException("Unconsumed SQL input");
        }
        return statements;
    }

    /**
     * 只读解析使用独立产品映射；不能改变脚本、DML 和迁移入口的原有准入集合。
     * 映射只承诺可解析的 SQL 子集，不代表对应服务器已通过真实连接验收。
     */
    static DbType readOnlyDialect(ProductTypeEnum product) {
        if (product == null) {
            throw new UnsupportedOperationException("只读 SQL 分类需要明确的数据库产品");
        }
        return switch (product) {
            // 仅放开普通 PostgreSQL 兼容查询子集，不承诺厂商扩展语法。
            case POSTGRESQL, GREENPLUM, HIGHGO, KINGBASE, OPENGAUSS, UXDB, GBASE8C, GAUSSDB, INFLUXDB3 -> DbType.postgresql;
            // OceanBase 仅对应现有 MySQL 协议配置；Oracle 模式须另行建模和验收。
            case MYSQL, MARIADB, TIDB, DORIS, STARROCKS, GBASE8A, OCEANBASE -> DbType.mysql;
            case ORACLE -> DbType.oracle;
            case DM -> DbType.dm;
            case OSCAR -> DbType.oscar;
            case SQLSERVER, SQLSERVER2000, SYBASE -> DbType.sqlserver;
            case DB2 -> DbType.db2;
            case HIVE -> DbType.hive;
            case SQLITE3 -> DbType.sqlite;
            case CLICKHOUSE -> DbType.clickhouse;
            case TDENGINE -> DbType.taosdata;
            case PHOENIX -> DbType.phoenix;
            case SAPHANA -> DbType.sapdb;
            case PRESTO -> DbType.presto;
            case UNKNOWN, MONGODB, ELASTICSEARCH, ODPS, NEO4J, MILVUS, INFLUXDB2 ->
                    throw new UnsupportedOperationException("该产品没有受支持的 JDBC 只读 SQL 分类入口：" + product);
        };
    }

    /**
     * 先校验词法和完整消费，再检查 AST；原 SQL 不重写，模式敏感或无法证明同义的语法直接拒绝。
     */
    static List<SQLStatement> parseReadOnly(ProductTypeEnum product, String sql) {
        DbType dialect = readOnlyDialect(product);
        boolean mysql = dialect == DbType.mysql;
        var source = readOnlyInput(product, sql);
        if (READ_ONLY_CONTROL.matcher(source.executableText()).find()
                || mysql && source.executableText().indexOf('"') >= 0) {
            throw new IllegalArgumentException("只读查询不允许写入、锁定、会话控制或模式敏感引号");
        }
        var lexer = SQLParserUtils.createLexer(source.script(), dialect, SQLParserFeature.KeepComments);
        do {
            lexer.nextToken();
            if (lexer.token() == Token.HINT || lexer.token() == Token.ERROR) {
                throw new IllegalArgumentException("只读查询不允许 SQL 提示或无效词法");
            }
        } while (lexer.token() != Token.EOF);
        if (dialect == DbType.postgresql) {
            return parse(ProductTypeEnum.POSTGRESQL, source.script());
        }
        var parser = SQLParserUtils.createSQLStatementParser(source.script(), dialect,
                SQLParserFeature.KeepComments, SQLParserFeature.KeepNameQuotes);
        var statements = parser.parseStatementList();
        if (parser.getLexer().token() != Token.EOF) {
            throw new IllegalArgumentException("只读查询存在未消费的语法");
        }
        return statements;
    }

    /** 原文分段不改变字面量、标识符、占位符顺序或厂商表达式。 */
    static String readOnlyStatement(ProductTypeEnum product, String sql) {
        var source = readOnlyInput(product, sql);
        if (source.statements().size() != 1) {
            throw new IllegalArgumentException("只读入口需要唯一可执行语句");
        }
        return source.statements().getFirst();
    }

    private static SqlScriptInput.HookInput readOnlyInput(ProductTypeEnum product, String sql) {
        DbType dialect = readOnlyDialect(product);
        return SqlScriptInput.readReadOnlyStatements(new StringReader(sql), dialect == DbType.postgresql,
                dialect == DbType.mysql, dialect == DbType.sqlserver || dialect == DbType.sqlite);
    }

    /** 仅供钩子使用的 Greenplum 扩展；外部 SQL 解析器契约不变。 */
    static List<SQLStatement> parseGreenplumHook(String sql) {
        var parser = new SupportedPostgresStatementParser(new IdentityParser(sql), true);
        var statements = parser.parseStatementList();
        if (parser.getLexer().token() != Token.EOF) {
            throw new IllegalArgumentException("Unconsumed SQL input");
        }
        return statements;
    }

    record Rendered(String sql, int parameterCount) {

    }

    /**
     * 将当前结构定义渲染为目标 SQL 或表达式。
     */
    static Rendered render(ProductTypeEnum product, SQLStatement statement) {
        StringBuilder sql = new StringBuilder();
        int[] bindingCount = {0};
        if (usesPostgresSyntax(product)) {
            statement.accept(new PGOutputVisitor(sql) {
                /**
                 * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
                 */
                @Override
                public boolean visit(com.alibaba.druid.sql.ast.expr.SQLCharExpr text) {
                    if (text instanceof NativeDollarString literal) {
                        print0(literal.tag);
                        print0(literal.getText());
                        print0(literal.tag);
                        return false;
                    }
                    if (text instanceof NativeCollationName collation) {
                        print0(collation.spelling);
                        return false;
                    }
                    return super.visit(text);
                }

                /**
                 * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
                 */
                @Override
                public boolean visit(com.alibaba.druid.sql.ast.statement.SQLCreateSequenceStatement sequence) {
                    if (!(sequence instanceof SupportedPostgresStatementParser.SequenceCreate controlled)) {
                        throw new IllegalArgumentException("Unverified sequence AST");
                    }
                    print0("CREATE SEQUENCE ");
                    sequence.getName().accept(this);
                    print0(" " + controlled.options);
                    return false;
                }

                /**
                 * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
                 */
                @Override
                public boolean visit(com.alibaba.druid.sql.ast.statement.SQLAlterSequenceStatement sequence) {
                    if (!(sequence instanceof SupportedPostgresStatementParser.SequenceAlter controlled)) {
                        throw new IllegalArgumentException("Unverified sequence AST");
                    }
                    print0("ALTER SEQUENCE ");
                    sequence.getName().accept(this);
                    print0(" " + controlled.operation);
                    return false;
                }

                /**
                 * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
                 */
                @Override
                public boolean visit(SQLVariantRefExpr parameter) {
                    requireParameter(parameter, bindingCount);
                    return super.visit(parameter);
                }

                /**
                 * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
                 */
                @Override
                public boolean visit(SQLColumnDefinition.Identity identity) {
                    if (identity instanceof NativeIdentity nativeIdentity) {
                        print0("IDENTITY " + nativeIdentity.options);
                        return false;
                    }
                    return super.visit(identity);
                }
            });
        } else {
            statement.accept(new MySqlOutputVisitor(sql) {
                /**
                 * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
                 */
                @Override
                public boolean visit(SQLVariantRefExpr parameter) {
                    requireParameter(parameter, bindingCount);
                    return super.visit(parameter);
                }
            });
        }
        return new Rendered(sql.toString(), bindingCount[0]);
    }

    /**
     * 校验并取得参数。
     */
    private static void requireParameter(SQLVariantRefExpr parameter, int[] count) {
        if (!"?".equals(parameter.getName())) {
            throw new IllegalArgumentException("Unsupported SQL parameter");
        }
        count[0]++;
    }

    private static final class NativeIdentity extends SQLColumnDefinition.Identity {

        final String options;

        /**
         * 初始化 {@code NativeIdentity}。
         */
        NativeIdentity(String options) {
            this.options = options;
        }
    }

    private static final class NativeDollarString extends com.alibaba.druid.sql.ast.expr.SQLCharExpr {

        final String tag;

        /**
         * 初始化 {@code NativeDollarString}及其父类状态。
         */
        NativeDollarString(String text, String tag) {
            super(text);
            this.tag = tag;
        }
    }

    /** 解析时捕获精确排序规则词素；渲染不得重新加引号或折叠大小写。 */
    private static final class NativeCollationName extends com.alibaba.druid.sql.ast.expr.SQLCharExpr {

        final String spelling;

        /**
         * 初始化 {@code NativeCollationName}及其父类状态。
         */
        NativeCollationName(String spelling) {
            super(spelling);
            this.spelling = spelling;
        }
    }

    private static final class IdentityParser extends PGExprParser {

        /**
         * 初始化 {@code IdentityParser}及其父类状态。
         */
        IdentityParser(String sql) {
            super(initializedLexer(sql));
        }

        /**
         * 创建并初始化当前 SQL 词法分析器。
         */
        private static ExactPostgresLexer initializedLexer(String sql) {
            var lexer = new ExactPostgresLexer(sql, true);
            lexer.nextToken();
            return lexer;
        }

        /**
         * 返回当前主。
         */
        @Override
        public com.alibaba.druid.sql.ast.SQLExpr primary() {
            var exact = (ExactPostgresLexer) lexer;
            String tag = exact.dollarStringTag();
            if (tag == null) {
                return super.primary();
            }
            var expression = new NativeDollarString(lexer.stringVal(), tag);
            lexer.nextToken();
            return primaryRest(expression);
        }

        /**
         * 解析列剩余部分。
         */
        @Override
        public SQLColumnDefinition parseColumnRest(SQLColumnDefinition column) {
            column = super.parseColumnRest(column);
            // Druid 的 PG 重写在 GENERATED 后立即返回。对于 IDENTITY PRIMARY KEY 和 IDENTITY NOT NULL
            // 等后缀，应恢复常规列约束解析器。
            while (column.getIdentity() != null && Set.of(Token.NOT, Token.PRIMARY, Token.UNIQUE,
                    Token.CONSTRAINT, Token.CHECK, Token.REFERENCES).contains(lexer.token())) {
                int previousPosition = lexer.pos();
                column = super.parseColumnRest(column);
                if (lexer.pos() == previousPosition) {
                    throw new IllegalArgumentException("Unconsumed identity column constraint");
                }
            }
            // Druid 仅在 parseCharTypeRest（text/varchar/char）内消费 COLLATE，并将其存到
            // 数据类型上但丢失引号；其他类型形式不会消费该子句，访问器也会将其丢弃。
            // 保留精确词素，并在子句后恢复常规约束解析器。
            if (lexer.token() == Token.IDENTIFIER && "collate".equalsIgnoreCase(lexer.stringVal())) {
                lexer.nextToken();
                if (column.getCollateExpr() != null) {
                    throw new IllegalArgumentException("Duplicate column collation");
                }
                column.setCollateExpr(collationName());
                column = super.parseColumnRest(column);
            } else if (column.getCollateExpr() == null && column.getDataType() instanceof com.alibaba.druid.sql.ast.statement.SQLCharacterDataType character
                    && character.getCollate() != null && !character.getCollate().isEmpty()) {
                // parseCharTypeRest 用 "." 连接词素，带引号部分保留引号；渲染前校验
                // 名称能否恢复，否则明确拒绝该计划。
                if (!COLLATION_LEXEMES.matcher(character.getCollate()).matches()) {
                    throw new IllegalArgumentException("Unsupported column collation spelling");
                }
                column.setCollateExpr(new NativeCollationName(character.getCollate()));
            }
            return column;
        }

        /**
         * 返回排序规则名称。
         */
        private com.alibaba.druid.sql.ast.SQLExpr collationName() {
            StringBuilder spelling = new StringBuilder();
            if (lexer.token() != Token.IDENTIFIER && lexer.token() != Token.LITERAL_ALIAS) {
                throw new IllegalArgumentException("Collation name is required");
            }
            spelling.append(lexer.stringVal());
            lexer.nextToken();
            while (lexer.token() == Token.DOT) {
                lexer.nextToken();
                if (lexer.token() != Token.IDENTIFIER && lexer.token() != Token.LITERAL_ALIAS) {
                    throw new IllegalArgumentException("Collation name is required");
                }
                spelling.append('.').append(lexer.stringVal());
                lexer.nextToken();
            }
            return new NativeCollationName(spelling.toString());
        }

        private static final java.util.regex.Pattern COLLATION_LEXEMES = java.util.regex.Pattern.compile(
                "(?:[A-Za-z_][A-Za-z0-9_$]*|\"(?:[^\"]|\"\")*\")(?:\\.(?:[A-Za-z_][A-Za-z0-9_$]*|\"(?:[^\"]|\"\")*\"))*");

        /**
         * 解析身份信息。
         */
        @Override
        protected SQLColumnDefinition.Identity parseIdentity() {
            if (lexer.token() != Token.LPAREN) {
                return new SQLColumnDefinition.Identity();
            }
            accept(Token.LPAREN);
            var seen = new HashSet<String>();
            var options = new ArrayList<String>();
            Map<String, Long> values = new HashMap<>();
            while (lexer.token() != Token.RPAREN) {
                String option = word();
                lexer.nextToken();
                if ("SEQUENCE".equals(option)) {
                    if (!seen.add(option) || !"NAME".equals(word())) {
                        throw invalid();
                    }
                    lexer.nextToken();
                    var name = name();
                    if (!(name instanceof com.alibaba.druid.sql.ast.expr.SQLPropertyExpr qualified)
                            || !(qualified.getOwner() instanceof com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr)
                            || qualified.getName().isBlank()) {
                        throw invalid();
                    }
                    options.add("SEQUENCE NAME " + name);
                    continue;
                }
                if ("NO".equals(option)) {
                    String disabled = word();
                    lexer.nextToken();
                    if (!Set.of("MINVALUE", "MAXVALUE", "CYCLE").contains(disabled) || !seen.add(disabled)) {
                        throw invalid();
                    }
                    options.add("NO " + disabled);
                    continue;
                }
                if (!Set.of("START", "INCREMENT", "MINVALUE", "MAXVALUE", "CACHE", "CYCLE").contains(option)
                        || !seen.add(option)) {
                    throw invalid();
                }
                if ("CYCLE".equals(option)) {
                    options.add("CYCLE");
                    continue;
                }
                if ("START".equals(option) && "WITH".equals(word()) || "INCREMENT".equals(option) && "BY".equals(
                        word())) {
                    lexer.nextToken();
                }
                boolean negative = lexer.token() == Token.SUB;
                if (negative || lexer.token() == Token.PLUS) {
                    lexer.nextToken();
                }
                if (lexer.token() != Token.LITERAL_INT) {
                    throw invalid();
                }
                BigInteger number = new BigInteger(lexer.integerValue().toString());
                if (negative) {
                    number = number.negate();
                }
                long value = number.longValueExact();
                lexer.nextToken();
                if ("INCREMENT".equals(option) && value == 0 || "CACHE".equals(option) && value <= 0) {
                    throw invalid();
                }
                values.put(option, value);
                options.add(option + ("START".equals(option) ? " WITH " : "INCREMENT".equals(
                        option) ? " BY " : " ") + value);
            }
            accept(Token.RPAREN);
            if (options.isEmpty()) {
                throw invalid();
            }
            if (values.containsKey("MINVALUE") && values.containsKey("MAXVALUE")
                    && values.get("MINVALUE") >= values.get("MAXVALUE")) {
                throw invalid();
            }
            if (values.containsKey("START") && (values.containsKey("MINVALUE") && values.get("START") < values.get(
                    "MINVALUE")
                    || values.containsKey("MAXVALUE") && values.get("START") > values.get("MAXVALUE"))) {
                throw invalid();
            }
            return new NativeIdentity("(" + String.join(" ", options) + ")");
        }

        /**
         * 按当前解析位置读取或匹配一个词元。
         */
        private String word() {
            return lexer.token() == Token.IDENTIFIER ? lexer.stringVal().toUpperCase(Locale.ROOT) : lexer.token()
                    .name();
        }

        /**
         * 构造表示当前输入、状态或操作不符合已声明约定的异常。
         */
        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException("Unsupported identity sequence options");
        }
    }
}
