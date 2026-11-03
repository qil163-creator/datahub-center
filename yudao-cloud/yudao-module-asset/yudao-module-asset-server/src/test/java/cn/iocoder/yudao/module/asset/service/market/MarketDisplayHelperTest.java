package cn.iocoder.yudao.module.asset.service.market;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class MarketDisplayHelperTest {

    private final MarketDisplayHelper helper = new MarketDisplayHelper();

    @Test
    void businessNumberKeepsDateAndSequenceFormat() {
        LocalDate day = LocalDate.of(2026, 9, 28);
        // 本地日期仅用于编号展示；授权期限由 MarketAccessService 的数据库时钟判断。
        try (var clock = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDate::now).thenReturn(day);
            assertEquals("APP2026092800000012", helper.nextBizNo("APP", 12));
        }
    }

}
