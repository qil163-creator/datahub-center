package cn.iocoder.yudao.framework.dbswitch.core.lineage;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单次脚本分析的可变状态：标识符规范化、参数占位、表结构缓存、临时表定义以及结果收集。
 * <p>
 * 每次分析新建实例，不跨线程共享。
 */
final class LineageScriptContext {

    /** 调度参数 {@code ${name}} 与时间模板 {@code $[expr]}。 */
    private static final Pattern PARAMETER = Pattern.compile("\\$\\{([^{}\\r\\n]{1,128})}|\\$\\[([^\\[\\]\\r\\n]{1,128})]");
    private static final String TOKEN_PREFIX = "__dhp_";
    private static final Pattern TOKEN = Pattern.compile("__dhp_(\\d+)__");

    enum Fold { LOWER, UPPER, PRESERVE }

    final ProductTypeEnum product;
    final DbType dbType;
    final SqlLineageLimits limits;
    private final Fold fold;
    private final LineageCatalog catalog;
    private final Map<String, String> parameters;
    private final List<String> placeholders = new ArrayList<>();
    String defaultSchema;

    private final Map<String, List<String>> localColumns = new HashMap<>();
    private final Map<String, Optional<List<String>>> catalogCache = new HashMap<>();
    private final Map<String, TransientTable> transientTables = new HashMap<>();
    private boolean catalogFailureReported;

    private final LinkedHashSet<LineageTableEdge> tableEdges = new LinkedHashSet<>();
    private final LinkedHashSet<LineageColumnEdge> columnEdges = new LinkedHashSet<>();
    private final LinkedHashSet<LineageTable> reads = new LinkedHashSet<>();
    private final LinkedHashSet<LineageTable> writes = new LinkedHashSet<>();
    private final LinkedHashMap<String, LineageIssue> issues = new LinkedHashMap<>();
    private boolean truncationReported;
    int statementIndex;

    /**
     * 按请求初始化。
     */
    LineageScriptContext(ProductTypeEnum product, DbType dbType, Fold fold, String defaultSchema,
                         Map<String, String> parameters, LineageCatalog catalog, SqlLineageLimits limits) {
        this.product = product;
        this.dbType = dbType;
        this.fold = fold;
        this.parameters = parameters;
        this.catalog = catalog;
        this.limits = limits;
        this.defaultSchema = defaultSchema == null ? null : identifier(defaultSchema);
    }

    // ==================== 参数占位 ====================

