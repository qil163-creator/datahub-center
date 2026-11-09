package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevBackfillItemDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DEV_BACKFILL_RANGE_INVALID;
import static org.junit.jupiter.api.Assertions.*;

/** 补数据日期区间展开与运行实例终态映射规则。 */
class DevBackfillRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    @Test
    void expandsInclusiveRangeInRequestedOrder() {
        assertEquals(List.of(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1)),
                DevBackfillServiceImpl.expandDates(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 1), "ASC", TODAY));
        assertEquals(List.of(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 30)),
                DevBackfillServiceImpl.expandDates(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 1), "DESC", TODAY));
        assertEquals(List.of(TODAY), DevBackfillServiceImpl.expandDates(TODAY, TODAY, "ASC", TODAY));
        assertEquals(366, DevBackfillServiceImpl.expandDates(TODAY.minusDays(365), TODAY, "ASC", TODAY).size());
    }

    @Test
    void rejectsReversedFutureAndOversizedRanges() {
        assertRange(() -> DevBackfillServiceImpl.expandDates(TODAY, TODAY.minusDays(1), "ASC", TODAY));
        assertRange(() -> DevBackfillServiceImpl.expandDates(TODAY, TODAY.plusDays(1), "ASC", TODAY));
        assertRange(() -> DevBackfillServiceImpl.expandDates(TODAY.minusDays(366), TODAY, "ASC", TODAY));
        assertRange(() -> DevBackfillServiceImpl.expandDates(null, TODAY, "ASC", TODAY));
    }

    @Test
    void runKeyIsStablePerBatchAndDate() {
        assertEquals("BACKFILL:12:20261009", DevBackfillServiceImpl.runKey(12L, LocalDate.of(2026, 10, 9)));
    }

    @Test
    void recordOutcomesRequireQualityAcceptanceAndDistinguishCancellation() {
        assertNull(DevBackfillServiceImpl.outcome(record("RUNNING", "PENDING"), false));
        assertNull(DevBackfillServiceImpl.outcome(record("WAITING", "NOT_CONFIGURED"), false));
        assertNull(DevBackfillServiceImpl.outcome(record("SUCCESS", "PENDING"), false));
        assertNull(DevBackfillServiceImpl.outcome(record("SUCCESS", "UNKNOWN"), false));
        assertEquals(DevBackfillItemDO.STATUS_SUCCESS,
                DevBackfillServiceImpl.outcome(record("SUCCESS", "PASSED"), false).status());
        assertEquals(DevBackfillItemDO.STATUS_SUCCESS,
                DevBackfillServiceImpl.outcome(record("SUCCESS", "NOT_CONFIGURED"), false).status());
        assertEquals(DevBackfillItemDO.STATUS_FAILED,
                DevBackfillServiceImpl.outcome(record("SUCCESS", "FAILED"), false).status());
        var failed = record("FAILED", "PENDING");
        failed.setLastError("SQL 执行失败");
        assertEquals(new DevBackfillServiceImpl.Outcome(DevBackfillItemDO.STATUS_FAILED, "SQL 执行失败"),
                DevBackfillServiceImpl.outcome(failed, false));
        assertEquals(DevBackfillItemDO.STATUS_FAILED,
                DevBackfillServiceImpl.outcome(record("STOPPED", "PENDING"), false).status());
        assertEquals(DevBackfillItemDO.STATUS_CANCELLED,
                DevBackfillServiceImpl.outcome(record("STOPPED", "PENDING"), true).status());
        assertEquals(DevBackfillItemDO.STATUS_FAILED, DevBackfillServiceImpl.outcome(null, false).status());
    }

    private static DevRecordDO record(String status, String quality) {
        return DevRecordDO.builder().status(status).qualityStatus(quality).build();
    }

    private static void assertRange(org.junit.jupiter.api.function.Executable executable) {
        ServiceException failure = assertThrows(ServiceException.class, executable);
        assertEquals(DEV_BACKFILL_RANGE_INVALID.getCode(), failure.getCode());
    }

}
