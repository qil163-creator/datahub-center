package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 标量和组合唯一性共用的 ROW_SET 语义。totalCount 表示整个 WHERE
 * 范围的行数；errorCount 统计重复组的全部成员，与问题明细完全一致。
 * Null 绝不等同于空字符串。忽略空值时，含任意 null 分量的元组不参与
 * 重复检测（但仍计入 totalCount）。输入是经过调用方验证的物理 SQL
 * 标识符和过滤条件。不暴露行级谓词：唯一性判断需要完整数据范围。
 */
abstract class AbstractUniquenessValidationGenerator extends AuditSqlGenerator {

    /**
     * 返回当前规则计算必须读取的列集合。
     */
    protected abstract List<String> requiredColumns(AuditRuleContext context);

    /**
     * 返回当前规则默认是否忽略空值。
     */
    protected abstract boolean defaultIgnoreNull();

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    @Override
    protected final String buildValidFrag(AuditRuleContext context) {
        throw new UnsupportedOperationException("唯一性属于集合检测，不支持独立行级片段");
    }

    /**
     * 统计 SQL：SELECT 总数 + 错误数。
     */
    @Override
    public final String buildCountSql(AuditRuleContext context) {
        List<String> columns = requiredColumns(context);
        String source = context.getTableName();
        return "SELECT (SELECT COUNT(*) FROM " + source + scope(context, columns, false) + ") AS "
                + resultAlias(context, "totalCount") + ", COALESCE(SUM(dup_count), 0) AS "
                + resultAlias(context, "errorCount") + " FROM (SELECT COUNT(*) AS dup_count FROM "
                + source + scope(context, columns, ignoreNull(context)) + " GROUP BY "
                + String.join(", ", columns) + " HAVING COUNT(*) > 1) quality_duplicates";
    }

    /**
     * 错误明细 SQL（带行数上限）。
     */
    @Override
    public final String buildErrorDataSql(AuditRuleContext context, int limit) {
        return buildRowsSql(context, limit, false);
    }

    /**
     * 返回本次范围内未判定为违规的记录；NULL/忽略空值遵循与统计相同的三值逻辑。
     */
    @Override
    public final String buildNormalDataSql(AuditRuleContext context, int limit) {
        return buildRowsSql(context, limit, true);
    }

    /**
     * 构建数据行SQL。
     */
    private String buildRowsSql(AuditRuleContext context, int limit, boolean normal) {
        List<String> columns = requiredColumns(context);
        String selected = IntStream.range(0, columns.size())
                .mapToObj(index -> columns.get(index) + " AS quality_key_" + index)
                .collect(java.util.stream.Collectors.joining(", "));
        String matching = IntStream.range(0, columns.size()).mapToObj(index -> {
            String left = "quality_row." + columns.get(index);
            String right = "quality_duplicates.quality_key_" + index;
            return "(" + left + " = " + right + " OR (" + left + " IS NULL AND " + right + " IS NULL))";
        }).collect(java.util.stream.Collectors.joining(" AND "));
        // EXISTS 避免行值 IN 在 NULL 元组上的 UNKNOWN 结果，也防止合成键
        // 进入导出的问题行。两次扫描使用同一个冻结范围。
        String sql = "SELECT quality_row.* FROM (SELECT * FROM " + context.getTableName()
                + scope(context, columns,
                false) + ") quality_row WHERE " + (normal ? "NOT " : "") + "EXISTS (SELECT 1 FROM (SELECT "
                + selected + " FROM " + context.getTableName() + scope(context, columns, ignoreNull(context))
                + " GROUP BY " + String.join(", ", columns) + " HAVING COUNT(*) > 1) quality_duplicates WHERE "
                + matching + ")";
        return context.database()
                .limitGeneratedSelect(sql, Math.max(1, limit));
    }

    /**
     * 读取当前规则是否忽略空值的配置。
     */
    private boolean ignoreNull(AuditRuleContext context) {
        Object value = context.getConfig() == null ? null : context.getConfig().get("ignoreNull");
        if (value == null && context.getConfig() != null) {
            value = context.getConfig().get("ignoreNullValue");
        }
        if (value == null) {
            return defaultIgnoreNull();
        }
        if ("true".equalsIgnoreCase(String.valueOf(value))) {
            return true;
        }
        if ("false".equalsIgnoreCase(String.valueOf(value))) {
            return false;
        }
        throw new IllegalArgumentException("唯一性 ignoreNull 必须是布尔值");
    }

    /**
     * 返回当前范围。
     */
    private static String scope(AuditRuleContext context, List<String> columns, boolean ignoreNull) {
        List<String> predicates = new ArrayList<>();
        String where = effectiveWhereClause(context);
        if (StrUtil.isNotBlank(where)) {
            predicates.add("(" + where + ")");
        }
        if (ignoreNull) {
            columns.forEach(column -> predicates.add(column + " IS NOT NULL"));
        }
        return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
    }
}
