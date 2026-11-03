package cn.iocoder.yudao.framework.quality.core.audit;

import cn.hutool.core.util.StrUtil;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 稽查规则 SQL 生成器：一个策略对应一种检查逻辑
 *
 * <p>参考 qData 质量引擎的三段式输出：
 * <ul>
 *     <li>统计 SQL：一次查询返回总记录数 totalCount 与问题记录数 errorCount；</li>
 *     <li>错误明细 SQL：返回问题记录，供排查与治理闭环。</li>
 * </ul>
 *
 * @author Li
 */
public abstract class AuditSqlGenerator {

    public enum ExecutionMode {ROW, METADATA}

    /**
     * 返回当前规则采用的执行模式。
     */
    public ExecutionMode getExecutionMode() {
        return ExecutionMode.ROW;
    }

    /** 旧策略保留原配置，新策略可收紧参数契约。 */
    public java.util.Map<String, Object> normalizeConfig(java.util.Map<String, Object> config) {
        return config == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(config);
    }

    /** 仅验证配置与已绑定字段，不执行 SQL。 */
    public void validateConfig(AuditRuleContext context) {
        buildCountSql(context);
    }

    /** 策略标识（与规则表 strategy_key 对应） */
    public abstract String getStrategyKey();

    /** 策略名称 */
    public abstract String getStrategyName();

    /** 规则配置 JSON 示例 */
    public abstract String getConfigExample();

    /**
     * 构建"合规"条件片段（TRUE 表示该行通过检查）
     */
    protected abstract String buildValidFrag(AuditRuleContext context);

    /**
     * 构建"违规"条件片段：默认对合规片段取反；
     * 不忽略空值时，稽查字段为 NULL 也计为违规（与 qData 语义一致）
     */
    protected String buildInvalidFrag(AuditRuleContext context) {
        String valid = buildValidFrag(context);
        if (context.isIgnoreNullValue()) {
            return "NOT (" + valid + ")";
        }
        StringBuilder frag = new StringBuilder("(");
        for (String column : context.resolveColumns()) {
            frag.append(column).append(" IS NULL OR ");
        }
        frag.append("NOT (").append(valid).append("))");
        return frag.toString();
    }

