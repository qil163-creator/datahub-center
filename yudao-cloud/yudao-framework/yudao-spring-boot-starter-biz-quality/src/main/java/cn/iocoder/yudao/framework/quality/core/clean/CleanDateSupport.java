package cn.iocoder.yudao.framework.quality.core.clean;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.time.chrono.IsoEra;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 清洗日期的严格解析：整段匹配且验证日历，不把无效日期自动滚动到下个月。 */
public final class CleanDateSupport {

    private static final List<String> DEFAULT_FORMATS = List.of("yyyy-MM-dd HH:mm:ss.SSS",
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd", "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd",
            "yyyyMMdd", "yyyy.MM.dd");

    /**
     * 初始化 {@code CleanDateSupport}。
     */
    private CleanDateSupport() {
    }

    /**
     * 根据当前输入构建并返回 {@code DateTimeFormatterBuilder}。
     */
    public static DateTimeFormatter formatter(String pattern) {
        return new DateTimeFormatterBuilder().appendPattern(pattern)
                .parseDefaulting(ChronoField.ERA, IsoEra.CE.getValue())
                .toFormatter(Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    }

    /** 失败返回空，由清洗策略保留原始输入；显式格式不匹配时不会回退到宽松解析。 */
    public static ZonedDateTime parse(Object value, List<String> formats) {
        ZoneId zone = ZoneId.systemDefault();
        if (value instanceof ZonedDateTime date) {
            return date;
        }
        if (value instanceof OffsetDateTime date) {
            return date.toZonedDateTime();
        }
        if (value instanceof LocalDateTime date) {
            return date.atZone(zone);
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay(zone);
        }
        if (value instanceof Instant instant) {
            return instant.atZone(zone);
        }
        if (value instanceof Timestamp date) {
            return date.toLocalDateTime().atZone(zone);
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().atStartOfDay(zone);
        }
        if (value instanceof java.sql.Time) {
            return null;
        }
        if (value instanceof Date date) {
            return date.toInstant().atZone(zone);
        }
        if (value instanceof Number number) {
            try {
                BigDecimal epoch = new BigDecimal(number.toString());
                if (epoch.compareTo(new BigDecimal("1000000000000")) >= 0) {
                    return Instant.ofEpochMilli(epoch.longValueExact()).atZone(zone);
                }
                if (epoch.compareTo(new BigDecimal("1000000000")) >= 0) {
                    return Instant.ofEpochSecond(epoch.longValueExact()).atZone(zone);
                }
            } catch (RuntimeException invalid) {
                return null;
            }
            return null;
        }
        if (!(value instanceof CharSequence)) {
            return null;
        }
        String text = value.toString().trim();
        for (String pattern : formats.isEmpty() ? DEFAULT_FORMATS : formats) {
            try {
                if ("timestamp".equalsIgnoreCase(pattern)) {
                    if (text.matches("[0-9]{13}")) {
                        return Instant.ofEpochMilli(Long.parseLong(text)).atZone(zone);
                    }
                    if (text.matches("[0-9]{10}")) {
                        return Instant.ofEpochSecond(Long.parseLong(text)).atZone(zone);
                    }
                    continue;
                }
                TemporalAccessor parsed = formatter(pattern).parseBest(text, ZonedDateTime::from,
                        OffsetDateTime::from, LocalDateTime::from, LocalDate::from);
                if (parsed instanceof ZonedDateTime date) {
                    return date;
                }
                if (parsed instanceof OffsetDateTime date) {
                    return date.toZonedDateTime();
                }
                if (parsed instanceof LocalDateTime date) {
                    return date.atZone(zone);
                }
                return LocalDate.from(parsed).atStartOfDay(zone);
            } catch (RuntimeException invalid) {
                // 尝试下一个显式输入格式，全部失败后保留原值。
            }
        }
        return null;
    }
}
