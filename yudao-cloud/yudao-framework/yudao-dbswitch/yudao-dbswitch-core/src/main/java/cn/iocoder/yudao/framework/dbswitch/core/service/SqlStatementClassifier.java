package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.SQLHint;
import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLDbLinkExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLSequenceExpr;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;
import com.alibaba.druid.sql.ast.statement.SQLAssignItem;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.clickhouse.ast.CKSelectQueryBlock;
import com.alibaba.druid.sql.dialect.db2.ast.stmt.DB2SelectQueryBlock;
import com.alibaba.druid.sql.dialect.oscar.ast.stmt.OscarSelectQueryBlock;
import com.alibaba.druid.sql.dialect.oscar.ast.stmt.OscarFunctionTableSource;
import com.alibaba.druid.sql.dialect.postgresql.ast.stmt.PGSelectQueryBlock;
import com.alibaba.druid.sql.dialect.sqlserver.ast.SQLServerSelectQueryBlock;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.alibaba.druid.sql.dialect.postgresql.visitor.PGASTVisitor;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitor;
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleASTVisitor;
import com.alibaba.druid.sql.dialect.oscar.visitor.OscarASTVisitor;
import com.alibaba.druid.sql.dialect.sqlserver.visitor.SQLServerASTVisitor;
import com.alibaba.druid.sql.dialect.db2.visitor.DB2ASTVisitor;
import com.alibaba.druid.sql.dialect.hive.visitor.HiveASTVisitor;
import com.alibaba.druid.sql.dialect.clickhouse.visitor.CKASTVisitor;
import com.alibaba.druid.sql.dialect.presto.visitor.PrestoASTVisitor;
import com.alibaba.druid.sql.dialect.phoenix.visitor.PhoenixASTVisitor;
import com.alibaba.druid.sql.ast.statement.SQLAlterStatement;
import com.alibaba.druid.sql.ast.statement.SQLCallStatement;
import com.alibaba.druid.sql.ast.statement.SQLCommentStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateStatement;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLDropStatement;
import com.alibaba.druid.sql.ast.statement.SQLExplainStatement;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLMergeStatement;
import com.alibaba.druid.sql.ast.statement.SQLReplaceStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLSetStatement;
import com.alibaba.druid.sql.ast.statement.SQLTruncateStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 将用户 SQL 分类为查询 / DML / DDL / 多语句，供只读执行边界使用。
 * <p>
 * 本类不执行 SQL，不替代对象授权、参数绑定或数据库账户权限。
 * 脚本分类保持原五产品范围；只读分类使用独立的产品方言和受限语法，不扩大写入准入。
 */
public final class SqlStatementClassifier {

    public enum Kind {
        QUERY, DML, DDL, MULTI, OTHER
    }

    public record Classification(Kind kind, int statementCount, String reason) {

        /**
         * 检查或构建不产生写入副作用的查询语句。
         */
        public boolean readOnlyQuery() {
            return kind == Kind.QUERY && statementCount == 1;
        }
    }

    /** 原文及 SQLLimit 结构信息；不含执行人、连接或业务授权配置。 */
    public record PreparedReadOnlyQuery(String sql, boolean topLevelPagination, boolean parameterizedPagination) {
    }

    private static final int MAX_SQL_LENGTH = 100_000;
    private static final int MAX_READ_ONLY_SQL_LENGTH = 25_000;

