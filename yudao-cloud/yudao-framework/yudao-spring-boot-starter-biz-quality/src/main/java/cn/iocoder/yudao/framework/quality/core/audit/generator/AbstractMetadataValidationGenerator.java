package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.quality.core.audit.*;

import java.util.*;

/** 元数据按字段统计，绝不把字段问题冒充逐行数据问题。 */
abstract class AbstractMetadataValidationGenerator extends AuditSqlGenerator {

    protected record Assessment(String column, String message, boolean invalid) {

    }

    /**
     * 返回当前规则采用的执行模式。
     */
    public ExecutionMode getExecutionMode() {
        return ExecutionMode.METADATA;
    }

    /**
     * 根据输入行和规则配置计算数据质量结果。
     */
    protected abstract List<Assessment> assess(AuditRuleContext context);

    /**
     * 根据规则配置生成判定数据有效的 SQL 条件。
     */
    protected final String buildValidFrag(AuditRuleContext context) {
        throw new IllegalArgumentException("Metadata strategies do not expose a row predicate");
    }

    /**
     * 校验配置。
     */
    public final void validateConfig(AuditRuleContext context) {
        normalizeConfig(context.getConfig());
    }

    /**
     * 校验并读取可信的列结构快照。
     */
    protected final List<AuditRuleContext.ColumnSnapshot> snapshots(AuditRuleContext context) {
        if (context.getColumnSnapshots() == null) {
            throw new IllegalArgumentException("Trusted columnSnapshots are required");
        }
        if (context.getColumnSnapshots().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Column snapshot cannot be null");
        }
        return List.copyOf(context.getColumnSnapshots());
    }

    /**
     * 统计 SQL：SELECT 总数 + 错误数。
     */
    public final String buildCountSql(AuditRuleContext context) {
        List<Assessment> result = assess(context);
        return "SELECT " + result.size() + " AS " + resultAlias(context, "totalCount") + ", "
                + result.stream().filter(Assessment::invalid).count() + " AS " + resultAlias(context, "errorCount");
    }

    /**
     * 错误明细 SQL（带行数上限）。
     */
    public final String buildErrorDataSql(AuditRuleContext context, int limit) {
        return detailSql(context, limit, true);
    }

    /**
     * 返回本次范围内未判定为违规的记录；NULL/忽略空值遵循与统计相同的三值逻辑。
     */
    public final String buildNormalDataSql(AuditRuleContext context, int limit) {
        return detailSql(context, limit, false);
    }

    /**
     * 根据元数据规则结果生成数量受限的明细查询 SQL。
     */
    private String detailSql(AuditRuleContext context, int limit, boolean invalid) {
        List<String> rows = assess(context).stream().filter(a -> a.invalid() == invalid).limit(Math.max(1, limit))
                .map(a -> "SELECT " + AuditConfigSupport.literal(context, a.column() == null ? "" : a.column())
                        + " AS " + resultAlias(context, "columnName") + ", " + AuditConfigSupport.literal(context,
                        a.message())
                        + " AS " + resultAlias(context, "message")).toList();
        return rows.isEmpty() ? "SELECT CAST(NULL AS VARCHAR(256)) AS " + resultAlias(context, "columnName")
                + ", CAST(NULL AS VARCHAR(1024)) AS " + resultAlias(context, "message") + " WHERE 1 = 0" : String.join(
                " UNION ALL ", rows);
    }

    /**
     * 分组统计 SQL：按分组字段（如机构标识字段 org_code）返回每组的总数 + 错误数， 供机构维度的问题留存与评分聚合使用。
     */
    public final String buildGroupCountSql(AuditRuleContext context, String groupColumn) {
        throw unsupported();
    }

    /**
     * 构建分组键数量SQL。
     */
    public final String buildGroupedKeyCountSql(AuditRuleContext context, String scopeColumn, String keyColumn) {
        throw unsupported();
    }

    /**
     * 构建复合键数量SQL。
     */
    public final String buildCompositeKeyCountSql(AuditRuleContext context, List<String> columns, String scopeColumn) {
        throw unsupported();
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private IllegalArgumentException unsupported() {
        return new IllegalArgumentException("Metadata strategies cannot group by data row keys");
    }
}
