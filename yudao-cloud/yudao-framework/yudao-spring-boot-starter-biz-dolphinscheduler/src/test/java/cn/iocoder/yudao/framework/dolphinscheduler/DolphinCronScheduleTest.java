package cn.iocoder.yudao.framework.dolphinscheduler;

import cn.iocoder.yudao.framework.dolphinscheduler.config.YudaoDolphinSchedulerAutoConfiguration;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinCronSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quartz.Scheduler;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DolphinCronScheduleTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void numericWeekdaysFollowQuartzSundayOneAndSaturdaySeven() {
        LocalDateTime reference = LocalDateTime.of(2026, 10, 10, 12, 0);
        assertThat(DolphinCronSchedule.parse("0 0 8 ? * 1").next(reference, SHANGHAI))
                .isEqualTo(LocalDateTime.of(2026, 10, 11, 8, 0));
        assertThat(DolphinCronSchedule.parse("0 0 8 ? * 2").next(reference, SHANGHAI))
                .isEqualTo(LocalDateTime.of(2026, 10, 12, 8, 0));
        assertThat(DolphinCronSchedule.parse("0 0 8 ? * 7").next(reference, SHANGHAI))
                .isEqualTo(LocalDateTime.of(2026, 10, 17, 8, 0));
        assertThat(DolphinCronSchedule.parse("0 0 8 ? * SUN").next(reference, SHANGHAI))
                .isEqualTo(DolphinCronSchedule.parse("0 0 8 ? * 1").next(reference, SHANGHAI));
    }

    @Test
    void sixAndSevenFieldsUseTheSameCurrentYearDates() {
        LocalDateTime reference = LocalDateTime.of(2026, 10, 14, 7, 59, 59);
        assertThat(DolphinCronSchedule.parse("0 0 8 * * ?").nextTimes(reference, SHANGHAI, 2))
                .containsExactly(LocalDateTime.of(2026, 10, 14, 8, 0),
                        LocalDateTime.of(2026, 10, 15, 8, 0));
        assertThat(DolphinCronSchedule.parse("0 0 8 * * ? 2026").nextTimes(reference, SHANGHAI, 2))
                .containsExactly(LocalDateTime.of(2026, 10, 14, 8, 0),
                        LocalDateTime.of(2026, 10, 15, 8, 0));
    }

    @Test
    void explicitYearsStopAtTheirEndAndCanSpanNewYear() {
        LocalDateTime reference = LocalDateTime.of(2026, 12, 30, 0, 0);
        assertThat(DolphinCronSchedule.parse("0 0 0 * * ? 2026").nextTimes(reference, SHANGHAI, 3))
                .containsExactly(LocalDateTime.of(2026, 12, 31, 0, 0));
        assertThat(DolphinCronSchedule.parse("0 0 0 * * ? 2026,2027").nextTimes(reference, SHANGHAI, 3))
                .containsExactly(LocalDateTime.of(2026, 12, 31, 0, 0),
                        LocalDateTime.of(2027, 1, 1, 0, 0), LocalDateTime.of(2027, 1, 2, 0, 0));
        assertThat(DolphinCronSchedule.parse("0 0 0 * * ? 2025").next(reference, SHANGHAI)).isNull();
    }

    @Test
    void lastDayLastWeekdayAndNthWeekdayDelegateToQuartz() {
        LocalDateTime reference = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertThat(DolphinCronSchedule.parse("0 0 8 L * ?").next(reference, SHANGHAI))
                .isEqualTo(LocalDateTime.of(2026, 1, 31, 8, 0));
        assertThat(DolphinCronSchedule.parse("0 0 8 ? * 6L").next(reference, SHANGHAI))
                .isEqualTo(LocalDateTime.of(2026, 1, 30, 8, 0));
        assertThat(DolphinCronSchedule.parse("0 0 8 ? * 2#2").next(reference, SHANGHAI))
                .isEqualTo(LocalDateTime.of(2026, 1, 12, 8, 0));
        assertThat(DolphinCronSchedule.parse("0 0 8 LW * ?").next(reference, SHANGHAI))
                .isEqualTo(LocalDateTime.of(2026, 1, 30, 8, 0));
    }

    @Test
    void dstGapSkipsMissingWallTimeAndRejectsMissingReferenceTime() {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        DolphinCronSchedule schedule = DolphinCronSchedule.parse("0 30 2 * * ? 2026");
        assertThat(schedule.nextTimes(LocalDateTime.of(2026, 3, 28, 2, 30), berlin, 2))
                .containsExactly(LocalDateTime.of(2026, 3, 30, 2, 30),
                        LocalDateTime.of(2026, 3, 31, 2, 30));
        assertThatThrownBy(() -> schedule.next(LocalDateTime.of(2026, 3, 29, 2, 15), berlin))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dstOverlapDoesNotInventExtraTriggers() {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        assertThat(DolphinCronSchedule.parse("0 30 2 * * ? 2026")
                .nextTimes(LocalDateTime.of(2026, 10, 25, 1, 59), berlin, 3))
                .containsExactly(LocalDateTime.of(2026, 10, 25, 2, 30),
                        LocalDateTime.of(2026, 10, 26, 2, 30), LocalDateTime.of(2026, 10, 27, 2, 30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "@daily", "@yearly", "0 0 * * *", "0 0 0 ? * 0",
            "0 0 0 ? * 8", "60 0 0 * * ?", "0 0 24 * * ?", "0 0 0 * * *",
            "0 0 0 ? * ?", "0 0 0 * * ? 2026 extra", "0 0 0 ? * MON#6"})
    void rejectsSpringMacrosInvalidFieldsAndWeekdays(String expression) {
        assertThat(DolphinCronSchedule.isValidExpression(expression)).isFalse();
        assertThatThrownBy(() -> DolphinCronSchedule.parse(expression))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DolphinScheduler Cron expression is invalid or exceeds supported bounds");
    }

    @Test
    void inputAndPreviewBoundsAreEnforcedWithoutEchoingInput() {
        assertThat(DolphinCronSchedule.isValidExpression(null)).isFalse();
        String excessive = "0 0 0 * * ?" + " ".repeat(257);
        assertThatThrownBy(() -> DolphinCronSchedule.parse(excessive))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(excessive);
        DolphinCronSchedule schedule = DolphinCronSchedule.parse("0 0 0 * * ?");
        LocalDateTime reference = LocalDateTime.of(2026, 10, 14, 0, 0);
        for (int count : new int[]{-1, 0, 101}) {
            assertThatThrownBy(() -> schedule.nextTimes(reference, SHANGHAI, count))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> schedule.next(null, SHANGHAI)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule.next(reference, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule.next(reference.withYear(1969), SHANGHAI))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule.next(reference.withYear(10000), SHANGHAI))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> schedule.nextTimes(reference, SHANGHAI, 2).clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(schedule.toString()).isEqualTo("DolphinCronSchedule");
    }

    @Test
    void explicitOffsetZonesWorkWithoutSilentTimezoneFallback() {
        DolphinCronSchedule schedule = DolphinCronSchedule.parse("0 0 8 * * ?");
        LocalDateTime reference = LocalDateTime.of(2026, 10, 14, 7, 0);
        assertThat(schedule.next(reference, ZoneId.of("UTC+05:45")))
                .isEqualTo(LocalDateTime.of(2026, 10, 14, 8, 0));
        assertThat(schedule.next(reference, ZoneId.of("UTC+05:30:45")))
                .isEqualTo(LocalDateTime.of(2026, 10, 14, 8, 0));
    }

    @Test
    void subSecondReferenceAndTimezoneIsolationRemainStrict() throws Exception {
        DolphinCronSchedule schedule = DolphinCronSchedule.parse("0/5 * * * * ?");
        assertThat(schedule.next(LocalDateTime.of(2026, 10, 14, 0, 0, 5, 999_000_000), SHANGHAI))
                .isEqualTo(LocalDateTime.of(2026, 10, 14, 0, 0, 10));
        DolphinCronSchedule leapSchedule = DolphinCronSchedule.parse("0 30 2 * * ? 2026");
        LocalDateTime reference = LocalDateTime.of(2026, 3, 28, 2, 30);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var work = IntStream.range(0, 20).mapToObj(index -> (Callable<LocalDateTime>) () ->
                    leapSchedule.next(reference, index % 2 == 0 ? SHANGHAI : ZoneId.of("Europe/Berlin"))).toList();
            var results = executor.invokeAll(work);
            for (int index = 0; index < results.size(); index++) {
                assertThat(results.get(index).get()).isEqualTo(index % 2 == 0
                        ? LocalDateTime.of(2026, 3, 29, 2, 30) : LocalDateTime.of(2026, 3, 30, 2, 30));
            }
        }
    }

    @Test
    void clientAutoConfigurationDoesNotRegisterLocalQuartzScheduler() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(YudaoDolphinSchedulerAutoConfiguration.class))
                .withPropertyValues("yudao.dolphinscheduler.server-url=http://ds.test:12345/dolphinscheduler")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DolphinSchedulerClient.class);
                    assertThat(context).doesNotHaveBean(Scheduler.class);
                    assertThat(context).doesNotHaveBean(DolphinCronSchedule.class);
                    assertThat(DolphinCronSchedule.isValidExpression("0 0 8 * * ?")).isTrue();
                });
    }
}
