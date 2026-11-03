package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketWorkCalendarDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketWorkCalendarMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketWorkingDayServiceTest {

    private final MarketWorkingDayService service = new MarketWorkingDayService();
    private final MarketWorkCalendarMapper calendarMapper = mock(MarketWorkCalendarMapper.class);

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "calendarMapper", calendarMapper);
        when(calendarMapper.selectList()).thenReturn(List.of());
    }

    @Test
    void plusWorkingDaysSkipsWeekend() {
        LocalDateTime friday = LocalDateTime.of(2026, 9, 18, 9, 0);
        LocalDateTime deadline = service.plusWorkingDays(friday, 1);
        assertEquals(LocalDate.of(2026, 9, 21), deadline.toLocalDate());
    }

    @Test
    void calendarHolidayAndCompensatoryWorkday() {
        MarketWorkCalendarDO holiday = new MarketWorkCalendarDO();
        holiday.setCalendarDay(LocalDate.of(2026, 9, 21));
        holiday.setDayKind(MarketWorkingDayService.KIND_HOLIDAY);
        MarketWorkCalendarDO work = new MarketWorkCalendarDO();
        work.setCalendarDay(LocalDate.of(2026, 9, 19));
        work.setDayKind(MarketWorkingDayService.KIND_WORKDAY);
        when(calendarMapper.selectList()).thenReturn(List.of(holiday, work));
        assertTrue(service.isWorkingDay(LocalDate.of(2026, 9, 19)));
        assertFalse(service.isWorkingDay(LocalDate.of(2026, 9, 21)));
        LocalDateTime deadline = service.plusWorkingDays(LocalDateTime.of(2026, 9, 18, 9, 0), 1);
        assertEquals(LocalDate.of(2026, 9, 19), deadline.toLocalDate());
    }

}
