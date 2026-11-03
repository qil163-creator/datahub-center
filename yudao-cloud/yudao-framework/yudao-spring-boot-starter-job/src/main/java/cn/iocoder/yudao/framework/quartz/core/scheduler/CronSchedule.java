package cn.iocoder.yudao.framework.quartz.core.scheduler;

import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 不可变的 Cron 时间计划。秒至星期六字段及宏完全复用 Spring；可选第七字段
 * 只筛选 1970–2099 年，支持通配符、指定列表、范围及步长。
 * 此类不注册任务、不持有时钟、时区或租户上下文；调用方提供本地时间及执行语义。
 */
public final class CronSchedule {

    private static final int MIN_YEAR = 1970;
    private static final int MAX_YEAR = 2099;
    private static final int YEAR_COUNT = MAX_YEAR - MIN_YEAR + 1;
    private final CronExpression cron;
    private final NavigableSet<Integer> years;

    private CronSchedule(CronExpression cron, NavigableSet<Integer> years) {
        this.cron = cron;
        this.years = years == null ? null : Collections.unmodifiableNavigableSet(new TreeSet<>(years));
    }

    /** 输入最多 256 字符；失败消息不包含输入表达式或解析器原因。 */
    public static CronSchedule parse(String expression) {
        if (expression == null || expression.isBlank() || expression.length() > 256) throw invalid();
        try {
            String normalized = expression.strip();
            String[] fields = normalized.split("\\s+");
            if (fields.length == 6 || fields.length == 1 && normalized.startsWith("@")) {
                return new CronSchedule(CronExpression.parse(normalized), null);
            }
            if (fields.length != 7) throw invalid();
            String sixFields = String.join(" ", java.util.Arrays.copyOf(fields, 6));
            return new CronSchedule(CronExpression.parse(sixFields), parseYears(fields[6]));
        } catch (IllegalArgumentException failure) {
            throw invalid();
        }
    }

    public static boolean isValidExpression(String expression) {
        try {
            parse(expression);
            return true;
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    /** 返回严格晚于 after 的下一次运行时间；没有匹配的未来年份/日期时返回 null。 */
    public LocalDateTime next(LocalDateTime after) {
        Objects.requireNonNull(after, "Cron reference time is required");
        if (years == null) return cron.next(after);
        LocalDateTime cursor = after;
        // 每轮至少跳过一个不匹配年份；至多检查完整的有界年份域。
        for (int attempt = 0; attempt < YEAR_COUNT; attempt++) {
            Integer allowedYear = years.ceiling(Math.max(cursor.getYear(), MIN_YEAR));
            if (allowedYear == null) return null;
            if (allowedYear > cursor.getYear()) {
                cursor = LocalDateTime.of(allowedYear, 1, 1, 0, 0).minusNanos(1);
            }
            LocalDateTime candidate = cron.next(cursor);
            if (candidate == null) return null;
            if (years.contains(candidate.getYear())) return candidate;
            cursor = candidate;
        }
        return null;
    }

    /** 生成最多 100 次预览，不足 count 次时返回所有剩余匹配时间。 */
    public List<LocalDateTime> nextTimes(LocalDateTime after, int count) {
        if (count < 1 || count > 100) throw new IllegalArgumentException("Cron preview count must be between 1 and 100");
        Objects.requireNonNull(after, "Cron reference time is required");
        var result = new ArrayList<LocalDateTime>(count);
        LocalDateTime cursor = after;
        for (int index = 0; index < count; index++) {
            cursor = next(cursor);
            if (cursor == null) break;
            result.add(cursor);
        }
        return List.copyOf(result);
    }

    private static NavigableSet<Integer> parseYears(String field) {
        var result = new TreeSet<Integer>();
        for (String segment : field.split(",", -1)) {
            String[] stepped = segment.split("/", -1);
            if (stepped.length > 2 || stepped[0].isEmpty()) throw invalid();
            int step = stepped.length == 2 ? number(stepped[1]) : 1;
            if (step < 1 || step > YEAR_COUNT) throw invalid();
            int start;
            int end;
            if (stepped[0].equals("*")) {
                start = MIN_YEAR;
                end = MAX_YEAR;
            } else {
                String[] range = stepped[0].split("-", -1);
                if (range.length > 2) throw invalid();
                start = number(range[0]);
                end = range.length == 2 ? number(range[1]) : stepped.length == 2 ? MAX_YEAR : start;
            }
            if (start < MIN_YEAR || end > MAX_YEAR || start > end) throw invalid();
            for (int year = start; year <= end; year += step) result.add(year);
        }
        if (result.isEmpty()) throw invalid();
        return result;
    }

    private static int number(String value) {
        if (!value.matches("[0-9]{1,4}")) throw invalid();
        return Integer.parseInt(value);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Cron schedule is invalid or exceeds its supported bounds");
    }

    @Override public String toString() { return "CronSchedule"; }
}
