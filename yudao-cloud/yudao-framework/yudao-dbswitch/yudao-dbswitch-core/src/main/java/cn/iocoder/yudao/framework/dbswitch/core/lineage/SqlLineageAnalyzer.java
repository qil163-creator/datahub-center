package cn.iocoder.yudao.framework.dbswitch.core.lineage;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageQueryResolver.Assignment;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageQueryResolver.OutputColumn;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageQueryResolver.QueryResult;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageQueryResolver.Relation;
import cn.iocoder.yudao.framework.dbswitch.core.lineage.LineageQueryResolver.Scope;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLAssignItem;
import com.alibaba.druid.sql.ast.statement.SQLBlockStatement;
import com.alibaba.druid.sql.ast.statement.SQLCallStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateFunctionStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateMaterializedViewStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateProcedureStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateTriggerStatement;
import com.alibaba.druid.sql.ast.statement.SQLCreateViewStatement;
import com.alibaba.druid.sql.ast.statement.SQLDropTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLInsertInto;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLMergeStatement;
import com.alibaba.druid.sql.ast.statement.SQLReplaceStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUpdateSetItem;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.ast.statement.SQLUseStatement;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.druid.sql.dialect.hive.ast.HiveInsert;
import com.alibaba.druid.sql.dialect.hive.ast.HiveMultiInsertStatement;
import com.alibaba.druid.sql.parser.SQLParserUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 血缘静态分析器：从脚本得到表级与字段级血缘，不执行 SQL、不连接数据库。
 * <p>
 * 支持矩阵只包含逐产品写过黄金用例的方言（见 {@link #supports(ProductTypeEnum)}）；其余产品返回
 * {@link SqlLineageStatus#UNSUPPORTED}，不按协议族外推。Flink SQL、存储过程体与动态 SQL 不在范围内。
 * <p>
 * 覆盖的写入语句：INSERT [OVERWRITE] ... SELECT（含 Hive 多路插入）、CREATE TABLE AS SELECT、
 * CREATE VIEW AS、MERGE、UPDATE（含关联更新）、REPLACE INTO ... SELECT、SELECT INTO。
 * 同一脚本内的临时表（TEMPORARY，或创建后又被删除的表）会被折叠，上游直接连到最终目标。
 * <p>
 * 线程安全：无共享状态，每次调用独立。异常与问题消息不包含 SQL 原文或字面量。
 */
public final class SqlLineageAnalyzer {

    /** 分析规则版本。规则变化导致结果不同时递增，消费方据此触发重算。 */
    public static final int VERSION = 1;

    private static final Map<ProductTypeEnum, Dialect> DIALECTS = new EnumMap<>(ProductTypeEnum.class);
    private static final Pattern SLASH_SEPARATOR = Pattern.compile("(?m)^\\s*/\\s*$");

    static {
        Dialect postgres = new Dialect(DbType.postgresql, LineageScriptContext.Fold.LOWER);
        Dialect mysql = new Dialect(DbType.mysql, LineageScriptContext.Fold.PRESERVE);
        DIALECTS.put(ProductTypeEnum.POSTGRESQL, postgres);
        DIALECTS.put(ProductTypeEnum.OPENGAUSS, postgres);
        DIALECTS.put(ProductTypeEnum.KINGBASE, postgres);
        DIALECTS.put(ProductTypeEnum.GREENPLUM, postgres);
        DIALECTS.put(ProductTypeEnum.MYSQL, mysql);
        DIALECTS.put(ProductTypeEnum.MARIADB, mysql);
        DIALECTS.put(ProductTypeEnum.TIDB, mysql);
        DIALECTS.put(ProductTypeEnum.DORIS, mysql);
        DIALECTS.put(ProductTypeEnum.STARROCKS, mysql);
        DIALECTS.put(ProductTypeEnum.HIVE, new Dialect(DbType.hive, LineageScriptContext.Fold.LOWER));
        DIALECTS.put(ProductTypeEnum.ORACLE, new Dialect(DbType.oracle, LineageScriptContext.Fold.UPPER));
        // 达梦兼容 Oracle 语法；达梦解析器不接受的语句回退 Oracle 方言解析。
        DIALECTS.put(ProductTypeEnum.DM, new Dialect(DbType.dm, LineageScriptContext.Fold.UPPER, DbType.oracle));
    }

    /**
     * 临时表或临时视图的语句头。Druid 的 PostgreSQL 解析器不接受 TEMP/TEMPORARY/UNLOGGED，
     * 达梦解析器不接受 GLOBAL TEMPORARY，解析失败时只改写该语句头后重试；分析不执行 SQL。
     */
    private static final Pattern TEMPORARY_HEADER = Pattern.compile(
            "(?is)^(\\s*(?:(?:--[^\\n]*(?:\\n|$)|/\\*.*?\\*/)\\s*)*)CREATE\\s+(OR\\s+REPLACE\\s+)?"
                    + "(?:(?:GLOBAL|LOCAL)\\s+)?(TEMP|TEMPORARY|UNLOGGED)\\s+(TABLE|VIEW)\\b");
    private static final Pattern ON_COMMIT = Pattern.compile("(?is)\\bON\\s+COMMIT\\s+(?:PRESERVE|DELETE|DROP)(?:\\s+ROWS)?\\b");

    private record Dialect(DbType dbType, LineageScriptContext.Fold fold, DbType fallback) {

        /**
         * 无回退方言。
         */
        Dialect(DbType dbType, LineageScriptContext.Fold fold) {
            this(dbType, fold, null);
        }
    }

    /**
     * 已解析的语句。temporary 来自语句头，用于方言 AST 不保留临时标记的情况。
     */
    private record Parsed(int index, SQLStatement statement, boolean temporary) {
    }

    /**
     * 初始化 {@code SqlLineageAnalyzer}。
     */
    private SqlLineageAnalyzer() {
    }

    /**
     * 判断产品是否进入已验证的血缘支持矩阵。Spark SQL 任务按 {@link ProductTypeEnum#HIVE} 方言分析。
     */
    public static boolean supports(ProductTypeEnum product) {
        return product != null && DIALECTS.containsKey(product);
    }

    /**
     * 分析脚本血缘。
     */
    public static SqlLineageResult analyze(SqlLineageRequest request) {
        Objects.requireNonNull(request, "request");
        Dialect dialect = DIALECTS.get(request.product());
        if (dialect == null) {
            return SqlLineageResult.empty(SqlLineageStatus.UNSUPPORTED, 0, List.of());
        }
        if (request.script().length() > request.limits().maxScriptChars()) {
            return SqlLineageResult.empty(SqlLineageStatus.FAILED, 0,
                    List.of(LineageIssue.Code.SCRIPT_TOO_LARGE.of(null)));
        }
        LineageScriptContext ctx = context(request, dialect);
        String script = ctx.substituteParameters(request.script());
        List<String> pieces = split(script, dialect.dbType(), request.statementSeparator());
        List<Parsed> parsed = new ArrayList<>();
        int statementCount = 0;
        int failed = 0;
        for (String piece : pieces) {
            if (piece.isBlank() || onlyComments(piece)) {
                continue;
            }
            statementCount++;
            if (statementCount > request.limits().maxStatements()) {
                ctx.scriptIssue(LineageIssue.Code.STATEMENT_LIMIT);
                statementCount--;
                break;
            }
            ctx.statementIndex = statementCount;
            Matcher header = TEMPORARY_HEADER.matcher(piece);
            boolean temporary = header.find() && !"UNLOGGED".equalsIgnoreCase(header.group(3));
            List<SQLStatement> statements = parse(piece, dialect, header);
            if (statements == null) {
                failed++;
                ctx.issue(LineageIssue.Code.PARSE_ERROR);
                continue;
            }
            for (SQLStatement statement : statements) {
                if (temporary && statement instanceof SQLCreateTableStatement create) {
                    create.setTemporary(true);
                }
                parsed.add(new Parsed(statementCount, statement, temporary));
            }
        }
        planTransientTables(parsed, ctx);
        LineageQueryResolver resolver = new LineageQueryResolver(ctx);
        for (Parsed statement : parsed) {
            ctx.statementIndex = statement.index();
            resolver.resetBudget();
            try {
                handle(statement.statement(), ctx, resolver);
            } catch (LineageQueryResolver.LineageLimitException | StackOverflowError tooComplex) {
                ctx.issue(LineageIssue.Code.TOO_COMPLEX);
            } catch (RuntimeException analysisError) {
                ctx.issue(LineageIssue.Code.ANALYSIS_ERROR);
            }
        }
        ctx.statementIndex = 0;
        return ctx.result(statementCount, failed);
    }

    /**
     * 分析单条查询的输出字段来源，供以查询作为输入节点的场景使用（如 ETL 的 SQL 输入）。
     */
    public static QueryLineageResult analyzeQuery(SqlLineageRequest request) {
        Objects.requireNonNull(request, "request");
        Dialect dialect = DIALECTS.get(request.product());
        if (dialect == null) {
            return new QueryLineageResult(SqlLineageStatus.UNSUPPORTED, List.of(), List.of(), List.of(), List.of());
        }
        if (request.script().length() > request.limits().maxScriptChars()) {
            return new QueryLineageResult(SqlLineageStatus.FAILED, List.of(), List.of(), List.of(),
                    List.of(LineageIssue.Code.SCRIPT_TOO_LARGE.of(null)));
        }
        LineageScriptContext ctx = context(request, dialect);
        String script = ctx.substituteParameters(request.script());
        ctx.statementIndex = 1;
        List<SQLStatement> statements;
        try {
            statements = SQLUtils.parseStatements(script, dialect.dbType());
        } catch (RuntimeException | StackOverflowError unparseable) {
            ctx.issue(LineageIssue.Code.PARSE_ERROR);
            return new QueryLineageResult(SqlLineageStatus.FAILED, List.of(), List.of(), List.of(), ctx.issues());
        }
        if (statements.size() != 1 || !(statements.getFirst() instanceof SQLSelectStatement select)) {
            ctx.issue(LineageIssue.Code.NOT_A_QUERY);
            return new QueryLineageResult(SqlLineageStatus.FAILED, List.of(), List.of(), List.of(), ctx.issues());
        }
        LineageQueryResolver resolver = new LineageQueryResolver(ctx);
        QueryResult result;
        try {
            result = resolver.analyzeSelect(select.getSelect(), new Scope(null), null);
        } catch (LineageQueryResolver.LineageLimitException | StackOverflowError tooComplex) {
            ctx.issue(LineageIssue.Code.TOO_COMPLEX);
            return new QueryLineageResult(SqlLineageStatus.FAILED, List.of(), List.of(), List.of(), ctx.issues());
        } catch (RuntimeException analysisError) {
            ctx.issue(LineageIssue.Code.ANALYSIS_ERROR);
            return new QueryLineageResult(SqlLineageStatus.FAILED, List.of(), List.of(), List.of(), ctx.issues());
        }
        List<QueryLineageResult.Column> columns = new ArrayList<>(result.columns.size());
        for (OutputColumn column : result.columns) {
            columns.add(new QueryLineageResult.Column(ctx.restore(column.name()),
                    column.sources().stream().map(source -> restore(source, ctx)).toList(),
                    column.wildcardFrom() != null));
        }
        List<LineageColumnSource> indirect = result.indirect.stream().map(source -> restore(source, ctx)).toList();
        List<LineageTable> reads = result.reads.stream().map(table -> ctx.restoreTable(table, 1)).distinct().toList();
        ctx.statementIndex = 0;
        return new QueryLineageResult(ctx.status(1, 0), columns, indirect, reads, ctx.issues());
    }

    /**
     * 还原来源中的参数占位。
     */
    private static LineageColumnSource restore(LineageColumnSource source, LineageScriptContext ctx) {
        return new LineageColumnSource(ctx.restoreTable(source.table(), 1), ctx.restore(source.column()),
                source.transform(), source.ambiguous(), source.masking());
    }

    /**
     * 创建脚本上下文。
     */
    private static LineageScriptContext context(SqlLineageRequest request, Dialect dialect) {
        return new LineageScriptContext(request.product(), dialect.dbType(), dialect.fold(), request.defaultSchema(),
                request.parameters(), request.catalog(), request.limits());
    }

    // ==================== 解析 ====================

    /**
     * 解析单个片段：原文 → 改写临时表语句头 → 回退方言；全部失败返回 null。
     */
    private static List<SQLStatement> parse(String piece, Dialect dialect, Matcher header) {
        List<String> candidates = new ArrayList<>(2);
        candidates.add(piece);
        if (header.find(0)) {
            String rewritten = header.group(1) + "CREATE " + (header.group(2) == null ? "" : header.group(2))
                    + header.group(4) + piece.substring(header.end());
            candidates.add(ON_COMMIT.matcher(rewritten).replaceAll(" "));
        }
        List<DbType> dialects = dialect.fallback() == null ? List.of(dialect.dbType())
                : List.of(dialect.dbType(), dialect.fallback());
        for (DbType dbType : dialects) {
            for (String candidate : candidates) {
                try {
                    return SQLUtils.parseStatements(candidate, dbType);
                } catch (RuntimeException | StackOverflowError unparseable) {
                    // 尝试下一种写法或方言；异常信息可能含 SQL 原文，不向外传播。
                }
            }
        }
        return null;
    }

    // ==================== 切分 ====================

    /**
     * 切分语句：单条语句解析失败只影响该条。Druid 切分失败时退回整体解析。
     */
    private static List<String> split(String script, DbType dbType, String separator) {
        List<String> chunks = "/".equals(separator) ? List.of(SLASH_SEPARATOR.split(script)) : List.of(script);
        List<String> pieces = new ArrayList<>();
        for (String chunk : chunks) {
            if ("/".equals(separator)) {
                // 以 / 分隔的脚本可能含 PL/SQL 块，块内分号不能再切。
                pieces.add(chunk);
                continue;
            }
            try {
                pieces.addAll(SQLParserUtils.split(chunk, dbType));
            } catch (RuntimeException | StackOverflowError unsplittable) {
                pieces.add(chunk);
            }
        }
        return pieces;
    }

    /**
     * 判断片段是否只有注释。
     */
    private static boolean onlyComments(String piece) {
        String stripped = piece.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--[^\\n]*", " ")
                .replaceAll("(?m)#[^\\n]*", " ");
        return stripped.isBlank() || stripped.trim().equals(";");
    }

    // ==================== 临时表 ====================

    /**
     * 预扫描临时表：TEMPORARY 表，或在最后一次创建之后又被删除的表。
     */
    private static void planTransientTables(List<Parsed> parsed, LineageScriptContext ctx) {
        Map<String, Integer> lastCreate = new HashMap<>();
        Map<String, Integer> lastDrop = new HashMap<>();
        Set<String> temporary = new LinkedHashSet<>();
        String savedSchema = ctx.defaultSchema;
        int position = 0;
        for (Parsed statement : parsed) {
            position++;
            SQLStatement st = statement.statement();
            if (st instanceof SQLUseStatement use && use.getDatabase() != null) {
                ctx.defaultSchema = ctx.identifier(use.getDatabase().getSimpleName());
            } else if (st instanceof SQLCreateTableStatement create && create.getTableSource() != null) {
                LineageTable table = ctx.table(create.getTableSource());
                if (table != null) {
                    lastCreate.put(table.matchKey(), position);
                    if (create.isTemporary() || statement.temporary()) {
                        temporary.add(table.matchKey());
                    }
                }
            } else if (st instanceof SQLCreateViewStatement view && view.getTableSource() != null
                    && statement.temporary()) {
                LineageTable table = ctx.table(view.getTableSource());
                if (table != null) {
                    temporary.add(table.matchKey());
                }
            } else if (st instanceof SQLDropTableStatement drop) {
                for (SQLExprTableSource source : drop.getTableSources()) {
                    LineageTable table = ctx.table(source);
                    if (table != null) {
                        lastDrop.put(table.matchKey(), position);
                    }
                }
            }
        }
        ctx.defaultSchema = savedSchema;
        for (String key : temporary) {
            ctx.markTransient(key);
        }
        lastCreate.forEach((key, created) -> {
            Integer dropped = lastDrop.get(key);
            if (dropped != null && dropped > created) {
                ctx.markTransient(key);
            }
        });
    }

    // ==================== 语句 ====================

    /**
     * 按语句类型分派。
     */
    private static void handle(SQLStatement statement, LineageScriptContext ctx, LineageQueryResolver resolver) {
        if (statement instanceof SQLUseStatement use) {
            if (use.getDatabase() != null) {
                ctx.defaultSchema = ctx.identifier(use.getDatabase().getSimpleName());
            }
            return;
        }
        if (statement instanceof HiveMultiInsertStatement multi) {
            for (HiveInsert insert : multi.getItems()) {
                insert(insert, multi.getWith(), multi.getFrom(), ctx, resolver);
            }
            return;
        }
        if (statement instanceof SQLInsertStatement insert) {
            insert(insert, insert.getWith(), null, ctx, resolver);
            return;
        }
        if (statement instanceof SQLReplaceStatement replace) {
            if (replace.getQuery() != null && replace.getQuery().getSubQuery() != null) {
                writeQuery(replace.getTableSource(), resolver.names(replace.getColumns()),
                        replace.getQuery().getSubQuery(), null, null, List.of(), ctx, resolver);
            }
            return;
        }
        if (statement instanceof SQLCreateTableStatement create) {
            createTable(create, ctx, resolver);
            return;
        }
        if (statement instanceof SQLCreateViewStatement view) {
            if (view.getSubQuery() != null && view.getTableSource() != null) {
                createAs(ctx.table(view.getTableSource()), resolver.names(view.getColumns()), view.getSubQuery(),
                        ctx, resolver);
            }
            return;
        }
        if (statement instanceof SQLCreateMaterializedViewStatement view) {
            if (view.getQuery() != null && view.getName() != null) {
                createAs(ctx.table(view.getName()), resolver.names(view.getColumns()), view.getQuery(), ctx, resolver);
            }
            return;
        }
        if (statement instanceof SQLMergeStatement merge) {
            merge(merge, ctx, resolver);
            return;
        }
        if (statement instanceof SQLUpdateStatement update) {
            update(update, ctx, resolver);
            return;
        }
        if (statement instanceof SQLSelectStatement select) {
            select(select, ctx, resolver);
            return;
        }
        if (statement instanceof SQLCallStatement || statement instanceof SQLBlockStatement
                || statement instanceof SQLCreateProcedureStatement || statement instanceof SQLCreateFunctionStatement
                || statement instanceof SQLCreateTriggerStatement) {
            ctx.issue(LineageIssue.Code.UNSUPPORTED_STATEMENT);
        }
        // 其余语句（DELETE、TRUNCATE、DROP、SET、ALTER 等）不产生表间数据流。
    }

    /**
     * INSERT [OVERWRITE] ... SELECT。
     */
    private static void insert(SQLInsertInto insert, SQLWithSubqueryClause with, SQLTableSource defaultFrom,
                               LineageScriptContext ctx, LineageQueryResolver resolver) {
        if (insert.getQuery() == null || insert.getTableSource() == null) {
            return;
        }
        List<String> staticPartitions = new ArrayList<>();
        for (SQLAssignItem partition : partitions(insert)) {
            if (partition.getValue() != null) {
                staticPartitions.add(resolver.columnName(partition.getTarget()));
            }
        }
        writeQuery(insert.getTableSource(), resolver.names(insert.getColumns()), insert.getQuery(), with,
                defaultFrom, staticPartitions, ctx, resolver);
    }

    /**
     * 取插入语句的分区子句。
     */
    private static List<SQLAssignItem> partitions(SQLInsertInto insert) {
        List<SQLAssignItem> partitions = insert.getPartitions();
        return partitions == null ? List.of() : partitions;
    }

    /**
     * 写入查询结果：显式列清单优先，其次已知目标表结构（去掉静态分区列）按位置映射。
     */
    private static void writeQuery(SQLExprTableSource targetSource, List<String> explicitColumns, SQLSelect select,
                                   SQLWithSubqueryClause with, SQLTableSource defaultFrom, List<String> staticPartitions,
                                   LineageScriptContext ctx, LineageQueryResolver resolver) {
        LineageTable target = ctx.table(targetSource);
        if (target == null) {
            ctx.issue(LineageIssue.Code.UNSUPPORTED_TABLE_SOURCE);
            return;
        }
        Scope root = new Scope(null);
        resolver.registerCtes(with, root);
        QueryResult query = resolver.analyzeSelect(select, root, defaultFrom);
        List<String> targetColumns = explicitColumns.isEmpty() ? null : explicitColumns;
        if (targetColumns == null) {
            List<String> known = ctx.knownColumns(target);
            if (known != null) {
                targetColumns = known.stream().filter(column -> staticPartitions.stream()
                        .noneMatch(partition -> LineageScriptContext.key(partition)
                                .equals(LineageScriptContext.key(column)))).toList();
            }
        }
        LineageScriptContext.TransientTable transientTarget = ctx.transientTable(target);
        if (targetColumns == null && transientTarget != null) {
            // 临时表结构未声明时按输出名记录，供后续语句按名称引用。
            targetColumns = query.columns.stream().map(OutputColumn::name).toList();
        }
        write(target, assignments(target, targetColumns, query.columns, ctx), query.indirect, query.reads, ctx);
    }

    /**
     * 输出字段按位置对齐到目标字段。
     */
    private static List<Assignment> assignments(LineageTable target, List<String> targetColumns,
                                                List<OutputColumn> outputs, LineageScriptContext ctx) {
        List<Assignment> assignments = new ArrayList<>();
        boolean wildcard = outputs.stream().anyMatch(column -> column.wildcardFrom() != null);
        if (targetColumns == null) {
            if (!outputs.isEmpty()) {
                ctx.issue(LineageIssue.Code.TARGET_COLUMNS_UNKNOWN, target.displayName());
            }
            for (OutputColumn output : outputs) {
                if (output.wildcardFrom() != null) {
                    assignments.add(new Assignment("*", output.sources(), output.wildcardFrom()));
                }
            }
            return assignments;
        }
        if (wildcard) {
            // 缺少结构的 * 无法确定位置：只记录全列透传，位于其前的字段仍按位置映射。
            for (int i = 0; i < outputs.size(); i++) {
                OutputColumn output = outputs.get(i);
                if (output.wildcardFrom() != null) {
                    assignments.add(new Assignment("*", output.sources(), output.wildcardFrom()));
                    for (int j = i + 1; j < outputs.size(); j++) {
                        if (outputs.get(j).wildcardFrom() != null) {
                            assignments.add(new Assignment("*", outputs.get(j).sources(),
                                    outputs.get(j).wildcardFrom()));
                        }
                    }
                    return assignments;
                }
                if (i < targetColumns.size()) {
                    addAssignment(assignments, targetColumns.get(i), output, ctx);
                }
            }
            return assignments;
        }
        if (targetColumns.size() != outputs.size()) {
            ctx.issue(LineageIssue.Code.COLUMN_COUNT_MISMATCH, target.displayName());
        }
        int aligned = Math.min(targetColumns.size(), outputs.size());
        for (int i = 0; i < aligned; i++) {
            addAssignment(assignments, targetColumns.get(i), outputs.get(i), ctx);
        }
        return assignments;
    }

    /**
     * 添加一个字段映射；目标字段名未知时报告未命名输出。
     */
    private static void addAssignment(List<Assignment> assignments, String column, OutputColumn output,
                                      LineageScriptContext ctx) {
        if (column == null) {
            if (!output.sources().isEmpty()) {
                ctx.issue(LineageIssue.Code.UNNAMED_OUTPUT);
            }
            return;
        }
        assignments.add(new Assignment(column, output.sources(), null));
    }

    /**
     * 写入目标表：临时表累积定义；持久表输出表级与字段级边。
     */
    private static void write(LineageTable target, List<Assignment> assignments, Set<LineageColumnSource> indirect,
                              Set<LineageTable> reads, LineageScriptContext ctx) {
        LineageScriptContext.TransientTable transientTarget = ctx.transientTable(target);
        if (transientTarget != null) {
            transientTarget.merge(assignments, indirect, reads);
            return;
        }
        ctx.written(target);
        ctx.read(reads);
        for (LineageTable read : reads) {
            ctx.tableEdge(read, target);
        }
        for (Assignment assignment : assignments) {
            String column = assignment.wildcardFrom() != null ? "*" : assignment.column();
            for (LineageColumnSource source : assignment.sources()) {
                ctx.columnEdge(source, target, column);
            }
        }
        for (LineageColumnSource source : indirect) {
            ctx.columnEdge(source, target, "*");
        }
    }

    /**
     * CREATE TABLE：带查询时按 CTAS 处理，否则登记脚本内列结构。
     */
    private static void createTable(SQLCreateTableStatement create, LineageScriptContext ctx,
                                     LineageQueryResolver resolver) {
        if (create.getTableSource() == null) {
            return;
        }
        List<String> declared = resolver.definedColumns(create.getTableElementList());
        LineageTable table = ctx.table(create.getTableSource());
        if (create.getSelect() == null) {
            if (table != null) {
                ctx.defineColumns(table, declared);
                LineageScriptContext.TransientTable transientTable = ctx.transientTable(table);
                if (transientTable != null) {
                    transientTable.declare(declared);
                }
            }
            return;
        }
        createAs(table, declared, create.getSelect(), ctx, resolver);
    }

    /**
     * CTAS 与视图：目标字段取声明列，否则取查询输出名。
     */
    private static void createAs(LineageTable target, List<String> declared, SQLSelect select,
                                 LineageScriptContext ctx, LineageQueryResolver resolver) {
        if (target == null) {
            return;
        }
        QueryResult query = resolver.analyzeSelect(select, new Scope(null), null);
        List<String> targetColumns = !declared.isEmpty() ? declared
                : query.columns.stream().map(OutputColumn::name).toList();
        if (query.columns.stream().noneMatch(column -> column.wildcardFrom() != null)) {
            ctx.defineColumns(target, targetColumns);
        }
        write(target, assignments(target, targetColumns, query.columns, ctx), query.indirect, query.reads, ctx);
    }

    /**
     * SELECT：带 INTO 时按建表处理，否则只记录读取表。
     */
    private static void select(SQLSelectStatement statement, LineageScriptContext ctx, LineageQueryResolver resolver) {
        QueryResult query = resolver.analyzeSelect(statement.getSelect(), new Scope(null), null);
        SQLExprTableSource into = query.into;
        if (into == null && statement.getSelect().getQuery() instanceof SQLSelectQueryBlock block) {
            into = block.getInto();
        }
        if (into == null) {
            for (LineageTable read : query.reads) {
                if (ctx.transientTable(read) == null) {
                    ctx.read(Set.of(read));
                }
            }
            return;
        }
        LineageTable target = ctx.table(into);
        if (target == null) {
            return;
        }
        List<String> targetColumns = query.columns.stream().map(OutputColumn::name).toList();
        write(target, assignments(target, targetColumns, query.columns, ctx), query.indirect, query.reads, ctx);
    }

    /**
     * MERGE INTO target USING source ON ... WHEN MATCHED/NOT MATCHED。
     */
    private static void merge(SQLMergeStatement merge, LineageScriptContext ctx, LineageQueryResolver resolver) {
        if (!(merge.getInto() instanceof SQLExprTableSource intoSource)) {
            ctx.issue(LineageIssue.Code.UNSUPPORTED_TABLE_SOURCE);
            return;
        }
        LineageTable target = ctx.table(intoSource);
        if (target == null) {
            return;
        }
        Scope scope = new Scope(null);
        QueryResult query = new QueryResult();
        String alias = ctx.identifier(intoSource.getAlias() != null ? intoSource.getAlias() : merge.getAlias());
        scope.relations.add(Relation.physical(alias, target, ctx.knownColumns(target)));
        resolver.addRelations(merge.getUsing(), scope, query);
        resolver.collect(merge.getOn(), scope, query, LineageTransform.JOIN, null, false);
        Map<String, Set<LineageColumnSource>> columns = new LinkedHashMap<>();
        for (SQLMergeStatement.When when : merge.getWhens()) {
            resolver.collect(when.getWhere(), scope, query, LineageTransform.FILTER, null, false);
            if (when instanceof SQLMergeStatement.WhenUpdate update) {
                for (SQLUpdateSetItem item : update.getItems()) {
                    assign(columns, resolver.columnName(item.getColumn()), item.getValue(), scope, query, resolver);
                }
            } else if (when instanceof SQLMergeStatement.WhenInsert insert) {
                List<String> names = resolver.names(insert.getColumns());
                List<SQLExpr> values = insert.getValues();
                for (int i = 0; i < Math.min(names.size(), values.size()); i++) {
                    assign(columns, names.get(i), values.get(i), scope, query, resolver);
                }
            }
        }
        List<Assignment> assignments = new ArrayList<>();
        columns.forEach((column, sources) -> assignments.add(new Assignment(column, sources, null)));
        Set<LineageTable> reads = new LinkedHashSet<>(query.reads);
        reads.removeIf(target::sameAs);
        write(target, assignments, query.indirect, reads, ctx);
    }

    /**
     * 记录一个 SET 或 VALUES 赋值的来源。
     */
    private static void assign(Map<String, Set<LineageColumnSource>> columns, String column, SQLExpr value,
                               Scope scope, QueryResult query, LineageQueryResolver resolver) {
        if (column == null || value == null) {
            return;
        }
        LineageTransform base = LineageQueryResolver.bareColumn(value)
                ? LineageTransform.IDENTITY : LineageTransform.TRANSFORMATION;
        Set<LineageColumnSource> sources = columns.computeIfAbsent(column, ignored -> new LinkedHashSet<>());
        resolver.collect(value, scope, query, base, sources, false);
    }

    /**
     * UPDATE：目标为 SET 字段所属的表，来源为其他关联表。
     */
    private static void update(SQLUpdateStatement update, LineageScriptContext ctx, LineageQueryResolver resolver) {
        Scope scope = new Scope(null);
        resolver.registerCtes(update.getWith(), scope);
        QueryResult query = new QueryResult();
        resolver.addRelations(update.getTableSource(), scope, query);
        if (update.getFrom() != null) {
            resolver.addRelations(update.getFrom(), scope, query);
        }
        Relation defaultTarget = scope.relations.stream().filter(relation -> relation.table != null)
                .findFirst().orElse(null);
        Map<LineageTable, Map<String, Set<LineageColumnSource>>> targets = new LinkedHashMap<>();
        for (SQLUpdateSetItem item : update.getItems()) {
            SQLExpr column = item.getColumn();
            Relation owner = defaultTarget;
            if (column instanceof SQLPropertyExpr property) {
                LineageQueryResolver.Qualifier qualifier = resolver.qualifier(property.getOwner());
                Relation found = qualifier == null ? null : scope.relation(qualifier.schema(), qualifier.name());
                if (found != null) {
                    owner = found;
                }
            }
            if (owner == null || owner.table == null) {
                continue;
            }
            assign(targets.computeIfAbsent(owner.table, ignored -> new LinkedHashMap<>()),
                    resolver.columnName(column), item.getValue(), scope, query, resolver);
        }
        resolver.collect(update.getWhere(), scope, query, LineageTransform.FILTER, null, false);
        for (Map.Entry<LineageTable, Map<String, Set<LineageColumnSource>>> entry : targets.entrySet()) {
            LineageTable target = entry.getKey();
            List<Assignment> assignments = new ArrayList<>();
            entry.getValue().forEach((column, sources) -> assignments.add(new Assignment(column, sources, null)));
            Set<LineageTable> reads = new LinkedHashSet<>(query.reads);
            reads.removeIf(target::sameAs);
            if (reads.isEmpty()) {
                // 只用自身字段或常量更新，没有表间数据流。
                continue;
            }
            write(target, assignments, query.indirect, reads, ctx);
        }
    }
}
