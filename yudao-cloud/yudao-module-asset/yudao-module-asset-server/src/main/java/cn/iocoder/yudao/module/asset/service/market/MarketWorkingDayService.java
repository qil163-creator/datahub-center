package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketWorkCalendarDO;
import cn.iocoder.yudao.module.asset.dal.mysql.market.MarketWorkCalendarMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 工作日计算：默认周六日休息；日历表可标记假日或调休上班。不是简单自然日加法。
 */
@Component
public class MarketWorkingDayService {

    public static final int KIND_HOLIDAY = 1;

    public static final int KIND_WORKDAY = 2;

    @Resource
    private MarketWorkCalendarMapper calendarMapper;

    /**
     * 按工作日历和例外配置推算增加指定工作日后的日期。
     */
    public LocalDateTime plusWorkingDays(LocalDateTime start, int workingDays) {
        if (start == null) {
            start = LocalDateTime.now();
        }
        if (workingDays <= 0) {
            return start;
        }
        Map<LocalDate, Integer> overrides = loadOverrides();
        LocalDate cursor = start.toLocalDate();
        int remain = workingDays;
        while (remain > 0) {
            cursor = cursor.plusDays(1);
            if (isWorkingDay(cursor, overrides)) {
                remain--;
            }
        }
        return LocalDateTime.of(cursor, LocalTime.of(23, 59, 59));
    }

    /**
     * 判断市场工作日是否工作日日期。
     */
    public boolean isWorkingDay(LocalDate day) {
        return isWorkingDay(day, loadOverrides());
    }

    /**
     * 判断市场工作日是否工作日日期。
     */
    private boolean isWorkingDay(LocalDate day, Map<LocalDate, Integer> overrides) {
        Integer kind = overrides.get(day);
        if (kind != null) {
            return kind == KIND_WORKDAY;
        }
        DayOfWeek week = day.getDayOfWeek();
        return week != DayOfWeek.SATURDAY && week != DayOfWeek.SUNDAY;
    }

    /**
     * 加载例外配置。
     */
    private Map<LocalDate, Integer> loadOverrides() {
        Map<LocalDate, Integer> map = new HashMap<>();
        try {
            for (MarketWorkCalendarDO item : calendarMapper.selectList()) {
                if (item.getCalendarDay() != null && item.getDayKind() != null) {
                    map.put(item.getCalendarDay(), item.getDayKind());
                }
            }
        } catch (RuntimeException ignored) {
            // 表尚未升级时退回周末规则
        }
        return map;
    }
}
