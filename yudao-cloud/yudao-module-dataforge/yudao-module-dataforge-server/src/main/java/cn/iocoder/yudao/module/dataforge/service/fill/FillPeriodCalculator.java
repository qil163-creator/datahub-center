package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillPeriodTypeEnum;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;

/**
 * 在线填报-周期计算器
 *
 * 职责：periodKey 生成、所属期起止推导、生成时点推进、截止时间推导。
 * periodKey 规范：日 2026-08-31 / 周 2026-W35（ISO 周）/ 月 2026-08 / 季 2026-Q3 / 一次性 ONCE-{planId}
 *
 * @author Li
 */
public final class FillPeriodCalculator {

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final LocalTime DEFAULT_GENERATE_TIME = LocalTime.of(8, 0);

    private static final LocalTime DEFAULT_DEADLINE_TIME = LocalTime.of(17, 30);

    private static final int DEFAULT_PERIOD_OFFSET = -1;

    private static final int DEFAULT_DEADLINE_OFFSET_DAYS = 3;

    /**
     * 限制填报所属期计算器工具的实例化，统一通过静态能力使用。
     */
    private FillPeriodCalculator() {
    }

    /** 一个周期任务的完整期次信息 */
    public record PeriodInfo(String periodKey, LocalDateTime periodStart, LocalDateTime periodEnd,
                             LocalDateTime openTime, LocalDateTime deadlineTime) {

    }

    /**
     * 解析填报所属期计算器的配置。
     *
     * @param periodConfigJson 所属期配置JSON
     * @return 填报所属期配置
     */
    public static FillPeriodConfig parseConfig(String periodConfigJson) {
        FillPeriodConfig config = StrUtil.isBlank(periodConfigJson)
                ? null : JsonUtils.parseObject(periodConfigJson, FillPeriodConfig.class);
        return config != null ? config : new FillPeriodConfig();
    }

    /**
     * 计算首次生成时点：启用计划时调用，返回 >= from 的最近一次生成时刻；一次性计划返回 null（发布即生成）
     */
    public static LocalDateTime computeFirstGenerateTime(Integer periodType, FillPeriodConfig config,
            LocalDateTime from) {
        if (FillPeriodTypeEnum.ONCE.getType().equals(periodType)) {
            return null;
        }
        LocalTime generateTime = parseTime(config.getGenerateTime(), DEFAULT_GENERATE_TIME);
        LocalDateTime candidate = switch (FillPeriodTypeEnum.values()[periodType - 1]) {
            case DAILY -> from.toLocalDate().atTime(generateTime);
            case WEEKLY -> {
                DayOfWeek day = DayOfWeek.of(defaultIfNull(config.getWeekGenerateDay(), 1));
                yield from.toLocalDate().with(TemporalAdjusters.nextOrSame(day)).atTime(generateTime);
            }
            case MONTHLY -> {
                int day = defaultIfNull(config.getMonthGenerateDay(), 1);
                yield from.toLocalDate().withDayOfMonth(Math.min(day, 28)).atTime(generateTime);
            }
            case QUARTERLY -> {
                int day = defaultIfNull(config.getQuarterGenerateDay(), 1);
                LocalDate quarterFirst = from.toLocalDate()
                        .with(from.toLocalDate().getMonth().firstMonthOfQuarter())
                        .withDayOfMonth(Math.min(day, 28));
                yield quarterFirst.atTime(generateTime);
            }
            default -> null;
        };
        if (candidate == null) {
            return null;
        }
        // 候选时点已过则推进一个周期
        return candidate.isBefore(from)
                ? computeNextGenerateTime(periodType, config, candidate) : candidate;
    }

    /**
     * 由本次生成时点推进到下一次生成时点
     */
    public static LocalDateTime computeNextGenerateTime(Integer periodType, FillPeriodConfig config,
            LocalDateTime current) {
        LocalTime generateTime = parseTime(config.getGenerateTime(), DEFAULT_GENERATE_TIME);
        return switch (FillPeriodTypeEnum.values()[periodType - 1]) {
            case ONCE -> null;
            case DAILY -> current.toLocalDate().plusDays(1).atTime(generateTime);
            case WEEKLY -> current.toLocalDate().plusWeeks(1).atTime(generateTime);
            case MONTHLY -> {
                int day = Math.min(defaultIfNull(config.getMonthGenerateDay(), 1), 28);
                yield current.toLocalDate().plusMonths(1).withDayOfMonth(day).atTime(generateTime);
            }
            case QUARTERLY -> {
                int day = Math.min(defaultIfNull(config.getQuarterGenerateDay(), 1), 28);
                LocalDate nextQuarterFirst = current.toLocalDate().plusMonths(3);
                nextQuarterFirst = nextQuarterFirst.with(nextQuarterFirst.getMonth().firstMonthOfQuarter())
                        .withDayOfMonth(day);
                yield nextQuarterFirst.atTime(generateTime);
            }
        };
    }