    // 只接纳已知查询函数；自定义、限定名称、网络、文件、锁、序列和会话函数不从 SELECT 外形推断只读。
    private static final Set<String> READ_ONLY_FUNCTIONS = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "ABS", "ROUND", "CEIL", "CEILING", "FLOOR", "POWER",
            "SQRT", "MOD", "SIGN", "EXP", "LN", "LOG", "LOG10", "TRUNC", "TRUNCATE",
            "LOWER", "UPPER", "LENGTH", "CHAR_LENGTH", "CHARACTER_LENGTH", "OCTET_LENGTH", "SUBSTR", "SUBSTRING",
            "TRIM", "LTRIM", "RTRIM", "CONCAT", "CONCAT_WS", "REPLACE", "LEFT", "RIGHT", "LPAD", "RPAD",
            "POSITION", "LOCATE", "INSTR", "COALESCE", "NULLIF", "IFNULL", "NVL", "NVL2", "IF",
            "CAST", "CONVERT", "DATE", "DATETIME", "TIME", "TIMESTAMP", "DATE_FORMAT", "DATE_TRUNC",
            "EXTRACT", "YEAR", "MONTH", "DAY", "DAYOFMONTH", "HOUR", "MINUTE", "SECOND", "NOW",
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "TO_CHAR", "TO_DATE", "TO_TIMESTAMP",
            "DATEDIFF", "TIMESTAMPDIFF", "DATE_ADD", "DATE_SUB", "ADD_MONTHS", "LAST_DAY",
            "ROW_NUMBER", "RANK", "DENSE_RANK", "LAG", "LEAD", "FIRST_VALUE", "LAST_VALUE", "NTH_VALUE", "NTILE",
            "JSON_VALUE", "JSON_QUERY", "JSON_EXTRACT", "JSON_UNQUOTE", "JSON_LENGTH", "ARRAY_LENGTH", "CARDINALITY",
            "STRING_AGG", "GROUP_CONCAT", "LISTAGG", "BOOL_AND", "BOOL_OR", "STDDEV", "STDDEV_POP", "STDDEV_SAMP",
            "VARIANCE", "VAR_POP", "VAR_SAMP", "MD5", "SHA1", "SHA2", "HEX", "UNHEX", "ENCODE", "DECODE");

    /**
     * 初始化 {@code SqlStatementClassifier}。
     */
    private SqlStatementClassifier() {
    }

    /**
     * 返回原有脚本分类支持范围；调用方不能用只读产品集合扩大 DML 或 DDL 能力。
     */
    public static boolean supports(ProductTypeEnum product) {
        return product == ProductTypeEnum.POSTGRESQL
                || product == ProductTypeEnum.OPENGAUSS
                || product == ProductTypeEnum.MYSQL
                || product == ProductTypeEnum.MARIADB
                || product == ProductTypeEnum.TIDB;
    }

    /** 返回具有独立只读 SQL 方言映射的产品；真实服务器能力仍由对应集成验收证明。 */
    public static boolean supportsReadOnly(ProductTypeEnum product) {
        try {
            SupportedSqlParser.readOnlyDialect(product);
            return true;
        } catch (UnsupportedOperationException unsupported) {
            return false;
        }
    }

    /**
     * 根据解析后的语句结构判断 SQL 操作类别。
     */
    public static Classification classify(ProductTypeEnum product, String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 为空");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            throw new IllegalArgumentException("SQL 超过长度限制");
        }
        dialect(product);
        List<SQLStatement> statements;
        try {
            statements = SupportedSqlParser.parse(product, sql);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("SQL 无法解析为受支持的语句");
        }
        if (statements == null || statements.isEmpty()) {
            throw new IllegalArgumentException("SQL 无法解析为受支持的语句");
        }
        if (statements.size() != 1) {
            return new Classification(Kind.MULTI, statements.size(), "只读路径不允许多语句");
        }
        return classifyOne(statements.get(0), 1);
    }

    /**
     * 独立只读分类入口；沿用有界输入的单句 25,000 字符限制。
     * 不会改变 classify()/supports() 的既有写入和脚本消费者契约。
     */
    public static Classification classifyReadOnly(ProductTypeEnum product, String sql) {
        if (sql == null || sql.isBlank() || sql.length() > MAX_READ_ONLY_SQL_LENGTH) {
            throw new IllegalArgumentException("SQL 为空或超过长度限制");
        }
        SupportedSqlParser.readOnlyDialect(product);
        try {
            var statements = SupportedSqlParser.parseReadOnly(product, sql);
            if (statements.isEmpty()) {
                throw new IllegalArgumentException("只读查询没有可执行语句");
            }
            if (statements.size() != 1) {
                return new Classification(Kind.MULTI, statements.size(), "只读路径不允许多语句");
            }
            var statement = statements.getFirst();
            if (!(statement instanceof SQLSelectStatement select)) {
                return new Classification(Kind.OTHER, 1, "只读路径仅允许 SELECT");
            }
            if (!isReadOnlySelect(select)) {
                return new Classification(Kind.OTHER, 1, "SELECT 含未验证的副作用、函数、变量、锁或控制结构");
            }
            return new Classification(Kind.QUERY, 1, "SELECT");
        } catch (RuntimeException invalid) {
            // 解析器异常可能含原 SQL、参数或连接细节，不向调用方传播原文或 cause。
            throw new IllegalArgumentException("SQL 无法解析为受支持的只读查询");
        }
    }

    /**
     * 要求当前语句为允许的只读查询。
     */
    public static void assertReadOnlyQuery(ProductTypeEnum product, String sql) {
        Classification classification = classifyReadOnly(product, sql);
        if (!classification.readOnlyQuery()) {
            throw new IllegalArgumentException("只读路径仅允许单条 SELECT 查询，实际为 "
                    + classification.kind() + "：" + classification.reason());
        }
    }

    /**
     * 验证后取得唯一只读语句的原文，去掉终止分号及其后的纯注释，供安全追加分页或包裹计数。
     * 不做 AST 重写；没有终止符的行尾注释仍保留，调用方追加内容前必须换行。
     */
    public static String prepareReadOnlyQuery(ProductTypeEnum product, String sql) {
        assertReadOnlyQuery(product, sql);
        return SupportedSqlParser.readOnlyStatement(product, sql);
    }

    /** 复用只读 AST 识别主查询/UNION 的分页和任意层分页参数，不重写或内联业务绑定值。 */
    public static PreparedReadOnlyQuery prepareReadOnlyQueryPlan(ProductTypeEnum product, String sql) {
        String statement = prepareReadOnlyQuery(product, sql);
        var select = (SQLSelectStatement) SupportedSqlParser.parseReadOnly(product, statement).getFirst();
        var visitor = new ReadOnlyVisitor();
        select.accept(visitor);
        var query = select.getSelect().getQuery();
        boolean topLevelPagination = query instanceof SQLSelectQueryBlock block && block.getLimit() != null
                || query instanceof SQLUnionQuery union && union.getLimit() != null;
        return new PreparedReadOnlyQuery(statement, topLevelPagination, visitor.parameterizedPagination);
    }

    /**
     * 根据当前输入构建并返回 {@code Classification}。
     */
    private static Classification classifyOne(SQLStatement statement, int count) {
        if (statement instanceof SQLSelectStatement select) {
            if (hasSideEffectSelect(select)) {
                return new Classification(Kind.DML, count, "SELECT 含 INTO/FOR UPDATE 等写副作用");
            }
            return new Classification(Kind.QUERY, count, "SELECT");
        }
        if (statement instanceof SQLInsertStatement
                || statement instanceof SQLUpdateStatement
                || statement instanceof SQLDeleteStatement
                || statement instanceof SQLMergeStatement
                || statement instanceof SQLReplaceStatement) {
            return new Classification(Kind.DML, count, statement.getClass().getSimpleName());
        }
        if (statement instanceof SQLCreateStatement
                || statement instanceof SQLDropStatement
                || statement instanceof SQLAlterStatement
                || statement instanceof SQLTruncateStatement
                || statement instanceof SQLCommentStatement) {
            return new Classification(Kind.DDL, count, statement.getClass().getSimpleName());
        }
        if (statement instanceof SQLCallStatement
                || statement instanceof SQLSetStatement
                || statement instanceof SQLExplainStatement) {
            return new Classification(Kind.OTHER, count, statement.getClass().getSimpleName());
        }
        return new Classification(Kind.OTHER, count, statement.getClass().getSimpleName());
    }

    /**
     * 检查 SELECT 语句是否包含可能产生副作用的结构。
     */
    private static boolean hasSideEffectSelect(SQLSelectStatement select) {
        boolean[] writes = {false};
        select.accept(new com.alibaba.druid.sql.dialect.postgresql.visitor.PGASTVisitorAdapter() {
            /**
             * 访问当前 SQL 语法节点并检查其是否符合受限语法约定。
             */
            @Override
            public void preVisit(SQLObject node) {
                if (node instanceof SQLInsertStatement || node instanceof SQLUpdateStatement
                        || node instanceof SQLDeleteStatement || node instanceof SQLMergeStatement
                        || node instanceof SQLReplaceStatement) {
                    writes[0] = true;
                }
                if (node instanceof SQLSelectQueryBlock block
                        && (block.getInto() != null || block.isForUpdate())) {
                    writes[0] = true;
                }
                if (node instanceof PGSelectQueryBlock block
                        && (block.getForClause() != null || block.getIntoOption() != null)) {
                    writes[0] = true;
                }
            }
        });
        return writes[0];
    }

    /** 对全部子查询和 CTE 递归检查，不把 SELECT 前缀当作安全证明。 */
    private static boolean isReadOnlySelect(SQLSelectStatement select) {
        var visitor = new ReadOnlyVisitor();
        select.accept(visitor);
        return !visitor.unsafe;
    }

    /**
     * 实现原生访问接口，让各方言 accept0 进入其完整子节点；未知方言节点保持拒绝。
     * 通用访问器会跳过 PG/Oscar 表函数、DISTINCT ON、FETCH 和 SQLServer TOP 等结构。
     */
    private static final class ReadOnlyVisitor extends SQLASTVisitorAdapter implements PGASTVisitor, MySqlASTVisitor,
            OracleASTVisitor, OscarASTVisitor, SQLServerASTVisitor, DB2ASTVisitor, HiveASTVisitor, CKASTVisitor,
            PrestoASTVisitor, PhoenixASTVisitor {
        private static final Set<String> AST_DIALECTS = Set.of("postgresql", "mysql", "oracle", "oscar", "sqlserver",
                "db2", "hive", "odps", "clickhouse", "presto", "phoenix");
        private boolean unsafe;
        private boolean parameterizedPagination;
        private int visitedNodes;

        @Override
        public void preVisit(SQLObject node) {
            // Druid 1.2.28 此节点的 accept0 递归调用自身，必须在进入该方法前拒绝。
            if (node instanceof OscarFunctionTableSource) {
                throw new IllegalArgumentException("当前解析器无法安全遍历神通表函数");
            }
            if (++visitedNodes > 20_000) {
                throw new IllegalArgumentException("只读 SQL AST 超过节点限制");
            }
            String className = node.getClass().getName();
            String dialectPrefix = "com.alibaba.druid.sql.dialect.";
            if (className.startsWith(dialectPrefix)) {
                String nativeDialect = className.substring(dialectPrefix.length()).split("[.]", 2)[0];
                if (!AST_DIALECTS.contains(nativeDialect)) unsafe = true;
            }
            if (node instanceof SQLStatement statement && !(statement instanceof SQLSelectStatement)
                    || node instanceof SQLHint || node instanceof SQLAssignItem || node instanceof SQLSequenceExpr
                    || node instanceof SQLDbLinkExpr) {
                unsafe = true;
            }
            if (node instanceof SQLVariantRefExpr variant && !"?".equals(variant.getName())) {
                unsafe = true;
            }
            if (node instanceof SQLVariantRefExpr variant && "?".equals(variant.getName())) {
                SQLObject parent = node.getParent();
                for (int depth = 0; parent != null; depth++, parent = parent.getParent()) {
                    if (depth > 128) throw new IllegalArgumentException("只读分页 AST 父链超过限制");
                    if (parent instanceof SQLLimit) {
                        parameterizedPagination = true;
                        break;
                    }
                }
            }
            if (node instanceof SQLBinaryOpExpr expression && expression.getOperator() == SQLBinaryOperator.Assignment) {
                unsafe = true;
            }
            if (node instanceof SQLPropertyExpr property) {
                String name = property.getName();
                if (name.length() > 1 && (name.startsWith("\"") && name.endsWith("\"")
                        || name.startsWith("`") && name.endsWith("`")
                        || name.startsWith("[") && name.endsWith("]"))) {
                    name = name.substring(1, name.length() - 1);
                }
                if (Set.of("NEXTVAL", "CURRVAL").contains(name.toUpperCase(Locale.ROOT))) unsafe = true;
            }
            if (node instanceof SQLMethodInvokeExpr method && (method.getOwner() != null
                    || !READ_ONLY_FUNCTIONS.contains(method.getMethodName().toUpperCase(Locale.ROOT)))) {
                unsafe = true;
            }
            if (node instanceof SQLSelect nested && (nested.isForBrowse() || nested.getHintsSize() > 0)) {
                unsafe = true;
            }
            if (node instanceof SQLTableSource table && table.getHints() != null && !table.getHints().isEmpty()) {
                unsafe = true;
            }
            if (node instanceof SQLSelectQueryBlock block && (block.getInto() != null || block.isForUpdate()
                    || block.isForShare() || block.getWaitTime() != null || block.getHintsSize() > 0)) {
                unsafe = true;
            }
            if (node instanceof PGSelectQueryBlock block
                    && (block.getForClause() != null || block.getIntoOption() != null)) {
                unsafe = true;
            }
            if (node instanceof OscarSelectQueryBlock block && (block.getForClause() != null
                    || block.getIntoOptionTemp() != null || block.getIntoOptionLocal() != null)) {
                unsafe = true;
            }
            if (node instanceof SQLServerSelectQueryBlock block) {
                if (block.getOptions() != null && !block.getOptions().isEmpty()) unsafe = true;
            }
            // Druid 的 SQLServer/DB2 原生访问器遗漏这些通用字段，需额外遍历。
            if (node instanceof SQLServerSelectQueryBlock || node instanceof DB2SelectQueryBlock) {
                var block = (SQLSelectQueryBlock) node;
                if (block.getOrderBy() != null) block.getOrderBy().accept(this);
                if (block.getLimit() != null) block.getLimit().accept(this);
                if (block.getOffset() != null) block.getOffset().accept(this);
                if (block.getWith() != null) block.getWith().accept(this);
                if (block.getQualify() != null) block.getQualify().accept(this);
                if (block.getWindows() != null) block.getWindows().forEach(window -> window.accept(this));
            }
            // CKSelectQueryBlock 未重写 accept0，PREWHERE/SAMPLE 不在通用子节点里。
            if (node instanceof CKSelectQueryBlock block) {
                if (block.getPreWhere() != null) block.getPreWhere().accept(this);
                if (block.getSample() != null) block.getSample().accept(this);
                if (block.getFormat() != null || block.getSettings() != null && !block.getSettings().isEmpty()) {
                    unsafe = true;
                }
            }
            if (node instanceof DB2SelectQueryBlock block) {
                if (block.getLockRequest() != null || block.getIsolation() != null) unsafe = true;
                if (block.getOptimizeFor() != null) block.getOptimizeFor().accept(this);
            }
            if (node instanceof MySqlSelectQueryBlock block && (block.isLockInShareMode()
                    || block.getProcedureName() != null || block.isCalcFoundRows())) {
                unsafe = true;
            }
        }
    }

    /**
     * 解析当前产品对应的数据库方言。
     */
    private static DbType dialect(ProductTypeEnum product) {
        if (SupportedSqlParser.usesPostgresSyntax(product)) {
            return DbType.postgresql;
        }
        if (product == ProductTypeEnum.MYSQL || product == ProductTypeEnum.MARIADB || product == ProductTypeEnum.TIDB) {
            return DbType.mysql;
        }
        throw new UnsupportedOperationException("数据库产品尚未验证只读 SQL 分类：" + product);
    }

    /**
     * 根据当前输入构建对象或结构的描述信息。
     */
    static String describe(Kind kind) {
        return kind == null ? "" : kind.name().toLowerCase(Locale.ROOT);
    }
}
