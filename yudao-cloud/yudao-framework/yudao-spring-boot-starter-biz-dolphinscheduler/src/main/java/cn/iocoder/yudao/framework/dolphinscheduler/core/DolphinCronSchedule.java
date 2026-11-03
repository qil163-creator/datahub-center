package cn.iocoder.yudao.framework.dolphinscheduler.core;

import org.quartz.CronExpression;

import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * DolphinScheduler 使用的不可变 Quartz Cron 计划，星期 1 为周日、7 为周六。
 * 只负责校验和预览，不创建调度器、不持有时钟或租户上下文；时区由调用方明确提供。
 */
public final class DolphinCronSchedule {

    private final String expression;

    /**
     * 初始化 {@code DolphinCronSchedule}。
     */
    private DolphinCronSchedule(String expression) {
        this.expression = expression;
    }

    /** 支持 Quartz 六或七字段，输入最多 256 字符；不接受 Spring 宏表达式。 */
    public static DolphinCronSchedule parse(String expression) {
        if (expression == null || expression.isBlank() || expression.length() > 256) {
            throw invalid();
        }
        String normalized = expression.strip();
        int fields = normalized.split("\\s+").length;
        if (fields != 6 && fields != 7) {
            throw invalid();
        }
        try {
            new CronExpression(normalized);
            return new DolphinCronSchedule(normalized);
        } catch (ParseException | IllegalArgumentException failure) {
            throw invalid();
        }
    }

    /**
     * 判断有效表达式。
     */
    public static boolean isValidExpression(String expression) {
        try {
            parse(expression);
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    /** 下一次运行对应的时刻严格晚于 after 对应时刻；没有未来匹配时间时返回 null。 */
    public LocalDateTime next(LocalDateTime after, ZoneId zone) {
        List<LocalDateTime> times = nextTimes(after, zone, 1);
        return times.isEmpty() ? null : times.getFirst();
    }

    /**
     * 生成 1–100 次预览；夏令时跳变和重叠遵循 Quartz 与指定时区的规则。
     * 本地参考时间位于夏令时空缺时拒绝输入，重叠时使用较早偏移量；返回值不包含偏移量。
     */
    public List<LocalDateTime> nextTimes(LocalDateTime after, ZoneId zone, int count) {
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException("Cron preview count must be between 1 and 100");
        }
        if (after == null || after.getYear() < 1970 || after.getYear() > 9999 || zone == null
                || zone.getRules().getValidOffsets(after).isEmpty()) {
            throw new IllegalArgumentException("Cron reference time or timezone is invalid");
        }
        TimeZone timeZone = TimeZone.getTimeZone(zone);
        if (!timeZone.toZoneId().getRules().equals(zone.getRules())) {
            throw new IllegalArgumentException("Cron timezone is unsupported");
        }
        CronExpression cron;
        try {
            // 每次预览创建私有解析器，避免 Quartz 的可变时区在并发调用间串扰。
            cron = new CronExpression(expression);
        } catch (ParseException failure) {
            throw invalid();
        }
        cron.setTimeZone(timeZone);
        Date cursor = Date.from(after.atZone(zone).toInstant());
        var result = new ArrayList<LocalDateTime>(count);
        for (int index = 0; index < count; index++) {
            cursor = cron.getNextValidTimeAfter(cursor);
            if (cursor == null) {
                break;
            }
            result.add(LocalDateTime.ofInstant(cursor.toInstant(), zone));
        }
        return List.copyOf(result);
    }

    /**
     * 构造表示当前输入、状态或操作不符合已声明约定的异常。
     */
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("DolphinScheduler Cron expression is invalid or exceeds supported bounds");
    }

    /**
     * 返回当前对象的文本表示。
     */
    @Override
    public String toString() {
        return "DolphinCronSchedule";
    }
}
