package cn.iocoder.yudao.framework.quality.core.audit.generator;

import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;
import cn.iocoder.yudao.framework.quality.core.audit.metric.RowCountFluctuation;

import java.util.Map;

/**
 * 表行数波动检测：统计（可带过滤条件的）表行数，结合历史检测结果做环比、同比与固定区间判定。
 *
 * <p>属于 {@link ExecutionMode#METRIC}：统计 SQL 的 totalCount 列是表行数，errorCount 恒为 0；不产生问题行，
 * 也不提供错误明细 SQL。判定逻辑见 {@link RowCountFluctuation}。</p>
 */
public class RowCountFluctuationGenerator extends AuditSqlGenerator {

    public static final String STRATEGY_KEY = "ROW_COUNT_FLUCTUATION";

    /** 指标键：写入检测结果，用于按同一指标取历史基准 */
    public static final String METRIC_KEY = "ROW_COUNT";

    /**
     * 返回当前规则采用的执行模式。
     */
    @Override
    public ExecutionMode getExecutionMode() {
        return ExecutionMode.METRIC;
    }

    /**
     * 策略标识（与规则表 strategy_key 对应）。
     */
    @Override
    public String getStrategyKey() {
        return STRATEGY_KEY;
    }

    /**
     * 策略名称。
     */
    @Override
    public String getStrategyName() {
        return "表行数波动检测";
    }

    /**
     * 规则配置 JSON 示例。
     */
    @Override
    public String getConfigExample() {
        return "{\"compareMode\":\"WEEK\",\"maxIncreasePercent\":\"50\",\"maxDecreasePercent\":\"30\","
                + "\"minRowCount\":\"1\",\"windowHours\":12}";
    }

    /**
     * 规范化并校验波动判定配置。
     */
    @Override
    public Map<String, Object> normalizeConfig(Map<String, Object> config) {
        return RowCountFluctuation.normalize(config);
    }

    /**
     * 校验配置并确认统计 SQL 可生成。
     */
    @Override
    public void validateConfig(AuditRuleContext context) {
        normalizeConfig(context.getConfig());
        buildCountSql(context);
    }

    /**
     * 行数统计不按行判定合格与否。
     */
    @Override
    protected String buildValidFrag(AuditRuleContext context) {
        return "1 = 1";
    }

    /**
     * 统计 SQL：totalCount 为（过滤后的）表行数，errorCount 恒为 0。
     */
    @Override
    public String buildCountSql(AuditRuleContext context) {
        String sql = "SELECT COUNT(*) AS " + resultAlias(context, "totalCount") + ", 0 AS "
                + resultAlias(context, "errorCount") + " FROM " + context.getTableName();
        String where = effectiveWhereClause(context);
        return where == null ? sql : sql + " WHERE (" + where + ")";
    }

    /**
     * 表级指标没有问题行。
     */
    @Override
    public String buildErrorDataSql(AuditRuleContext context, int limit) {
        throw new UnsupportedOperationException("表行数波动检测没有问题行明细");
    }

    /**
     * 表级指标没有正常行抽样。
     */
    @Override
    public String buildNormalDataSql(AuditRuleContext context, int limit) {
        throw new UnsupportedOperationException("表行数波动检测没有行级抽样");
    }

}
