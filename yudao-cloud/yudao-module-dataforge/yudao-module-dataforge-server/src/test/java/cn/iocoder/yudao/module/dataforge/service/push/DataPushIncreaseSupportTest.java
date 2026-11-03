package cn.iocoder.yudao.module.dataforge.service.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 增量水位：(prevEnd, currentMax]
 */
class DataPushIncreaseSupportTest {

    @Test
    @DisplayName("全量语义：不计算水位；MAX 为空视为无需推送")
    void emptyMaxReturnsNull() {
        assertNull(DataPushIncreaseSupport.build(null, null));
        assertNull(DataPushIncreaseSupport.build("100", null));
    }

    @Test
    @DisplayName("增量首次：start 为空，end=MAX")
    void firstIncrementUsesOpenStart() {
        DataPushIncreaseSupport.Watermark watermark = DataPushIncreaseSupport.build(null, "2026-09-10");
        assertEquals(null, watermark.startVal());
        assertEquals("2026-09-10", watermark.endVal());
    }

    @Test
    @DisplayName("增量首次指定水位：区间为 (initStart, MAX]")
    void firstIncrementUsesConfiguredStart() {
        DataPushIncreaseSupport.Watermark watermark = DataPushIncreaseSupport.build("100", "180");
        assertEquals("100", watermark.startVal());
        assertEquals("180", watermark.endVal());
    }

    @Test
    @DisplayName("增量后续：区间为 (prevEnd, newMax]")
    void subsequentIncrementUsesPrevEnd() {
        DataPushIncreaseSupport.Watermark watermark = DataPushIncreaseSupport.build("100", "180");
        assertEquals("100", watermark.startVal());
        assertEquals("180", watermark.endVal());
    }

    @Test
    @DisplayName("失败不推进：重算时仍用上次成功 end，不会因为失败而改 prevEnd")
    void failedRunDoesNotAdvanceWatermark() {
        String lastSuccessEnd = "50";
        DataPushIncreaseSupport.Watermark retry = DataPushIncreaseSupport.build(lastSuccessEnd, "80");
        assertEquals("50", retry.startVal());
        assertEquals("80", retry.endVal());
    }

    @Test
    @DisplayName("水位未前进：区间为空，无需再推")
    void unchangedMaxReturnsNull() {
        assertNull(DataPushIncreaseSupport.build("180", "180"));
    }

    @Test
    @DisplayName("水位超长时拒绝写入")
    void rejectTooLongWatermark() {
        String tooLong = "x".repeat(257);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> DataPushIncreaseSupport.validateLength(null, tooLong, 256));
        DataPushIncreaseSupport.validateLength("1", "2", 256);
    }

}
