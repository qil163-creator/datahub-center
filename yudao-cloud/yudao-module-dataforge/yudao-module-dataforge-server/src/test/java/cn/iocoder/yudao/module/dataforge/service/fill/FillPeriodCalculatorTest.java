package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.module.dataforge.service.fill.FillPeriodCalculator.PeriodInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FillPeriodCalculator} 单元测试
 *
 * 周期语义是任务生成的核心（政府报送"本期报上期"约定），纯函数可离线验证
 *
 * @author Li
 */
public class FillPeriodCalculatorTest {

    private static final Long PLAN_ID = 9L;

    private FillPeriodConfig config(String json) {
        return FillPeriodCalculator.parseConfig(json);
    }

    // ==================== buildPeriod ====================

    @Test
    @DisplayName("月报默认配置：9 月 1 日生成报 8 月数据，截止 +3 天 17:30")
    public void testMonthlyDefault() {
        LocalDateTime generate = LocalDateTime.of(2026, 9, 1, 8, 0);
        PeriodInfo info = FillPeriodCalculator.buildPeriod(PLAN_ID, 4, config(null), generate);

        assertEquals("2026-08", info.periodKey());
        assertEquals(LocalDateTime.of(2026, 8, 1, 0, 0), info.periodStart());
        assertEquals(LocalDateTime.of(2026, 8, 31, 23, 59, 59, 999_999_999), info.periodEnd());
        assertEquals(generate, info.openTime());
        assertEquals(LocalDateTime.of(2026, 9, 4, 17, 30), info.deadlineTime());
    }

    @Test
    @DisplayName("月报自定义：5 号 09:30 生成、报当期、截止 +2 天 12:00")
    public void testMonthlyCustomConfig() {
        FillPeriodConfig config = config(
                "{\"monthGenerateDay\":5,\"generateTime\":\"09:30\",\"periodOffset\":0,"
                        + "\"deadlineOffsetDays\":2,\"deadlineTime\":\"12:00\"}");
        LocalDateTime generate = LocalDateTime.of(2026, 9, 5, 9, 30);
        PeriodInfo info = FillPeriodCalculator.buildPeriod(PLAN_ID, 4, config, generate);

        assertEquals("2026-09", info.periodKey());
        assertEquals(LocalDateTime.of(2026, 9, 7, 12, 0), info.deadlineTime());
    }

    @Test
    @DisplayName("日报默认：报昨天")
    public void testDailyDefault() {
        LocalDateTime generate = LocalDateTime.of(2026, 8, 31, 8, 0);
        PeriodInfo info = FillPeriodCalculator.buildPeriod(PLAN_ID, 2, config(null), generate);

        assertEquals("2026-08-30", info.periodKey());
        assertEquals(LocalDateTime.of(2026, 8, 30, 0, 0), info.periodStart());
    }

    @Test
    @DisplayName("周报默认：报上一 ISO 周，期次起止为周一至周日")
    public void testWeeklyDefault() {
        LocalDateTime generate = LocalDateTime.of(2026, 8, 31, 8, 0);
        PeriodInfo info = FillPeriodCalculator.buildPeriod(PLAN_ID, 3, config(null), generate);

        assertTrue(info.periodKey().matches("^\\d{4}-W\\d{2}$"), "periodKey 应为 yyyy-Www：" + info.periodKey());
        assertEquals(DayOfWeek.MONDAY, info.periodStart().getDayOfWeek());
        assertEquals(DayOfWeek.SUNDAY, info.periodEnd().getDayOfWeek());
        // 报上周：期末必须早于生成时点
        assertTrue(info.periodEnd().isBefore(generate));
        assertEquals(6, info.periodEnd().toLocalDate().toEpochDay() - info.periodStart().toLocalDate().toEpochDay());
    }

    @Test
    @DisplayName("季报默认：10 月生成报 Q3，期次为 7-1 至 9-30")
    public void testQuarterlyDefault() {
        LocalDateTime generate = LocalDateTime.of(2026, 10, 1, 8, 0);
        PeriodInfo info = FillPeriodCalculator.buildPeriod(PLAN_ID, 5, config(null), generate);

        assertEquals("2026-Q3", info.periodKey());
        assertEquals(LocalDateTime.of(2026, 7, 1, 0, 0), info.periodStart());
        assertEquals(30, info.periodEnd().getDayOfMonth());
        assertEquals(9, info.periodEnd().getMonthValue());
    }