    /**
     * 代入固定值参数，其余参数替换为可作为标识符、也可留在字面量中的占位标记。
     */
    String substituteParameters(String script) {
        Matcher matcher = PARAMETER.matcher(script);
        StringBuilder out = new StringBuilder(script.length());
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement;
            if (name != null && parameters.containsKey(name)) {
                replacement = parameters.get(name);
            } else {
                placeholders.add(matcher.group());
                replacement = TOKEN_PREFIX + (placeholders.size() - 1) + "__";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 判断标识符是否只是一个参数占位（作为值使用的参数不是字段）。
     */
    boolean isPlaceholder(String name) {
        return name != null && TOKEN.matcher(name).matches();
    }

    /**
     * 把名称中的占位标记还原为原始参数写法。
     */
    String restore(String name) {
        if (name == null || !name.contains(TOKEN_PREFIX)) {
            return name;
        }
        Matcher matcher = TOKEN.matcher(name);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            String original = index < placeholders.size() ? placeholders.get(index) : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(original));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // ==================== 标识符 ====================

    /**
     * 去掉引号；未加引号的标识符按产品规则折叠大小写。
     */
    String identifier(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if (first == '"' && last == '"') {
                return text.substring(1, text.length() - 1).replace("\"\"", "\"");
            }
            if (first == '`' && last == '`') {
                return text.substring(1, text.length() - 1).replace("``", "`");
            }
            if (first == '[' && last == ']') {
                return text.substring(1, text.length() - 1);
            }
        }
        return switch (fold) {
            case LOWER -> text.toLowerCase(Locale.ROOT);
            case UPPER -> text.toUpperCase(Locale.ROOT);
            case PRESERVE -> text;
        };
    }

    /**
     * 大小写不敏感的比较键。
     */
    static String key(String name) {
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    /**
     * 解析表名表达式为物理表；多段名称取最后两段作为命名空间与表名。
     */
    LineageTable table(SQLExprTableSource source) {
        return table(source.getExpr());
    }

    /**
     * 解析表名表达式为物理表。
     */
    LineageTable table(SQLExpr expr) {
        if (expr instanceof SQLIdentifierExpr identifier) {
            return new LineageTable(defaultSchema, identifier(identifier.getName()));
        }
        if (expr instanceof SQLPropertyExpr property) {
            String schema = null;
            SQLExpr owner = property.getOwner();
            if (owner instanceof SQLIdentifierExpr ownerIdentifier) {
                schema = identifier(ownerIdentifier.getName());
            } else if (owner instanceof SQLPropertyExpr ownerProperty) {
                schema = identifier(ownerProperty.getName());
            }
            return new LineageTable(schema == null ? defaultSchema : schema, identifier(property.getName()));
        }
        return null;
    }

    // ==================== 表结构 ====================

    /**
     * 记录脚本内 DDL 声明的列，优先于外部表结构。
     */
    void defineColumns(LineageTable table, List<String> columns) {
        if (table != null && columns != null && !columns.isEmpty() && columns.stream().noneMatch(c -> c == null)) {
            localColumns.put(table.matchKey(), List.copyOf(columns));
        }
    }

    /**
     * 返回已知的有序列名；脚本内 DDL 优先，其次外部表结构；均未知时返回 null。
     */
    List<String> knownColumns(LineageTable table) {
        List<String> local = localColumns.get(table.matchKey());
        if (local != null) {
            return local;
        }
        Optional<List<String>> cached = catalogCache.computeIfAbsent(table.matchKey(), ignored -> lookup(table));
        return cached.orElse(null);
    }

    /**
     * 调用外部表结构；任何异常都视为不可用并只报告一次。
     */
    private Optional<List<String>> lookup(LineageTable table) {
        try {
            Optional<List<String>> columns = catalog.columns(new LineageTable(table.schema(), restore(table.name())));
            if (columns == null || columns.isEmpty() || columns.get().isEmpty()) {
                return Optional.empty();
            }
            List<String> normalized = new ArrayList<>(columns.get().size());
            for (String column : columns.get()) {
                if (column == null || column.isBlank()) {
                    return Optional.empty();
                }
                normalized.add(column);
            }
            return Optional.of(List.copyOf(normalized));
        } catch (RuntimeException unavailable) {
            if (!catalogFailureReported) {
                catalogFailureReported = true;
                issue(LineageIssue.Code.CATALOG_UNAVAILABLE);
            }
            return Optional.empty();
        }
    }

    // ==================== 临时表 ====================

    /**
     * 标记脚本内的临时表：其上下游会在最终结果中折叠。
     */
    void markTransient(String matchKey) {
        transientTables.putIfAbsent(matchKey, new TransientTable());
    }

    /**
     * 返回临时表定义；非临时表返回 null。
     */
    TransientTable transientTable(LineageTable table) {
        return table == null ? null : transientTables.get(table.matchKey());
    }

    /** 临时表累积的字段来源、依赖与读取表。 */
    static final class TransientTable {
        final LinkedHashMap<String, LineageQueryResolver.OutputColumn> columns = new LinkedHashMap<>();
        final LinkedHashSet<LineageColumnSource> indirect = new LinkedHashSet<>();
        final LinkedHashSet<LineageTable> reads = new LinkedHashSet<>();

        /**
         * 合并一次写入。
         */
        void merge(List<LineageQueryResolver.Assignment> assignments, Set<LineageColumnSource> indirectSources,
                   Set<LineageTable> readTables) {
            for (LineageQueryResolver.Assignment assignment : assignments) {
                String name = assignment.column() == null ? "*" : assignment.column();
                var existing = columns.computeIfAbsent(key(name) + (assignment.wildcardFrom() == null ? ""
                                : "\u0000" + assignment.wildcardFrom().matchKey()),
                        ignored -> new LineageQueryResolver.OutputColumn(name, new LinkedHashSet<>(),
                                assignment.wildcardFrom()));
                existing.sources().addAll(assignment.sources());
            }
            indirect.addAll(indirectSources);
            reads.addAll(readTables);
        }

        /**
         * 若脚本已声明列顺序，先建立空列以保持顺序。
         */
        void declare(List<String> names) {
            for (String name : names) {
                if (name != null) {
                    columns.computeIfAbsent(key(name),
                            ignored -> new LineageQueryResolver.OutputColumn(name, new LinkedHashSet<>(), null));
                }
            }
        }
    }

    // ==================== 结果收集 ====================

    /**
     * 记录读取的持久表。
     */
    void read(Set<LineageTable> tables) {
        reads.addAll(tables);
    }

    /**
     * 记录写入的持久表。
     */
    void written(LineageTable table) {
        writes.add(table);
    }

    /**
     * 添加表级边；超出上限时截断。
     */
    void tableEdge(LineageTable upstream, LineageTable downstream) {
        if (upstream.sameAs(downstream)) {
            return;
        }
        LineageTableEdge edge = new LineageTableEdge(upstream, downstream, statementIndex);
        if (tableEdges.contains(edge)) {
            return;
        }
        if (tableEdges.size() >= limits.maxTableEdges()) {
            reportTruncation();
            return;
        }
        tableEdges.add(edge);
    }

    /**
     * 添加字段级边；自引用与超出上限的边被忽略。
     */
    void columnEdge(LineageColumnSource source, LineageTable downstream, String downstreamColumn) {
        if (source.table().sameAs(downstream)) {
            return;
        }
        LineageColumnEdge edge = new LineageColumnEdge(source.table(), source.column(), downstream,
                downstreamColumn, source.transform(), source.ambiguous(), source.masking(), statementIndex);
        if (columnEdges.contains(edge)) {
            return;
        }
        if (columnEdges.size() >= limits.maxColumnEdges()) {
            reportTruncation();
            return;
        }
        columnEdges.add(edge);
    }

    /**
     * 报告截断（只报告一次）。
     */
    private void reportTruncation() {
        if (!truncationReported) {
            truncationReported = true;
            issues.put("TRUNCATED", LineageIssue.Code.TRUNCATED.of(null));
        }
    }

    /**
     * 记录当前语句的问题；相同问题只保留一条。
     */
    void issue(LineageIssue.Code code, Object... args) {
        Integer index = statementIndex <= 0 ? null : statementIndex;
        LineageIssue issue = code.of(index, args);
        String dedupe = issue.code() + "\u0000" + index + "\u0000" + issue.message();
        if (issues.size() < limits.maxIssues() || issues.containsKey(dedupe)) {
            issues.putIfAbsent(dedupe, issue);
        }
    }

    /**
     * 记录脚本级问题。
     */
    void scriptIssue(LineageIssue.Code code) {
        int saved = statementIndex;
        statementIndex = 0;
        issue(code);
        statementIndex = saved;
    }

    /**
     * 汇总脚本结果，并把占位标记还原为参数原文。
     */
    SqlLineageResult result(int statementCount, int failedStatements) {
        List<LineageTableEdge> restoredTables = new ArrayList<>(tableEdges.size());
        for (LineageTableEdge edge : tableEdges) {
            restoredTables.add(new LineageTableEdge(restoreTable(edge.upstream(), edge.statementIndex()),
                    restoreTable(edge.downstream(), edge.statementIndex()), edge.statementIndex()));
        }
        List<LineageColumnEdge> restoredColumns = new ArrayList<>(columnEdges.size());
        for (LineageColumnEdge edge : columnEdges) {
            restoredColumns.add(new LineageColumnEdge(restoreTable(edge.upstream(), edge.statementIndex()),
                    restore(edge.upstreamColumn()), restoreTable(edge.downstream(), edge.statementIndex()),
                    restore(edge.downstreamColumn()), edge.transform(), edge.ambiguous(), edge.masking(),
                    edge.statementIndex()));
        }
        List<LineageTable> restoredReads = reads.stream().map(table -> restoreTable(table, null)).distinct().toList();
        List<LineageTable> restoredWrites = writes.stream().map(table -> restoreTable(table, null)).distinct().toList();
        SqlLineageStatus status = status(statementCount, failedStatements);
        return new SqlLineageResult(status, statementCount, restoredReads, restoredWrites, restoredTables,
                restoredColumns, List.copyOf(issues.values()));
    }

    /**
     * 计算整体状态。
     */
    SqlLineageStatus status(int statementCount, int failedStatements) {
        if (statementCount > 0 && failedStatements >= statementCount) {
            return SqlLineageStatus.FAILED;
        }
        return issues.values().stream().anyMatch(LineageIssue::degrading)
                ? SqlLineageStatus.PARTIAL : SqlLineageStatus.SUCCESS;
    }

    /**
     * 当前问题清单。
     */
    List<LineageIssue> issues() {
        return List.copyOf(issues.values());
    }

    /**
     * 还原表名中的参数，并报告参数化表名。
     */
    LineageTable restoreTable(LineageTable table, Integer statement) {
        String schema = restore(table.schema());
        String name = restore(table.name());
        if (!name.equals(table.name()) || schema != null && !schema.equals(table.schema())) {
            int saved = statementIndex;
            statementIndex = statement == null ? 0 : statement;
            issue(LineageIssue.Code.PARAMETERIZED_TABLE, (schema == null ? "" : schema + ".") + name);
            statementIndex = saved;
        }
        return new LineageTable(schema, name);
    }
}
