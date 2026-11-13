package cn.iocoder.yudao.module.dataforge.service.fill;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;

/**
 * 在线填报-单元格取值转换。
 *
 * <p>提交强校验、Excel 导入预检、取值范围比较与合并写入正式表共用这一处转换规则，
 * 保证“提交校验通过的值，合并时一定能转换成目标类型”。字段类型沿用 dbswitch 逻辑类型编号。</p>
 *
 * @author Li
 */
public final class FillValueConverter {

    static final int TYPE_DECIMAL = 1;
    static final int TYPE_DATE = 3;
    static final int TYPE_INTEGER = 5;
    static final int TYPE_LONG = 6;
    static final int TYPE_DATETIME = 9;
    static final int TYPE_TIME = 10;

    /** 日期：允许月、日不补零，例如 2026-1-5 */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-M-d")
            .withResolverStyle(ResolverStyle.STRICT);

    /** 时间：时:分[:秒[.小数秒]] */
    private static final DateTimeFormatter TIME_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("H:m")
            .optionalStart().appendPattern(":s")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true).optionalEnd()
            .optionalEnd()
            .toFormatter().withResolverStyle(ResolverStyle.STRICT);

    private FillValueConverter() {
    }

    /**
     * 类型问题
     *
     * @param ruleCode 规则编码
     * @param detail 问题说明，拼接在“第 N 行【字段名】”之后
     */
    public record TypeViolation(String ruleCode, String detail) {
    }

    /**
     * 把单元格值转换成写入正式表使用的 Java 值。
     *
     * @param fieldType 字段类型
     * @param value 草稿或快照中的值
     * @return 空值返回 null；小数 BigDecimal、整数 Integer、长整型 Long、日期 LocalDate、
     *         日期时间 LocalDateTime、时间 LocalTime，其余类型返回原文本
     * @throws IllegalArgumentException 值不符合字段类型
     */
    public static Object convert(int fieldType, Object value) {
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value);
        if (raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            return switch (fieldType) {
                case TYPE_DECIMAL -> new BigDecimal(text);
                case TYPE_INTEGER -> Integer.valueOf(text);
                case TYPE_LONG -> Long.valueOf(text);
                case TYPE_DATE -> parseDate(text);
                case TYPE_DATETIME -> parseDateTime(text);
                case TYPE_TIME -> LocalTime.parse(text, TIME_FORMAT);
                default -> raw;
            };
        } catch (NumberFormatException | DateTimeParseException ex) {
            throw new IllegalArgumentException(describe(fieldType), ex);
        }
    }

    /**
     * 检查非空文本是否符合字段类型。
     *
     * @param fieldType 字段类型
     * @param text 已去除首尾空白的非空文本
     * @return 不符合时返回问题，符合返回 null
     */
    public static TypeViolation check(int fieldType, String text) {
        try {
            convert(fieldType, text);
            return null;
        } catch (IllegalArgumentException ex) {
            return new TypeViolation(ruleCode(fieldType), ex.getMessage());
        }
    }

    /** 日期字段允许带时间部分（Excel 导入的日期单元格可能带时间），只取日期 */
    private static LocalDate parseDate(String text) {
        String normalized = text.replace('/', '-');
        int split = timeSeparatorIndex(normalized);
        if (split > 0) {
            LocalTime.parse(normalized.substring(split + 1).trim(), TIME_FORMAT);
            normalized = normalized.substring(0, split);
        }
        return LocalDate.parse(normalized, DATE_FORMAT);
    }

    /** 日期时间字段允许只填日期，按当天零点处理 */
    private static LocalDateTime parseDateTime(String text) {
        String normalized = text.replace('/', '-');
        int split = timeSeparatorIndex(normalized);
        if (split < 0) {
            return LocalDate.parse(normalized, DATE_FORMAT).atStartOfDay();
        }
        return LocalDate.parse(normalized.substring(0, split), DATE_FORMAT)
                .atTime(LocalTime.parse(normalized.substring(split + 1).trim(), TIME_FORMAT));
    }

    private static int timeSeparatorIndex(String text) {
        int space = text.indexOf(' ');
        return space >= 0 ? space : text.indexOf('T');
    }

    private static String ruleCode(int fieldType) {
        return switch (fieldType) {
            case TYPE_DECIMAL -> "TYPE_DECIMAL";
            case TYPE_INTEGER, TYPE_LONG -> "TYPE_INTEGER";
            case TYPE_DATE -> "TYPE_DATE";
            case TYPE_DATETIME -> "TYPE_DATETIME";
            case TYPE_TIME -> "TYPE_TIME";
            default -> "TYPE";
        };
    }

    private static String describe(int fieldType) {
        return switch (fieldType) {
            case TYPE_DECIMAL -> "必须为数值";
            case TYPE_INTEGER -> "必须为整数，且在 -2147483648 到 2147483647 之间";
            case TYPE_LONG -> "必须为整数";
            case TYPE_DATE -> "必须为日期，如 2026-01-01";
            case TYPE_DATETIME -> "必须为日期时间，如 2026-01-01 12:00:00";
            case TYPE_TIME -> "必须为时间，如 12:00:00";
            default -> "格式不正确";
        };
    }

}