    @Test
    @DisplayName("一次性：periodKey 含计划编号，缺省截止为生成 +7 天 17:30")
    public void testOnceDefault() {
        LocalDateTime generate = LocalDateTime.of(2026, 9, 1, 10, 0);
        PeriodInfo info = FillPeriodCalculator.buildPeriod(PLAN_ID, 1, config(null), generate);

        assertEquals("ONCE-9", info.periodKey());
        assertEquals(generate, info.openTime());
        assertEquals(LocalDateTime.of(2026, 9, 8, 17, 30), info.deadlineTime());
    }

    @Test
    @DisplayName("一次性显式配置开放与截止时间")
    public void testOnceExplicitTimes() {
        FillPeriodConfig config = config(
                "{\"onceOpenTime\":\"2026-09-01T09:00:00\",\"onceDeadlineTime\":\"2026-09-08T17:30:00\"}");
        PeriodInfo info = FillPeriodCalculator.buildPeriod(PLAN_ID, 1, config,
                LocalDateTime.of(2026, 8, 31, 18, 0));

        assertEquals(LocalDateTime.of(2026, 9, 1, 9, 0), info.openTime());
        assertEquals(LocalDateTime.of(2026, 9, 8, 17, 30), info.deadlineTime());
    }

    // ==================== 生成时点推进 ====================

    @Test
    @DisplayName("月报下一生成时点：跨月保持生成日与时刻")
    public void testNextGenerateTimeMonthly() {
        FillPeriodConfig config = config("{\"monthGenerateDay\":1}");
        LocalDateTime next = FillPeriodCalculator.computeNextGenerateTime(
                4, config, LocalDateTime.of(2026, 8, 1, 8, 0));
        assertEquals(LocalDateTime.of(2026, 9, 1, 8, 0), next);
    }

    @Test
    @DisplayName("季报下一生成时点：推进到下季度首月")
    public void testNextGenerateTimeQuarterly() {
        LocalDateTime next = FillPeriodCalculator.computeNextGenerateTime(
                5, config(null), LocalDateTime.of(2026, 7, 1, 8, 0));
        assertEquals(LocalDateTime.of(2026, 10, 1, 8, 0), next);
    }

    @Test
    @DisplayName("首次生成时点：本期候选已过则推进一个周期")
    public void testFirstGenerateTimeAdvancesWhenPassed() {
        // 8 月 15 日启用月报（生成日 1 号）：8-1 已过 → 首次生成应为 9-1
        LocalDateTime first = FillPeriodCalculator.computeFirstGenerateTime(
                4, config("{\"monthGenerateDay\":1}"), LocalDateTime.of(2026, 8, 15, 10, 0));
        assertEquals(LocalDateTime.of(2026, 9, 1, 8, 0), first);
    }

    @Test
    @DisplayName("首次生成时点：候选未过则取本期")
    public void testFirstGenerateTimeSamePeriod() {
        LocalDateTime first = FillPeriodCalculator.computeFirstGenerateTime(
                2, config(null), LocalDateTime.of(2026, 8, 31, 6, 0));
        assertEquals(LocalDateTime.of(2026, 8, 31, 8, 0), first);
    }

    @Test
    @DisplayName("一次性计划无周期生成时点")
    public void testOnceHasNoGenerateTime() {
        assertNull(FillPeriodCalculator.computeFirstGenerateTime(1, config(null), LocalDateTime.now()));
        assertNull(FillPeriodCalculator.computeNextGenerateTime(1, config(null), LocalDateTime.now()));
    }

    @Test
    @DisplayName("坏配置 JSON 与非法时刻回退默认值")
    public void testBadConfigFallsBack() {
        FillPeriodConfig config = config("{\"generateTime\":\"not-a-time\"}");
        LocalDateTime next = FillPeriodCalculator.computeNextGenerateTime(
                2, config, LocalDateTime.of(2026, 8, 31, 8, 0));
        assertEquals(LocalDateTime.of(2026, 9, 1, 8, 0), next);

        assertNotNull(FillPeriodCalculator.parseConfig(null));
        assertNotNull(FillPeriodCalculator.parseConfig(""));
    }

}
