package cn.iocoder.yudao.framework.dbswitch.core.lineage;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.SQLOrderBy;
import com.alibaba.druid.sql.ast.SQLOver;
import com.alibaba.druid.sql.ast.SQLWindow;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLCaseExpr;
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.expr.SQLListExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLLateralViewTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectGroupByClause;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectOrderByItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLTableElement;
import com.alibaba.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.ast.statement.SQLUnionQueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLValuesQuery;
import com.alibaba.druid.sql.ast.statement.SQLValuesTableSource;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.druid.sql.dialect.hive.visitor.HiveASTVisitor;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitor;
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleASTVisitor;
import com.alibaba.druid.sql.dialect.postgresql.visitor.PGASTVisitor;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 解析查询的输出字段来源、INDIRECT 依赖和读取表。
 * <p>
 * 采用静态分析：表结构只用于展开 {@code *} 和判定未限定字段归属；无法证明归属时，
 * 按 sqllineage 的做法对全部候选表记录并标记为不确定，而不是猜测单一来源。
 */
final class LineageQueryResolver {

    private static final Set<String> AGGREGATES = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX", "STDDEV",
            "STDDEV_POP", "STDDEV_SAMP", "VARIANCE", "VAR_POP", "VAR_SAMP", "GROUP_CONCAT", "STRING_AGG",
            "LISTAGG", "ARRAY_AGG", "COLLECT_LIST", "COLLECT_SET", "BOOL_AND", "BOOL_OR", "BIT_AND", "BIT_OR",
            "BIT_XOR", "EVERY", "APPROX_COUNT_DISTINCT", "PERCENTILE", "PERCENTILE_APPROX", "PERCENTILE_CONT",
            "PERCENTILE_DISC", "MEDIAN", "ANY_VALUE", "FIRST", "LAST", "JSON_ARRAYAGG", "JSON_OBJECTAGG",
            "JSON_AGG", "JSONB_AGG", "NDV", "WM_CONCAT", "CORR", "COVAR_POP", "COVAR_SAMP");
    /** 输出不暴露原值的函数；COUNT 与 OpenLineage 示例一致视为 masking。 */
    private static final Set<String> MASKING = Set.of("MD5", "SHA", "SHA1", "SHA2", "SHA256", "SHA512", "SM3",
            "HASH", "MURMUR_HASH", "MURMURHASH", "MURMUR3_HASH", "CRC32", "FARM_FINGERPRINT", "HMAC", "MASK",
            "MASK_HASH", "MASK_FIRST_N", "MASK_LAST_N", "MASK_SHOW_FIRST_N", "MASK_SHOW_LAST_N", "AES_ENCRYPT",
            "DES_ENCRYPT", "SM4_ENCRYPT", "ENCRYPT", "COUNT");
    /** 解析器可能识别为标识符的伪列与关键字。 */
    private static final Set<String> PSEUDO = Set.of("SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIME",
            "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP", "ROWNUM", "ROWID", "LEVEL", "NULL", "TRUE", "FALSE",
            "CURRENT_USER", "SESSION_USER", "USER", "UNKNOWN", "DEFAULT");
    private static final Set<String> SEQUENCE_PROPERTIES = Set.of("NEXTVAL", "CURRVAL");

    private final LineageScriptContext ctx;
    private int visitedNodes;
    private int existsDepth;

    /**
     * 绑定脚本上下文。
     */
    LineageQueryResolver(LineageScriptContext ctx) {
        this.ctx = ctx;
    }

    /**
     * 每条语句开始时重置节点计数。
     */
    void resetBudget() {
        visitedNodes = 0;
    }

    // ==================== 模型 ====================

    /** 查询输出字段。wildcardFrom 非空表示缺少表结构而保留的 {@code 表.*}。 */
    record OutputColumn(String name, LinkedHashSet<LineageColumnSource> sources, LineageTable wildcardFrom) {
    }

    /** 写入目标字段及其来源。column 为 null 表示无法命名；wildcardFrom 表示整表透传。 */
    record Assignment(String column, Set<LineageColumnSource> sources, LineageTable wildcardFrom) {
    }

    /** 查询分析结果。 */
    static final class QueryResult {
        final List<OutputColumn> columns = new ArrayList<>();
        final LinkedHashSet<LineageColumnSource> indirect = new LinkedHashSet<>();
        final LinkedHashSet<LineageTable> reads = new LinkedHashSet<>();
        SQLExprTableSource into;

        /**
         * 合并来源关系带来的依赖。
         */
        void absorb(Relation relation) {
            reads.addAll(relation.reads);
            indirect.addAll(relation.indirect);
        }
    }

    /** CTE 定义。opaque 表示递归引用自身或无法分析。 */
    record CteDef(List<OutputColumn> columns, Set<LineageTable> reads, Set<LineageColumnSource> indirect,
                  boolean opaque) {
    }

    /** 作用域中的一个数据来源：物理表或派生关系（子查询、CTE、临时表）。 */
    static final class Relation {
        final String alias;
        final LineageTable table;
        final List<String> knownColumns;
        final List<OutputColumn> columns;
        final Set<LineageTable> reads;
        final Set<LineageColumnSource> indirect;
        final boolean opaque;

        /**
         * 创建关系。
         */
        private Relation(String alias, LineageTable table, List<String> knownColumns, List<OutputColumn> columns,
                         Set<LineageTable> reads, Set<LineageColumnSource> indirect, boolean opaque) {
            this.alias = alias;
            this.table = table;
            this.knownColumns = knownColumns;
            this.columns = columns;
            this.reads = reads;
            this.indirect = indirect;
            this.opaque = opaque;
        }

        /**
         * 物理表关系。
         */
        static Relation physical(String alias, LineageTable table, List<String> knownColumns) {
            return new Relation(alias, table, knownColumns, null, Set.of(table), Set.of(), false);
        }

        /**
         * 派生关系。
         */
        static Relation derived(String alias, List<OutputColumn> columns, Set<LineageTable> reads,
                                Set<LineageColumnSource> indirect) {
            return new Relation(alias, null, null, columns, reads, indirect, false);
        }

        /**
         * 无法分析的关系。
         */
        static Relation opaque(String alias) {
            return new Relation(alias, null, null, List.of(), Set.of(), Set.of(), true);
        }

        /**
         * 是否匹配字段限定符。
         */
        boolean matches(String schemaQualifier, String qualifier) {
            String q = LineageScriptContext.key(qualifier);
            if (alias != null) {
                return schemaQualifier == null && q.equals(LineageScriptContext.key(alias));
            }
            if (table == null || !q.equals(LineageScriptContext.key(table.name()))) {
                return false;
            }
            return schemaQualifier == null || table.schema() == null
                    || LineageScriptContext.key(schemaQualifier).equals(LineageScriptContext.key(table.schema()));
        }

        /**
         * 判断关系是否包含字段：TRUE 确定包含，FALSE 确定不含，null 未知。
         */
        Boolean hasColumn(String name) {
            String k = LineageScriptContext.key(name);
            if (opaque) {
                return null;
            }
            if (table != null) {
                if (knownColumns == null) {
                    return null;
                }
                return knownColumns.stream().anyMatch(c -> LineageScriptContext.key(c).equals(k));
            }
            boolean wildcard = false;
            for (OutputColumn column : columns) {
                if (column.wildcardFrom() != null) {
                    wildcard = true;
                } else if (column.name() != null && LineageScriptContext.key(column.name()).equals(k)) {
                    return Boolean.TRUE;
                }
            }
            return wildcard ? null : Boolean.FALSE;
        }
    }

    /** 名称解析作用域。 */
    static final class Scope {
        final Scope parent;
        final Map<String, CteDef> ctes = new HashMap<>();
        final List<Relation> relations = new ArrayList<>();
        List<OutputColumn> outputs = List.of();

        /**
         * 创建子作用域。
         */
        Scope(Scope parent) {
            this.parent = parent;
        }

        /**
         * 沿作用域链查找 CTE。
         */
        CteDef cte(String name) {
            String k = LineageScriptContext.key(name);
            for (Scope scope = this; scope != null; scope = scope.parent) {
                CteDef def = scope.ctes.get(k);
                if (def != null) {
                    return def;
                }
            }
            return null;
        }

        /**
         * 在当前作用域查找限定符对应的关系。
         */
        Relation relation(String schemaQualifier, String qualifier) {
            for (int i = relations.size() - 1; i >= 0; i--) {
                if (relations.get(i).matches(schemaQualifier, qualifier)) {
                    return relations.get(i);
                }
            }
            return null;
        }

        /**
         * 查找当前查询的输出别名。
         */
        OutputColumn output(String name) {
            String k = LineageScriptContext.key(name);
            for (OutputColumn column : outputs) {
                if (column.name() != null && column.wildcardFrom() == null
                        && LineageScriptContext.key(column.name()).equals(k)) {
                    return column;
                }
            }
            return null;
        }
    }

    // ==================== 查询 ====================

    /**
     * 注册 WITH 子句中的 CTE。递归 CTE 在分析前先登记为不可见来源，避免自引用无限展开。
     */
    void registerCtes(SQLWithSubqueryClause with, Scope scope) {
        if (with == null) {
            return;
        }
        boolean recursive = Boolean.TRUE.equals(with.getRecursive());
        for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
            String name = ctx.identifier(entry.getAlias());
            if (name == null) {
                continue;
            }
            String k = LineageScriptContext.key(name);
            if (recursive) {
                scope.ctes.put(k, new CteDef(List.of(), Set.of(), Set.of(), true));
            }
            if (entry.getSubQuery() == null) {
                ctx.issue(LineageIssue.Code.UNSUPPORTED_QUERY);
                scope.ctes.put(k, new CteDef(List.of(), Set.of(), Set.of(), true));
                continue;
            }
            QueryResult result = analyzeSelect(entry.getSubQuery(), scope, null);
            List<OutputColumn> columns = rename(result.columns, names(entry.getColumns()));
            scope.ctes.put(k, new CteDef(columns, Set.copyOf(result.reads), Set.copyOf(result.indirect), false));
        }
    }

    /**
     * 分析完整 SELECT（含 WITH）。defaultFrom 用于 Hive 多路插入共享的 FROM。
     */
    QueryResult analyzeSelect(SQLSelect select, Scope outer, SQLTableSource defaultFrom) {
        Scope scope = new Scope(outer);
        registerCtes(select.getWithSubQuery(), scope);
        if (select.getQuery() == null) {
            ctx.issue(LineageIssue.Code.UNSUPPORTED_QUERY);
            return new QueryResult();
        }
        return analyzeQuery(select.getQuery(), scope, defaultFrom);
    }

    /**
     * 分析查询体：查询块、UNION 或 VALUES。
     */
    QueryResult analyzeQuery(SQLSelectQuery query, Scope outer, SQLTableSource defaultFrom) {
        if (query instanceof SQLSelectQueryBlock block) {
            return analyzeBlock(block, outer, defaultFrom);
        }
        if (query instanceof SQLUnionQuery union) {
            List<SQLSelectQuery> branches = union.getRelations() != null && !union.getRelations().isEmpty()
                    ? union.getRelations() : List.of(union.getLeft(), union.getRight());
            QueryResult merged = null;
            for (SQLSelectQuery branch : branches) {
                if (branch == null) {
                    continue;
                }
                QueryResult result = analyzeQuery(branch, outer, defaultFrom);
                if (merged == null) {
                    merged = result;
                    continue;
                }
                mergeUnion(merged, result);
            }
            return merged == null ? new QueryResult() : merged;
        }
        if (query instanceof SQLValuesQuery values) {
            QueryResult result = new QueryResult();
            if (!values.getValues().isEmpty() && values.getValues().getFirst() instanceof SQLListExpr row) {
                for (int i = 0; i < row.getItems().size(); i++) {
                    result.columns.add(new OutputColumn(null, new LinkedHashSet<>(), null));
                }
            }
            return result;
        }
        ctx.issue(LineageIssue.Code.UNSUPPORTED_QUERY);
        return new QueryResult();
    }

    /**
     * 按位置合并 UNION 分支：名称取第一个分支，来源取并集。
     */
    private void mergeUnion(QueryResult target, QueryResult branch) {
        target.reads.addAll(branch.reads);
        target.indirect.addAll(branch.indirect);
        boolean wildcard = target.columns.stream().anyMatch(c -> c.wildcardFrom() != null)
                || branch.columns.stream().anyMatch(c -> c.wildcardFrom() != null);
        if (wildcard || target.columns.size() != branch.columns.size()) {
            // 无法按位置对齐时保留并集，交给目标映射报告列数问题。
            for (OutputColumn column : branch.columns) {
                if (column.wildcardFrom() != null) {
                    target.columns.add(column);
                }
            }
        }
        int aligned = Math.min(target.columns.size(), branch.columns.size());
        for (int i = 0; i < aligned; i++) {
            OutputColumn into = target.columns.get(i);
            OutputColumn from = branch.columns.get(i);
            if (into.wildcardFrom() == null && from.wildcardFrom() == null) {
                into.sources().addAll(from.sources());
            }
        }
    }

    /**
     * 分析单个查询块。
     */
    private QueryResult analyzeBlock(SQLSelectQueryBlock block, Scope outer, SQLTableSource defaultFrom) {
        Scope scope = new Scope(outer);
        QueryResult result = new QueryResult();
        SQLTableSource from = block.getFrom() != null ? block.getFrom() : defaultFrom;
        if (from != null) {
            addRelations(from, scope, result);
        }
        for (SQLSelectItem item : block.getSelectList()) {
            SQLExpr expr = item.getExpr();
            if (expr instanceof SQLAllColumnExpr) {
                for (Relation relation : scope.relations) {
                    expand(relation, result);
                }
                continue;
            }
            if (expr instanceof SQLPropertyExpr property && "*".equals(property.getName())) {
                Qualifier qualifier = qualifier(property.getOwner());
                Relation relation = qualifier == null ? null : find(scope, qualifier);
                if (relation == null) {
                    ctx.issue(LineageIssue.Code.UNRESOLVED_COLUMN, property.getOwner() + ".*");
                } else {
                    expand(relation, result);
                }
                continue;
            }
            LinkedHashSet<LineageColumnSource> sources = new LinkedHashSet<>();
            LineageTransform base = bareColumn(expr) ? LineageTransform.IDENTITY : LineageTransform.TRANSFORMATION;
            collect(expr, scope, result, base, sources, false);
            List<String> aliasList = item.getAliasList();
            if (aliasList != null && aliasList.size() > 1) {
                // Hive UDTF：explode(x) AS (a, b) 产出多列，来源相同。
                for (String alias : aliasList) {
                    result.columns.add(new OutputColumn(ctx.identifier(alias), new LinkedHashSet<>(sources), null));
                }
                continue;
            }
            result.columns.add(new OutputColumn(outputName(item), sources, null));
        }
        scope.outputs = result.columns;
        collect(block.getWhere(), scope, result, LineageTransform.FILTER, null, false);
        SQLSelectGroupByClause groupBy = block.getGroupBy();
        if (groupBy != null) {
            for (SQLExpr item : groupBy.getItems()) {
                collectAliasAware(item, scope, result, LineageTransform.GROUP_BY);
            }
            collect(groupBy.getHaving(), scope, result, LineageTransform.FILTER, null, true);
        }
        collect(block.getQualify(), scope, result, LineageTransform.FILTER, null, true);
        collectOrderBy(block.getOrderBy(), scope, result);
        if (block.getWindows() != null) {
            for (SQLWindow window : block.getWindows()) {
                collectOver(window.getOver(), scope, result);
            }
        }
        result.into = block.getInto();
        return result;
    }

    /**
     * 分析 ORDER BY，支持输出别名与序号。
     */
    private void collectOrderBy(SQLOrderBy orderBy, Scope scope, QueryResult result) {
        if (orderBy == null) {
            return;
        }
        for (SQLSelectOrderByItem item : orderBy.getItems()) {
            collectAliasAware(item.getExpr(), scope, result, LineageTransform.SORT);
        }
    }

    /**
     * 分组和排序项：整数表示输出序号，标识符可引用输出别名。
     */
    private void collectAliasAware(SQLExpr expr, Scope scope, QueryResult result, LineageTransform transform) {
        if (expr instanceof SQLIntegerExpr ordinal && ordinal.getNumber() != null) {
            int index = ordinal.getNumber().intValue() - 1;
            if (index >= 0 && index < scope.outputs.size()) {
                for (LineageColumnSource source : scope.outputs.get(index).sources()) {
                    result.indirect.add(source.under(transform, false, false));
                }
            }
            return;
        }
        collect(expr, scope, result, transform, null, true);
    }

    /**
     * 窗口定义中的分区与排序字段。
     */
    private void collectOver(SQLOver over, Scope scope, QueryResult result) {
        if (over == null) {
            return;
        }
        if (over.getPartitionBy() != null) {
            for (SQLExpr item : over.getPartitionBy()) {
                collect(item, scope, result, LineageTransform.WINDOW, null, false);
            }
        }
        if (over.getOrderBy() != null) {
            for (SQLSelectOrderByItem item : over.getOrderBy().getItems()) {
                collect(item.getExpr(), scope, result, LineageTransform.WINDOW, null, false);
            }
        }
    }

    // ==================== 关系 ====================

    /**
     * 把 FROM 中的数据来源加入作用域，并处理 JOIN 条件。
     */
    void addRelations(SQLTableSource source, Scope scope, QueryResult result) {
        if (source instanceof SQLExprTableSource table) {
            Relation relation = tableRelation(table, scope);
            if (relation != null) {
                scope.relations.add(relation);
                result.absorb(relation);
            }
            return;
        }
        if (source instanceof SQLJoinTableSource join) {
            addRelations(join.getLeft(), scope, result);
            addRelations(join.getRight(), scope, result);
            collect(join.getCondition(), scope, result, LineageTransform.JOIN, null, false);
            if (join.getUsing() != null) {
                for (SQLExpr using : join.getUsing()) {
                    collect(using, scope, result, LineageTransform.JOIN, null, false);
                }
            }
            return;
        }
        if (source instanceof SQLSubqueryTableSource subquery) {
            QueryResult inner = analyzeSelect(subquery.getSelect(), scope, null);
            List<OutputColumn> columns = rename(inner.columns, names(subquery.getColumns()));
            Relation relation = Relation.derived(ctx.identifier(subquery.getAlias()), columns,
                    Set.copyOf(inner.reads), Set.copyOf(inner.indirect));
            scope.relations.add(relation);
            result.absorb(relation);
            return;
        }
        if (source instanceof SQLUnionQueryTableSource union) {
            QueryResult inner = analyzeQuery(union.getUnion(), scope, null);
            List<OutputColumn> columns = rename(inner.columns, names(union.getColumns()));
            Relation relation = Relation.derived(ctx.identifier(union.getAlias()), columns,
                    Set.copyOf(inner.reads), Set.copyOf(inner.indirect));
            scope.relations.add(relation);
            result.absorb(relation);
            return;
        }
        if (source instanceof SQLLateralViewTableSource lateral) {
            if (lateral.getTableSource() != null) {
                addRelations(lateral.getTableSource(), scope, result);
            }
            LinkedHashSet<LineageColumnSource> sources = new LinkedHashSet<>();
            collect(lateral.getMethod(), scope, result, LineageTransform.TRANSFORMATION, sources, false);
            List<OutputColumn> columns = new ArrayList<>();
            for (String name : names(lateral.getColumns())) {
                columns.add(new OutputColumn(name, new LinkedHashSet<>(sources), null));
            }
            scope.relations.add(Relation.derived(ctx.identifier(lateral.getAlias()), columns, Set.of(), Set.of()));
            return;
        }
        if (source instanceof SQLValuesTableSource values) {
            List<OutputColumn> columns = new ArrayList<>();
            for (String name : names(values.getColumns())) {
                columns.add(new OutputColumn(name, new LinkedHashSet<>(), null));
            }
            scope.relations.add(Relation.derived(ctx.identifier(values.getAlias()), columns, Set.of(), Set.of()));
            return;
        }
        if (source != null) {
            ctx.issue(LineageIssue.Code.UNSUPPORTED_TABLE_SOURCE);
            scope.relations.add(Relation.opaque(ctx.identifier(source.getAlias())));
        }
    }

    /**
     * 解析表引用：CTE、脚本内临时表或物理表。
     */
    Relation tableRelation(SQLExprTableSource source, Scope scope) {
        SQLExpr expr = source.getExpr();
        String alias = ctx.identifier(source.getAlias());
        if (expr instanceof SQLMethodInvokeExpr) {
            ctx.issue(LineageIssue.Code.UNSUPPORTED_TABLE_SOURCE);
            return Relation.opaque(alias);
        }
        if (expr instanceof SQLIdentifierExpr identifier) {
            String name = ctx.identifier(identifier.getName());
            if ("dual".equalsIgnoreCase(name)) {
                return null;
            }
            CteDef cte = scope.cte(name);
            if (cte != null) {
                if (cte.opaque()) {
                    return Relation.opaque(alias == null ? name : alias);
                }
                return Relation.derived(alias == null ? name : alias, cte.columns(), cte.reads(), cte.indirect());
            }
        }
        LineageTable table = ctx.table(expr);
        if (table == null) {
            ctx.issue(LineageIssue.Code.UNSUPPORTED_TABLE_SOURCE);
            return Relation.opaque(alias);
        }
        LineageScriptContext.TransientTable transientTable = ctx.transientTable(table);
        if (transientTable != null) {
            return Relation.derived(alias == null ? table.name() : alias,
                    new ArrayList<>(transientTable.columns.values()), Set.copyOf(transientTable.reads),
                    Set.copyOf(transientTable.indirect));
        }
        return Relation.physical(alias, table, ctx.knownColumns(table));
    }

    /**
     * 展开关系的全部字段。
     */
    private void expand(Relation relation, QueryResult result) {
        if (relation.opaque) {
            return;
        }
        if (relation.table != null) {
            if (relation.knownColumns == null) {
                if (existsDepth == 0) {
                    ctx.issue(LineageIssue.Code.WILDCARD_UNEXPANDED, relation.table.displayName());
                }
                LinkedHashSet<LineageColumnSource> sources = new LinkedHashSet<>();
                sources.add(new LineageColumnSource(relation.table, "*", LineageTransform.IDENTITY, false, false));
                result.columns.add(new OutputColumn("*", sources, relation.table));
                return;
            }
            for (String column : relation.knownColumns) {
                LinkedHashSet<LineageColumnSource> sources = new LinkedHashSet<>();
                sources.add(new LineageColumnSource(relation.table, column, LineageTransform.IDENTITY, false, false));
                result.columns.add(new OutputColumn(column, sources, null));
            }
            return;
        }
        for (OutputColumn column : relation.columns) {
            result.columns.add(new OutputColumn(column.name(), new LinkedHashSet<>(column.sources()),
                    column.wildcardFrom()));
        }
    }

    // ==================== 字段解析 ====================

    /** 字段限定符：可选命名空间加表名或别名。 */
    record Qualifier(String schema, String name) {
    }

    /**
     * 解析限定符表达式；无法识别的结构返回 null。
     */
    Qualifier qualifier(SQLExpr owner) {
        if (owner instanceof SQLIdentifierExpr identifier) {
            return new Qualifier(null, ctx.identifier(identifier.getName()));
        }
        if (owner instanceof SQLPropertyExpr property && property.getOwner() instanceof SQLIdentifierExpr schema) {
            return new Qualifier(ctx.identifier(schema.getName()), ctx.identifier(property.getName()));
        }
        return null;
    }

    /**
     * 沿作用域链查找限定符对应的关系。
     */
    private Relation find(Scope scope, Qualifier qualifier) {
        for (Scope current = scope; current != null; current = current.parent) {
            Relation relation = current.relation(qualifier.schema(), qualifier.name());
            if (relation != null) {
                return relation;
            }
        }
        return null;
    }

    /**
     * 解析字段引用为物理来源；qualifier 为 null 表示未限定。
     */
    List<LineageColumnSource> resolve(Scope scope, Qualifier qualifier, String column) {
        if (qualifier != null) {
            Relation relation = find(scope, qualifier);
            if (relation != null) {
                return fromRelation(relation, column, false);
            }
            if (qualifier.schema() == null) {
                // 可能是结构体字段访问（如 Hive 的 info.name），按 info 字段解析。
                return resolve(scope, null, qualifier.name());
            }
            ctx.issue(LineageIssue.Code.UNRESOLVED_COLUMN, qualifier.name() + "." + column);
            return List.of();
        }
        for (Scope current = scope; current != null; current = current.parent) {
            if (current.relations.isEmpty()) {
                continue;
            }
            List<Relation> definite = new ArrayList<>();
            List<Relation> unknown = new ArrayList<>();
            for (Relation relation : current.relations) {
                Boolean has = relation.hasColumn(column);
                if (Boolean.TRUE.equals(has)) {
                    definite.add(relation);
                } else if (has == null) {
                    unknown.add(relation);
                }
            }
            List<Relation> candidates = !definite.isEmpty() ? definite : unknown;
            if (candidates.size() == 1) {
                return fromRelation(candidates.getFirst(), column, false);
            }
            if (candidates.size() > 1) {
                List<Relation> resolvable = candidates.stream().filter(r -> !r.opaque).toList();
                if (resolvable.isEmpty()) {
                    return List.of();
                }
                if (resolvable.size() == 1 && definite.isEmpty()) {
                    return fromRelation(resolvable.getFirst(), column, false);
                }
                ctx.issue(LineageIssue.Code.AMBIGUOUS_COLUMN, column);
                List<LineageColumnSource> sources = new ArrayList<>();
                for (Relation relation : resolvable) {
                    sources.addAll(fromRelation(relation, column, true));
                }
                return sources;
            }
        }
        ctx.issue(LineageIssue.Code.UNRESOLVED_COLUMN, column);
        return List.of();
    }

    /**
     * 从关系中取字段来源。
     */
    private List<LineageColumnSource> fromRelation(Relation relation, String column, boolean ambiguous) {
        if (relation.opaque) {
            return List.of();
        }
        if (relation.table != null) {
            String name = column;
            if (relation.knownColumns != null) {
                String k = LineageScriptContext.key(column);
                name = relation.knownColumns.stream().filter(c -> LineageScriptContext.key(c).equals(k))
                        .findFirst().orElse(column);
            }
            return List.of(new LineageColumnSource(relation.table, name, LineageTransform.IDENTITY, ambiguous, false));
        }
        String k = LineageScriptContext.key(column);
        for (OutputColumn output : relation.columns) {
            if (output.wildcardFrom() == null && output.name() != null
                    && LineageScriptContext.key(output.name()).equals(k)) {
                return output.sources().stream().map(s -> s.under(LineageTransform.IDENTITY, ambiguous, false)).toList();
            }
        }
        List<LineageTable> wildcards = relation.columns.stream().map(OutputColumn::wildcardFrom)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (wildcards.isEmpty()) {
            ctx.issue(LineageIssue.Code.UNRESOLVED_COLUMN, column);
            return List.of();
        }
        boolean uncertain = ambiguous || wildcards.size() > 1;
        return wildcards.stream().map(table -> new LineageColumnSource(table, column, LineageTransform.IDENTITY,
                uncertain, false)).toList();
    }

    // ==================== 表达式 ====================

    /**
     * 收集表达式引用的字段：DIRECT 来源写入 out（可为 null 表示丢弃），INDIRECT 来源写入查询依赖。
     */
    void collect(SQLObject expr, Scope scope, QueryResult result, LineageTransform base,
                 Set<LineageColumnSource> out, boolean aliasAware) {
        if (expr == null) {
            return;
        }
        expr.accept(new Walker(scope, result, base, out, aliasAware));
    }

    /**
     * 判断表达式是否为直接的字段引用。
     */
    static boolean bareColumn(SQLExpr expr) {
        return expr instanceof SQLIdentifierExpr || expr instanceof SQLPropertyExpr property
                && !"*".equals(property.getName());
    }

    /**
     * 计算输出字段名：别名优先，其次字段名；表达式未命名时为 null。
     */
    private String outputName(SQLSelectItem item) {
        if (item.getAlias() != null) {
            return ctx.identifier(item.getAlias());
        }
        SQLExpr expr = item.getExpr();
        if (expr instanceof SQLIdentifierExpr identifier) {
            return ctx.identifier(identifier.getName());
        }
        if (expr instanceof SQLPropertyExpr property) {
            return ctx.identifier(property.getName());
        }
        return null;
    }

    /**
     * 按列别名重命名输出。
     */
    private List<OutputColumn> rename(List<OutputColumn> columns, List<String> names) {
        if (names.isEmpty()) {
            return columns;
        }
        List<OutputColumn> renamed = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            OutputColumn column = columns.get(i);
            if (i < names.size() && column.wildcardFrom() == null) {
                renamed.add(new OutputColumn(names.get(i), column.sources(), null));
            } else {
                renamed.add(column);
            }
        }
        return renamed;
    }

    /**
     * 规范化名称列表。
     */
    List<String> names(List<? extends SQLObject> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>(nodes.size());
        for (SQLObject node : nodes) {
            names.add(columnName(node));
        }
        return names;
    }

    /**
     * 取列定义或列引用的名称。
     */
    String columnName(SQLObject node) {
        if (node instanceof SQLColumnDefinition definition) {
            return ctx.identifier(definition.getColumnName());
        }
        if (node instanceof SQLIdentifierExpr identifier) {
            return ctx.identifier(identifier.getName());
        }
        if (node instanceof SQLPropertyExpr property) {
            return ctx.identifier(property.getName());
        }
        if (node instanceof SQLName name) {
            return ctx.identifier(name.getSimpleName());
        }
        return null;
    }

    /**
     * 取建表语句中的列名；含约束等非列元素时跳过它们。
     */
    List<String> definedColumns(List<SQLTableElement> elements) {
        if (elements == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (SQLTableElement element : elements) {
            if (element instanceof SQLColumnDefinition definition) {
                names.add(ctx.identifier(definition.getColumnName()));
            }
        }
        return names;
    }

    /**
     * 表达式遍历器。实现各方言访问接口，使方言节点进入完整子节点；子查询单独分析。
     */
    private final class Walker extends SQLASTVisitorAdapter
            implements PGASTVisitor, MySqlASTVisitor, OracleASTVisitor, HiveASTVisitor {

        private final Scope scope;
        private final QueryResult result;
        private final Set<LineageColumnSource> out;
        private final boolean aliasAware;
        private LineageTransform transform;
        private boolean masking;

        /**
         * 创建遍历器。
         */
        Walker(Scope scope, QueryResult result, LineageTransform base, Set<LineageColumnSource> out,
               boolean aliasAware) {
            this.scope = scope;
            this.result = result;
            this.out = out;
            this.aliasAware = aliasAware;
            this.transform = base;
        }

        /**
         * 限制单条语句访问的节点数。
         */
        @Override
        public void preVisit(SQLObject node) {
            if (++visitedNodes > ctx.limits.maxNodesPerStatement()) {
                throw new LineageLimitException();
            }
        }

        /**
         * 在指定上下文中访问子节点。
         */
        private void within(LineageTransform next, boolean mask, SQLObject node) {
            if (node == null) {
                return;
            }
            LineageTransform savedTransform = transform;
            boolean savedMasking = masking;
            transform = LineageTransform.compose(transform, next);
            masking = masking || mask;
            node.accept(this);
            transform = savedTransform;
            masking = savedMasking;
        }

        /**
         * 按当前上下文输出来源。
         */
        private void emit(Iterable<LineageColumnSource> sources) {
            for (LineageColumnSource source : sources) {
                LineageColumnSource composed = source.under(transform, false, masking);
                if (composed.transform().direct()) {
                    if (out != null) {
                        out.add(composed);
                    }
                } else {
                    result.indirect.add(composed);
                }
            }
        }

        /**
         * 未限定字段。
         */
        @Override
        public boolean visit(SQLIdentifierExpr x) {
            String raw = x.getName();
            if (raw == null || ctx.isPlaceholder(raw) || PSEUDO.contains(raw.toUpperCase(Locale.ROOT))) {
                return false;
            }
            String name = ctx.identifier(raw);
            if (aliasAware) {
                OutputColumn alias = scope.output(name);
                if (alias != null && scope.relations.stream().noneMatch(r -> Boolean.TRUE.equals(r.hasColumn(name)))) {
                    emit(alias.sources());
                    return false;
                }
            }
            emit(resolve(scope, null, name));
            return false;
        }

        /**
         * 限定字段。
         */
        @Override
        public boolean visit(SQLPropertyExpr x) {
            String raw = x.getName();
            if (raw == null || "*".equals(raw) || SEQUENCE_PROPERTIES.contains(raw.toUpperCase(Locale.ROOT))) {
                return false;
            }
            Qualifier qualifier = qualifier(x.getOwner());
            if (qualifier == null) {
                x.getOwner().accept(this);
                return false;
            }
            emit(resolve(scope, qualifier, ctx.identifier(raw)));
            return false;
        }

        /**
         * COUNT(*) 等不引用具体字段。
         */
        @Override
        public boolean visit(SQLAllColumnExpr x) {
            return false;
        }

        /**
         * 聚合函数与窗口函数。
         */
        @Override
        public boolean visit(SQLAggregateExpr x) {
            String name = upper(x.getMethodName());
            boolean aggregate = AGGREGATES.contains(name) || x.getOver() == null && x.getOverRef() == null;
            LineageTransform argument = aggregate ? LineageTransform.AGGREGATION : LineageTransform.TRANSFORMATION;
            boolean mask = MASKING.contains(name);
            for (SQLExpr arg : x.getArguments()) {
                within(argument, mask, arg);
            }
            within(LineageTransform.FILTER, false, x.getFilter());
            if (x.getWithinGroup() != null) {
                for (SQLSelectOrderByItem item : x.getWithinGroup().getItems()) {
                    within(argument, mask, item.getExpr());
                }
            }
            if (x.getOrderBy() != null) {
                for (SQLSelectOrderByItem item : x.getOrderBy().getItems()) {
                    within(LineageTransform.SORT, false, item.getExpr());
                }
            }
            SQLOver over = x.getOver();
            if (over != null) {
                if (over.getPartitionBy() != null) {
                    for (SQLExpr item : over.getPartitionBy()) {
                        within(LineageTransform.WINDOW, false, item);
                    }
                }
                if (over.getOrderBy() != null) {
                    for (SQLSelectOrderByItem item : over.getOrderBy().getItems()) {
                        within(LineageTransform.WINDOW, false, item.getExpr());
                    }
                }
            }
            return false;
        }

        /**
         * 普通函数调用。
         */
        @Override
        public boolean visit(SQLMethodInvokeExpr x) {
            String name = upper(x.getMethodName());
            List<SQLExpr> arguments = x.getArguments();
            boolean mask = MASKING.contains(name);
            if ("IF".equals(name) && arguments.size() == 3) {
                within(LineageTransform.CONDITIONAL, false, arguments.get(0));
                within(LineageTransform.TRANSFORMATION, false, arguments.get(1));
                within(LineageTransform.TRANSFORMATION, false, arguments.get(2));
                return false;
            }
            LineageTransform argument = AGGREGATES.contains(name)
                    ? LineageTransform.AGGREGATION : LineageTransform.TRANSFORMATION;
            for (SQLExpr arg : arguments) {
                within(argument, mask, arg);
            }
            within(argument, mask, x.getFrom());
            within(argument, mask, x.getFor());
            return false;
        }

        /**
         * CASE：条件为 CONDITIONAL，结果为 TRANSFORMATION。
         */
        @Override
        public boolean visit(SQLCaseExpr x) {
            within(LineageTransform.CONDITIONAL, false, x.getValueExpr());
            for (SQLCaseExpr.Item item : x.getItems()) {
                within(LineageTransform.CONDITIONAL, false, item.getConditionExpr());
                within(LineageTransform.TRANSFORMATION, false, item.getValueExpr());
            }
            within(LineageTransform.TRANSFORMATION, false, x.getElseExpr());
            return false;
        }

        /**
         * EXISTS 只看是否有行：输出列与血缘无关，只并入读取表和依赖，且不因 {@code *} 未展开而降级。
         */
        @Override
        public boolean visit(SQLExistsExpr x) {
            existsDepth++;
            try {
                QueryResult inner = analyzeSelect(x.getSubQuery(), scope, null);
                result.reads.addAll(inner.reads);
                result.indirect.addAll(inner.indirect);
            } finally {
                existsDepth--;
            }
            return false;
        }

        /**
         * 子查询（标量、IN、ANY/ALL）：读取表与依赖并入当前查询，输出按当前上下文计入。
         */
        @Override
        public boolean visit(SQLSelect x) {
            QueryResult inner = analyzeSelect(x, scope, null);
            result.reads.addAll(inner.reads);
            result.indirect.addAll(inner.indirect);
            LineageTransform saved = transform;
            transform = LineageTransform.compose(transform, LineageTransform.TRANSFORMATION);
            for (OutputColumn column : inner.columns) {
                emit(column.sources());
            }
            transform = saved;
            return false;
        }

        /**
         * 名称大写。
         */
        private String upper(String name) {
            return name == null ? "" : name.toUpperCase(Locale.ROOT);
        }
    }

    /** 超过节点上限时中止当前语句。 */
    static final class LineageLimitException extends RuntimeException {

        /**
         * 创建异常；不记录堆栈以降低开销。
         */
        LineageLimitException() {
            super(null, null, false, false);
        }
    }
}