    /**
     * 统计 SQL：SELECT 总数 + 错误数
     */
    public String buildCountSql(AuditRuleContext context) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT COUNT(*) AS ").append(resultAlias(context, "totalCount")).append(", ")
                .append("COUNT(CASE WHEN ").append(buildInvalidFrag(context)).append(" THEN 1 END) AS ")
                .append(resultAlias(context, "errorCount")).append(" ")
                .append("FROM ").append(context.getTableName());
        appendWhere(sql, context, " WHERE ");
        return sql.toString();
    }

    /**
     * 分组统计 SQL：按分组字段（如机构标识字段 org_code）返回每组的总数 + 错误数，
     * 供机构维度的问题留存与评分聚合使用
     */
    public String buildGroupCountSql(AuditRuleContext context, String groupColumn) {
        return buildGroupedCounts(context, groupColumn + " AS " + resultAlias(context, "groupKey") + ", ", groupColumn);
    }

    /** 同一次查询按范围列和记录键统计；调用方须先验证两个列标识符。 */
    public String buildGroupedKeyCountSql(AuditRuleContext context, String scopeColumn, String keyColumn) {
        return buildGroupedCounts(context, scopeColumn + " AS " + resultAlias(context, "scopeKey") + ", "
                + keyColumn + " AS " + resultAlias(context, "groupKey") + ", ", scopeColumn + ", " + keyColumn);
    }

    /** 按有序复合键统计；调用方先验证列标识符，不能把逐列集合当作元组集合。 */
    public String buildCompositeKeyCountSql(AuditRuleContext context, List<String> columns, String scopeColumn) {
        String selected = IntStream.range(0, columns.size())
                .mapToObj(i -> columns.get(i) + " AS " + resultAlias(context, "key" + i) + ", ")
                .collect(java.util.stream.Collectors.joining());
        String grouped = String.join(", ", columns);
        if (scopeColumn != null) {
            selected = scopeColumn + " AS " + resultAlias(context, "scopeKey") + ", " + selected;
            grouped = scopeColumn + ", " + grouped;
        }
        return buildGroupedCounts(context, selected, grouped);
    }

    /**
     * 生成按当前键分组的总行数及错误行数统计 SQL。
     */
    private String buildGroupedCounts(AuditRuleContext context, String selected, String grouped) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT ").append(selected)
                .append("COUNT(*) AS ").append(resultAlias(context, "totalCount")).append(", ")
                .append("COUNT(CASE WHEN ").append(buildInvalidFrag(context)).append(" THEN 1 END) AS ")
                .append(resultAlias(context, "errorCount")).append(" ")
                .append("FROM ").append(context.getTableName());
        appendWhere(sql, context, " WHERE ");
        sql.append(" GROUP BY ").append(grouped);
        return sql.toString();
    }

    /**
     * 保留其他产品已持久化的 SQL/哈希契约。以下引擎无法使用通用的带引号写法：。
     */
    protected static String resultAlias(AuditRuleContext context, String alias) {
        // 保留其他产品已持久化的 SQL/哈希契约。以下引擎无法使用通用的带引号写法：
        // TDengine、Hive 和 SQL Server 需要各自的引号。DM 和 ShenTong 将未加引号的名称存为
        // 大写，因此带引号的别名必须在子查询两侧使用相同的折叠名称。
        return context.getProductType() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.TDENGINE
                || context.getProductType() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.HIVE
                || context.getProductType() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SQLSERVER
                || context.getProductType() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.DM
                || context.getProductType() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.OSCAR
                ? cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard.quotePhysicalIdentifier(
                context.getProductType(), alias)
                : "\"" + alias + "\"";
    }

    /**
     * 错误明细 SQL（带行数上限）
     */
    public String buildErrorDataSql(AuditRuleContext context, int limit) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT * FROM ").append(context.getTableName())
                .append(" WHERE ").append(buildInvalidFrag(context));
        appendWhere(sql, context, " AND ");
        if (context.getProductType() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.SQLSERVER
                || context.getProductType() == cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.ORACLE) {
            return context.database()
                    .limitGeneratedSelect(sql.toString(), Math.max(1, limit));
        }
        sql.append(" LIMIT ").append(Math.max(1, limit));
        return sql.toString();
    }

    /** 返回本次范围内未判定为违规的记录；NULL/忽略空值遵循与统计相同的三值逻辑。 */
    public String buildNormalDataSql(AuditRuleContext context, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(context.getTableName())
                .append(" WHERE CASE WHEN ").append(buildInvalidFrag(context))
                .append(" THEN 1 ELSE 0 END = 0");
        appendWhere(sql, context, " AND ");
        return context.database().limitGeneratedSelect(sql.toString(), Math.max(1, limit));
    }

    /**
     * 追加WHERE 条件。
     */
    private static void appendWhere(StringBuilder sql, AuditRuleContext context, String prefix) {
        String where = effectiveWhereClause(context);
        if (where != null) {
            sql.append(prefix).append('(').append(where).append(')');
        }
    }

    /** 普通谓词和集合检测共用同一调用方条件与强制范围。 */
    protected static String effectiveWhereClause(AuditRuleContext context) {
        // 现有渲染器已对整个谓词分组。仅在组合额外的
        // 强制范围时，才需单独对调用方谓词分组，尤其是包含 OR 时。
        if (context.getScopePredicates() == null || context.getScopePredicates().isEmpty()) {
            return StrUtil.isNotBlank(context.getWhereClause()) ? context.getWhereClause() : null;
        }
        List<String> clauses = new java.util.ArrayList<>();
        if (StrUtil.isNotBlank(context.getWhereClause())) {
            clauses.add("(" + context.getWhereClause() + ")");
        }
        if (context.getScopePredicates() != null) {
            for (AuditRuleContext.ScopePredicate predicate : context.getScopePredicates()) {
                String column = cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard
                        .quotePhysicalIdentifier(context.getProductType(), predicate.column());
                if (predicate.operator() == AuditRuleContext.ScopeOperator.EQUALS) {
                    clauses.add(column + " = " + quoteText((String) predicate.value()));
                } else if (predicate.operator() == AuditRuleContext.ScopeOperator.IN) {
                    @SuppressWarnings("unchecked")
                    List<String> values = (List<String>) predicate.value();
                    clauses.add(column + " IN (" + values.stream().map(AuditSqlGenerator::quoteText)
                            .collect(java.util.stream.Collectors.joining(", ")) + ")");
                } else {
                    throw new IllegalArgumentException("Unsupported typed scope operator");
                }
            }
        }
        return clauses.isEmpty() ? null : String.join(" AND ", clauses);
    }

    /**
     * 引用文本。
     */
    private static String quoteText(String value) {
        // 此有限字面值契约明确排除反斜杠。其含义会随
        // MySQL 系列 SQL 模式和 PostgreSQL 会话设置改变；仅重复引号
        // 无法提供可移植的转义规则。这类值必须使用参数绑定执行 API。
        if (value == null || value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0 || value.length() > 1024) {
            throw new IllegalArgumentException("Invalid typed text scope value");
        }
        return "'" + value.replace("'", "''") + "'";
    }

}