    /**
     * 按生成时点推导期次信息（补生成历史期次时传入历史生成时点即可）
     *
     * @param planId 一次性任务的 periodKey 需要计划编号
     */
    public static PeriodInfo buildPeriod(Long planId, Integer periodType, FillPeriodConfig config,
            LocalDateTime generateTime) {
        LocalTime deadlineTime = parseTime(config.getDeadlineTime(), DEFAULT_DEADLINE_TIME);
        int deadlineOffsetDays = defaultIfNull(config.getDeadlineOffsetDays(), DEFAULT_DEADLINE_OFFSET_DAYS);
        LocalDateTime deadline = generateTime.toLocalDate().plusDays(deadlineOffsetDays).atTime(deadlineTime);
        int offset = defaultIfNull(config.getPeriodOffset(), DEFAULT_PERIOD_OFFSET);

        return switch (FillPeriodTypeEnum.values()[periodType - 1]) {
            case ONCE -> {
                LocalDateTime openTime = StrUtil.isBlank(config.getOnceOpenTime())
                        ? generateTime : LocalDateTime.parse(config.getOnceOpenTime());
                LocalDateTime onceDeadline = StrUtil.isBlank(config.getOnceDeadlineTime())
                        ? generateTime.toLocalDate().plusDays(7).atTime(DEFAULT_DEADLINE_TIME)
                        : LocalDateTime.parse(config.getOnceDeadlineTime());
                yield new PeriodInfo("ONCE-" + planId, openTime, onceDeadline, openTime, onceDeadline);
            }
            case DAILY -> {
                LocalDate date = generateTime.toLocalDate().plusDays(offset);
                yield new PeriodInfo(DAY_FORMATTER.format(date),
                        date.atStartOfDay(), date.atTime(LocalTime.MAX),
                        generateTime, deadline);
            }
            case WEEKLY -> {
                LocalDate anchor = generateTime.toLocalDate().plusWeeks(offset);
                LocalDate monday = anchor.with(DayOfWeek.MONDAY);
                String key = String.format("%d-W%02d",
                        anchor.get(IsoFields.WEEK_BASED_YEAR),
                        anchor.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
                yield new PeriodInfo(key, monday.atStartOfDay(),
                        monday.plusDays(6).atTime(LocalTime.MAX), generateTime, deadline);
            }
            case MONTHLY -> {
                LocalDate anchor = generateTime.toLocalDate().plusMonths(offset);
                LocalDate first = anchor.with(TemporalAdjusters.firstDayOfMonth());
                yield new PeriodInfo(MONTH_FORMATTER.format(first), first.atStartOfDay(),
                        first.with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX),
                        generateTime, deadline);
            }
            case QUARTERLY -> {
                LocalDate anchor = generateTime.toLocalDate().plusMonths(3L * offset);
                LocalDate quarterFirst = anchor.with(anchor.getMonth().firstMonthOfQuarter())
                        .with(TemporalAdjusters.firstDayOfMonth());
                String key = quarterFirst.getYear() + "-Q" + quarterFirst.get(IsoFields.QUARTER_OF_YEAR);
                yield new PeriodInfo(key, quarterFirst.atStartOfDay(),
                        quarterFirst.plusMonths(2).with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX),
                        generateTime, deadline);
            }
        };
    }

    /**
     * 解析时间。
     *
     * @param text 待处理文本
     * @param defaultValue 默认值
     * @return 本地时间
     */
    private static LocalTime parseTime(String text, LocalTime defaultValue) {
        if (StrUtil.isBlank(text)) {
            return defaultValue;
        }
        try {
            return LocalTime.parse(text);
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    /**
     * 为空值提供指定的默认值。
     *
     * @param value 待处理的值
     * @param defaultValue 默认值
     * @return 默认条件空值数值
     */
    private static int defaultIfNull(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }

}
