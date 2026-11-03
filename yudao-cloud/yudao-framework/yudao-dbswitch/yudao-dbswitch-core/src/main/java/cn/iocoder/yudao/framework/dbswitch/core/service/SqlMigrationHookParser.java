package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.postgresql.visitor.PGASTVisitorAdapter;
import com.alibaba.druid.sql.parser.SQLParserUtils;
import com.alibaba.druid.sql.parser.Token;

import java.io.Reader;
import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * 可信 JDBC 迁移钩子的语法准入，与外部查询授权分开。
 * 每个产品选择实际的 Druid 方言；部分厂商方言只有 Druid 的
 * 通用解析器，所以此契约仅确认普通 SQL 语法，不保证厂商原生语法
 * 或执行兼容性。整段输入通过后，保留原始 SQL 标记。
 */
final class SqlMigrationHookParser {

    // 钩子使用 Statement 执行，不绑定参数。同时拒绝会话变量，
    // 避免使用连接内变量代替未提供的参数。
    private static final Pattern UNBOUND_INPUT = Pattern.compile("\\?|@|(?<!:):(?!:)[A-Za-z0-9_]");

    /**
     * 初始化 {@code SqlMigrationHookParser}。
     */
    private SqlMigrationHookParser() {
    }

    /**
     * 返回当前解析。
     */
    static SqlScriptPlan parse(ProductTypeEnum product, Reader input) {
        DbType dialect = dialect(product);
        try {
            var source = SqlScriptInput.readMigrationHook(input, postgresStrings(product),
                    mysqlComments(product), bracketIdentifiers(product));
            if (!postgresStrings(product) && UNBOUND_INPUT.matcher(source.executableText()).find()) {
                throw new IllegalArgumentException("Migration hooks cannot contain unbound parameters");
            }
            var steps = new ArrayList<SqlScriptPlan.Step>();
            for (String segment : source.statements()) {
                String sql = segment.strip();
                if (sql.isEmpty()) {
                    continue;
                }
                if (MigrationHookAnalyzeSql.admits(product, sql)) {
                    if (steps.size() >= 500) {
                        throw new IllegalArgumentException("Too many migration hook statements");
                    }
                    // 通用白名单和外部解析器仍拒绝 OTHER。
                    // 此单一维护语法仅由钩子入口接受。
                    steps.add(new SqlScriptPlan.Step(steps.size() + 1, sql, SqlStatementClassifier.Kind.OTHER));
                    continue;
                }
                java.util.List<SQLStatement> statements;
                if (product == ProductTypeEnum.GREENPLUM) {
                    statements = SupportedSqlParser.parseGreenplumHook(sql);
                } else if (SupportedSqlParser.usesPostgresSyntax(product)) {
                    // 保留现有精确 PG 词法、标识和序列语法。
                    // 钩子在该既有解析器校验后，保留原始 SQL。
                    statements = SupportedSqlParser.parse(product, sql);
                } else {
                    var parser = SQLParserUtils.createSQLStatementParser(sql, dialect);
                    statements = parser.parseStatementList();
                    if (parser.getLexer().token() != Token.EOF) {
                        throw new IllegalArgumentException("Unconsumed migration hook syntax");
                    }
                }
                if (statements.size() > 1) {
                    throw new IllegalArgumentException("Multiple migration hook statements in one step");
                }
                // 尾部注释和空分隔符不是可执行步骤。
                if (statements.isEmpty()) {
                    continue;
                }
                if (steps.size() >= 500) {
                    throw new IllegalArgumentException("Too many migration hook statements");
                }
                var statement = statements.getFirst();
                if (postgresStrings(product)) {
                    // PG 的 ? / @> / <@ 是表达式运算符，不是未绑定参数。
                    // 对此类语法使用实际 AST，不按标点匹配。
                    statement.accept(new PGASTVisitorAdapter() {
                        /**
                         * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
                         */
                        @Override
                        public void preVisit(SQLObject node) {
                            if (node instanceof SQLVariantRefExpr) {
                                throw new IllegalArgumentException("Migration hooks cannot contain unbound parameters");
                            }
                        }
                    });
                }
                var kind = classify(statement);
                if (kind == SqlStatementClassifier.Kind.OTHER) {
                    throw new IllegalArgumentException("Unsupported migration hook statement");
                }
                if (product == ProductTypeEnum.SQLITE3 && statement instanceof SQLTruncateStatement) {
                    throw new IllegalArgumentException("SQLite has no TRUNCATE statement");
                }
                steps.add(new SqlScriptPlan.Step(steps.size() + 1, sql, kind));
            }
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("Migration hook has no executable statements");
            }
            return new SqlScriptPlan(product, SqlScriptPlan.digest(source.script()), steps);
        } catch (RuntimeException failure) {
            // 解析器异常通常包含 SQL 或字面量。绝不保留其消息或原因。
            throw new IllegalArgumentException("Migration hook cannot be planned using the supported syntax");
        }
    }

    /**
     * 根据解析后的语句结构判断 SQL 操作类别。
     */
    private static SqlStatementClassifier.Kind classify(SQLStatement statement) {
        if (statement instanceof SQLSelectStatement) {
            return SqlStatementClassifier.Kind.QUERY;
        }
        if (statement instanceof SQLInsertStatement || statement instanceof SQLUpdateStatement
                || statement instanceof SQLDeleteStatement || statement instanceof SQLMergeStatement
                || statement instanceof SQLReplaceStatement) {
            return SqlStatementClassifier.Kind.DML;
        }
        if (statement instanceof SQLCreateTableStatement || statement instanceof SQLCreateIndexStatement
                || statement instanceof SQLDropTableStatement || statement instanceof SQLDropIndexStatement
                || statement instanceof SQLAlterTableStatement || statement instanceof SQLAlterIndexStatement
                || statement instanceof SupportedPostgresStatementParser.SequenceCreate
                || statement instanceof SupportedPostgresStatementParser.SequenceAlter
                || statement instanceof SQLTruncateStatement
                || statement instanceof SQLCommentStatement) {
            return SqlStatementClassifier.Kind.DDL;
        }
        return SqlStatementClassifier.Kind.OTHER;
    }

    /**
     * 识别 PostgreSQL 语法中的字符串字面量。
     */
    private static boolean postgresStrings(ProductTypeEnum product) {
        return switch (product) {
            case POSTGRESQL, GREENPLUM, HIGHGO, KINGBASE, OPENGAUSS, UXDB -> true;
            default -> false;
        };
    }

    /**
     * 识别 MySQL 语法中的注释范围。
     */
    private static boolean mysqlComments(ProductTypeEnum product) {
        return switch (product) {
            case MYSQL, MARIADB, TIDB, OCEANBASE, GBASE8A, DORIS, STARROCKS -> true;
            default -> false;
        };
    }

    /**
     * 识别 SQL Server 方括号包围的标识符。
     */
    private static boolean bracketIdentifiers(ProductTypeEnum product) {
        return product == ProductTypeEnum.SQLSERVER || product == ProductTypeEnum.SQLSERVER2000
                || product == ProductTypeEnum.SYBASE || product == ProductTypeEnum.SQLITE3;
    }

    /**
     * 解析当前产品对应的数据库方言。
     */
    private static DbType dialect(ProductTypeEnum product) {
        if (product == null) {
            throw new IllegalArgumentException("Migration hook product is required");
        }
        return switch (product) {
            case MYSQL -> DbType.mysql;
            case MARIADB -> DbType.mariadb;
            case TIDB -> DbType.tidb;
            case OCEANBASE -> DbType.oceanbase; // 现有产品契约为 MySQL 兼容模式。
            case ORACLE -> DbType.oracle;
            case DM -> DbType.dm;
            case OSCAR -> DbType.oscar;
            case SQLSERVER, SQLSERVER2000 -> DbType.sqlserver;
            case SYBASE -> DbType.sybase;
            case POSTGRESQL -> DbType.postgresql;
            case GREENPLUM -> DbType.greenplum;
            case HIGHGO -> DbType.highgo;
            case KINGBASE -> DbType.kingbase;
            case OPENGAUSS -> DbType.gaussdb;
            case UXDB -> DbType.postgresql; // 仅支持普通 PostgreSQL 兼容 SQL，不承诺原生语法。
            case DB2 -> DbType.db2;
            case GBASE8A -> DbType.gbase;
            case HIVE -> DbType.hive;
            case SQLITE3 -> DbType.sqlite;
            case DORIS -> DbType.doris;
            case STARROCKS -> DbType.starrocks;
            case CLICKHOUSE -> DbType.clickhouse;
            case TDENGINE -> DbType.taosdata;
            case UNKNOWN, MONGODB, ELASTICSEARCH, INFLUXDB3, GBASE8C, GAUSSDB, PHOENIX, SAPHANA, PRESTO, ODPS, NEO4J,
                 MILVUS, INFLUXDB2 -> throw new UnsupportedOperationException(
                    "Migration SQL hooks require a supported JDBC SQL dialect for this product");
        };
    }
}
