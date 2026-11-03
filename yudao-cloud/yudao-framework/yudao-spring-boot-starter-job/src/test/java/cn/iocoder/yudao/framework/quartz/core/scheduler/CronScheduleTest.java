package cn.iocoder.yudao.framework.quartz.core.scheduler;

import cn.iocoder.yudao.framework.quartz.config.YudaoAsyncAutoConfiguration;
import cn.iocoder.yudao.framework.quartz.config.YudaoXxlJobAutoConfiguration;
import com.xxl.job.core.executor.XxlJobExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CronScheduleTest {

    private static final LocalDateTime REFERENCE = LocalDateTime.of(2026, 10, 11, 22, 0);

    @ParameterizedTest
    @ValueSource(strings = {"0 * * * * ?", "0 15 10 ? JAN MON-FRI", "0 0 0 L * ?", "0 0 0 1W * ?",
            "0 0 0 ? * MON#2", "0 0 0 29 2 ?", "@daily", "@hourly"})
    void sixFieldsAndMacrosRetainSpringSemantics(String expression) {
        var expected = CronExpression.parse(expression);
        var actual = CronSchedule.parse(expression);
        LocalDateTime cursor = REFERENCE;
        for (int index = 0; index < 5; index++) {
            LocalDateTime next = expected.next(cursor);
            assertEquals(next, actual.next(cursor));
            if (next == null) break;
            cursor = next;
        }
        assertTrue(CronSchedule.isValidExpression(expression));
    }

    @Test
    void specificYearsRangesAndStepsMatchTriggerDates() {
        String annual = "0 0 0 1 1 ? ";
        assertEquals(List.of(at(2028), at(2030)), CronSchedule.parse(annual + "2028,2030").nextTimes(REFERENCE, 5));
        assertEquals(List.of(at(2028), at(2029), at(2030)), CronSchedule.parse(annual + "2028-2030").nextTimes(REFERENCE, 5));
        assertEquals(List.of(at(2028), at(2030), at(2032)), CronSchedule.parse(annual + "2028-2032/2").nextTimes(REFERENCE, 5));
        assertEquals(List.of(at(2027), at(2030), at(2033)), CronSchedule.parse(annual + "2027/3").nextTimes(REFERENCE, 3));
        assertEquals(List.of(at(2028), at(2030), at(2032)), CronSchedule.parse(annual + "*/2").nextTimes(REFERENCE, 3));
        assertEquals(at(2027), CronSchedule.parse(annual + "*").next(REFERENCE));
        assertEquals(at(2030), CronSchedule.parse(annual + "2030,2028,2030").next(at(2028)));
    }

    @Test
    void leapDaysExpiredYearsAndHorizonExhaustionHaveNoInventedDates() {
        var leap = CronSchedule.parse("0 0 0 29 2 ? 2027-2032/1");
        assertEquals(List.of(LocalDateTime.of(2028, 2, 29, 0, 0), LocalDateTime.of(2032, 2, 29, 0, 0)),
                leap.nextTimes(REFERENCE, 5));
        assertNull(CronSchedule.parse("0 0 0 29 2 ? 2027").next(REFERENCE));
        assertNull(CronSchedule.parse("0 * * * * ? 2025").next(REFERENCE));
        assertNull(CronSchedule.parse("* * * * * ? *").next(LocalDateTime.of(2099, 12, 31, 23, 59, 59)));
        assertEquals(at(1970), CronSchedule.parse("0 0 0 1 1 ? 1970").next(at(1900)));
        // 无年份的旧六字段不受新增的年份域限制。
        assertEquals(at(2101), CronSchedule.parse("0 0 0 1 1 ?").next(at(2100)));
    }

    @Test
    @Timeout(3)
    void frequentScheduleSkipsDecadesOfExcludedSeconds() {
        var schedule = CronSchedule.parse("* * * * * ? 2099");
        assertEquals(List.of(at(2099), at(2099).plusSeconds(1), at(2099).plusSeconds(2)),
                schedule.nextTimes(REFERENCE, 3));
        assertEquals(at(2099).plusSeconds(1), schedule.next(at(2099)));
    }

    @Test
    void validationAndPreviewBoundsRejectUnsupportedInputWithoutLeakingIt() {
        for (String invalid : List.of("", "0 0 0 1 1 ? 1969", "0 0 0 1 1 ? 2100", "0 0 0 1 1 ? 2028-2026",
                "0 0 0 1 1 ? 2027/0", "0 0 0 1 1 ? */131", "0 0 0 1 1 ? ?", "0 0 0 1 1 ? 2027,",
                "0 0 0 1 1 ? 2027//2", "0 0 0 1 1 ? 2027-", "0 0 0 1 1 ? 2027 2028", "@daily 2027",
                "0 0 0 1 1 ? fixture_private", "0 ".repeat(129))) {
            assertFalse(CronSchedule.isValidExpression(invalid));
            var failure = assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse(invalid));
            if (!invalid.isEmpty()) assertFalse(failure.toString().contains(invalid));
            assertNull(failure.getCause());
        }
        assertFalse(CronSchedule.isValidExpression(null));
        var schedule = CronSchedule.parse("0 * * * * ? 2026");
        assertThrows(IllegalArgumentException.class, () -> schedule.nextTimes(REFERENCE, 0));
        assertThrows(IllegalArgumentException.class, () -> schedule.nextTimes(REFERENCE, 101));
        assertThrows(UnsupportedOperationException.class, () -> schedule.nextTimes(REFERENCE, 5).clear());
        assertEquals("CronSchedule", schedule.toString());
    }

    @Test
    void scheduleLoadsWithExistingJobConfigurationWithoutStartingAnExecutor() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoXxlJobAutoConfiguration.class, YudaoAsyncAutoConfiguration.class))
                .withPropertyValues("xxl.job.enabled=false")
                .withBean(CronSchedule.class, () -> CronSchedule.parse("* * * * * ? 2099"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(at(2099), context.getBean(CronSchedule.class).next(REFERENCE));
                    assertTrue(context.getBeansOfType(XxlJobExecutor.class).isEmpty());
                });
    }

    private static LocalDateTime at(int year) { return LocalDateTime.of(year, 1, 1, 0, 0); }
}
