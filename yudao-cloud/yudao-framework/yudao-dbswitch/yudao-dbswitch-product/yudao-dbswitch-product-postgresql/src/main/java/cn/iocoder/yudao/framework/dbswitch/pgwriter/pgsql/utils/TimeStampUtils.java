package cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.utils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * 时间戳转换工具类,将 Java 日期时间转换为 PostgreSQL 纪元(2000-01-01)起的微秒数或天数。
 */
public class TimeStampUtils {

    /**
     * 初始化 {@code TimeStampUtils}。
     */
    private TimeStampUtils() {
    }

    private static final LocalDateTime JavaEpoch = LocalDateTime.of(1970, 1, 1, 0, 0, 0);

    private static final LocalDateTime PostgresEpoch = LocalDateTime.of(2000, 1, 1, 0, 0, 0);

    private static final long DaysBetweenJavaAndPostgresEpochs = ChronoUnit.DAYS
            .between(JavaEpoch, PostgresEpoch);

    /**
     * 转换目标PostgreSQL时间标记。
     */
    public static long convertToPostgresTimeStamp(LocalDateTime localDateTime) {

        if (localDateTime == null) {
            throw new IllegalArgumentException("localDateTime");
        }
        // 提取一天内时间的纳秒数：
        long timeInNanoseconds = localDateTime
                .toLocalTime()
                .toNanoOfDay();

        // 将纳秒转换为微秒：
        long timeInMicroseconds = timeInNanoseconds / 1000;

        // 计算 Postgres 时间戳：
        if (localDateTime.isBefore(PostgresEpoch)) {
            long dateInMicroseconds =
                    (localDateTime.toLocalDate().toEpochDay() - DaysBetweenJavaAndPostgresEpochs)
                            * 86400000000L;

            return dateInMicroseconds + timeInMicroseconds;
        } else {
            long dateInMicroseconds =
                    (DaysBetweenJavaAndPostgresEpochs - localDateTime.toLocalDate().toEpochDay())
                            * 86400000000L;

            return -(dateInMicroseconds - timeInMicroseconds);
        }
    }

    /**
     * 将日期转换为相对 PostgreSQL 时间纪元的天数。
     */
    public static int toPgDays(LocalDate date) {
        // 调整时区偏移：
        LocalDateTime dateTime = date.atStartOfDay();
        // PG 时间零点为 2000-01-01 00:00:00：
        long secs = toPgSecs(getSecondsSinceJavaEpoch(dateTime));
        // 需要天数：
        return (int) TimeUnit.SECONDS.toDays(secs);
    }

    /**
     * 将日期时间转换为 PostgreSQL 时间纪元使用的秒或微秒表示。
     */
    public static Long toPgSecs(LocalDateTime dateTime) {
        // PG 时间零点为 2000-01-01 00:00:00：
        long secs = toPgSecs(getSecondsSinceJavaEpoch(dateTime));
        // 需要微秒数：
        return TimeUnit.SECONDS.toMicros(secs);
    }

    /**
     * 按 UTC 将本地日期时间转换为 Unix 纪元秒数。
     */
    private static long getSecondsSinceJavaEpoch(LocalDateTime localDateTime) {
        // 调整时区偏移：
        OffsetDateTime zdt = localDateTime.atOffset(ZoneOffset.UTC);
        // 获取 Unix 毫秒时间戳：
        long milliseconds = zdt.toInstant().toEpochMilli();
        // 转换为秒：
        return TimeUnit.MILLISECONDS.toSeconds(milliseconds);
    }

    /**
     * 将给定 Java 秒数转换为 PostgreSQL 秒数，适用于公元前 100 年
     * 及之后的年份。
     * <p>
     * 来自 /org/postgresql/jdbc2/TimestampUtils.java
     *
     * @param seconds PostgreSQL 秒数。
     * @return Java 秒数。
     */
    @SuppressWarnings("checkstyle:magicnumber")
    private static long toPgSecs(final long seconds) {
        long secs = seconds;
        // Java 纪元转换为 PostgreSQL 纪元
        secs -= 946684800L;

        // 儒略历和格里高利历的切换点
        if (secs < -13165977600L) { // 1582 年 10 月 15 日 -> 1582 年 10 月 4 日
            secs -= 86400 * 10;
            if (secs < -15773356800L) { // 1500-03-01 -> 1500-02-28
                int years = (int) ((secs + 15773356800L) / -3155823050L);
                years++;
                years -= years / 4;
                secs += years * 86400;
            }
        }

        return secs;
    }
}
