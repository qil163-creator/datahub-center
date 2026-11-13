package cn.iocoder.yudao.module.dataforge.service.fill;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 单元格取值转换测试：提交校验与合并写入共用同一套规则
 */
class FillValueConverterTest {

    @Test
    void blankValuesBecomeNull() {
        assertNull(FillValueConverter.convert(FillValueConverter.TYPE_DATE, null));
        assertNull(FillValueConverter.convert(FillValueConverter.TYPE_INTEGER, "  "));
    }

    @Test
    void numbersFollowTargetColumnRange() {
        assertEquals(new BigDecimal("12.50"), FillValueConverter.convert(FillValueConverter.TYPE_DECIMAL, " 12.50 "));
        assertEquals(42, FillValueConverter.convert(FillValueConverter.TYPE_INTEGER, "42"));
        assertEquals(3_000_000_000L, FillValueConverter.convert(FillValueConverter.TYPE_LONG, "3000000000"));
        // 整数列是 32 位：超出范围的值过去能通过提交校验，却在合并时失败
        assertNotNull(FillValueConverter.check(FillValueConverter.TYPE_INTEGER, "3000000000"));
        assertNotNull(FillValueConverter.check(FillValueConverter.TYPE_INTEGER, "1.5"));
        assertNotNull(FillValueConverter.check(FillValueConverter.TYPE_DECIMAL, "abc"));
    }

    @Test
    void datesAcceptCommonSpellings() {
        LocalDate expected = LocalDate.of(2026, 1, 5);
        assertEquals(expected, FillValueConverter.convert(FillValueConverter.TYPE_DATE, "2026-01-05"));
        assertEquals(expected, FillValueConverter.convert(FillValueConverter.TYPE_DATE, "2026/1/5"));
        // Excel 导入的日期单元格可能带时间
        assertEquals(expected, FillValueConverter.convert(FillValueConverter.TYPE_DATE, "2026-01-05 10:30:00"));
    }

    @Test
    void dateTimesAndTimesAcceptCommonSpellings() {
        assertEquals(LocalDateTime.of(2026, 1, 5, 12, 30, 15),
                FillValueConverter.convert(FillValueConverter.TYPE_DATETIME, "2026-01-05 12:30:15"));
        assertEquals(LocalDateTime.of(2026, 1, 5, 12, 30),
                FillValueConverter.convert(FillValueConverter.TYPE_DATETIME, "2026-01-05T12:30"));
        assertEquals(LocalDateTime.of(2026, 1, 5, 0, 0),
                FillValueConverter.convert(FillValueConverter.TYPE_DATETIME, "2026-01-05"));
        assertEquals(LocalTime.of(8, 5), FillValueConverter.convert(FillValueConverter.TYPE_TIME, "08:05"));
        assertEquals(LocalTime.of(8, 5, 9), FillValueConverter.convert(FillValueConverter.TYPE_TIME, "8:05:09"));
    }

    @Test
    void invalidTemporalValuesAreRejectedWithRuleCode() {
        assertEquals("TYPE_DATE", FillValueConverter.check(FillValueConverter.TYPE_DATE, "2026-02-30").ruleCode());
        assertEquals("TYPE_DATE", FillValueConverter.check(FillValueConverter.TYPE_DATE, "一月五日").ruleCode());
        assertEquals("TYPE_DATETIME",
                FillValueConverter.check(FillValueConverter.TYPE_DATETIME, "2026-01-05 25:00:00").ruleCode());
        assertEquals("TYPE_TIME", FillValueConverter.check(FillValueConverter.TYPE_TIME, "noon").ruleCode());
        assertThrows(IllegalArgumentException.class,
                () -> FillValueConverter.convert(FillValueConverter.TYPE_DATE, "20260105"));
    }

    @Test
    void stringsPassThroughUnchanged() {
        assertEquals(" 原文 ", FillValueConverter.convert(2, " 原文 "));
        assertNull(FillValueConverter.check(2, "任意文本"));
    }

    @Test
    void rangeRulesUseTheSameTemporalParsing() {
        // 取值范围比较与类型校验对同一写法的结论一致
        assertEquals(LocalDate.of(2026, 1, 5), FillFieldRuleValidator.parseBound(FillValueConverter.TYPE_DATE, "2026/1/5"));
        assertNull(FillFieldRuleValidator.parseBound(FillValueConverter.TYPE_DATE, "2026-13-01"));
    }

}
