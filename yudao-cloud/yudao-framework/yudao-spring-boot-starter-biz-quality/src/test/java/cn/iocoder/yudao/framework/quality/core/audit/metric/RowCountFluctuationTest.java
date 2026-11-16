package cn.iocoder.yudao.framework.quality.core.audit.metric;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.quality.core.audit.AuditRuleContext;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlGenerator;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.framework.quality.core.audit.generator.RowCountFluctuationGenerator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowCountFluctuationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 2, 0);

    private static RowCountFluctuation.Config config(Map<String, Object> input) {
        return RowCountFluctuation.parse(input);
    }

    @Test
    void normalizesStringsDropsBlankOptionalsAndRequiresACondition() {
        Map<String, Object> input = new HashMap<>();
        input.put("compareMode", "WEEK");
        input.put("maxDecreasePercent", "30.50");
        input.put("maxIncreasePercent", "");
        input.put("minRowCount", "1");
        assertThat(RowCountFluctuation.normalize(input)).containsEntry("compareMode", "WEEK")
                .containsEntry("maxDecreasePercent", "30.5").containsEntry("minRowCount", "1")
                .containsEntry("windowHours", 12).doesNotContainKey("maxIncreasePercent");

        assertThatThrownBy(() -> RowCountFluctuation.normalize(Map.of("compareMode", "PREVIOUS")))
                .hasMessageContaining("至少配置");
        assertThatThrownBy(() -> RowCountFluctuation.normalize(Map.of("maxDecreasePercent", "101")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RowCountFluctuation.normalize(Map.of("maxIncreasePercent", "1.234")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RowCountFluctuation.normalize(Map.of("minRowCount", "1.5")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RowCountFluctuation.normalize(Map.of("minRowCount", "9", "maxRowCount", "3")))
                .hasMessageContaining("最少行数");
        assertThatThrownBy(() -> RowCountFluctuation.normalize(Map.of("minRowCount", "1", "unknown", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RowCountFluctuation.pick(Map.of("minRowCount", "1", "toleranceType", "NONE", "pkColumn", "id")))
                .containsOnlyKeys("minRowCount");
    }

    @Test
    void computesBaselineWindowsForEachCompareMode() {
        var previous = config(Map.of("compareMode", "PREVIOUS", "maxDecreasePercent", "30")).baselineWindow(NOW);
        assertThat(previous.previous()).isTrue();
        assertThat(previous.before()).isEqualTo(NOW);
        var week = config(Map.of("compareMode", "WEEK", "maxDecreasePercent", "30", "windowHours", 6))
                .baselineWindow(NOW);
        assertThat(week.target()).isEqualTo(NOW.minusDays(7));
        assertThat(week.from()).isEqualTo(NOW.minusDays(7).minusHours(6));
        assertThat(week.to()).isEqualTo(NOW.minusDays(7).plusHours(6));
        assertThat(config(Map.of("compareMode", "MONTH", "minRowCount", "1")).baselineWindow(NOW).target())
                .isEqualTo(LocalDateTime.of(2026, 10, 16, 2, 0));
        assertThat(config(Map.of("compareMode", "YEAR", "minRowCount", "1")).baselineWindow(NOW).target())
                .isEqualTo(LocalDateTime.of(2025, 11, 16, 2, 0));
    }

    @Test
    void judgesIncreaseDecreaseWithExactPercentages() {
        var bounded = config(Map.of("maxIncreasePercent", "50", "maxDecreasePercent", "30"));
        var pass = RowCountFluctuation.evaluate(bounded, 1200, 1000L, NOW.minusDays(1));
        assertThat(pass.status()).isEqualTo(RowCountFluctuation.Status.PASS);
        assertThat(pass.changePercent()).isEqualByComparingTo("20.00");
        assertThat(pass.message()).contains("当前 1200 行", "1000 行", "+20.00%");

        var drop = RowCountFluctuation.evaluate(bounded, 699, 1000L, NOW.minusDays(1));
        assertThat(drop.status()).isEqualTo(RowCountFluctuation.Status.FAIL);
        assertThat(drop.changePercent()).isEqualByComparingTo("-30.10");
        assertThat(drop.message()).contains("降幅超过上限 30%");

        assertThat(RowCountFluctuation.evaluate(bounded, 700, 1000L, null).status())
                .as("恰好等于上限不算超出").isEqualTo(RowCountFluctuation.Status.PASS);
        assertThat(RowCountFluctuation.evaluate(bounded, 1501, 1000L, null).status())
                .isEqualTo(RowCountFluctuation.Status.FAIL);
        var huge = RowCountFluctuation.evaluate(bounded, Long.MAX_VALUE, 1L, null);
        assertThat(huge.changePercent()).isEqualTo(new BigDecimal(Long.MAX_VALUE - 1).multiply(new BigDecimal(100))
                .setScale(2));
    }

    @Test
    void handlesZeroBaselineMissingBaselineAndFixedRange() {
        var bounded = config(Map.of("maxIncreasePercent", "50", "minRowCount", "1"));
        assertThat(RowCountFluctuation.evaluate(bounded, 0, 0L, null).status())
                .as("0 → 0 无变化，但低于最少行数").isEqualTo(RowCountFluctuation.Status.FAIL);
        var fromZero = RowCountFluctuation.evaluate(bounded, 5, 0L, null);
        assertThat(fromZero.status()).isEqualTo(RowCountFluctuation.Status.FAIL);
        assertThat(fromZero.changePercent()).isNull();

        var learning = RowCountFluctuation.evaluate(bounded, 10, null, null);
        assertThat(learning.status()).isEqualTo(RowCountFluctuation.Status.NO_BASELINE);
        assertThat(learning.message()).contains("没有可比较的历史结果");
        assertThat(RowCountFluctuation.evaluate(bounded, 0, null, null).status())
                .as("没有基准时固定区间照样生效").isEqualTo(RowCountFluctuation.Status.FAIL);

        var rangeOnly = config(Map.of("minRowCount", "1", "maxRowCount", "100"));
        assertThat(RowCountFluctuation.evaluate(rangeOnly, 50, null, null).status())
                .isEqualTo(RowCountFluctuation.Status.PASS);
        assertThat(RowCountFluctuation.evaluate(rangeOnly, 101, 50L, null).message()).contains("超过最多行数 100");
    }

    @Test
    void percentModesReportExpectedRangeFromBaselineAndFixedBounds() {
        var bounded = config(Map.of("maxIncreasePercent", "50", "maxDecreasePercent", "30", "maxRowCount", "1400"));
        var verdict = RowCountFluctuation.evaluate(bounded, 1200, 1000L, null);
        assertThat(verdict.lowerBound()).isEqualByComparingTo("700");
        assertThat(verdict.upperBound()).as("固定上限更严时取交集").isEqualByComparingTo("1400");
        var learning = RowCountFluctuation.evaluate(bounded, 1200, null, null);
        assertThat(learning.lowerBound()).isNull();
        assertThat(learning.upperBound()).isEqualByComparingTo("1400");
    }

    @Test
    void dynamicConfigHasOwnDefaultsAndRejectsPercentLimits() {
        Map<String, Object> normalized = RowCountFluctuation.normalize(Map.of("compareMode", "DYNAMIC"));
        assertThat(normalized).containsEntry("dynamicMethod", "MAD").containsEntry("sensitivity", "3")
                .containsEntry("historySize", 30).containsEntry("minSamples", 7).containsEntry("sameWeekday", false)
                .doesNotContainKey("windowHours");
        assertThatThrownBy(() -> RowCountFluctuation.normalize(Map.of("compareMode", "DYNAMIC",
                "maxDecreasePercent", "30"))).hasMessageContaining("不能同时配置");
        assertThatThrownBy(() -> RowCountFluctuation.normalize(Map.of("compareMode", "WEEK", "minRowCount", "1",
                "sensitivity", "3"))).hasMessageContaining("仅适用于动态阈值");
        assertThatThrownBy(() -> RowCountFluctuation.normalize(Map.of("compareMode", "DYNAMIC", "sensitivity", "11")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RowCountFluctuation.normalize(Map.of("compareMode", "DYNAMIC", "historySize", 5,
                "minSamples", 6))).hasMessageContaining("最少样本数");
    }

    private static java.util.List<BigDecimal> values(long... rows) {
        return java.util.Arrays.stream(rows).mapToObj(BigDecimal::valueOf).toList();
    }

    @Test
    void dynamicMadIsRobustToOutliersAndSigmaUsesMeanAndStdDev() {
        var mad = config(Map.of("compareMode", "DYNAMIC", "minSamples", 5, "historySize", 10));
        // 中位数 101，|x-101| = 1,1,3,3,5,899 → 中位数 3 → 尺度 4.4478，k=3 → 101 ± 13.3434
        var history = values(100, 98, 102, 96, 104, 1000);
        var normal = RowCountFluctuation.evaluateDynamic(mad, 110, history);
        assertThat(normal.status()).isEqualTo(RowCountFluctuation.Status.PASS);
        assertThat(normal.lowerBound()).isEqualByComparingTo("87.66");
        assertThat(normal.upperBound()).isEqualByComparingTo("114.34");
        assertThat(normal.message()).contains("近 6 次中位数 101", "预期区间 87.66～114.34");
        assertThat(RowCountFluctuation.evaluateDynamic(mad, 60, history).message()).contains("低于动态阈值下限");

        var sigma = config(Map.of("compareMode", "DYNAMIC", "dynamicMethod", "SIGMA", "sensitivity", "2",
                "minSamples", 4, "historySize", 10));
        // 均值 100，总体标准差 sqrt(100/4)=5，k=2 → 100 ± 10
        assertThat(RowCountFluctuation.evaluateDynamic(sigma, 110, values(95, 105, 95, 105)).status())
                .as("恰好等于上限不算超出").isEqualTo(RowCountFluctuation.Status.PASS);
        var verdict = RowCountFluctuation.evaluateDynamic(sigma, 111, values(95, 105, 95, 105));
        assertThat(verdict.status()).isEqualTo(RowCountFluctuation.Status.FAIL);
        assertThat(verdict.lowerBound()).isEqualByComparingTo("90");
        assertThat(verdict.upperBound()).isEqualByComparingTo("110");
        assertThat(verdict.message()).contains("高于动态阈值上限");
    }

    @Test
    void dynamicLearnsUntilEnoughSamplesAndToleratesFlatHistory() {
        var dynamic = config(Map.of("compareMode", "DYNAMIC", "minSamples", 3, "historySize", 10, "minRowCount", "1"));
        var learning = RowCountFluctuation.evaluateDynamic(dynamic, 50, values(40, 41));
        assertThat(learning.status()).isEqualTo(RowCountFluctuation.Status.NO_BASELINE);
        assertThat(learning.message()).contains("学习中（历史样本 2/3）");
        assertThat(RowCountFluctuation.evaluateDynamic(dynamic, 0, values(40)).status())
                .as("学习期间固定区间照样生效").isEqualTo(RowCountFluctuation.Status.FAIL);

        // 历史完全一致：按 1%（至少 1 行）容差，k=3 → 1000 ± 30
        var flat = RowCountFluctuation.evaluateDynamic(dynamic, 1025, values(1000, 1000, 1000));
        assertThat(flat.status()).isEqualTo(RowCountFluctuation.Status.PASS);
        assertThat(flat.upperBound()).isEqualByComparingTo("1030");
        assertThat(RowCountFluctuation.evaluateDynamic(dynamic, 1031, values(1000, 1000, 1000)).status())
                .isEqualTo(RowCountFluctuation.Status.FAIL);
        assertThat(RowCountFluctuation.evaluateDynamic(dynamic, 2, values(0, 0, 0)).upperBound())
                .as("中心为 0 时容差至少 1 行").isEqualByComparingTo("3");
    }

    @Test
    void generatorIsRegisteredAsMetricAndCountsWithFilterOnly() {
        AuditSqlGenerator generator = new AuditSqlRegistry().getGenerator("ROW_COUNT_FLUCTUATION");
        assertThat(generator).isInstanceOf(RowCountFluctuationGenerator.class);
        assertThat(generator.getExecutionMode()).isEqualTo(AuditSqlGenerator.ExecutionMode.METRIC);

        AuditRuleContext context = new AuditRuleContext(cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover());
        context.setProductType(ProductTypeEnum.POSTGRESQL);
        context.setTableName("\"public\".\"orders\"");
        context.setWhereClause("\"status\" = 'PAID'");
        context.setConfig(Map.of("maxDecreasePercent", "30"));
        generator.validateConfig(context);
        assertThat(generator.buildCountSql(context)).isEqualTo(
                "SELECT COUNT(*) AS \"totalCount\", 0 AS \"errorCount\" FROM \"public\".\"orders\" WHERE (\"status\" = 'PAID')");
        assertThatThrownBy(() -> generator.buildErrorDataSql(context, 10)).isInstanceOf(UnsupportedOperationException.class);
    }

}
